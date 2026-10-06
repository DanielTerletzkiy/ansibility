package de.terletzkiy.ansibility.typeflow

import de.terletzkiy.ansibility.fixtures.RequiresInfraFixture
import de.terletzkiy.ansibility.semantics.CoreVersion

/**
 * The ANS-T020 analysis (plan A.6 must-rule, F3.4) on a synthetic role of the `golden` root: the exact matrix for
 * ansible-core 2.18.8 and 2.21.4, multi-definition chains, cycles, vault values, nested options, runtime-only names,
 * root scoping and the chain depth setting.
 */
@RequiresInfraFixture
class TemplatedValueTypesTest : TemplatedTestCase() {

    private fun demoRole() {
        copyInfra("golden/roles/haproxy")
        createFile("golden/roles/typedemo/meta/argument_specs.yml", DEMO_SPEC)
        createFile("golden/roles/typedemo/defaults/main.yml", DEMO_DEFAULTS)
        createFile("golden/roles/typedemo/vars/main.yml", "td_mixed: '5'")
        createFile(
            "golden/roles/typedemo/tasks/main.yml",
            """
            - name: Register something
              ansible.builtin.command: echo
              register: td_registered
              changed_when: false
            """,
        )
    }

    /** One play per case (so one option can be assigned many times); returns the path and the line of each case. */
    private fun matrixPlaybook(cases: List<Case>): Pair<String, Map<Case, Int>> {
        val text = StringBuilder()
        val lines = HashMap<Case, Int>()
        var line = 1
        for (case in cases) {
            text.append("- hosts: all\n  roles: [typedemo]\n  vars:\n")
            line += 3
            text.append("    ${case.option}: '${case.template.replace("'", "''")}'\n")
            lines[case] = line
            line++
        }
        val path = "golden/playbooks/typedemo-matrix.yml"
        createFile(path, text.toString())
        return path to lines
    }

    private data class Case(val option: String, val template: String, val red218: Boolean, val red221: Boolean)

    private val matrix = listOf(
        Case("td_str", "{{ td_port }}", red218 = true, red221 = true),
        Case("td_str", "{{ td_port | int }}", red218 = false, red221 = true),
        Case("td_int", "{{ td_port | int }}", red218 = false, red221 = false),
        Case("td_int", "{{ td_items | join(',') }}", red218 = true, red221 = true),
        Case("td_int", "{{ td_port | string }}", red218 = true, red221 = true),
        Case("td_str", "{{ td_items | length }}", red218 = false, red221 = true),
        Case("td_bool", "{{ td_items | join(',') }}", red218 = false, red221 = true),
        Case("td_list", "{{ td_items | join(',') }}", red218 = false, red221 = true),
        Case("td_str", "{{ td_items }}", red218 = true, red221 = true),
        Case("td_int", "{{ td_items }}", red218 = true, red221 = true),
        Case("td_str", "{{ td_started }}", red218 = false, red221 = true),
        Case("td_str", "{{ td_alias }}", red218 = false, red221 = true),
        Case("td_int", "{{ td_alias }}", red218 = false, red221 = false),
        Case("td_int", "x-{{ td_port }}", red218 = true, red221 = true),
        Case("td_str", "x-{{ td_port }}", red218 = false, red221 = false),
        Case("td_str", "{{ td_mixed }}", red218 = false, red221 = false),
        Case("td_int", "{{ td_mixed }}", red218 = false, red221 = false),
        Case("td_bool", "{{ td_mixed }}", red218 = true, red221 = true),
        Case("td_str", "{{ td_cycle_a }}", red218 = false, red221 = false),
        Case("td_int", "{{ vault_td_secret }}", red218 = false, red221 = false),
        Case("td_raw", "{{ td_items }}", red218 = false, red221 = false),
        Case("td_float", "{{ td_port }}", red218 = false, red221 = false),
        Case("td_dict", "{{ td_items }}", red218 = true, red221 = true),
        Case("td_path", "{{ td_port }}", red218 = true, red221 = true),
        Case("td_str", "{{ ansible_hostname }}", red218 = false, red221 = false),
        Case("td_str", "{{ inventory_hostname }}", red218 = false, red221 = false),
        Case("td_str", "{{ td_registered }}", red218 = false, red221 = false),
        Case("td_str", "{{ td_undefined_anywhere }}", red218 = false, red221 = false),
        Case("td_int", "{{ td_port if td_port else td_name }}", red218 = false, red221 = false),
        Case("td_list", "{{ td_port if td_port else td_name }}", red218 = true, red221 = true),
        Case("td_str", "{% if td_port %}{{ td_port }}{% endif %}", red218 = false, red221 = false),
        Case("td_str", "{{ td_port | default(omit) }}", red218 = false, red221 = false),
        Case("td_int", "{{ td_name | default(8080) }}", red218 = false, red221 = false),
        Case("td_bool", "{{ td_port > 1 }}", red218 = false, red221 = false),
        Case("td_str", "{{ td_port > 1 }}", red218 = true, red221 = true),
        // A tuple renders as the string '(1, 2)' on 2.18; 2.19+ hands it over as the list [1, 2].
        Case("td_list", "{{ (1, 2) }}", red218 = true, red221 = false),
        Case("td_str", "{{ (1, 2) }}", red218 = false, red221 = true),
    )

    private fun redLines(path: String): Set<Int> = findings(path).map { lineOf(path, it.range.startOffset) }.toSet()

    fun testMatrixOnBothCores() {
        demoRole()
        val (path, lines) = matrixPlaybook(matrix)
        target(CoreVersion(2, 18, 8))
        val red218 = redLines(path)
        target(CoreVersion(2, 21, 4))
        val red221 = redLines(path)
        val wrong = matrix.filter { (lines.getValue(it) in red218) != it.red218 || (lines.getValue(it) in red221) != it.red221 }
        assertEquals(
            "cases whose verdict differs (case → 2.18 red, 2.21 red)",
            emptyList<String>(),
            wrong.map { "${it.option}: ${it.template} → ${lines.getValue(it) in red218}, ${lines.getValue(it) in red221}" },
        )
    }

    fun testMessagesNameTheChainAndTheCoreOutcome() {
        demoRole()
        val (path, _) = matrixPlaybook(
            listOf(
                Case("td_str", "{{ td_port }}", true, true),
                Case("td_int", "{{ td_items | join(',') }}", true, true),
                Case("td_str", "{{ td_items | length }}", false, true),
                Case("td_int", "{{ td_items }}", true, true),
            ),
        )
        target(CoreVersion(2, 18, 8))
        assertEquals(
            listOf(
                "documented `str` for role `typedemo` (entry point `main`), but this is int `8080` via " +
                    "`td_port: 8080` (roles/typedemo/defaults/main.yml:1); ansible-core 2.18.8 would coerce it to `'8080'` " +
                    "(the role itself still receives `8080`)",
                "documented `int` for role `typedemo` (entry point `main`), but this is str from the trailing `| join` filter; " +
                    "ansible-core 2.18.8 renders it as bool, str, list, dict or another type and would " +
                    "convert it to int where it can and reject it otherwise",
                "documented `int` for role `typedemo` (entry point `main`), but this is list `['a', 'b']` via " +
                    "`td_items` (roles/typedemo/defaults/main.yml:2); ansible-core 2.18.8 would reject it: " +
                    "\"['a', 'b']\" cannot be converted to an int",
            ),
            findings(path).map { it.message },
        )
        target(CoreVersion(2, 21, 4))
        assertContainsElements(
            findings(path).map { it.message },
            "documented `str` for role `typedemo` (entry point `main`), but this is int from the trailing `| length` filter; " +
                "ansible-core 2.21.4 would convert it to str (the role itself still receives the unconverted value)",
        )
    }

    fun testMultiHopChainsNameEveryHop() {
        demoRole()
        createFile("golden/roles/typedemo/defaults/more.yml", "td_alias2: '{{ td_alias }}'")
        val (path, _) = matrixPlaybook(listOf(Case("td_str", "{{ td_alias2 }}", false, true)))
        target(CoreVersion(2, 21, 4))
        val message = findings(path).single().message
        assertTrue(
            message,
            message.contains(
                "via `td_alias2` (roles/typedemo/defaults/more.yml:1) → `td_alias` (roles/typedemo/defaults/main.yml:5) → " +
                    "`td_port: 8080` (roles/typedemo/defaults/main.yml:1)",
            ),
        )
        target(CoreVersion(2, 18, 8))
        assertEmpty("2.18 renders a chain through another template as a string", findings(path))
    }

    fun testOneMatchingDefinitionKeepsTheChainSilent() {
        demoRole()
        createFile("golden/roles/typedemo/defaults/extra.yml", "td_port_text: '8080'")
        createFile("golden/group_vars/all.yml", "td_port_text: 8080")
        val (path, _) = matrixPlaybook(listOf(Case("td_str", "{{ td_port_text }}", false, false), Case("td_int", "{{ td_port_text }}", false, false)))
        target(CoreVersion(2, 21, 4))
        assertEmpty(findings(path))
    }

    fun testVaultValuesAreSilentAndSecretsAreNeverShown() {
        demoRole()
        createFile(
            "golden/roles/typedemo/vars/vault.yml",
            """
            td_secret_port: 5
            td_vaulted: !vault |
              ${'$'}ANSIBLE_VAULT;1.1;AES256
              64756d6d79
            """,
        )
        val (path, _) = matrixPlaybook(
            listOf(
                Case("td_int", "{{ td_vaulted }}", false, false),
                Case("td_str", "{{ td_secret_port }}", true, true),
            ),
        )
        target(CoreVersion(2, 18, 8))
        val finding = findings(path).single()
        assertEquals(listOf("td_str"), finding.path)
        assertFalse("the value of a vault file is never shown: ${finding.message}", finding.message.contains("`5`") || finding.message.contains(": 5"))
        assertTrue(finding.message, finding.message.contains("via `td_secret_port` (roles/typedemo/vars/vault.yml:1)"))
    }

    fun testNestedOptionsAndElementsAreChecked() {
        demoRole()
        createFile(
            "golden/group_vars/all.yml",
            """
            td_servers:
              - name: '{{ td_port }}'
                port: '{{ td_items }}'
              - name: ok
                port: '{{ td_port }}'
            td_names:
              - '{{ td_port }}'
              - '{{ td_name }}'
            """,
        )
        target(CoreVersion(2, 18, 8))
        val found = findings("golden/group_vars/all.yml")
        assertEquals(
            listOf("td_servers[0].name: str", "td_servers[0].port: int", "td_names[0]: elements str"),
            found.map { "${TemplatedMessages.pathText(it.path)}: ${if (it.elements) "elements " else ""}${it.documented.name}" },
        )
        assertTrue(found[1].mismatch.certainRejection)
        assertTrue(found[1].message, found[1].message.contains(" at `td_servers[0].port`"))
        assertTrue(found[2].message, found[2].message.startsWith("documented `elements: str`"))
    }

    fun testRootScoping() {
        demoRole()
        // Another root defines td_port as a string: it must not make the golden chain uncertain.
        createFile("repos/other/ansible/ansible.cfg", "[defaults]\n")
        createFile("repos/other/ansible/group_vars/all.yml", "td_port: 'eighty'")
        val (path, _) = matrixPlaybook(listOf(Case("td_str", "{{ td_port }}", true, true)))
        target(CoreVersion(2, 18, 8))
        assertEquals(1, findings(path).size)
    }

    fun testChainDepthSetting() {
        demoRole()
        val (path, _) = matrixPlaybook(listOf(Case("td_str", "{{ td_alias }}", false, true)))
        target(CoreVersion(2, 21, 4))
        assertEquals(1, findings(path).size)
        rootSettings("golden") { it.copy(chainDepth = 1) }
        assertEmpty("two hops do not fit into a depth of 1", findings(path))
    }

    fun testOnlySpecDeclaredNamesAreChecked() {
        demoRole()
        createFile("golden/group_vars/all.yml", "td_not_in_any_spec: '{{ td_items }}'\ntd_str: '{{ td_items }}'")
        target(CoreVersion(2, 18, 8))
        assertEquals(listOf(listOf("td_str")), findings("golden/group_vars/all.yml").map { it.path })
    }

    fun testJinja2NativeOverrideOn218() {
        demoRole()
        val (path, _) = matrixPlaybook(listOf(Case("td_bool", "{{ td_port | int }}", true, true), Case("td_int", "{{ td_port | string }}", true, false)))
        target(CoreVersion(2, 18, 8))
        assertEquals(2, findings(path).size)
        rootSettings("golden") { it.copy(cfgOverrides = it.cfgOverrides.copy(jinja2Native = true)) }
        // jinja2_native literal_evals rendered strings: '| string' may become anything, '| int' stays int.
        assertEquals(listOf("td_bool"), findings(path).map { it.path.single() })
    }
}
