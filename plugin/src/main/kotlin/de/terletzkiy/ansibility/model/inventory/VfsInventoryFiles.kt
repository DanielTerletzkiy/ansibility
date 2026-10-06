package de.terletzkiy.ansibility.model.inventory

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiBinaryFile
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import de.terletzkiy.ansibility.semantics.inventory.InventoryFileSystem
import de.terletzkiy.ansibility.semantics.inventory.YamlLoad
import de.terletzkiy.ansibility.yaml.PsiYValueAdapter
import org.jetbrains.yaml.YAMLLanguage
import org.jetbrains.yaml.psi.YAMLFile
import java.io.IOException

/**
 * The VFS as [de.terletzkiy.ansibility.semantics.inventory.InventorySources] reads it: text from the file's current
 * PSI (so unsaved edits count, as everywhere in the model), YAML through the YAML PSI, with a parse error reported
 * as [YamlLoad.Failed] so the INI plugin gets its turn on an extension-less `hosts`. Every file read becomes an
 * input of the [ModelCache] value being computed ([ModelInputs.file]). Call inside a read action.
 */
class VfsInventoryFiles(private val project: Project) : InventoryFileSystem<VirtualFile> {
    override fun name(file: VirtualFile): String = file.name

    override fun isDirectory(file: VirtualFile): Boolean = file.isDirectory

    override fun children(dir: VirtualFile): List<VirtualFile> = dir.children.orEmpty().filter { it.isValid }

    override fun exists(file: VirtualFile): Boolean = file.isValid

    override fun text(file: VirtualFile): String? {
        if (!file.isValid || file.isDirectory) return null
        ModelInputs.file(project, file)
        return when (val psi = PsiManager.getInstance(project).findFile(file)) {
            is PsiBinaryFile -> null
            null -> load(file)
            else -> psi.text
        }
    }

    override fun yaml(file: VirtualFile): YamlLoad {
        if (!file.isValid || file.isDirectory) return YamlLoad.Failed("not a file")
        ModelInputs.file(project, file)
        val yaml = PsiManager.getInstance(project).findFile(file) as? YAMLFile
            ?: text(file)?.let { PsiFileFactory.getInstance(project).createFileFromText(file.name, YAMLLanguage.INSTANCE, it) as? YAMLFile }
            ?: return YamlLoad.Failed("not readable")
        PsiTreeUtil.findChildOfType(yaml, PsiErrorElement::class.java)?.let { error ->
            return YamlLoad.Failed(error.errorDescription, error.textRange.startOffset)
        }
        return YamlLoad.Loaded(PsiYValueAdapter.documentValue(yaml))
    }

    override fun isExecutable(file: VirtualFile): Boolean =
        file.isInLocalFileSystem && runCatching { java.io.File(file.path).canExecute() }.getOrDefault(false)

    private fun load(file: VirtualFile): String? = try {
        VfsUtilCore.loadText(file)
    } catch (e: IOException) {
        LOG.debug("Cannot read ${file.path}", e)
        null
    }

    private companion object {
        val LOG = logger<VfsInventoryFiles>()
    }
}
