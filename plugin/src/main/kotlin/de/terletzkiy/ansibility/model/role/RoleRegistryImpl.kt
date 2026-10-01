package de.terletzkiy.ansibility.model.role

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.RoleInfo
import de.terletzkiy.ansibility.api.RoleRef
import de.terletzkiy.ansibility.api.RoleRegistry
import de.terletzkiy.ansibility.model.task.YamlFiles
import de.terletzkiy.ansibility.semantics.schema.ArgSpecParser
import de.terletzkiy.ansibility.semantics.schema.ArgumentSpec
import de.terletzkiy.ansibility.semantics.yaml.YMap
import de.terletzkiy.ansibility.yaml.PsiYValueAdapter
import org.jetbrains.yaml.YAMLLanguage
import org.jetbrains.yaml.psi.YAMLFile
import java.util.concurrent.ConcurrentHashMap

/**
 * The project's [RoleRegistry] (plan A.5 "Role", F1.1).
 *
 * - **Roles** of a root are the directories under its `rolesDirs` that [RoleLayout.isRole] accepts (so the
 *   `role-state` ghosts are skipped). That is the shared `context.RoleDirectories` rule, so every role directory
 *   that `AnsibleWorkspace.contextOf` reports is listed here. A name found in several roles dirs resolves to the nearest one, as
 *   ansible-core's role search path does; the others stay reachable through [roleOf].
 * - **RoleInfo.argumentSpecs** comes from `meta/argument_specs.yml` (or, without it, the `argument_specs` key of
 *   `meta/main.yml`) through [ArgSpecParser]; `options: {}` and a missing `options` stay distinct where the
 *   parser keeps them apart (nested options).
 * - **RoleInfo.metaDependencies** are the role names of `meta/main.yml` `dependencies` (string and mapping forms;
 *   a path gives its last segment). The play graph resolves the written references ([RoleMeta.dependencies]).
 * - Per root, one table is cached until the Ansible structure, the project roots or any YAML file change
 *   ([AnsibleWorkspace.structureTracker] and the YAML PSI modification tracker); role details are filled into it
 *   on first use.
 *
 * Every lookup stays inside the given root. Methods may be called from any thread; they take a read lock when the
 * caller holds none.
 */
class RoleRegistryImpl(private val project: Project) : RoleRegistry {
    private val tables = ConcurrentHashMap<AnsibleRoot, CachedValue<RoleTable>>()

    override fun roles(root: AnsibleRoot): List<RoleRef> = readLocked { table(root).refs }

    override fun role(root: AnsibleRoot, name: String): RoleInfo? = readLocked {
        val table = table(root)
        table.dirsByName[name]?.let(table::info)
    }

    override fun roleOf(file: VirtualFile): RoleInfo? = readLocked {
        if (!file.isValid) return@readLocked null
        val workspace = AnsibleWorkspace.getInstance(project)
        if (file.isDirectory) {
            val root = workspace.rootFor(file) ?: return@readLocked null
            val roleDir = generateSequence(file) { it.parent }
                .takeWhile { it != root.dir }
                .firstOrNull { it.parent in root.rolesDirs }
                ?: return@readLocked null
            table(root).infoIfRole(roleDir)
        } else {
            val context = workspace.contextOf(file) ?: return@readLocked null
            val roleDir = context.roleDir ?: return@readLocked null
            table(context.root).infoIfRole(roleDir)
        }
    }

    private fun table(root: AnsibleRoot): RoleTable {
        if (tables.size > MAX_TABLES) tables.clear()
        val cached = tables.computeIfAbsent(root) {
            CachedValuesManager.getManager(project).createCachedValue(
                {
                    CachedValueProvider.Result.create(
                        RoleTable(root, scan(root)),
                        AnsibleWorkspace.getInstance(project).structureTracker,
                        PsiModificationTracker.getInstance(project).forLanguage(YAMLLanguage.INSTANCE),
                        ProjectRootManager.getInstance(project),
                    )
                },
                false,
            )
        }
        return cached.value
    }

    /** Every role dir of [root], nearest roles dir first, each dir's roles by name. */
    private fun scan(root: AnsibleRoot): List<VirtualFile> = root.rolesDirs.filter { it.isValid && it.isDirectory }.flatMap { rolesDir ->
        rolesDir.children.orEmpty()
            .filter { ProgressManager.checkCanceled(); RoleLayout.isRole(it) }
            .sortedBy { it.name }
    }

    /** One root's roles. [dirs] is in search order; details are built on first use. */
    private inner class RoleTable(private val root: AnsibleRoot, dirs: List<VirtualFile>) {
        private val allDirs: Set<VirtualFile> = dirs.toHashSet()
        private val infos = ConcurrentHashMap<VirtualFile, RoleInfo>()

        /** The visible role of each name (the first in search order). */
        val dirsByName: Map<String, VirtualFile> = LinkedHashMap<String, VirtualFile>().also { map ->
            dirs.forEach { map.putIfAbsent(it.name, it) }
        }

        val refs: List<RoleRef> = dirsByName.values.sortedBy { it.name }.map { RoleRef(root.dir, it.name, it) }

        fun info(dir: VirtualFile): RoleInfo = infos[dir] ?: build(dir).also { infos.putIfAbsent(dir, it) }

        fun infoIfRole(dir: VirtualFile): RoleInfo? = if (dir in allDirs || RoleLayout.isRole(dir)) info(dir) else null

        private fun build(dir: VirtualFile): RoleInfo {
            val name = dir.name
            val (specFile, specs) = specsOf(dir, name)
            val meta = RoleLayout.metaFile(dir)?.let(::yamlFile)
            return RoleInfo(
                ref = RoleRef(root.dir, name, dir),
                argumentSpecs = specs,
                specFile = specFile,
                defaultsFiles = RoleLayout.defaultsFiles(dir),
                varsFiles = RoleLayout.varsFiles(dir),
                taskFiles = RoleLayout.taskFiles(dir),
                handlerFiles = RoleLayout.handlerFiles(dir),
                templatesDir = RoleLayout.templatesDir(dir),
                filesDir = RoleLayout.filesDir(dir),
                metaDependencies = meta?.let(RoleMeta::dependencies).orEmpty().map { it.name },
            )
        }
    }

    /**
     * The spec file and its entry points: `meta/argument_specs.yml`, or else `meta/main.yml` when it has an
     * `argument_specs` key (ansible-core's order). No spec gives no entry points.
     */
    private fun specsOf(roleDir: VirtualFile, roleName: String): Pair<VirtualFile?, Map<String, ArgumentSpec>> {
        RoleLayout.specFile(roleDir)?.let { file ->
            val value = yamlFile(file)?.let(PsiYValueAdapter::documentValue)
            return file to ArgSpecParser.parse(value, roleName).entryPoints
        }
        val meta = RoleLayout.metaFile(roleDir) ?: return null to emptyMap()
        val value = yamlFile(meta)?.let(PsiYValueAdapter::documentValue)
        if (value !is YMap || value["argument_specs"] == null) return null to emptyMap()
        return meta to ArgSpecParser.parse(value, roleName).entryPoints
    }

    private fun yamlFile(file: VirtualFile): YAMLFile? = YamlFiles.yamlFile(project, file)

    private fun <T> readLocked(action: () -> T): T =
        if (ApplicationManager.getApplication().isReadAccessAllowed) action() else runReadActionBlocking(action)

    private companion object {
        /** Roots are few; stale root keys (after a root changed shape) are dropped wholesale past this size. */
        const val MAX_TABLES = 256
    }
}
