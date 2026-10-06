package de.terletzkiy.ansibility.navigation

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.FileKind
import de.terletzkiy.ansibility.render.lookup.SearchPath

/**
 * Ctrl+B on the path of `lookup('file' | 'template' | 'fileglob', '…')` (also `query`/`q`), plan X37: resolved like
 * ansible-core's search path, the role's `files/` or `templates/` first, then the playbook directory. A glob opens the
 * chooser with every match.
 */
class LookupPathNavigation : GotoDeclarationHandler {
    override fun getGotoDeclarationTargets(element: PsiElement?, offset: Int, editor: Editor?): Array<PsiElement>? {
        element ?: return null
        val injected = InjectedLanguageManager.getInstance(element.project)
        val host = injected.getTopLevelFile(element) ?: return null
        val hostOffset = if (host == element.containingFile) offset else injected.injectedToHost(element, offset)
        val file = host.originalFile.virtualFile ?: return null
        val text = host.viewProvider.document?.charsSequence ?: host.text
        val (plugin, path) = pathAt(text, hostOffset) ?: return null
        val targets = resolve(element.project, file, plugin, path)
        if (targets.isEmpty()) return null
        val psi = PsiManager.getInstance(element.project)
        return targets.mapNotNull(psi::findFile).toTypedArray<PsiElement>().takeIf { it.isNotEmpty() }
    }

    companion object {
        private val LOOKUP = Regex("""\b(?:lookup|query|q)\(\s*['"](file|template|fileglob)['"]\s*,\s*['"]([^'"{}]+)['"]""")
        private const val WINDOW = 400

        /** The lookup plugin and path whose string literal contains [offset], or null. */
        fun pathAt(text: CharSequence, offset: Int): Pair<String, String>? {
            val from = (offset - WINDOW).coerceAtLeast(0)
            val window = text.subSequence(from, (offset + WINDOW).coerceAtMost(text.length)).toString()
            for (match in LOOKUP.findAll(window)) {
                val range = match.groups[2]!!.range
                if (offset - from in range.first..range.last + 1) return match.groupValues[1] to match.groupValues[2]
            }
            return null
        }

        fun resolve(project: Project, file: VirtualFile, plugin: String, path: String): List<VirtualFile> {
            val context = AnsibleWorkspace.getInstance(project).contextOf(file) ?: return emptyList()
            val playbookDir = if (context.kind == FileKind.PLAYBOOK) file.parent else context.root.dir
            val search = SearchPath(context.roleDir, playbookDir, listOf(context.root.dir))
            return when (plugin) {
                "template" -> listOfNotNull(search.template(path, null))
                "fileglob" -> search.fileglob(path).mapNotNull { file.fileSystem.findFileByPath(it) }
                else -> listOfNotNull(search.firstFound(listOf(path), emptyList())?.let { file.fileSystem.findFileByPath(it) })
            }
        }
    }
}
