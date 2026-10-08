package de.terletzkiy.ansibility.inspections.vault

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.codeInspection.LocalInspectionEP
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.terletzkiy.ansibility.context.AnsibleWorkspaceImpl
import de.terletzkiy.ansibility.semantics.vault.VaultEnvelope
import de.terletzkiy.ansibility.semantics.vault.VaultLayout
import de.terletzkiy.ansibility.vault.VaultVectors

/**
 * ANS-V113 on a synthetic root (`site/`, role `app`): which tasks write a secret, which modes let others read the file,
 * the message texts and the "Set mode to …" fixes. Envelopes are generated at test time with the synthetic passwords
 * of `tools/vault/SYNTHETIC.md`; nothing is decrypted.
 */
class SecretFileModeInspectionTest : BasePlatformTestCase() {
    private val envelope: VaultEnvelope = VaultVectors.encrypt("synthetic secret for a mode check", VaultVectors.PW1)
    private val wholeFileVault: String = envelope.formatLines().joinToString("\n") + "\n"

    /** Each checked text gets a task file of its own, so a fixed (unsaved) document is never overwritten. */
    private var taskFiles = 0

    override fun setUp() {
        super.setUp()
        myFixture.enableInspections(AnsibleSecretFileModeInspection())
        create("site/ansible.cfg", "[defaults]\nroles_path = roles\n")
        create("site/environments/prod/hosts.yml", "---\nall:\n  hosts:\n    h1:\n")
        create("site/environments/prod/group_vars/all/vault.yml", "---\napi_token: synthetic\n")
        create(
            "$ROLE/defaults/main.yml",
            """
            ---
            app_port: 8080
            app_mode: "0600"
            db_password: "{{ vault_db_password }}"
            chain_a: "{{ chain_b }}"
            chain_b: "{{ chain_c }}"
            chain_c: "{{ vault_chain }}"
            deep_a: "{{ deep_b }}"
            deep_b: "{{ deep_c }}"
            deep_c: "{{ deep_d }}"
            deep_d: "{{ vault_deep }}"
            loop_a: "{{ loop_b }}"
            loop_b: "{{ loop_a }}"
            """.trimIndent() + "\n" + VaultLayout.inlineBlock("inline_secret", 0, 2, envelope),
        )
        create("$ROLE/templates/secret.conf.j2", "{% set user = 'app' %}user={{ user }}\npassword={{ db_password }}\n")
        create("$ROLE/templates/plain.conf.j2", "port={{ app_port }}\n")
        create("$ROLE/templates/sealed.conf.j2", wholeFileVault)
        create("$ROLE/files/sealed.key", wholeFileVault)
        create("$ROLE/files/plain.txt", "not a secret\n")
        create("$ROLE/tasks/main.yml", "---\n- name: Ping\n  ansible.builtin.ping:\n")
    }

    /**
     * Creates [path], re-detects the roots and settles the project: the template re-typing a new `ansible.cfg` or role
     * marker schedules (`AnsibleJinjaFileTypeRefresh`, invoked later on the EDT) runs now, and the re-indexing it
     * starts finishes, so highlighting never starts while the project enters or leaves dumb mode.
     */
    private fun create(path: String, text: String): VirtualFile =
        myFixture.tempDirFixture.createFile(path, text).also {
            (AnsibleWorkspaceImpl.getInstance(project) ?: error("AnsibleWorkspace is not AnsibleWorkspaceImpl")).structureChanged()
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            IndexingTestUtil.waitUntilIndexesAreReady(project)
        }

    /** Opens [text] as a new task file of role `app` and returns the ANS-V113 highlights in offset order. */
    private fun infos(text: String): List<HighlightInfo> {
        myFixture.configureFromExistingVirtualFile(create("$ROLE/tasks/check${++taskFiles}.yml", text.trimIndent() + "\n"))
        return myFixture.doHighlighting().filter { it.inspectionToolId == SHORT_NAME }.sortedBy { it.startOffset }
    }

    /** The highlights of [text] as `highlighted text: message`. */
    private fun problems(text: String): List<String> = infos(text).map { "${highlighted(it)}: ${it.description}" }

    private fun highlighted(info: HighlightInfo): String = myFixture.editor.document.text.substring(info.startOffset, info.endOffset)

