package de.terletzkiy.ansibility.resolve.loop

import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.terletzkiy.ansibility.context.AnsibleWorkspaceImpl
import de.terletzkiy.ansibility.fixtures.InfraTestData
import de.terletzkiy.ansibility.fixtures.RequiresInfraFixture
import de.terletzkiy.ansibility.semantics.schema.OptionType
import org.jetbrains.yaml.psi.YAMLFile

/** [LoopItemTyper] (plan F1.7, X09): the item type of task loops. */
@RequiresInfraFixture
class LoopItemTyperTest : BasePlatformTestCase() {
    override fun getTestDataPath(): String = InfraTestData.testDataPath.toString()

    private fun refresh() = (AnsibleWorkspaceImpl.getInstance(project) ?: error("no workspace")).structureChanged()

    private fun create(path: String, text: String): VirtualFile = myFixture.tempDirFixture.createFile(path, text.trimIndent() + "\n").also { refresh() }

    private fun typeAt(path: String, marker: String): LoopItemType? = runReadActionBlocking {
        val file = myFixture.findFileInTempDir(path) ?: error("missing $path")
        val yaml = PsiManager.getInstance(project).findFile(file) as YAMLFile
        LoopItemTyper.typeAt(project, yaml, yaml.text.indexOf(marker).also { check(it >= 0) { "no $marker" } })
    }

    override fun setUp() {
        super.setUp()
        create(
            "site/roles/web/meta/argument_specs.yml",
            """
            argument_specs:
              main:
                options:
                  web_sites:
                    type: list
                    elements: dict
                    options:
                      name: {type: str, required: true}
                      port: {type: int}
                      tls:
                        type: dict
                        options:
                          cert: {type: str}
                  web_users:
                    type: dict
                    options:
                      admin: {type: str}
                  web_names:
                    type: list
                    elements: str
            """,
        )
        create(
            "site/roles/web/defaults/main.yml",
            """
            web_sites: []
            web_backends:
              - host: a
                weight: 1
              - host: b
                port: 80
            """,
        )
    }

    fun testLoopOverSpecdListGivesElementOptions() {
        create(
            "site/roles/web/tasks/main.yml",
            """
            - name: Sites
              ansible.builtin.debug:
                msg: "{{ item.name }}"
              loop: "{{ web_sites }}"
            """,
        )
        val type = typeAt("site/roles/web/tasks/main.yml", "msg")!!
        assertEquals("item", type.loopVar)
        assertEquals("web_sites", type.loop.sourceVariable)
        assertEquals(OptionType.Dict, type.loop.item?.type)
        assertEquals(OptionType.Str, type.typeOfPath("item", listOf("name"))?.type)
        assertEquals(OptionType.Int, type.typeOfPath("item", listOf("port"))?.type)
        assertEquals(OptionType.Str, type.typeOfPath("item", listOf("tls", "cert"))?.type)
        assertNull(type.typeOfPath("item", listOf("bogus")))
        assertNull("not the loop's name", type.typeOfPath("other", emptyList()))
        assertEquals("loop", type.keyword)
    }

    fun testLoopControlLoopVarIndexVarAndExtended() {
        create(
            "site/roles/web/tasks/main.yml",
            """
            - name: Sites
              ansible.builtin.debug:
                msg: "{{ site.port }}"
              with_items: "{{ web_sites | default([]) | selectattr('port', 'defined') | list }}"
              loop_control:
                loop_var: site
                index_var: idx
                extended: true
            """,
        )
        val type = typeAt("site/roles/web/tasks/main.yml", "msg")!!
        assertEquals("site", type.loopVar)
        assertEquals(listOf("site", "idx", "ansible_loop"), type.names)
        assertEquals(OptionType.Int, type.typeOfPath("site", listOf("port"))?.type)
        assertEquals(OptionType.Int, type.typeOfPath("idx", emptyList())?.type)
        assertEquals(OptionType.Bool, type.typeOfPath("ansible_loop", listOf("last"))?.type)
        assertNull("item is not defined with loop_var", type.typeOfPath("item", emptyList()))
    }

