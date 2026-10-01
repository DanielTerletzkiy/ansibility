package de.terletzkiy.ansibility.navigation

import com.intellij.DynamicBundle
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.PropertyKey

private const val BUNDLE = "messages.AnsibilityNavigationBundle"

/**
 * Messages of the role-navigation area (plan F1.8, X50): chooser labels, completion tails and the ANS-R001
 * unresolved-reference inspection with its quick fix.
 */
object AnsibilityNavigationBundle : DynamicBundle(BUNDLE) {
    @Nls
    fun message(@PropertyKey(resourceBundle = BUNDLE) key: String, vararg params: Any): String =
        getMessage(key, *params)
}