    /** A copy task whose content uses `vault_api_key`, with [mode] written as given (null: no mode line). */
    private fun copyWithMode(mode: String?): String = buildString {
        append("- name: Write\n  ansible.builtin.copy:\n    content: \"{{ vault_api_key }}\"\n    dest: /etc/app/key\n")
        if (mode != null) append("    mode: $mode\n")
    }

    /** Puts the caret on the ANS-V113 problem of [text] (the single one, or the one at [index]) and applies [fix]. */
    private fun applyFix(text: String, fix: String, index: Int? = null) {
        val all = infos(text)
        val info = if (index == null) all.single() else all[index]
        myFixture.editor.caretModel.moveToOffset(info.startOffset)
        // The intentions at the caret: getAllQuickFixes() would also offer the fixes of the file's other problems.
        val fixes = myFixture.availableIntentions
        val action = fixes.firstOrNull { it.text == fix } ?: error("no '$fix' in ${fixes.map { it.text }}")
        myFixture.checkPreviewAndLaunchAction(action)
    }

    private fun fixNames(info: HighlightInfo): List<String> {
        myFixture.editor.caretModel.moveToOffset(info.startOffset)
        return myFixture.getAllQuickFixes().map { it.text }.filter { it.startsWith("Set mode to") }
    }

    // ------------------------------------------------------------------------------------------------ modules

    fun testEveryWriterIsCheckedInEveryNameForm() {
        val found = problems(
            """
            - ansible.builtin.template:
                src: secret.conf.j2
                dest: /etc/app/a.conf
                mode: "0644"
            - template:
                src: secret.conf.j2
                dest: /etc/app/b.conf
                mode: "0644"
            - ansible.builtin.copy:
                content: "{{ vault_api_key }}"
                dest: /etc/app/c
                mode: "0644"
            - copy:
                content: "{{ vault_api_key }}"
                dest: /etc/app/d
                mode: "0644"
            - ansible.builtin.lineinfile:
                path: /etc/app/e
                line: "key={{ vault_api_key }}"
                mode: "0644"
            - lineinfile:
                path: /etc/app/f
                line: "key={{ vault_api_key }}"
                mode: "0644"
            - ansible.builtin.blockinfile:
                path: /etc/app/g
                block: "key={{ vault_api_key }}"
                mode: "0644"
            - blockinfile:
                path: /etc/app/h
                block: "key={{ vault_api_key }}"
                mode: "0644"
            - community.general.ini_file:
                path: /etc/app/i.ini
                section: auth
                option: key
                value: "{{ vault_api_key }}"
                mode: "0644"
            - ini_file:
                path: /etc/app/j.ini
                section: auth
                option: key
                value: "{{ vault_api_key }}"
                mode: "0644"
            - ansible.builtin.file:
                path: /etc/app/k
                mode: "0644"
            - ansible.builtin.debug:
                msg: "{{ vault_api_key }}"
            """,
        )
        assertEquals(found.joinToString("\n"), 10, found.size)
        assertEquals("\"0644\": The file gets vault_db_password (through db_password) and others can read it (0644).", found[0])
        assertEquals("\"0644\": The file gets vault_api_key and others can read it (0644).", found[2])
        assertTrue(found.drop(2).joinToString("\n"), found.drop(2).all { it == found[2] })
    }

    fun testWithoutAModeOnlyFilesTheModuleCreatesAreReported() {
        val found = problems(
            """
            - ansible.builtin.lineinfile:
                path: /etc/app/a
                line: "key={{ vault_api_key }}"
            - ansible.builtin.lineinfile:
                path: /etc/app/b
                line: "key={{ vault_api_key }}"
                create: true
            - ansible.builtin.blockinfile:
                path: /etc/app/c
                block: "key={{ vault_api_key }}"
            - blockinfile:
                path: /etc/app/d
                block: "key={{ vault_api_key }}"
                create: yes
            - ansible.builtin.lineinfile:
                path: /etc/app/e
                line: "key={{ vault_api_key }}"
                create: "{{ app_create }}"
            - community.general.ini_file:
                path: /etc/app/f.ini
                section: auth
                option: key
                value: "{{ vault_api_key }}"
            - community.general.ini_file:
                path: /etc/app/g.ini
                section: auth
                option: key
                value: "{{ vault_api_key }}"
                create: false
            - ansible.builtin.lineinfile:
                path: /etc/app/h
                line: "key={{ vault_api_key }}"
                mode: "0644"
            """,
        )
        val noMode = "The file gets vault_api_key and has no mode: it gets the default, usually 0644, which others can read."
        assertEquals(
            listOf(
                "ansible.builtin.lineinfile: $noMode",
                "blockinfile: $noMode",
                "community.general.ini_file: $noMode",
                "\"0644\": The file gets vault_api_key and others can read it (0644).",
            ),
            found,
        )
    }

