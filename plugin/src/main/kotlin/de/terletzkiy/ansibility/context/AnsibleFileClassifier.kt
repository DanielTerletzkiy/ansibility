package de.terletzkiy.ansibility.context

import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.FileContext
import de.terletzkiy.ansibility.api.FileKind
import de.terletzkiy.ansibility.api.InventoryDef
import de.terletzkiy.ansibility.api.RootLayout
import de.terletzkiy.ansibility.api.VarsLayer
import de.terletzkiy.ansibility.semantics.inventory.InventoryDirectoryWalk

/**
 * Query-time classification of one file inside a known [AnsibleRoot] (plan A.5).
 *
 * The answer depends on the path below the root, on the root's structure (its roles dirs, whether a role is
 * real), and, only for YAML files whose path is ambiguous, on the [probe] of the file's content. Callers cache
 * results and must re-classify when [Result.usedContentProbe] is set and the file's modification stamp changes.
 *
 * Skipped files (null context): directories, anything inside a `.ansible` directory or below `<root>/patches`,
 * and every file of a directory under `roles/` that is not a role by [RoleDirectories.isRole] (the `role-state`
 * ghosts). The ignored paths of the project settings are applied by the caller (`AnsibleWorkspaceImpl`).
 *
 * [moleculeSupport] is read on every classification: when it answers false, files below a `molecule/` directory
 * are [FileKind.OTHER] (still with their role), so no `MOLECULE_*` kind is ever reported.
 *
 * [layoutOf] answers first for inventory files and inventory-level vars directories (plan amendment R10): a file
 * that is an inventory source of the root's layout is [FileKind.INVENTORY] or [FileKind.INVENTORY_INI], and a
 * `group_vars`/`host_vars` next to one is inventory-level for every environment it serves. Paths the layout does
 * not claim fall back to the `environments/<env>` rules.
 */
