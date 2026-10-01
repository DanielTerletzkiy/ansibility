package de.terletzkiy.ansibility.completion.jinja

import com.intellij.codeInsight.completion.impl.CamelHumpMatcher
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.psi.PsiManager
import com.intellij.util.indexing.FileBasedIndex
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.JinjaContainer
import de.terletzkiy.ansibility.fixtures.InfraTestData
import de.terletzkiy.ansibility.index.VarUseIndex
import de.terletzkiy.ansibility.lang.jinja.lexer.JinjaLexMode
import de.terletzkiy.ansibility.vars.JinjaTextSites
import java.util.concurrent.TimeUnit

/**
 * A sweep over the whole sanitised infra fixture: at every free variable reference of every normal root the first
 * three characters are completed, and at every `name.attr` reference the first two characters of the attribute. The
 * referenced name must be offered almost everywhere (it is defined somewhere the tiers see); nothing may fail; the p95
 * of one completion pass (position, scope, candidates) is reported and bounded.
 */
class JinjaCompletionFixtureSweepTest : JinjaCompletionTestCase() {
    fun testEveryFreeReferenceOfTheFixture() {
        myFixture.copyDirectoryToProject(InfraTestData.INFRA, "")
        refreshRoots()
        val stats = com.intellij.openapi.application.ApplicationManager.getApplication()
            .executeOnPooledThread<Stats> { runReadActionBlocking(::sweep) }.get(15, TimeUnit.MINUTES)
        println("JINJACOMP: fixture sweep: $stats")
        stats.nameMisses.groupingBy { it.substringBefore(' ') }.eachCount().forEach { (kind, count) -> println("JINJACOMP: name misses in $kind: $count") }
        stats.nameMisses.groupBy { it.substringBefore(' ') }.forEach { (_, misses) -> misses.take(12).forEach { println("JINJACOMP: name miss $it") } }
        stats.noPositionAt.take(10).forEach { println("JINJACOMP: no position for $it") }
        stats.memberMisses.take(15).forEach { println("JINJACOMP: member miss $it") }
        assertTrue(stats.problems.take(20).joinToString("\n"), stats.problems.isEmpty())
        assertTrue("references were completed: $stats", stats.names > 3000)
        assertTrue("every name the root defines is offered: $stats", stats.nameMisses.size <= stats.names / 100)
        assertTrue("item members offered: $stats", stats.itemMemberHits >= stats.itemMembers * 0.9)
        assertEquals("fact keys offered: $stats", stats.factMembers, stats.factMemberHits)
        assertTrue("completion p95 on the fixture: $stats", stats.p95() < P95_BUDGET_MS)
    }

    class Stats {
        var names = 0
        var nameHits = 0
        var members = 0
        var memberHits = 0
        var itemMembers = 0
        var itemMemberHits = 0
        var factMembers = 0
        var factMemberHits = 0

        /** Misses of names nothing in the root defines, declares or sets (undefined in the fixture, vault-file keys). */
        var undefined = 0
        var noPosition = 0
        val nameMisses = ArrayList<String>()
        val noPositionAt = ArrayList<String>()
        val memberMisses = ArrayList<String>()
        val problems = ArrayList<String>()
        val times = ArrayList<Double>()

        fun p95(): Double = times.sorted().let { it.getOrNull((it.size * 95 + 99) / 100 - 1) ?: 0.0 }

        fun p50(): Double = times.sorted().let { it.getOrNull(it.size / 2) ?: 0.0 }

        override fun toString(): String =
            "$names names ($nameHits offered, $undefined undefined in the root, ${nameMisses.size} missed), " +
                "$members members ($memberHits offered; item $itemMemberHits/$itemMembers, ansible_facts $factMemberHits/$factMembers), " +
                "$noPosition without position, ${times.size} passes, p50 ${"%.1f".format(p50())} ms, p95 ${"%.1f".format(p95())} ms, " +
                "max ${"%.1f".format(times.maxOrNull() ?: 0.0)} ms, ${problems.size} problems"
    }

