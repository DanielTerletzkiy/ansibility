package de.terletzkiy.ansibility.vault.ui

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.icons.AllIcons
import com.intellij.lang.folding.LanguageFolding
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.psi.PsiElement
import com.intellij.testFramework.EditorTestUtil
import com.intellij.testFramework.ExtensionTestUtil
import com.intellij.testFramework.TestActionEvent
import de.terletzkiy.ansibility.coexist.vault.VaultEditorLookup
import de.terletzkiy.ansibility.vault.VaultVectors
import java.awt.datatransfer.DataFlavor
import java.awt.event.MouseEvent
import org.jetbrains.yaml.YAMLLanguage
import org.jetbrains.yaml.folding.YAMLFoldingBuilder

/**
 * 🟣 X16 folding next to `YAMLFoldingBuilder` (spike S-V2) and 🟣 X93's lock gutter: ranges, placeholders,
 * collapse state, lock-state icons, Vault Editor coexistence and click = Reveal.
 */
class VaultFoldingAndGutterTest : VaultUiTestCase() {
    private lateinit var root: String
    private val path get() = "$root/group_vars/all/vault.yml"

    override fun setUp() {
        super.setUp()
        root = defaultRoot()
        write(path, "plain: 1\n" +
            VaultVectors.inline("vault_db_password", VaultVectors.encrypt("hello world", VaultVectors.PW1)) +
            "nested:\n  vault_dev: !vault |\n" + VaultVectors.encrypt("dev secret", VaultVectors.DEV, "dev").formatLines().joinToString("") { "    $it\n" } +
            "list:\n  - !vault |\n" + VaultVectors.encrypt("item secret", VaultVectors.PW1).formatLines().joinToString("") { "    $it\n" } +
            "vault_quoted: !vault \"" + VaultVectors.encrypt("quoted", VaultVectors.PW1).format().replace("\n", "\\n") + "\"\n" +
            "after: 2\n")
        root(root)
        myFixture.configureFromExistingVirtualFile(vf(path))
    }

    private fun lineStart(text: String, needle: String): Int = text.indexOf(needle).also { check(it >= 0) { "no $needle" } }

    fun testFoldsEveryLiteralBodyInsideYamlsOwnRegion() {
        val document = myFixture.editor.document
        val text = document.text
        val ours = runReadActionBlocking { VaultFoldingBuilder().buildFoldRegions(myFixture.file, document, false) }
        assertEquals("three literal blocks, the quoted value is not folded", 3, ours.size)
        val yaml = runReadActionBlocking { YAMLFoldingBuilder().let { LanguageFolding.buildFoldingDescriptors(it, myFixture.file, document, false) } }
        for (descriptor in ours) {
            val range = descriptor.range
            assertEquals("starts at the end of the `!vault |` line", '\n', text[range.startOffset])
            assertTrue("ends at the block end", range.endOffset == text.length || text[range.endOffset] == '\n')
            val enclosing = yaml.filter { it.range.contains(range) && it.range != range }
            assertTrue("strictly inside YAML's scalar region: ${yaml.map { it.range }}", enclosing.isNotEmpty())
            assertTrue("never the same range as a YAML region", yaml.none { it.range == range })
        }
        val placeholders = ours.map { it.placeholderText }
        assertEquals(listOf("🔒 vault 1.1 · default · 6 lines", "🔒 vault 1.2 · dev · 6 lines", "🔒 vault 1.1 · default · 6 lines"), placeholders)
        val composite = runReadActionBlocking {
            LanguageFolding.buildFoldingDescriptors(LanguageFolding.INSTANCE.forLanguage(YAMLLanguage.INSTANCE), myFixture.file, document, false)
        }
        for (descriptor in ours) assertTrue("the composite keeps ours", composite.any { it.range == descriptor.range })
        assertTrue("and YAML's", yaml.all { y -> composite.any { it.range == y.range } })
        assertTrue(lineStart(text, "vault_db_password") < ours.first().range.startOffset)
    }

    fun testTheEditorCollapsesOursAndLeavesYamlsExpanded() {
        EditorTestUtil.buildInitialFoldingsInBackground(myFixture.editor)
        val regions = myFixture.editor.foldingModel.allFoldRegions
        val ours = regions.filter { it.placeholderText.startsWith("🔒 vault") }
        assertEquals(3, ours.size)
        assertTrue("collapsed by default", ours.none { it.isExpanded })
        val text = myFixture.editor.document.text
        val yamlScalar = regions.single { it.startOffset == text.indexOf("!vault |") }
        assertTrue("YAML's own region stays expanded", yamlScalar.isExpanded)
        for (region in ours) {
            assertFalse(region.placeholderText.contains("hello") || region.placeholderText.contains("ANSIBLE"))
        }
    }

