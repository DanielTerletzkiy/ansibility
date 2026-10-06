package de.terletzkiy.ansibility.layout

import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.context.AnsibleWorkspaceImpl
import de.terletzkiy.ansibility.context.ContextPresentation
import de.terletzkiy.ansibility.context.TargetVersionDetector
import de.terletzkiy.ansibility.fixtures.InfraTestData
import de.terletzkiy.ansibility.fixtures.RequiresInfraFixture
import de.terletzkiy.ansibility.model.effective.HostViews
import de.terletzkiy.ansibility.model.inventory.InventoryModels
import java.nio.file.Files
import java.security.MessageDigest

/**
 * The R10 baseline (plan amendment R10, R10-0): on the infra fixture, which uses only the `environments/<env>/hosts.yml`
 * convention, every root (kind, roles dirs, environments dir, label), every file's [de.terletzkiy.ansibility.api.FileContext],
 * every environment with its hosts and groups, a digest of every host's inventory view (names, winning and shadowed
 * sources with layer and key offset; never a value) and the status-bar text are exactly what they were before R10.
 *
 * The snapshot is `testData/layout/baseline/snapshot.txt`. It is written when missing, or when the system property
 * `ansibility.layout.updateBaseline` is set; otherwise any difference fails the test with the first differing lines.
 */
@RequiresInfraFixture
class LayoutBaselineTest : BasePlatformTestCase() {
    override fun getTestDataPath(): String = InfraTestData.testDataPath.toString()

    fun testTheConventionLayoutIsUnchanged() {
        myFixture.copyDirectoryToProject(InfraTestData.INFRA, "")
        AnsibleWorkspaceImpl.getInstance(project)!!.structureChanged()
        val actual = runReadActionBlocking { snapshot() }
        val file = InfraTestData.testDataPath.resolve("layout/baseline/snapshot.txt")
        if (!Files.exists(file) || System.getProperty(UPDATE) != null) {
            Files.createDirectories(file.parent)
            Files.writeString(file, actual.joinToString("\n", postfix = "\n"))
            return
        }
        val expected = Files.readAllLines(file)
        if (expected == actual) return
        val missing = expected.filter { it !in actual.toSet() }.take(15)
        val extra = actual.filter { it !in expected.toSet() }.take(15)
        fail("the convention layout changed (${expected.size} → ${actual.size} lines)\nonly in the baseline:\n${missing.joinToString("\n")}\nonly now:\n${extra.joinToString("\n")}")
    }

    private fun snapshot(): List<String> {
        val base = myFixture.tempDirFixture.findOrCreateDir(".")
        fun rel(file: VirtualFile?): String = file?.let { VfsUtilCore.getRelativePath(it, base) ?: it.path } ?: "-"
        val workspace = AnsibleWorkspace.getInstance(project)
        val lines = ArrayList<String>()
        val roots = workspace.roots()
        for (root in roots) {
            lines += "root ${rel(root.dir)} ${root.kind} detached=${root.detached} parent=${rel(root.parentDir)} " +
                "roles=${root.rolesDirs.map(::rel)} env=${rel(root.environmentsDir)} name=${root.displayName}"
            lines += "status ${rel(root.dir)} ${ContextPresentation.statusText(root, TargetVersionDetector.getInstance(project).targetVersion(root))}"
            for (env in InventoryModels.getInstance(project).environments(root)) {
                lines += "env ${rel(root.dir)} ${env.name} dir=${rel(env.dir)} hosts=${rel(env.hostsFile)} " +
                    "varFiles=${env.inventory.varFiles.size}"
                for (group in env.graph.groups.values.sortedBy { it.name }) {
                    lines += "group ${rel(root.dir)} ${env.name} ${group.name} parents=${group.parents.sorted()} hosts=${group.hosts.sorted()}"
                }
                val views = HostViews.getInstance(project).views(root, env.name, null)
                for (host in env.graph.hosts.keys.sorted()) {
                    val view = views?.get(host)?.view
                    val digest = view?.vars?.values?.sortedBy { it.name }?.joinToString("|") { v ->
                        (listOf(v.winner) + v.shadowed).joinToString(",") { s -> "${s.name}@${relUrl(s.source.originId, base)}:${s.source.layer}:${s.keyRange?.start}" }
                    }
                    lines += "view ${rel(root.dir)} ${env.name} $host groups=${view?.groups} ${digest?.let(::sha)}"
                }
            }
        }
        val files = ArrayList<VirtualFile>()
        VfsUtilCore.iterateChildrenRecursively(base, null) { file -> if (!file.isDirectory) files += file; true }
        for (file in files.sortedBy { it.path }) {
            val c = workspace.contextOf(file) ?: continue
            lines += "file ${rel(file)} root=${rel(c.root.dir)} ${c.kind} role=${c.roleName} env=${c.environment} " +
                "group=${c.group} host=${c.host} layer=${c.layer} scenario=${rel(c.moleculeScenarioDir)}"
        }
        return lines
    }

    private fun relUrl(url: String, base: VirtualFile): String = url.removePrefix(base.url).removePrefix("/")

    private fun sha(text: String): String =
        MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)

    private companion object {
        const val UPDATE = "ansibility.layout.updateBaseline"
    }
}
