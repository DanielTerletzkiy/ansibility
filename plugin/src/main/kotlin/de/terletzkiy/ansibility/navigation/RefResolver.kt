package de.terletzkiy.ansibility.navigation

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.FileContext
import de.terletzkiy.ansibility.api.FileKind
import de.terletzkiy.ansibility.api.PlayGraph
import de.terletzkiy.ansibility.api.RoleRegistry
import de.terletzkiy.ansibility.context.AnsibleLayout
import de.terletzkiy.ansibility.index.AnsibleIndexQueries
import de.terletzkiy.ansibility.model.task.BlockNode
import de.terletzkiy.ansibility.model.task.NameRef
import de.terletzkiy.ansibility.model.task.TaskFileKind
import de.terletzkiy.ansibility.model.task.TaskFileModels
import de.terletzkiy.ansibility.model.task.TaskItem
import de.terletzkiy.ansibility.model.task.TaskNode
import de.terletzkiy.ansibility.model.task.YamlFiles

/** Whether a reference names something that exists. */
internal enum class ResolutionStatus {
    RESOLVED,

    /** Certainly missing: ansible-core fails on it (ANS-R001). */
    UNRESOLVED,

    /** The project cannot tell (templated, outside the project, an open search path, or never an error). */
    UNKNOWN,
}

/** A navigation target: a file or directory, or a position in a file with a chooser label. */
internal data class RefTarget(
    val file: VirtualFile,
    /** The position to open; null for the file (or directory) itself. */
    val offset: Int? = null,
    /** The name shown in the chooser (a handler name). */
    val name: String? = null,
    /** The role that owns the target. */
    val owner: String? = null,
    /** What the target is, as the chooser shows it (`cross-role (play scope)`). */
    val label: String? = null,
)

/** The targets of a reference and its status; [suggestions] are the existing names for "Change to …", nearest style. */
internal class Resolution(
    val targets: List<RefTarget>,
    val status: ResolutionStatus,
    suggestions: () -> List<String> = { emptyList() },
) {
    val suggestions: List<String> by lazy(suggestions)

    companion object {
        val UNKNOWN: Resolution = Resolution(emptyList(), ResolutionStatus.UNKNOWN)
    }
}

/**
 * Resolves the [RefOccurrence]s of one file within its root (plan F1.8, DEV.md rule 6), the way ansible-core looks
 * them up:
 * - `include_tasks`/`import_tasks` → `<role>/tasks/<file>` (`handlers/` in handler files), `<role>/<file>`, then the
 *   playbook dirs; outside roles relative to the file; the name must match exactly (navigation also tries `.yml`);
 * - `tasks_from`/`handlers_from`/`vars_from`/`defaults_from` → the named role's subdirectory, the name with `''`,
 *   `.yml`, `.yaml` or `.json` (`RoleDefinition._load_role_yaml`); no error when the subdirectory does not exist;
 * - roles ([RoleLocator]); `template`/`copy` `src` ([NeedleSearch] over `templates/` or `files/`), every candidate of a
 *   dynamic prefix; absolute paths through [AbsolutePath];
 * - `notify` ([HandlerScope]), `listen` → the tasks that notify the topic;
 * - `vars_files` and `import_playbook` relative to the playbook; template-name values through their [TemplateNameSlot];
 *   `{% include %}` relative to the role's `templates/`, the role and the template's directory.
 *
 * Call inside a read action; loops check for cancellation.
 */
internal class RefResolver(private val file: PsiFile, private val context: FileContext) {
    private val project = file.project
    private val root = context.root
    /** The file the references are written in (the original of a completion copy). */
    val virtualFile: VirtualFile = file.originalFile.viewProvider.virtualFile

    /** Its directory. */
    val fileDir: VirtualFile? = virtualFile.parent

    /** The role a task or handler file belongs to (not molecule files, which are playbooks or their task files). */
    private val ownRoleDir: VirtualFile? =
        context.roleDir?.takeIf { context.kind == FileKind.ROLE_TASKS || context.kind == FileKind.ROLE_HANDLERS }

    /** The playbook directories a file's tasks run from: its own for playbooks, those of the plays applying its role. */
    private val playbookDirs: List<VirtualFile> by lazy {
        when (context.kind) {
            FileKind.PLAYBOOK, FileKind.MOLECULE_PLAYBOOK -> listOfNotNull(fileDir)
            FileKind.MOLECULE_TASKS -> listOfNotNull(context.moleculeScenarioDir ?: fileDir)
            else -> context.roleDir?.let { dir ->
                PlayGraph.getInstance(project).playsApplying(root, dir.name).map { it.playbookDir }.distinct()
            }.orEmpty()
        }
    }

