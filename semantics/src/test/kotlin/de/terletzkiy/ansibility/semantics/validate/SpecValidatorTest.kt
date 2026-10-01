package de.terletzkiy.ansibility.semantics.validate

import de.terletzkiy.ansibility.semantics.CoreVersion
import de.terletzkiy.ansibility.semantics.coerce.CoreSemantics
import de.terletzkiy.ansibility.semantics.diagnostics.DiagnosticCode
import de.terletzkiy.ansibility.semantics.diagnostics.DiagnosticCode.M001_UNKNOWN_MODULE_OPTION
import de.terletzkiy.ansibility.semantics.diagnostics.DiagnosticCode.M002_MISSING_MODULE_OPTION
import de.terletzkiy.ansibility.semantics.diagnostics.DiagnosticCode.T001_VALUE_REJECTED
import de.terletzkiy.ansibility.semantics.diagnostics.DiagnosticCode.T002_UNSUPPORTED_SUB_OPTION
import de.terletzkiy.ansibility.semantics.diagnostics.DiagnosticCode.T003_MISSING_REQUIRED_SUB_OPTION
import de.terletzkiy.ansibility.semantics.diagnostics.DiagnosticCode.T004_CHOICE_MISMATCH
import de.terletzkiy.ansibility.semantics.diagnostics.DiagnosticCode.T005_NULL_FOR_TYPED_OPTION
import de.terletzkiy.ansibility.semantics.diagnostics.DiagnosticCode.T010_SHAPE_CONTRADICTION
import de.terletzkiy.ansibility.semantics.diagnostics.DiagnosticCode.T011_COERCED_SCALAR_TO_STR
import de.terletzkiy.ansibility.semantics.diagnostics.DiagnosticCode.T013_SCALAR_TYPE_MISMATCH
import de.terletzkiy.ansibility.semantics.diagnostics.DiagnosticCode.T014_LEGACY_COERCION
import de.terletzkiy.ansibility.semantics.diagnostics.DiagnosticCode.T015_NULL_FOR_OPTIONAL
import de.terletzkiy.ansibility.semantics.diagnostics.DiagnosticCode.T016_STRING_FOR_NUMBER_OR_BOOL
import de.terletzkiy.ansibility.semantics.diagnostics.Finding
import de.terletzkiy.ansibility.semantics.schema.SpecOrigin
import de.terletzkiy.ansibility.semantics.testutil.YamlText
import de.terletzkiy.ansibility.semantics.yaml.SourceRange
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpecValidatorTest {
    private val v2214 = CoreSemantics(CoreVersion(2, 21, 4))

    private fun check(
        spec: String,
        values: String,
        role: String = "demo",
        semantics: CoreSemantics = CoreSemantics.PINNED,
        kind: SpecKind = SpecKind.ROLE,
    ): List<Finding> {
        val options = SpecText.options(spec, SpecOrigin.RoleSpec(role, "main"))
        return SpecValidator(semantics, kind).validate(options, YamlText.map(values)).findings
    }

    private fun List<Finding>.codes() = map { it.code }

    private fun List<Finding>.single(code: DiagnosticCode): Finding {
        val matching = filter { it.code == code }
        assertEquals(1, matching.size, "expected one $code in $this")
        return matching.single()
    }

    /** The source text a finding points at. */
    private fun String.at(range: SourceRange?): String = substring(range!!.start, range.end)

    // ------------------------------------------------------------------------------------------ real shapes

    @Test
    fun `haproxy_backports_version 3_2 for a str option is T011`() {
        val values = "haproxy_backports_version: 3.2\n"
        val findings = check("haproxy_backports_version: {type: str, default: \"3.2\"}", values, role = "haproxy")
        val finding = findings.single(T011_COERCED_SCALAR_TO_STR)
        assertEquals(
            "documented `str` for role `haproxy` (entry point `main`); ansible-core 2.18.8 would coerce `3.2` → `'3.2'` " +
                "(the role itself still receives `3.2`)",
            finding.message,
        )
        assertEquals("3.2", values.at(finding.range))
        assertEquals(listOf("haproxy_backports_version"), finding.path)
        assertEquals(listOf("quote"), finding.fixHints)
        assertEquals(listOf(T011_COERCED_SCALAR_TO_STR), findings.codes(), "3.2 is accepted, so nothing else fires")
    }

    @Test
    fun `YAML 1_1 hazards are named in T011`() {
        val finding = check("v: {type: str}", "v: 3.10").single(T011_COERCED_SCALAR_TO_STR)
        assertTrue("would coerce `3.1` → `'3.1'`" in finding.message, finding.message)
        assertTrue("YAML 1.1 reads `3.10` as `3.1`" in finding.message, finding.message)
        assertTrue("would coerce `True` → `'True'`" in check("v: {type: path}", "v: yes").single(T011_COERCED_SCALAR_TO_STR).message)
        assertEquals(1, check("v: {type: str}", "v: 2024-01-01").count { it.code == T011_COERCED_SCALAR_TO_STR })
    }

    @Test
    fun `totp_users dicts against elements str are T010 per item`() {
        val values = """
            totp_users:
              - name: alice
                secret_file_src: files/alice
              - name: bob
                secret_file_src: files/bob
        """.trimIndent()
        val findings = check("totp_users: {type: list, elements: str, required: true}", values, role = "totp-token")
        assertEquals(listOf(T010_SHAPE_CONTRADICTION, T010_SHAPE_CONTRADICTION), findings.codes())
        val first = findings.first()
        assertEquals(listOf("totp_users", "0"), first.path)
        assertTrue(first.message.startsWith("documented `elements: str` for role `totp-token` (entry point `main`); " +
            "ansible-core 2.18.8 would stringify `{'name': 'alice', 'secret_file_src': 'files/alice'}` → "), first.message)
        assertTrue(values.at(first.range).startsWith("name: alice"))
        assertEquals(listOf("totp_users", "1"), findings[1].path)
    }

    @Test
    fun `unknown nested key optional_client_scopes is T002 at the key`() {
        val spec = """
            keycloak_clients:
              type: list
              elements: dict
              options:
                client_id: {type: str, required: true}
                default_client_scopes: {type: list, elements: str}
        """.trimIndent()
        val values = """
            keycloak_clients:
              - client_id: app
                default_client_scopes: [profile]
              - client_id: other
                optional_client_scopes: [offline_access]
        """.trimIndent()
        val findings = check(spec, values, role = "keycloak")
        val finding = findings.single(T002_UNSUPPORTED_SUB_OPTION)
        assertEquals("optional_client_scopes", values.at(finding.range))
        assertEquals(listOf("keycloak_clients", "1", "optional_client_scopes"), finding.path)
        assertTrue(
            finding.message.startsWith("Unsupported key `optional_client_scopes` in `keycloak_clients[1]` for role `keycloak` " +
                "(entry point `main`): ansible-core 2.18.8 rejects unknown keys here (supported: client_id, default_client_scopes)"),
            finding.message,
        )
        assertEquals(listOf("remove-key"), finding.fixHints)
        assertEquals(listOf(T002_UNSUPPORTED_SUB_OPTION), findings.codes())
    }

    @Test
    fun `port quoted 444 for an int option is T016 with unquote`() {
        val values = "port: \"444\"\n"
        val finding = check("port: {type: int}", values).single(T016_STRING_FOR_NUMBER_OR_BOOL)
        assertTrue("ansible-core 2.18.8 would coerce `'444'` → `444` (the role itself still receives `'444'`)" in finding.message, finding.message)
        assertEquals("\"444\"", values.at(finding.range))
        assertEquals(listOf("unquote"), finding.fixHints)
        val octal = check("mode: {type: int}", "mode: \"0644\"").single(T016_STRING_FOR_NUMBER_OR_BOOL)
        assertEquals(emptyList<String>(), octal.fixHints, "unquoted 0644 would load as octal 420, not 644")
    }

    @Test
    fun `enabled 1 for a bool option is T013`() {
        val finding = check("enabled: {type: bool}", "enabled: 1").single(T013_SCALAR_TYPE_MISMATCH)
        assertTrue("would coerce `1` → `True`" in finding.message, finding.message)
        assertEquals(listOf("replace=true"), finding.fixHints)
    }

    // ------------------------------------------------------------------------------------------ every code

    @Test
    fun `T001 rejected values`() {
        val values = "port: abc\ncount: 42.5\nflag: maybe\n"
        val findings = check("port: {type: int}\ncount: {type: int}\nflag: {type: bool}", values)
        val rejections = findings.filter { it.code == T001_VALUE_REJECTED }
        assertEquals(listOf(listOf("port"), listOf("count"), listOf("flag")), rejections.map { it.path })
        assertTrue(
            rejections[0].message.startsWith("ansible-core 2.18.8 would reject `port` for role `demo` (entry point `main`): " +
                "argument 'port' is of type <class 'str'> and we were unable to convert to int: \"'abc'\" cannot be converted to an int"),
            rejections[0].message,
        )
        assertEquals("abc", values.at(rejections[0].range))
        // The documented-type findings are still reported (independent of acceptance) and say "would reject".
        val t016 = findings.first { it.code == T016_STRING_FOR_NUMBER_OR_BOOL && it.path == listOf("port") }
        assertTrue("ansible-core 2.18.8 would reject it:" in t016.message, t016.message)
        assertEquals(listOf(T013_SCALAR_TYPE_MISMATCH), findings.filter { it.path == listOf("count") && it.code != T001_VALUE_REJECTED }.codes())
    }

    @Test
    fun `T001 for elements, crashes and invalid spec types`() {
        val elements = check("ports: {type: list, elements: int}", "ports: [80, http, 443]")
        assertEquals(listOf("ports", "1"), elements.single(T001_VALUE_REJECTED).path)
        val values = "a: 'inf'\nb: abc\n"
        val crash = check("a: {type: int}\nb: {type: int}", values)
        val crashFinding = crash.first { it.code == T001_VALUE_REJECTED && it.path == listOf("a") }
        assertTrue("would crash with OverflowError (cannot convert Infinity to integer)" in crashFinding.message, crashFinding.message)
        assertTrue(crash.any { it.code == T001_VALUE_REJECTED && it.path == listOf("b") }, "later values are still checked")
        val invalid = check("v: {type: string}", "v: x").single(T001_VALUE_REJECTED)
        assertTrue("'NoneType' object is not callable" in invalid.message, invalid.message)
    }

    @Test
    fun `T003 missing nested required key`() {
        val values = "db:\n  port: 5432\n"
        val finding = check("db: {type: dict, options: {host: {type: str, required: true}, port: {type: int}}}", values)
            .single(T003_MISSING_REQUIRED_SUB_OPTION)
        assertEquals(listOf("db"), finding.path)
        assertEquals(listOf("add-key=host"), finding.fixHints)
        assertTrue("\"missing required arguments: host found in db\"" in finding.message, finding.message)
        assertEquals("port: 5432\n", values.at(finding.range))
        assertEquals(emptyList<Finding>(), check("db: {type: dict, options: {host: {type: str, required: true}}}", "other: 1"))
    }

    @Test
    fun `T004 choices with nearest-choice and per element`() {
        val values = "state: absentx\nmodes: [read, wrte]\n"
        val findings = check("state: {type: str, choices: [present, absent]}\nmodes: {type: list, elements: str, choices: [read, write]}", values)
        val state = findings.first { it.path == listOf("state") }
        assertEquals(T004_CHOICE_MISMATCH, state.code)
        assertEquals(listOf("nearest-choice=absent"), state.fixHints)
        assertTrue("value of state must be one of: present, absent, got: absentx" in state.message, state.message)
        val mode = findings.first { it.path == listOf("modes", "1") }
        assertEquals(T004_CHOICE_MISMATCH, mode.code)
        assertEquals("wrte", values.at(mode.range))
        assertEquals(listOf("nearest-choice=write"), mode.fixHints)
        // Module regressions: the bool rescue, dict choices and raw values are never rejected. (`false` for a
        // str option is still a documented-type coercion, which the plugin's module policy keeps silent.)
        val rescued = check(
            "follow_redirects: {type: str, choices: [all, 'no', none, safe, urllib2, 'yes']}", "follow_redirects: false",
            kind = SpecKind.MODULE,
        )
        assertEquals(listOf(T011_COERCED_SCALAR_TO_STR), rescued.codes())
        assertTrue("would coerce `False` → `'False'`, which `choices` maps to `'no'`" in rescued.single().message, rescued.single().message)
        assertEquals(emptyList<Finding>(), check("follow_redirects: {type: str, choices: {none: n, all: a, safe: s}}", "follow_redirects: none", kind = SpecKind.MODULE))
        assertEquals(emptyList<Finding>(), check("mode: {type: raw}", "mode: \"0644\"", kind = SpecKind.MODULE))
    }

    @Test
    fun `T005 null for required or defaulted options, str only on 2_18`() {
        val values = "enabled: ~\nname: ~\n"
        val spec = "enabled: {type: bool, default: false}\nname: {type: str, required: true}"
        val pinned = check(spec, values)
        assertEquals(listOf(listOf("enabled"), listOf("name")), pinned.filter { it.code == T005_NULL_FOR_TYPED_OPTION }.map { it.path })
        assertTrue(pinned.first().message.startsWith("`null` for defaulted `bool` option `enabled` for role `demo`"), pinned.first().message)
        val latest = check(spec, values, semantics = v2214)
        assertEquals(listOf(listOf("enabled")), latest.filter { it.code == T005_NULL_FOR_TYPED_OPTION }.map { it.path }, "2.21 turns None into ''")
    }

    @Test
    fun `T014 legacy coercions`() {
        val findings = check("hosts: {type: list}\nopts: {type: dict}\nbad: {type: dict}\nnum: {type: list}", "hosts: \"a,b\"\nopts: \"a=1 b=2\"\nbad: abc\nnum: 5")
        val byPath = findings.filter { it.code == T014_LEGACY_COERCION }.associateBy { it.path.single() }
        assertTrue("would split `'a,b'` → `['a', 'b']`" in byPath.getValue("hosts").message)
        assertTrue("would parse `'a=1 b=2'` → `{'a': '1', 'b': '2'}`" in byPath.getValue("opts").message)
        assertTrue("would reject it: dictionary requested, could not parse JSON or key=value" in byPath.getValue("bad").message)
        assertTrue("would wrap `5` → `['5']`" in byPath.getValue("num").message)
        assertEquals(listOf("to-sequence"), byPath.getValue("hosts").fixHints)
        assertEquals(listOf("to-mapping"), byPath.getValue("opts").fixHints)
        assertTrue(findings.any { it.code == T001_VALUE_REJECTED && it.path == listOf("bad") })
    }

    @Test
    fun `T013 and T016 scalar rules`() {
        val findings = check(
            "a: {type: int}\nb: {type: int}\nc: {type: float}\nd: {type: float}\ne: {type: bool}\nf: {type: float}",
            "a: 42.0\nb: true\nc: 3\nd: yes\ne: \"true\"\nf: \"2.5\"",
        )
        assertEquals(listOf("replace=42"), findings.first { it.path == listOf("a") }.fixHints)
        assertEquals(T013_SCALAR_TYPE_MISMATCH, findings.first { it.path == listOf("b") }.code)
        assertTrue(findings.none { it.path == listOf("c") }, "an int for float only widens")
        assertEquals(listOf("replace=1.0"), findings.first { it.path == listOf("d") }.fixHints)
        assertEquals(listOf("unquote"), findings.first { it.path == listOf("e") }.fixHints)
        assertEquals(T016_STRING_FOR_NUMBER_OR_BOOL, findings.first { it.path == listOf("f") }.code)
        val conditional = check("e: {type: bool}", "e: \"yes\"", semantics = v2214).single(T016_STRING_FOR_NUMBER_OR_BOOL)
        assertTrue("conditionals must be bool" in conditional.message, conditional.message)
    }

    @Test
    fun `T015 null for optional option without default`() {
        val values = "chronod_server_certificate:\n"
        val finding = check("chronod_server_certificate: {type: str}", values).single(T015_NULL_FOR_OPTIONAL)
        assertTrue("ansible-core 2.18.8 skips validation and the role receives `None`" in finding.message, finding.message)
        assertEquals(listOf(T015_NULL_FOR_OPTIONAL), check("s: {type: list, elements: str}", "s: ~").codes())
        assertEquals(listOf(T004_CHOICE_MISMATCH), check("s: {type: str, choices: [a]}", "s: ~").codes(), "choices still reject None")
    }

    // ------------------------------------------------------------------------------------------ scope rules

    @Test
    fun `role top level checks only declared names, nested keys strictly`() {
        assertEquals(emptyList<Finding>(), check("known: {type: int}", "unknown: abc\nother: [1]"))
        assertEquals(emptyList<Finding>(), check("req: {type: str, required: true}", "unrelated: 1"), "top-level required is not decidable per file")
        val nested = check("d: {type: dict, options: {x: {type: int}}}", "d: {x: 1, y: 2}")
        assertEquals(listOf(T002_UNSUPPORTED_SUB_OPTION), nested.codes())
    }

    @Test
    fun `module options flag unknown and missing top-level options`() {
        val values = "path: /tmp/x\nstat: present\n"
        val findings = check("path: {type: path, required: true, aliases: [dest]}\nstate: {type: str}\nmode: {type: raw, required: true}", values, kind = SpecKind.MODULE)
        val unknown = findings.single(M001_UNKNOWN_MODULE_OPTION)
        assertEquals("stat", values.at(unknown.range))
        assertEquals(listOf("remove-key", "nearest-key=state"), unknown.fixHints)
        assertEquals(listOf("add-key=mode"), findings.single(M002_MISSING_MODULE_OPTION).fixHints)
        val alias = check("path: {type: int, aliases: [dest]}", "dest: abc", kind = SpecKind.MODULE)
        assertEquals("abc", "dest: abc".at(alias.single(T001_VALUE_REJECTED).range), "errors on the canonical name point at the alias")
    }

    @Test
    fun `templates vault and unloadable values are never judged`() {
        val values = "port: \"{{ p }}\"\nname: !vault |\n  \$ANSIBLE_VAULT;1.1;AES256\n  6162\nd: {x: \"{{ y }}\"}\n"
        val findings = check("port: {type: int}\nname: {type: int}\nd: {type: dict, options: {x: {type: bool}}}", values)
        assertEquals(emptyList<Finding>(), findings)
        val unsafe = SpecValidator().validate(SpecText.options("v: {type: int}"), YamlText.map("v: \"{% raw %}\""))
        assertTrue(unsafe.findings.isEmpty())
        val shape = check("v: {type: str}", "v: [a, \"{{ b }}\"]").single(T010_SHAPE_CONTRADICTION)
        assertTrue("would stringify this list (the exact text depends on templated values)" in shape.message, shape.message)
    }

    @Test
    fun `primary keeps one finding per rejected value`() {
        val validation = SpecValidator().validate(
            SpecText.options("port: {type: int, choices: [1, 2]}\nok: {type: str}"),
            YamlText.map("port: \"abc\"\nok: 1"),
        )
        assertEquals(setOf(T001_VALUE_REJECTED, T004_CHOICE_MISMATCH, T016_STRING_FOR_NUMBER_OR_BOOL, T011_COERCED_SCALAR_TO_STR),
            validation.findings.codes().toSet())
        assertEquals(listOf(T001_VALUE_REJECTED, T011_COERCED_SCALAR_TO_STR), validation.primary.codes())
        assertTrue(!validation.result.accepted)
    }

    @Test
    fun `validateValue checks one vars-file key`() {
        val option = SpecText.option("grafana_version", "{type: str}", SpecOrigin.RoleSpec("grafana", "main"))
        val value = YamlText.map("grafana_version: 12.4")["grafana_version"]!!
        val findings = SpecValidator().validateValue(option, value).findings
        assertEquals(listOf(T011_COERCED_SCALAR_TO_STR), findings.codes())
        assertTrue("for role `grafana`" in findings.single().message)
    }

    @Test
    fun `documented checks recurse into options and elements up to the depth limit`() {
        val spec = "a: {type: dict, options: {b: {type: dict, options: {c: {type: list, elements: int}}}}}"
        val deep = check(spec, "a: {b: {c: [\"1\"]}}")
        assertEquals(listOf(listOf("a", "b", "c", "0")), deep.filter { it.code == T016_STRING_FOR_NUMBER_OR_BOOL }.map { it.path })
        val shallow = SpecValidator(maxDocumentedDepth = 1).validate(SpecText.options(spec), YamlText.map("a: {b: {c: [\"1\"]}}"))
        assertTrue(shallow.findings.none { it.code == T016_STRING_FOR_NUMBER_OR_BOOL })
    }
}
