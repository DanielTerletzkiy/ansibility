package de.terletzkiy.ansibility.vars

import com.intellij.testFramework.IndexingTestUtil

/**
 * The card's "Default" and "Documented (argument_specs)" rows and the Ctrl-hover hint (plan amendment R23, D173/D174)
 * on a synthetic root: the role default Ansible uses is the card's default (Ansible's file selection, a dependency's,
 * the own role's over another role's spec, JSON defaults, merged dictionaries, unreadable files), the documented default
 * only shows where it is not that, flagged exactly when ANS-S003/S004 flag the spec (per entry point, also from a
 * depending role), `{{ name }}` chains resolve as the role's tasks see them, and secrets (`vault_*` names, vault values,
 * `no_log` in any declaring spec) never show in any row, chain or hint.
 */
class VarCardRoleDefaultTest : VarsTestCase() {
    override fun setUp() {
        super.setUp()
        write("$ROOT/ansible.cfg", "[defaults]\nroles_path = roles\n")
        write("$ROOT/site.yml", "- hosts: all\n  roles: [web, app, enc, own, doc, dirrole]\n")
        write(WEB_DEFAULTS, WEB_DEFAULTS_TEXT)
        write(WEB_SPEC, WEB_SPEC_TEXT)
        write(WEB_TASKS, "- name: Use\n  ansible.builtin.debug:\n    msg: \"$WEB_USES\"\n")

        write("$ROOT/roles/app/meta/main.yml", "dependencies:\n  - base\n")
        write("$ROOT/roles/app/meta/argument_specs.yml", spec("app_port", "int", "8080"))
        write(APP_TASKS, use("app_port"))
        write("$ROOT/roles/base/defaults/main.yml", "app_port: 80\n")

        write("$ROOT/roles/enc/defaults/main/10-base.yml", "enc_level: 1\n")
        write("$ROOT/roles/enc/defaults/main/20-secret.yml", "\$ANSIBLE_VAULT;1.1;AES256\n61626364\n")
        write("$ROOT/roles/enc/meta/argument_specs.yml", spec("enc_level", "int", "2"))
        write(ENC_TASKS, use("enc_level"))

        write("$ROOT/roles/own/defaults/main.yml", "shared_port: 81\n")
        write(OWN_TASKS, use("shared_port"))
        write("$ROOT/roles/doc/defaults/main.yml", "shared_port: 9\n")
        write("$ROOT/roles/doc/meta/argument_specs.yml", spec("shared_port", "int", "9"))
        write("$ROOT/roles/doc/tasks/main.yml", use("shared_port"))

        write("$ROOT/roles/dirrole/defaults/main/10-a.yml", "dir_v: 1\n")
        write("$ROOT/roles/dirrole/defaults/main/20-b.yml", "dir_v: 2\n")
        write("$ROOT/roles/dirrole/meta/argument_specs.yml", spec("dir_v", "int", "2"))
        write(DIR_TASKS, use("dir_v"))

        // A dependency that documents its own default (ANS-S003 in its spec), and a chain through a name the depending
        // role overrides.
        write("$ROOT/roles/dapp/meta/main.yml", "dependencies:\n  - dbase\n")
        write("$ROOT/roles/dapp/defaults/main.yml", "dbase_host: app.example\n")
        write(DAPP_VARS, "dbase_port: 90\n")
        write(DAPP_TASKS, "- name: Use\n  ansible.builtin.debug:\n    msg: \"{{ dbase_port }} {{ dbase_url }}\"\n")
        write("$ROOT/roles/dbase/defaults/main.yml", "dbase_port: 80\ndbase_url: \"{{ dbase_host }}\"\ndbase_host: localhost\n")
        write("$ROOT/roles/dbase/meta/argument_specs.yml", spec("dbase_port", "int", "8080"))

        // `no_log` in a role with two defaults files, a plain playbook group_vars value, and a vault_ name there.
        write("$ROOT/roles/nl/defaults/main/10-a.yml", "nl_password: role-secret-5\n")
        write("$ROOT/roles/nl/defaults/main/20-b.yml", "nl_password: role-secret-6\n")
        write("$ROOT/roles/nl/meta/argument_specs.yml", "---\nargument_specs:\n  main:\n    options:\n      nl_password:\n        type: str\n        no_log: true\n")
        write(NL_TASKS, "- name: Use\n  ansible.builtin.debug:\n    msg: \"{{ nl_password }} {{ vault_plain_token }}\"\n")
        write("$ROOT/group_vars/all.yml", "nl_password: role-secret-7\nvault_plain_token: role-secret-12\n")

        // A wrapper role documents a variable its dependency keeps secret (`no_log` in the dependency's spec only).
        write("$ROOT/roles/wrap/meta/main.yml", "dependencies:\n  - nlbase\n")
        write("$ROOT/roles/wrap/meta/argument_specs.yml", "---\nargument_specs:\n  main:\n    options:\n      wrap_pw:\n        type: str\n")
        write(WRAP_TASKS, use("wrap_pw"))
        write("$ROOT/roles/nlbase/defaults/main.yml", "wrap_pw: role-secret-9\n")
        write("$ROOT/roles/nlbase/meta/argument_specs.yml", "---\nargument_specs:\n  main:\n    options:\n      wrap_pw:\n        type: str\n        no_log: true\n")

        // The own role's only defaults file is a whole-file vault.
        write("$ROOT/roles/opq/defaults/main.yml", "\$ANSIBLE_VAULT;1.1;AES256\n61626364\n")
        write("$ROOT/roles/opq/meta/argument_specs.yml", spec("opq_x", "int", "3"))
        write(OPQ_TASKS, "- name: Use\n  ansible.builtin.debug:\n    msg: \"{{ version }} {{ opq_x }}\"\n")

        // A chain through `defaults/main/` (the last file wins) and one that ends at a secret.
        write("$ROOT/roles/chn/defaults/main/10-a.yml", "chn_url: \"{{ chn_host }}\"\nchn_host: a.example\nchn_secret_ref: \"{{ vault_chn }}\"\nvault_chn: role-secret-11\n")
        write("$ROOT/roles/chn/defaults/main/20-b.yml", "chn_host: b.example\n")
        write(CHN_TASKS, "- name: Use\n  ansible.builtin.debug:\n    msg: \"{{ chn_url }} {{ chn_secret_ref }}\"\n")

        // A JSON defaults file; a spec with two entry points of which only `main` differs.
        write("$ROOT/roles/jsn/defaults/main.json", "{\n  \"jsn_port\": 8080\n}\n")
        write("$ROOT/roles/jsn/meta/argument_specs.yml", spec("jsn_port", "int", "8081"))
        write(JSN_TASKS, use("jsn_port"))
        write("$ROOT/roles/japp/meta/main.yml", "dependencies:\n  - jsn\n")
        write(JAPP_VARS, "jsn_port: 9\n")
        write("$ROOT/roles/japp/tasks/main.yml", use("jsn_port"))
        write("$ROOT/roles/ep/defaults/main.yml", "ep_v: 1\n")
        write(
            "$ROOT/roles/ep/meta/argument_specs.yml",
            "---\nargument_specs:\n  alt:\n    options:\n      ep_v:\n        type: int\n        default: 1\n" +
                "  main:\n    options:\n      ep_v:\n        type: int\n        default: 2\n",
        )
        write(EP_TASKS, use("ep_v"))

        // `hash_behaviour = merge`: the dictionary Ansible uses is the merge of both files.
        write("$MERGE_ROOT/ansible.cfg", "[defaults]\nroles_path = roles\nhash_behaviour = merge\n")
        write("$MERGE_ROOT/roles/mrg/defaults/main/10.yml", "cfg:\n  a: 1\n  b: 2\n")
        write("$MERGE_ROOT/roles/mrg/defaults/main/20.yml", "cfg:\n  a: 1\n")
        write(
            "$MERGE_ROOT/roles/mrg/meta/argument_specs.yml",
            "---\nargument_specs:\n  main:\n    options:\n      cfg:\n        type: dict\n        default: {a: 1}\n" +
                "        options:\n          a: {type: int}\n          b: {type: int, default: 2}\n",
        )
        write(MRG_TASKS, "- name: Use\n  ansible.builtin.debug:\n    msg: \"{{ cfg }} {{ cfg.b }}\"\n")
        refreshRoots()
        IndexingTestUtil.waitUntilIndexesAreReady(project)
    }