    fun testGutterMarksVaultValuesWithTheirLockState() {
        val attempts = crypto.decryptAttempts
        val gutters = myFixture.findAllGutters().filter { it.tooltipText?.startsWith("Ansible Vault value") == true }
        assertEquals("the two keyed values, the sequence item and the quoted value", 4, gutters.size)
        assertTrue(gutters.all { it.icon == AllIcons.Nodes.Locked })
        assertTrue(gutters.first().tooltipText!!.contains("id default"))
        assertTrue(gutters.first().tooltipText!!.contains("locked"))
        assertEquals("rendering never decrypts", attempts, crypto.decryptAttempts)

        await { secrets.unlock(root(root)) }
        val unlocked = myFixture.findAllGutters().filter { it.tooltipText?.startsWith("Ansible Vault value") == true }
        assertEquals(AllIcons.Ide.Readwrite, unlocked.first().icon)
        assertTrue(unlocked.first().tooltipText!!.contains("unlocked"))
    }

    fun testVaultEditorKeepsItsLiteralGutterAndWeMarkOnlyWhatItMisses() {
        ExtensionTestUtil.maskExtensions(VaultEditorLookup.EP_NAME, listOf(LoadedVaultEditor), testRootDisposable)
        val gutters = myFixture.findAllGutters().filter { it.tooltipText?.startsWith("Ansible Vault value") == true }
        assertEquals("Vault Editor marks literal key-values only: ours stay on the list item and the quoted value", 2, gutters.size)
    }

    fun testClickingTheGutterReveals() {
        val gutter = myFixture.findAllGutters().first { it.tooltipText?.startsWith("Ansible Vault value") == true } as GutterIconRenderer
        @Suppress("UNCHECKED_CAST")
        val info = (gutter as LineMarkerInfo.LineMarkerGutterIconRenderer<*>).lineMarkerInfo as LineMarkerInfo<PsiElement>
        val click = MouseEvent(myFixture.editor.component, MouseEvent.MOUSE_CLICKED, 0, 0, 0, 0, 1, false)
        info.navigationHandler!!.navigate(click, info.element!!)
        waitFor("no reveal") { VaultRevealService.getInstance(project).session != null }
        val session = VaultRevealService.getInstance(project).session!!
        assertEquals("vault_db_password", session.facts.keyName)
    }

    /** The right-click menu of the first vault value's marker, separators removed. */
    private fun menu(): List<AnAction> {
        val gutter = myFixture.findAllGutters().first { it.tooltipText?.startsWith("Ansible Vault value") == true } as GutterIconRenderer
        return (gutter.popupMenuActions as DefaultActionGroup).getChildren(ActionManager.getInstance()).filter { it !is Separator }
    }

    /** Whether [action] shows in the menu now. */
    private fun shown(action: AnAction): Boolean {
        val event = TestActionEvent.createTestEvent()
        action.update(event)
        return event.presentation.isEnabledAndVisible
    }

    fun testTheRightClickMenuOffersTheValueActions() {
        val actions = menu()
        assertEquals(
            listOf(
                "Reveal vault value", "Copy decrypted vault value", "Edit vault value\u2026", "Rekey vault value to id\u2026", "Change vault id\u2026",
                "Decrypt to plain value\u2026", "Unlock vault ids\u2026",
            ),
            actions.map { it.templatePresentation.text },
        )
        assertTrue(actions.all(::shown))
        actions[1].actionPerformed(TestActionEvent.createTestEvent())
        awaitAction()
        assertEquals("hello world", CopyPasteManager.getInstance().getContents<String>(DataFlavor.stringFlavor))
        scheduler.advance(VaultUiTimings.CLIPBOARD_MILLIS)
    }

    fun testUnlockShowsWhileLockedAndUnlocksWithoutDecrypting() {
        val unlock = menu().single { it.templatePresentation.text == "Unlock vault ids\u2026" }
        assertTrue(shown(unlock))
        val attempts = crypto.decryptAttempts
        unlock.actionPerformed(TestActionEvent.createTestEvent())
        awaitAction()
        assertEquals("consent before the first read", 1, prompter.consentRequests.size)
        assertEquals(listOf("Unlocked vault ids: default."), feedback())
        assertEquals("unlocking decrypts nothing", attempts, crypto.decryptAttempts)
        assertFalse("hidden once unlocked", shown(menu().single { it.templatePresentation.text == "Unlock vault ids\u2026" }))
    }
}

/** Ansible Vault Editor loaded, with every intention of it enabled (the coexistence gate's input, F7.12). */
private object LoadedVaultEditor : VaultEditorLookup {
    override fun isLoaded(): Boolean = true

    override fun isIntentionEnabled(className: String): Boolean = true
}
