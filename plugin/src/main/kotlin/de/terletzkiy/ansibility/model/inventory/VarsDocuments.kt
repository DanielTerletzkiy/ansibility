package de.terletzkiy.ansibility.model.inventory

import com.intellij.lang.Language
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileTypes.PlainTextLanguage
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.ModificationTracker
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiBinaryFile
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiManager
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import de.terletzkiy.ansibility.index.ValueSummary
import de.terletzkiy.ansibility.semantics.yaml.YValue
import de.terletzkiy.ansibility.yaml.PsiYValueAdapter
import org.jetbrains.yaml.YAMLLanguage
import org.jetbrains.yaml.psi.YAMLFile
import java.io.IOException

/**
 * Loads inventory files and vars files as the [YValue] ansible-core's loader would build, with file offsets.
 *
 * YAML files go through their own PSI ([PsiYValueAdapter.documentValue], cached per file). Files the IDE does not
 * type as YAML (`group_vars/web.json`, `group_vars/web` without an extension) are parsed as YAML from their
 * current text: ansible-core's `DataLoader` accepts JSON and YAML alike, and JSON is YAML-compatible, so the values
 * and the key offsets match the file. Those results are cached on the file's own PSI.
 *
 * Call inside a read action.
 */
object VarsDocuments {
    private val LOG = logger<VarsDocuments>()
    private val TEXT_VALUE = Key.create<CachedValue<YValue?>>("ansibility.model.varsTextValue")

    /**
     * The first document of [file], or null when the file is missing, empty, binary or not loadable. Inside a
     * [ModelCache] computation the file becomes an input of the value being computed ([ModelInputs.file]).
     */
    fun load(project: Project, file: VirtualFile?): YValue? {
        if (file == null || !file.isValid || file.isDirectory) return null
        ModelInputs.file(project, file)
        return when (val psi = PsiManager.getInstance(project).findFile(file)) {
            is YAMLFile -> PsiYValueAdapter.documentValue(psi)
            // Binary content (a whole-file vault is text, so this is never a vars file ansible-core could load).
            is PsiBinaryFile -> null
            // No PSI (e.g. above the PSI size limit): parse the text once, uncached.
            null -> readText(file)?.let { parseYaml(project, file.name, it) }
            else -> CachedValuesManager.getCachedValue(psi, TEXT_VALUE) {
                CachedValueProvider.Result.create(parseYaml(project, file.name, psi.text), psi)
            }
        }
    }

    /**
     * Changes whenever the PSI of a file that can hold vars changes: YAML, JSON, INI (inventories) and plain text. Edits of other
     * languages (Python, Jinja templates …) leave it alone. A coarse tracker for consumers outside the model; the
     * model caches themselves depend only on the files they read ([ModelCache]).
     */
    fun tracker(project: Project): ModificationTracker =
        PsiModificationTracker.getInstance(project).forLanguages(::isVarsLanguage)

    /**
     * Whether values of [file] must never be previewed: files named `vault…` (`vault.yml`, `vault_prod.yml`,
     * `vault`), which hold secrets even when a value is not `!vault`-encrypted (plan A.7, F4.8).
     */
    fun isVaultFile(file: VirtualFile): Boolean = ValueSummary.isVaultFileName(file.name)

    private fun isVarsLanguage(language: Language): Boolean =
        language.isKindOf(YAMLLanguage.INSTANCE) || language == PlainTextLanguage.INSTANCE ||
            language.id == "JSON" || language.id == "JSON5" || language.id == "Ini"

    private fun parseYaml(project: Project, name: String, text: String): YValue? {
        val light = PsiFileFactory.getInstance(project).createFileFromText(name, YAMLLanguage.INSTANCE, text) as? YAMLFile
            ?: return null
        return PsiYValueAdapter.documentValue(light)
    }

    private fun readText(file: VirtualFile): String? = try {
        VfsUtilCore.loadText(file)
    } catch (e: IOException) {
        LOG.debug("Cannot read ${file.path}", e)
        null
    }
}
