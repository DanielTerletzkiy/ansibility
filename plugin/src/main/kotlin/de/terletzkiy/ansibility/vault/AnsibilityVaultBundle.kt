package de.terletzkiy.ansibility.vault

import com.intellij.DynamicBundle
import de.terletzkiy.ansibility.api.VaultFailure
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.PropertyKey

private const val BUNDLE = "messages.AnsibilityVaultBundle"

/** Messages of the vault area (plan amendment R7/R8, A.13): consent and password dialogs, failure texts. */
object AnsibilityVaultBundle : DynamicBundle(BUNDLE) {
    @Nls
    fun message(@PropertyKey(resourceBundle = BUNDLE) key: String, vararg params: Any): String =
        getMessage(key, *params)

    /** The user-facing text for [failure]; carries no data, like the failure itself. */
    @Nls
    fun failure(failure: VaultFailure): String = when (failure) {
        VaultFailure.LOCKED -> message("failure.locked")
        VaultFailure.NO_IDENTITY -> message("failure.no.identity")
        VaultFailure.WRONG_SECRET -> message("failure.wrong.secret")
        VaultFailure.FORMAT -> message("failure.format")
        VaultFailure.UNKNOWN_CIPHER -> message("failure.unknown.cipher")
        VaultFailure.SOURCE_UNAVAILABLE -> message("failure.source.unavailable")
        VaultFailure.NOT_TRUSTED -> message("failure.not.trusted")
        VaultFailure.ENCRYPT_IDENTITY_REQUIRED -> message("failure.encrypt.identity.required")
        VaultFailure.ANALYSIS_DISABLED -> message("failure.analysis.disabled")
        VaultFailure.CANCELLED -> message("failure.cancelled")
    }
}
