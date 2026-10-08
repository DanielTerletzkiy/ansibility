package de.terletzkiy.ansibility.inspections.vault

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.terletzkiy.ansibility.context.AnsibleWorkspaceImpl
import de.terletzkiy.ansibility.semantics.vault.ShapeContext
import de.terletzkiy.ansibility.semantics.vault.VaultEnvelope
import de.terletzkiy.ansibility.semantics.vault.VaultFileShape
import de.terletzkiy.ansibility.vault.VaultVectors
import de.terletzkiy.ansibility.vault.crypto.VaultCrypto

/**
 * ANS-V107 "Not a whole-file vault" and ANS-V114 "Vault envelope without the !vault tag" (plan amendment R21, D159 and
 * D163) with their fixes, on synthetic files around an envelope made at test time with the synthetic password pw1 of
 * `tools/vault/SYNTHETIC.md`. Nothing is decrypted; messages carry line numbers, never content.
 */
class VaultNotWholeFileInspectionTest : BasePlatformTestCase() {
    private val envelope: VaultEnvelope = VaultVectors.encrypt("a synthetic key long enough for two blocks", VaultVectors.PW1)
    private val lines: List<String> = envelope.formatLines()
    private val header: String = lines[0]
    private val payload: List<String> = lines.drop(1)

    override fun setUp() {
        super.setUp()
        myFixture.enableInspections(
            AnsibleVaultMalformedEnvelopeInspection(),
            AnsibleVaultFoldedValueInspection(),
            AnsibleVaultTrailingWhitespaceInspection(),
            AnsibleVaultNotWholeFileInspection(),
            AnsibleVaultUntaggedEnvelopeInspection(),
        )
        // ANS-V107 runs in projects with an Ansible root (plan amendment R21): the project's top directory is one.
        root = myFixture.tempDirFixture.createFile("ansible.cfg", "[defaults]\n")
        structureChanged()
    }

    private lateinit var root: VirtualFile

    private fun structureChanged() {
        (AnsibleWorkspaceImpl.getInstance(project) ?: error("AnsibleWorkspace is not AnsibleWorkspaceImpl")).structureChanged()
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        IndexingTestUtil.waitUntilIndexesAreReady(project)
    }

    private fun block(indent: Int = 0): String = lines.joinToString("") { " ".repeat(indent) + it + "\n" }

    /** Every vault finding of [path] with [text]: V101–V103, V107 and V114 by their message prefixes. */
    private fun infos(path: String, text: String): List<HighlightInfo> {
        val file = myFixture.addFileToProject(path, text)
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        return highlight()
    }

    private fun highlight(): List<HighlightInfo> = myFixture.doHighlighting().filter { info ->
        val description = info.description ?: return@filter false
        PREFIXES.any { description.startsWith(it) }
    }

    private fun fixes(): List<String> = myFixture.getAllQuickFixes().map { it.text }.filter { it in FIX_NAMES }

    private fun fix(name: String) {
        val action = myFixture.getAllQuickFixes().firstOrNull { it.text == name } ?: error("no '$name' in ${myFixture.getAllQuickFixes().map { it.text }}")
        myFixture.checkPreviewAndLaunchAction(action)
    }

    private fun assertNoPayload(info: HighlightInfo) {
        assertFalse(info.description, payload.any { it.take(16) in info.description!! })
        assertFalse(info.description, header in info.description!!)
    }

    // ------------------------------------------------------------------------------------------------ ANS-V107

    fun testAPastedTagLineIsAnErrorAndConvertsToAWholeFileVaultInOneUndoableStep() {
        val before = VaultCrypto.getInstance(project).decryptAttempts
        val text = "!vault |\n" + block()
        val info = infos("roles/web/files/ssl/pasted.key", text).single()
        assertEquals(HighlightSeverity.ERROR, info.severity)
        assertTrue(info.description, info.description!!.startsWith("Not a whole-file vault: line 1 is a YAML tag (!vault)"))
        assertTrue(info.description, "copy, template and lookup('file') deliver the envelope text" in info.description!!)
        assertEquals("the tag line", "!vault |", text.substring(info.startOffset, info.endOffset))
        assertNoPayload(info)

        fix("Convert to whole-file vault")
        assertEquals(envelope.format(), myFixture.editor.document.text)
        assertTrue(VaultEnvelope.isEncrypted(myFixture.editor.document.text))
        assertEquals("a whole-file vault now: no finding at all", emptyList<HighlightInfo>(), highlight())

        val editor = TextEditorProvider.getInstance().getTextEditor(myFixture.editor)
        UndoManager.getInstance(project).undo(editor)
        assertEquals("one undo restores the file", text, myFixture.editor.document.text)
        assertEquals("never decrypted", before, VaultCrypto.getInstance(project).decryptAttempts)
    }

