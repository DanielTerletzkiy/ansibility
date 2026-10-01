package de.terletzkiy.ansibility.dispatch

import com.intellij.DynamicBundle
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.PropertyKey

private const val BUNDLE = "messages.AnsibilityDispatchBundle"

/** Messages of the dispatch area (web doc targets, the X75 "Show Ansible Context" action, SchemaStore coexistence). */
object AnsibilityDispatchBundle : DynamicBundle(BUNDLE) {
    @Nls
    fun message(@PropertyKey(resourceBundle = BUNDLE) key: String, vararg params: Any): String =
        getMessage(key, *params)
}
