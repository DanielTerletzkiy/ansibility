package de.terletzkiy.ansibility.model.role

import com.intellij.openapi.util.Key
import com.intellij.openapi.util.TextRange
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import de.terletzkiy.ansibility.semantics.yaml.SourceRange
import de.terletzkiy.ansibility.semantics.yaml.YMap
import de.terletzkiy.ansibility.semantics.yaml.YScalar
import de.terletzkiy.ansibility.semantics.yaml.YSeq
import de.terletzkiy.ansibility.semantics.yaml.YValue
import de.terletzkiy.ansibility.yaml.PsiYValueAdapter
import org.jetbrains.yaml.psi.YAMLFile

/** One `dependencies:` entry of a role's `meta/main.yml`. */
data class RoleDependency(
    /** The role name: the text as written, or its last path segment when it is a path. */
    val name: String,
    /** The reference as written (`docker`, `../docker`, `/ansible/roles/docker`). */
    val written: String,
    val tags: List<String>,
    /** The range of the role name in `meta/main.yml`. */
    val range: TextRange?,
)

/**
 * Reads the `dependencies:` of a role's `meta/main.yml` (ansible-core `RoleMetadata`): a list whose items are a
 * role name (`- docker`) or a mapping with `role:` (or `name:`, or galaxy's `src:`) plus keywords such as
 * `tags`. In the target repo only `jenkins-agent-docker` has dependencies: `[{role: docker, tags: ['docker']}]`.
 */
object RoleMeta {
    private val DEPENDENCIES = Key.create<CachedValue<List<RoleDependency>>>("ansibility.model.roleDependencies")

    /** The dependencies of a loaded `meta/main.yml` document. */
    fun dependencies(document: YValue?): List<RoleDependency> {
        val list = (document as? YMap)?.get("dependencies") as? YSeq ?: return emptyList()
        return list.items.mapNotNull(::dependency)
    }

    /** The dependencies of [meta], cached until the file changes. */
    fun dependencies(meta: YAMLFile): List<RoleDependency> =
        CachedValuesManager.getCachedValue(meta, DEPENDENCIES) {
            CachedValueProvider.Result.create(dependencies(PsiYValueAdapter.documentValue(meta)), meta)
        }

    /** The role name of a reference: a path's last segment (`../roles/docker` → `docker`), else the text. */
    fun nameOf(written: String): String = written.trimEnd('/').substringAfterLast('/')

    private fun dependency(item: YValue): RoleDependency? = when (item) {
        is YScalar -> item.text.trim().takeIf { it.isNotEmpty() }?.let { RoleDependency(nameOf(it), it, emptyList(), item.range?.let(::range)) }
        is YMap -> {
            val ref = (item["role"] ?: item["name"] ?: item["src"]) as? YScalar
            ref?.text?.trim()?.takeIf { it.isNotEmpty() }?.let { written ->
                RoleDependency(nameOf(written), written, tags(item["tags"]), ref.range?.let(::range))
            }
        }
        else -> null
    }

    private fun tags(value: YValue?): List<String> = when (value) {
        is YScalar -> value.text.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        is YSeq -> value.items.mapNotNull { (it as? YScalar)?.text }
        else -> emptyList()
    }

    private fun range(range: SourceRange): TextRange = TextRange(range.start, range.end)
}
