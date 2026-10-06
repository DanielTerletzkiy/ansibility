package de.terletzkiy.ansibility.context

import com.intellij.openapi.util.NlsSafe
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.FileContext
import de.terletzkiy.ansibility.api.FileKind
import de.terletzkiy.ansibility.api.RootKind
import org.jetbrains.annotations.Nls

/**
 * Text for the X02 status-bar widget and its details popup. Pure: everything comes in as arguments, so the
 * wording is unit-tested without a status bar.
 */
object ContextPresentation {

    /** One labelled line of the details popup. */
    data class Detail(@Nls val label: String, @NlsSafe val value: String)

    /** `Ansible: falcon · All envs · core 2.18.8`; `golden (role library)` for a role library. */
    @Nls
    fun statusText(root: AnsibleRoot, target: TargetVersion, inventory: InventoryShape = InventoryShape.of(root)): String {
        val name = if (root.kind == RootKind.ROLE_LIBRARY) {
            AnsibilityCoreBundle.message("status.root.library", root.displayName)
        } else {
            root.displayName
        }
        val parts = mutableListOf(AnsibilityCoreBundle.message("status.text", name))
        if (inventory.hasInventory) parts += AnsibilityCoreBundle.message(if (inventory.single) "status.hosts.all" else "status.envs.all")
        target.version?.let { version ->
            parts += AnsibilityCoreBundle.message(if (target.guessed) "status.core.guessed" else "status.core", version.toString())
        }
        return parts.joinToString(SEPARATOR)
    }

    /** The lines of the details popup for the selected file. */
    fun details(root: AnsibleRoot, context: FileContext?, target: TargetVersion, worktree: DetachedWorktree?): List<Detail> {
        val lines = mutableListOf<Detail>()
        fun add(key: String, value: String?) {
            if (!value.isNullOrEmpty()) lines += Detail(AnsibilityCoreBundle.message(key), value)
        }
        add("details.root", root.displayName)
        add("details.root.kind", rootKindName(root, worktree))
        add("details.root.path", root.dir.presentableUrl)
        add("details.roles.dirs", root.rolesDirs.joinToString(", ") { it.presentableUrl })
        if (context != null) {
            add("details.file.kind", fileKindName(context.kind))
            add("details.role", context.roleName)
            add("details.layer", context.layer?.let { AnsibilityCoreBundle.message("details.layer.value", it.label, it.level) })
            add("details.environment", context.environment)
            add("details.group", context.group)
            add("details.host", context.host)
            add("details.scenario", context.moleculeScenarioDir?.name)
        }
        add("details.target", targetText(target))
        return lines
    }

    @Nls
    fun rootKindName(root: AnsibleRoot, worktree: DetachedWorktree?): String {
        val kind = when (root.kind) {
            RootKind.PROJECT -> AnsibilityCoreBundle.message(
                if (root.dir.findChild(AnsibleLayout.ANSIBLE_CFG) == null) "root.kind.project.cfgless" else "root.kind.project",
            )
            RootKind.ROLE_LIBRARY -> AnsibilityCoreBundle.message("root.kind.library")
            RootKind.NESTED_PLAYBOOK -> AnsibilityCoreBundle.message("root.kind.nested")
        }
        if (!root.detached) return kind
        return AnsibilityCoreBundle.message("root.kind.detached", kind, worktree?.name ?: "")
    }

    @Nls
    fun targetText(target: TargetVersion): String {
        val version = target.version ?: return AnsibilityCoreBundle.message("target.unknown")
        val source = when (target.source) {
            TargetVersionSource.SETTINGS -> AnsibilityCoreBundle.message("target.source.settings")
            TargetVersionSource.DOCKERFILE -> AnsibilityCoreBundle.message("target.source.dockerfile", target.detail)
            TargetVersionSource.MAJORITY -> AnsibilityCoreBundle.message("target.source.majority", target.detail)
            TargetVersionSource.LOCAL_GUESSED -> AnsibilityCoreBundle.message("target.source.local", target.detail)
            TargetVersionSource.NONE -> return AnsibilityCoreBundle.message("target.unknown")
        }
        return AnsibilityCoreBundle.message("target.value", version.toString(), source)
    }

    @Nls
    fun fileKindName(kind: FileKind): String = AnsibilityCoreBundle.message(
        when (kind) {
            FileKind.ROLE_TASKS -> "file.kind.role.tasks"
            FileKind.ROLE_HANDLERS -> "file.kind.role.handlers"
            FileKind.ROLE_DEFAULTS -> "file.kind.role.defaults"
            FileKind.ROLE_VARS -> "file.kind.role.vars"
            FileKind.ROLE_ARGSPEC -> "file.kind.role.argspec"
            FileKind.ROLE_META -> "file.kind.role.meta"
            FileKind.ROLE_TEMPLATE -> "file.kind.role.template"
            FileKind.ROLE_FILE -> "file.kind.role.file"
            FileKind.PLAYBOOK -> "file.kind.playbook"
            FileKind.INVENTORY -> "file.kind.inventory"
            FileKind.INVENTORY_INI -> "file.kind.inventory.ini"
            FileKind.GROUP_VARS -> "file.kind.group.vars"
            FileKind.HOST_VARS -> "file.kind.host.vars"
            FileKind.MOLECULE_CONFIG -> "file.kind.molecule.config"
            FileKind.MOLECULE_PLAYBOOK -> "file.kind.molecule.playbook"
            FileKind.MOLECULE_TASKS -> "file.kind.molecule.tasks"
            FileKind.MOLECULE_VARS -> "file.kind.molecule.vars"
            FileKind.ANSIBLE_CFG -> "file.kind.ansible.cfg"
            FileKind.LINT_CONFIG -> "file.kind.lint.config"
            FileKind.REQUIREMENTS -> "file.kind.requirements"
            FileKind.OTHER -> "file.kind.other"
        },
    )

    private const val SEPARATOR = " · "
}
