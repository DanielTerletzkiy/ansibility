package de.terletzkiy.ansibility.model.play

import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.ExtensionTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.terletzkiy.ansibility.api.AnsibleSite
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.CompletionSource
import de.terletzkiy.ansibility.api.PlayGraph
import de.terletzkiy.ansibility.api.RoleRegistry
import de.terletzkiy.ansibility.fixtures.InfraTestData
import de.terletzkiy.ansibility.fixtures.RequiresInfraFixture
import de.terletzkiy.ansibility.model.role.ModelFixture
import de.terletzkiy.ansibility.navigation.RefsCompletionSource
import java.util.Locale

/**
 * The cost of the first [PlayGraph] query and of the first `notify:` completion (which reads the play scope through
 * the graph) after an edit of a YAML file, on the whole golden library plus the falcon root of the fixture.
 *
 * Every round types into a YAML file (document change plus commit, as typing does) before measuring, so the graph
 * sees a PSI change each time; the file tree does not change between rounds. Results are printed; the budgets are far
 * above the measured values, so a slow build machine does not make them flaky. The opt-in
 * [PlayGraphCorpusTest.testFirstQueryAfterAnEditOnTheRealRepo] measures the same on the real repo.
 */
@RequiresInfraFixture
class PlayGraphTimingTest : BasePlatformTestCase() {
    override fun getTestDataPath(): String = ModelFixture.testDataPath

    private val graph: PlayGraph get() = PlayGraph.getInstance(project)

    /** Appends [text] to the document of [path] and commits it, as typing does. */
    private fun type(path: String, text: String) {
        WriteCommandAction.runWriteCommandAction(project) {
            val psi = ModelFixture.yaml(myFixture, path)
            val documents = PsiDocumentManager.getInstance(project)
            val document = documents.getDocument(psi)!!
            document.insertString(document.textLength, text)
            documents.commitDocument(document)
        }
    }

    fun testFirstQueryAfterAnEdit() {
        myFixture.copyDirectoryToProject(InfraTestData.INFRA, "")
        ModelFixture.rescan(project)
        val roots = AnsibleWorkspace.getInstance(project).roots().filter { !it.detached }
        val registry = RoleRegistry.getInstance(project)
        val golden = ModelFixture.root(myFixture, "golden")
        val falcon = ModelFixture.root(myFixture, "repos/falcon/ansible")
        // A narrow consumer (hover, notify completion) and a wide one (every role of every root, as AppliedRoles asks).
        val narrow = { graph.playsApplying(golden, "haproxy").size + graph.playsApplying(falcon, "haproxy").size }
        val wide = {
            roots.sumOf { root -> graph.playbooks(root).size + registry.roles(root).sumOf { role -> graph.playsApplying(root, role.name).size } }
        }
        val expectedNarrow = narrow()
        val expectedWide = wide()
        assertTrue("the fixture applies haproxy", expectedNarrow > 0)
        val narrowSamples = ArrayList<Long>()
        val wideSamples = ArrayList<Long>()
        repeat(ROUNDS) { round ->
            type(EDITED[round % EDITED.size], "# edit $round\n")
            var start = System.nanoTime()
            assertEquals(expectedNarrow, narrow())
            narrowSamples += (System.nanoTime() - start) / 1_000
            type(EDITED[(round + 1) % EDITED.size], "# edit $round\n")
            start = System.nanoTime()
            assertEquals(expectedWide, wide())
            wideSamples += (System.nanoTime() - start) / 1_000
        }
        val n = narrowSamples.sorted()
        val w = wideSamples.sorted()
        println(
            "PlayGraph first query after an edit on the whole fixture (${roots.size} roots, $ROUNDS rounds): " +
                "narrow p50 ${ms(n, 50)} ms, p95 ${ms(n, 95)} ms, max ${ms(n, 100)} ms; " +
                "every role of every root p50 ${ms(w, 50)} ms, p95 ${ms(w, 95)} ms, max ${ms(w, 100)} ms",
        )
        assertTrue("narrow p95 ${ms(n, 95)} ms", percentile(n, 95) < QUERY_BUDGET_MICROS)
        assertTrue("wide p95 ${ms(w, 95)} ms", percentile(w, 95) < QUERY_BUDGET_MICROS)
    }