    fun resolve(occurrence: RefOccurrence): Resolution {
        ProgressManager.checkCanceled()
        return when (occurrence.kind) {
            RefKind.TASK_INCLUDE -> taskInclude(occurrence)
            RefKind.TASKS_FROM -> roleFile(occurrence, SUBDIR_TASKS, acceptDirectory = false)
            RefKind.HANDLERS_FROM -> roleFile(occurrence, SUBDIR_HANDLERS, acceptDirectory = false)
            RefKind.VARS_FROM -> roleFile(occurrence, SUBDIR_VARS, acceptDirectory = true)
            RefKind.DEFAULTS_FROM -> roleFile(occurrence, SUBDIR_DEFAULTS, acceptDirectory = true)
            RefKind.ROLE -> role(occurrence)
            RefKind.TEMPLATE_SRC -> source(occurrence, SUBDIR_TEMPLATES, acceptDirectory = false)
            RefKind.COPY_SRC -> source(occurrence, SUBDIR_FILES, acceptDirectory = true)
            RefKind.NOTIFY -> notify(occurrence)
            RefKind.LISTEN -> listen(occurrence)
            RefKind.VARS_FILE, RefKind.IMPORT_PLAYBOOK -> playbookRelative(occurrence)
            RefKind.TEMPLATE_NAME -> templateName(occurrence)
            RefKind.TEMPLATE_INCLUDE -> templateInclude(occurrence)
        }
    }

    /**
     * Existing names for the value of [occurrence], in the value's style: what completion offers and what "Change to …"
     * picks from (task files, `*_from` files, roles, sources, handlers, playbook-relative files, templates).
     */
    fun suggestions(occurrence: RefOccurrence): List<String> = when (occurrence.kind) {
        RefKind.TASK_INCLUDE -> taskFileNames()
        RefKind.VARS_FILE, RefKind.IMPORT_PLAYBOOK -> playbookFileNames()
        RefKind.TEMPLATE_INCLUDE -> templateFileNames()
        else -> resolve(occurrence).suggestions
    }

    /** The role directory [written] names from this file, as the reference at [site] is looked up. */
    fun locateRole(written: String, site: RoleSite): RoleLocator.Result {
        val playbooks = when (context.kind) {
            FileKind.PLAYBOOK, FileKind.MOLECULE_PLAYBOOK -> listOfNotNull(fileDir)
            else -> playbookDirs
        }
        val dependent = when (site) {
            RoleSite.DEPENDENCY -> context.roleDir
            RoleSite.INCLUDE_ROLE -> ownRoleDir
            RoleSite.PLAY_ROLE -> null
        }
        return RoleLocator.locate(project, written, virtualFile, root, playbooks, dependent)
    }

    // ------------------------------------------------------------------------------------------------ task files

    private fun taskInclude(occurrence: RefOccurrence): Resolution {
        val target = occurrence.text
        if (target.startsWith("/") || target.startsWith("~")) return absolute(occurrence, acceptDirectory = false)
        val role = ownRoleDir
        val subdir = if (context.kind == FileKind.ROLE_HANDLERS) SUBDIR_HANDLERS else SUBDIR_TASKS
        val candidates = if (role != null) {
            listOf(Candidate(role, "$subdir/$target"), Candidate(role, target)) +
                playbookDirs.flatMap { listOf(Candidate(it, "$subdir/$target"), Candidate(it, target)) }
        } else {
            listOfNotNull(fileDir).map { Candidate(it, target) } + playbookDirs.map { Candidate(it, target) }
        }
        val suggestions = ::taskFileNames
        NeedleSearch.resolve(candidates, acceptDirectory = false)?.let { return Resolution(listOf(RefTarget(it)), ResolutionStatus.RESOLVED, suggestions) }
        // ansible-core adds no extension to include_tasks; navigation still finds `x.yml` for `x`.
        val lenient = YAML_EXTENSIONS.firstNotNullOfOrNull { ext -> NeedleSearch.resolve(candidates.map { it.copy(relative = it.relative + ext) }, false) }
        return Resolution(listOfNotNull(lenient?.let(::RefTarget)), ResolutionStatus.UNRESOLVED, suggestions)
    }

