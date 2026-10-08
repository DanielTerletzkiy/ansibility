package de.terletzkiy.ansibility.resolve

import com.intellij.json.JsonFileType
import com.intellij.lang.Language
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.fileTypes.PlainTextLanguage
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.ModificationTracker
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.util.indexing.FileBasedIndex
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.SpecBinding
import de.terletzkiy.ansibility.api.VarDefinition
import de.terletzkiy.ansibility.api.VarService
import de.terletzkiy.ansibility.api.VarSymbol
import de.terletzkiy.ansibility.context.MoleculeView
import de.terletzkiy.ansibility.context.MoleculeVisibility
import de.terletzkiy.ansibility.index.RootFamily
import de.terletzkiy.ansibility.index.VarDefIndex
import de.terletzkiy.ansibility.model.inventory.VarsDocuments
import org.jetbrains.yaml.YAMLFileType
import org.jetbrains.yaml.YAMLLanguage
import java.util.concurrent.ConcurrentHashMap

/**
 * The project's [VarService] (plan A.7, A.8; features F1.4, F1.5, F4.3): every definition and spec declaration of a
 * variable name inside one root, read from `ansible.var.def` and interpreted at query time.
 *
 * - **Scope.** [RootFamily]: the root directory minus nested and detached roots, plus the parent's inventories for a
 *   NESTED_PLAYBOOK root. A worktree copy or a sibling repo never appears.
 * - **Kinds.** Each entry's [de.terletzkiy.ansibility.api.VarDefKind], layer, environment, group, host and role come from
 *   `AnsibleWorkspace.contextOf(file)`; entries whose file kind contradicts their site are dropped.
 * - **Spec bindings.** Every `SPEC_OPTION` entry becomes a [SpecBinding] with the option parsed by `ArgSpecParser`
 *   (cached per spec file).
 * - **Inline inventory vars** of INI and other unindexed inventory sources come from the parsed inventory models
 *   ([InlineInventoryDefinitions]), deduplicated by location against the index entries.
 * - **Caching.** Symbols and name sets are cached per (root, view, name) until the `ansible.var.def` index stamp, the
 *   YAML PSI, the workspace structure or the project roots change.
 * - **Molecule views** (plan amendment R20, D153): [symbol] and [allNames] with a [MoleculeView], so navigation and
 *   search started outside Molecule never see Molecule definitions. A [MoleculeView.EXCLUDE] symbol is the cached full
 *   symbol without what [MoleculeVisibility.isMolecule] calls Molecule (Molecule files, Molecule inventories and,
 *   judged by cause, `include_vars` keys only Molecule playbooks load): no second index walk, and the one rule. The
 *   [MoleculeView.EXCLUDE] name set walks a family that admits no Molecule file ([RootFamily.view]) and leaves out those
 *   `include_vars` keys ([IncludedVarsDefinitions]). The [VarService] methods are [MoleculeView.INCLUDE]: the model,
 *   rename and inspections see everything. Callers outside this class go through [VarViews].
 *
 * All methods take a read lock when the caller holds none. They need smart mode: index access throws
 * `IndexNotReadyException` while indexing, so callers are not `DumbAware`.
 */
class VarServiceImpl(private val project: Project) : VarService {
    private val caches = ConcurrentHashMap<CacheKey, CachedValue<RootCache>>()

    /** One cache per root directory and view. */
    private data class CacheKey(val dir: VirtualFile, val view: MoleculeView)

    private val indexStamp = ModificationTracker { FileBasedIndex.getInstance().getIndexModificationStamp(VarDefIndex.NAME, project) }

    private val psiTracker: ModificationTracker by lazy {
        PsiModificationTracker.getInstance(project).forLanguages { it.isKindOf(YAMLLanguage.INSTANCE) || it.isKindOf(PlainTextLanguage.INSTANCE) || isJson(it) }
    }

    /** Symbols and the name set of one root, dropped together when any dependency changes. */
    private class RootCache {
        val symbols = ConcurrentHashMap<String, VarSymbol>()

        @Volatile
        var names: Set<String>? = null
    }

    override fun symbol(root: AnsibleRoot, name: String): VarSymbol = symbol(root, name, MoleculeView.INCLUDE)

    override fun allNames(root: AnsibleRoot): Collection<String> = allNames(root, MoleculeView.INCLUDE)

