package de.terletzkiy.ansibility.toolwindow

import com.intellij.execution.runners.ExecutionUtil
import com.intellij.icons.AllIcons
import com.intellij.openapi.util.IconLoader
import de.terletzkiy.ansibility.toolwindow.model.NodeIcon
import de.terletzkiy.ansibility.toolwindow.model.NodeMarker
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

    /** The "Differences from golden" folder and its groups: the platform's new-UI folder, in yellow (R24). */
    @JvmField
    val DriftFolder: Icon = load("/icons/driftFolder.svg")

    /** A role's `defaults/` and `vars/`: a folder with the variable badge. */
    @JvmField
    val RoleVarsFolder: Icon = load("/icons/roleVarsFolder.svg")

    /** A role's `handlers/`: a folder with a lightning badge (handlers run when notified). */
    @JvmField
    val RoleHandlersFolder: Icon = load("/icons/roleHandlersFolder.svg")

    /** A role's `meta/`: a folder with an info badge. */
    @JvmField
    val RoleMetaFolder: Icon = load("/icons/roleMetaFolder.svg")

    /** A role's Molecule tests (the flask of the run area's Molecule icons). */
    @JvmField
    val Molecule: Icon = load("/icons/molecule.svg")

    /** The icon of a test [marker] after a node's own: the flask, with a live dot while it runs, or the last result. */
    fun of(marker: NodeMarker): Icon = when (marker) {
        NodeMarker.TESTS -> Molecule
        NodeMarker.TESTS_RUNNING -> ExecutionUtil.getLiveIndicator(Molecule)
        NodeMarker.TESTS_PASSED -> AllIcons.RunConfigurations.TestPassed
        NodeMarker.TESTS_FAILED -> AllIcons.RunConfigurations.TestFailed
    }

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
        NodeIcon.FILE -> AllIcons.FileTypes.Any_type
        NodeIcon.DRIFT_FOLDER -> DriftFolder
        // The role's directories: the platform's source/template/resource/test/library folders where they fit.
        NodeIcon.ROLE_TASKS -> AllIcons.Modules.SourceRoot
        NodeIcon.ROLE_HANDLERS -> RoleHandlersFolder
        NodeIcon.ROLE_VARS -> RoleVarsFolder
        NodeIcon.ROLE_META -> RoleMetaFolder
        NodeIcon.ROLE_TEMPLATES -> AllIcons.Nodes.TemplateRoot
        NodeIcon.ROLE_FILES -> AllIcons.Modules.ResourcesRoot
        NodeIcon.ROLE_TESTS -> AllIcons.Modules.TestRoot
        NodeIcon.ROLE_PLUGINS -> AllIcons.Nodes.PpLibFolder
    }

    /** A definition another one shadows: the variable icon, greyed. */
    private val ShadowedVariable: Icon by lazy { IconLoader.getDisabledIcon(AllIcons.Nodes.Variable) }

    private fun load(path: String): Icon = IconLoader.getIcon(path, AnsibilityToolWindowIcons::class.java)
}