    /** The user's case: `version: 1.2` in defaults, `default: 1.5` in the spec. */
    fun testTheRoleDefaultIsTheDefaultAndTheDocumentedOneIsStruck() {
        val target = hover(WEB_TASKS, offsetAt(WEB_TASKS, 3, "version", 1))
        val card = text(html(target))
        assertTrue(card, card.startsWith("version : float ⚠ role web · $ROOT · optional"))
        assertEquals(" 1.2 · roles/web/defaults/main.yml:2 ", section(card, "Default", *SECTIONS))
        assertEquals(
            "the documented default, struck, with the check's code and a link to the spec's default",
            " ⚠ 1.5 never applied (ANS-S003) · roles/web/meta/argument_specs.yml:7 ",
            section(card, "Documented (argument_specs)", *SECTIONS),
        )
        assertTrue("struck: ${html(target)}", "<s><code>1.5</code></s>" in html(target))
        assertTrue("the Default row comes first", card.indexOf("Default 1.2") < card.indexOf("Documented (argument_specs)"))
        for (old in listOf("Default (spec)", "Runtime default", "differs from the runtime default")) assertFalse(old, old in card)
        assertEquals("the hint shows the role default only", "version: float = 1.2 · web (optional) ⚠", hint(target))
        assertTrue(html(target), "title='The role default does not match its documentation'" in html(target))
    }

