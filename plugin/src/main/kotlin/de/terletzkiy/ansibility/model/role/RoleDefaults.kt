package de.terletzkiy.ansibility.model.role

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiBinaryFile
import com.intellij.psi.PsiManager
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.RoleInfo
import de.terletzkiy.ansibility.api.RoleRegistry
import de.terletzkiy.ansibility.api.SourceLocation
import de.terletzkiy.ansibility.context.AnsibleLayout
import de.terletzkiy.ansibility.index.JinjaBearing
import de.terletzkiy.ansibility.index.ValueSummary
import de.terletzkiy.ansibility.model.inventory.ModelInputs
import de.terletzkiy.ansibility.model.inventory.VarsConfig
import de.terletzkiy.ansibility.model.inventory.VarsDocuments
import de.terletzkiy.ansibility.semantics.precedence.HashBehaviour
import de.terletzkiy.ansibility.semantics.validate.SpecDefaults
import de.terletzkiy.ansibility.semantics.yaml.SourceRange
import de.terletzkiy.ansibility.semantics.yaml.YMap
import de.terletzkiy.ansibility.semantics.yaml.YValue

/**
 * "The role default": the value of a role variable Ansible actually uses, from the files it really loads (plan amendment
 * R23, D169/D173). The argument spec's `default:` never counts (ansible-core only documents and validates with it).
 *
 * Files ([loadedFiles], `Role._load_role_yaml` with `allow_dir` and `DataLoader.find_vars_files`): without
 * `defaults_from` the first that exists of `main.yml`, `main.yaml`, `main.json` and `main` (a file or a directory), so a
 * `defaults/main.yml` hides a `defaults/main/` directory completely; with `defaults_from: x` the first of `x`, `x.yml`,
 * `x.yaml`, `x.json`. A directory's files load in sorted order per level, recursively (subdirectories without an
 * extension), skipping hidden files and `~` backups, and only files with no extension or a vars extension.
 * `defaults/<other>.yml` is loaded only through `defaults_from`, which is why it never holds "the" role default.
 *
 * Values ([of], [scan]): each later file replaces a top-level key of the earlier ones (`combine_vars`; under
 * `hash_behaviour = merge` dictionaries merge, flagged [RoleDefault.merged]), and within one file the last duplicate key
 * wins (PyYAML). A loaded file that is not a mapping (a whole-file vault) is opaque: a winner loaded before it may be
 * overridden by it ([Scan.mayBeOverridden]). Dependency defaults ([lookup], [dependencies]) are combined first and lose
 * to the role's own (`get_default_vars`), so a dependency's value counts only when the role has none.
 *
 * VFS and the model's PSI-backed documents only ([VarsDocuments.load]); call inside a read action. Inside a model
 * cache computation the files become its inputs.
 */
object RoleDefaults {
    /** The search order of a main file (`Role._load_role_yaml`: the bare name last without `defaults_from`). */
    private val MAIN_EXTENSIONS = listOf(".yml", ".yaml", ".json")
    private const val MAX_DIRECTORY_DEPTH = 16
    private const val MAX_DEPENDENCY_DEPTH = 16
    private const val JSON = "json"
    private const val DOCUMENT_START = "---"

    /**
     * The files ansible-core loads from [subdir] (`defaults` or `vars`) of [roleDir], in load order: [from] (the
     * literal `defaults_from`/`vars_from`, ignored when templated) or the main file, a directory's files when the name
     * found is a directory.
     */
    fun loadedFiles(roleDir: VirtualFile, subdir: String = RoleLayout.DEFAULTS, from: String? = null): List<VirtualFile> {
        val dir = roleDir.findChild(subdir)?.takeIf { it.isDirectory } ?: return emptyList()
        val named = from?.takeUnless { it.isEmpty() || JinjaBearing.hasTemplateMarkers(it) }
        val candidates = if (named == null) {
            MAIN_EXTENSIONS.map { RoleLayout.MAIN + it } + RoleLayout.MAIN
        } else {
            listOf(named) + MAIN_EXTENSIONS.map { named + it }
        }
        for (candidate in candidates) {
            val found = dir.findFileByRelativePath(candidate) ?: continue
            return if (found.isDirectory) directoryFiles(found) else listOf(found)
        }
        return emptyList()
    }

    /** `_get_dir_vars_files`: sorted per level, recursive, vars files and extension-less files only. */
    private fun directoryFiles(dir: VirtualFile): List<VirtualFile> {
        val out = ArrayList<VirtualFile>()
        fun visit(current: VirtualFile, depth: Int) {
            for (child in current.children.orEmpty().sortedBy { it.name }) {
                ProgressManager.checkCanceled()
                when {
                    child.isDirectory -> if (depth < MAX_DIRECTORY_DEPTH && AnsibleLayout.isVarsSubdirectory(child.name)) visit(child, depth + 1)
                    AnsibleLayout.isVarsFileInDirectory(child.name) -> out += child
                }
            }
        }
        visit(dir, 0)
        return out
    }