class AnsibleFileClassifier(
    private val probe: (VirtualFile) -> Boolean = PlaybookProbe::looksLikePlaybook,
    private val moleculeSupport: () -> Boolean = { true },
    private val layoutOf: (AnsibleRoot) -> RootLayout? = { null },
) {

    /** A classification and whether the file's content was read to obtain it. */
    class Result(val context: FileContext?, val usedContentProbe: Boolean)

    fun classify(file: VirtualFile, root: AnsibleRoot): Result {
        if (file.isDirectory) return Result(null, false)
        val relative = VfsUtilCore.getRelativePath(file, root.dir) ?: return Result(null, false)
        val segments = relative.split('/')
        if (AnsibleLayout.DOT_ANSIBLE in segments || segments.first() == AnsibleLayout.PATCHES) return Result(null, false)
        val run = Run(file, root, moleculeSupport())
        val roleDir = roleDirOf(file, root)
        val context = when {
            roleDir == null -> run.rootFile(segments)
            isRealRole(roleDir) -> run.roleFile(roleDir)
            else -> null
        }
        return Result(context, run.probed)
    }

    /** One classification; remembers whether the content probe ran. */
    private inner class Run(val file: VirtualFile, val root: AnsibleRoot, val molecule: Boolean) {
        var probed = false
        val name: String = file.name

        fun looksLikePlaybook(): Boolean {
            probed = true
            return probe(file)
        }

        fun context(
            kind: FileKind,
            roleDir: VirtualFile? = null,
            environment: String? = null,
            group: String? = null,
            host: String? = null,
            layer: VarsLayer? = null,
            scenario: VirtualFile? = null,
            environments: List<String> = listOfNotNull(environment),
            playbookLayer: VarsLayer? = null,
        ) = FileContext(root, kind, roleDir?.name, roleDir, environment, group, host, layer, scenario, environments, playbookLayer)

        val layout: RootLayout? by lazy { layoutOf(root)?.takeIf { it.hasInventory } }

        fun roleFile(roleDir: VirtualFile): FileContext {
            val inRole = VfsUtilCore.getRelativePath(file, roleDir)!!.split('/')
            if (inRole.size == 1) {
                return context(if (AnsibleLayout.isRequirementsName(name)) FileKind.REQUIREMENTS else FileKind.OTHER, roleDir)
            }
            val yaml = AnsibleLayout.isYamlName(name)
            return when (inRole[0]) {
                "tasks" -> context(if (yaml) FileKind.ROLE_TASKS else FileKind.OTHER, roleDir)
                "handlers" -> context(if (yaml) FileKind.ROLE_HANDLERS else FileKind.OTHER, roleDir)
                "defaults" ->
                    if (isRoleVarsName(name)) context(FileKind.ROLE_DEFAULTS, roleDir, layer = VarsLayer.ROLE_DEFAULTS)
                    else context(FileKind.OTHER, roleDir)
                "vars" ->
                    if (isRoleVarsName(name)) context(FileKind.ROLE_VARS, roleDir, layer = VarsLayer.ROLE_VARS)
                    else context(FileKind.OTHER, roleDir)
                "meta" -> context(
                    when {
                        inRole.size == 2 && AnsibleLayout.isArgumentSpecsName(name) -> FileKind.ROLE_ARGSPEC
                        inRole.size == 2 && yaml && AnsibleLayout.stem(name) == "main" -> FileKind.ROLE_META
                        AnsibleLayout.isRequirementsName(name) -> FileKind.REQUIREMENTS
                        else -> FileKind.OTHER
                    },
                    roleDir,
                )
                "templates" -> context(FileKind.ROLE_TEMPLATE, roleDir)
                "files" -> context(FileKind.ROLE_FILE, roleDir)
                AnsibleLayout.MOLECULE -> if (molecule) molecule(inRole.drop(1), roleDir) else context(FileKind.OTHER, roleDir)
                else -> context(FileKind.OTHER, roleDir)
            }
        }

        /** [inMolecule] are the path segments below a `molecule/` directory, the file name last. */
        fun molecule(inMolecule: List<String>, roleDir: VirtualFile?): FileContext {
            val yaml = AnsibleLayout.isYamlName(name)
            if (inMolecule.size == 1) {
                return context(
                    when {
                        AnsibleLayout.isRequirementsName(name) -> FileKind.REQUIREMENTS
                        yaml && AnsibleLayout.isMoleculeTasksName(name) -> FileKind.MOLECULE_TASKS
                        else -> FileKind.OTHER
                    },
                    roleDir,
                )
            }
            if (inMolecule[0] == "vars") {
                return if (isRoleVarsName(name)) context(FileKind.MOLECULE_VARS, roleDir, layer = VarsLayer.MOLECULE_INVENTORY)
                else context(FileKind.OTHER, roleDir)
            }
            val scenario = ancestor(file, inMolecule.size - 1)
            if (yaml && AnsibleLayout.isMoleculeTasksName(name)) return context(FileKind.MOLECULE_TASKS, roleDir, scenario = scenario)
            if (inMolecule.size == 2) {
                val stem = AnsibleLayout.stem(name)
                val kind = when {
                    AnsibleLayout.isMoleculeConfigName(name) -> FileKind.MOLECULE_CONFIG
                    AnsibleLayout.isRequirementsName(name) -> FileKind.REQUIREMENTS
                    !yaml -> FileKind.OTHER
                    stem in AnsibleLayout.MOLECULE_PLAYBOOK_STEMS -> FileKind.MOLECULE_PLAYBOOK
                    stem == "vars" -> FileKind.MOLECULE_VARS
                    looksLikePlaybook() -> FileKind.MOLECULE_PLAYBOOK
                    else -> FileKind.OTHER
                }
                val layer = VarsLayer.MOLECULE_INVENTORY.takeIf { kind == FileKind.MOLECULE_VARS }
                return context(kind, roleDir, layer = layer, scenario = scenario)
            }
            return when (inMolecule[1]) {
                "vars", AnsibleLayout.GROUP_VARS, AnsibleLayout.HOST_VARS ->
                    if (isRoleVarsName(name)) {
                        context(FileKind.MOLECULE_VARS, roleDir, layer = VarsLayer.MOLECULE_INVENTORY, scenario = scenario)
                    } else {
                        context(FileKind.OTHER, roleDir, scenario = scenario)
                    }
                "tasks" -> context(if (yaml) FileKind.MOLECULE_TASKS else FileKind.OTHER, roleDir, scenario = scenario)
                else -> context(FileKind.OTHER, roleDir, scenario = scenario)
            }
        }

        /** A file of the root that is not inside a role directory. */
        fun rootFile(segments: List<String>): FileContext {
            val moleculeAt = segments.indexOf(AnsibleLayout.MOLECULE)
            if (moleculeAt in 0 until segments.lastIndex) {
                return if (molecule) molecule(segments.drop(moleculeAt + 1), null) else context(FileKind.OTHER)
            }
            when {
                name == AnsibleLayout.ANSIBLE_CFG -> return context(FileKind.ANSIBLE_CFG)
                name in AnsibleLayout.LINT_CONFIG_NAMES -> return context(FileKind.LINT_CONFIG)
                AnsibleLayout.isRequirementsName(name) -> return context(FileKind.REQUIREMENTS)
            }
            val varsAt = segments.indexOfFirst { it == AnsibleLayout.GROUP_VARS || it == AnsibleLayout.HOST_VARS }
            if (varsAt in 0 until segments.lastIndex) return varsFile(segments, varsAt)
            layoutInventory()?.let { return it }
            val environment = environmentOf(segments)
            if (AnsibleLayout.isHostsFileName(name) &&
                (segments.size == 1 || (segments.size == 3 && segments[0] in AnsibleLayout.ENVIRONMENT_PARENTS))
            ) {
                return context(FileKind.INVENTORY, environment = environment)
            }
            if (!AnsibleLayout.isYamlName(name)) return context(FileKind.OTHER, environment = environment)
            if (segments.size == 1 && AnsibleLayout.isPlaybookName(name)) return context(FileKind.PLAYBOOK)
            if (segments.size == 2 && segments[0] == AnsibleLayout.PLAYBOOKS) return context(FileKind.PLAYBOOK)
            if (segments.size > 1 && segments[0] in AnsibleLayout.ROOT_CONTENT_DIRS) {
                return context(FileKind.OTHER, environment = environment)
            }
            return context(if (looksLikePlaybook()) FileKind.PLAYBOOK else FileKind.OTHER)
        }

        /** A file below `group_vars/` or `host_vars/`, found at [varsAt] in [segments]. */
        fun varsFile(segments: List<String>, varsAt: Int): FileContext {
            val isGroup = segments[varsAt] == AnsibleLayout.GROUP_VARS
            val below = segments.subList(varsAt + 1, segments.size)
            val entity = if (below.size == 1) {
                if (isGroup) AnsibleLayout.groupFromFileName(name) else AnsibleLayout.hostFromFileName(name)
            } else {
                below[0].takeIf {
                    !AnsibleLayout.isIgnoredVarsEntry(it) &&
                        below.subList(1, below.lastIndex).all(AnsibleLayout::isVarsSubdirectory) &&
                        AnsibleLayout.isVarsFileInDirectory(name)
                }
            }
            val base = ancestor(file, below.size + 1)
            val served = base?.let { layout?.environmentsWithVarsDir(it) }.orEmpty().map { it.id }
            val environment = when {
                served.isNotEmpty() -> served.first()
                varsAt == 2 && segments[0] in AnsibleLayout.ENVIRONMENT_PARENTS -> segments[1]
                varsAt > 0 && hasInventoryFile(base) -> segments[varsAt - 1]
                else -> null
            }
            val environments = served.ifEmpty { listOfNotNull(environment) }
            if (entity == null) return context(FileKind.OTHER, environment = environment, environments = environments)
            val inventoryLayer = environment != null
            val alsoPlaybook = served.isNotEmpty() && base == root.dir
            return if (isGroup) {
                val all = entity == "all"
                val playbook = if (all) VarsLayer.PLAYBOOK_GROUP_VARS_ALL else VarsLayer.PLAYBOOK_GROUP_VARS
                val layer = when {
                    inventoryLayer && all -> VarsLayer.INVENTORY_GROUP_VARS_ALL
                    inventoryLayer -> VarsLayer.INVENTORY_GROUP_VARS
                    else -> playbook
                }
                context(
                    FileKind.GROUP_VARS, environment = environment, group = entity, layer = layer,
                    environments = environments, playbookLayer = playbook.takeIf { alsoPlaybook },
                )
            } else {
                val layer = if (inventoryLayer) VarsLayer.INVENTORY_HOST_VARS else VarsLayer.PLAYBOOK_HOST_VARS
                context(
                    FileKind.HOST_VARS, environment = environment, host = entity, layer = layer,
                    environments = environments, playbookLayer = VarsLayer.PLAYBOOK_HOST_VARS.takeIf { alsoPlaybook },
                )
            }
        }

        /** The file as an inventory source of the layout, or null when no environment reads it. */
        fun layoutInventory(): FileContext? {
            val defs = layout?.inventoriesOf(file).orEmpty().filter { def -> readsFile(def) }
            if (defs.isEmpty()) return null
            val ids = defs.map { it.id }
            val kind = if (AnsibleLayout.isYamlName(name) || name.endsWith(".json")) FileKind.INVENTORY else FileKind.INVENTORY_INI
            return context(kind, environment = ids.first(), environments = ids)
        }

        /** A file inside a directory source counts only when the directory walk reads it. */
        private fun readsFile(def: InventoryDef): Boolean = def.sources.any { s ->
            val f = s.file ?: return@any false
            f == file || (s.isDirectory && generateSequence(file) { it.parent }.takeWhile { it != f }.all { DIRECTORY_WALK.skipReason(it.name) == null })
        }
    }

    /** The role directory containing [file]: an ancestor whose parent is one of the root's roles dirs. */
    private fun roleDirOf(file: VirtualFile, root: AnsibleRoot): VirtualFile? {
        if (root.rolesDirs.isEmpty()) return null
        var current = file.parent
        while (current != null && current != root.dir) {
            if (current.parent in root.rolesDirs) return current
            current = current.parent
        }
        return null
    }

    companion object {
        private val DIRECTORY_WALK = InventoryDirectoryWalk.Options()

        /** `environments/<env>/…` → `<env>`. */
        fun environmentOf(segments: List<String>): String? =
            segments.takeIf { it.size >= 3 && it[0] in AnsibleLayout.ENVIRONMENT_PARENTS }?.get(1)

        /** Whether [roleDir] is a role rather than a ghost (plan A.5: `roles/role-state`); the shared [RoleDirectories] rule. */
        fun isRealRole(roleDir: VirtualFile): Boolean = RoleDirectories.isRole(roleDir)

        /** Role and molecule vars: `main`, or any name with a vars extension (`defaults_from: other.yml`). */
        private fun isRoleVarsName(name: String): Boolean =
            !AnsibleLayout.isIgnoredVarsEntry(name) &&
                (name == "main" || name.substringAfterLast('.', "").lowercase() in AnsibleLayout.VARS_EXTENSIONS)

        /** Whether [dir] holds an inventory file, which makes its `group_vars`/`host_vars` inventory-level. */
        private fun hasInventoryFile(dir: VirtualFile?): Boolean = dir?.children.orEmpty().any {
            !it.isDirectory && (AnsibleLayout.isHostsFileName(it.name) || it.name == "hosts" || it.name == "hosts.ini")
        }

        /** The [levels]-th parent of [file] (0 is the file itself). */
        private fun ancestor(file: VirtualFile, levels: Int): VirtualFile? {
            var current: VirtualFile? = file
            repeat(levels) { current = current?.parent }
            return current
        }
    }
}