    /** The same card on the defaults key itself (the S003 twin's position). */
    fun testTheDefaultsKeyCardAgreesWithTheSpec() {
        val card = text(html(hover(WEB_DEFAULTS, offsetAt(WEB_DEFAULTS, 2, "version", 1))))
        assertEquals(" 1.2 · roles/web/defaults/main.yml:2 ", section(card, "Default", *SECTIONS))
        assertTrue(card, "⚠ 1.5 never applied (ANS-S003)" in card)
    }

    /** `"3.2"` documented for `type: str`, `3.2` in defaults: equal after the type's conversion, so no Documented row. */
    fun testATypeOnlyDifferenceShowsNoDocumentedRow() {
        val target = hover(WEB_TASKS, offsetAt(WEB_TASKS, 3, "web_float", 1))
        val card = text(html(target))
        assertFalse(card, "Documented (argument_specs)" in card)
        assertFalse(card, "ANS-S003" in card)
        val default = section(card, "Default", *SECTIONS)
        assertTrue(default, default.startsWith(" 3.2 · roles/web/defaults/main.yml:3"))
        assertTrue("the lossy badge stays: $default", "⚠ the runtime default is a YAML float for documented str" in default)
        assertEquals("web_float: str = 3.2 · web (optional) ⚠", hint(target))
    }

    /** ANS-S004: a documented default that nothing sets; the hint has no `= 5`. */
    fun testADocumentedDefaultNothingAppliesIsNotTheDefault() {
        val target = hover(WEB_TASKS, offsetAt(WEB_TASKS, 3, "web_missing", 1))
        val card = text(html(target))
        assertEquals(
            " none in defaults/ — Ansible never applies the documented default; set it in defaults/ or the inventory ",
            section(card, "Default", *SECTIONS),
        )
        assertEquals(
            " ⚠ 5 documented, never applied (ANS-S004) · roles/web/meta/argument_specs.yml:13 ",
            section(card, "Documented (argument_specs)", *SECTIONS),
        )
        assertEquals("web_missing: int · web (optional) ⚠", hint(target))
        assertTrue(
            "the header ⚠ says why without claiming a role default: ${html(target)}",
            "title='argument_specs documents a default that Ansible never applies'" in html(target),
        )
    }

