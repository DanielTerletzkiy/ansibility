package de.terletzkiy.ansibility.coexist.vault

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInsight.intention.impl.config.IntentionActionMetaData
import com.intellij.codeInsight.intention.impl.config.IntentionManagerSettings
import com.intellij.ide.util.PropertiesComponent
import com.intellij.notification.Notification
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.ExtensionTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.terletzkiy.ansibility.coexist.CoexistenceSettings
import de.terletzkiy.ansibility.vault.VaultVectors
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLKeyValue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The F7.12 coexistence gate without Ansible Vault Editor installed: its absence, a fake [VaultEditorLookup] standing
 * for it, the real intention-settings lookup on synthetic metadata, Vault Editor's gutter positions, the optional
 * fragment's registrations and the one-time suggestion.
 */
class VaultEditorCoexistenceTest : BasePlatformTestCase() {
    private val gate: VaultEditorCoexistence get() = VaultEditorCoexistence.getInstance()

    /** A lookup that stands for a loaded Vault Editor whose intentions have the given enabled flags. */
    private class FakeLookup(private val loaded: Boolean, private val enabled: Map<String, Boolean>) : VaultEditorLookup {
        override fun isLoaded(): Boolean = loaded
        override fun isIntentionEnabled(className: String): Boolean? = enabled[className]
    }

    private fun install(lookup: VaultEditorLookup) {
        ExtensionTestUtil.maskExtensions(VaultEditorLookup.EP_NAME, listOf(lookup), testRootDisposable)
    }

    fun testWithoutVaultEditorEveryIntentionOfOursIsVisible() {
        assertFalse(gate.isVaultEditorLoaded())
        for (kind in VaultIntentionKind.entries) assertTrue(kind.name, gate.ourIntentionVisible(kind))
        val keyValue = keyValue("v: !vault |\n" + VaultVectors.encrypt("x", VaultVectors.PW1).formatLines().joinToString("") { "  $it\n" })
        assertFalse(gate.vaultEditorMarksValue(keyValue))
        assertFalse(gate.vaultEditorMarksFile(myFixture.file))
    }

    fun testOurIntentionsHideWhileTheirsAreEnabled() {
        install(
            FakeLookup(
                loaded = true,
                enabled = mapOf(
                    VaultIntentionKind.EDIT_VALUE.vaultEditorIntention to true,
                    VaultIntentionKind.ENCRYPT_VALUE.vaultEditorIntention to false,
                ),
            ),
        )
        assertTrue(gate.isVaultEditorLoaded())
        assertFalse("theirs is enabled: ours hides", gate.ourIntentionVisible(VaultIntentionKind.EDIT_VALUE))
        assertTrue("theirs is disabled: ours shows", gate.ourIntentionVisible(VaultIntentionKind.ENCRYPT_VALUE))
        assertTrue("this version has no such intention: ours shows", gate.ourIntentionVisible(VaultIntentionKind.CHANGE_PASSWORD_FILE))
    }

    fun testALookupOfAnUnloadedPluginCountsAsAbsent() {
        install(FakeLookup(loaded = false, enabled = VaultIntentionKind.entries.associate { it.vaultEditorIntention to true }))
        assertFalse(gate.isVaultEditorLoaded())
        assertTrue(VaultIntentionKind.entries.all(gate::ourIntentionVisible))
    }

    fun testVaultEditorGutterPositionsAreDeferredTo() {
        install(FakeLookup(loaded = true, enabled = emptyMap()))
        val lines = VaultVectors.encrypt("x", VaultVectors.PW1).formatLines()
        assertTrue("literal block", gate.vaultEditorMarksValue(keyValue("v: !vault |\n" + lines.joinToString("") { "  $it\n" })))
        assertTrue("it ignores the tag", gate.vaultEditorMarksValue(keyValue("v: |\n" + lines.joinToString("") { "  $it\n" })))
        assertFalse("quoted values are ours", gate.vaultEditorMarksValue(keyValue("v: !vault \"" + lines.joinToString("\\n") + "\"\n")))
        assertFalse("folded blocks are ours", gate.vaultEditorMarksValue(keyValue("v: !vault >\n" + lines.joinToString("") { "  $it\n" })))
        assertFalse("plain values", gate.vaultEditorMarksValue(keyValue("v: secret\n")))
        myFixture.configureByText("web.txt", lines.joinToString("\n", postfix = "\n"))
        assertTrue(gate.vaultEditorMarksFile(myFixture.file))
        myFixture.configureByText("other.txt", "plain\n")
        assertFalse(gate.vaultEditorMarksFile(myFixture.file))
    }