    /** The YAML files of the own role's `tasks/` (`handlers/` in handler files), else of the file's directory; not the file itself. */
    private fun taskFileNames(): List<String> {
        val subdir = if (context.kind == FileKind.ROLE_HANDLERS) SUBDIR_HANDLERS else SUBDIR_TASKS
        val dir = ownRoleDir?.findChild(subdir) ?: fileDir ?: return emptyList()
        return FileListing.relativeFiles(dir, AnsibleLayout::isYamlName).filter { dir != fileDir || it != virtualFile.name }
    }

    private fun roleFile(occurrence: RefOccurrence, subdir: String, acceptDirectory: Boolean): Resolution {
        val written = occurrence.role ?: return Resolution.UNKNOWN
        val roleDir = (locateRole(written, RoleSite.INCLUDE_ROLE) as? RoleLocator.Result.Found)?.dir ?: return Resolution.UNKNOWN
        // `_load_role_yaml` raises only when the subdirectory exists and holds no match.
        val dir = roleDir.findChild(subdir)?.takeIf { it.isDirectory } ?: return Resolution.UNKNOWN
        val name = occurrence.text
        val suggestions = {
            val files = FileListing.relativeFiles(dir, { AnsibleLayout.isYamlName(it) || it.endsWith(".json") })
            if ('.' in name.substringAfterLast('/')) files else files.map { AnsibleLayout.stem(it) }.distinct()
        }
        val found = ROLE_FILE_EXTENSIONS.firstNotNullOfOrNull { ext ->
            dir.findFileByRelativePath(name + ext)?.takeIf { it.isValid && (acceptDirectory || !it.isDirectory) }
        }
        return if (found != null) Resolution(listOf(RefTarget(found)), ResolutionStatus.RESOLVED, suggestions)
        else Resolution(emptyList(), ResolutionStatus.UNRESOLVED, suggestions)
    }

    private fun role(occurrence: RefOccurrence): Resolution {
        val site = occurrence.roleSite ?: RoleSite.PLAY_ROLE
        val suggestions = {
            val prefix = if (occurrence.text.contains('/')) occurrence.text.substringBeforeLast('/') + "/" else ""
            RoleRegistry.getInstance(project).roles(root).map { prefix + it.name }
        }
        return when (val result = locateRole(occurrence.text, site)) {
            is RoleLocator.Result.Found -> Resolution(listOf(RefTarget(RoleLocator.entryFile(result.dir))), ResolutionStatus.RESOLVED, suggestions)
            is RoleLocator.Result.Missing ->
                Resolution(emptyList(), if (result.certain) ResolutionStatus.UNRESOLVED else ResolutionStatus.UNKNOWN, suggestions)
            RoleLocator.Result.Unknown -> Resolution.UNKNOWN
        }
    }

    // ------------------------------------------------------------------------------------------------ templates and files

    private fun source(occurrence: RefOccurrence, dirname: String, acceptDirectory: Boolean): Resolution {
        if (occurrence.isDynamic) return dynamicSource(occurrence, dirname)
        val src = occurrence.text
        if (src.startsWith("/") || src.startsWith("~")) return absolute(occurrence, acceptDirectory)
        val candidates = NeedleSearch.candidates(dirname, src, ownRoleDir, fileDir, playbookDirs)
        val suggestions = { sourceNames(dirname, prefixed = src.startsWith("$dirname/")) }
        val found = NeedleSearch.resolve(candidates, acceptDirectory)
        return if (found != null) Resolution(listOf(RefTarget(found)), ResolutionStatus.RESOLVED, suggestions)
        else Resolution(emptyList(), ResolutionStatus.UNRESOLVED, suggestions)
    }

    /** The files of the own role's (else the file directory's) [dirname] directory, written `<dirname>/<path>` when [prefixed]. */
    fun sourceNames(dirname: String, prefixed: Boolean): List<String> {
        val base = (ownRoleDir ?: fileDir)?.findChild(dirname)?.takeIf { it.isDirectory } ?: return emptyList()
        val files = FileListing.relativeFiles(base, { true })
        return if (prefixed) files.map { "$dirname/$it" } else files
    }

