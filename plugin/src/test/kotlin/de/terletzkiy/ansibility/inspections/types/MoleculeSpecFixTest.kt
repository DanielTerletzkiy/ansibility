package de.terletzkiy.ansibility.inspections.types

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.psi.PsiDocumentManager
import de.terletzkiy.ansibility.types.TypeCheckTestCase

/**
 * Plan amendment R20, D157 for the "Update spec from usage" fix (X79): applied in a production file, the sub-options it
 * writes into the role's argument spec come from production values only; a converge play's test-only keys count only
 * when the fix is applied in a Molecule file.
 */
class MoleculeSpecFixTest : TypeCheckTestCase() {
    override fun setUp() {
        super.setUp()
        createFile("ansible.cfg", "[defaults]\n")
        createFile("environments/prod/hosts.yml", "all:\n  children:\n    web:\n      hosts:\n        web1:\n")
        createFile("site.yml", "- hosts: web\n  roles: [web]\n")
        createFile(
            SPEC,
            """
            ---
            argument_specs:
              main:
                short_description: web
                options:
                  web_vhosts:
                    type: list
                    elements: str
            """.trimIndent() + "\n",
        )
        createFile(PRODUCTION, "---\nweb_vhosts:\n  - name: a\n    port: 80\n")
        createFile("roles/web/molecule/default/molecule.yml", "---\ndriver:\n  name: default\n")
        createFile(
            CONVERGE,
            """
            ---
            - name: Converge
              hosts: all
              vars:
                web_vhosts:
                  - name: t
                    port: 1
                    test_only: x
              roles: [web]
            """.trimIndent() + "\n",
        )
    }

    private fun fix(name: String) =
        myFixture.getAllQuickFixes().firstOrNull { it.text == name } ?: error("no fix '$name' in ${myFixture.getAllQuickFixes().map { it.text }}")

    private fun specText(): String {
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        return FileDocumentManager.getInstance().getDocument(vf(SPEC))!!.text
    }

    /** Applies "Update web spec from usage" at the first spec-shape finding of [path] and returns the spec's sub-options. */
    private fun applyIn(path: String): String {
        val finding = highlights(path).first { it.inspectionToolId == "AnsibleSpecShapeContradiction" }
        myFixture.editor.caretModel.moveToOffset(finding.startOffset)
        myFixture.launchAction(fix("Update web spec from usage"))
        return specText().substringAfter("elements: dict")
    }

    fun testAppliedInAProductionFileTheSpecGetsProductionKeysOnly() {
        val options = applyIn(PRODUCTION)
        assertTrue(options, options.contains("name:") && options.contains("port:"))
        assertFalse("the converge fixture's key stays out: $options", options.contains("test_only"))
    }

    fun testAppliedInAMoleculeFileTheFixtureKeysCount() {
        val options = applyIn(CONVERGE)
        assertTrue(options, options.contains("name:") && options.contains("port:") && options.contains("test_only:"))
    }

    private companion object {
        const val SPEC = "roles/web/meta/argument_specs.yml"
        const val PRODUCTION = "environments/prod/group_vars/web.yml"
        const val CONVERGE = "roles/web/molecule/default/converge.yml"
    }
}
