package de.terletzkiy.ansibility.model.drift

import com.intellij.DynamicBundle
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.PropertyKey

private const val BUNDLE = "messages.AnsibilityDriftBundle"

/** Messages of role drift (plan amendment R9, F9.5): tier badges and their tooltips. */
object AnsibilityDriftBundle : DynamicBundle(BUNDLE) {
    @Nls
    fun message(@PropertyKey(resourceBundle = BUNDLE) key: String, vararg params: Any): String =
        getMessage(key, *params)
}