    /** Every file a dynamic `src` can name: those below the prefix's directory that start and end right. */
    private fun dynamicSource(occurrence: RefOccurrence, dirname: String): Resolution {
        val prefix = occurrence.dynamicPrefix ?: return Resolution.UNKNOWN
        val close = occurrence.text.indexOf("}}", prefix.length)
        val suffix = if (close < 0) "" else occurrence.text.substring(close + 2).takeUnless(RefOccurrence::isTemplated).orEmpty()
        val targets = dynamicCandidates(dirname, prefix, suffix, ownRoleDir, fileDir, playbookDirs).map { RefTarget(it) }
        return Resolution(targets, ResolutionStatus.UNKNOWN)
    }

    private fun templateName(occurrence: RefOccurrence): Resolution {
        val slot = occurrence.slot ?: return Resolution.UNKNOWN
        val rendererContext = AnsibleWorkspace.getInstance(project).contextOf(slot.renderer)
        // Only a renderer of the same root (the slots are root-scoped already; a changed tree may leave stale ones).
        if (rendererContext == null || rendererContext.root.dir != root.dir) return Resolution.UNKNOWN
        val roleDir = rendererContext.roleDir?.takeIf { rendererContext.kind == FileKind.ROLE_TASKS || rendererContext.kind == FileKind.ROLE_HANDLERS }
        val candidates = NeedleSearch.candidates(SUBDIR_TEMPLATES, slot.sourceFor(occurrence.text), roleDir, slot.renderer.parent)
        val found = NeedleSearch.resolve(candidates, acceptDirectory = false)
        val suggestions = { templateNameValues(slot).map { it.first } }
        return if (found != null) Resolution(listOf(RefTarget(found)), ResolutionStatus.RESOLVED, suggestions)
        else Resolution(emptyList(), ResolutionStatus.UNRESOLVED, suggestions)
    }

    /** The values [slot] can take (file names with the slot's prefix and suffix stripped) and their files. */
    fun templateNameValues(slot: TemplateNameSlot): List<Pair<String, VirtualFile>> {
        val rendererContext = AnsibleWorkspace.getInstance(project).contextOf(slot.renderer) ?: return emptyList()
        if (rendererContext.root.dir != root.dir) return emptyList()
        val roleDir = rendererContext.roleDir?.takeIf { rendererContext.kind == FileKind.ROLE_TASKS || rendererContext.kind == FileKind.ROLE_HANDLERS }
        val directory = slot.prefix.substringBeforeLast('/', "")
        val namePrefix = slot.prefix.substringAfterLast('/')
        val result = LinkedHashMap<String, VirtualFile>()
        for (candidate in NeedleSearch.candidates(SUBDIR_TEMPLATES, directory, roleDir, slot.renderer.parent)) {
            val dir = candidate.find()?.takeIf { it.isDirectory } ?: continue
            for (relative in FileListing.relativeFiles(dir, { true })) {
                if (!relative.startsWith(namePrefix) || !relative.endsWith(slot.suffix)) continue
                val value = relative.removePrefix(namePrefix).removeSuffix(slot.suffix)
                if (value.isNotEmpty()) dir.findFileByRelativePath(relative)?.let { result.putIfAbsent(value, it) }
            }
        }
        return result.map { it.key to it.value }
    }

    private fun templateInclude(occurrence: RefOccurrence): Resolution {
        val name = occurrence.text
        if (name.startsWith("/")) return Resolution.UNKNOWN
        val roleDir = context.roleDir
        val candidates = NeedleSearch.candidates(SUBDIR_TEMPLATES, name, roleDir, fileDir)
        val suggestions = ::templateFileNames
        val found = NeedleSearch.resolve(candidates, acceptDirectory = false)
        return when {
            found != null -> Resolution(listOf(RefTarget(found)), ResolutionStatus.RESOLVED, suggestions)
            occurrence.optional -> Resolution(emptyList(), ResolutionStatus.UNKNOWN, suggestions)
            else -> Resolution(emptyList(), ResolutionStatus.UNRESOLVED, suggestions)
        }
    }

    /** The files of the role's `templates/` (else the template's directory), as `{% include %}` names them. */
    private fun templateFileNames(): List<String> {
        val base = context.roleDir?.findChild(SUBDIR_TEMPLATES) ?: fileDir ?: return emptyList()
        return FileListing.relativeFiles(base, { true })
    }

    // ------------------------------------------------------------------------------------------------ plays

