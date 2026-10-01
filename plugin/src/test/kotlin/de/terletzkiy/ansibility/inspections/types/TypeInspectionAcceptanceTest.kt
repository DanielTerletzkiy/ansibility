package de.terletzkiy.ansibility.inspections.types

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.openapi.vfs.VfsUtilCore
import de.terletzkiy.ansibility.semantics.diagnostics.Preset
import de.terletzkiy.ansibility.types.TypeCheckTestCase

/**
 * M4 acceptance for the role-variable type checks (plan M4 1, 2, 3, 5, 6, 10; F3.2, F4.4, F3.6, D4, D6; X79, X80, X85),
 * through the highlighting pass on copies of the real files (line numbers as in the infra repo).
 */
class TypeInspectionAcceptanceTest : TypeCheckTestCase() {

    private fun problems(path: String): List<String> = highlights(path).map { "${lineOf(it)}: ${it.inspectionToolId} ${it.severity.name}" }

    private fun at(infos: List<HighlightInfo>, line: Int): HighlightInfo =
        infos.singleOrNull { lineOf(it) == line } ?: error("no single highlight on line $line: ${infos.map { "${lineOf(it)} ${it.description}" }}")

    private fun fix(name: String) =
        myFixture.getAllQuickFixes().firstOrNull { it.text == name } ?: error("no fix '$name' in ${myFixture.getAllQuickFixes().map { it.text }}")

    // ------------------------------------------------------------------------------------------------ acceptance 1

    fun testHaproxyVersionFloatIsRedAndQuoteValueFixesIt() {
        copyInfra("golden/roles/haproxy", "golden/docker")
        val path = "golden/roles/haproxy/defaults/main.yml"
        val infos = highlights(path)
        assertEquals(listOf("1: AnsibleCoercedScalar ERROR"), infos.map { "${lineOf(it)}: ${it.inspectionToolId} ${it.severity.name}" })
        val info = infos.single()
        assertEquals("3.2", highlightedText(info))
        assertEquals(
            "documented `str` for role `haproxy` (entry point `main`); ansible-core 2.18.8 would coerce `3.2` → `'3.2'` " +
                "(the role itself still receives `3.2`)",
            info.description,
        )
        myFixture.editor.caretModel.moveToOffset(info.startOffset)
        val quote = fix("Quote value")
        assertTrue(myFixture.getIntentionPreviewText(quote)!!.startsWith("haproxy_backports_version: \"3.2\"\n"))
        myFixture.launchAction(quote)
        assertEquals("haproxy_backports_version: \"3.2\"", lineText(1))
        assertEmpty(problems(path))
    }

    fun testRuntimeFaithfulPresetTurnsTheCoercionYellow() {
        copyInfra("golden/roles/haproxy", "golden/docker")
        updateRoot("golden") { it.copy(preset = Preset.RUNTIME_FAITHFUL) }
        assertEquals(listOf("1: AnsibleCoercedScalar WARNING"), problems("golden/roles/haproxy/defaults/main.yml"))
    }

    fun testStrictPresetRaisesTheWeakNullWarningsToWarnings() {
        copyInfra("golden/roles/chronod", "golden/docker")
        val path = "golden/roles/chronod/defaults/main.yml"
        assertEquals(
            listOf("161: AnsibleNullForOptional WEAK WARNING", "163: AnsibleNullForOptional WEAK WARNING", "164: AnsibleNullForOptional WEAK WARNING"),
            problems(path),
        )
        updateRoot("golden") { it.copy(preset = Preset.STRICT) }
        assertEquals(
            listOf("161: AnsibleNullForOptional WARNING", "163: AnsibleNullForOptional WARNING", "164: AnsibleNullForOptional WARNING"),
            problems(path),
        )
    }

    // ------------------------------------------------------------------------------------------------ acceptance 2 (X79)

