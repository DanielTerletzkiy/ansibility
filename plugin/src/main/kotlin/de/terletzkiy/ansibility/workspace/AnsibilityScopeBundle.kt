package de.terletzkiy.ansibility.workspace

import com.intellij.DynamicBundle
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.PropertyKey
import java.util.function.Supplier

private const val BUNDLE = "messages.AnsibilityScopeBundle"

/** Messages of the workspace scope (plan amendment R9, F9.1): choice labels, coverage, problems, the selector popup. */
object AnsibilityScopeBundle : DynamicBundle(BUNDLE) {
    @Nls
    fun message(@PropertyKey(resourceBundle = BUNDLE) key: String, vararg params: Any): String =
        getMessage(key, *params)

    /** A lazily resolved message, for action presentations created before the locale is known. */
    fun pointer(@PropertyKey(resourceBundle = BUNDLE) key: String, vararg params: Any): Supplier<@Nls String> =
        getLazyMessage(key, *params)
}
