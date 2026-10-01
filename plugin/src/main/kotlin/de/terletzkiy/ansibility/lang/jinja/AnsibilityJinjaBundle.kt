package de.terletzkiy.ansibility.lang.jinja

import com.intellij.DynamicBundle
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.PropertyKey
import java.util.function.Supplier

private const val BUNDLE = "messages.AnsibilityJinjaBundle"

/** Messages of the Jinja area, read from `messages/AnsibilityJinjaBundle.properties`. */
object AnsibilityJinjaBundle : DynamicBundle(BUNDLE) {
    @Nls
    fun message(@PropertyKey(resourceBundle = BUNDLE) key: String, vararg params: Any): String =
        getMessage(key, *params)

    /** A message resolved on every call, for UI that must follow a language-pack switch. */
    fun lazyMessage(@PropertyKey(resourceBundle = BUNDLE) key: String, vararg params: Any): Supplier<@Nls String> =
        getLazyMessage(key, *params)
}
