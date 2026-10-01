package de.terletzkiy.ansibility.index

import com.intellij.openapi.progress.ProgressManager
import de.terletzkiy.ansibility.yaml.YamlPsi
import org.jetbrains.yaml.psi.YAMLMapping
import org.jetbrains.yaml.psi.YAMLScalar
import org.jetbrains.yaml.psi.YAMLSequence
import org.jetbrains.yaml.psi.YAMLValue

/** Walks the scalar values (never keys) of a YAML value tree where they are written, with their key paths. */
internal object YamlScalars {
    /**
     * Calls [action] for every scalar value below [top] with its key path (mapping keys and sequence indices, as
     * `YamlPaths.keyPath` spells them). Aliases are skipped: the anchored node is visited where it is written.
     */
    fun forEach(top: YAMLValue, action: (YAMLScalar, List<String>) -> Unit) = visit(top, ArrayList(), action)

    private fun visit(value: YAMLValue?, path: MutableList<String>, action: (YAMLScalar, List<String>) -> Unit) {
        ProgressManager.checkCanceled()
        when (value) {
            is YAMLScalar -> action(value, path)
            is YAMLMapping -> for (keyValue in value.keyValues) {
                path += keyValue.keyText
                visit(keyValue.value, path, action)
                path.removeAt(path.lastIndex)
            }
            is YAMLSequence -> value.items.forEachIndexed { i, item ->
                path += i.toString()
                val pairs = YamlPsi.flowPairs(item)
                if (pairs.isEmpty()) {
                    visit(item.value, path, action)
                } else {
                    for (pair in pairs) {
                        path += pair.keyText
                        visit(pair.value, path, action)
                        path.removeAt(path.lastIndex)
                    }
                }
                path.removeAt(path.lastIndex)
            }
            // Aliases, missing values and error recovery nodes carry no scalar of their own.
            else -> Unit
        }
    }
}