    fun testTotpUsersItemsAreRedWithStaleSpecContextAndUpdateSpecFromUsage() {
        copyInfra("repos/falcon/ansible")
        val prod = "repos/falcon/ansible/environments/prod/group_vars/all/vars.yml"
        val infos = highlights(prod)
        assertEquals(listOf("687: AnsibleSpecShapeContradiction ERROR", "689: AnsibleSpecShapeContradiction ERROR"), infos.map {
            "${lineOf(it)}: ${it.inspectionToolId} ${it.severity.name}"
        })
        assertEquals("totp_users:", lineText(686))
        val first = at(infos, 687)
        assertEquals("name: deploy", highlightedText(first))
        val message = first.description
        assertTrue(message, message.startsWith("documented `elements: str` for role `totp-token` (entry point `main`); ansible-core 2.18.8 would stringify `{'name': 'deploy'"))
        assertTrue(message, message.endsWith(
            "; tasks in `playbook-initial-setup.yml` read `item.name`/`item.secret_file_src`" +
                "; role `totp-token` is not applied by any play in this root; its spec may be stale",
        ))

        myFixture.editor.caretModel.moveToOffset(first.startOffset)
        val update = fix("Update totp-token spec from usage")
        val expected = """
            ---
            argument_specs:
              main:
                short_description: Install google-authenticator and create TOTP secrets under /etc/totp.
                options:
                  totp_users:
                    type: list
                    elements: dict
                    required: true
                    description: Usernames for which TOTP secret files are created at /etc/totp/<username>.
                    options:
                      name:
                        type: str
                      secret_file_src:
                        type: str
            """.trimIndent() + "\n"
        assertEquals("the preview shows the spec file after the change", expected, myFixture.getIntentionPreviewText(update))
        myFixture.launchAction(update)
        assertEquals(expected, documentText("repos/falcon/ansible/roles/totp-token/meta/argument_specs.yml"))
        for (env in listOf("prod", "test", "ops")) {
            assertEmpty(env, problems("repos/falcon/ansible/environments/$env/group_vars/all/vars.yml"))
        }
    }

    fun testRequireReachablePlayForRedDemotesUnappliedRolesOnly() {
        copyInfra("repos/falcon/ansible")
        updateRoot("repos/falcon/ansible") { it.copy(requireReachablePlayForRed = true) }
        assertEquals(
            listOf("687: AnsibleSpecShapeContradiction WARNING", "689: AnsibleSpecShapeContradiction WARNING"),
            problems("repos/falcon/ansible/environments/prod/group_vars/all/vars.yml"),
        )
        assertEquals("the role's own defaults have no reachability context", listOf("1: AnsibleCoercedScalar ERROR"), problems("repos/falcon/ansible/roles/haproxy/defaults/main.yml"))
    }

    // ------------------------------------------------------------------------------------------------ acceptance 3 + T005

    fun testKeycloakClientsUnsupportedKeysAndNullSecrets() {
        copyHeron()
        val path = HERON_KEYCLOAK
        val infos = highlights(path)
        assertEquals(
            listOf(
                "223: AnsibleNullForTypedOption ERROR",
                "236: AnsibleUnsupportedSubOption ERROR",
                "274: AnsibleNullForTypedOption ERROR",
                "287: AnsibleUnsupportedSubOption ERROR",
                "875: AnsibleCoercedScalar ERROR",
            ),
            infos.map { "${lineOf(it)}: ${it.inspectionToolId} ${it.severity.name}" },
        )
        assertEquals("    secret: null", lineText(223))
        assertEquals("null", highlightedText(at(infos, 223)))
        assertTrue(at(infos, 223).description, at(infos, 223).description.startsWith(
            "`null` for required `str` option `keycloak_clients[6].secret` for role `keycloak` (entry point `main`): ansible-core 2.18.8 would reject it",
        ))
        val unsupported = at(infos, 236)
        assertEquals("optional_client_scopes", highlightedText(unsupported))
        assertTrue(unsupported.description, unsupported.description.startsWith(
            "Unsupported key `optional_client_scopes` in `keycloak_clients[6]` for role `keycloak` (entry point `main`): ansible-core 2.18.8 rejects unknown keys here",
        ))
    }

    fun testRemoveUnsupportedKey() {
        copyHeron()
        val infos = highlights(HERON_KEYCLOAK)
        myFixture.editor.caretModel.moveToOffset(at(infos, 236).startOffset)
        myFixture.launchAction(fix("Remove unsupported key 'optional_client_scopes'"))
        assertEquals("      - email", lineText(235))
        assertEquals("    full_scope_allowed: false", lineText(236))
        assertEquals(
            listOf(
                "223: AnsibleNullForTypedOption ERROR",
                "272: AnsibleNullForTypedOption ERROR",
                "285: AnsibleUnsupportedSubOption ERROR",
                "873: AnsibleCoercedScalar ERROR",
            ),
            problems(HERON_KEYCLOAK),
        )
    }

