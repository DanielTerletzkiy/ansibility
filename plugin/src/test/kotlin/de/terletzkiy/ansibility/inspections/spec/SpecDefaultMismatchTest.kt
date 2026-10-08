package de.terletzkiy.ansibility.inspections.spec

import com.intellij.codeInspection.InspectionManager
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.psi.PsiManager

/**
 * ANS-S003 (plan amendment R23, D169–D171): the documented `default:` against the role default Ansible uses, on the
 * user's case (`version: 1.2` in defaults, `default: 1.5` in the spec), type-only differences, templates, secrets,
 * sub-options, entry points, a `defaults/main/` directory, dependency defaults, the quiet twin and the fixes.
 */
class SpecDefaultMismatchTest : SpecDefaultTestCase() {
    private val inspection = AnsibleSpecDefaultMismatchInspection::class.java

    override fun addFiles() {
        file("$ROOT/site.yml", "- hosts: all\n  roles: [web, api, db, cache, app, legacy]")
        file(WEB_SPEC, WEB_SPEC_TEXT)
        file(WEB_DEFAULTS, WEB_DEFAULTS_TEXT)
        file(
            API_SPEC,
            """
            ---
            argument_specs:
              main:
                options:
                  api_version:
                    default: 1.5
              install:
                options:
                  api_version:
                    default: 1.2
              extra:
                options:
                  api_version:
                    default: 9.9
            """,
        )
        file(API_DEFAULTS, "---\napi_version: 1.2")
        file("$ROOT/roles/db/defaults/main/10-base.yml", "db_version: 1.2\ndb_port: 1")
        file("$ROOT/roles/db/defaults/main/20-over.yml", "db_version: \"1.3\"")
        file("$ROOT/roles/db/defaults/other.yml", "db_other: 5")
        file(
            DB_SPEC,
            """
            ---
            argument_specs:
              main:
                options:
                  db_version:
                    type: str
                    default: 1.2
                  db_port:
                    type: int
                    default: 1
                  db_other:
                    type: int
                    default: 6
            """,
        )
        file("$ROOT/roles/cache/defaults/main.yml", "cache_size: 64")
        file("$ROOT/roles/cache/defaults/main/10-dir.yml", "cache_size: 128")
        file(CACHE_SPEC, "argument_specs:\n  main:\n    options:\n      cache_size:\n        type: int\n        default: 128")
        file("$ROOT/roles/app/meta/main.yml", "dependencies:\n  - base")
        file("$ROOT/roles/app/tasks/main.yml", "- name: Use\n  ansible.builtin.debug:\n    msg: \"{{ app_port }}\"")
        file(APP_SPEC, "argument_specs:\n  main:\n    options:\n      app_port:\n        type: int\n        default: 8080")
        file("$ROOT/roles/base/defaults/main.yml", "app_port: 80")
        file("$ROOT/roles/hidden/defaults/main.yml", "hidden_conf:\n  key: b")
        file(HIDDEN_SPEC, "argument_specs:\n  main:\n    options:\n      hidden_conf:\n        type: dict\n        no_log: true\n        options:\n          key:\n            type: str\n            default: a")
        file("$ROOT/roles/legacy/defaults/main.yml", "legacy_mode: a")
        file(LEGACY_META, "dependencies: []\nargument_specs:\n  main:\n    options:\n      legacy_mode:\n        default: b")
    }

    fun testTheUsersCaseAndEveryKindOfMismatchInTheSpec() {
        assertEquals(
            listOf(
                "8 ERROR \"1.5\"",
                "20 WARNING \"1.20\"",
                "22 WARNING 1.5",
                "38 ERROR 5432",
                "44 WEAK_WARNING ''",
                "47 ERROR [a]",
                "50 ERROR \"no\"",
                "51 ERROR '1.5'",
            ),
            highlights(WEB_SPEC, inspection),
        )
        assertEquals(
            "argument_specs documents default \"1.5\" for 'version', but Ansible uses 1.2 from defaults/main.yml:2; " +
                "the documented default is never applied",
            message(WEB_SPEC, inspection, 8),
        )
        assertEquals(
            "argument_specs documents default 1.5 for 'web_chain', but Ansible uses 1.2 (through 'web_base') from defaults/main.yml:8; " +
                "the documented default is never applied",
            message(WEB_SPEC, inspection, 22),
        )
        assertEquals(
            "argument_specs documents default 5432 for 'web_db.port', but Ansible uses 5433 from defaults/main.yml:16; " +
                "the documented default is never applied",
            message(WEB_SPEC, inspection, 38),
        )
    }