    /** A dependency's default is what the role's tasks see; the spec's 8080 is only documentation (no S003, no ⚠). */
    fun testADependencyDefault() {
        val target = hover(APP_TASKS, offsetAt(APP_TASKS, 3, "app_port", 1))
        val card = text(html(target))
        assertEquals(" 80 · roles/base/defaults/main.yml:1 from dependency base ", section(card, "Default", *SECTIONS))
        assertEquals(
            " 8080 documentation only, never applied · roles/app/meta/argument_specs.yml:7 ",
            section(card, "Documented (argument_specs)", *SECTIONS),
        )
        assertFalse(card, "⚠" in card)
        assertEquals("app_port: int = 80 · app (optional)", hint(target))
    }

    /** A whole-file vault in `defaults/main/` loads after the winner: the value with a note, and no ⚠ (S003 is silent). */
    fun testAnUncertainDefault() {
        val target = hover(ENC_TASKS, offsetAt(ENC_TASKS, 3, "enc_level", 1))
        val card = text(html(target))
        assertEquals(
            " 1 · roles/enc/defaults/main/10-base.yml:1 a later encrypted defaults file may override it ",
            section(card, "Default", *SECTIONS),
        )
        assertFalse(card, "ANS-S003" in card)
        assertFalse(card, "⚠" in card)
        assertTrue(card, "2 documentation only, never applied" in card)
        assertEquals("enc_level: int = 1 · enc (optional)", hint(target))
    }

    /** The own role's default is what its tasks see, although only another role's spec documents the name (D174). */
    fun testTheOwnRolesDefaultWinsOverAnotherRolesSpec() {
        val target = hover(OWN_TASKS, offsetAt(OWN_TASKS, 3, "shared_port", 1))
        val card = text(html(target))
        assertEquals(" 81 · roles/own/defaults/main.yml:1 ", section(card, "Default", *SECTIONS))
        assertEquals(
            "doc's spec documents doc's default (applied there), not own's",
            " 9 documented by role doc, not the default role own uses · roles/doc/meta/argument_specs.yml:7 ",
            section(card, "Documented (argument_specs)", *SECTIONS),
        )
        assertEquals("the hint does not credit doc with own's value", "shared_port: int = 81 (role own) · doc (optional)", hint(target))
        // In doc's own tasks the default is doc's own, equal to its documentation: no Documented row, no role named.
        val doc = hover("$ROOT/roles/doc/tasks/main.yml", offsetAt("$ROOT/roles/doc/tasks/main.yml", 3, "shared_port", 1))
        assertFalse(text(html(doc)), "Documented (argument_specs)" in text(html(doc)))
        assertEquals("shared_port: int = 9 · doc (optional)", hint(doc))
    }

    /** `defaults/main/`: the files load in sorted order and the last value wins. */
    fun testADefaultsDirectoryShowsTheLastFilesValue() {
        val target = hover(DIR_TASKS, offsetAt(DIR_TASKS, 3, "dir_v", 1))
        val card = text(html(target))
        assertEquals(" 2 · roles/dirrole/defaults/main/20-b.yml:1 ", section(card, "Default", *SECTIONS))
        assertFalse("2 is documented and applied: $card", "Documented (argument_specs)" in card)
        assertEquals("dir_v: int = 2 · dirrole (optional)", hint(target))
    }

    /** A dict sub-option: ANS-S003 compares the sub-option's documented default with the role default dict's key. */
    fun testANestedOptionUsesTheSubOptionsFinding() {
        val card = text(html(hover(WEB_TASKS, offsetAt(WEB_TASKS, 3, "web_db.port", 7))))
        assertTrue(card, card.startsWith("web_db.port : int ⚠ role web"))
        assertEquals(" 5433 · roles/web/defaults/main.yml:8 ", section(card, "Default", *SECTIONS))
        assertEquals(" ⚠ 5432 never applied (ANS-S003) · roles/web/meta/argument_specs.yml:30 ", section(card, "Documented (argument_specs)", *SECTIONS))
    }

