package de.terletzkiy.ansibility.types

import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.ModificationTracker
import com.intellij.psi.PsiFile
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.util.indexing.FileBasedIndex
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.TypeCheckService
import de.terletzkiy.ansibility.api.TypeFinding
import de.terletzkiy.ansibility.context.TargetVersionDetector
import de.terletzkiy.ansibility.index.ValueSummary
import de.terletzkiy.ansibility.index.VarDefIndex
import de.terletzkiy.ansibility.index.VarUseIndex
import de.terletzkiy.ansibility.settings.AnsibilityProjectSettings
import org.jetbrains.yaml.YAMLLanguage
import org.jetbrains.yaml.psi.YAMLFile

/**
 * The project's [TypeCheckService] (plan F3.1–F3.2, F4.4): runs [FileTypeCheck] once per file version and shares the
 * result between the eleven type inspections of one highlighting pass.
 *
 * The result is cached on the PSI file until any YAML file changes (the file itself, spec files, plays and loops),
 * the `ansible.var.def`/`ansible.var.use` indexes, the Ansible structure, the project settings, a root's target
 * ansible-core or the project roots change. Vault files (`vault.yml`, `vault_prod.yml` …) are never checked; they
 * hold secrets, and their values are vault-encrypted anyway.
 */
class TypeCheckServiceImpl(private val project: Project) : TypeCheckService {
    private val defIndexStamp = ModificationTracker { FileBasedIndex.getInstance().getIndexModificationStamp(VarDefIndex.NAME, project) }
    private val useIndexStamp = ModificationTracker { FileBasedIndex.getInstance().getIndexModificationStamp(VarUseIndex.NAME, project) }

    override fun findings(file: PsiFile): List<TypeFinding> {
        val yaml = file as? YAMLFile ?: return emptyList()
        if (DumbService.isDumb(project)) return emptyList()
        return CachedValuesManager.getCachedValue(yaml, KEY) { CachedValueProvider.Result.create(compute(yaml), *dependencies(yaml)) }
    }

    private fun compute(file: YAMLFile): List<TypeFinding> {
        val virtualFile = file.originalFile.virtualFile ?: file.viewProvider.virtualFile
        if (ValueSummary.isVaultFileName(virtualFile.name)) return emptyList()
        val context = AnsibleWorkspace.getInstance(project).contextOf(virtualFile) ?: return emptyList()
        if (context.kind !in ValueSites.CHECKED_KINDS) return emptyList()
        return FileTypeCheck(project, file, context).run()
    }

    private fun dependencies(file: YAMLFile): Array<Any> = arrayOf(
        file,
        PsiModificationTracker.getInstance(project).forLanguage(YAMLLanguage.INSTANCE),
        defIndexStamp,
        useIndexStamp,
        AnsibleWorkspace.getInstance(project).structureTracker,
        AnsibilityProjectSettings.getInstance(project).modificationTracker,
        TargetVersionDetector.getInstance(project).modificationTracker,
        ProjectRootManager.getInstance(project),
    )

    private companion object {
        val KEY: Key<CachedValue<List<TypeFinding>>> = Key.create("ansibility.types.findings")
    }
}
