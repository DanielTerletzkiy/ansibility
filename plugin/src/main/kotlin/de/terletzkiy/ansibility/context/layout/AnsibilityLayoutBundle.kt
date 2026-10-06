package de.terletzkiy.ansibility.context.layout

import com.intellij.DynamicBundle
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.PropertyKey

private const val BUNDLE = "messages.AnsibilityLayoutBundle"

/** Messages of the project layout: diagnostics, the Layout settings page and the layout report. */
object AnsibilityLayoutBundle : DynamicBundle(BUNDLE) {
    @Nls
    fun message(@PropertyKey(resourceBundle = BUNDLE) key: String, vararg params: Any): String = getMessage(key, *params)
}
