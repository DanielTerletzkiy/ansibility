package de.terletzkiy.ansibility.resolve

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.PlayGraph
import de.terletzkiy.ansibility.api.RoleRegistry
import de.terletzkiy.ansibility.api.SourceLocation
import de.terletzkiy.ansibility.api.VarDefKind
import de.terletzkiy.ansibility.api.VarDefinition
import de.terletzkiy.ansibility.api.VarsLayer
import de.terletzkiy.ansibility.index.JinjaBearing
import de.terletzkiy.ansibility.index.TaskKeywords
import de.terletzkiy.ansibility.index.ValueSummary
import de.terletzkiy.ansibility.model.inventory.VarsDocuments
import de.terletzkiy.ansibility.model.role.RoleLayout
import de.terletzkiy.ansibility.model.task.BlockNode
import de.terletzkiy.ansibility.model.task.TaskFileKind
import de.terletzkiy.ansibility.model.task.TaskFileModels
import de.terletzkiy.ansibility.model.task.TaskItem
import de.terletzkiy.ansibility.model.task.TaskNode
import de.terletzkiy.ansibility.model.task.YamlFiles
import de.terletzkiy.ansibility.semantics.yaml.YMap
import de.terletzkiy.ansibility.semantics.yaml.YScalar
import de.terletzkiy.ansibility.semantics.yaml.YSeq
import de.terletzkiy.ansibility.semantics.yaml.YValue
import de.terletzkiy.ansibility.yaml.PsiYValueAdapter
import java.util.concurrent.ConcurrentHashMap

/**
 * Definitions the `ansible.var.def` index does not see because only a task makes them definitions (plan amendment
 * FU, "include_vars / import_playbook vars in the definition index"):
 * - the top-level keys of a file a literal `include_vars` task loads (`include_vars: extra.yml`, `file: extra.yml`),
 *   from the role's `vars/` dir, the role, the playbook's `vars/` dir, the playbook dir or the task file's dir;
 * - the `vars:` keys of an `import_playbook` entry, which apply to every play it imports.
 * Files already indexed as vars files are deduplicated by location by the caller. Cached until any PSI changes.
 */
internal object IncludedVarsDefinitions {
    private val CACHE = Key.create<CachedValue<ConcurrentHashMap<String, Map<String, List<VarDefinition>>>>>("ansibility.includedVars")
    private val INCLUDE_VARS = setOf("include_vars", "ansible.builtin.include_vars", "ansible.legacy.include_vars")

    fun of(project: Project, root: AnsibleRoot, name: String): List<VarDefinition> = all(project, root)[name].orEmpty()

    fun names(project: Project, root: AnsibleRoot): Set<String> = all(project, root).keys

    fun inFile(project: Project, root: AnsibleRoot, file: VirtualFile): List<VarDefinition> =
        all(project, root).values.flatten().filter { it.location.file == file }

    private fun all(project: Project, root: AnsibleRoot): Map<String, List<VarDefinition>> {
        val cache = CachedValuesManager.getManager(project).getCachedValue(project, CACHE, {
            CachedValueProvider.Result.create(ConcurrentHashMap(), PsiModificationTracker.getInstance(project))
        }, false)
        return cache.getOrPut(root.dir.path) { compute(project, root) }
    }

    private fun compute(project: Project, root: AnsibleRoot): Map<String, List<VarDefinition>> {
        val out = LinkedHashMap<String, MutableList<VarDefinition>>()
        val loaded = HashSet<VirtualFile>()

        fun addKeys(target: VirtualFile, roleName: String?) {
            if (!loaded.add(target)) return
            val map = VarsDocuments.load(project, target) as? YMap ?: return
            for (entry in map.entries) {
                val offset = entry.key.range?.start ?: continue
                out.getOrPut(entry.key.text) { ArrayList() } += definition(entry.key.text, entry.value, target, offset, VarDefKind.INCLUDE_VARS, VarsLayer.INCLUDE_VARS, roleName)
            }
        }

        fun scan(taskFile: VirtualFile, tasks: List<TaskNode>, searchDirs: List<VirtualFile>, roleName: String?) {
            for (task in tasks) {
                ProgressManager.checkCanceled()
                val call = task.module ?: continue
                if (call.canonical !in INCLUDE_VARS && call.name !in INCLUDE_VARS) continue
                if (call.args.option("name") != null) continue
                val path = (call.args.option("file") as? YScalar)?.text ?: call.args.rawParams?.trim() ?: continue
                if (path.isEmpty() || JinjaBearing.hasTemplateMarkers(path)) continue
                val target = (searchDirs + taskFile.parent).firstNotNullOfOrNull { it.findFileByRelativePath(path)?.takeIf { f -> !f.isDirectory } } ?: continue
                addKeys(target, roleName)
            }
        }

        for (role in RoleRegistry.getInstance(project).roles(root)) {
            val dir = role.dir
            val searchDirs = listOfNotNull(dir.findChild(RoleLayout.VARS), dir)
            for (file in RoleLayout.taskFiles(dir) + RoleLayout.handlerFiles(dir)) {
                val yaml = YamlFiles.yamlFile(project, file) ?: continue
                scan(file, TaskFileModels.of(yaml, TaskFileKind.TASKS).tasks(), searchDirs, role.name)
            }
        }
        for (playbook in PlayGraph.getInstance(project).playbooks(root)) {
            ProgressManager.checkCanceled()
            val yaml = YamlFiles.yamlFile(project, playbook) ?: continue
            val dir = playbook.parent
            val searchDirs = listOfNotNull(dir.findChild(RoleLayout.VARS), dir)
            for (play in TaskFileModels.of(yaml, TaskFileKind.PLAYBOOK).plays) {
                val tasks = ArrayList<TaskNode>()
                fun visit(items: List<TaskItem>) {
                    for (item in items) when (item) {
                        is TaskNode -> tasks += item
                        is BlockNode -> {
                            visit(item.block)
                            visit(item.rescue)
                            visit(item.always)
                        }
                    }
                }
                play.sections().forEach(::visit)
                scan(playbook, tasks, searchDirs, null)
            }
            val document = PsiYValueAdapter.documentValue(yaml) as? YSeq ?: continue
            for (entry in document.items) {
                val map = entry as? YMap ?: continue
                if (map.keys.none { it in TaskKeywords.IMPORT_PLAYBOOK }) continue
                val vars = map["vars"] as? YMap ?: continue
                for (variable in vars.entries) {
                    val offset = variable.key.range?.start ?: continue
                    out.getOrPut(variable.key.text) { ArrayList() } +=
                        definition(variable.key.text, variable.value, playbook, offset, VarDefKind.PLAY_VARS, VarsLayer.PLAY_VARS, null)
                }
            }
        }
        return out
    }

    private fun definition(name: String, value: YValue, file: VirtualFile, offset: Int, kind: VarDefKind, layer: VarsLayer, roleName: String?) = VarDefinition(
        name = name,
        kind = kind,
        location = SourceLocation(file, offset),
        layer = layer,
        roleName = roleName,
        valueShape = ValueSummary.shape(value),
        preview = ValueSummary.preview(name, value, file.name),
        literalType = VarDefinitions.literalTypeName(ValueSummary.literalType(value)),
    )
}
