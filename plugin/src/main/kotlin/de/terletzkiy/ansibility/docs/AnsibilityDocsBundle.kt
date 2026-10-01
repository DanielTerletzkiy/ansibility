package de.terletzkiy.ansibility.docs

import com.intellij.DynamicBundle
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.PropertyKey

private const val BUNDLE = "messages.AnsibilityDocsBundle"

/** Messages of the docs area (module, option and keyword documentation, plan R5), from `messages/AnsibilityDocsBundle.properties`. */
object AnsibilityDocsBundle : DynamicBundle(BUNDLE) {
    @Nls
    fun message(@PropertyKey(resourceBundle = BUNDLE) key: String, vararg params: Any): String =
        getMessage(key, *params)
}
