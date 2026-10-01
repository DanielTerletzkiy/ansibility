package de.terletzkiy.ansibility.context.host

import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.RootKind
import de.terletzkiy.ansibility.context.RootDetector
import de.terletzkiy.ansibility.fixtures.InfraTestData
import de.terletzkiy.ansibility.model.effective.HostViews
import de.terletzkiy.ansibility.model.inventory.InventoryModels
import de.terletzkiy.ansibility.model.inventory.VarsConfig
import de.terletzkiy.ansibility.semantics.json.Json
import de.terletzkiy.ansibility.semantics.json.YValueJson
import java.io.File
import java.math.BigInteger
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Opt-in oracle (plan amendment R7/R8, M4.7 acceptance 10 and Testing additions §4) against the real infra repo,
 * read-only: set `ANSIBLE_INFRA_REPO` to its path.
 *
 * For every host of every environment, with the playbook dir of each root that evaluates it (the PROJECT root's
 * directory, and each nested danger-zone root's own directory, whose plays do not load `ansible/group_vars`), it runs
 * `ansible-inventory -i <env>/hosts.yml --playbook-dir <dir> --host <host>` from a temporary directory outside the
 * repo (stdin `/dev/null`, `ANSIBLE_CONFIG` = the root's `ansible.cfg`, no vault password source in the environment)
 * and compares the result with the inventory view behind `AnsibleContextService.inventoryView` ([HostViews]): the
 * same names and, for each, the same value. Only inventory-level variables are compared; vault values are compared as
 * `__ansible_vault` markers and never decrypted. Mismatch reports name the variable, never its value.
 *
 * The repo is never added to the test project (nothing of it is indexed); its roots come from [RootDetector]. Without
 * the variable, or without an `ansible-inventory` executable (`ANSIBLE_INVENTORY_BIN`, `PATH`, `/opt/homebrew/bin`,
 * `/usr/local/bin`), the test returns immediately.
 */
class HostContextOracleCorpusTest : BasePlatformTestCase() {
    fun testInventoryViewsMatchAnsibleInventoryHost() {
        val repoPath = System.getenv(InfraTestData.INFRA_REPO_ENV)?.takeIf { it.isNotBlank() } ?: return
        val binary = ansibleInventory() ?: run {
            println("HostContextOracleCorpusTest: no ansible-inventory executable found; skipped")
            return
        }
        VfsRootAccess.allowRootAccess(testRootDisposable, repoPath)
        val repo = LocalFileSystem.getInstance().refreshAndFindFileByPath(repoPath) ?: error("no repo at $repoPath")
        val roots = RootDetector(isExcluded = { VfsUtilCore.getRelativePath(it, repo) in setOf("patches", ".idea/dictionaries") })
            .detect(listOf(repo)).roots
            .filter { !it.detached && it.kind != RootKind.ROLE_LIBRARY }
        val views = runReadActionBlocking { roots.flatMap { root -> viewsOf(root) } }
        val temp = FileUtil.createTempDirectory("ansibility-host-oracle", null, true)
        val problems = ArrayList<String>()
        var variables = 0
        try {
            val pool = Executors.newFixedThreadPool(PARALLEL_RUNS)
            val results = try {
                views.map { view -> pool.submit(Callable { view to oracle(binary, view, temp) }) }.map { it.get(10, TimeUnit.MINUTES) }
            } finally {
                pool.shutdownNow()
            }
            for ((view, expected) in results) {
                if (expected == null) {
                    problems += "${view.label}: ansible-inventory failed"
                    continue
                }
                val ours = runReadActionBlocking {
                    HostViews.getInstance(project).view(view.root, view.environment, view.host, view.playbookDir)?.view?.values()
                }
                if (ours == null) {
                    problems += "${view.label}: no inventory view"
                    continue
                }
                (expected.keys - ours.keys).sorted().forEach { problems += "${view.label}: $it missing" }
                (ours.keys - expected.keys).sorted().forEach { problems += "${view.label}: $it extra" }
                for (name in expected.keys.intersect(ours.keys)) {
                    variables++
                    if (normalise(YValueJson.toJson(ours[name])) != normalise(expected[name])) problems += "${view.label}: $name differs"
                }
            }
        } finally {
            FileUtil.delete(temp)
        }
        val projectViews = views.count { it.root.kind == RootKind.PROJECT }
        val summary = "compared ${views.size} views ($projectViews PROJECT host views, ${views.size - projectViews} nested), $variables variables; ${problems.size} problems"
        println("HostContextOracleCorpusTest: $summary")
        problems.take(50).forEach { println("  $it") }
        assertEquals("PROJECT host views (research: 81 hosts)", 81, projectViews)
        assertEquals(summary, emptyList<String>(), problems)
    }

    /** One inventory view: a host of an environment of [root], evaluated with [playbookDir]. */
    private class View(val root: AnsibleRoot, val environment: String, val host: String, val hostsFile: VirtualFile, val playbookDir: VirtualFile) {
        val label: String get() = "${root.displayName}/$environment/$host @ ${playbookDir.name}"
    }

    /** Every host of [root]'s environments (a nested root's are its parent's), with [root]'s directory as playbook dir. */
    private fun viewsOf(root: AnsibleRoot): List<View> = InventoryModels.getInstance(project).environments(root).flatMap { environment ->
        environment.graph.hosts.keys.map { View(root, environment.name, it, environment.hostsFile, root.dir) }
    }

    /** The variables `ansible-inventory --host` prints for [view], or null when the run failed. */
    @Suppress("UNCHECKED_CAST")
    private fun oracle(binary: String, view: View, temp: File): Map<String, Any?>? {
        val name = view.label.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val out = File(temp, "$name.json")
        val err = File(temp, "$name.err")
        val command = listOf(binary, "-i", view.hostsFile.path, "--playbook-dir", view.playbookDir.path, "--host", view.host)
        val builder = ProcessBuilder(command)
            .directory(temp)
            .redirectInput(File("/dev/null"))
            .redirectOutput(out)
            .redirectError(err)
        builder.environment().apply {
            VarsConfig.cfgFile(view.root)?.let { put("ANSIBLE_CONFIG", it.path) }
            put("ANSIBLE_LOCAL_TEMP", File(temp, "local-tmp").path)
            put("ANSIBLE_NOCOLOR", "1")
            put("PYTHONDONTWRITEBYTECODE", "1")
            // Never hand ansible-inventory a vault secret: values stay envelopes, printed as markers.
            remove("ANSIBLE_VAULT_PASSWORD_FILE")
            remove("ANSIBLE_VAULT_IDENTITY_LIST")
            remove("ANSIBLE_VAULT_IDENTITY")
            remove("ANSIBLE_HOME")
        }
        val process = builder.start()
        if (!process.waitFor(5, TimeUnit.MINUTES)) {
            process.destroyForcibly()
            return null
        }
        if (process.exitValue() != 0) return null
        return Json.parse(out.readText()) as? Map<String, Any?>
    }

    /** Vault values become a marker; integers compare whatever their width. */
    private fun normalise(value: Any?): Any? = when (value) {
        is Map<*, *> -> if (value.keys == setOf("__ansible_vault")) VAULT else value.entries.associate { (k, v) -> k.toString() to normalise(v) }
        is List<*> -> value.map { normalise(it) }
        is Long -> BigInteger.valueOf(value)
        is Int -> BigInteger.valueOf(value.toLong())
        else -> value
    }

    private fun ansibleInventory(): String? {
        System.getenv("ANSIBLE_INVENTORY_BIN")?.takeIf { File(it).canExecute() }?.let { return it }
        val dirs = System.getenv("PATH").orEmpty().split(File.pathSeparator) + listOf("/opt/homebrew/bin", "/usr/local/bin")
        return dirs.map { File(it, "ansible-inventory") }.firstOrNull { it.canExecute() }?.path
    }

    private companion object {
        const val VAULT = "<vault>"
        const val PARALLEL_RUNS = 6
    }
}
