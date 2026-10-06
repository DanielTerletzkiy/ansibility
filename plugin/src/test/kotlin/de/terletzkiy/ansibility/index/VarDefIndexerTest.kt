package de.terletzkiy.ansibility.index

import com.intellij.testFramework.LightVirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.terletzkiy.ansibility.api.ValueShape
import de.terletzkiy.ansibility.fixtures.InfraTestData
import de.terletzkiy.ansibility.fixtures.RequiresInfraFixture
import de.terletzkiy.ansibility.model.inventory.ValuePreview
import de.terletzkiy.ansibility.semantics.yaml.YMap
import de.terletzkiy.ansibility.yaml.PsiYValueAdapter
import org.jetbrains.yaml.psi.YAMLFile

@RequiresInfraFixture
class VarDefIndexerTest : BasePlatformTestCase() {
    private fun defs(path: String, text: String): Map<String, List<DefEntry>> =
        VarDefIndexer.index(IndexInput.of(path, text.trimIndent() + "\n", project))

    private fun single(map: Map<String, List<DefEntry>>, name: String): DefEntry = map[name]?.single() ?: error("no single $name in ${map.keys}")

    private fun String.lineOf(offset: Int): Int = substring(0, offset).count { it == '\n' } + 1

    fun testDefaultsKeysWithShapesPreviewsAndDocComments() {
        val text = """
            haproxy_backports_version: 3.2
            haproxy_backports_version_debian:
              bookworm: "{{ haproxy_backports_version }}.*"
            # Maximum connections at kernel level
            haproxy_settings_kernel_somaxconn: '{{ haproxy_settings_maximum_connections }}'
            empty:
            quoted: "3.2"
            long: ${"x".repeat(200)}
        """
        val map = defs("golden/roles/haproxy/defaults/main.yml", text)
        val version = single(map, "haproxy_backports_version")
        assertEquals(DefSite.DEFAULTS, version.site)
        assertEquals(PathHint.DEFAULTS, version.hint)
        assertEquals(0, version.offset)
        assertEquals(ValueShape.LITERAL, version.shape)
        assertEquals(LiteralType.FLOAT, version.literalType)
        assertEquals("3.2", version.preview)
        assertFalse(version.hasDocComment)

        val debian = single(map, "haproxy_backports_version_debian")
        assertEquals(ValueShape.CONTAINER, debian.shape)
        assertEquals("{bookworm: \"{{ haproxy_backports_version }}.*\"}", debian.preview)

        val somaxconn = single(map, "haproxy_settings_kernel_somaxconn")
        assertEquals(ValueShape.JINJA, somaxconn.shape)
        assertTrue(somaxconn.hasDocComment)
        assertEquals(5, text.trimIndent().lineOf(somaxconn.offset))

        assertEquals(ValueShape.NULL, single(map, "empty").shape)
        assertEquals(LiteralType.STR, single(map, "quoted").literalType)
        assertEquals("\"3.2\"", single(map, "quoted").preview)
        val long = single(map, "long").preview!!
        assertEquals(ValueSummary.MAX_PREVIEW, long.length)
        assertTrue(long.endsWith("…"))
    }

    fun testDocCommentChangesChangeTheContentHash() {
        val a = single(defs("golden/roles/x/defaults/main.yml", "# one\nx: 1"), "x")
        val b = single(defs("golden/roles/x/defaults/main.yml", "# two\nx: 1"), "x")
        assertTrue(a.hasDocComment && b.hasDocComment)
        assertFalse(a.contentHash == b.contentHash)
    }

    fun testNoPreviewForVaultKeysVaultFilesAndVaultValues() {
        val vars = defs(
            "repos/falcon/ansible/environments/test/group_vars/all/vars.yml",
            """
            vault_plain: REDACTED
            encrypted: !vault |
              ${'$'}ANSIBLE_VAULT;1.1;AES256
              64756d6d79
            nested:
              password: !vault |
                ${'$'}ANSIBLE_VAULT;1.1;AES256
                64756d6d79
            ref: "{{ vault_plain }}"
            """,
        )
        assertNull(single(vars, "vault_plain").preview)
        assertEquals(ValueShape.VAULT, single(vars, "encrypted").shape)
        assertNull(single(vars, "encrypted").preview)
        assertEquals("a nested vault value shows only its tag", "{password: !vault}", single(vars, "nested").preview)
        assertEquals("\"{{ vault_plain }}\"", single(vars, "ref").preview)
        assertEquals(DefSite.INVENTORY_KEY, single(vars, "ref").site)

        val vault = defs("repos/falcon/ansible/group_vars/all/vault.yml", "plain_secret: hunter2\nother: x")
        assertNull(single(vault, "plain_secret").preview)
        assertNull(single(vault, "other").preview)
    }

