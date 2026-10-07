package de.terletzkiy.ansibility.run.molecule

import com.intellij.icons.AllIcons
import com.intellij.openapi.util.IconLoader
import de.terletzkiy.ansibility.api.RoleTestState
import javax.swing.Icon

/**
 * Icons of Molecule runs (plan amendment R16): a flask (original artwork, `_dark` variants beside it) for the run
 * configuration, menus and the Roles tab, the flask with ▶ for "Run Molecule Tests"; a scenario's gutter shows the
 * platform's test icons, the last result once it ran.
 */
object MoleculeIcons {
    @JvmField
    val Molecule: Icon = IconLoader.getIcon("/icons/molecule.svg", MoleculeIcons::class.java)

    @JvmField
    val RunMolecule: Icon = IconLoader.getIcon("/icons/moleculeRun.svg", MoleculeIcons::class.java)

    /** The gutter icon of a scenario file: the last result, else ▶ (▶▶ on `molecule.yml`, which offers every command). */
    fun gutter(state: RoleTestState?, all: Boolean): Icon = when (state) {
        RoleTestState.PASSED -> AllIcons.RunConfigurations.TestState.Green2
        RoleTestState.FAILED -> AllIcons.RunConfigurations.TestState.Red2
        else -> if (all) AllIcons.RunConfigurations.TestState.Run_run else AllIcons.RunConfigurations.TestState.Run
    }
}
