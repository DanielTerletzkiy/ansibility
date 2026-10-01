package de.terletzkiy.ansibility.navigation

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import de.terletzkiy.ansibility.api.FileContext

/**
 * Template-name values for the vars-file value completion (plan X19, X50): which templates a vars-file key names
 * when a rendering task turns its values into template names ([TemplateNameSlot], e.g.
 * `loki_nginx_sites[].floating.template` through `src: "templates/nginx/{{ item.floating.template }}"`).
 *
 * The answer comes from the same slots and search order as Ctrl+B on such a value, inside the vars file's root, so a
 * completion item and its navigation target always agree. Needs the indexes: empty while indexing. Call in a read
 * action.
 */
internal object TemplateNameValues {
    /**
     * The templates the value of [varPath] in the vars file [file] can name, by value (the template's path below the
     * rendering task's `src` prefix, its suffix removed): empty when no rendering task of [context]'s root turns the
     * key into a template name. [varPath] starts at the variable and holds sequence indices as written
     * (`["loki_nginx_sites", "0", "floating", "template"]`); [file] may be a completion copy.
     */
    fun templatesFor(file: PsiFile, context: FileContext, varPath: List<String>): Map<String, VirtualFile> {
        if (context.kind !in RefSites.VARS_KINDS || varPath.isEmpty()) return emptyMap()
        val pattern = varPath.map { if (it.toIntOrNull() != null) TemplateNames.ITEM else it }
        val slots = TemplateNames.slots(file.project, context.root).filter { it.pattern == pattern }
        if (slots.isEmpty()) return emptyMap()
        val resolver = RefResolver(file, context)
        val result = LinkedHashMap<String, VirtualFile>()
        for (slot in slots) resolver.templateNameValues(slot).forEach { (value, template) -> result.putIfAbsent(value, template) }
        return result
    }
}
