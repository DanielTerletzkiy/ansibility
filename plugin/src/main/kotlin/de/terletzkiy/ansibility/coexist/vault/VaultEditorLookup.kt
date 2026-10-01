package de.terletzkiy.ansibility.coexist.vault

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInsight.intention.impl.config.IntentionActionMetaData
import com.intellij.codeInsight.intention.impl.config.IntentionActionWrapper
import com.intellij.codeInsight.intention.impl.config.IntentionManagerSettings
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.ExtensionPointName
import java.util.concurrent.ConcurrentHashMap

/**
 * What Ansibility may know about Ansible Vault Editor (`ru.sadv1r.ansible-vault-editor-idea-plugin`) while it is
 * loaded (F7.12, spike S-V3). Implemented by [IntentionSettingsVaultEditorLookup], which the optional fragment
 * `ansibility-vault.xml` registers: that fragment loads and unloads with Vault Editor, so an extension of this point
 * exists exactly while Vault Editor is loaded. Callers use [VaultEditorCoexistence], never this point directly.
 */
interface VaultEditorLookup {
    /** True while Ansible Vault Editor is loaded (`PluginManagerCore.isLoaded`). */
    fun isLoaded(): Boolean

    /**
     * Whether Vault Editor's intention implemented by [className] is enabled in Settings › Editor › Intentions › Ansible
     * vault; null when Vault Editor registers no intention of that class (another version renamed it).
     */
    fun isIntentionEnabled(className: String): Boolean?

    companion object {
        val EP_NAME: ExtensionPointName<VaultEditorLookup> = ExtensionPointName("de.terletzkiy.ansibility.vaultEditorLookup")
    }
}

/**
 * The S-V3 result: Vault Editor's intentions are found among `IntentionManagerSettings.getMetaData()`, whose actions
 * are the platform's `IntentionActionWrapper`s around each `<intentionAction>` bean. The wrapper names the
 * implementation class without loading it (`getImplementationClassName`, public), and
 * `IntentionManagerSettings.isEnabled(IntentionActionMetaData)` (public) answers with the same key as the Intentions
 * settings page: the category path plus the family name. Passing the bare action to `isEnabled(IntentionAction)`
 * would compare the family name without the category and always answer "enabled". Nothing here references a Vault
 * Editor class, reads its settings beyond that public flag, or changes them.
 */
class IntentionSettingsVaultEditorLookup : VaultEditorLookup {
    /** Metadata found so far, by implementation class. Misses are not cached: metadata may register after us. */
    private val found = ConcurrentHashMap<String, IntentionActionMetaData>()

    override fun isLoaded(): Boolean = PluginManagerCore.isLoaded(VaultEditorCoexistence.PLUGIN_ID)

    override fun isIntentionEnabled(className: String): Boolean? {
        val settings = IntentionManagerSettings.getInstance()
        val metaData = found[className]
            ?: find(className, settings.getMetaData())?.also { found[className] = it }
            ?: return null
        return settings.isEnabled(metaData)
    }

    companion object {
        /** The metadata among [metaData] whose intention is implemented by [className], or null. */
        fun find(className: String, metaData: List<IntentionActionMetaData>): IntentionActionMetaData? =
            metaData.firstOrNull { implementationClassOf(it.action) == className }

        /** The implementation class of [action]: the class a registered wrapper names, else the action's own class. */
        fun implementationClassOf(action: IntentionAction): String =
            (action as? IntentionActionWrapper)?.implementationClassName ?: action.javaClass.name
    }
}
