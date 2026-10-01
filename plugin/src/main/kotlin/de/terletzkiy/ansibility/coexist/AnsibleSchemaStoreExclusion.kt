package de.terletzkiy.ansibility.coexist

import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectLocator
import com.intellij.openapi.vfs.VirtualFile
import com.jetbrains.jsonSchema.remote.JsonSchemaCatalogExclusion
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.FileKind

/**
 * Keeps SchemaStore's catalog schemas off the files whose semantics we own (`json.catalog.exclusion`, plan
 * "Coexistence & settings"), so task and playbook warnings are not doubled: SchemaStore maps its Ansible
 * "Tasks File" schema to every `tasks` and `handlers` YAML file and its "Playbook" schema to `playbooks` files.
 *
 * Excluded: [EXCLUDED_KINDS] (role tasks and handlers, playbooks, molecule playbooks and task lists) inside an
 * Ansible root. Kept: Argument Specs, Meta, Vars and every other schema, on which we add semantics, and every file
 * outside the roots. The JSON plugin's exclusion is per file and drops the whole catalog mapping for it.
 *
 * Switchable per project through [CoexistenceSettings.schemaStoreExclusion] (on by default).
 */
class AnsibleSchemaStoreExclusion : JsonSchemaCatalogExclusion {
    override fun isExcluded(file: VirtualFile): Boolean {
        if (file.isDirectory || !isYamlName(file.name)) return false
        return ProjectLocator.getInstance().getProjectsForFile(file).any { project -> isExcluded(project, file) }
    }

    /** The decision for [file] in [project]. */
    fun isExcluded(project: Project, file: VirtualFile): Boolean {
        if (project.isDisposed || !CoexistenceSettings.getInstance().schemaStoreExclusion(project)) return false
        val kind = AnsibleWorkspace.getInstance(project).contextOf(file)?.kind ?: return false
        return kind in EXCLUDED_KINDS
    }

    private fun isYamlName(name: String): Boolean = name.endsWith(".yml") || name.endsWith(".yaml")

    companion object {
        /** File kinds whose SchemaStore mapping (Ansible Tasks File or Playbook) duplicates our checks. */
        val EXCLUDED_KINDS: Set<FileKind> = setOf(
            FileKind.ROLE_TASKS,
            FileKind.ROLE_HANDLERS,
            FileKind.PLAYBOOK,
            FileKind.MOLECULE_PLAYBOOK,
            FileKind.MOLECULE_TASKS,
        )
    }
}
