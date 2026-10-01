package de.terletzkiy.ansibility.navigation

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.indexing.FileBasedIndex
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.FileContext
import de.terletzkiy.ansibility.index.AnsibleIndexQueries
import de.terletzkiy.ansibility.index.RenderEntry
import de.terletzkiy.ansibility.index.RootFamily
import de.terletzkiy.ansibility.index.SrcKind
import de.terletzkiy.ansibility.index.TemplateUseIndex
import de.terletzkiy.ansibility.lang.jinja.refs.JinjaRefs
import de.terletzkiy.ansibility.semantics.yaml.YScalar
import de.terletzkiy.ansibility.yaml.PsiYValueAdapter
import org.jetbrains.yaml.psi.YAMLDocument
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLScalar
import org.jetbrains.yaml.psi.YAMLSequenceItem

/**
 * A place in vars files whose values a rendering task turns into template names (plan F1.8, F4.2 X19/X50): a
 * `template` task whose `src` is a static prefix, one expression and a static suffix, e.g.
 * `src: "templates/nginx/{{ item.floating.template }}"` looping over `{{ app_wren_mono_nginx_sites }}`
 * (`repos/wren/ansible/roles/app-wren-mono/tasks/nginx.yml:14`) makes every `app_wren_mono_nginx_sites[].floating.template`
 * value a template name under `templates/nginx/`.
 */
internal data class TemplateNameSlot(
    /** The vars-file key path the values sit at: keys, and [TemplateNames.ITEM] for sequence items. */
    val pattern: List<String>,
    /** The static text before the expression (`templates/nginx/`). */
    val prefix: String,
    /** The static text after the expression (usually empty). */
    val suffix: String,
    /** The task file of the rendering task. */
    val renderer: VirtualFile,
    /** The offset of the `src` value in [renderer]. */
    val srcOffset: Int,
) {
    /** The `src` the task renders for [value]. */
    fun sourceFor(value: String): String = prefix + value + suffix
}

/**
 * Template-name values in vars files ([RefKind.TEMPLATE_NAME]): the slots of a root come from the
 * `ansible.template.use` index (dynamic-prefix renders with their `dynamicVarPath` and loop), and a vars-file scalar is
 * a template name when its key path equals a slot's pattern. Needs the indexes: returns nothing while indexing.
 * Call inside a read action.
 */
internal object TemplateNames {
    /** The path segment of a sequence item in [TemplateNameSlot.pattern]. */
    const val ITEM: String = "[]"

    /** The template-name value at [offset] of the vars file [file], or null. */
    fun at(file: PsiFile, offset: Int, context: FileContext): RefOccurrence? {
        val project = file.project
        if (DumbService.isDumb(project)) return null
        val yaml = RefSites.yamlOf(file) ?: return null
        val scalar = valueScalarAt(yaml, offset) ?: return null
        val value = (PsiYValueAdapter.toYValue(scalar) as? YScalar)?.text?.trim() ?: return null
        if (value.isEmpty() || RefOccurrence.isTemplated(value)) return null
        val path = pathOf(scalar) ?: return null
        val slot = slots(project, context.root).firstOrNull { it.pattern == path } ?: return null
        val range = RefSites.locate(file.viewProvider.contents, value, scalar.textRange)
        if (offset < range.startOffset || offset > range.endOffset) return null
        return RefOccurrence(RefKind.TEMPLATE_NAME, value, range, slot = slot)
    }

    /** The slots of [root], cached per generation of [RefCaches]; empty while indexing. */
    fun slots(project: Project, root: AnsibleRoot): List<TemplateNameSlot> {
        if (DumbService.isDumb(project)) return emptyList()
        return try {
            RefCaches.getInstance(project).slots(root) { computeSlots(project, root) }
        } catch (_: IndexNotReadyException) {
            emptyList()
        }
    }

    /** The key path of a value [scalar] from the document root: keys, and [ITEM] for sequence items. */
    fun pathOf(scalar: YAMLScalar): List<String>? {
        val path = ArrayList<String>()
        var current: PsiElement = scalar
        while (true) {
            val parent = current.parent ?: return null
            when (parent) {
                is YAMLKeyValue -> path += PsiYValueAdapter.keyOf(parent).text
                is YAMLSequenceItem -> path += ITEM
                is YAMLDocument, is PsiFile -> break
            }
            current = parent
        }
        return path.asReversed().takeIf { it.isNotEmpty() }
    }

    private fun valueScalarAt(file: PsiFile, offset: Int): YAMLScalar? {
        for (candidate in intArrayOf(offset, offset - 1)) {
            if (candidate < 0) continue
            val leaf = file.findElementAt(candidate) ?: continue
            val scalar = PsiTreeUtil.getParentOfType(leaf, YAMLScalar::class.java, false) ?: continue
            val parent = scalar.parent
            if (parent is YAMLSequenceItem || (parent is YAMLKeyValue && parent.value == scalar)) return scalar
        }
        return null
    }

    private fun computeSlots(project: Project, root: AnsibleRoot): List<TemplateNameSlot> {
        val family = RootFamily.of(project, root)
        val keys = ArrayList<String>()
        FileBasedIndex.getInstance().processAllKeys(TemplateUseIndex.NAME, { key -> if (key.contains("{{")) keys += key; true }, family.scope, null)
        val slots = ArrayList<TemplateNameSlot>()
        for (key in keys) {
            ProgressManager.checkCanceled()
            for (hit in AnsibleIndexQueries.values(project, TemplateUseIndex.NAME, key, family)) {
                slotOf(key, hit.value, hit.file)?.let(slots::add)
            }
        }
        return slots.distinct()
    }

    /** The slot of one render, or null when its `src` is not prefix + one expression + suffix over a known path. */
    fun slotOf(src: String, entry: RenderEntry, renderer: VirtualFile): TemplateNameSlot? {
        if (entry.srcKind != SrcKind.DYNAMIC_PREFIX || entry.dynamicVarPath.isEmpty()) return null
        val open = src.indexOf("{{")
        val close = src.indexOf("}}", open)
        if (open <= 0 || close < 0) return null
        val suffix = src.substring(close + 2)
        if (RefOccurrence.isTemplated(suffix)) return null
        val path = entry.dynamicVarPath
        val pattern = if (entry.loopVar != null && path.first() == entry.loopVar) {
            val source = entry.loopExprText?.let(::loopSource) ?: return null
            source + ITEM + path.drop(1)
        } else {
            path
        }
        return TemplateNameSlot(pattern, src.substring(0, open), suffix, renderer, entry.srcOffset)
    }

    /** The variable path a loop iterates: the first reference of `{{ sites }}` or `{{ x.sites | selectattr(…) }}`. */
    private fun loopSource(loop: String): List<String>? {
        if (!RefOccurrence.isTemplated(loop)) return null
        val first = JinjaRefs.analyze(loop).references.firstOrNull() ?: return null
        return listOf(first.name) + first.attrPath
    }
}