    fun testSecretsAreNeverComparedOrShown() {
        val messages = (infos(WEB_SPEC, inspection) + infos(WEB_DEFAULTS, inspection)).mapNotNull { it.description }
        for (secret in listOf("secret-1", "other-secret", "61626364", "plain", "web_quiet", "vault_web_password", "web_token")) {
            assertTrue("'$secret' in $messages", messages.none { secret in it })
        }
    }

    fun testTheKeysOfANoLogDictionaryAreSecretToo() {
        assertEquals(emptyList<String>(), highlights(HIDDEN_SPEC, inspection))
    }

    fun testTheDefaultsKeyGetsAQuietHintWithTheFixes() {
        assertEquals(
            listOf("2 INFO version", "6 INFO web_same", "8 INFO web_chain", "16 INFO port", "17 INFO web_null", "18 INFO web_list", "21 INFO web_answer", "22 INFO web_single"),
            highlights(WEB_DEFAULTS, inspection),
        )
        assertEquals(
            "argument_specs documents default \"1.5\" for 'version' (meta/argument_specs.yml:8), but Ansible uses this value; " +
                "the documented default is never applied",
            message(WEB_DEFAULTS, inspection, 2),
        )
        assertEquals(listOf("Set the documented default to 1.2", "Remove the documented default"), fixNames(WEB_DEFAULTS, inspection, 2))
    }

    fun testBatchInspectionCountsEachMismatchOnce() {
        val defaults = runReadActionBlocking { PsiManager.getInstance(project).findFile(vf(WEB_DEFAULTS))!! }
        val spec = runReadActionBlocking { PsiManager.getInstance(project).findFile(vf(WEB_SPEC))!! }
        val manager = InspectionManager.getInstance(project)
        assertNull(runReadActionBlocking { AnsibleSpecDefaultMismatchInspection().checkFile(defaults, manager, false) })
        assertEquals(8, runReadActionBlocking { AnsibleSpecDefaultMismatchInspection().checkFile(spec, manager, false) }!!.size)
    }

    fun testEveryEntryPointIsCheckedAgainstTheSameRoleDefault() {
        assertEquals(listOf("6 ERROR 1.5", "14 ERROR 9.9"), highlights(API_SPEC, inspection))
        assertEquals(
            "argument_specs (entry point 'extra') documents default 9.9 for 'api_version', but Ansible uses 1.2 from defaults/main.yml:2; " +
                "the documented default is never applied",
            message(API_SPEC, inspection, 14),
        )
        assertEquals(
            "argument_specs documents default 1.5 for 'api_version' (meta/argument_specs.yml:6 and 1 more), but Ansible uses this value; " +
                "the documented default is never applied",
            message(API_DEFAULTS, inspection, 2),
        )
        apply(API_DEFAULTS, inspection, 2, "Set the documented default to 1.2")
        assertEquals("the twin's fix updates every differing entry point", emptyList<String>(), highlights(API_SPEC, inspection))
        assertEquals(3, Regex("default: 1\\.2").findAll(text(API_SPEC)).count())
    }

    fun testADefaultsDirectoryLoadsInOrderAndTheLastFileWins() {
        assertEquals(listOf("7 ERROR 1.2"), highlights(DB_SPEC, inspection))
        assertEquals(
            "argument_specs documents default 1.2 for 'db_version', but Ansible uses \"1.3\" from defaults/main/20-over.yml:1; " +
                "the documented default is never applied",
            message(DB_SPEC, inspection, 7),
        )
    }

    fun testAMainFileHidesTheDefaultsDirectory() {
        assertEquals(listOf("6 ERROR 128"), highlights(CACHE_SPEC, inspection))
        assertTrue(message(CACHE_SPEC, inspection, 6).contains("Ansible uses 64 from defaults/main.yml:1"))
    }

    fun testDependencyDefaultsAreNotCompared() {
        assertEquals(emptyList<String>(), highlights(APP_SPEC, inspection))
    }

    fun testASpecInMetaMainIsChecked() {
        assertEquals(listOf("6 ERROR b"), highlights(LEGACY_META, inspection))
    }

    fun testSetTheDocumentedDefaultKeepsTheSpecsQuotingWhenItMeansTheSame() {
        assertEquals(listOf("Set the documented default to 1.2", "Remove the documented default"), fixNames(WEB_SPEC, inspection, 8))
        apply(WEB_SPEC, inspection, 8, "Set the documented default to 1.2")
        assertEquals("        default: \"1.2\"", text(WEB_SPEC).lines()[7])
        // `"yes"` would document the string 'yes', but Ansible's str() of the role default `yes` is 'True'.
        apply(WEB_SPEC, inspection, 50, "Set the documented default to yes")
        assertEquals("        default: yes", text(WEB_SPEC).lines()[49])
        apply(WEB_SPEC, inspection, 51, "Set the documented default to 1.2")
        assertEquals("      web_single: {type: str, default: '1.2'}", text(WEB_SPEC).lines()[50])
        assertEquals(listOf("20 WARNING \"1.20\"", "22 WARNING 1.5", "38 ERROR 5432", "44 WEAK_WARNING ''", "47 ERROR [a]"), highlights(WEB_SPEC, inspection))
    }