    fun testAddSubOptionToTheSameRootSpec() {
        copyHeron()
        val infos = highlights(HERON_KEYCLOAK)
        myFixture.editor.caretModel.moveToOffset(at(infos, 236).startOffset)
        val add = fix("Add sub-option 'optional_client_scopes' to keycloak argument_specs")
        val snippet = "          optional_client_scopes:\n            type: list\n            elements: str\n"
        assertTrue("the preview shows the spec change", myFixture.getIntentionPreviewText(add)!!.contains(snippet))
        myFixture.launchAction(add)
        val spec = documentText("repos/heron/ansible/roles/keycloak/meta/argument_specs.yml")
        val clients = spec.substring(spec.indexOf("      keycloak_clients:"))
        assertTrue(spec, clients.substringBefore("\n      keycloak_obsolete_clients").contains(snippet))
        assertEquals(
            listOf("223: AnsibleNullForTypedOption ERROR", "274: AnsibleNullForTypedOption ERROR", "875: AnsibleCoercedScalar ERROR"),
            problems(HERON_KEYCLOAK),
        )
    }

    // ------------------------------------------------------------------------------------------------ acceptance 5

    fun testTypingIntoAHaproxyServersItemAndUndo() {
        copyInfra("repos/falcon/ansible")
        val path = "repos/falcon/ansible/environments/prod/group_vars/all/vars.yml"
        assertEquals("haproxy_servers:", lineOf(path, 537))
        assertEquals(listOf("687", "689"), problems(path).map { it.substringBefore(":") })
        val portLine = 540
        assertEquals("    port: 444", lineText(portLine))

        typeOver(portLine, "444", "abc")
        val rejected = highlights(path).filter { lineOf(it) == portLine }
        assertEquals(listOf("AnsibleValueRejected ERROR"), rejected.map { "${it.inspectionToolId} ${it.severity.name}" })
        assertEquals("abc", highlightedText(rejected.single()))
        assertTrue(rejected.single().description, rejected.single().description.startsWith(
            "ansible-core 2.18.8 would reject `haproxy_servers[0].port` for role `haproxy` (entry point `main`)",
        ))
        undo()
        assertEquals("    port: 444", lineText(portLine))
        assertEquals(listOf("687", "689"), problems(path).map { it.substringBefore(":") })

        typeOver(portLine, "444", "\"444\"")
        val quoted = highlights(path).filter { lineOf(it) == portLine }
        assertEquals(listOf("AnsibleStringForNumberOrBool ERROR"), quoted.map { "${it.inspectionToolId} ${it.severity.name}" })
        assertTrue(quoted.single().description, quoted.single().description.startsWith(
            "documented `int` for role `haproxy` (entry point `main`); ansible-core 2.18.8 would coerce `'444'` → `444`",
        ))
        undo()
        assertEquals(listOf("687", "689"), problems(path).map { it.substringBefore(":") })
        typeOver(portLine, "444", "\"444\"")
        myFixture.editor.caretModel.moveToOffset(highlights(path).first { lineOf(it) == portLine }.startOffset)
        myFixture.launchAction(fix("Unquote value"))
        assertEquals("    port: 444", lineText(portLine))
        assertEquals(listOf("687", "689"), problems(path).map { it.substringBefore(":") })
    }

    /** Selects [old] on [line] and types [text] over it, as a user would. */
    private fun typeOver(line: Int, old: String, text: String) {
        val document = myFixture.editor.document
        val start = document.getLineStartOffset(line - 1) + lineText(line).indexOf(old)
        myFixture.editor.selectionModel.setSelection(start, start + old.length)
        myFixture.editor.caretModel.moveToOffset(start + old.length)
        myFixture.type(text)
        PsiDocumentManager.getInstance(project).commitAllDocuments()
    }

    private fun undo() {
        val editor = TextEditorProvider.getInstance().getTextEditor(myFixture.editor)
        val undoManager = UndoManager.getInstance(project)
        assertTrue(undoManager.isUndoAvailable(editor))
        undoManager.undo(editor)
        PsiDocumentManager.getInstance(project).commitAllDocuments()
    }

    // ------------------------------------------------------------------------------------------------ acceptance 6

