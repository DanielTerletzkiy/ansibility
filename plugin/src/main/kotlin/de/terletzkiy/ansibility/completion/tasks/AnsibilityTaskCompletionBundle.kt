package de.terletzkiy.ansibility.completion.tasks

import com.intellij.DynamicBundle
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.PropertyKey

private const val BUNDLE = "messages.AnsibilityTaskCompletionBundle"

/**
 * Messages of task completion (plan F5.7, X19): tail and type texts of module, option, keyword and value items,
 * from `messages/AnsibilityTaskCompletionBundle.properties`.
 */
object AnsibilityTaskCompletionBundle : DynamicBundle(BUNDLE) {
    @Nls
    fun message(@PropertyKey(resourceBundle = BUNDLE) key: String, vararg params: Any): String =
        getMessage(key, *params)
}
