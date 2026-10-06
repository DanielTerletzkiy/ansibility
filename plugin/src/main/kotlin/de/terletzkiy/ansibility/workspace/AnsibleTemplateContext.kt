package de.terletzkiy.ansibility.workspace

import com.intellij.codeInsight.template.TemplateActionContext
import com.intellij.codeInsight.template.TemplateContextType
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.FileKind

/** The "Ansible" live-template context (plan X69): YAML files of an Ansible root that hold tasks or plays. */
class AnsibleTemplateContext : TemplateContextType(AnsibilityScopeBundle.message("live.context")) {
    override fun isInContext(templateActionContext: TemplateActionContext): Boolean {
        val file = templateActionContext.file.originalFile.virtualFile ?: return false
        val kind = AnsibleWorkspace.getInstance(templateActionContext.file.project).contextOf(file)?.kind ?: return false
        return kind in KINDS
    }

    private companion object {
        val KINDS = setOf(FileKind.ROLE_TASKS, FileKind.ROLE_HANDLERS, FileKind.PLAYBOOK, FileKind.MOLECULE_PLAYBOOK, FileKind.MOLECULE_TASKS)
    }
}
