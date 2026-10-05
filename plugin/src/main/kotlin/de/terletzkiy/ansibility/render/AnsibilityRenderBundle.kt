package de.terletzkiy.ansibility.render

import com.intellij.DynamicBundle
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.PropertyKey

private const val BUNDLE = "messages.AnsibilityRenderBundle"

/** Messages of the rendering area (plan amendment R11): the template preview, its pickers and actions. */
object AnsibilityRenderBundle : DynamicBundle(BUNDLE) {
    @Nls
    fun message(@PropertyKey(resourceBundle = BUNDLE) key: String, vararg params: Any): String =
        getMessage(key, *params)
}
