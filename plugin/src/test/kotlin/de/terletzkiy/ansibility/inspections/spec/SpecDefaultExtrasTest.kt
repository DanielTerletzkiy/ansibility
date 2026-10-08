package de.terletzkiy.ansibility.inspections.spec

import com.intellij.openapi.vfs.VfsUtil

/**
 * The approved extras of plan amendment R23 (D172): ANS-S004 "Documented default never applied" and ANS-S005 "Role
 * default not documented", with their fixes ("Add 'name: value' to defaults/…" respecting a `defaults/main/` directory,
 * "Remove the documented default", "Document the default in argument_specs").
 */
class SpecDefaultExtrasTest : SpecDefaultTestCase() {
    private val notApplied = AnsibleSpecDefaultNotAppliedInspection::class.java
    private val undocumented = AnsibleSpecDefaultUndocumentedInspection::class.java

    override fun addFiles() {
        file("$ROOT/site.yml", "- hosts: all\n  roles: [web, db, fresh, bare, doc]")
        file(
            WEB_SPEC,
            """
            ---
            argument_specs:
              main:
                options:
                  web_version:
                    type: str
                    default: "1.5"
                  web_from_vars:
                    default: 1
                  web_from_fact:
                    default: 2
                  web_from_dep:
                    default: 3
                  web_secret:
                    type: str
                    no_log: true
                    default: hidden-value
                  web_required:
                    required: true
                    default: x
                  web_list:
                    type: list
                    default:
                      - a
                      - b
            """,
        )
        file(WEB_DEFAULTS, "---\nweb_other: 1")
        file("$ROOT/roles/web/vars/main.yml", "web_from_vars: 1")
        file("$ROOT/roles/web/meta/main.yml", "dependencies:\n  - base")
        file("$ROOT/roles/base/defaults/main.yml", "web_from_dep: 3")
        file(
            "$ROOT/roles/web/tasks/main.yml",
            """
            - name: Fact
              ansible.builtin.set_fact:
                web_from_fact: 2
            - name: Use
              ansible.builtin.debug:
                msg: "{{ web_version }} {{ web_from_vars }} {{ web_from_fact }} {{ web_from_dep }}"
            """,
        )
        file("$ROOT/roles/db/defaults/main/10-a.yml", "db_name: app")
        file("$ROOT/roles/db/defaults/main/20-b.yml", "db_user: app")
        file(DB_SPEC, "argument_specs:\n  main:\n    options:\n      db_port:\n        type: int\n        default: 5432")
        file(FRESH_SPEC, "argument_specs:\n  main:\n    options:\n      fresh_port:\n        type: int\n        default: 80")
        file("$ROOT/roles/fresh/tasks/main.yml", "- name: Use\n  ansible.builtin.debug:\n    msg: \"{{ fresh_port }}\"")
        file(BARE_SPEC, "argument_specs:\n  main:\n    options:\n      bare_port:\n        type: int\n        default: 81")
        myFixture.tempDirFixture.findOrCreateDir("$ROOT/roles/bare/defaults")
        file(
            DOC_DEFAULTS,
            """
            ---
            doc_port: 8080
            doc_list:
              - a
              - b
            doc_req: 1
            vault_doc_pass: abc
            doc_hidden: x
            doc_tpl: "{{ doc_port }}"
            doc_null:
            doc_flow: 3
            doc_ok: 1
            """,
        )
        file(
            DOC_SPEC,
            """
            ---
            argument_specs:
              main:
                options:
                  doc_port:
                    type: int
                    description: The port.
                  doc_list:
                    type: list
                  doc_req:
                    required: true
                  vault_doc_pass:
                    type: str
                  doc_hidden:
                    no_log: true
                  doc_tpl:
                    type: str
                  doc_null:
                    type: str
                  doc_flow: {type: int}
                  doc_ok:
                    default: 1
            """,
        )
    }

    // ------------------------------------------------------------------------------------------------ ANS-S004

    fun testADocumentedDefaultNothingSetsIsNeverApplied() {
        assertEquals(listOf("7 WARNING \"1.5\"", "17 WARNING hidden-value", "23 WARNING default"), highlights(WEB_SPEC, notApplied))
        assertEquals(
            "argument_specs documents default \"1.5\" for 'web_version', but no defaults file of role 'web' sets it and Ansible never " +
                "applies a documented default: 'web_version' is undefined unless the caller sets it",
            message(WEB_SPEC, notApplied, 7),
        )
        assertEquals(
            "a secret's value is never shown",
            "argument_specs documents a default for 'web_secret', but no defaults file of role 'web' sets it and Ansible never applies a documented default",
            message(WEB_SPEC, notApplied, 17),
        )
        assertEquals(listOf("Remove the documented default"), fixNames(WEB_SPEC, notApplied, 17))
    }

