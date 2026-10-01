package de.terletzkiy.ansibility.vars

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.SourceLocation
import de.terletzkiy.ansibility.model.task.YamlFiles
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLScalar

/** Positions of variable definitions: `path:line` labels relative to a root, and the PSI written there. */
internal object VarLocations {
    /** The 1-based line of [offset] in [file]. */
    fun line(file: VirtualFile, offset: Int): Int {
        val document = FileDocumentManager.getInstance().getDocument(file)
        if (document != null && offset <= document.textLength) return document.getLineNumber(offset) + 1
        return StringUtil.offsetToLineNumber(VfsUtilCore.loadText(file), offset) + 1
    }

    /** [file]'s path relative to [root] (or to the root's parent for family files outside it, else the full path). */
    fun path(root: AnsibleRoot, file: VirtualFile): String =
        VfsUtilCore.getRelativePath(file, root.dir)
            ?: root.dir.parent?.let { VfsUtilCore.getRelativePath(file, it) }
            ?: file.presentableUrl

    /** `roles/postfix/defaults/main.yml:2`: [location] relative to [root] with its 1-based line. */
    fun label(root: AnsibleRoot, location: SourceLocation): String = "${path(root, location.file)}:${line(location.file, location.offset)}"

    /** The key-value whose key starts at [location] (its YAML view, whatever the file type), or null. */
    fun keyValueAt(project: Project, location: SourceLocation): YAMLKeyValue? {
        val yaml = YamlFiles.yamlFile(project, location.file) ?: return null
        val keyValue = PsiTreeUtil.getParentOfType(yaml.findElementAt(location.offset), YAMLKeyValue::class.java, false) ?: return null
        return keyValue.takeIf { it.key?.textRange?.startOffset == location.offset }
    }

    /**
     * The element written at [location]: the key-value of a key, else the scalar that starts there (the name of
     * `register: name`), else the leaf at the offset (template files).
     */
    fun elementAt(project: Project, location: SourceLocation): PsiElement? {
        keyValueAt(project, location)?.let { return it }
        val psi = PsiManager.getInstance(project).findFile(location.file) ?: return null
        val leaf = psi.findElementAt(location.offset) ?: return psi
        return PsiTreeUtil.getParentOfType(leaf, YAMLScalar::class.java, false)?.takeIf { it.textRange.startOffset == location.offset } ?: leaf
    }
}