    fun testStateAbsentWritesNothing() {
        assertEmpty(
            problems(
                """
                - ansible.builtin.lineinfile:
                    path: /etc/app/key
                    line: "key={{ vault_api_key }}"
                    state: absent
                """,
            ),
        )
    }

    // ------------------------------------------------------------------------------------------------ secrets

    fun testWhatCountsAsASecret() {
        val tasks = linkedMapOf(
            "vault_ name" to "content: \"{{ vault_api_key }}\"",
            "vault file" to "content: \"token={{ api_token }}\"",
            "!vault definition" to "content: \"{{ inline_secret }}\"",
            "one step" to "content: \"{{ db_password }}\"",
            "three steps" to "content: \"{{ chain_a }}\"",
            "vars lookup" to "content: \"{{ lookup('vars', 'vault_api_key') }}\"",
            "list content" to "content:\n      - \"{{ vault_api_key }}\"",
        )
        val expected = mapOf(
            "vault_ name" to "vault_api_key",
            "vault file" to "api_token",
            "!vault definition" to "inline_secret",
            "one step" to "vault_db_password (through db_password)",
            "three steps" to "vault_chain (through chain_a → chain_b → chain_c)",
            "vars lookup" to "vault_api_key",
            "list content" to "vault_api_key",
        )
        for ((case, content) in tasks) {
            val found = problems("- ansible.builtin.copy:\n    $content\n    dest: /etc/app/x\n    mode: \"0644\"")
            assertEquals(case, listOf("\"0644\": The file gets ${expected.getValue(case)} and others can read it (0644)."), found)
        }
    }

    fun testInlineVaultValueInTheTask() {
        val task = "- ansible.builtin.copy:\n" + VaultLayout.inlineBlock("content", 4, 6, envelope) + "    dest: /etc/app/x\n    mode: \"0644\"\n"
        val found = problems(task)
        assertEquals(listOf("\"0644\": The file gets a !vault value and others can read it (0644)."), found)
        assertFalse("no payload in the message", envelope.formatLines().drop(1).any { line -> found.single().contains(line.take(16)) })
    }

    fun testIndirectionStopsAfterThreeStepsAndAtCycles() {
        for (name in listOf("deep_a", "loop_a", "app_port", "undefined_name")) {
            assertEmpty(name, problems("- ansible.builtin.copy:\n    content: \"{{ $name }}\"\n    dest: /etc/app/x\n    mode: \"0644\""))
        }
    }

    fun testTemplatesAreResolvedAndReadForVaultedVariables() {
        val found = problems(
            """
            - ansible.builtin.template:
                src: secret.conf.j2
                dest: /etc/app/a.conf
                mode: "0644"
            - ansible.builtin.template:
                src: plain.conf.j2
                dest: /etc/app/b.conf
                mode: "0644"
            - ansible.builtin.template:
                src: templates/sealed.conf.j2
                dest: /etc/app/c.conf
                mode: "0644"
            - ansible.builtin.template:
                src: missing.conf.j2
                dest: /etc/app/d.conf
                mode: "0644"
            """,
        )
        assertEquals(
            listOf(
                "\"0644\": The file gets vault_db_password (through db_password) and others can read it (0644).",
                "\"0644\": The file gets the decrypted vault file templates/sealed.conf.j2 and others can read it (0644).",
            ),
            found,
        )
    }

