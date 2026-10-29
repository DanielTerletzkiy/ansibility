package de.terletzkiy.ansibility.vault.regenerate

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.FileKind
import de.terletzkiy.ansibility.resolve.VarUsage
import de.terletzkiy.ansibility.resolve.VarUsageQuery
import de.terletzkiy.ansibility.vault.ui.AnsibilityVaultUiBundle.message
import org.jetbrains.annotations.Nls
import org.jetbrains.yaml.psi.YAMLKeyValue

/**
 * X130 of plan amendment R29: the roles and templates that use a regenerated variable, from the variable usage index
 * ([VarUsageQuery]), so you know what to deploy. Names only, never values; at most [SHOWN] per variable, then "and N
 * more".
 *
 * A use inside a role's files names the role; a template outside roles, a playbook and any other file name themselves.
 * A use as the value of another variable in a vars file (`db_password: "{{ vault_db_password }}"`, the usual way a
 * `vault_*` value is handed on) is followed one step: the users of that variable count too. Call in a smart read
 * action; it checks for cancellation.
 */
internal object UsedBy {
    /** The users named per variable. */
    const val SHOWN: Int = 5

    /** The uses looked at per variable (and per variable it is handed on to). */
    private const val LOOKED_AT = 200

    /** File kinds whose key-values hand a variable on to another variable. */
    private val VARS_KINDS = setOf(FileKind.GROUP_VARS, FileKind.HOST_VARS, FileKind.ROLE_DEFAULTS, FileKind.ROLE_VARS, FileKind.INVENTORY, FileKind.OTHER)

    /** "vault_db_password: role web, role db, template haproxy.cfg.j2 and 2 more", or null when nothing uses [name]. */
    @Nls
    fun line(project: Project, root: AnsibleRoot, name: String): String? {
        val users = users(project, root, name)
        if (users.isEmpty()) return null
        val shown = users.take(SHOWN).joinToString(", ") { StringUtil.escapeXmlEntities(it) }
        val list = if (users.size > SHOWN) message("regenerate.used.by.more", shown, users.size - SHOWN) else shown
        return message("regenerate.used.by", StringUtil.escapeXmlEntities(name), list)
    }

    /** The users of [name] in [root]: roles first, then templates, playbooks and other files, each once. */
    fun users(project: Project, root: AnsibleRoot, name: String): List<String> {
        val query = VarUsageQuery.getInstance(project)
        val workspace = AnsibleWorkspace.getInstance(project)
        val roles = LinkedHashSet<String>()
        val others = LinkedHashSet<String>()
        val handedOn = LinkedHashSet<String>()
        for (usage in query.usages(root, name).take(LOOKED_AT)) {
            ProgressManager.checkCanceled()
            classify(project, workspace, usage, roles, others)?.takeIf { it != name }?.let(handedOn::add)
        }
        for (alias in handedOn) {
            for (usage in query.usages(root, alias).take(LOOKED_AT)) {
                ProgressManager.checkCanceled()
                classify(project, workspace, usage, roles, others)
            }
        }
        return roles.map { message("regenerate.used.role", it) } + others.toList()
    }

    /**
     * Files [usage] under a role or another file; returns the variable it is handed on to when the use is the value of a
     * key in a vars file (then the file itself is not named).
     */
    private fun classify(project: Project, workspace: AnsibleWorkspace, usage: VarUsage, roles: MutableSet<String>, others: MutableSet<String>): String? {
        val file = usage.location.file
        val context = workspace.contextOf(file)
        // Any use inside a role's files names the role; its defaults and vars may hand the value on as well.
        context?.roleName?.let { roles += it }
        if (context != null && context.kind in VARS_KINDS && !file.name.endsWith(".j2")) {
            val element = PsiManager.getInstance(project).findFile(file)?.findElementAt(usage.location.offset)
            val key = PsiTreeUtil.getParentOfType(element, YAMLKeyValue::class.java)?.keyText
            if (key != null) return key
        }
        if (context?.roleName != null) return null
        others += when {
            file.name.endsWith(".j2") || context?.kind == FileKind.ROLE_TEMPLATE -> message("regenerate.used.template", file.name)
            context?.kind == FileKind.PLAYBOOK -> message("regenerate.used.playbook", file.name)
            else -> file.name
        }
        return null
    }
}
