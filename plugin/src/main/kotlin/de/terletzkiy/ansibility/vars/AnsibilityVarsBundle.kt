package de.terletzkiy.ansibility.vars

import com.intellij.DynamicBundle
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.PropertyKey

private const val BUNDLE = "messages.AnsibilityVarsBundle"

/** Messages of the variables area: the variable documentation card, its hint and the navigation chooser. */
object AnsibilityVarsBundle : DynamicBundle(BUNDLE) {
    @Nls
    fun message(@PropertyKey(resourceBundle = BUNDLE) key: String, vararg params: Any): String =
        getMessage(key, *params)
}
