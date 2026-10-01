package de.terletzkiy.ansibility.types

import com.intellij.DynamicBundle
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.PropertyKey

private const val BUNDLE = "messages.AnsibilityTypesBundle"

/**
 * Messages of the role-variable type checks (plan F3.2, F4.4, X79, X80): inspection names, the context the plugin adds
 * to the semantics layer's findings (roles, reachability, loop reads) and the quick fixes.
 */
object AnsibilityTypesBundle : DynamicBundle(BUNDLE) {
    @Nls
    fun message(@PropertyKey(resourceBundle = BUNDLE) key: String, vararg params: Any): String =
        getMessage(key, *params)
}