    /** S-V3 on the real settings: a category-registered intention found by class and switched off on the Intentions page. */
    fun testIntentionSettingsLookupFindsTheIntentionByClassAndReadsItsFlag() {
        val action = SyntheticVaultIntention()
        val metaData = IntentionActionMetaData(action, javaClass.classLoader, arrayOf("Ansible vault"), "SyntheticVaultIntention", true)
        val other = IntentionActionMetaData(OtherIntention(), javaClass.classLoader, arrayOf("Other"), "OtherIntention", true)
        val sameFamilyElsewhere =
            IntentionActionMetaData(SyntheticVaultIntention(), javaClass.classLoader, arrayOf("Other category"), "SyntheticVaultIntention", true)
        val all = listOf(other, metaData)
        assertSame(metaData, IntentionSettingsVaultEditorLookup.find(SyntheticVaultIntention::class.java.name, all))
        assertNull(IntentionSettingsVaultEditorLookup.find("ru.example.Missing", all))
        assertEquals(SyntheticVaultIntention::class.java.name, IntentionSettingsVaultEditorLookup.implementationClassOf(action))

        val settings = IntentionManagerSettings.getInstance()
        assertTrue(settings.isEnabled(metaData))
        try {
            settings.setEnabled(metaData, false)
            assertFalse("disabled on the Intentions page", settings.isEnabled(metaData))
            assertTrue("another intention stays enabled", settings.isEnabled(other))
            assertTrue("the key is the category path plus the family", settings.isEnabled(sameFamilyElsewhere))
            assertTrue("the bare action is keyed by its family alone, so it would always read as enabled", settings.isEnabled(action))
        } finally {
            settings.setEnabled(metaData, true)
        }
        assertNull("Vault Editor is not installed in tests", IntentionSettingsVaultEditorLookup().isIntentionEnabled(VaultIntentionKind.EDIT_VALUE.vaultEditorIntention))
        assertFalse(IntentionSettingsVaultEditorLookup().isLoaded())
    }

    fun testTheVaultEditorClassTableMatchesItsPluginXml() {
        assertEquals("ru.sadv1r.ansible-vault-editor-idea-plugin", VaultEditorCoexistence.PLUGIN_ID.idString)
        assertEquals(
            setOf(
                "ru.sadv1r.idea.plugin.ansible.vault.editor.intention.PropertyVaultModifyIntentionAction",
                "ru.sadv1r.idea.plugin.ansible.vault.editor.intention.FileVaultModifyIntentionAction",
                "ru.sadv1r.idea.plugin.ansible.vault.editor.intention.PropertyVaultCreateIntentionAction",
                "ru.sadv1r.idea.plugin.ansible.vault.editor.intention.PropertyVaultChangePasswordIntentionAction",
                "ru.sadv1r.idea.plugin.ansible.vault.editor.intention.FileVaultChangePasswordIntentionAction",
            ),
            VaultIntentionKind.entries.mapTo(HashSet()) { it.vaultEditorIntention },
        )
    }