    fun testPreviewsFollowTheSharedVaultSafeRule() {
        val vars = defs(
            "repos/falcon/ansible/environments/test/group_vars/all/vars.yml",
            """
            creds:
              user: admin
              vault_password: hunter2
            servers:
              - name: backend1
                vault_token: plain
            """,
        )
        assertEquals("nested vault_* values are masked", "{user: admin, vault_password: ***}", single(vars, "creds").preview)
        assertEquals("[{name: backend1, vault_token: ***}]", single(vars, "servers").preview)
        for (name in listOf("vault_prod.yml", "Vault.yaml", "vault")) {
            val entries = defs("repos/falcon/ansible/environments/test/group_vars/all/$name", "user: admin")
            assertNull("no preview in $name", single(entries, "user").preview)
        }
    }

    /** The indexed preview of every top-level key equals [ValuePreview.of] on the loaded file (the one rule, plan A.7). */
    fun testIndexedPreviewsEqualTheInventoryPreviews() {
        val files = listOf(
            "repos/falcon/ansible/environments/prod/group_vars/all/vars.yml",
            "repos/falcon/ansible/environments/prod/group_vars/all/vault.yml",
            "repos/falcon/ansible/group_vars/all/vars.yml",
            "golden/roles/haproxy/defaults/main.yml",
        )
        var compared = 0
        for (path in files) {
            val text = java.nio.file.Files.readString(InfraTestData.root.resolve(path))
            val entries = VarDefIndexer.index(IndexInput.of(path, text, project))
            val file = LightVirtualFile(path.substringAfterLast('/'), text)
            val document = PsiYValueAdapter.documentValue(myFixture.configureByText("check.yml", text) as YAMLFile) as YMap
            for (entry in document.entries) {
                val name = entry.key.text
                val indexed = entries[name]?.lastOrNull() ?: continue
                assertEquals("$path: $name", ValuePreview.of(name, entry.value, file), indexed.preview)
                compared++
            }
        }
        assertTrue("compared $compared keys", compared > 50)
    }

    fun testLastDuplicateKeyAndMergeKeysFollowPyYaml() {
        val map = defs(
            "golden/roles/x/vars/main.yml",
            """
            base: &base
              a: 1
            x: 1
            x: 2
            <<: { merged: 3 }
            """,
        )
        assertEquals("2", single(map, "x").preview)
        assertEquals(DefSite.VARS, single(map, "merged").site)
        assertFalse("<<" in map.keys)
    }

    fun testArgumentSpecsTopLevelOptionsOnly() {
        val text = """
            argument_specs:
              main:
                short_description: x
                options:
                  haproxy_servers:
                    type: list
                    elements: dict
                    options:
                      name:
                        type: str
                  haproxy_backports_version:
                    type: str
                    description: Repo URL uses {{ haproxy_backports_version }}-backports.
              other:
                options:
                  only_other:
                    type: int
        """
        val map = defs("golden/roles/haproxy/meta/argument_specs.yml", text)
        assertEquals(setOf("haproxy_servers", "haproxy_backports_version", "only_other"), map.keys)
        val servers = single(map, "haproxy_servers")
        assertEquals(DefSite.SPEC_OPTION, servers.site)
        assertEquals("main", servers.entryPoint)
        assertEquals("other", single(map, "only_other").entryPoint)
        assertNull(servers.preview)

        val changed = defs("golden/roles/haproxy/meta/argument_specs.yml", text.replace("type: list", "type: raw"))
        assertFalse("spec edits change the content hash", servers.contentHash == single(changed, "haproxy_servers").contentHash)

        val meta = defs("golden/roles/haproxy/meta/main.yml", text + "\ngalaxy_info:\n  author: x\n")
        assertEquals(setOf("haproxy_servers", "haproxy_backports_version", "only_other"), meta.keys)
        assertEquals(PathHint.ROLE_META, single(meta, "haproxy_servers").hint)
    }

    fun testInventoryInlineVarsWithOwners() {
        val map = defs(
            "repos/falcon/ansible/environments/prod/hosts.yml",
            """
            all:
              vars:
                ansible_user: provisioner
              hosts:
                prod-prod1:
                  ansible_host: 192.0.2.29
                prod-prod2:
            app_mono:
              hosts:
                prod-prod1:
              vars:
                inventory_docs_client_structure:
                  ha: 1
              children:
                app_web:
                  vars:
                    web_port: 80
            """,
        )
        val user = single(map, "ansible_user")
        assertEquals(DefSite.INVENTORY_INLINE, user.site)
        assertEquals("all", user.owner)
        assertFalse(user.ownerIsHost)
        val host = single(map, "ansible_host")
        assertEquals("prod-prod1", host.owner)
        assertTrue(host.ownerIsHost)
        assertEquals("app_mono", single(map, "inventory_docs_client_structure").owner)
        assertEquals("app_web", single(map, "web_port").owner)
    }

