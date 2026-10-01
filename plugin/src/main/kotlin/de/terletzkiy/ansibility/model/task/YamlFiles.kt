package de.terletzkiy.ansibility.model.task

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiManager
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import org.jetbrains.yaml.YAMLFileType
import org.jetbrains.yaml.YAMLLanguage
import org.jetbrains.yaml.psi.YAMLFile

/**
 * The YAML PSI of a file whatever its current file type. IDEs map some Ansible file names to other types
 * (PyCharm types `docker-compose.ansible-playbook.yaml` as Jinja2), and users may map `*.yml` themselves;
 * the models still read such files as the YAML Ansible loads.
 *
 * Order: the file's own [YAMLFile]; the YAML view of a multi-language file; else a non-physical YAML copy of its
 * text, cached until the file changes (offsets are the same as in the file). Call inside a read action.
 */
object YamlFiles {
    private val COPY = Key.create<CachedValue<YAMLFile?>>("ansibility.model.yamlCopy")

    fun yamlFile(project: Project, file: VirtualFile): YAMLFile? {
        if (!file.isValid || file.isDirectory) return null
        val psi = PsiManager.getInstance(project).findFile(file) ?: return null
        (psi as? YAMLFile)?.let { return it }
        (psi.viewProvider.getPsi(YAMLLanguage.INSTANCE) as? YAMLFile)?.let { return it }
        return CachedValuesManager.getCachedValue(psi, COPY) {
            val copy = PsiFileFactory.getInstance(project).createFileFromText(file.name, YAMLFileType.YML, psi.text) as? YAMLFile
            CachedValueProvider.Result.create(copy, psi)
        }
    }
}