    fun testLiteralListsAndDicts() {
        create(
            "site/roles/web/tasks/main.yml",
            """
            - name: Literal
              ansible.builtin.debug:
                msg: "{{ item.path }}"
              loop:
                - {path: /a, mode: "0644"}
                - {path: /b, recurse: true}
            - name: With dict
              ansible.builtin.debug:
                msg: "{{ item.key }}"
              with_dict:
                a: 1
                b: 2
            - name: Nested with_items flattens
              ansible.builtin.debug:
                msg: "{{ item }}"
              with_items:
                - [x, y]
                - z
            """,
        )
        val literal = typeAt("site/roles/web/tasks/main.yml", "{{ item.path }}")!!
        assertEquals(setOf("path", "mode", "recurse"), literal.loop.item?.options?.keys)
        assertEquals(OptionType.Bool, literal.typeOfPath("item", listOf("recurse"))?.type)
        assertNull(literal.loop.sourceVariable)

        val dict = typeAt("site/roles/web/tasks/main.yml", "{{ item.key }}")!!
        assertEquals(OptionType.Str, dict.typeOfPath("item", listOf("key"))?.type)
        assertEquals(OptionType.Int, dict.typeOfPath("item", listOf("value"))?.type)

        val flat = typeAt("site/roles/web/tasks/main.yml", "{{ item }}")!!
        assertEquals(OptionType.Str, flat.loop.item?.type)
    }

    fun testDict2ItemsMapAttributeLookupsAndLiteralDefaults() {
        create(
            "site/roles/web/tasks/main.yml",
            """
            - name: Users
              ansible.builtin.debug:
                msg: "{{ item.value }}"
              loop: "{{ web_users | dict2items }}"
            - name: Names
              ansible.builtin.debug:
                msg: "{{ item | upper }}"
              loop: "{{ web_sites | map(attribute='tls.cert') | list }}"
            - name: Globs
              ansible.builtin.debug:
                msg: "{{ item | basename }}"
              loop: "{{ lookup('fileglob', role_path ~ '/templates/*.j2', wantlist=True) | sort }}"
            - name: Backends
              ansible.builtin.debug:
                msg: "{{ item.host }}"
              loop: "{{ web_backends }}"
            - name: Unknown
              ansible.builtin.debug:
                msg: "{{ item }}"
              loop: "{{ web_sites + web_backends }}"
            - name: No loop
              ansible.builtin.debug:
                msg: plain
            """,
        )
        val path = "site/roles/web/tasks/main.yml"
        val users = typeAt(path, "{{ item.value }}")!!
        assertEquals(setOf("key", "value"), users.loop.item?.options?.keys)
        assertEquals(OptionType.Str, typeAt(path, "{{ item | upper }}")!!.loop.item?.type)
        assertEquals(OptionType.Str, typeAt(path, "{{ item | basename }}")!!.loop.item?.type)
        val backends = typeAt(path, "{{ item.host }}")!!
        assertEquals("the literal default's shape", setOf("host", "weight", "port"), backends.loop.item?.options?.keys)
        assertEquals(OptionType.Int, backends.typeOfPath("item", listOf("weight"))?.type)
        assertNull("a concatenation is not typed", typeAt(path, "{{ web_sites + web_backends }}")?.loop?.item)
        assertNull(typeAt(path, "plain"))
    }

    fun testTaskVarsTakePrecedence() {
        create(
            "site/roles/web/tasks/main.yml",
            """
            - name: Block
              vars:
                local_list:
                  - {a: 1}
              block:
                - name: Inner
                  ansible.builtin.debug:
                    msg: "{{ item.a }}"
                  loop: "{{ local_list }}"
            """,
        )
        val type = typeAt("site/roles/web/tasks/main.yml", "msg")!!
        assertEquals(OptionType.Int, type.typeOfPath("item", listOf("a"))?.type)
    }

    fun testGrafanaNginxSitesFixture() {
        myFixture.copyDirectoryToProject("${InfraTestData.INFRA}/golden/roles/grafana", "golden/roles/grafana")
        refresh()
        val type = typeAt("golden/roles/grafana/tasks/nginx.yml", "src: \"templates/nginx/{{ item.template }}\"")!!
        assertEquals("grafana_nginx_sites", type.loop.sourceVariable)
        val template = type.typeOfPath("item", listOf("template"))!!
        assertEquals(listOf("main.site.conf.j2", "main.site.proxy_protocol.conf.j2", "main.site.mcp.conf.j2"), template.choices?.values?.map { (it as de.terletzkiy.ansibility.semantics.yaml.YScalar).text })
        assertEquals(setOf("port", "cert_file", "key_file", "trusted_intermediate", "client_cert_ca", "client_cert_ca_src"), type.typeOfPath("item", listOf("floating", "ssl"))?.options?.keys)
    }
}