    fun testCopyOfAWholeFileVaultUnlessDecryptIsFalse() {
        val found = problems(
            """
            - ansible.builtin.copy:
                src: sealed.key
                dest: /etc/app/a.key
                mode: "0644"
            - ansible.builtin.copy:
                src: sealed.key
                dest: /etc/app/b.key
                decrypt: false
                mode: "0644"
            - ansible.builtin.copy:
                src: plain.txt
                dest: /etc/app/c.txt
                mode: "0644"
            - ansible.builtin.copy:
                src: sealed.key
                dest: /etc/app/d.key
                remote_src: true
                mode: "0644"
            """,
        )
        assertEquals(listOf("\"0644\": The file gets the decrypted vault file sealed.key and others can read it (0644)."), found)
    }

    fun testNoLogCountsAsASecretAlsoFromABlock() {
        val found = problems(
            """
            - name: Own no_log
              ansible.builtin.copy:
                content: "plain"
                dest: /etc/app/a
                mode: "0644"
              no_log: true
            - name: Inherited
              block:
                - ansible.builtin.lineinfile:
                    path: /etc/app/b
                    line: "plain"
                    mode: "0644"
              no_log: true
            - name: Overridden
              block:
                - ansible.builtin.lineinfile:
                    path: /etc/app/c
                    line: "plain"
                    mode: "0644"
                  no_log: false
              no_log: true
            - name: Plain
              ansible.builtin.copy:
                content: "plain"
                dest: /etc/app/d
                mode: "0644"
            """,
        )
        assertEquals(List(2) { "\"0644\": The file gets a secret (no_log: true) and others can read it (0644)." }, found)
    }

    fun testPlayLevelNoLogInAPlaybook() {
        val playbook = create(
            "site/playbook-deploy.yml",
            """
            - name: Deploy
              hosts: all
              no_log: true
              tasks:
                - ansible.builtin.copy:
                    content: "plain"
                    dest: /etc/app/a
            """.trimIndent() + "\n",
        )
        myFixture.configureFromExistingVirtualFile(playbook)
        val found = myFixture.doHighlighting().filter { it.inspectionToolId == SHORT_NAME }.map { "${highlighted(it)}: ${it.description}" }
        assertEquals(
            listOf("ansible.builtin.copy: The file gets a secret (no_log: true) and has no mode: it gets the default, usually 0644, which others can read."),
            found,
        )
    }

    // ------------------------------------------------------------------------------------------------ modes

    fun testModesThatLetOthersRead() {
        val broad = listOf(
            "0655", "0644", "644", "\"0644\"", "'0644'", "\"0o644\"", "\"644\"", "o+r", "a=r", "u=rw,g=r,o=r", "\"=r\"", "\"u=rwx,go=rx\"",
        )
        for (mode in broad) {
            val found = problems(copyWithMode(mode))
            val written = mode.trim('"', '\'')
            assertEquals(mode, listOf("$mode: The file gets vault_api_key and others can read it ($written)."), found)
        }
    }

    fun testModesThatKeepOthersOut() {
        val keepOthersOut = listOf(
            "\"0600\"", "0600", "0640", "\"0640\"", "0700", "600", "640", "\"u=rw,g=r,o=\"", "\"{{ app_mode }}\"", "preserve", "u+rw", "go-rwx",
            "\"u=rw,go-rwx\"", "\"o-rwx\"",
            // Write or execute alone shows others nothing.
            "0711", "\"0751\"", "\"0602\"", "\"o+x\"", "\"o=wx\"",
            // ansible-core rejects `a` combined with other users.
            "\"ua+r\"", "\"ao=r\"", "\"aa=r\"",
        )
        for (mode in keepOthersOut) {
            assertEmpty(mode, problems(copyWithMode(mode)))
        }
    }

    fun testMissingModeIsReportedOnTheModule() {
        val infos = infos(copyWithMode(null))
        assertEquals(1, infos.size)
        val info = infos.single()
        assertEquals("ansible.builtin.copy", highlighted(info))
        assertEquals(
            "The file gets vault_api_key and has no mode: it gets the default, usually 0644, which others can read.",
            info.description,
        )
        assertEquals(HighlightSeverity.WARNING, info.severity)
        assertEquals(listOf("Set mode to 0600", "Set mode to 0640"), fixNames(info))
    }