    fun testIndentedPreambleAndKeyedShapesAreReportedAndConverted() {
        val cases = listOf(
            Triple("files/tern.txt", "!vault |\n" + block(10), "line 1 is a YAML tag"),
            Triple("files/greeting.key", "greeting: !vault |\n" + block(10), "line 1 is a YAML key with a !vault value"),
            Triple("files/db.key", "# pasted from the password manager\n\n" + block(), "2 lines come before \$ANSIBLE_VAULT (line 3)"),
            Triple("certs/web.key", block(4), "indented by 4 columns"),
            Triple("notes/anything.txt", " " + block(), "indented by 1 column"),
        )
        for ((path, text, expected) in cases) {
            val info = infos(path, text).single()
            assertTrue("$path: ${info.description}", info.description!!.startsWith("Not a whole-file vault: "))
            assertTrue("$path: ${info.description}", expected in info.description!!)
            assertNoPayload(info)
            fix("Convert to whole-file vault")
            assertEquals(path, envelope.format(), myFixture.editor.document.text)
            assertEquals(path, emptyList<HighlightInfo>(), highlight())
        }
    }

    fun testAVarsFileThatIsOneVaultValueGetsOnlyANS107() {
        for (indent in listOf(0, 2)) {
            val text = "!vault |\n" + block(indent)
            val path = "group_vars/all/tern$indent.yml"
            val infos = infos(path, text)
            assertEquals("one code per envelope (no V101 'the value is empty'): $infos", 1, infos.size)
            val info = infos.single()
            assertTrue(info.description, info.description!!.startsWith("Not a whole-file vault: this file is a single !vault value (line 1), not a mapping"))
            assertEquals(HighlightSeverity.ERROR, info.severity)
            fix("Convert to whole-file vault")
            assertEquals(envelope.format(), myFixture.editor.document.text)
            assertEquals(emptyList<HighlightInfo>(), highlight())
        }
    }

    fun testNormalInlineValuesAndDocsStaySilent() {
        val vars = "---\n" + VaultVectors.inline("db_password", envelope) + "db_user: falcon\n" + VaultVectors.inline("api_token", envelope)
        assertEquals(emptyList<HighlightInfo>(), infos("group_vars/all/vault.yml", vars))
        assertEquals("a single key-value is the normal inline form too", emptyList<HighlightInfo>(), infos("host_vars/web.yml", VaultVectors.inline("db_password", envelope)))
        val readme = "# Vault files\n\nAn encrypted file starts like this:\n\n    $header\n    ${payload[0]}\n"
        assertEquals("docs that show an envelope", emptyList<HighlightInfo>(), infos("README.md", readme))
        assertEquals(emptyList<HighlightInfo>(), infos("files/plain.txt", "just text\n"))
        assertEquals("a whole-file vault", emptyList<HighlightInfo>(), infos("files/web.key", envelope.format()))
    }

    fun testQuotedAndMixedEnvelopesInFilesAnsibleCopiesHaveNoFix() {
        val mixed = infos("roles/web/files/app.conf", "[db]\nuser = tern\npassword =\n" + block() + "port = 5432\n").single()
        assertTrue(mixed.description, mixed.description!!.startsWith("Vault envelope inside other text (line 4)"))
        assertEquals("the header only is highlighted", header, myFixture.editor.document.text.substring(mixed.startOffset, mixed.endOffset))
        assertEquals(emptyList<String>(), fixes())
        val quoted = infos("files/token.key", "\"" + lines.joinToString("\\n") + "\\n\"\n").single()
        assertTrue(quoted.description, quoted.description!!.startsWith("Not a whole-file vault: the envelope on line 1 is quoted"))
        assertEquals(header, myFixture.editor.document.text.substring(quoted.startOffset, quoted.endOffset))
        assertEquals(emptyList<String>(), fixes())
        assertEquals("the same text outside files Ansible copies", emptyList<HighlightInfo>(),
            infos("notes/app.conf", "[db]\npassword =\n" + block() + "port = 5432\n"))
    }