    /**
     * Where a new role default of [roleDir] goes: the last loaded defaults file a YAML line can be appended to (the main
     * file, or the last such file of a `defaults/main/` directory, since a new `defaults/main.yml` would hide that
     * directory); [AppendTarget.Create] when the role loads no defaults file yet (`defaults/main.yml` is to be created);
     * [AppendTarget.None] when every loaded file is a vault file (by name or a whole-file vault), a JSON file or not a
     * mapping, which an appended line would corrupt.
     */
    fun appendTarget(project: Project, roleDir: VirtualFile): AppendTarget {
        val loaded = loadedFiles(roleDir)
        if (loaded.isEmpty()) return AppendTarget.Create
        return loaded.lastOrNull { appendable(project, it) }?.let(AppendTarget::Existing) ?: AppendTarget.None
    }

    /** A plain YAML mapping (or an empty file): not a vault file, not JSON, not a whole-file vault or a list. */
    private fun appendable(project: Project, file: VirtualFile): Boolean {
        if (ValueSummary.isVaultFileName(file.name) || file.extension.equals(JSON, ignoreCase = true)) return false
        return when (VarsDocuments.load(project, file)) {
            is YMap -> true
            null -> {
                val psi = PsiManager.getInstance(project).findFile(file)
                psi != null && psi !is PsiBinaryFile && psi.viewProvider.contents.lineSequence().all { line ->
                    val trimmed = line.trim()
                    trimmed.isEmpty() || trimmed == DOCUMENT_START || trimmed.startsWith("#")
                }
            }
            else -> false
        }
    }

    /** The role's own winning default of [name], or null when no loaded defaults file sets it. */
    fun of(project: Project, roleDir: VirtualFile, name: String, hashBehaviour: HashBehaviour = HashBehaviour.REPLACE): RoleDefault? =
        scan(project, roleDir, hashBehaviour).defaults[name]

    /** Every own default of [roleDir] (the winners), and which loaded files could not be read as a mapping. */
    fun scan(project: Project, roleDir: VirtualFile, hashBehaviour: HashBehaviour = HashBehaviour.REPLACE): Scan {
        val defaults = LinkedHashMap<String, RoleDefault>()
        val dictionaries = HashMap<String, MutableSet<VirtualFile>>()
        val files = loadedFiles(roleDir)
        val opaque = ArrayList<Int>()
        for ((index, file) in files.withIndex()) {
            ProgressManager.checkCanceled()
            val document = VarsDocuments.load(project, file) ?: continue
            if (document !is YMap) {
                opaque += index
                continue
            }
            for (entry in document.entries) {
                val name = entry.key.text
                if (entry.value is YMap) dictionaries.getOrPut(name) { HashSet() } += file
                val merged = hashBehaviour == HashBehaviour.MERGE && entry.value is YMap && dictionaries.getValue(name).size > 1
                defaults.remove(name) // keep the map in the order of the winning definitions
                defaults[name] = RoleDefault(name, roleDir, file, entry.key.range?.start ?: 0, entry.key.range, entry.value, merged)
            }
        }
        return Scan(defaults, files, opaque)
    }

    /**
     * The default of [name] a task of [role] sees from role defaults: the role's own ([Lookup.Own]), else that of the
     * dependency applied last that sets it ([Lookup.Dependency], in [dependencies]' order), else [Lookup.None]. A found
     * default is [Lookup.Found.uncertain] when an unreadable file (a whole-file vault) is applied after it and may
     * override it; [Lookup.None.opaque] when any loaded defaults file could not be read, so the absence proves nothing.
     */
    fun lookup(project: Project, root: AnsibleRoot, role: RoleInfo, name: String): Lookup {
        val hash = VarsConfig.of(root).hashBehaviour
        val own = scan(project, role.ref.dir, hash)
        own.defaults[name]?.let { return Lookup.Own(it, own.mayBeOverridden(it)) }
        var opaque = own.opaque
        var found: RoleDefault? = null
        var uncertain = false
        for (dependency in dependencies(project, root, role)) {
            val scan = scan(project, dependency.ref.dir, hash)
            opaque = opaque || scan.opaque
            val hit = scan.defaults[name]
            if (hit != null) {
                found = hit
                uncertain = scan.mayBeOverridden(hit)
            } else if (scan.opaque) {
                uncertain = true
            }
        }
        // The role's own files are applied after every dependency.
        return found?.let { Lookup.Dependency(it, uncertain || own.opaque) } ?: Lookup.None(opaque)
    }