    fun testSetTheDocumentedDefaultReindentsABlockCollection() {
        apply(WEB_SPEC, inspection, 47, "Set the documented default to [a, b]")
        assertTrue(text(WEB_SPEC), text(WEB_SPEC).endsWith("      web_list:\n        type: list\n        default:\n          - a\n          - b\n      web_answer:\n        type: str\n        default: \"no\"\n      web_single: {type: str, default: '1.5'}\n"))
        assertTrue(highlights(WEB_SPEC, inspection).none { it.startsWith("47 ") || it.contains("[a]") })
    }

    fun testSetTheDocumentedDefaultOfAChainDocumentsItsLiteral() {
        apply(WEB_SPEC, inspection, 22, "Set the documented default to 1.2")
        assertEquals("        default: 1.2", text(WEB_SPEC).lines()[21])
    }

    fun testRemoveTheDocumentedDefault() {
        apply(WEB_SPEC, inspection, 22, "Remove the documented default")
        assertEquals("an option left without keys stays a mapping", "      web_chain: {}", text(WEB_SPEC).lines()[20])
        assertEquals("      web_jinja:", text(WEB_SPEC).lines()[21])
        apply(WEB_SPEC, inspection, 49, "Remove the documented default")
        assertTrue(text(WEB_SPEC).endsWith("      web_answer:\n        type: str\n      web_single: {type: str, default: '1.5'}\n"))
        val single = highlights(WEB_SPEC, inspection).single { it.endsWith("'1.5'") }.substringBefore(' ').toInt()
        apply(WEB_SPEC, inspection, single, "Remove the documented default")
        assertTrue(text(WEB_SPEC).endsWith("      web_single: {type: str}\n"))
        val defaults = text(WEB_DEFAULTS)
        assertEquals("no fix touches defaults/", WEB_DEFAULTS_TEXT.trimIndent() + "\n", defaults)
    }

    private companion object {
        const val WEB_SPEC = "$ROOT/roles/web/meta/argument_specs.yml"
        const val WEB_DEFAULTS = "$ROOT/roles/web/defaults/main.yml"
        const val API_SPEC = "$ROOT/roles/api/meta/argument_specs.yml"
        const val API_DEFAULTS = "$ROOT/roles/api/defaults/main.yml"
        const val DB_SPEC = "$ROOT/roles/db/meta/argument_specs.yml"
        const val CACHE_SPEC = "$ROOT/roles/cache/meta/argument_specs.yml"
        const val APP_SPEC = "$ROOT/roles/app/meta/argument_specs.yml"
        const val LEGACY_META = "$ROOT/roles/legacy/meta/main.yml"
        const val HIDDEN_SPEC = "$ROOT/roles/hidden/meta/argument_specs.yml"

        val WEB_SPEC_TEXT = """
            ---
            argument_specs:
              main:
                short_description: web
                options:
                  version:
                    type: str
                    default: "1.5"
                  web_version:
                    type: str
                    default: "3.2"
                  web_flag:
                    type: bool
                    default: yes
                  web_port:
                    type: int
                    default: "8080"
                  web_same:
                    type: str
                    default: "1.20"
                  web_chain:
                    default: 1.5
                  web_jinja:
                    default: other
                  vault_web_password:
                    default: other-secret
                  web_token:
                    default: plain
                  web_quiet:
                    type: str
                    no_log: true
                    default: y
                  web_db:
                    type: dict
                    options:
                      port:
                        type: int
                        default: 5432
                      host:
                        type: str
                        default: localhost
                  web_null:
                    type: str
                    default: ''
                  web_list:
                    type: list
                    default: [a]
                  web_answer:
                    type: str
                    default: "no"
                  web_single: {type: str, default: '1.5'}
            """

        val WEB_DEFAULTS_TEXT = """
            ---
            version: 1.2
            web_version: 3.2
            web_flag: true
            web_port: 8080
            web_same: 1.20
            web_base: 1.2
            web_chain: "{{ web_base }}"
            web_jinja: "{{ web_base }}-x"
            vault_web_password: secret-1
            web_token: !vault |
              ${'$'}ANSIBLE_VAULT;1.1;AES256
              61626364
            web_quiet: z
            web_db:
              port: 5433
            web_null:
            web_list:
              - a
              - b
            web_answer: yes
            web_single: 1.2
            """
    }
}
