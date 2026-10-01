package de.terletzkiy.ansibility.navigation

import com.intellij.openapi.project.DumbAware
import com.intellij.psi.PsiFile
import de.terletzkiy.ansibility.api.AnsibleSite
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.SiteClassifier

/**
 * The role-navigation area's `siteClassifier` (plan A.4 F0, F1.8, X50; `order="after ansibilityVars, before
 * ansibilityTasks"`). It classifies reference values, never keys and never a Jinja range (those belong to the
 * variables area, which runs first):
 * - [AnsibleSite.TaskFileRef]: `include_tasks`/`import_tasks` targets (with the file's own role) and `tasks_from`/
 *   `handlers_from`/`vars_from`/`defaults_from` (with the included role);
 * - [AnsibleSite.PlaybookFileRef]: a play's `vars_files` entries and `import_playbook` targets;
 * - [AnsibleSite.TemplateRef]: `template`/`copy` `src` (a static value or the static prefix of a dynamic one; never a
 *   copy with a truthy `remote_src`, never an absolute path [AbsolutePath] cannot map), template-name values in vars
 *   files, and `{% include %}`-style names in role templates;
 * - [AnsibleSite.HandlerRef]: `notify` items (string or list) and handler `listen` topics;
 * - [AnsibleSite.RoleRef]: `roles:` entries, `include_role`/`import_role` `name`, `meta/main.yml` dependencies and
 *   molecule's `/ansible/roles/<role>` paths.
 *
 * Pure PSI and model work, [DumbAware]; only template-name values need the indexes and are skipped while indexing.
 */
class RefsSiteClassifier : SiteClassifier, DumbAware {
    override fun classify(file: PsiFile, offset: Int): AnsibleSite? {
        val virtualFile = file.originalFile.viewProvider.virtualFile
        val context = AnsibleWorkspace.getInstance(file.project).contextOf(virtualFile) ?: return null
        if (context.kind !in RefSites.KINDS) return null
        val occurrence = RefSites.at(file, offset, context) ?: return null
        if (occurrence.text.startsWith("/") && occurrence.kind in PATH_KINDS &&
            AbsolutePath.of(file.project, occurrence.text, virtualFile) == AbsolutePath.Unmapped
        ) {
            return null
        }
        return occurrence.toSite(context.roleName)
    }

    private companion object {
        /** Kinds whose absolute values are paths (a role path included). */
        val PATH_KINDS = setOf(
            RefKind.TASK_INCLUDE, RefKind.ROLE, RefKind.TEMPLATE_SRC, RefKind.COPY_SRC, RefKind.VARS_FILE, RefKind.IMPORT_PLAYBOOK,
        )
    }
}
