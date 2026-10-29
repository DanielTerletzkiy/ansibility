package de.terletzkiy.ansibility.vault.rotate

import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import de.terletzkiy.ansibility.api.VaultPurpose
import de.terletzkiy.ansibility.vault.crypto.VaultCrypto
import de.terletzkiy.ansibility.vault.identity.VaultDiscovery
import de.terletzkiy.ansibility.vault.secrets.VaultSecretsService
import de.terletzkiy.ansibility.vault.ui.AnsibilityVaultUiBundle.message
import de.terletzkiy.ansibility.vault.ui.settings.VaultConfigurable
import org.jetbrains.annotations.TestOnly
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * X129, the teammate hint (plan amendment R29): when an explicit vault operation (Reveal, Edit, Copy, opening a
 * decrypted tab, a file action) fails with a wrong secret for an envelope whose id is unlocked in this session, and the
 * verification cache says that same secret opened other envelopes of that id in the root since the last lock, the
 * id's password was probably rotated elsewhere (a teammate, after a pull): "The vault password for 'default' may have
 * been rotated. Update `ansible/.vault-pass`." with an action that opens Settings › Vault.
 *
 * Once per root and id per session; never for an id rotated in this IDE ([rotatedHere]); never on passive paths
 * (highlighting, folding, completion, documentation and the D31 analysis never call it); nothing is decrypted for it:
 * it reads the verification records [de.terletzkiy.ansibility.vault.VaultOperationsImpl] keeps anyway.
 */
@Service(Service.Level.PROJECT)
class RotationHints(private val project: Project) {
    private val shown = ConcurrentHashMap.newKeySet<String>()
    private val rotated = ConcurrentHashMap.newKeySet<String>()

    /** The hints shown, for tests (filled only in unit-test mode). */
    private val reported = CopyOnWriteArrayList<String>()

    /** A rotation of [label] in the roots [rootKeys] ran in this IDE: no hint for them in this session. */
    fun rotatedHere(rootKeys: Collection<String>, label: String) {
        for (rootKey in rootKeys) rotated += key(rootKey, label)
    }

    /**
     * An explicit operation with [purpose] failed with a wrong secret for an envelope labelled [label] (the effective
     * label) of [discovery]'s root after trying [tried]: shows the hint when the conditions of the class comment hold.
     */
    fun wrongSecret(discovery: VaultDiscovery, label: String, tried: List<String>, purpose: VaultPurpose) {
        if (purpose !in EXPLICIT || label !in tried) return
        val key = key(discovery.rootKey, label)
        if (key in rotated || key in shown) return
        val secrets = VaultSecretsService.getInstance(project)
        if (label !in secrets.unlockedLabels(discovery)) return
        if (!VaultCrypto.getInstance(project).anyDecryptedWith(discovery.rootKey, label)) return
        if (!shown.add(key)) return
        val source = RotationSources(project).sourceOf(discovery, label, secrets.currentIdentity(discovery, label), read = false)
        val text = message("rotate.hint", StringUtil.escapeXmlEntities(label), StringUtil.escapeXmlEntities(source.display))
        if (ApplicationManager.getApplication().isUnitTestMode) reported += text
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            val notification = Notification(WARNINGS_GROUP, message("notification.title"), text, NotificationType.WARNING)
            notification.addAction(NotificationAction.createSimpleExpiring(message("rotate.hint.settings")) {
                ShowSettingsUtil.getInstance().showSettingsDialog(project, VaultConfigurable::class.java)
            })
            notification.notify(project)
        }
    }

    /** The hints shown since the last call (tests). */
    @TestOnly
    fun drainForTests(): List<String> = reported.toList().also { reported.clear() }

    private fun key(rootKey: String, label: String): String = "$rootKey\u0000$label"

    companion object {
        /** The vault warnings group (balloon; plan amendment R21). */
        private const val WARNINGS_GROUP = "Ansibility.Vault.Warnings"

        /** The operations you start on one envelope or a selection (never TEST over a root, RENDER_PREVIEW or ANALYSIS). */
        private val EXPLICIT = setOf(
            VaultPurpose.REVEAL, VaultPurpose.COPY, VaultPurpose.EDIT, VaultPurpose.REKEY, VaultPurpose.CHANGE_ID,
            VaultPurpose.OPEN_DECRYPTED, VaultPurpose.DECRYPT_TO_PLAIN,
        )

        fun getInstance(project: Project): RotationHints = project.service()
    }
}
