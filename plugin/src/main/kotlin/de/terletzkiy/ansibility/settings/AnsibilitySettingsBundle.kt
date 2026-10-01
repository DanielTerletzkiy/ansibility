package de.terletzkiy.ansibility.settings

import com.intellij.DynamicBundle
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.PropertyKey

private const val BUNDLE = "messages.AnsibilitySettingsBundle"

/** Messages of the settings area (the Languages & Frameworks › Ansibility page). */
object AnsibilitySettingsBundle : DynamicBundle(BUNDLE) {
    @Nls
    fun message(@PropertyKey(resourceBundle = BUNDLE) key: String, vararg params: Any): String =
        getMessage(key, *params)
}
