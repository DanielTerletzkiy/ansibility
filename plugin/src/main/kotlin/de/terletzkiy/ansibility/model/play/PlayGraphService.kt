package de.terletzkiy.ansibility.model.play

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileVisitor
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.FileKind
import de.terletzkiy.ansibility.api.PlayGraph
import de.terletzkiy.ansibility.api.PlayInfo
import de.terletzkiy.ansibility.api.PlayRef
import de.terletzkiy.ansibility.api.PlayRoleEntry
import de.terletzkiy.ansibility.api.PlaySection
import de.terletzkiy.ansibility.api.PlaybookImport
import de.terletzkiy.ansibility.api.RoleEntryKind
import de.terletzkiy.ansibility.api.RoleRef
import de.terletzkiy.ansibility.api.SourceLocation
import de.terletzkiy.ansibility.api.VarsFileRef
import de.terletzkiy.ansibility.context.AnsibleLayout
import de.terletzkiy.ansibility.model.container.ContainerPathMapper
import de.terletzkiy.ansibility.model.inventory.ModelCache
import de.terletzkiy.ansibility.model.inventory.ModelInputs
import de.terletzkiy.ansibility.model.role.RoleLayout
import de.terletzkiy.ansibility.model.role.RoleMeta
import de.terletzkiy.ansibility.model.task.BlockNode
import de.terletzkiy.ansibility.model.task.IncludeKind
import de.terletzkiy.ansibility.model.task.PlayNode
import de.terletzkiy.ansibility.model.task.RoleIncludeCall
import de.terletzkiy.ansibility.model.task.TaskFileKind
import de.terletzkiy.ansibility.model.task.TaskFileModels
import de.terletzkiy.ansibility.model.task.TaskItem
import de.terletzkiy.ansibility.model.task.TaskNode
import de.terletzkiy.ansibility.model.task.YamlFiles
import de.terletzkiy.ansibility.semantics.yaml.YMap

/**
 * The project's [PlayGraph] (plan A.5 `PlayGraph`, A.8 `PlayGraphService`).
 *
 * - **Plays** come from the task model of each playbook ([TaskFileModels], read as a playbook), with the `vars:`
 *   mapping as loaded ([PlayInfo.vars], the L12 source of the ExecutionView).
 * - **Playbook dir per play** is the directory of the defining file. Roles of a play resolve the way
 *   ansible-core's `RoleDefinition` searches: `<playbook dir>/roles`, the root's roles dirs (which carry the
 *   `ansible.cfg` `roles_path`), for dependencies the dependent role's roles dir, then the playbook dir itself.
 *   Absolute paths go through [ContainerPathMapper] (`/ansible/roles/haproxy` in molecule).
 * - **`meta/main.yml` dependencies** expand into `main` entries before the dependent role, depth first, cycles cut.
 * - **`import_playbook`** resolves relative to the importing file; [executionOrder] follows it with a recursion
 *   guard, and imported plays keep their own file and playbook dir.
 *
 * **Caching is per playbook** (plan A.9; plan amendment R7/R8, A.9 change 2), in [ModelCache]s held by the service, not
 * by the PSI, so the PSI of the many playbooks nobody has open may be collected without losing them:
 * - the graph of one file depends on the content stamps of every file it was read from: the playbook itself, the
 *   `meta/main.yml` files whose dependencies it expanded and the compose files behind container paths it mapped. An
 *   edit of one of them rebuilds that graph only; typing in a role's tasks or vars files rebuilds nothing;
 * - the playbook set of a root depends on the saved content of every YAML file the walk classified (the playbook probe
 *   reads saved content only);
 * - [playsApplying] results depend on the playbook sets and graphs they were computed from;
 * - everything depends on the layout stamp ([de.terletzkiy.ansibility.model.inventory.ModelCaches.layout]): the file
 *   tree below the project content (a file or directory created, deleted, moved, copied or renamed outside tool
 *   directories such as `.git/`), the Ansible structure, the project roots, the Ansibility settings and the target
 *   versions.
 *
 * Results are the same objects while they are cached ([playsOf], [playbooks], [playsApplying]), so callers may compare
 * them by identity. Methods may be called from any thread; they take a read lock when the caller holds none.
 */
class PlayGraphService(private val project: Project) : PlayGraph {
    private data class ApplyingKey(val root: AnsibleRoot, val roleName: String, val entryPoint: String?)

    private val playbookSets = ModelCache<AnsibleRoot, List<VirtualFile>>(project, "plays.playbooks")
    private val graphs = ModelCache<VirtualFile, FileGraph>(project, "plays.graph")
    private val applying = ModelCache<ApplyingKey, List<PlayRef>>(project, "plays.applying")

