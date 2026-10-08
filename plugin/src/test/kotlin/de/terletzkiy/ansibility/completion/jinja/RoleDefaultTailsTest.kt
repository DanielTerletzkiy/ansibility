package de.terletzkiy.ansibility.completion.jinja

/**
 * Completion tails show the role default Ansible uses, never the argument spec's documented `default:` (plan amendment
 * R23, D174): the last file of a `defaults/main/` directory wins, a file only `defaults_from` loads does not count, a
 * `!vault` default has no tail, members of a dict option show the role default's nested value (never a `no_log`
 * sub-option's), and names of other roles show the value of their last loaded defaults file.
 */
class RoleDefaultTailsTest : JinjaCompletionTestCase() {
    private val tasks = "site/roles/web/tasks/main.yml"

    override fun setUp() {
        super.setUp()
        createFile("site/ansible.cfg", "[defaults]\nroles_path = roles")
        createFile("site/playbook.yml", "- hosts: all\n  roles:\n    - web")
        createFile(
            "site/roles/web/meta/argument_specs.yml",
            """
            argument_specs:
              main:
                options:
                  web_version: {type: str, default: "1.5"}
                  web_spec_only: {type: str, default: documented}
                  web_secret_val: {type: str, default: shown-nowhere}
                  web_required: {type: str, required: true}
                  web_db:
                    type: dict
                    options:
                      port: {type: int, default: 5432}
                      host: {type: str, default: localhost}
                  web_conn:
                    type: dict
                    options:
                      user: {type: str}
                      password: {type: str, no_log: true}
            """,
        )
        createFile("site/roles/web/defaults/main/10-base.yml", "web_version: 1.1\nweb_db:\n  port: 5433\nweb_conn: {user: app, password: changeme}")
        createFile("site/roles/db/defaults/main/10-a.yml", "db_port: 1")
        createFile("site/roles/db/defaults/main/20-b.yml", "db_port: 2")
        createFile("site/roles/db/tasks/main.yml", "- name: Noop\n  ansible.builtin.debug:\n    msg: hi")
        createFile(
            "site/roles/web/defaults/main/20-over.yml",
            "web_version: 1.2\nweb_secret_val: !vault |\n  \$ANSIBLE_VAULT;1.1;AES256\n  6162",
        )
        createFile("site/roles/web/defaults/other.yml", "web_spec_only: other-file\nweb_extra: 7")
        createFile("site/roles/web/tasks/main.yml", "- name: Use\n  ansible.builtin.debug:\n    msg: \"{{ PLACEHOLDER }}\"")
    }

    fun testNameTailsShowOnlyTheRoleDefault() {
        val items = completeAfterEdit(tasks, 3, "PLACEHOLDER", "web_")
        assertEquals("the last file of defaults/main/ wins; never the documented 1.5", "str | = 1.2 · web", describe(items, "web_version"))
        assertEquals("no role default: no value at all", "str | · web", describe(items, "web_spec_only"))
        assertEquals("a vault default shows no value, and never the documented one", "str | · web", describe(items, "web_secret_val"))
        assertEquals("str | · required · web", describe(items, "web_required"))
        assertTrue(presentation(item(items, "web_required")).isItemTextBold)
        assertFalse(presentation(item(items, "web_spec_only")).isItemTextBold)
        assertEquals("a key only defaults_from loads has no value Ansible uses", "int | · web", describe(items, "web_extra"))
    }

    fun testOtherRolesShowTheirLastLoadedDefault() {
        val items = completeAfterEdit(tasks, 3, "PLACEHOLDER", "db_")
        assertEquals("20-b.yml loads last", "int | = 2 · db", describe(items, "db_port"))
    }

    fun testMemberTailsShowTheRoleDefaultsNestedValue() {
        val members = completeAfterEdit(tasks, 3, "PLACEHOLDER", "web_db.")
        assertEquals("int | = 5433", describe(members, "port"))
        assertEquals("the documented sub-option default is never applied", "str |", describe(members, "host"))
        reset(tasks)
        val conn = completeAfterEdit(tasks, 3, "PLACEHOLDER", "web_conn.")
        assertEquals("str | = app", describe(conn, "user"))
        assertEquals("a no_log sub-option's value is never shown", "str |", describe(conn, "password"))
    }
}