    /** [symbol] as a request with [view] sees it: with [MoleculeView.EXCLUDE] without any Molecule definition. */
    fun symbol(root: AnsibleRoot, name: String, view: MoleculeView): VarSymbol = readLocked {
        val cache = cacheOf(root, view)
        cache.symbols[name] ?: when (view) {
            MoleculeView.INCLUDE -> computeSymbol(root, name)
            // Both caches share their dependencies, so the production view never outlives the full symbol it came from.
            MoleculeView.EXCLUDE -> MoleculeVisibility.withoutMolecule(project, root, symbol(root, name, MoleculeView.INCLUDE))
        }.also { cache.symbols.putIfAbsent(name, it) }
    }

    /** [allNames] as a request with [view] sees them: with [MoleculeView.EXCLUDE] the names some production file defines. */
    fun allNames(root: AnsibleRoot, view: MoleculeView): Collection<String> = readLocked {
        val cache = cacheOf(root, view)
        cache.names ?: computeNames(root, view).also { cache.names = it }
    }

    private fun computeSymbol(root: AnsibleRoot, name: String): VarSymbol {
        val workspace = AnsibleWorkspace.getInstance(project)
        val family = RootFamily.of(project, root, workspace)
        val definitions = ArrayList<VarDefinition>()
        val bindings = ArrayList<SpecBinding>()
        FileBasedIndex.getInstance().processValues(
            VarDefIndex.NAME, name, null,
            FileBasedIndex.ValueProcessor { file, entries ->
                ProgressManager.checkCanceled()
                val context = workspace.contextOf(file)
                if (context != null && family.admits(file, context)) {
                    for (entry in entries) {
                        val definition = VarDefinitions.definition(project, name, file, context, entry) ?: continue
                        definitions += definition
                        VarDefinitions.specBinding(project, name, file, context, entry)?.let(bindings::add)
                    }
                }
                true
            },
            family.scope,
        )
        val indexed = definitions.mapTo(HashSet()) { it.location }
        InlineInventoryDefinitions.of(project, root, name).filterTo(definitions) { it.location !in indexed }
        IncludedVarsDefinitions.of(project, root, name).filterTo(definitions) { it.location !in indexed && definitions.none { d -> d.location == it.location } }
        definitions.sortWith(compareBy<VarDefinition>({ it.location.file.path }, { it.location.offset }))
        bindings.sortWith(compareBy<SpecBinding>({ it.location.file.path }, { it.location.offset }))
        return VarSymbol(root.dir, name, definitions, bindings)
    }

    /**
     * Names with at least one counting definition. Reads each candidate file's index data once instead of probing
     * every key of the project-wide index.
     */
    private fun computeNames(root: AnsibleRoot, view: MoleculeView): Set<String> {
        val workspace = AnsibleWorkspace.getInstance(project)
        val family = RootFamily.of(project, root, workspace, view)
        val index = FileBasedIndex.getInstance()
        val names = HashSet<String>()
        val seen = HashSet<VirtualFile>()
        for (type in varsFileTypes()) {
            FileTypeIndex.processFiles(
                type,
                { file ->
                    ProgressManager.checkCanceled()
                    if (seen.add(file)) {
                        val context = workspace.contextOf(file)
                        if (context != null && family.admits(file, context)) {
                            for ((name, entries) in index.getFileData(VarDefIndex.NAME, file, project)) {
                                if (entries.any { VarDefinitions.classify(it, context) != null }) names += name
                            }
                        }
                    }
                    true
                },
                family.scope,
            )
        }
        // Inline inventory variables come from the environments' inventories, never from Molecule scenarios.
        names += InlineInventoryDefinitions.names(project, root)
        names += IncludedVarsDefinitions.names(project, root, view)
        return names
    }

    /** The file types `ansible.var.def` reads YAML from: YAML, and vars files typed as plain text or JSON. */
    private fun varsFileTypes(): List<FileType> = listOf(YAMLFileType.YML, PlainTextFileType.INSTANCE, JsonFileType.INSTANCE)

    private fun cacheOf(root: AnsibleRoot, view: MoleculeView): RootCache {
        val value = caches.computeIfAbsent(CacheKey(root.dir, view)) {
            CachedValuesManager.getManager(project).createCachedValue(
                {
                    CachedValueProvider.Result.create(
                        RootCache(),
                        indexStamp, psiTracker, VarsDocuments.tracker(project), AnsibleWorkspace.getInstance(project).structureTracker,
                        ProjectRootManager.getInstance(project),
                    )
                },
                false,
            )
        }
        return value.value
    }

    private fun isJson(language: Language): Boolean = language.id == "JSON"

    private fun <T> readLocked(action: () -> T): T =
        if (ApplicationManager.getApplication().isReadAccessAllowed) action() else runReadActionBlocking(action)

    companion object {
        /** This project's service, when it is this implementation (tests and internal callers). */
        fun getInstance(project: Project): VarServiceImpl? = VarService.getInstance(project) as? VarServiceImpl
    }
}