    /** The top-level items of one playbook in file order. */
    private class FileGraph(val items: List<Item>) {
        val plays: List<PlayInfo> = items.filterIsInstance<Item.Play>().map { it.info }
        val imports: List<PlaybookImport> = items.filterIsInstance<Item.Import>().map { it.edge }
    }

    private sealed interface Item {
        class Play(val info: PlayInfo) : Item
        class Import(val edge: PlaybookImport) : Item
    }

    override fun playbooks(root: AnsibleRoot): List<VirtualFile> = readLocked { playbooksOf(root) }

    override fun playsOf(playbook: VirtualFile): List<PlayInfo> = readLocked { graphOf(playbook).plays }

    override fun imports(playbook: VirtualFile): List<PlaybookImport> = readLocked { graphOf(playbook).imports }

    override fun executionOrder(playbook: VirtualFile): List<PlayRef> = readLocked {
        val result = ArrayList<PlayRef>()
        collectOrder(playbook, ArrayDeque(), result)
        result
    }

    override fun playsApplying(root: AnsibleRoot, roleName: String, entryPoint: String?): List<PlayRef> = readLocked {
        applying.get(ApplyingKey(root, roleName, entryPoint)) { computeApplying(root, roleName, entryPoint) }
    }

    private fun computeApplying(root: AnsibleRoot, roleName: String, entryPoint: String?): List<PlayRef> {
        val files = LinkedHashSet<VirtualFile>()
        familyOf(root).forEach { files += playbooksOf(it) }
        val queue = ArrayDeque(files)
        while (queue.isNotEmpty()) {
            ProgressManager.checkCanceled()
            graphOf(queue.removeFirst()).imports.forEach { edge -> edge.target?.let { if (files.add(it)) queue += it } }
        }
        return files.flatMap { file ->
            graphOf(file).plays.filter { play ->
                play.roles.any { entry ->
                    val role = entry.role
                    role != null && role.rootDir == root.dir && role.name == roleName && (entryPoint == null || entry.entryPoint == entryPoint)
                }
            }.map { it.ref }
        }.sortedWith(compareBy({ it.file.path }, { it.playIndex }))
    }

    override fun rolesOfPlay(play: PlayRef): List<PlayRoleEntry> = play(play)?.roles.orEmpty()

    override fun play(play: PlayRef): PlayInfo? = readLocked {
        if (!play.file.isValid) return@readLocked null
        graphOf(play.file).plays.getOrNull(play.playIndex)
    }

    // ------------------------------------------------------------------------------------------------ playbooks

    private fun playbooksOf(root: AnsibleRoot): List<VirtualFile> = playbookSets.get(root) { findPlaybooks(root) }

    /**
     * The files of [root] classified as `PLAYBOOK` or `MOLECULE_PLAYBOOK`. The walk skips nested roots, tool and
     * hidden directories, inventory and vars trees, and everything of a role except its `molecule/` directory. Every
     * YAML file it classifies is an input by its saved content: saved with other content, it may have become, or
     * stopped being, a playbook.
     */
    private fun findPlaybooks(root: AnsibleRoot): List<VirtualFile> {
        val workspace = AnsibleWorkspace.getInstance(project)
        val otherRoots = workspace.roots().mapTo(HashSet()) { it.dir } - root.dir
        val result = ArrayList<VirtualFile>()
        VfsUtilCore.visitChildrenRecursively(
            root.dir,
            object : VirtualFileVisitor<Unit>() {
                override fun visitFile(file: VirtualFile): Boolean {
                    ProgressManager.checkCanceled()
                    if (file.isDirectory) return file == root.dir || descend(root, file, otherRoots)
                    if (AnsibleLayout.isYamlName(file.name)) {
                        ModelInputs.savedFile(file)
                        val context = workspace.contextOf(file)
                        if (context != null && context.root.dir == root.dir && context.kind in PLAYBOOK_KINDS) result += file
                    }
                    return true
                }
            },
        )
        return result.sortedBy { it.path }
    }

    private fun descend(root: AnsibleRoot, dir: VirtualFile, otherRoots: Set<VirtualFile>): Boolean {
        val name = dir.name
        if (dir in otherRoots || name.startsWith(".") || name in AnsibleLayout.SKIPPED_DIRS) return false
        val rolesDir = root.rolesDirs.firstOrNull { VfsUtilCore.isAncestor(it, dir, false) }
        if (rolesDir != null) {
            val segments = VfsUtilCore.getRelativePath(dir, rolesDir)?.split('/')?.filter { it.isNotEmpty() } ?: return false
            return segments.size <= 1 || segments[1] == AnsibleLayout.MOLECULE
        }
        return name == AnsibleLayout.MOLECULE || name !in AnsibleLayout.ROOT_CONTENT_DIRS
    }

