package de.terletzkiy.ansibility.context.host

import com.intellij.DynamicBundle
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.PropertyKey

private const val BUNDLE = "messages.AnsibilityHostBundle"

/** Messages of the host-awareness area (plan amendment R7/R8, A.14): empty-scope reasons and selection problems. */
object AnsibilityHostBundle : DynamicBundle(BUNDLE) {
    @Nls
    fun message(@PropertyKey(resourceBundle = BUNDLE) key: String, vararg params: Any): String =
        getMessage(key, *params)
}
