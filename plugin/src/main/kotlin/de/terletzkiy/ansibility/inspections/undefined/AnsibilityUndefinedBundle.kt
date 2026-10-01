package de.terletzkiy.ansibility.inspections.undefined

import com.intellij.DynamicBundle
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.PropertyKey

private const val BUNDLE = "messages.AnsibilityUndefinedBundle"

/** Messages of ANS-V003 "possibly undefined variable" (plan amendment R7/R8, F8.12): problems, fixes, card rows, setting. */
object AnsibilityUndefinedBundle : DynamicBundle(BUNDLE) {
    @Nls
    fun message(@PropertyKey(resourceBundle = BUNDLE) key: String, vararg params: Any): String = getMessage(key, *params)
}