    private fun playbookRelative(occurrence: RefOccurrence): Resolution {
        val path = occurrence.text
        if (path.startsWith("/") || path.startsWith("~")) return absolute(occurrence, acceptDirectory = false)
        // `import_playbook: ns.coll.playbook` names a collection playbook.
        if (occurrence.kind == RefKind.IMPORT_PLAYBOOK && '/' !in path && !AnsibleLayout.isYamlName(path) && path.count { it == '.' } >= 2) {
            return Resolution.UNKNOWN
        }
        val base = fileDir ?: return Resolution.UNKNOWN
        val suggestions = ::playbookFileNames
        val found = base.findFileByRelativePath(path)?.takeIf { it.isValid && !it.isDirectory }
        return if (found != null) Resolution(listOf(RefTarget(found)), ResolutionStatus.RESOLVED, suggestions)
        else Resolution(emptyList(), ResolutionStatus.UNRESOLVED, suggestions)
    }

    /** YAML files near the playbook (three levels deep), for `vars_files` and `import_playbook`; not the file itself. */
    private fun playbookFileNames(): List<String> {
        val base = fileDir ?: return emptyList()
        return FileListing.relativeFiles(base, AnsibleLayout::isYamlName, maxDepth = 3).filter { it != virtualFile.name }
    }

    private fun absolute(occurrence: RefOccurrence, acceptDirectory: Boolean): Resolution {
        if (occurrence.text.startsWith("~")) return Resolution.UNKNOWN
        return when (val path = AbsolutePath.of(project, occurrence.text, virtualFile)) {
            is AbsolutePath.Mapped -> {
                val file = path.file?.takeIf { acceptDirectory || !it.isDirectory }
                if (file != null) Resolution(listOf(RefTarget(file)), ResolutionStatus.RESOLVED) else Resolution(emptyList(), ResolutionStatus.UNRESOLVED)
            }
            AbsolutePath.Unmapped -> Resolution.UNKNOWN
        }
    }

    // ------------------------------------------------------------------------------------------------ handlers

    /** The handler scope of the play [playIndex] (or of the file's role). */
    fun handlerScope(playIndex: Int?): HandlerScope = HandlerScope(project, root, virtualFile, context, playIndex)

    private fun notify(occurrence: RefOccurrence): Resolution {
        val scope = handlerScope(occurrence.playIndex)
        val matches = scope.resolve(occurrence.text)
        val targets = matches.map { match ->
            RefTarget(match.def.file, match.def.offset, match.def.name, match.def.role, tierLabel(match))
        }
        val suggestions = { handlerSuggestions(scope, occurrence.text) }
        val status = when {
            targets.isNotEmpty() -> ResolutionStatus.RESOLVED
            scope.isOpen -> ResolutionStatus.UNKNOWN
            else -> ResolutionStatus.UNRESOLVED
        }
        return Resolution(targets, status, suggestions)
    }

    private fun handlerSuggestions(scope: HandlerScope, text: String): List<String> {
        HandlerScope.qualified(text)?.let { (role, _) ->
            val dir = RoleRegistry.getInstance(project).role(root, role)?.ref?.dir
            if (dir != null) return HandlerDefs.ofRole(project, dir).filter { !it.isTemplated }.map { HandlerScope.qualify(role, it.name) }.distinct()
        }
        val names = LinkedHashSet<String>()
        scope.completionCandidates().forEach { names += it.def.name }
        try {
            AnsibleIndexQueries.handlerNames(project, root).filterTo(names) { !RefOccurrence.isTemplated(it) }
        } catch (_: IndexNotReadyException) {
            // Suggestions from the scope only while indexing.
        }
        return names.toList()
    }

    private fun tierLabel(match: HandlerScope.Match): String {
        val kind = when (match.tier) {
            HandlerScope.Tier.QUALIFIED, HandlerScope.Tier.OWN ->
                if (match.def.role == null) "target.handler.play" else "target.handler"
            HandlerScope.Tier.PLAY -> "target.handler.play.role"
            HandlerScope.Tier.PLAY_SCOPE -> "target.handler.cross.role"
            HandlerScope.Tier.ROOT -> "target.handler.cross.role.root"
        }
        val label = AnsibilityNavigationBundle.message(kind)
        return if (match.def.listen) AnsibilityNavigationBundle.message("target.handler.listen", label) else label
    }