    /** Neither a `no_log` option's nor a vault value's defaults, documented or not, ever show in the card or the hint. */
    fun testSecretsAreNeverShown() {
        for (name in listOf("web_password", "vault_web_token", "web_key", "web_secret_pw", "web_dsn", "web_creds")) {
            val target = hover(WEB_TASKS, offsetAt(WEB_TASKS, 3, name, 1))
            val html = html(target)
            val hint = hint(target)
            for (secret in SECRETS) {
                assertFalse("$name card shows $secret: ${text(html)}", secret in html)
                assertFalse("$name hint shows $secret: $hint", secret in hint)
            }
        }
        val password = hover(WEB_TASKS, offsetAt(WEB_TASKS, 3, "web_password", 1))
        assertEquals(" 🔒 value hidden (no_log) in roles/web/defaults/main.yml:4 ", section(text(html(password)), "Default", *SECTIONS))
        assertEquals("web_password: str = 🔒 · web (optional)", hint(password))
        val token = text(html(hover(WEB_TASKS, offsetAt(WEB_TASKS, 3, "vault_web_token", 1))))
        assertTrue(token, "🔒 vault-encrypted (AES256, 1.1) in roles/web/defaults/main.yml:5" in token)
        assertFalse("never compared, so no row: $token", "Documented (argument_specs)" in token)
        val key = text(html(hover(WEB_TASKS, offsetAt(WEB_TASKS, 3, "web_key", 1))))
        assertEquals(
            "ANS-S004 still reports it, without the value",
            " ⚠ 🔒 value hidden documented, never applied (ANS-S004) · roles/web/meta/argument_specs.yml:24 ",
            section(key, "Documented (argument_specs)", *SECTIONS),
        )
        // A `{{ name }}` default leading to a `no_log` variable's default: no chain (it would show that value).
        val dsn = section(text(html(hover(WEB_TASKS, offsetAt(WEB_TASKS, 3, "web_dsn", 1)))), "Default", *SECTIONS)
        assertTrue(dsn, dsn.startsWith(" \"{{ web_secret_pw }}\" evaluated at runtime · roles/web/defaults/main.yml:11"))
        assertFalse(dsn, "→" in dsn)
    }

    /**
     * A dependency's spec documents its own default: the depending role's tasks see that same default, so the card there
     * flags it like the dependency's own card and the underline in its spec (ANS-S003).
     */
    fun testADependencysOwnSpecFlagsItsDefaultFromTheDependingRole() {
        val target = hover(DAPP_TASKS, offsetAt(DAPP_TASKS, 3, "dbase_port", 1))
        val card = text(html(target))
        assertTrue(card, card.startsWith("dbase_port : int ⚠ role dbase"))
        assertEquals(" 80 · roles/dbase/defaults/main.yml:1 from dependency dbase ", section(card, "Default", *SECTIONS))
        assertEquals(
            " ⚠ 8080 never applied (ANS-S003) · roles/dbase/meta/argument_specs.yml:7 ",
            section(card, "Documented (argument_specs)", *SECTIONS),
        )
        assertEquals("dbase_port: int = 80 · dbase (optional) ⚠", hint(target))
    }

    /** A dependency's `{{ name }}` default resolves against the depending role's own default of that name (applied last). */
    fun testADependencyChainSeesTheDependingRolesOverride() {
        val card = text(html(hover(DAPP_TASKS, offsetAt(DAPP_TASKS, 3, "dbase_url", 1))))
        val default = section(card, "Default", *SECTIONS)
        assertTrue(default, "from dependency dbase" in default)
        assertTrue(default, "→ str app.example via dbase_host (roles/dapp/defaults/main.yml:1)" in default)
        assertFalse(default, "localhost" in default)
    }

    /** "This definition" of a role var names the dependency whose default it overrides. */
    fun testThisDefinitionOverridesTheDependencysDefault() {
        val card = text(html(hover(DAPP_VARS, offsetAt(DAPP_VARS, 1, "dbase_port", 1))))
        val row = section(card, "This definition", *SECTIONS)
        assertTrue(row, "overrides dbase default 80 (roles/dbase/defaults/main.yml:1)" in row)
        // The same from a dependency's JSON defaults file.
        val json = section(text(html(hover(JAPP_VARS, offsetAt(JAPP_VARS, 1, "jsn_port", 1)))), "This definition", *SECTIONS)
        assertTrue(json, "role japp · overrides jsn default 8080 (roles/jsn/defaults/main.json:2)" in json)
    }

