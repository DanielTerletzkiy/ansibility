package de.terletzkiy.ansibility.inspections.spec

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.VfsUtil
import de.terletzkiy.ansibility.fixtures.RequiresInfraFixture
import de.terletzkiy.ansibility.inspections.undefined.UndefinedTestCase

/** ANS-S002: role inputs that `meta/argument_specs.yml` does not declare, and the fixes that declare them. */
@RequiresInfraFixture
class RoleInputNotInSpecTest : UndefinedTestCase() {
    override fun addFixtureFiles() {
        add(
            "$NEW_ROLE/defaults/main.yml",
            """
            ---
            fresh_port: 8080
            fresh_names:
              - a
              - b
            """,
        )
        add(
            "$NEW_ROLE/tasks/main.yml",
            """
            ---
            - name: Compute
              ansible.builtin.set_fact:
                fresh_computed: "{{ fresh_port + 1 }}"
            - name: Use
              ansible.builtin.debug:
                msg: "{{ fresh_port }} {{ fresh_computed }} {{ fresh_external }} {{ inventory_hostname }}"
              loop: "{{ fresh_names }}"
              loop_control:
                loop_var: fresh_item
            - name: Loop var
              ansible.builtin.debug:
                msg: "{{ fresh_item }}"
            """,
        )
        add("$NEW_ROLE/templates/app.conf.j2", "{% set local = 1 %}{{ local }} {{ fresh_from_template }}")
        add("$SPEC_ROLE/defaults/main.yml", "---\nspecced_port: 80\nspecced_extra: true")
        add(
            "$SPEC_ROLE/meta/argument_specs.yml",
            """
            ---
            argument_specs:
              main:
                short_description: specced
                options:
                  specced_port:
                    type: int
            """,
        )
        add("$SPEC_ROLE/tasks/main.yml", "---\n- name: Use\n  ansible.builtin.debug:\n    msg: \"{{ specced_port }} {{ specced_extra }}\"")
    }

    private fun s002(path: String): List<String> {
        myFixture.enableInspections(AnsibleRoleInputNotInSpecInspection::class.java)
        myFixture.configureFromExistingVirtualFile(vf(path))
        return myFixture.doHighlighting()
            .filter { it.description?.contains("meta/argument_specs.yml") == true }
            .sortedBy { it.startOffset }
            .map { "${if (it.severity == HighlightSeverity.WARNING) "WARNING" else it.severity.name} ${myFixture.file.text.substring(it.startOffset, it.endOffset)}" }
    }

    fun testRoleWithoutSpecSuggestsItsInputs() {
        assertEquals(listOf("WEAK WARNING fresh_port", "WEAK WARNING fresh_names"), s002("$NEW_ROLE/defaults/main.yml"))
        assertEquals("only names from outside the role", listOf("WEAK WARNING fresh_external"), s002("$NEW_ROLE/tasks/main.yml"))
        assertEquals(listOf("WEAK WARNING fresh_from_template"), s002("$NEW_ROLE/templates/app.conf.j2"))
    }

    fun testRoleWithSpecWarnsAboutUndeclaredInputsOnly() {
        assertEquals(listOf("WARNING specced_extra"), s002("$SPEC_ROLE/defaults/main.yml"))
        assertEquals("defaults keys are reported on the key", emptyList<String>(), s002("$SPEC_ROLE/tasks/main.yml"))
    }

    fun testCreateSpecFixWritesTypedOptions() {
        s002("$NEW_ROLE/defaults/main.yml")
        val fix = myFixture.getAllQuickFixes().first { it.text == "Create meta/argument_specs.yml declaring all 2 inputs of this file" }
        myFixture.launchAction(fix)
        val spec = vf("$NEW_ROLE/meta/argument_specs.yml")
        assertEquals(
            """
            ---
            argument_specs:
              main:
                short_description: fresh
                options:
                  fresh_port:
                    type: int
                  fresh_names:
                    type: list
                    elements: str

            """.trimIndent(),
            VfsUtil.loadText(spec),
        )
    }

    fun testAddToSpecFixDeclaresTheOption() {
        s002("$SPEC_ROLE/defaults/main.yml")
        val fix = myFixture.getAllQuickFixes().first { it.text == "Add 'specced_extra' (bool) to the argument spec of 'specced'" }
        myFixture.launchAction(fix)
        val text = FileDocumentManager.getInstance().getDocument(vf("$SPEC_ROLE/meta/argument_specs.yml"))!!.text
        assertTrue(text, "      specced_extra:\n        type: bool" in text)
        assertTrue(s002("$SPEC_ROLE/defaults/main.yml").isEmpty())
    }

    private companion object {
        const val NEW_ROLE = "$FALCON/roles/fresh"
        const val SPEC_ROLE = "$FALCON/roles/specced"
    }
}