    /** `listen` topic → the tasks that notify it (the own role's task and handler files, or the same playbook). */
    private fun listen(occurrence: RefOccurrence): Resolution {
        val topic = occurrence.text
        val role = ownRoleDir?.name
        val files: List<Pair<VirtualFile, TaskFileKind>> = when {
            ownRoleDir != null -> {
                val info = RoleRegistry.getInstance(project).roleOf(ownRoleDir)
                info?.taskFiles.orEmpty().map { it to TaskFileKind.TASKS } + info?.handlerFiles.orEmpty().map { it to TaskFileKind.HANDLERS }
            }
            context.kind == FileKind.PLAYBOOK || context.kind == FileKind.MOLECULE_PLAYBOOK -> listOf(virtualFile to TaskFileKind.PLAYBOOK)
            else -> emptyList()
        }
        val label = AnsibilityNavigationBundle.message("target.notifier")
        val targets = ArrayList<RefTarget>()
        for ((notifierFile, kind) in files) {
            val yaml = YamlFiles.yamlFile(project, notifierFile) ?: continue
            val model = TaskFileModels.of(yaml, kind)
            val items = model.items + model.plays.flatMap { play -> play.sections().flatten() }
            forEachNotify(items) { ref ->
                val text = ref.text.trim()
                val qualified = HandlerScope.qualified(text)
                if (text == topic || (qualified != null && qualified.first == role && qualified.second == topic)) {
                    targets += RefTarget(notifierFile, ref.range.startOffset, text, role, label)
                }
            }
        }
        return Resolution(targets, if (targets.isEmpty()) ResolutionStatus.UNKNOWN else ResolutionStatus.RESOLVED)
    }

    private fun forEachNotify(items: List<TaskItem>, action: (NameRef) -> Unit) {
        for (item in items) {
            ProgressManager.checkCanceled()
            when (item) {
                is TaskNode -> item.notify.forEach(action)
                is BlockNode -> {
                    item.notify.forEach(action)
                    forEachNotify(item.block, action)
                    forEachNotify(item.rescue, action)
                    forEachNotify(item.always, action)
                }
            }
        }
    }

    companion object {
        const val SUBDIR_TASKS: String = "tasks"
        const val SUBDIR_HANDLERS: String = "handlers"
        const val SUBDIR_VARS: String = "vars"
        const val SUBDIR_DEFAULTS: String = "defaults"
        const val SUBDIR_TEMPLATES: String = "templates"
        const val SUBDIR_FILES: String = "files"

        /** `RoleDefinition._load_role_yaml` with a `*_from` name: the bare name first, then the YAML extensions. */
        val ROLE_FILE_EXTENSIONS: List<String> = listOf("", ".yml", ".yaml", ".json")
        private val YAML_EXTENSIONS = listOf(".yml", ".yaml")

        /** The files a dynamic `src` (static [prefix], an expression, static [suffix]) can name, in search order. */
        fun dynamicCandidates(
            dirname: String,
            prefix: String,
            suffix: String,
            roleDir: VirtualFile?,
            taskDir: VirtualFile?,
            playbookDirs: List<VirtualFile>,
        ): List<VirtualFile> {
            val directory = prefix.substringBeforeLast('/', "")
            val namePrefix = prefix.substringAfterLast('/')
            val result = LinkedHashSet<VirtualFile>()
            for (candidate in NeedleSearch.candidates(dirname, directory, roleDir, taskDir, playbookDirs)) {
                val dir = candidate.find()?.takeIf { it.isDirectory } ?: continue
                for (relative in FileListing.relativeFiles(dir, { true })) {
                    if (relative.startsWith(namePrefix) && relative.endsWith(suffix)) dir.findFileByRelativePath(relative)?.let(result::add)
                }
            }
            return result.toList()
        }
    }
}

/** Bounded, sorted listings of files below a directory (hidden entries and editor backups skipped). */
internal object FileListing {
    private const val MAX_FILES = 500

    /** Paths relative to [dir] of the files whose name [accept]s, depth first, at most [maxDepth] levels deep. */
    fun relativeFiles(dir: VirtualFile, accept: (String) -> Boolean, maxDepth: Int = 6): List<String> {
        val result = ArrayList<String>()
        fun visit(current: VirtualFile, depth: Int) {
            for (child in current.children.orEmpty().sortedBy { it.name }) {
                ProgressManager.checkCanceled()
                if (result.size >= MAX_FILES) return
                if (child.name.startsWith(".") || child.name.endsWith("~")) continue
                if (child.isDirectory) {
                    if (depth < maxDepth) visit(child, depth + 1)
                } else if (accept(child.name)) {
                    VfsUtilCore.getRelativePath(child, dir)?.let(result::add)
                }
            }
        }
        if (dir.isDirectory) visit(dir, 1)
        return result
    }
}
