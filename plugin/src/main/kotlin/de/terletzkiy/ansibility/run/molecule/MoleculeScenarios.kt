package de.terletzkiy.ansibility.run.molecule

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import de.terletzkiy.ansibility.settings.AnsibilityProjectSettings
import de.terletzkiy.ansibility.settings.MoleculeSettings

/**
 * The cheap questions the Molecule run UI asks before it shows anything (plan amendments R19, D140, and R20, D152):
 * whether Molecule runs are offered and whether a role or a scenario file has a scenario. VFS and settings only; any
 * thread.
 */
object MoleculeScenarios {
    /**
     * The project setting "Run Molecule tests" ([MoleculeSettings.runTests]). Off, no Molecule run UI shows: the
     * toolbar button, the context menus, the gutter and the Roles markers. Code insight inside Molecule files and saved
     * Molecule run configurations do not depend on it, nor on "Show Molecule in navigation and search" (D152).
     */
    fun runsTests(project: Project): Boolean = AnsibilityProjectSettings.getInstance(project).settings.molecule.runTests

    /**
     * Whether the role at [roleDir] has a scenario (`molecule/<name>/molecule.yml`). Stops at the first one, unlike
     * [MoleculeRunContext.scenariosOf], which lists and sorts them all. False for a directory deleted since the caller
     * found it (a tool-window snapshot renders its role nodes until the next rebuild).
     */
    fun hasScenarios(roleDir: VirtualFile): Boolean {
        if (!roleDir.isValid) return false
        val molecule = roleDir.findChild(MoleculeRunContext.MOLECULE)?.takeIf { it.isDirectory } ?: return false
        return molecule.children.orEmpty().any { it.isDirectory && it.findChild(MoleculeRunContext.CONFIG) != null }
    }

    /**
     * Whether [file] (`molecule.yml`, `converge.yml`, `verify.yml`) sits in a scenario: a `molecule.yml` next to it in
     * a folder of `molecule`. Without one, Molecule would not run it.
     */
    fun inScenario(file: VirtualFile): Boolean {
        if (!file.isValid) return false
        val dir = file.parent ?: return false
        return dir.parent?.name == MoleculeRunContext.MOLECULE && dir.findChild(MoleculeRunContext.CONFIG) != null
    }
}
