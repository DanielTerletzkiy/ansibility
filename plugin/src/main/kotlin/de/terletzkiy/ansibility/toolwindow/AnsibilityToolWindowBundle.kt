package de.terletzkiy.ansibility.toolwindow

import com.intellij.DynamicBundle
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.PropertyKey

private const val BUNDLE = "messages.AnsibilityToolWindowBundle"

/** Messages of the "Ansibility" tool window: node labels, precedence-level labels and tooltips, the details pane. */
object AnsibilityToolWindowBundle : DynamicBundle(BUNDLE) {
    @Nls
    fun message(@PropertyKey(resourceBundle = BUNDLE) key: String, vararg params: Any): String =
        getMessage(key, *params)
}
