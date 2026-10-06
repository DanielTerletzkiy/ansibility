package de.terletzkiy.ansibility.types

import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.psi.PsiDocumentManager
import de.terletzkiy.ansibility.api.TypeCheckService
import de.terletzkiy.ansibility.fixtures.RequiresInfraFixture

/**
 * [TypeCheckServiceImpl] on every kind of place that assigns variables (plan F3.2 "Where it runs", F4.1), the skip
 * rules (undeclared names, templates, vault), the own-role rule for role defaults, target-version semantics, secret
 * masking and caching.
 */
@RequiresInfraFixture
class TypeCheckServiceTest : TypeCheckTestCase() {

    override fun setUp() {
        super.setUp()
        copyInfra("repos/falcon/ansible")
    }

    fun testGroupVarsTopLevelValues() {
        val path = "repos/falcon/ansible/environments/prod/group_vars/web.yml"
        createFile(
            path,
            """
            haproxy_backports_version: 3.3
            haproxy_stats_http_port: "8404"
            haproxy_apply_kernel_params: 1
            haproxy_servers: web1,web2
            haproxy_log_path: null
            unknown_variable: 3.2
            haproxy_balance: roundrobbin
            """.trimIndent() + "\n",
        )
        assertEquals(listOf("1: ANS-T011", "2: ANS-T016", "3: ANS-T013", "4: ANS-T001", "5: ANS-T005", "7: ANS-T004"), codes(path))
        val byCode = findings(path).associateBy { it.code.id }
        assertEquals(listOf("quote"), byCode.getValue("ANS-T011").fixHints)
        assertEquals(listOf("unquote"), byCode.getValue("ANS-T016").fixHints)
        assertEquals(listOf("replace=true"), byCode.getValue("ANS-T013").fixHints)
        assertEquals(listOf("nearest-choice=roundrobin"), byCode.getValue("ANS-T004").fixHints)
        assertEquals(
            "a comma string for a list of dicts is rejected (the list, then each element): one problem",
            "ansible-core 2.18.8 would reject `haproxy_servers` for role `haproxy` (entry point `main`): dictionary requested, " +
                "could not parse JSON or key=value (and 2 more errors for this value)",
            byCode.getValue("ANS-T001").message,
        )
        assertTrue("the role's spec has a default, so null is checked", byCode.getValue("ANS-T005").message.contains("ansible-core 2.18.8 would reject it"))
        assertTrue("playbook-setup-system.yml:52 applies haproxy", findings(path).all { it.reachable == true })
    }

    fun testNestedItemsMissingKeysAndTemplatedLeaves() {
        val path = "repos/falcon/ansible/environments/prod/group_vars/lb.yml"
        createFile(
            path,
            """
            haproxy_servers:
              - name: web1
                ip: "{{ web1_ip }}"
                port: "{{ web1_port }}"
              - name: web2
                port: 444.5
              - name: web3
                ip: 192.0.2.3
                port: 444
                wieght: 1
            """.trimIndent() + "\n",
        )
        assertEquals("templated leaves are opaque; the mapping lacking `ip` is T003", listOf("5: ANS-T003", "6: ANS-T001", "10: ANS-T002"), codes(path))
        val missing = findings(path).first()
        assertEquals(listOf("haproxy_servers", "1"), missing.path)
        assertTrue(missing.message, missing.message.startsWith("Missing required key `ip` in `haproxy_servers[1]` for role `haproxy` (entry point `main`)"))
        assertEquals(listOf("remove-key", "nearest-key=weight"), findings(path).last().fixHints)
    }

    fun testTemplatedVaultAndSecretValuesAreSkipped() {
        val path = "repos/falcon/ansible/environments/prod/group_vars/skipped.yml"
        createFile(
            path,
            """
            haproxy_backports_version: "{{ haproxy_version }}"
            haproxy_stats_http_port: !vault |
              ${'$'}ANSIBLE_VAULT;1.1;AES256
              64756d6d79
            vault_haproxy_backports_version: 3.3
            """.trimIndent() + "\n",
        )
        assertEmpty(codes(path))
        val vaultFile = "repos/falcon/ansible/environments/prod/group_vars/lb/vault.yml"
        createFile(vaultFile, "haproxy_backports_version: 3.3\n")
        assertEmpty("vault files are never checked", codes(vaultFile))
    }

