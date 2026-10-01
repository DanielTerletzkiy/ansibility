package de.terletzkiy.ansibility.vault.ui

import com.intellij.DynamicBundle
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.PropertyKey

private const val BUNDLE = "messages.AnsibilityVaultUiBundle"

/**
 * Messages of the vault UI (plan amendment R7/R8: Reveal, Copy, Edit, the value intentions, the card section, the
 * folding placeholder and the gutter). No text carries a password, a plaintext or a ciphertext body.
 */
object AnsibilityVaultUiBundle : DynamicBundle(BUNDLE) {
    @Nls
    fun message(@PropertyKey(resourceBundle = BUNDLE) key: String, vararg params: Any): String =
        getMessage(key, *params)
}