    /** [root] plus the roots whose plays can reach its roles: nested playbook roots inside it and its parent. */
    private fun familyOf(root: AnsibleRoot): List<AnsibleRoot> {
        val roots = AnsibleWorkspace.getInstance(project).roots()
        return listOf(root) + roots.filter { it != root && (it.parentDir == root.dir || it.dir == root.parentDir) }
    }

    private fun collectOrder(file: VirtualFile, stack: ArrayDeque<VirtualFile>, out: MutableList<PlayRef>) {
        if (file in stack || stack.size >= MAX_IMPORT_DEPTH) return
        stack.addLast(file)
        for (item in graphOf(file).items) {
            ProgressManager.checkCanceled()
            when (item) {
                is Item.Play -> out += item.info.ref
                is Item.Import -> item.edge.target?.let { collectOrder(it, stack, out) }
            }
        }
        stack.removeLast()
    }

    // ------------------------------------------------------------------------------------------------ one file

    /** The graph of one playbook, kept until one of the files it was built from changes. */
    private fun graphOf(file: VirtualFile): FileGraph {
        if (!file.isValid || file.isDirectory) return EMPTY_GRAPH
        return graphs.get(file) { buildGraph(file) }
    }

    private fun buildGraph(file: VirtualFile): FileGraph {
        ModelInputs.file(project, file)
        val yaml = YamlFiles.yamlFile(project, file) ?: return EMPTY_GRAPH
        val playbookDir = file.parent ?: return EMPTY_GRAPH
        val model = TaskFileModels.of(yaml, TaskFileKind.PLAYBOOK)
        val root = AnsibleWorkspace.getInstance(project).rootFor(file)
        val items = ArrayList<Pair<Int, Item>>()
        for (play in model.plays) items += play.itemIndex to Item.Play(PlayBuilder(file, playbookDir, root).build(play))
        for (node in model.imports) {
            val path = node.path ?: continue
            val edge = PlaybookImport(file, path.text, resolveFile(playbookDir, path.text, file), SourceLocation(file, path.range.startOffset))
            items += node.itemIndex to Item.Import(edge)
        }
        return FileGraph(items.sortedBy { it.first }.map { it.second })
    }