    fun testMissingModeIsNotReportedForTemplatedArgumentsOrUnderModuleDefaults() {
        assertEmpty(problems("- ansible.builtin.copy:\n  args: \"{{ copy_args }}\"\n  no_log: true"))
        create("site/playbook-defaults.yml", "- hosts: all\n  module_defaults:\n    ansible.builtin.copy:\n      mode: \"0600\"\n  roles:\n    - app\n")
        assertEmpty(problems(copyWithMode(null)))
        assertEquals("an explicit mode still counts", 1, problems(copyWithMode("\"0644\"")).size)
    }

    fun testEmptyAndNullModesCountAsMissing() {
        for (mode in listOf("", "~", "null")) {
            val found = problems(copyWithMode(mode).replace("mode: \n", "mode:\n"))
            assertEquals(mode, 1, found.size)
            assertTrue(found.single(), found.single().endsWith("has no mode: it gets the default, usually 0644, which others can read."))
        }
    }

    // ------------------------------------------------------------------------------------------------ fixes

    fun testFixReplacesTheModeValue() {
        applyFix(copyWithMode("0644"), "Set mode to 0600")
        assertEquals(copyWithMode("\"0600\""), myFixture.editor.document.text)
        assertEmpty(myFixture.doHighlighting().filter { it.inspectionToolId == SHORT_NAME })
    }

    fun testFixKeepsSingleQuotes() {
        applyFix(copyWithMode("'0644'"), "Set mode to 0640")
        assertEquals(copyWithMode("'0640'"), myFixture.editor.document.text)
    }

    fun testFixKeepsAnchorsAndTags() {
        applyFix(copyWithMode("&m '0644'"), "Set mode to 0640")
        assertEquals(copyWithMode("&m '0640'"), myFixture.editor.document.text)

        applyFix(copyWithMode("!!str '0644'"), "Set mode to 0600")
        assertEquals(copyWithMode("!!str '0600'"), myFixture.editor.document.text)

        applyFix(copyWithMode("&m"), "Set mode to 0600")
        assertEquals(copyWithMode("&m \"0600\""), myFixture.editor.document.text)
    }

    fun testAliasedModeIsReportedOnItsOwnKey() {
        val text = copyWithMode("&m \"0644\"") + copyWithMode("*m").replace("/etc/app/key", "/etc/app/other")
        val infos = infos(text)
        assertEquals(listOf("&m \"0644\"", "mode"), infos.map(::highlighted))
        assertTrue(infos.all { it.description == "The file gets vault_api_key and others can read it (0644)." })
        assertTrue("the alias is reported in its own task", infos[1].startOffset > text.indexOf("/etc/app/other"))

        applyFix(text, "Set mode to 0600", index = 1)
        assertEquals(copyWithMode("&m \"0644\"") + copyWithMode("\"0600\"").replace("/etc/app/key", "/etc/app/other"), myFixture.editor.document.text)
        assertEquals("the anchored mode is still reported", 1, myFixture.doHighlighting().count { it.inspectionToolId == SHORT_NAME })

        applyFix(text, "Set mode to 0640", index = 0)
        assertEquals(copyWithMode("&m \"0640\"") + copyWithMode("*m").replace("/etc/app/key", "/etc/app/other"), myFixture.editor.document.text)
        assertEmpty(myFixture.doHighlighting().filter { it.inspectionToolId == SHORT_NAME })
    }

    fun testMergedModeIsReportedOnTheModuleAndOverridden() {
        val text = """
            - name: Defaults
              ansible.builtin.copy: &copy_defaults
                content: "{{ vault_api_key }}"
                dest: /etc/app/a
                mode: "0644"
            - name: Merged
              ansible.builtin.copy:
                <<: *copy_defaults
                dest: /etc/app/b
            """
        assertEquals(
            listOf(
                "\"0644\": The file gets vault_api_key and others can read it (0644).",
                "ansible.builtin.copy: The file gets vault_api_key and others can read it (0644).",
            ),
            problems(text),
        )
        val merged = infos(text)[1]
        assertTrue("reported in the merging task", merged.startOffset > text.trimIndent().indexOf("Merged"))

        applyFix(text, "Set mode to 0600", index = 1)
        assertEquals(text.trimIndent() + "\n    mode: \"0600\"\n", myFixture.editor.document.text)
        assertEquals("the defaults are still reported", 1, myFixture.doHighlighting().count { it.inspectionToolId == SHORT_NAME })
    }