    /** The optional fragment loads only with Vault Editor, so tests check its registrations from the XML. */
    fun testTheOptionalFragmentRegistersTheLookupAndTheSuggestion() {
        val xml = javaClass.getResource("/META-INF/ansibility-vault.xml")?.readText() ?: error("ansibility-vault.xml missing")
        val implementations = Regex("""implementation="([^"]+)"""").findAll(xml).map { it.groupValues[1] }.toList()
        assertEquals(listOf(VaultEditorSuggestionActivity::class.java.name, IntentionSettingsVaultEditorLookup::class.java.name), implementations)
        assertTrue("<vaultEditorLookup" in xml)
        implementations.forEach { Class.forName(it) }
        val checks = javaClass.getResource("/META-INF/ansibility-vault-checks.xml")?.readText() ?: error("ansibility-vault-checks.xml missing")
        assertTrue(checks, """qualifiedName="${VaultEditorLookup.EP_NAME.name}"""" in checks)
        assertTrue(VaultEditorLookup.EP_NAME.name.endsWith(".vaultEditorLookup"))
    }

    fun testTheSuggestionAppearsOnceUntilAnswered() {
        val properties = PropertiesComponent.getInstance()
        properties.unsetValue(VaultEditorSuggestion.ANSWER_KEY)
        try {
            val session = AtomicBoolean()
            assertNull("absent plugin", VaultEditorSuggestion.suggestOnce(project, loaded = { false }, session = session))
            assertNull("X03 off", VaultEditorSuggestion.suggestOnce(project, loaded = { true }, settings = NoNotifications, session = session))
            val shown = VaultEditorSuggestion.suggestOnce(project, loaded = { true }, session = session) ?: error("not shown")
            assertEquals("Ansible Vault Editor overlaps Ansibility", shown.title)
            assertTrue(shown.content, "vault ids, folding and inventory awareness" in shown.content)
            assertEquals(listOf("Open Plugins Settings", "Keep both", "Don't show again"), shown.actions.map { it.templateText })
            shown.expire()
            assertNull("once per session", VaultEditorSuggestion.suggestOnce(project, loaded = { true }, session = session))

            properties.setValue(VaultEditorSuggestion.ANSWER_KEY, VaultEditorSuggestion.ANSWER_KEEP_BOTH)
            assertNull("answered", VaultEditorSuggestion.suggestOnce(project, loaded = { true }, session = AtomicBoolean()))
        } finally {
            properties.unsetValue(VaultEditorSuggestion.ANSWER_KEY)
        }
    }

    fun testEachAnswerIsRemembered() {
        val properties = PropertiesComponent.getInstance()
        try {
            val expected = listOf(VaultEditorSuggestion.ANSWER_KEEP_BOTH, VaultEditorSuggestion.ANSWER_NEVER)
            for ((index, answer) in expected.withIndex()) {
                properties.unsetValue(VaultEditorSuggestion.ANSWER_KEY)
                val notification: Notification = VaultEditorSuggestion.createNotification(project, properties)
                val context = SimpleDataContext.builder().add(Notification.KEY, notification).add(CommonDataKeys.PROJECT, project).build()
                Notification.fire(notification, notification.actions[index + 1], context)
                assertEquals(answer, properties.getValue(VaultEditorSuggestion.ANSWER_KEY))
                assertTrue(notification.isExpired)
            }
        } finally {
            properties.unsetValue(VaultEditorSuggestion.ANSWER_KEY)
        }
    }

    private fun keyValue(text: String): YAMLKeyValue {
        val file = myFixture.configureByText("v.yml", text) as YAMLFile
        return PsiTreeUtil.findChildOfType(file, YAMLKeyValue::class.java) ?: error("no key-value")
    }

    private object NoNotifications : CoexistenceSettings {
        override fun hideOtherAnsibleCompletions(): Boolean = false
        override fun schemaStoreExclusion(project: Project): Boolean = true
        override fun conflictNotifications(): Boolean = false
    }

    /** A synthetic intention standing for one of Vault Editor's (the real ones are never loaded in tests). */
    private open class SyntheticVaultIntention : IntentionAction {
        override fun getText(): String = familyName
        override fun getFamilyName(): String = "Synthetic vault intention"
        override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean = false
        override fun invoke(project: Project, editor: Editor?, file: PsiFile?) = Unit
        override fun startInWriteAction(): Boolean = false
    }

    private class OtherIntention : SyntheticVaultIntention() {
        override fun getFamilyName(): String = "Other synthetic intention"
    }
}
