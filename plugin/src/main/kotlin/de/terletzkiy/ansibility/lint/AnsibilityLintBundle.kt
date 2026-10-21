package de.terletzkiy.ansibility.lint

import com.intellij.DynamicBundle
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.PropertyKey

private const val BUNDLE = "messages.AnsibilityLintBundle"

/** Messages of the ansible-lint integration (plan amendment R28). */
object AnsibilityLintBundle : DynamicBundle(BUNDLE)

@Nls
internal fun message(@PropertyKey(resourceBundle = BUNDLE) key: String, vararg params: Any): String =
    AnsibilityLintBundle.getMessage(key, *params)