    private fun sweep(): Stats {
        val stats = Stats()
        val workspace = AnsibleWorkspace.getInstance(project)
        val base = myFixture.tempDirFixture.getFile("")!!
        val index = FileBasedIndex.getInstance()
        VfsUtilCore.iterateChildrenRecursively(base, null) { file ->
            ProgressManager.checkCanceled()
            if (file.isDirectory) return@iterateChildrenRecursively true
            val context = workspace.contextOf(file) ?: return@iterateChildrenRecursively true
            if (context.root.detached) return@iterateChildrenRecursively true
            val psi = PsiManager.getInstance(project).findFile(file) ?: return@iterateChildrenRecursively true
            val text = psi.viewProvider.contents
            for ((name, entries) in index.getFileData(VarUseIndex.NAME, file, project)) {
                for (entry in entries) {
                    if (entry.called) continue
                    val where = "${file.path.substringAfter("/src/")}:${StringUtil.offsetToLineNumber(text, entry.offset) + 1}"
                    try {
                        val prefix = name.take(3)
                        val offered = completeAt(psi, entry.offset + prefix.length, prefix, stats)
                        if (offered == null) {
                            stats.noPositionAt += "$name at $where"
                            continue
                        }
                        stats.names++
                        when {
                            name in offered -> stats.nameHits++
                            isUndefined(context.root, name) -> stats.undefined++
                            else -> stats.nameMisses += "${context.kind} $name at $where"
                        }
                        val attr = entry.attrPath.firstOrNull() ?: continue
                        val dot = entry.offset + name.length
                        if (dot >= text.length || text[dot] != '.' || !text.startsWith(attr, dot + 1)) continue
                        val attrPrefix = attr.take(2)
                        val members = completeAt(psi, dot + 1 + attrPrefix.length, attrPrefix, stats) ?: continue
                        stats.members++
                        val hit = attr in members
                        if (hit) stats.memberHits++
                        when {
                            // molecule Dockerfile.j2 files type `item` as the molecule platform (X77, M5), not as a role loop item
                            name == "item" && "/molecule/" in file.path -> Unit
                            name == "item" -> {
                                stats.itemMembers++
                                if (hit) stats.itemMemberHits++ else stats.memberMisses += "$name.$attr at $where"
                            }
                            name == JinjaMembers.ANSIBLE_FACTS -> {
                                stats.factMembers++
                                if (hit) stats.factMemberHits++ else stats.memberMisses += "$name.$attr at $where"
                            }
                        }
                    } catch (e: ProcessCanceledException) {
                        throw e
                    } catch (e: Throwable) {
                        stats.problems += "$where: $name: $e"
                    }
                }
            }
            true
        }
        return stats
    }

    /**
     * True when nothing in [root] defines, declares or sets [name]: it has no definition in the index and is no loop
     * variable, special variable or fact (so no tier can offer it; e.g. keys of whole-file vaults).
     */
    private fun isUndefined(root: de.terletzkiy.ansibility.api.AnsibleRoot, name: String): Boolean {
        val symbol = de.terletzkiy.ansibility.api.VarService.getInstance(project).symbol(root, name)
        return symbol.definitions.isEmpty() && symbol.specBindings.isEmpty()
    }

    /** The lookup strings one completion pass offers at [caret] of [psi] for [prefix]; null without a position. */
    private fun completeAt(psi: com.intellij.psi.PsiFile, caret: Int, prefix: String, stats: Stats): Set<String>? {
        val start = System.nanoTime()
        val analysis = JinjaTextSites.analysisAt(psi, caret)
        val mode = if (analysis?.container == JinjaContainer.YAML_EXPRESSION) JinjaLexMode.EXPRESSION else JinjaLexMode.TEMPLATE
        val position = analysis?.let { JinjaCompletionPosition.at(it.text, it.textOffset, mode) }
        if (analysis == null || position == null || position.prefix != prefix) {
            stats.noPosition++
            return null
        }
        val scope = JinjaCompletionScope.create(psi, caret, analysis) ?: return null
        val matcher = CamelHumpMatcher(prefix)
        val result = JinjaVarCompletionSource.candidates(scope, position) { matcher.prefixMatches(it) }.mapTo(HashSet()) { it.lookupString }
        stats.times += (System.nanoTime() - start) / 1e6
        return result
    }

    private companion object {
        /** The plan's completion target is p95 < 100 ms; the sweep's bound leaves room for a loaded CI machine. */
        const val P95_BUDGET_MS = 100.0
    }
}
