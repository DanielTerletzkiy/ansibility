package de.terletzkiy.ansibility.inspections.templated

import com.intellij.codeInspection.InspectionManager
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.IndexingTestUtil
import de.terletzkiy.ansibility.typeflow.TemplatedTestCase

/**
 * ANS-T020 on the biggest vars file of the fixture, `repos/falcon/ansible/environments/prod/group_vars/keycloak/vars.yml`
 * (867 lines, `keycloak_clients` with hundreds of nested keys): the inspection must stay below 300 ms after an
 * edit (the state while typing: PSI-dependent caches such as `VarService`'s are dropped, other files stay parsed).
 * No role of the falcon root declares its variables, so that run measures the definition scan; the heron copy of the file
 * and a synthetic stress file also measure the chain evaluation. The measurements are printed for the work-unit report.
 */
class TemplatedValueTypePerformanceTest : TemplatedTestCase() {

    fun testKeycloakVarsFileIsFastAfterAnEdit() {
        copyInfra("repos/falcon")
        val afterEdit = measure("repos/falcon/ansible/environments/prod/group_vars/keycloak/vars.yml")
        assertTrue("median after an edit $afterEdit ms, budget 300 ms", afterEdit < 300)
    }

    /**
     * The same file in `repos/heron` (875 lines), whose root carries the keycloak role: 22 templated values of spec'd
     * variables (vault references, nested `keycloak_clients[*].secret`, chains) are evaluated, none is a finding.
     */
    fun testHeronKeycloakVarsFileWithSpecIsFastAfterAnEdit() {
        copyInfra("repos/heron")
        val afterEdit = measure("repos/heron/ansible/environments/prod/group_vars/keycloak/vars.yml", expectedFindings = 0)
        assertTrue("median after an edit $afterEdit ms, budget 300 ms", afterEdit < 300)
    }

    /** A stress case beyond the repo: 300 spec'd values, each a two-hop chain through distinct names. */
    fun testThreeHundredChainsAreFastAfterAnEdit() {
        copyInfra("golden/roles/haproxy")
        val count = 300
        createFile(
            "golden/roles/stress/meta/argument_specs.yml",
            "argument_specs:\n  main:\n    options:\n" + (1..count).joinToString("\n") { "      s_$it:\n        type: str" },
        )
        createFile(
            "golden/roles/stress/defaults/main.yml",
            (1..count).joinToString("\n") { "s_$it: '{{ a_$it }}'\na_$it: '{{ b_$it }}'\nb_$it: $it" },
        )
        val afterEdit = measure("golden/roles/stress/defaults/main.yml", expectedFindings = 0)
        assertTrue("median after an edit $afterEdit ms, budget 300 ms", afterEdit < 300)
    }

    /** Times the inspection on [path]: cold, unchanged and after edits; prints all and returns the median after an edit. */
    private fun measure(path: String, expectedFindings: Int? = null): Double {
        myFixture.configureFromTempProjectFile(path)
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        val inspection = AnsibleTemplatedValueTypeInspection()
        val manager = InspectionManager.getInstance(project)
        var problems = 0
        fun inspect(): Double {
            val start = System.nanoTime()
            problems = runReadActionBlocking { inspection.checkFile(myFixture.file, manager, true)?.size ?: 0 }
            return (System.nanoTime() - start) / 1e6
        }
        fun edit() {
            WriteCommandAction.runWriteCommandAction(project) { myFixture.editor.document.insertString(0, "# edit\n") }
            PsiDocumentManager.getInstance(project).commitAllDocuments()
        }
        val lines = myFixture.editor.document.lineCount
        val cold = inspect()
        val unchanged = List(5) { inspect() }.sorted()
        val afterEdit = List(7) {
            edit()
            inspect()
        }.sorted()
        expectedFindings?.let { assertEquals(it, problems) }
        val passes = List(3) {
            edit()
            val start = System.nanoTime()
            myFixture.doHighlighting()
            (System.nanoTime() - start) / 1e6
        }.sorted()
        fun Double.ms() = "%.1f ms".format(this)
        println(
            "ANS-T020 timing on $path ($lines lines, $problems findings): cold ${cold.ms()}, unchanged median ${unchanged[2].ms()}, " +
                "after an edit median ${afterEdit[3].ms()} (max ${afterEdit.last().ms()}), " +
                "whole highlighting pass after an edit median ${passes[1].ms()}",
        )
        return afterEdit[3]
    }
}
