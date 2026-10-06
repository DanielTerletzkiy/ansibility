package de.terletzkiy.ansibility.vars

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.backend.documentation.DocumentationData
import com.intellij.psi.PsiManager
import com.intellij.util.indexing.FileBasedIndex
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.AnsibleSite
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.fixtures.InfraTestData
import de.terletzkiy.ansibility.fixtures.RequiresInfraFixture
import de.terletzkiy.ansibility.index.RootFamily
import de.terletzkiy.ansibility.index.VarDefIndex
import de.terletzkiy.ansibility.index.VarUseIndex
import java.util.concurrent.TimeUnit

/**
 * A sweep over the whole sanitised infra fixture: hover and Ctrl+B at every indexed variable reference and definition
 * of every normal root. No position may fail, no card may contain a vault payload or envelope, and no target may lie
 * outside the root's family or in the detached worktree.
 */
@RequiresInfraFixture
class VarFixtureSweepTest : VarsTestCase() {
    fun testEveryReferenceAndDefinitionOfTheFixture() {
        myFixture.copyDirectoryToProject(InfraTestData.INFRA, "")
        refreshRoots()
        val stats = ApplicationManager.getApplication().executeOnPooledThread<Stats> { runReadActionBlocking(::sweep) }.get(10, TimeUnit.MINUTES)
        println("VARS: fixture sweep: $stats")
        assertTrue(stats.problems.take(20).joinToString("\n"), stats.problems.isEmpty())
        assertTrue("references were classified: $stats", stats.references > 2000)
        assertTrue("keys were classified: $stats", stats.keys > 2000)
        assertTrue("cards were rendered: $stats", stats.cards > 4000)
    }

    class Stats {
        var positions = 0
        var references = 0
        var keys = 0
        var cards = 0
        var targets = 0
        val problems = ArrayList<String>()
        val times = ArrayList<Double>()

        override fun toString(): String {
            val sorted = times.sorted()
            val p95 = sorted.getOrNull((sorted.size * 95 + 99) / 100 - 1) ?: 0.0
            return "$positions positions, $references references, $keys keys, $cards cards, $targets targets, " +
                "card p95 ${"%.1f".format(p95)} ms, ${problems.size} problems"
        }
    }

    private fun sweep(): Stats {
        val stats = Stats()
        val workspace = AnsibleWorkspace.getInstance(project)
        val base = myFixture.tempDirFixture.getFile("")!!
        val index = FileBasedIndex.getInstance()
        val classifier = VarsSiteClassifier()
        val documentation = VarSiteDocumentation()
        val navigation = VarNavigation()
        VfsUtilCore.iterateChildrenRecursively(base, null) { file ->
            ProgressManager.checkCanceled()
            if (file.isDirectory) return@iterateChildrenRecursively true
            val context = workspace.contextOf(file) ?: return@iterateChildrenRecursively true
            if (context.root.detached) return@iterateChildrenRecursively true
            val psi = PsiManager.getInstance(project).findFile(file) ?: return@iterateChildrenRecursively true
            val offsets = sortedSetOf<Int>()
            index.getFileData(VarUseIndex.NAME, file, project).values.flatten().mapTo(offsets) { it.offset + 1 }
            index.getFileData(VarDefIndex.NAME, file, project).values.flatten().mapTo(offsets) { it.offset + 1 }
            val family = RootFamily.of(project, context.root, workspace)
            for (offset in offsets) {
                stats.positions++
                try {
                    val site = classifier.classify(psi, offset) ?: continue
                    when (site) {
                        is AnsibleSite.VarRef -> stats.references++
                        is AnsibleSite.VarKey -> stats.keys++
                        else -> continue
                    }
                    val start = System.nanoTime()
                    val target = documentation.documentation(site, psi)
                    val html = (target?.computeDocumentation() as? DocumentationData)?.html
                    stats.times += (System.nanoTime() - start) / 1_000_000.0
                    if (html != null) {
                        stats.cards++
                        target.computeDocumentationHint()
                        if (InfraTestData.containsVaultPayload(html) || "\$ANSIBLE_VAULT" in html) stats.problems += "vault content in the card at ${where(file, offset)}"
                    }
                    for (element in navigation.targets(site, psi)) {
                        stats.targets++
                        val location = (element as VarTargetElement).location
                        if (!admits(family, context.root, location.file)) stats.problems += "target ${location.file.path} outside ${context.root.displayName} from ${where(file, offset)}"
                    }
                } catch (e: ProcessCanceledException) {
                    throw e
                } catch (e: Throwable) {
                    stats.problems += "${e.javaClass.simpleName} at ${where(file, offset)}: ${e.message}"
                }
            }
            true
        }
        return stats
    }

    private fun admits(family: RootFamily, root: AnsibleRoot, file: VirtualFile): Boolean {
        if ("/.claude/worktrees/" in file.path) return false
        val context = AnsibleWorkspace.getInstance(project).contextOf(file) ?: return false
        return family.admits(file, context) && context.root.detached == root.detached
    }

    private fun where(file: VirtualFile, offset: Int): String {
        val base = myFixture.tempDirFixture.getFile("")!!
        return "${VfsUtilCore.getRelativePath(file, base)}:${VarLocations.line(file, offset)}"
    }
}
