package de.terletzkiy.ansibility.types

import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiDocumentManager
import de.terletzkiy.ansibility.api.TypeCheckService
import kotlin.system.measureNanoTime

/**
 * Performance budget (plan "Testing strategy" 7): full highlighting of the ~900-line
 * `repos/falcon/ansible/environments/prod/group_vars/keycloak/vars.yml` stays under 300 ms with the type inspections on,
 * and the type checks themselves take a small share of it.
 * Each round edits the file first, so nothing is served from the per-file cache. The heron copy of the file, whose
 * keycloak spec is in the fixture (so every client item is validated), is measured the same way.
 */
class TypeCheckPerformanceTest : TypeCheckTestCase() {

    fun testHighlightingTheKeycloakVarsFilesStaysWithinBudget() {
        copyInfra("repos/falcon/ansible", "repos/heron/ansible")
        for (path in listOf(FALCON_KEYCLOAK, HERON_KEYCLOAK)) {
            highlights(path) // warm-up: indexes, spec files, play graph
            val highlighting = ArrayList<Long>()
            val service = ArrayList<Long>()
            repeat(ROUNDS) { round ->
                edit("# round $round\n")
                highlighting += measureNanoTime { myFixture.doHighlighting() } / 1_000_000
                edit("# again $round\n")
                service += measureNanoTime { runReadActionBlocking { TypeCheckService.getInstance(project).findings(myFixture.file) } } / 1_000_000
            }
            val lines = myFixture.editor.document.lineCount
            println("$path ($lines lines): full highlighting median ${median(highlighting)} ms $highlighting; type checks median ${median(service)} ms $service")
            assertTrue("$path highlighting median ${median(highlighting)} ms", median(highlighting) < BUDGET_MS)
            assertTrue("$path type checks median ${median(service)} ms", median(service) < TYPE_CHECK_BUDGET_MS)
        }
    }

    private fun edit(comment: String) {
        val document = myFixture.editor.document
        WriteCommandAction.runWriteCommandAction(project) { document.insertString(document.textLength, comment) }
        PsiDocumentManager.getInstance(project).commitAllDocuments()
    }

    private fun median(values: List<Long>): Long = values.sorted()[values.size / 2]

    private companion object {
        const val FALCON_KEYCLOAK = "repos/falcon/ansible/environments/prod/group_vars/keycloak/vars.yml"
        const val HERON_KEYCLOAK = "repos/heron/ansible/environments/prod/group_vars/keycloak/vars.yml"
        const val ROUNDS = 11

        /** Full highlighting: every inspection of the plugin and the YAML plugin, not only the type checks. */
        const val BUDGET_MS = 300L

        /** The type checks' own share (uncached [TypeCheckService] run); measured at 2–5 ms. */
        const val TYPE_CHECK_BUDGET_MS = 50L
    }
}
