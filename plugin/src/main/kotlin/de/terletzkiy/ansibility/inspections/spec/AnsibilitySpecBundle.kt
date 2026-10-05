package de.terletzkiy.ansibility.inspections.spec

import com.intellij.DynamicBundle
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.PropertyKey

private const val BUNDLE = "messages.AnsibilitySpecBundle"

/** Messages of ANS-S002 "role input not in the argument spec": problems and fixes. */
object AnsibilitySpecBundle : DynamicBundle(BUNDLE) {
    @Nls
    fun message(@PropertyKey(resourceBundle = BUNDLE) key: String, vararg params: Any): String = getMessage(key, *params)
}
