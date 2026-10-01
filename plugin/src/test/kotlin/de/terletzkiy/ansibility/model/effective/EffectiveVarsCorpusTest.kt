package de.terletzkiy.ansibility.model.effective

import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.EffectiveVarsService
import de.terletzkiy.ansibility.api.InventoryService
import de.terletzkiy.ansibility.api.RootKind
import de.terletzkiy.ansibility.api.VarSourceRef
import de.terletzkiy.ansibility.context.RootDetector
import de.terletzkiy.ansibility.fixtures.InfraTestData
import de.terletzkiy.ansibility.model.inventory.VarsConfig
import de.terletzkiy.ansibility.model.inventory.VarsDocuments
import de.terletzkiy.ansibility.semantics.json.Json
import de.terletzkiy.ansibility.semantics.json.YValueJson
import de.terletzkiy.ansibility.semantics.yaml.YMap
import de.terletzkiy.ansibility.semantics.yaml.YSeq
import de.terletzkiy.ansibility.semantics.yaml.YValue
import java.io.File
import java.math.BigInteger
import java.util.concurrent.TimeUnit

/**
 * Opt-in oracle check against the real infra repo (read-only): set `ANSIBLE_INFRA_REPO` to its path. For every
 * environment of every PROJECT root it runs `ansible-inventory -i <env>/hosts.yml --playbook-dir <root> --list`,
 * the same without `--playbook-dir`, and for the nested danger-zone roots with their own directory as playbook dir,
 * from a temporary directory outside the repo (stdin `/dev/null`, `ANSIBLE_CONFIG` = the root's `ansible.cfg`,
 * local temp and output in the temporary directory, no byte code written) and compares every host's variables with
 * [EffectiveVarsService.inventoryView]: the same names, and for each the winner's value. Vault values are compared
 * as markers; mismatch reports name the variable, never its value.
 *
 * Without the variable, or without an `ansible-inventory` executable (`ANSIBLE_INVENTORY_BIN`, `PATH`,
 * `/opt/homebrew/bin`, `/usr/local/bin`), the test returns immediately.
 */
class EffectiveVarsCorpusTest : BasePlatformTestCase() {
    fun testInventoryViewsMatchAnsibleInventory() {
        val repoPath = System.getenv(InfraTestData.INFRA_REPO_ENV)?.takeIf { it.isNotBlank() } ?: return
        val binary = ansibleInventory() ?: run {
            println("EffectiveVarsCorpusTest: no ansible-inventory executable found; skipped")
            return
        }
        VfsRootAccess.allowRootAccess(testRootDisposable, repoPath)
        val repo = LocalFileSystem.getInstance().refreshAndFindFileByPath(repoPath) ?: error("no repo at $repoPath")
        val roots = RootDetector(isExcluded = { VfsUtilCore.getRelativePath(it, repo) in setOf("patches", ".idea/dictionaries") })
            .detect(listOf(repo)).roots
            .filter { !it.detached }
        // Every PROJECT environment with and without the root as playbook dir; nested roots with their own dir.
        val cases = roots.filter { it.kind == RootKind.PROJECT }.flatMap { listOf(Case(it, it.dir), Case(it, null)) } +
            roots.filter { it.kind == RootKind.NESTED_PLAYBOOK }.map { Case(it, it.dir) }
        val temp = FileUtil.createTempDirectory("ansibility-inventory-oracle", null, true)
        val problems = ArrayList<String>()
        val environments = HashMap<String, Int>()
        var hosts = 0
        var variables = 0
        try {
            for (case in cases) {
                for (inventory in InventoryService.getInstance(project).inventories(case.root)) {
                    val mode = if (case.playbookDir == null) "no playbook dir" else case.root.kind.name
                    environments.merge(mode, 1, Int::plus)
                    val label = "${case.root.displayName}/${inventory.environment}" + if (case.playbookDir == null) " (no playbook dir)" else ""
                    val expected = oracle(binary, case, inventory.hostsFile, temp, label)
                    if (expected == null) {
                        problems += "$label: ansible-inventory failed"
                        continue
                    }
                    for ((host, oracleVars) in expected) {
                        hosts++
                        val view = EffectiveVarsService.getInstance(project).inventoryView(case.root, inventory.environment, host, case.playbookDir)
                        if (view == null) {
                            problems += "$label/$host: no inventory view"
                            continue
                        }
                        val ours = view.vars.associateBy { it.name }
                        (oracleVars.keys - ours.keys).sorted().forEach { problems += "$label/$host: $it missing" }
                        (ours.keys - oracleVars.keys).sorted().forEach { problems += "$label/$host: $it extra" }
                        for (name in oracleVars.keys.intersect(ours.keys)) {
                            variables++
                            val winner = ours.getValue(name).winner
                            val value = valueAt(winner, name)
                            when {
                                value == null -> problems += "$label/$host: $name value not found at ${winner.file.name}"
                                normalise(YValueJson.toJson(value)) != normalise(oracleVars[name]) ->
                                    problems += "$label/$host: $name differs (winner ${winner.layer} ${winner.file.name})"
                            }
                        }
                    }
                }
            }
        } finally {
            FileUtil.delete(temp)
        }
        val summary = "compared ${environments.entries.sortedBy { it.key }.joinToString { "${it.value} ${it.key}" }} environment runs, " +
            "$hosts hosts, $variables variables; ${problems.size} problems"
        println("EffectiveVarsCorpusTest: $summary")
        problems.take(50).forEach { println("  $it") }
        assertEquals("inventories (research: 32)", 32, environments["PROJECT"])
        assertEquals(summary, emptyList<String>(), problems)
    }

