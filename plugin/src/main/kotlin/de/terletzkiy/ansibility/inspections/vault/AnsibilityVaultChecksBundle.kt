package de.terletzkiy.ansibility.inspections.vault

import com.intellij.DynamicBundle
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.PropertyKey

private const val BUNDLE = "messages.AnsibilityVaultChecksBundle"

/**
 * Messages of the password-free vault checks (plan amendment R7/R8, "R7 diagnostics": ANS-V101–V103) and their quick
 * fixes, and of the Ansible Vault Editor coexistence (F7.12, `coexist.vault`).
 */
object AnsibilityVaultChecksBundle : DynamicBundle(BUNDLE) {
    @Nls
    fun message(@PropertyKey(resourceBundle = BUNDLE) key: String, vararg params: Any): String =
        getMessage(key, *params)
}
