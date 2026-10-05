package de.terletzkiy.ansibility.vars.usages

import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.usages.UsageInfo2UsageAdapter
import java.util.Locale

/**
 * F1.10 acceptance 7 (warm): Show Usages on a variable with 300 uses on the fixture under 1 s (the search our handler
 * runs for the popup, and the row presentations the popup renders), and the caret highlighting under 20 ms per file,
 * also on the first caret event after a keystroke.
 * `system_networking_main_ip` in `repos/falcon` (90 uses, 4 declaring roles, every environment) gets a few more task files
 * that read it, so the search crosses 300 uses.
 *
 * The build machine may be busy (parallel builds): a series over budget is measured again, the best one counts.
 */
class VarUsagesPerformanceTest : UsagesTestCase() {

    override fun setUp() {
        super.setUp()
        copyInfra("repos/falcon")
        repeat(EXTRA_FILES) { index ->
            val uses = (1..USES_PER_LINE).joinToString(" ") { "{{ $NAME }}" }
            val tasks = (1..TASKS_PER_FILE).joinToString("") { task ->
                "- name: Probe $index.$task\n  ansible.builtin.debug:\n    msg: \"$uses\"\n  when: $NAME | length > 0\n"
            }
            myFixture.tempDirFixture.createFile("repos/falcon/ansible/roles/haproxy/tasks/perf-$index.yml", "---\n$tasks")
        }
        refreshRoots()
    }

    fun testShowUsagesWithThreeHundredUsesIsUnderASecondWarm() {
        at(TEMPLATE, 48, NAME)
        val symbol = targetAtCaret()!!
        val count = showUsages(symbol)
        assertTrue("at least 300 usages: $count", count >= 300)
        showUsages(symbol)

        var best = Long.MAX_VALUE
        for (series in 1..MAX_SERIES) {
            val samples = (1..ROUNDS).map { timed { showUsages(symbol) } }.sorted()
            best = minOf(best, samples[samples.size / 2])
            println(String.format(Locale.ROOT, "Show Usages %s (%d usages), series %d: median %d ms, max %d ms", NAME, count, series, samples[samples.size / 2], samples.last()))
            if (best < SHOW_USAGES_BUDGET_MS) break
        }
        assertTrue("median $best ms (budget $SHOW_USAGES_BUDGET_MS ms)", best < SHOW_USAGES_BUDGET_MS)
    }

    fun testCaretHighlightingIsUnderTwentyMillisecondsPerFile() {
        val files = listOf(TEMPLATE to 48, "repos/falcon/ansible/roles/haproxy/tasks/perf-0.yml" to 4, "repos/falcon/ansible/roles/haproxy/templates/haproxy.cfg.j2" to null)
        for ((path, line) in files) {
            val offset = if (line != null) at(path, line, NAME) else firstUse(path)
            assertNotNull("a handler in $path", highlightHandlerAtCaret())
            var best = Long.MAX_VALUE
            for (series in 1..MAX_SERIES) {
                val samples = (1..ROUNDS).map { timed { highlightHandlerAtCaret() } }.sorted()
                best = minOf(best, samples[samples.size / 2])
                if (best < HIGHLIGHT_BUDGET_MS) break
            }
            val ranges = highlightHandlerAtCaret()!!.readUsages.size
            println(String.format(Locale.ROOT, "Caret highlighting %s at %d (%d reads): median %d ms", path, offset, ranges, best))
            assertTrue("$path: median $best ms (budget $HIGHLIGHT_BUDGET_MS ms)", best < HIGHLIGHT_BUDGET_MS)
        }
    }

    /**
     * After every keystroke the first caret highlighting recomputes the open file's own entries (never the indexes'):
     * on the 900-line keycloak vars file, at a use and at a key, under 20 ms.
     */
    fun testCaretHighlightingAfterAKeystrokeIsUnderTwentyMilliseconds() {
        myFixture.configureFromTempProjectFile(KEYCLOAK)
        for (marker in listOf("vault_keycloak_admin_password", "keycloak_admin_password:")) {
            hostEditor().caretModel.moveToOffset(hostEditor().document.text.indexOf(marker) + 1)
            assertInstanceOf(highlightHandlerAtCaret(), VarHighlightUsagesHandler::class.java)
            var best = Long.MAX_VALUE
            for (series in 1..MAX_SERIES) {
                val samples = (1..ROUNDS).map { round ->
                    WriteCommandAction.runWriteCommandAction(project) {
                        val document = hostEditor().document
                        document.insertString(document.textLength, "# keystroke $series.$round\n")
                        PsiDocumentManager.getInstance(project).commitDocument(document)
                    }
                    timed { highlightHandlerAtCaret() }
                }.sorted()
                best = minOf(best, samples[samples.size / 2])
                if (best < HIGHLIGHT_BUDGET_MS) break
            }
            println(String.format(Locale.ROOT, "Caret highlighting after a keystroke %s at %s: median %d ms", KEYCLOAK, marker, best))
            assertTrue("$marker: median $best ms (budget $HIGHLIGHT_BUDGET_MS ms)", best < HIGHLIGHT_BUDGET_MS)
        }
    }

    /** The search behind Show Usages (our handler, on a pooled thread) and every row's presentation; the number of usages. */
    private fun showUsages(symbol: VarSymbolElement): Int {
        val infos = findUsagesOf(symbol)
        runReadActionBlocking { infos.forEach { UsageInfo2UsageAdapter(it).presentation.text } }
        return infos.size
    }

    private fun firstUse(path: String): Int {
        myFixture.configureFromTempProjectFile(path)
        val offset = myFixture.editor.document.text.indexOf(NAME) + 1
        hostEditor().caretModel.moveToOffset(offset)
        return offset
    }

    private fun timed(action: () -> Unit): Long {
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        val start = System.nanoTime()
        action()
        return (System.nanoTime() - start) / 1_000_000
    }

    private companion object {
        const val NAME = "system_networking_main_ip"
        const val TEMPLATE = "repos/falcon/ansible/roles/grafana/templates/nginx/main.site.conf.j2"
        const val KEYCLOAK = "repos/falcon/ansible/environments/prod/group_vars/keycloak/vars.yml"
        const val EXTRA_FILES = 7
        const val TASKS_PER_FILE = 3
        const val USES_PER_LINE = 9
        const val ROUNDS = 7
        const val MAX_SERIES = 3
        const val SHOW_USAGES_BUDGET_MS = 1000L
        const val HIGHLIGHT_BUDGET_MS = 20L
    }
}