    /** `defaults/main/`: a `{{ name }}` default follows the last file's value; a chain to a secret ends silently. */
    fun testAChainFollowsTheLastDefaultsFileAndStopsAtSecrets() {
        val url = section(text(html(hover(CHN_TASKS, offsetAt(CHN_TASKS, 3, "chn_url", 1)))), "Default", *SECTIONS)
        assertTrue(url, "→ str b.example via chn_host (roles/chn/defaults/main/20-b.yml:1)" in url)
        val ref = hover(CHN_TASKS, offsetAt(CHN_TASKS, 3, "chn_secret_ref", 1))
        val html = html(ref)
        assertFalse(text(html), "role-secret-11" in html || "role-secret-11" in hint(ref))
        assertFalse("no chain to a vault_ name: ${text(html)}", "via vault_chn" in text(html))
    }

    /** A JSON defaults file has no YAML key: the loaded value is shown, and its ANS-S003 finding too. */
    fun testAJsonDefaultsFile() {
        val target = hover(JSN_TASKS, offsetAt(JSN_TASKS, 3, "jsn_port", 1))
        val card = text(html(target))
        assertEquals(" 8080 · roles/jsn/defaults/main.json:2 ", section(card, "Default", *SECTIONS))
        assertTrue(card, "⚠ 8081 never applied (ANS-S003)" in card)
        assertEquals("jsn_port: int = 8080 · jsn (optional) ⚠", hint(target))
    }

    /** Only the `main` entry point's documented default differs: the `alt` entry point's card is not flagged by it. */
    fun testAFindingCountsForItsOwnEntryPointOnly() {
        val card = text(html(hover(EP_TASKS, offsetAt(EP_TASKS, 3, "ep_v", 1))))
        // The card documents the first entry point of the spec (alt), whose documented default is the role default.
        assertFalse("alt is not flagged by main's ANS-S003: $card", "ANS-S003" in card)
        assertFalse(card, "Documented (argument_specs)" in card)
        assertEquals(" 1 · roles/ep/defaults/main.yml:1 ", section(card, "Default", *SECTIONS))
    }

    /** No role default in a defaults file that cannot be read: it may set the name, so neither "none" nor ANS-S004. */
    fun testAnUnreadableDefaultsFileMayHoldTheDefault() {
        val target = hover(OPQ_TASKS, offsetAt(OPQ_TASKS, 3, "opq_x", 1))
        val card = text(html(target))
        assertEquals(" none found — an encrypted defaults file may set it ", section(card, "Default", *SECTIONS))
        assertEquals(" 3 documentation only, never applied · roles/opq/meta/argument_specs.yml:7 ", section(card, "Documented (argument_specs)", *SECTIONS))
        assertEquals("opq_x: int · opq (optional)", hint(target))
    }

    /** The own role's unreadable defaults apply after the documenting role's: that role's default may be overridden there. */
    fun testAnotherRolesDefaultIsUncertainWhereTheOwnDefaultsAreUnreadable() {
        val target = hover(OPQ_TASKS, offsetAt(OPQ_TASKS, 3, "version", 1))
        val card = text(html(target))
        assertEquals(
            " 1.2 · roles/web/defaults/main.yml:2 a later encrypted defaults file may override it ",
            section(card, "Default", *SECTIONS),
        )
        assertTrue("web's own default still differs from web's documentation: $card", "⚠ 1.5 never applied (ANS-S003)" in card)
    }

    /** Nested options whose key the role default's literal dict lacks: none (documented only) and required. */
    fun testNestedOptionsMissingFromTheDefaultDict() {
        val user = text(html(hover(WEB_TASKS, offsetAt(WEB_TASKS, 3, "web_db.user", 7))))
        assertEquals(" none ", section(user, "Default", *SECTIONS))
        assertEquals(" admin documentation only, never applied · roles/web/meta/argument_specs.yml:33 ", section(user, "Documented (argument_specs)", *SECTIONS))
        val host = text(html(hover(WEB_TASKS, offsetAt(WEB_TASKS, 3, "web_db.host", 7))))
        assertEquals(" none — must be supplied (required) ", section(host, "Default", *SECTIONS))
    }