    /**
     * The `meta/main.yml` dependencies of [role], transitively, in the order `Role.get_default_vars` applies their
     * defaults, each role at its last application. Ansible applies a shared dependency again wherever it recurs
     * (`get_all_dependencies` keeps repeats, and each dependency's `get_default_vars` re-applies its own dependencies),
     * so for `app` → `a`, `b` with both depending on `c`, the order is `a`, `c`, `b`: `c`'s defaults beat `a`'s. An
     * earlier application of a role is always overridden by its later one, so the last occurrences decide.
     */
    fun dependencies(project: Project, root: AnsibleRoot, role: RoleInfo): List<RoleInfo> {
        val registry = RoleRegistry.getInstance(project)
        val infos = HashMap<VirtualFile, RoleInfo>()
        val allMemo = HashMap<VirtualFile, List<VirtualFile>>()
        val appliedMemo = HashMap<VirtualFile, List<VirtualFile>>()
        val inAll = HashSet<VirtualFile>()
        val inApplied = HashSet<VirtualFile>()

        // `get_all_dependencies`: each direct dependency's own list, then the dependency (last occurrences only).
        fun all(info: RoleInfo, depth: Int): List<VirtualFile> {
            val dir = info.ref.dir
            allMemo[dir]?.let { return it }
            if (depth > MAX_DEPENDENCY_DEPTH || !inAll.add(dir)) return emptyList()
            // The dependencies come from `meta/main.yml`: an input of a model cache computing this.
            RoleLayout.metaFile(dir)?.let { ModelInputs.file(project, it) }
            val list = ArrayList<VirtualFile>()
            for (written in info.metaDependencies) {
                ProgressManager.checkCanceled()
                val dependency = registry.role(root, written) ?: continue
                infos[dependency.ref.dir] = dependency
                list += all(dependency, depth + 1)
                list += dependency.ref.dir
            }
            inAll.remove(dir)
            return lastOccurrences(list).also { allMemo[dir] = it }
        }

        // `get_default_vars`: every dependency's own `get_default_vars()` in `get_all_dependencies` order, then the role.
        fun applied(info: RoleInfo, depth: Int): List<VirtualFile> {
            val dir = info.ref.dir
            appliedMemo[dir]?.let { return it }
            if (depth > MAX_DEPENDENCY_DEPTH || !inApplied.add(dir)) return listOf(dir)
            val list = ArrayList<VirtualFile>()
            for (dependency in all(info, 0)) {
                ProgressManager.checkCanceled()
                list += applied(infos.getValue(dependency), depth + 1)
            }
            list += dir
            inApplied.remove(dir)
            return lastOccurrences(list).also { appliedMemo[dir] = it }
        }

        return applied(role, 0).filter { it != role.ref.dir }.mapNotNull(infos::get)
    }

    /** [list] with each element at its last position only. */
    private fun <T> lastOccurrences(list: List<T>): List<T> {
        val seen = HashSet<T>()
        return list.asReversed().filter(seen::add).asReversed()
    }

    /** The winners of one role's own defaults [files] (in load order); [opaqueIndexes] are the files that are not a mapping. */
    class Scan(val defaults: Map<String, RoleDefault>, val files: List<VirtualFile>, private val opaqueIndexes: List<Int>) {
        /** Whether a loaded file could not be read as a mapping (a whole-file vault). */
        val opaque: Boolean get() = opaqueIndexes.isNotEmpty()

        /** Whether an unreadable file (a whole-file vault) loads after [default]'s file, so it may override it. */
        fun mayBeOverridden(default: RoleDefault): Boolean {
            val index = files.indexOf(default.file)
            return opaqueIndexes.any { it > index }
        }
    }

    /** Where a role variable's default comes from. */
    sealed interface Lookup {
        /** A default was found; [uncertain] when an unreadable defaults file applied after it may override it. */
        sealed interface Found : Lookup {
            val default: RoleDefault
            val uncertain: Boolean
        }

        class Own(override val default: RoleDefault, override val uncertain: Boolean = false) : Found

        class Dependency(override val default: RoleDefault, override val uncertain: Boolean = false) : Found

        class None(val opaque: Boolean) : Lookup
    }

    /** Where a new role default goes ([appendTarget]). */
    sealed interface AppendTarget {
        /** Append to this loaded defaults file. */
        class Existing(val file: VirtualFile) : AppendTarget

        /** The role loads no defaults file: create `defaults/main.yml`. */
        data object Create : AppendTarget

        /** No loaded defaults file can take a YAML line (vault, JSON or not a mapping): no fix is offered. */
        data object None : AppendTarget
    }
}

/**
 * One winning top-level key of a role's loaded defaults files: [value] as ansible-core loads it, written in [file] with
 * its key at [keyOffset] ([location], the `VarDefinition` convention).
 */
class RoleDefault(
    val name: String,
    val roleDir: VirtualFile,
    val file: VirtualFile,
    val keyOffset: Int,
    val keyRange: SourceRange?,
    val value: YValue,
    /** `hash_behaviour = merge` and several loaded files set a dictionary: the value Ansible uses is their merge. */
    val merged: Boolean,
) {
    val location: SourceLocation get() = SourceLocation(file, keyOffset)

    /** Never shown or compared: a `vault_*` name, a vault file (`vault.yml` …) or a `!vault` value anywhere in it. */
    val isSecret: Boolean
        get() = ValueSummary.isSecret(name, value) || ValueSummary.isVaultFileName(file.name) || SpecDefaults.containsVault(value)

    override fun toString(): String = "RoleDefault($name @ ${file.name}:$keyOffset)"
}