    fun testInventoryInlineVars() {
        val path = "repos/falcon/ansible/environments/stage/hosts.yml"
        createFile(
            path,
            """
            all:
              vars:
                haproxy_backports_version: 3.3
              children:
                web:
                  hosts:
                    web1:
                      haproxy_stats_http_port: "8404"
                  vars:
                    haproxy_apply_kernel_params: 0
            """.trimIndent() + "\n",
        )
        assertEquals(listOf("3: ANS-T011", "8: ANS-T016", "10: ANS-T013"), codes(path))
    }

    fun testPlayBlockTaskIncludeRoleVarsAndRoleParams() {
        val path = "repos/falcon/ansible/playbook-types.yml"
        createFile(
            path,
            """
            - name: Play
              hosts: all
              vars:
                haproxy_backports_version: 3.3
              roles:
                - role: haproxy
                  haproxy_apply_kernel_params: "yes"
                  vars:
                    haproxy_stats_http_port: 8404.5
              tasks:
                - name: Include
                  ansible.builtin.include_role:
                    name: haproxy
                  vars:
                    system_heartbeat_products: postfix,haproxy
                - block:
                    - name: Debug
                      ansible.builtin.debug:
                        msg: hi
                      vars:
                        haproxy_log_path: 42
                  vars:
                    haproxy_balance: 1
            - ansible.builtin.import_playbook: playbook-setup-system.yml
              vars:
                haproxy_backports_version: 3.4
            """.trimIndent() + "\n",
        )
        assertEquals(
            listOf("4: ANS-T011", "7: ANS-T016", "9: ANS-T001", "15: ANS-T014", "21: ANS-T011", "23: ANS-T004", "23: ANS-T011", "26: ANS-T011"),
            codes(path),
        )
        assertTrue(findings(path).all { it.reachable == true && "not applied" !in it.message })
    }

    fun testRoleTaskVars() {
        val path = "repos/falcon/ansible/roles/haproxy/tasks/types.yml"
        createFile(
            path,
            """
            - name: Task
              ansible.builtin.debug:
                msg: hi
              vars:
                haproxy_stats_http_port: "8404"
            """.trimIndent() + "\n",
        )
        assertEquals(listOf("5: ANS-T016"), codes(path))
    }

    fun testMoleculeInventoryAndVarsFiles() {
        val config = "repos/falcon/ansible/roles/haproxy/molecule/types/molecule.yml"
        createFile(
            config,
            """
            provisioner:
              name: ansible
              inventory:
                group_vars:
                  all:
                    haproxy_backports_version: 3.3
                host_vars:
                  haproxy_deb13:
                    haproxy_stats_http_port: "8404"
                hosts:
                  all:
                    vars:
                      haproxy_apply_kernel_params: 1
            """.trimIndent() + "\n",
        )
        assertEquals(listOf("6: ANS-T011", "9: ANS-T016", "13: ANS-T013"), codes(config))
        assertTrue("reachability does not apply to molecule scenarios", findings(config).all { it.reachable == null })
        val vars = "repos/falcon/ansible/roles/haproxy/molecule/types/vars/main.yml"
        createFile(vars, "haproxy_backports_version: 3.3\n")
        assertEquals(listOf("1: ANS-T011"), codes(vars))
    }

    fun testMoleculeScenariosAreCheckedAgainstTheRolesTheyRun() {
        // Like repos/platform/ansible/roles/puppet-migration: another role of the root documents totp_users as dicts.
        createFile(
            "repos/falcon/ansible/roles/puppet-migration/meta/argument_specs.yml",
            """
            argument_specs:
              main:
                options:
                  totp_users:
                    type: list
                    elements: dict
                    required: true
                    options:
                      name:
                        type: str
                        required: true
                      secret_file_src:
                        type: str
                        required: true
            """.trimIndent() + "\n",
        )
        val molecule = "repos/falcon/ansible/roles/totp-token/molecule/default/molecule.yml"
        assertEquals("        totp_users:", VfsUtilCore.loadText(vf(molecule)).lines()[52])
        assertEmpty("the totp-token scenario never runs puppet-migration, whose spec wants dicts", codes(molecule))
        val inventory = "repos/falcon/ansible/environments/prod/group_vars/all/vars.yml"
        assertEquals("inventory values reach every role of the root", listOf("687: ANS-T010", "689: ANS-T010"), codes(inventory))
        assertEquals(listOf("totp-token"), findings(inventory).first().bindings.map { it.role.name })
    }

