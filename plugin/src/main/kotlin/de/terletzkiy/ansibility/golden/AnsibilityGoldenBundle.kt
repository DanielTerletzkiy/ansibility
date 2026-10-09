package de.terletzkiy.ansibility.golden

import com.intellij.DynamicBundle
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.PropertyKey

private const val BUNDLE = "messages.AnsibilityGoldenBundle"

/** Messages of the golden features (plan amendment R24): compare, last change, align and push. */
object AnsibilityGoldenBundle : DynamicBundle(BUNDLE) {
    @Nls
    fun message(@PropertyKey(resourceBundle = BUNDLE) key: String, vararg params: Any): String = getMessage(key, *params)
}