    fun testMoleculeInventoryOnly() {
        val map = defs(
            "golden/roles/postfix/molecule/default/molecule.yml",
            """
            platforms:
              - name: instance-${'$'}{MOLECULE_RUN_ID:-local}
            provisioner:
              name: ansible
              inventory:
                group_vars:
                  all:
                    postfix_relayhost: mail.example
                host_vars:
                  instance:
                    only_host: 1
                hosts:
                  all:
                    hosts:
                      instance:
                        inline_host: 2
            """,
        )
        assertEquals(setOf("postfix_relayhost", "only_host", "inline_host"), map.keys)
        val relay = single(map, "postfix_relayhost")
        assertEquals(DefSite.MOLECULE_INVENTORY, relay.site)
        assertEquals("all", relay.owner)
        assertFalse(relay.ownerIsHost)
        assertTrue(single(map, "only_host").ownerIsHost)
        assertEquals("instance", single(map, "inline_host").owner)
    }

    fun testTaskAndPlaySites() {
        val tasks = """
            - name: Set facts
              ansible.builtin.set_fact:
                fact_a: "{{ x }}"
                cacheable: true
              register: set_result
            - name: Legacy set_fact
              set_fact: fact_b=1 fact_c="{{ y }}"
            - name: Loop
              ansible.builtin.debug:
                msg: "{{ server }}"
              loop: "{{ haproxy_servers }}"
              loop_control:
                loop_var: server
                index_var: server_index
              vars:
                task_var: 1
            - name: Block
              vars:
                block_var: 1
              block:
                - name: Inner
                  ansible.builtin.command: echo
                  register: inner_result
            - name: Include
              ansible.builtin.include_role:
                name: haproxy
              vars:
                include_param: 1
        """
        val map = defs("golden/roles/haproxy/tasks/main.yml", tasks)
        assertEquals(DefSite.SET_FACT, single(map, "fact_a").site)
        assertEquals(ValueShape.JINJA, single(map, "fact_a").shape)
        assertNull("cacheable is an option, not a fact", map["cacheable"])
        assertEquals(DefSite.SET_FACT, single(map, "fact_b").site)
        assertEquals(DefSite.SET_FACT, single(map, "fact_c").site)
        assertEquals("fact_b=1", tasks.trimIndent().substring(single(map, "fact_b").offset).substringBefore(' '))
        assertEquals(DefSite.REGISTER, single(map, "set_result").site)
        assertEquals(DefSite.REGISTER, single(map, "inner_result").site)
        assertEquals(DefSite.LOOP_VAR, single(map, "server").site)
        assertEquals(DefSite.INDEX_VAR, single(map, "server_index").site)
        assertEquals(DefSite.TASK_VARS, single(map, "task_var").site)
        assertEquals(DefSite.BLOCK_VARS, single(map, "block_var").site)
        assertEquals(DefSite.INCLUDE_PARAMS, single(map, "include_param").site)
        val register = single(map, "set_result")
        assertEquals("set_result", tasks.trimIndent().substring(register.offset, register.offset + "set_result".length))

        val play = defs(
            "repos/falcon/ansible/playbook-setup-system.yml",
            """
            - name: System
              hosts: system
              vars:
                play_var: 1
              vars_prompt:
                - name: prompted
                  prompt: Say
              roles:
                - { role: system, tags: ['system'], role_param: 1 }
                - role: postfix
                  vars:
                    role_var: 2
              tasks:
                - name: T
                  ansible.builtin.debug:
                    msg: x
                  register: play_task_result
              handlers:
                - name: H
                  ansible.builtin.command: x
                  register: handler_result
            """,
        )
        assertEquals(DefSite.PLAY_VARS, single(play, "play_var").site)
        assertEquals(DefSite.VARS_PROMPT, single(play, "prompted").site)
        assertEquals(DefSite.ROLE_PARAMS, single(play, "role_param").site)
        assertEquals(DefSite.ROLE_PARAMS, single(play, "role_var").site)
        assertNull("role keywords are not params", play["tags"])
        assertEquals(DefSite.REGISTER, single(play, "play_task_result").site)
        assertEquals(DefSite.REGISTER, single(play, "handler_result").site)
    }

    fun testTemplatesFilesAndMappingsInTaskFilesDefineNothing() {
        assertTrue(defs("golden/roles/x/templates/a.yml", "x: 1").isEmpty())
        assertTrue(defs("golden/roles/x/files/a.yml", "x: 1").isEmpty())
        assertTrue(defs("golden/roles/x/tasks/main.yml", "x: 1").isEmpty())
        assertTrue(defs("repos/falcon/ansible/docker-compose.yml", "services: {}").isEmpty())
    }

    fun testVarsFilesWithoutYamlExtension() {
        val map = defs("repos/falcon/ansible/environments/test/host_vars/preview-dev2.bike.example.de", "host_only: 1")
        assertEquals(DefSite.INVENTORY_KEY, single(map, "host_only").site)
        val json = defs("repos/falcon/ansible/group_vars/all/extra.json", """{"json_var": 1}""")
        assertEquals(DefSite.INVENTORY_KEY, single(json, "json_var").site)
        assertTrue(defs("repos/falcon/ansible/group_vars/all/README.md", "x: 1").isEmpty())
    }
}
