package de.terletzkiy.ansibility.completion.keys

/**
 * Value and key completion read the role default Ansible uses (plan amendment R23, D174): "(default)" marks the value
 * the role's defaults set, never the spec's documented `default:`, and a required option counts as set only by a file
 * ansible-core loads.
 */
class RoleDefaultMarkerTest : KeyCompletionTestCase() {
    private val vars = "site/environments/prod/group_vars/all.yml"

    override fun setUp() {
        super.setUp()
        createFile("site/ansible.cfg", "[defaults]\nroles_path = roles\n")
        createFile("site/environments/prod/hosts.yml", "all:\n  hosts:\n    web1:\n")
        createFile("site/playbook-site.yml", "- hosts: all\n  roles:\n    - web\n")
        createFile(
            "site/roles/web/meta/argument_specs.yml",
            """
            argument_specs:
              main:
                options:
                  web_mode: {type: str, choices: [a, b, c], default: a}
                  web_flag: {type: bool, default: true}
                  web_req: {type: str, required: true}
                  web_set: {type: str, required: true}
            """.trimIndent() + "\n",
        )
        createFile("site/roles/web/defaults/main.yml", "web_mode: b\nweb_flag: false\nweb_set: x\n")
        createFile("site/roles/web/defaults/other.yml", "web_req: only-with-defaults_from\n")
        createFile(vars, "---\nother: 1\n")
    }

    fun testTheDefaultMarkerFollowsTheRoleDefault() {
        val choices = completeInsertingLine(vars, 2, "web_mode: $CARET")
        assertEquals(listOf("a", "b", "c"), names(choices))
        assertEquals(" (default)", presentation(choices, "b").tailText)
        assertNull("the documented default is never applied", presentation(choices, "a").tailText)
        val flags = completeInsertingLine(vars, 2, "web_flag: $CARET")
        assertEquals(" (default)", presentation(flags, "false").tailText)
        assertNull(presentation(flags, "true").tailText)
    }

    fun testARequiredOptionOnlyAnUnloadedDefaultsFileSetsIsStillRequired() {
        val items = completeInsertingLine(vars, 2, "web_$CARET")
        assertTrue("defaults/other.yml needs defaults_from", presentation(items, "web_req").isItemTextBold)
        assertFalse(presentation(items, "web_set").isItemTextBold)
    }
}
