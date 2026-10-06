package de.terletzkiy.ansibility.inspections.modules

import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import de.terletzkiy.ansibility.fixtures.RequiresInfraFixture

/**
 * Plan "Testing strategy" 7 (full highlighting of the ~900-line
 * `repos/falcon/ansible/environments/prod/group_vars/keycloak/vars.yml` < 300 ms): what the six module option and
 * keyword inspections add to it, and to the largest task-like file of the fixture
 * (`golden/roles/postfix/molecule/default/verify.yml`, 592 lines), where they do their full work. Each measured run
 * follows an edit, so the cached analysis is recomputed. The inspections must stay within a tenth of the budget; the
 * full highlighting time (all of the plugin's passes, on a shared test machine) is printed for the report.
 */
@RequiresInfraFixture
class ModuleChecksPerformanceTest : ModuleChecksTestCase() {

    fun testKeycloakVarsFileHighlightsWithinBudget() {
        copyInfra("repos/falcon")
        assertWithinBudget("repos/falcon/ansible/environments/prod/group_vars/keycloak/vars.yml")
    }

    fun testLargestTaskFileHighlightsWithinBudget() {
        copyInfra("golden/roles/postfix")
        assertWithinBudget("golden/roles/postfix/molecule/default/verify.yml")
    }

    private fun assertWithinBudget(path: String) {
        myFixture.configureFromTempProjectFile(path)
        repeat(3) { edited { myFixture.doHighlighting() } } // warm up the snapshots, indexes and the JIT
        val highlighting = (1..7).map { edited { myFixture.doHighlighting() } }.sorted()
        val inspections = (1..7).map { edited { runReadActionBlocking { check(myFixture.file.virtualFile) } } }.sorted()
        val ours = inspections[inspections.size / 2]
        println("$path: full highlighting median ${highlighting[highlighting.size / 2]} ms (runs $highlighting); module option and keyword inspections median $ours ms (runs $inspections)")
        assertTrue("$path: the module option and keyword inspections take $ours ms", ours < BUDGET_MS / 10)
    }

    /** Appends and removes a comment line (a real edit, which drops cached analyses), then times [block] in ms. */
    private fun edited(block: () -> Unit): Long {
        val document = myFixture.editor.document
        WriteCommandAction.runWriteCommandAction(project) { document.insertString(document.textLength, "# probe\n") }
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        val start = System.nanoTime()
        block()
        val elapsed = (System.nanoTime() - start) / 1_000_000
        WriteCommandAction.runWriteCommandAction(project) { document.deleteString(document.textLength - "# probe\n".length, document.textLength) }
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        runReadActionBlocking { PsiManager.getInstance(project).findFile(myFixture.file.virtualFile) }
        return elapsed
    }

    private companion object {
        const val BUDGET_MS = 300L
    }
}