    /** `hash_behaviour = merge`: the merged dictionary is noted, and never "equal" to its documentation. */
    fun testAMergedDictionary() {
        val target = hover(MRG_TASKS, offsetAt(MRG_TASKS, 3, "cfg", 1))
        val card = text(html(target))
        val default = section(card, "Default", *SECTIONS)
        assertTrue(default, default.startsWith(" a: 1 · roles/mrg/defaults/main/20.yml:1 "))
        assertTrue(default, "merged with the same dictionary of the earlier defaults files (hash_behaviour = merge)" in default)
        assertEquals(
            "Ansible uses {a: 1, b: 2}: the documented {a: 1} is no claim about it",
            " {a: 1} documentation only, never applied · roles/mrg/meta/argument_specs.yml:7 ",
            section(card, "Documented (argument_specs)", *SECTIONS),
        )
        // `b` is missing from the last file's dictionary only: the merge holds it (2 from 10.yml), so no "none" row.
        val b = text(html(hover(MRG_TASKS, offsetAt(MRG_TASKS, 3, "cfg.b", 4))))
        assertFalse(b, "Default none" in b)
        assertFalse(b, " Default " in b)
    }

    /** A `vault_*` name on the option path hides the value for that reason, not as `no_log`. */
    fun testAVaultNameIsNotCalledNoLog() {
        for ((name, delta) in listOf("web_creds" to 1, "web_creds.vault_token" to 10)) {
            val card = text(html(hover(WEB_TASKS, offsetAt(WEB_TASKS, 3, name, delta))))
            val default = section(card, "Default", *SECTIONS)
            assertTrue("$name: $default", default.startsWith(" 🔒 value hidden (vault_ name) in roles/web/defaults/main.yml:"))
            assertFalse("$name: $card", "no_log" in card)
        }
        val plain = text(html(hover(NL_TASKS, offsetAt(NL_TASKS, 3, "vault_plain_token", 1))))
        assertTrue(plain, "group_vars/all.yml:2 · playbook group_vars/all · level 5 · 🔒 value hidden (vault_ name)" in plain)
        assertFalse(plain, "role-secret-12" in plain)
    }

    /** A `no_log` variable's other definitions (a losing defaults file, playbook group_vars) never show their values. */
    fun testNoLogValuesNeverShowInSetIn() {
        val target = hover(NL_TASKS, offsetAt(NL_TASKS, 3, "nl_password", 1))
        val html = html(target)
        for (secret in listOf("role-secret-5", "role-secret-6", "role-secret-7")) assertFalse("$secret: ${text(html)}", secret in html)
        val card = text(html)
        assertEquals(" 🔒 value hidden (no_log) in roles/nl/defaults/main/20-b.yml:1 ", section(card, "Default", *SECTIONS))
        val setIn = section(card, "Set in", *SECTIONS)
        assertTrue(setIn, "roles/nl/defaults/main/10-a.yml:1 · role defaults · level 2 · 🔒 value hidden (no_log)" in setIn)
        assertTrue(setIn, "group_vars/all.yml:1 · playbook group_vars/all · level 5 · 🔒 value hidden (no_log)" in setIn)
        val definition = text(html(hover("$ROOT/group_vars/all.yml", offsetAt("$ROOT/group_vars/all.yml", 1, "nl_password", 1))))
        assertFalse(definition, "role-secret" in definition)
        assertTrue(definition, "🔒 value hidden (no_log) in group_vars/all.yml:1" in section(definition, "This definition", *SECTIONS))
    }

    /** A dependency's spec keeps the value secret although the wrapper role's own spec does not say `no_log`. */
    fun testNoLogInAnyDeclaringSpecHidesTheValue() {
        val target = hover(WRAP_TASKS, offsetAt(WRAP_TASKS, 3, "wrap_pw", 1))
        val html = html(target)
        assertFalse(text(html), "role-secret-9" in html)
        assertEquals(" 🔒 value hidden (no_log) in roles/nlbase/defaults/main.yml:1 from dependency nlbase ", section(text(html), "Default", *SECTIONS))
        assertEquals("wrap_pw: str = 🔒 · wrap (optional)", hint(target))
    }

