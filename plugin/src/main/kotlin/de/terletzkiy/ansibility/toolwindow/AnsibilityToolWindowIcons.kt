package de.terletzkiy.ansibility.toolwindow

import com.intellij.icons.AllIcons
import com.intellij.openapi.util.IconLoader
import de.terletzkiy.ansibility.toolwindow.model.NodeIcon
import javax.swing.Icon

/**
 * Icons of the "Ansibility" tool window. The domain icons are SVGs under `/icons` with `_dark` variants (picked up by
 * [IconLoader]); file-like nodes use the platform's icons.
 */
object AnsibilityToolWindowIcons {
    /** The tool window stripe icon (13×13), also registered in `ansibility-inventory.xml`. */
    @JvmField
    val ToolWindow: Icon = load("/icons/ansibilityToolWindow.svg")

    @JvmField
    val Root: Icon = load("/icons/ansibleRoot.svg")

    @JvmField
    val Environment: Icon = load("/icons/ansibleEnvironment.svg")

    @JvmField
    val Group: Icon = load("/icons/ansibleGroup.svg")

    @JvmField
    val Host: Icon = load("/icons/ansibleHost.svg")

    /** The Swing icon of a model [NodeIcon]. */
    fun of(icon: NodeIcon): Icon = when (icon) {
        NodeIcon.WORKSPACE, NodeIcon.PROJECT_ROOT -> Root
        NodeIcon.ROLE_LIBRARY -> AllIcons.Nodes.PpLib
        NodeIcon.NESTED_ROOT -> AllIcons.Nodes.Module
        NodeIcon.WORKTREE -> AllIcons.Vcs.Branch
        NodeIcon.FOLDER -> AllIcons.Nodes.Folder
        NodeIcon.ENVIRONMENT -> Environment
        NodeIcon.GROUP -> Group
        NodeIcon.HOST -> Host
        NodeIcon.VAR_FILE -> AllIcons.FileTypes.Yaml
        NodeIcon.VAULT_FILE -> AllIcons.Nodes.Padlock
        NodeIcon.INLINE_VARS -> AllIcons.Nodes.Variable
        NodeIcon.CONFIG -> AllIcons.FileTypes.Config
        NodeIcon.PLAYBOOK -> AllIcons.FileTypes.Yaml
        NodeIcon.PLAY -> AllIcons.Nodes.Target
        NodeIcon.VARIABLE -> AllIcons.Nodes.Variable
        NodeIcon.SHADOWED -> ShadowedVariable
        NodeIcon.ROLE -> AllIcons.Nodes.Package
        NodeIcon.RUNTIME -> AllIcons.Actions.Lightning
    }

    /** A definition another one shadows: the variable icon, greyed. */
    private val ShadowedVariable: Icon by lazy { IconLoader.getDisabledIcon(AllIcons.Nodes.Variable) }

    private fun load(path: String): Icon = IconLoader.getIcon(path, AnsibilityToolWindowIcons::class.java)
}