    fun testFixInsertsAModeLineAtTheArgumentIndentation() {
        applyFix(copyWithMode(null), "Set mode to 0600")
        assertEquals(copyWithMode("\"0600\""), myFixture.editor.document.text)

        val wide = "- name: Wide\n  ansible.builtin.copy:\n      content: \"{{ vault_api_key }}\"\n      dest: /etc/app/key\n  tags: [app]\n"
        applyFix(wide, "Set mode to 0640")
        assertEquals(
            "- name: Wide\n  ansible.builtin.copy:\n      content: \"{{ vault_api_key }}\"\n      dest: /etc/app/key\n      mode: \"0640\"\n  tags: [app]\n",
            myFixture.editor.document.text,
        )
    }

    fun testFixSetsAnEmptyMode() {
        applyFix(copyWithMode("").replace("mode: \n", "mode:\n"), "Set mode to 0600")
        assertEquals(copyWithMode("\"0600\""), myFixture.editor.document.text)
    }

    fun testFixInFlowMappingsAndArgs() {
        applyFix("- ansible.builtin.copy: {content: \"{{ vault_api_key }}\", dest: /etc/app/key}", "Set mode to 0600")
        assertEquals("- ansible.builtin.copy: {content: \"{{ vault_api_key }}\", dest: /etc/app/key, mode: \"0600\"}\n", myFixture.editor.document.text)

        applyFix("- ansible.builtin.copy:\n  args:\n    content: \"{{ vault_api_key }}\"\n    dest: /etc/app/key", "Set mode to 0640")
        assertEquals("- ansible.builtin.copy:\n  args:\n    content: \"{{ vault_api_key }}\"\n    dest: /etc/app/key\n    mode: \"0640\"\n", myFixture.editor.document.text)

        applyFix("- action:\n    module: ansible.builtin.copy\n    content: \"{{ vault_api_key }}\"\n    dest: /etc/app/key", "Set mode to 0600")
        assertEquals(
            "- action:\n    module: ansible.builtin.copy\n    content: \"{{ vault_api_key }}\"\n    dest: /etc/app/key\n    mode: \"0600\"\n",
            myFixture.editor.document.text,
        )
    }

    fun testFreeFormArguments() {
        val found = problems("- ansible.builtin.copy: content=\"{{ vault_api_key }}\" dest=/etc/app/key mode=0644")
        assertEquals(listOf("0644: The file gets vault_api_key and others can read it (0644)."), found)
        applyFix("- ansible.builtin.copy: content=\"{{ vault_api_key }}\" dest=/etc/app/key mode=0644", "Set mode to 0600")
        assertEquals("- ansible.builtin.copy: content=\"{{ vault_api_key }}\" dest=/etc/app/key mode=0600\n", myFixture.editor.document.text)

        val missing = infos("- ansible.builtin.copy: content=\"{{ vault_api_key }}\" dest=/etc/app/key")
        assertEquals(1, missing.size)
        assertEquals("free-form arguments without args: get no fix", emptyList<String>(), fixNames(missing.single()))
    }

    // ------------------------------------------------------------------------------------------------ registration

    fun testRegisteredUnderAnsibilityVaultAsWarningWithDescription() {
        val ep = LocalInspectionEP.LOCAL_INSPECTION.extensionList.single { it.shortName == SHORT_NAME }
        assertEquals("Ansibility", ep.groupPath)
        assertEquals("inspection.group.vault", ep.groupKey)
        assertEquals("Vault", AnsibilityVaultChecksBundle.message(ep.groupKey))
        assertEquals("WARNING", ep.level)
        assertEquals("yaml", ep.language)
        assertEquals("Secret written to a file others can read (ANS-V113)", AnsibilityVaultChecksBundle.message(ep.key))
        assertNotNull(javaClass.getResource("/inspectionDescriptions/$SHORT_NAME.html"))
    }

    private companion object {
        const val SHORT_NAME = "AnsibleSecretFileMode"
        const val ROLE = "site/roles/app"
    }
}