    fun testATemplateIsReadAsItIs() {
        // Inside a root a template is an Ansible Jinja file: its outer text is where the envelope sits.
        val info = infos("roles/web/templates/web.key.j2", "!vault |\n" + block(10)).single()
        assertEquals("AnsibleJinja", myFixture.file.language.id)
        assertTrue(info.description, info.description!!.startsWith("Not a whole-file vault: line 1 is a YAML tag"))
    }

    fun testAMalformedEnvelopeBehindTheWrapperSaysSoAndHasNoFix() {
        val odd = "!vault |\n$header\n" + payload.dropLast(1).joinToString("") { "$it\n" } + payload.last().dropLast(1) + "\n"
        val info = infos("files/odd.key", odd).single()
        assertTrue(info.description, info.description!!.startsWith("Not a whole-file vault: line 1 is a YAML tag"))
        assertTrue(info.description, info.description!!.endsWith(" The envelope itself is malformed too: the payload has an odd number of hex digits."))
        assertEquals(emptyList<String>(), fixes())
    }

    fun testAByteOrderMarkIsReportedAndPointsToRemoveBom() {
        val file = myFixture.tempDirFixture.createFile("files/bom.key")
        WriteAction.runAndWait<Exception> { file.setBinaryContent(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + envelope.formatBytes()) }
        myFixture.configureFromExistingVirtualFile(file)
        assertNotNull("the IDE read the mark", file.bom)
        val info = highlight().single()
        assertTrue(info.description, info.description!!.startsWith("Not a whole-file vault: a byte order mark comes before \$ANSIBLE_VAULT"))
        assertTrue(info.description, "File | File Properties | Remove BOM" in info.description!!)
        assertEquals("no V101 for the same envelope, and no Convert fix", emptyList<String>(), fixes())
    }

    fun testAByteOrderMarkBeforeATagLineIsReportedAsTheMarkWithoutConvert() {
        val file = myFixture.tempDirFixture.createFile("files/bom-tag.key")
        WriteAction.runAndWait<Exception> { file.setBinaryContent(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + ("!vault |\n" + block(2)).toByteArray()) }
        myFixture.configureFromExistingVirtualFile(file)
        assertNotNull("the IDE read the mark", file.bom)
        val info = highlight().single()
        assertTrue(info.description, info.description!!.startsWith("Not a whole-file vault: a byte order mark comes before \$ANSIBLE_VAULT"))
        assertEquals("converting would leave the mark: it has to go first", emptyList<String>(), fixes())
    }

    fun testShellVariablesDocsAndOneVariableVarsFilesStaySilent() {
        val script = "#!/bin/sh\nansible-playbook --vault-password-file \"\$ANSIBLE_VAULT_PASSWORD_FILE\" site.yml\necho \$ANSIBLE_VAULT_IDENTITY_LIST\n"
        assertEquals("Ansible's environment variables", emptyList<HighlightInfo>(), infos("roles/ci/templates/run-playbook.sh.j2", script))
        assertEquals(emptyList<HighlightInfo>(), infos("roles/ci/files/deploy.sh", "echo \$ANSIBLE_VAULT_PASSWORD_FILE\n"))
        val doc = "# Encrypted file example\n\n" + block(4)
        assertEquals("a doc that ends with an example", emptyList<HighlightInfo>(), infos("docs/vault-example.md", doc))
        assertEquals("a vars file of one variable (vars_files takes any name)", emptyList<HighlightInfo>(),
            infos("env/prod/secrets", "db_password: !vault |\n" + block(2)))
    }

    fun testNothingWithoutARootOrInADetachedWorktree() {
        val text = "!vault |\n" + block()
        assertEquals("a detached worktree", emptyList<HighlightInfo>(), run {
            myFixture.tempDirFixture.createFile(".claude/worktrees/wt/ansible.cfg", "[defaults]\n")
            structureChanged()
            infos(".claude/worktrees/wt/files/pasted.key", text)
        })
        WriteAction.runAndWait<Exception> { root.delete(this) }
        WriteAction.runAndWait<Exception> { myFixture.tempDirFixture.getFile(".claude")!!.delete(this) }
        structureChanged()
        assertEquals("no Ansible root at all", emptyList<HighlightInfo>(), infos("files/pasted.key", text))
    }