    /** One oracle run: a root's environments evaluated with [playbookDir] (none: no `--playbook-dir`). */
    private data class Case(val root: AnsibleRoot, val playbookDir: VirtualFile?)

    /** `_meta.hostvars` of `ansible-inventory --list`, or null when the run failed. */
    @Suppress("UNCHECKED_CAST")
    private fun oracle(binary: String, case: Case, hostsFile: VirtualFile, temp: File, label: String): Map<String, Map<String, Any?>>? {
        val out = File(temp, label.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".json")
        val err = File(temp, out.name + ".err")
        val command = listOf(binary, "-i", hostsFile.path) + listOfNotNull(case.playbookDir?.let { "--playbook-dir" }, case.playbookDir?.path) + "--list"
        val builder = ProcessBuilder(command)
            .directory(temp)
            .redirectInput(File("/dev/null"))
            .redirectOutput(out)
            .redirectError(err)
        builder.environment().apply {
            VarsConfig.cfgFile(case.root)?.let { put("ANSIBLE_CONFIG", it.path) }
            put("ANSIBLE_LOCAL_TEMP", File(temp, "local-tmp").path)
            put("ANSIBLE_NOCOLOR", "1")
            put("PYTHONDONTWRITEBYTECODE", "1")
            remove("ANSIBLE_HOME")
        }
        val process = builder.start()
        if (!process.waitFor(120, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return null
        }
        if (process.exitValue() != 0) return null
        val document = Json.parse(out.readText()) as? Map<String, Any?> ?: return null
        return (document["_meta"] as? Map<String, Any?>)?.get("hostvars") as? Map<String, Map<String, Any?>>
    }

    /** The value of [name] whose key starts at the winner's offset, searched through the whole document. */
    private fun valueAt(ref: VarSourceRef, name: String): YValue? {
        fun find(value: YValue?): YValue? = when (value) {
            is YMap -> value.entries.firstOrNull { it.key.text == name && it.key.range?.start == ref.offset }?.value
                ?: value.entries.firstNotNullOfOrNull { find(it.value) }
            is YSeq -> value.items.firstNotNullOfOrNull { find(it) }
            else -> null
        }
        return find(VarsDocuments.load(project, ref.file))
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
    }
}