    fun testFirstNotifyCompletionAfterAnEdit() {
        val settings = CodeInsightSettings.getInstance()
        val autoInsert = settings.AUTOCOMPLETE_ON_CODE_COMPLETION
        settings.AUTOCOMPLETE_ON_CODE_COMPLETION = false
        try {
            measureCompletion()
        } finally {
            settings.AUTOCOMPLETE_ON_CODE_COMPLETION = autoInsert
        }
    }

    private fun measureCompletion() {
        val timing = TimingSource(RefsCompletionSource())
        val sources = CompletionSource.EP_NAME.extensionList.map { if (it is RefsCompletionSource) timing else it }
        ExtensionTestUtil.maskExtensions(CompletionSource.EP_NAME, sources, testRootDisposable)
        ModelFixture.copyInfra(myFixture, "golden", "repos/falcon")
        val text = "- ansible.builtin.template:\n    src: templates/haproxy.cfg.j2\n    dest: /etc/haproxy/haproxy.cfg\n  notify: Re"
        val samples = ArrayList<Long>()
        val whole = ArrayList<Long>()
        // One file per round (the test editor slows down after many completions in one editor), all created up front:
        // a new file changes the file tree, and the rounds measure the first completion after an edit, not after that.
        val paths = List(ROUNDS) { round -> "golden/roles/haproxy/tasks/timing$round.yml" }
        for (path in paths) myFixture.tempDirFixture.createFile(path, text)
        ModelFixture.rescan(project)
        repeat(ROUNDS) { round ->
            val path = paths[round]
            myFixture.configureFromTempProjectFile(path)
            myFixture.editor.caretModel.moveToOffset(text.length)
            if (round == 0) {
                myFixture.completeBasic()
                myFixture.lookup?.hideLookup(true)
            }
            type(EDITED[round % EDITED.size], "# edit $round\n")
            timing.recording = true
            val start = System.nanoTime()
            myFixture.completeBasic()
            whole += (System.nanoTime() - start) / 1_000
            timing.recording = false
            assertTrue(myFixture.lookupElementStrings.orEmpty().toString(), "Reload haproxy" in myFixture.lookupElementStrings.orEmpty())
            myFixture.lookup?.hideLookup(true)
        }
        samples += timing.samples
        val source = samples.sorted()
        val total = whole.sorted()
        println(
            "First notify completion after an edit ($ROUNDS rounds): source p50 ${ms(source, 50)} ms, p95 ${ms(source, 95)} ms, " +
                "max ${ms(source, 100)} ms; whole completeBasic p50 ${ms(total, 50)} ms, p95 ${ms(total, 95)} ms",
        )
        assertTrue("source p95 ${ms(source, 95)} ms", percentile(source, 95) < COMPLETION_BUDGET_MICROS)
    }

    /** Wraps a source and records the microseconds each call takes while [recording]. */
    private class TimingSource(private val delegate: CompletionSource) : CompletionSource {
        val samples = ArrayList<Long>()
        var recording = false

        override fun complete(site: AnsibleSite?, parameters: CompletionParameters, result: CompletionResultSet) {
            val start = System.nanoTime()
            try {
                delegate.complete(site, parameters, result)
            } finally {
                if (recording) samples += (System.nanoTime() - start) / 1_000
            }
        }
    }

    private fun percentile(sorted: List<Long>, p: Int): Long = sorted[((sorted.size * p) / 100).coerceAtMost(sorted.lastIndex)]

    private fun ms(sorted: List<Long>, p: Int): String = "%.1f".format(Locale.ROOT, percentile(sorted, p) / 1000.0)

    private companion object {
        const val ROUNDS = 30

        /** Generous bounds (microseconds): the incremental graph measures a few milliseconds. */
        const val QUERY_BUDGET_MICROS = 500_000L
        const val COMPLETION_BUDGET_MICROS = 500_000L

        /** Files typed into between measurements: a role task file, a vars file and a playbook. */
        val EDITED = listOf(
            "golden/roles/haproxy/defaults/main.yml",
            "repos/falcon/ansible/environments/prod/group_vars/all/vars.yml",
            "golden/roles/grafana/tasks/main.yml",
        )
    }
}
