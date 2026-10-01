package de.terletzkiy.ansibility.resolve

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.VarDefKind
import de.terletzkiy.ansibility.api.VarDefinition
import de.terletzkiy.ansibility.api.VarService
import de.terletzkiy.ansibility.api.VarsLayer
import de.terletzkiy.ansibility.context.AnsibleWorkspaceImpl
import de.terletzkiy.ansibility.index.LiteralType

/** `ansible.var.def` entries mapped to the api: role params, `vars_prompt` and literal types (task j). */
class VarDefinitionKindsTest : BasePlatformTestCase() {
    private lateinit var root: AnsibleRoot

    override fun setUp() {
        super.setUp()
        myFixture.tempDirFixture.createFile("site/ansible.cfg", "[defaults]\n")
        myFixture.tempDirFixture.createFile("site/roles/web/tasks/main.yml", "- name: Ping\n  ansible.builtin.ping:\n")
        myFixture.tempDirFixture.createFile(
            "site/roles/web/defaults/main.yml",
            "web_version: 3.2\nweb_port: 8080\nweb_name: web\nweb_on: true\nweb_day: 2024-01-01\nweb_none: ~\n" +
                "web_template: \"{{ web_name }}\"\nweb_list: [1]\n",
        )
        myFixture.tempDirFixture.createFile(
            "site/playbook-site.yml",
            """
            - name: Site
              hosts: all
              vars_prompt:
                - name: web_password
                  prompt: Password?
              roles:
                - role: web
                  web_port: 9090
                  vars:
                    web_name: site
            """.trimIndent() + "\n",
        )
        AnsibleWorkspaceImpl.getInstance(project)!!.structureChanged()
        val dir = myFixture.findFileInTempDir("site")
        root = AnsibleWorkspaceImpl.getInstance(project)!!.roots().single { it.dir == dir }
    }

    private fun definitions(name: String): List<VarDefinition> = VarService.getInstance(project).symbol(root, name).definitions

    fun testRoleParamsAndVarsPromptHaveTheirOwnKinds() {
        val param = definitions("web_port").single { it.kind != VarDefKind.ROLE_DEFAULT }
        assertEquals(VarDefKind.ROLE_PARAMS, param.kind)
        assertEquals(VarsLayer.ROLE_PARAMS, param.layer)
        val roleVar = definitions("web_name").single { it.kind != VarDefKind.ROLE_DEFAULT }
        assertEquals("vars of a roles: entry are role params too", VarDefKind.ROLE_PARAMS, roleVar.kind)

        val prompt = definitions("web_password").single()
        assertEquals(VarDefKind.VARS_PROMPT, prompt.kind)
        assertEquals(VarsLayer.VARS_FILES, prompt.layer)
    }

    fun testLiteralTypesReachTheApi() {
        fun literal(name: String) = definitions(name).single { it.kind == VarDefKind.ROLE_DEFAULT }.literalType
        assertEquals("float", literal("web_version"))
        assertEquals("int", literal("web_port"))
        assertEquals("str", literal("web_name"))
        assertEquals("bool", literal("web_on"))
        assertEquals("timestamp", literal("web_day"))
        assertEquals("null", literal("web_none"))
        assertNull("Jinja is not a literal", literal("web_template"))
        assertNull("containers are not literals", literal("web_list"))
    }

    fun testLiteralTypeNames() {
        assertEquals(
            mapOf(
                LiteralType.NONE to null, LiteralType.STR to "str", LiteralType.INT to "int", LiteralType.FLOAT to "float",
                LiteralType.BOOL to "bool", LiteralType.TIMESTAMP to "timestamp", LiteralType.NULL to "null", LiteralType.UNLOADABLE to null,
            ),
            LiteralType.entries.associateWith(VarDefinitions::literalTypeName),
        )
    }
}