    private fun write(path: String, text: String) {
        myFixture.tempDirFixture.createFile(path, text)
    }

    private fun spec(name: String, type: String, default: String) =
        "---\nargument_specs:\n  main:\n    options:\n      $name:\n        type: $type\n        default: $default\n"

    private fun use(name: String) = "- name: Use\n  ansible.builtin.debug:\n    msg: \"{{ $name }}\"\n"

    private companion object {
        const val ROOT = "rd"
        const val WEB_DEFAULTS = "$ROOT/roles/web/defaults/main.yml"
        const val WEB_SPEC = "$ROOT/roles/web/meta/argument_specs.yml"
        const val WEB_TASKS = "$ROOT/roles/web/tasks/main.yml"
        const val APP_TASKS = "$ROOT/roles/app/tasks/main.yml"
        const val ENC_TASKS = "$ROOT/roles/enc/tasks/main.yml"
        const val OWN_TASKS = "$ROOT/roles/own/tasks/main.yml"
        const val DIR_TASKS = "$ROOT/roles/dirrole/tasks/main.yml"
        const val DAPP_TASKS = "$ROOT/roles/dapp/tasks/main.yml"
        const val DAPP_VARS = "$ROOT/roles/dapp/vars/main.yml"
        const val JAPP_VARS = "$ROOT/roles/japp/vars/main.yml"
        const val NL_TASKS = "$ROOT/roles/nl/tasks/main.yml"
        const val WRAP_TASKS = "$ROOT/roles/wrap/tasks/main.yml"
        const val OPQ_TASKS = "$ROOT/roles/opq/tasks/main.yml"
        const val CHN_TASKS = "$ROOT/roles/chn/tasks/main.yml"
        const val JSN_TASKS = "$ROOT/roles/jsn/tasks/main.yml"
        const val EP_TASKS = "$ROOT/roles/ep/tasks/main.yml"
        const val MERGE_ROOT = "mg"
        const val MRG_TASKS = "$MERGE_ROOT/roles/mrg/tasks/main.yml"
        const val WEB_USES = "{{ version }} {{ web_float }} {{ web_missing }} {{ web_password }} {{ vault_web_token }} {{ web_key }} {{ web_db.port }}" +
            " {{ web_db.user }} {{ web_db.host }} {{ web_dsn }} {{ web_creds }} {{ web_creds.vault_token }} {{ web_secret_pw }}"
        val SECRETS = listOf(
            "role-secret-1", "documented-secret-2", "documented-secret-3", "documented-secret-4", "61626364", "role-secret-8", "role-secret-10",
        )

        val WEB_DEFAULTS_TEXT = """
            |---
            |version: 1.2
            |web_float: 3.2
            |web_password: role-secret-1
            |vault_web_token: !vault |
            |  ${'$'}ANSIBLE_VAULT;1.1;AES256
            |  61626364
            |web_db:
            |  port: 5433
            |web_secret_pw: role-secret-8
            |web_dsn: "{{ web_secret_pw }}"
            |web_creds:
            |  vault_token: role-secret-10
            |""".trimMargin()

        val WEB_SPEC_TEXT = """
            |---
            |argument_specs:
            |  main:
            |    options:
            |      version:
            |        type: float
            |        default: 1.5
            |      web_float:
            |        type: str
            |        default: "3.2"
            |      web_missing:
            |        type: int
            |        default: 5
            |      web_password:
            |        type: str
            |        no_log: true
            |        default: documented-secret-2
            |      vault_web_token:
            |        type: str
            |        default: documented-secret-3
            |      web_key:
            |        type: str
            |        no_log: true
            |        default: documented-secret-4
            |      web_db:
            |        type: dict
            |        options:
            |          port:
            |            type: int
            |            default: 5432
            |          user:
            |            type: str
            |            default: admin
            |          host:
            |            type: str
            |            required: true
            |      web_secret_pw:
            |        type: str
            |        no_log: true
            |      web_dsn:
            |        type: str
            |      web_creds:
            |        type: dict
            |        options:
            |          vault_token:
            |            type: str
            |""".trimMargin()
    }
}
