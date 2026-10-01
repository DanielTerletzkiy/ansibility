package de.terletzkiy.ansibility.completion.jinja

import com.intellij.DynamicBundle
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.PropertyKey

private const val BUNDLE = "messages.AnsibilityJinjaCompletionBundle"

/** Messages of Jinja variable completion: tail texts, type texts and the popup documentation of non-role names. */
object AnsibilityJinjaCompletionBundle : DynamicBundle(BUNDLE) {
    @Nls
    fun message(@PropertyKey(resourceBundle = BUNDLE) key: String, vararg params: Any): String = getMessage(key, *params)
}