    fun testCrlfFilesKeepTheirLineSeparator() {
        val file = myFixture.tempDirFixture.createFile("files/crlf.key")
        WriteAction.runAndWait<Exception> { file.setBinaryContent(("!vault |\r\n" + block(2).replace("\n", "\r\n")).toByteArray()) }
        // Opened as it is: the fixture's configure would save it with LF first.
        myFixture.openFileInEditor(file)
        assertEquals("\r\n", file.detectedLineSeparator)
        highlight().single()
        fix("Convert to whole-file vault")
        FileDocumentManager.getInstance().saveAllDocuments()
        assertEquals(envelope.format().replace("\n", "\r\n"), String(contents(file), Charsets.US_ASCII))
    }

    fun testTheFixWritesNothingWhenTheEnvelopeChangedMeanwhile() {
        val text = "!vault |\n" + block()
        infos("files/changed.key", text).single()
        val action = myFixture.getAllQuickFixes().single { it.text == "Convert to whole-file vault" }
        val document = myFixture.editor.document
        // The low digit of the payload's first byte: another hex character of the salt, so the envelope still parses.
        val low = document.text.indexOf(payload[0]) + 1
        val replacement = when {
            document.text[low - 1] == '3' -> if (document.text[low] == '0') "1" else "0"
            else -> if (document.text[low] == '1') "2" else "1"
        }
        WriteCommandAction.runWriteCommandAction(project) { document.replaceString(low, low + 1, replacement) }
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        val changed = document.text
        assertTrue(VaultFileShape.classify(changed, ShapeContext(yamlInput = false, readRaw = true))!!.unwrapsToVault)
        myFixture.launchAction(action)
        assertEquals("another envelope than the inspection saw: left alone", changed, document.text)
    }

    // ------------------------------------------------------------------------------------------------ ANS-V114

    fun testAnUntaggedEnvelopeIsAWarningAndGetsTheTag() {
        val text = "---\ndb_password: |\n" + block(2) + "db_user: falcon\n"
        val info = infos("group_vars/all/vault.yml", text).single()
        assertEquals(HighlightSeverity.WARNING, info.severity)
        assertTrue(info.description, info.description!!.startsWith("Vault envelope without the !vault tag"))
        assertEquals("the header line", header, text.substring(info.startOffset, info.endOffset))
        fix("Add !vault tag")
        assertEquals("---\ndb_password: !vault |\n" + block(2) + "db_user: falcon\n", myFixture.editor.document.text)
        assertEquals(emptyList<HighlightInfo>(), highlight())
    }

    fun testAFlattenedUntaggedEnvelopeIsTaggedAndThenFoldedValueTakesOver() {
        val text = "db_password: $header\n" + payload.joinToString("") { "  $it\n" }
        assertTrue(infos("group_vars/all/flat.yml", text).single().description!!.startsWith("Vault envelope without the !vault tag"))
        fix("Add !vault tag")
        assertTrue(myFixture.editor.document.text.startsWith("db_password: !vault $header\n"))
        assertTrue(highlight().single().description!!.startsWith("Flattened vault value"))
    }

    fun testMentionsTaggedValuesAndTemplatesAreNoUntaggedEnvelope() {
        assertEquals(emptyList<HighlightInfo>(), infos("group_vars/all/note.yml", "note: \$ANSIBLE_VAULT;1.1;AES256 starts every vault\n"))
        assertEquals(emptyList<HighlightInfo>(), infos("group_vars/all/tagged.yml", VaultVectors.inline("db_password", envelope)))
        assertEquals(emptyList<HighlightInfo>(), infos("group_vars/all/other.yml", "token: !unsafe |\n" + block(2)))
        // YAML templates may render vars files Ansible loads later: their values are left alone (as by ANS-V101–V103).
        assertEquals(emptyList<HighlightInfo>(), infos("roles/web/templates/app.yml", "db_password: |\n" + block(2)))
        assertEquals(emptyList<HighlightInfo>(), infos("roles/web/templates/vars.yml.j2", VaultVectors.inline("db_password", envelope)))
        assertEquals(emptyList<HighlightInfo>(), infos("roles/web/files/vars.yml", VaultVectors.inline("db_password", envelope) + "db_user: falcon\n"))
    }

    private fun contents(file: VirtualFile): ByteArray = file.contentsToByteArray()

    private companion object {
        val PREFIXES = listOf("Malformed vault", "Folded vault", "Flattened vault", "Trailing whitespace on line", "Not a whole-file vault",
            "Vault envelope inside other text", "Vault envelope without the !vault tag")
        val FIX_NAMES = setOf("Convert to literal block", "Strip trailing whitespace", "Convert to whole-file vault", "Add !vault tag")
    }
}
