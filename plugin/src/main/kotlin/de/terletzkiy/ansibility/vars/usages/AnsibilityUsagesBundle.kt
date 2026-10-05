package de.terletzkiy.ansibility.vars.usages

import com.intellij.DynamicBundle
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.PropertyKey

private const val BUNDLE = "messages.AnsibilityUsagesBundle"

/**
 * Messages of Find Usages for variables (plan amendment FU, F1.10): the search target's presentation, the usage-type
 * groups of the Find tool window, the highlighting status text and the card's "Used in" row.
 */
object AnsibilityUsagesBundle : DynamicBundle(BUNDLE) {
    @Nls
    fun message(@PropertyKey(resourceBundle = BUNDLE) key: String, vararg params: Any): String =
        getMessage(key, *params)
}