    /**
     * Builds the [PlayInfo] of one play of [file]; every file it reads besides [file] (`meta/main.yml` files, compose
     * files) becomes an input of the graph being computed ([ModelInputs]).
     */
    private inner class PlayBuilder(val file: VirtualFile, val playbookDir: VirtualFile, val root: AnsibleRoot?) {
        private val entries = ArrayList<PlayRoleEntry>()

        fun build(play: PlayNode): PlayInfo {
            val ref = PlayRef(file, play.index, play.name?.text, play.hosts?.pattern, playbookDir)
            includes(PlaySection.PRE_TASKS, play.preTasks)
            for (role in play.roles) {
                val name = role.name ?: continue
                val dir = locateRole(name.text, file, dependentRoleDir = null)
                if (dir != null) expandDependencies(dir, PlaySection.ROLES, linkedSetOf(dir))
                entries += PlayRoleEntry(
                    name = RoleMeta.nameOf(name.text),
                    written = name.text,
                    role = dir?.let(::refOf),
                    entryPoint = RoleLayout.MAIN,
                    handlersFrom = null,
                    tags = role.tags.map { it.text },
                    kind = RoleEntryKind.PLAY_ROLE,
                    section = PlaySection.ROLES,
                    location = SourceLocation(file, name.range.startOffset),
                )
            }
            includes(PlaySection.TASKS, play.tasks)
            includes(PlaySection.POST_TASKS, play.postTasks)
            includes(PlaySection.HANDLERS, play.handlers)
            return PlayInfo(
                ref = ref,
                location = SourceLocation(file, play.range.startOffset),
                varsKeys = play.vars.map { it.text },
                varsFiles = play.varsFiles.map { VarsFileRef(it.text, resolveFile(playbookDir, it.text, file), SourceLocation(file, it.range.startOffset)) },
                roles = entries.toList(),
                vars = play.keywords[VARS]?.value as? YMap,
            )
        }

        /** `include_role`/`import_role` tasks of a section, blocks included. */
        private fun includes(section: PlaySection, items: List<TaskItem>) {
            for (item in items) {
                ProgressManager.checkCanceled()
                when (item) {
                    is BlockNode -> {
                        includes(section, item.block)
                        includes(section, item.rescue)
                        includes(section, item.always)
                    }
                    is TaskNode -> item.roleInclude?.let { include(section, item, it) }
                }
            }
        }

        private fun include(section: PlaySection, task: TaskNode, call: RoleIncludeCall) {
            val name = call.name ?: return
            val dir = locateRole(name.text, file, dependentRoleDir = null)
            if (dir != null) expandDependencies(dir, section, linkedSetOf(dir))
            entries += PlayRoleEntry(
                name = RoleMeta.nameOf(name.text),
                written = name.text,
                role = dir?.let(::refOf),
                entryPoint = call.tasksFrom?.text ?: RoleLayout.MAIN,
                handlersFrom = call.handlersFrom?.text,
                tags = task.tags.map { it.text },
                kind = if (call.kind == IncludeKind.IMPORT) RoleEntryKind.IMPORT_ROLE else RoleEntryKind.INCLUDE_ROLE,
                section = section,
                location = SourceLocation(file, name.range.startOffset),
            )
        }

        /** Adds the dependencies of [roleDir] (theirs first), each before its dependent; [chain] cuts cycles. */
        private fun expandDependencies(roleDir: VirtualFile, section: PlaySection, chain: LinkedHashSet<VirtualFile>) {
            if (chain.size > MAX_DEPENDENCY_DEPTH) return
            val meta = RoleLayout.metaFile(roleDir) ?: return
            ModelInputs.file(project, meta)
            val yaml = YamlFiles.yamlFile(project, meta) ?: return
            for (dependency in RoleMeta.dependencies(yaml)) {
                ProgressManager.checkCanceled()
                val dir = locateRole(dependency.written, meta, dependentRoleDir = roleDir)
                if (dir != null) {
                    if (dir in chain) continue
                    chain += dir
                    expandDependencies(dir, section, chain)
                    chain -= dir
                }
                entries += PlayRoleEntry(
                    name = dependency.name,
                    written = dependency.written,
                    role = dir?.let(::refOf),
                    entryPoint = RoleLayout.MAIN,
                    handlersFrom = null,
                    tags = dependency.tags,
                    kind = RoleEntryKind.DEPENDENCY,
                    section = section,
                    requiredBy = roleDir.name,
                    location = dependency.range?.let { SourceLocation(meta, it.startOffset) },
                )
            }
        }

        /**
         * The role directory [written] names, searched like ansible-core's `RoleDefinition._load_role_path`;
         * [contextFile] is where the reference is written (for container paths).
         */
        private fun locateRole(written: String, contextFile: VirtualFile, dependentRoleDir: VirtualFile?): VirtualFile? {
            if (isTemplated(written)) return null
            if (written.startsWith("/")) return mapContainerPath(written, contextFile)?.takeIf { it.isDirectory }
            val searchPath = listOfNotNull(playbookDir.findChild(AnsibleLayout.ROLES)) +
                root?.rolesDirs.orEmpty() +
                listOfNotNull(dependentRoleDir?.parent) +
                playbookDir
            return searchPath.distinct().firstNotNullOfOrNull { dir ->
                dir.findFileByRelativePath(written.trimEnd('/'))?.takeIf { it.isDirectory }
            }
        }

        /** The role of [dir]: owned by the play's root when it lies in one of its roles dirs, else by its own root. */
        private fun refOf(dir: VirtualFile): RoleRef {
            val owner = root?.takeIf { dir.parent in it.rolesDirs } ?: AnsibleWorkspace.getInstance(project).rootFor(dir)
            return RoleRef(owner?.dir ?: dir.parent ?: dir, dir.name, dir)
        }
    }

    /** A file path of `import_playbook` or `vars_files`, relative to [baseDir]; absolute paths map from the container. */
    private fun resolveFile(baseDir: VirtualFile, path: String, contextFile: VirtualFile): VirtualFile? {
        if (isTemplated(path)) return null
        val file = if (path.startsWith("/")) mapContainerPath(path, contextFile) else baseDir.findFileByRelativePath(path)
        return file?.takeIf { it.isValid && !it.isDirectory }
    }

    /**
     * The project file a container [path] seen from [contextFile] maps to ([ContainerPathMapper]); the compose files
     * behind the mappings become inputs (their volumes may change without a structure change while unsaved).
     */
    private fun mapContainerPath(path: String, contextFile: VirtualFile): VirtualFile? {
        val mapper = ContainerPathMapper.getInstance(project)
        for (mapping in mapper.mappings(contextFile)) mapping.composeFile?.let { ModelInputs.file(project, it) }
        return mapper.map(path, contextFile) ?: mapper.findProjectFile(path)
    }

    private fun <T> readLocked(action: () -> T): T =
        if (ApplicationManager.getApplication().isReadAccessAllowed) action() else runReadActionBlocking(action)

    private companion object {
        const val VARS = "vars"
        val PLAYBOOK_KINDS = setOf(FileKind.PLAYBOOK, FileKind.MOLECULE_PLAYBOOK)
        val EMPTY_GRAPH = FileGraph(emptyList())
        const val MAX_IMPORT_DEPTH = 32
        const val MAX_DEPENDENCY_DEPTH = 16

        fun isTemplated(text: String): Boolean = "{{" in text || "{%" in text
    }
}