    fun testModuleOptionsAndKeywordsStayClean() {
        copyInfra("golden/roles/jenkins-controller", "golden/roles/chronod", "golden/roles/haproxy", "golden/docker", "repos/falcon/ansible")
        val files = mapOf(
            "golden/roles/jenkins-controller/tasks/jenkins.yml" to (156 to "    follow_redirects: false"),
            "golden/roles/chronod/tasks/install.yml" to (25 to "        follow_redirects: none"),
            "repos/falcon/ansible/roles/jenkins-agent-docker/tasks/main.yml" to (6 to "    owner: 1000"),
            "golden/roles/haproxy/tasks/configure.yml" to (8 to "    mode: \"0644\""),
            "repos/falcon/ansible/playbook-initial-setup.yml" to (10 to "  serial: 1"),
        )
        for ((path, expected) in files) {
            assertEmpty(path, problems(path))
            assertEquals(path, expected.second, lineText(expected.first))
        }
    }

    // ------------------------------------------------------------------------------------------------ acceptance 10 (F3.6)

    fun testNoinspectionCommentAboveLineOneSilencesTheCoercion() {
        copyInfra("golden/roles/haproxy", "golden/docker")
        val path = "golden/roles/haproxy/defaults/main.yml"
        assertEquals(listOf("1: AnsibleCoercedScalar ERROR"), problems(path))
        WriteCommandAction.runWriteCommandAction(project) { myFixture.editor.document.insertString(0, "# noinspection AnsibleCoercedScalar\n") }
        assertEmpty(problems(path))
    }

    fun testNoinspectionCommentAboveANestedKey() {
        copyHeron()
        assertTrue(problems(HERON_KEYCLOAK).contains("236: AnsibleUnsupportedSubOption ERROR"))
        val document = myFixture.editor.document
        WriteCommandAction.runWriteCommandAction(project) {
            document.insertString(document.getLineStartOffset(235), "    # noinspection AnsibleUnsupportedSubOption\n")
        }
        assertEquals(
            "only the commented key is silenced; the lines below move down by one",
            listOf(
                "223: AnsibleNullForTypedOption ERROR",
                "275: AnsibleNullForTypedOption ERROR",
                "288: AnsibleUnsupportedSubOption ERROR",
                "876: AnsibleCoercedScalar ERROR",
            ),
            problems(HERON_KEYCLOAK),
        )
    }

    fun testFileLevelNoinspectionComment() {
        copyInfra("golden/roles/chronod", "golden/docker")
        val path = "golden/roles/chronod/defaults/main.yml"
        assertEquals(listOf("161", "163", "164"), problems(path).map { it.substringBefore(":") })
        WriteCommandAction.runWriteCommandAction(project) { myFixture.editor.document.insertString(0, "#file: noinspection AnsibleNullForOptional\n") }
        assertEmpty(problems(path))
    }

    // ------------------------------------------------------------------------------------------------ multi-spec names

    fun testANameDeclaredBySeveralSpecsGetsOneFindingNamingTheRoles() {
        copyInfra("repos/falcon/ansible")
        val path = "repos/falcon/ansible/environments/prod/host_vars/prod-prod1/vars.yml"
        val document = runReadActionBlocking { psi(path).viewProvider.document!! }
        val line = VfsUtilCore.loadText(vf(path)).lines().indexOf("system_networking_main_ip: 192.0.2.29")
        assertTrue(line >= 0)
        WriteCommandAction.runWriteCommandAction(project) {
            document.replaceString(document.getLineStartOffset(line), document.getLineEndOffset(line), "system_networking_main_ip: 10")
        }
        val infos = highlights(path)
        assertEquals(listOf("${line + 1}: AnsibleCoercedScalar ERROR"), infos.map { "${lineOf(it)}: ${it.inspectionToolId} ${it.severity.name}" })
        assertEquals(
            "documented `str` for roles `grafana`, `haproxy`, `loki` and `system` (entry point `main`); " +
                "ansible-core 2.18.8 would coerce `10` → `'10'` (the role itself still receives `10`)",
            infos.single().description,
        )
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private fun copyHeron() {
        // heron pins nothing itself; falcon's Dockerfiles give the majority target (2.18.8), as in the real repo.
        copyInfra("repos/heron/ansible", "repos/falcon/ansible/docker")
        myFixture.copyFileToProject("infra/repos/falcon/ansible/ansible.cfg", "repos/falcon/ansible/ansible.cfg")
        refreshRoots()
    }

    /** The text of [path]'s document (fixes that edit another file leave it unsaved). */
    private fun documentText(path: String): String {
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        return FileDocumentManager.getInstance().getDocument(vf(path))!!.text
    }

    private fun lineOf(path: String, line: Int): String = VfsUtilCore.loadText(vf(path)).lines()[line - 1]

    private companion object {
        const val HERON_KEYCLOAK = "repos/heron/ansible/environments/prod/group_vars/keycloak/vars.yml"
    }
}