    fun testAddTheDocumentedDefaultToTheDefaultsFile() {
        assertEquals(
            listOf("Add 'web_version: \"1.5\"' to defaults/main.yml", "Remove the documented default"),
            fixNames(WEB_SPEC, notApplied, 7),
        )
        apply(WEB_SPEC, notApplied, 7, "Add 'web_version: \"1.5\"' to defaults/main.yml")
        apply(WEB_SPEC, notApplied, 23, "Add 'web_list: [a, b]' to defaults/main.yml")
        assertEquals("---\nweb_other: 1\nweb_version: \"1.5\"\nweb_list:\n  - a\n  - b\n", text(WEB_DEFAULTS))
        assertEquals(listOf("17 WARNING hidden-value"), highlights(WEB_SPEC, notApplied))
    }

    fun testAddGoesToTheLastFileOfADefaultsDirectory() {
        assertEquals("Add 'db_port: 5432' to defaults/main/20-b.yml", fixNames(DB_SPEC, notApplied, 6).first())
        apply(DB_SPEC, notApplied, 6, "Add 'db_port: 5432' to defaults/main/20-b.yml")
        assertEquals("db_user: app\ndb_port: 5432\n", text("$ROOT/roles/db/defaults/main/20-b.yml"))
        assertNull("a defaults/main.yml would hide the directory", vf("$ROOT/roles/db/defaults").findChild("main.yml"))
    }

    fun testAddCreatesTheDefaultsFileWhenTheRoleHasNone() {
        apply(BARE_SPEC, notApplied, 6, "Add 'bare_port: 81' to defaults/main.yml")
        assertEquals("---\nbare_port: 81\n", VfsUtil.loadText(vf("$ROOT/roles/bare/defaults/main.yml")))
        apply(FRESH_SPEC, notApplied, 6, "Add 'fresh_port: 80' to defaults/main.yml")
        assertEquals("---\nfresh_port: 80\n", VfsUtil.loadText(vf("$ROOT/roles/fresh/defaults/main.yml")))
    }

    fun testRemoveTheNeverAppliedDefault() {
        apply(WEB_SPEC, notApplied, 23, "Remove the documented default")
        assertTrue(text(WEB_SPEC), text(WEB_SPEC).endsWith("      web_list:\n        type: list\n"))
        apply(WEB_SPEC, notApplied, 7, "Remove the documented default")
        assertTrue(text(WEB_SPEC), text(WEB_SPEC).contains("      web_version:\n        type: str\n      web_from_vars:\n"))
    }

    // ------------------------------------------------------------------------------------------------ ANS-S005

    fun testAnUndocumentedRoleDefaultIsAQuietHint() {
        assertEquals(listOf("5 INFO doc_port", "8 INFO doc_list", "20 INFO doc_flow"), highlights(DOC_SPEC, undocumented))
        assertEquals(
            "'doc_port' has the role default 8080 (defaults/main.yml:2), but argument_specs documents no default for it",
            message(DOC_SPEC, undocumented, 5),
        )
    }

    fun testDocumentTheRoleDefault() {
        apply(DOC_SPEC, undocumented, 20, "Document the default in argument_specs (default: 3)")
        apply(DOC_SPEC, undocumented, 8, "Document the default in argument_specs (default: [a, b])")
        apply(DOC_SPEC, undocumented, 5, "Document the default in argument_specs (default: 8080)")
        val spec = text(DOC_SPEC)
        assertTrue(spec, spec.contains("      doc_port:\n        type: int\n        description: The port.\n        default: 8080\n      doc_list:\n"))
        assertTrue(spec, spec.contains("      doc_list:\n        type: list\n        default:\n          - a\n          - b\n      doc_req:\n"))
        assertTrue(spec, spec.contains("      doc_flow: {type: int, default: 3}\n"))
        assertEmpty(highlights(DOC_SPEC, undocumented))
        assertEmpty("the documented defaults agree", highlights(DOC_SPEC, AnsibleSpecDefaultMismatchInspection::class.java))
    }

    private companion object {
        const val WEB_SPEC = "$ROOT/roles/web/meta/argument_specs.yml"
        const val WEB_DEFAULTS = "$ROOT/roles/web/defaults/main.yml"
        const val DB_SPEC = "$ROOT/roles/db/meta/argument_specs.yml"
        const val FRESH_SPEC = "$ROOT/roles/fresh/meta/argument_specs.yml"
        const val BARE_SPEC = "$ROOT/roles/bare/meta/argument_specs.yml"
        const val DOC_SPEC = "$ROOT/roles/doc/meta/argument_specs.yml"
        const val DOC_DEFAULTS = "$ROOT/roles/doc/defaults/main.yml"
    }
}
