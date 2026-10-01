package de.terletzkiy.ansibility.completion.keys

import com.intellij.DynamicBundle
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.PropertyKey

private const val BUNDLE = "messages.AnsibilityKeyCompletionBundle"

/**
 * Messages of vars-file key and value completion (plan F4.2, X19), from
 * `messages/AnsibilityKeyCompletionBundle.properties`.
 */
object AnsibilityKeyCompletionBundle : DynamicBundle(BUNDLE) {
    @Nls
    fun message(@PropertyKey(resourceBundle = BUNDLE) key: String, vararg params: Any): String =
        getMessage(key, *params)
}