    fun testLoopReadsNameTheReadingFiles() {
        createFile(
            "repos/falcon/ansible/roles/totp-token/tasks/extra.yml",
            """
            - name: Read the items
              ansible.builtin.debug:
                msg: "{{ item.name }} {{ item.mode }}"
              loop: "{{ totp_users | default([]) }}"
            """.trimIndent() + "\n",
        )
        val inventory = "repos/falcon/ansible/environments/prod/group_vars/all/vars.yml"
        val finding = findings(inventory).first()
        assertEquals("item keys first, then the others by name", listOf("name", "secret_file_src", "mode"), finding.usageAttributes)
        assertTrue(finding.message, finding.message.contains(
            "; tasks in `playbook-initial-setup.yml`, `roles/totp-token/tasks/extra.yml` read `item.name`/`item.secret_file_src`/`item.mode`;",
        ))
        WriteAction.runAndWait<Throwable> { vf("repos/falcon/ansible/playbook-initial-setup.yml").delete(this) }
        assertTrue(findings(inventory).first().message, findings(inventory).first().message.contains("; role tasks read `item.name`/`item.mode`;"))
    }

    fun testSeveralUnappliedRolesAreNamedTogether() {
        createFile(
            "repos/falcon/ansible/roles/totp-legacy/meta/argument_specs.yml",
            "argument_specs:\n  main:\n    options:\n      totp_users:\n        type: list\n        elements: str\n",
        )
        val finding = findings("repos/falcon/ansible/environments/prod/group_vars/all/vars.yml").first()
        assertEquals(listOf("totp-legacy", "totp-token"), finding.bindings.map { it.role.name })
        assertTrue(finding.message, finding.message.startsWith("documented `elements: str` for roles `totp-legacy` and `totp-token` (entry point `main`);"))
        assertTrue(finding.message, finding.message.endsWith(
            "; roles `totp-legacy` and `totp-token` are not applied by any play in this root; their specs may be stale",
        ))
        assertFalse(finding.reachable!!)
    }

    fun testRoleDefaultsUseOnlyTheirOwnSpec() {
        val path = "repos/falcon/ansible/roles/grafana/defaults/types.yml"
        createFile(path, "system_networking_main_ip: 10\n")
        val finding = findings(path).single()
        assertEquals(listOf("grafana"), finding.bindings.map { it.role.name })
        assertNull(finding.reachable)
        val inventory = "repos/falcon/ansible/environments/prod/group_vars/ip.yml"
        createFile(inventory, "system_networking_main_ip: 10\n")
        assertEquals(listOf("grafana", "haproxy", "loki", "system"), findings(inventory).single().bindings.map { it.role.name })
    }

    fun testTargetVersionDecidesWhetherStrRejectsNull() {
        val path = "repos/falcon/ansible/environments/prod/group_vars/nulls.yml"
        createFile(path, "haproxy_log_path: null\n")
        assertEquals(listOf("1: ANS-T005"), codes(path))
        updateRoot("repos/falcon/ansible") { it.copy(targetCore = "2.21.4") }
        assertEmpty("2.19.1+ turns None into '' for str options", codes(path))
    }

    fun testSecretsAreNotShown() {
        createFile("secret/ansible.cfg", "[defaults]\n")
        createFile(
            "secret/roles/app/meta/argument_specs.yml",
            """
            argument_specs:
              main:
                options:
                  app_token:
                    type: int
                    no_log: true
                  app_settings:
                    type: str
            """.trimIndent() + "\n",
        )
        val path = "secret/group_vars/all.yml"
        createFile(path, "app_token: hunter2\napp_settings:\n  vault_password: hunter2\n")
        val messages = findings(path).map { it.message }
        assertEquals(2, messages.size)
        assertTrue(messages.toString(), messages.none { "hunter2" in it })
        assertTrue(messages[0], messages[0].startsWith("ansible-core 2.18.8 would reject the value of `app_token` for role `app` (entry point `main`)"))
        assertTrue(messages[1], messages[1].startsWith("documented `str` for role `app` (entry point `main`); ansible-core 2.18.8 would coerce the value of `app_settings`"))
    }

    fun testFindingsAreCachedUntilTheFileChanges() {
        val path = "repos/falcon/ansible/environments/prod/group_vars/cached.yml"
        createFile(path, "haproxy_backports_version: 3.3\n")
        val first = runReadActionBlocking { TypeCheckService.getInstance(project).findings(psi(path)) }
        val second = runReadActionBlocking { TypeCheckService.getInstance(project).findings(psi(path)) }
        assertSame(first, second)
        val document = runReadActionBlocking { psi(path).viewProvider.document!! }
        WriteCommandAction.runWriteCommandAction(project) { document.replaceString(0, document.textLength, "haproxy_backports_version: \"3.3\"\n") }
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        assertEmpty(codes(path))
    }
}
