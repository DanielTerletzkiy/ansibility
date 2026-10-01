package de.terletzkiy.ansibility.inspections.modules

import com.intellij.DynamicBundle
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.PropertyKey

private const val BUNDLE = "messages.AnsibilityModuleChecksBundle"

/**
 * Messages of the module option and keyword checks (plan M4 E3: ANS-M001, ANS-M002, module option values,
 * ANS-K001, ANS-K002) and their quick fixes.
 */
object AnsibilityModuleChecksBundle : DynamicBundle(BUNDLE) {
    @Nls
    fun message(@PropertyKey(resourceBundle = BUNDLE) key: String, vararg params: Any): String =
        getMessage(key, *params)
}
