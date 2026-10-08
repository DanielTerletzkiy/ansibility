package de.terletzkiy.ansibility.run.molecule

import com.intellij.openapi.application.WriteAction
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * [MoleculeScenarios] answers for files the Roles tab still shows after they were deleted (a tool-window snapshot
 * renders its role nodes until the next rebuild): no scenario, instead of an invalid-file error from the tree's
 * background presentation.
 */
class MoleculeScenariosTest : BasePlatformTestCase() {
    fun testADeletedRoleHasNoScenario() {
        val config = myFixture.tempDirFixture.createFile("roles/web/molecule/default/molecule.yml", "---\ndriver:\n  name: default\n")
        val converge = myFixture.tempDirFixture.createFile("roles/web/molecule/default/converge.yml", "---\n- hosts: all\n")
        val role = config.parent.parent.parent
        assertTrue(MoleculeScenarios.hasScenarios(role))
        assertTrue(MoleculeScenarios.inScenario(converge))
        WriteAction.runAndWait<Throwable> { role.delete(this) }
        assertFalse(role.isValid)
        assertFalse(MoleculeScenarios.hasScenarios(role))
        assertFalse(MoleculeScenarios.inScenario(converge))
    }
}
