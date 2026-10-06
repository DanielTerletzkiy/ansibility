package de.terletzkiy.ansibility.workspace.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.InputValidator
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.workspace.AnsibilityScopeBundle.message

/**
 * "New › Ansible Role" (plan X64) on a directory of an Ansible root: creates `roles/<name>/` with `tasks/main.yml`,
 * `handlers/main.yml`, `defaults/main.yml`, `meta/main.yml` and `meta/argument_specs.yml`, then opens the tasks.
 * Selected `roles/` directories take the role directly; other directories put it below their `roles/`.
 */
class NewRoleAction : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        val dir = e.getData(CommonDataKeys.VIRTUAL_FILE)
        e.presentation.isEnabledAndVisible = project != null && dir != null && dir.isDirectory &&
            AnsibleWorkspace.getInstance(project).rootFor(dir) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val dir = e.getData(CommonDataKeys.VIRTUAL_FILE)?.takeIf { it.isDirectory } ?: return
        val name = Messages.showInputDialog(project, message("new.role.prompt"), message("new.role.title"), null, "", NameValidator) ?: return
        val tasks = create(project, dir, name) ?: return
        FileEditorManager.getInstance(project).openFile(tasks, true)
    }

    private object NameValidator : InputValidator {
        override fun checkInput(inputString: String): Boolean = NAME.matches(inputString)

        override fun canClose(inputString: String): Boolean = checkInput(inputString)
    }

    companion object {
        private val NAME = Regex("[a-z][a-z0-9_-]*")

        /** Creates the role [name] for the selected [dir] and returns its `tasks/main.yml`, or null when it already exists. */
        fun create(project: Project, dir: VirtualFile, name: String): VirtualFile? = WriteCommandAction.writeCommandAction(project)
            .withName(message("new.role.title"))
            .compute<VirtualFile?, RuntimeException> {
                val roles = if (dir.name == ROLES) dir else VfsUtil.createDirectoryIfMissing(dir, ROLES)
                if (roles.findChild(name) != null) return@compute null
                val role = roles.createChildDirectory(this, name)
                fun write(path: String, text: String): VirtualFile {
                    val parent = VfsUtil.createDirectoryIfMissing(role, path.substringBeforeLast('/'))
                    val file = parent.createChildData(this, path.substringAfterLast('/'))
                    VfsUtil.saveText(file, text)
                    return file
                }
                write("defaults/main.yml", "---\n")
                write("handlers/main.yml", "---\n")
                write("meta/main.yml", "---\ngalaxy_info:\n  role_name: $name\n  description: \"\"\n  min_ansible_version: \"2.15\"\ndependencies: []\n")
                write("meta/argument_specs.yml", "---\nargument_specs:\n  main:\n    short_description: \"\"\n    options: {}\n")
                write("tasks/main.yml", "---\n")
            }

        private const val ROLES = "roles"
    }
}
