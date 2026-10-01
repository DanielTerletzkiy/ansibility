package de.terletzkiy.ansibility.typeflow

import com.intellij.DynamicBundle
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.PropertyKey

private const val BUNDLE = "messages.AnsibilityTypeflowBundle"

/** Messages of the templated-value type checks (ANS-T020): the inspection, its findings and its quick fixes. */
object AnsibilityTypeflowBundle : DynamicBundle(BUNDLE) {
    @Nls
    fun message(@PropertyKey(resourceBundle = BUNDLE) key: String, vararg params: Any): String =
        getMessage(key, *params)
}
