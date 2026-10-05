package de.terletzkiy.ansibility.resolve.register

import com.intellij.DynamicBundle
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.PropertyKey

private const val BUNDLE = "messages.AnsibilityRegisteredBundle"

/**
 * Messages of typed register results (plan amendment FU, F1.12): the built-in result members, the member cards, the
 * variable card's "Registered result" rows, completion tails and Ctrl+B targets. Texts of built-in members are looked up
 * by keys built at runtime through the inherited `messageOrNull`.
 */
object AnsibilityRegisteredBundle : DynamicBundle(BUNDLE) {
    @Nls
    fun message(@PropertyKey(resourceBundle = BUNDLE) key: String, vararg params: Any): String =
        getMessage(key, *params)
}
