package de.terletzkiy.ansibility.settings

import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.context.AnsibleStructureListener
import de.terletzkiy.ansibility.context.AnsibleWorkspaceImpl
import de.terletzkiy.ansibility.context.ContextTestTree
import de.terletzkiy.ansibility.context.ContextTestTree.DANGER_ZONE
import de.terletzkiy.ansibility.context.ContextTestTree.FALCON
import de.terletzkiy.ansibility.context.ContextTestTree.GOLDEN
import de.terletzkiy.ansibility.context.ContextTestTree.PELICAN
import de.terletzkiy.ansibility.context.TargetVersionDetector
import de.terletzkiy.ansibility.context.TargetVersionSource
import de.terletzkiy.ansibility.semantics.CoreVersion
import de.terletzkiy.ansibility.semantics.diagnostics.Preset
import kotlinx.coroutines.runBlocking

/** The settings → TargetVersionDetector / AnsibleWorkspace wiring (task item 6). */
class AnsibilitySettingsWiringTest : BasePlatformTestCase() {
    private lateinit var detector: TargetVersionDetector
    private lateinit var settings: AnsibilityProjectSettings

    override fun setUp() {
        super.setUp()
        ContextTestTree.create(myFixture)
        AnsibleWorkspaceImpl.getInstance(project)!!.structureChanged()
        detector = TargetVersionDetector.getInstance(project)
        settings = AnsibilityProjectSettings.getInstance(project)
        AnsibilitySettingsWiring.getInstance(project).install()
    }

    override fun tearDown() {
        try {
            SettingsTestSupport.resetAll(project)
            detector.overrideFor = { null }
        } finally {
            super.tearDown()
        }
    }

    private fun root(path: String): AnsibleRoot {
        val dir = myFixture.findFileInTempDir(path)
        return AnsibleWorkspaceImpl.getInstance(project)!!.roots().single { it.dir == dir }
    }

    fun testActivityInstallsTheHook() {
        settings.updateRoot(FALCON) { it.copy(targetCore = "2.20.1") }
        detector.overrideFor = { null }
        assertEquals(TargetVersionSource.DOCKERFILE, detector.targetVersion(root(FALCON)).source)
        runBlocking { AnsibilitySettingsActivity().execute(project) }
        assertEquals(CoreVersion(2, 20, 1), detector.targetVersion(root(FALCON)).version)
        assertEquals(TargetVersionSource.SETTINGS, detector.targetVersion(root(FALCON)).source)
    }

    fun testActivityIsRegistered() {
        val activity = ExtensionPointName<Any>("com.intellij.postStartupActivity").findExtension(AnsibilitySettingsActivity::class.java)
        assertNotNull("postStartupActivity in ansibility-settings.xml", activity)
    }

    fun testExplicitTargetOverridesTheDockerfilePin() {
        assertEquals(TargetVersionSource.DOCKERFILE, detector.targetVersion(root(FALCON)).source)
        settings.updateRoot(FALCON) { it.copy(targetCore = "2.19.2") }
        val falcon = detector.targetVersion(root(FALCON))
        assertEquals(CoreVersion(2, 19, 2), falcon.version)
        assertEquals(TargetVersionSource.SETTINGS, falcon.source)
        assertEquals("other roots keep their pins", TargetVersionSource.DOCKERFILE, detector.targetVersion(root(GOLDEN)).source)
    }

    fun testBackToAutoRestoresDetection() {
        settings.updateRoot(FALCON) { it.copy(targetCore = "2.19") }
        assertEquals(CoreVersion(2, 19, 0), detector.targetVersion(root(FALCON)).version)
        settings.updateRoot(FALCON) { it.copy(targetCore = null) }
        val falcon = detector.targetVersion(root(FALCON))
        assertEquals(CoreVersion(2, 18, 8), falcon.version)
        assertEquals(TargetVersionSource.DOCKERFILE, falcon.source)
    }

    fun testNestedRootFollowsTheParentOverride() {
        settings.updateRoot(PELICAN) { it.copy(targetCore = "2.21.4") }
        assertEquals(CoreVersion(2, 21, 4), detector.targetVersion(root(DANGER_ZONE)).version)
        assertEquals(TargetVersionSource.SETTINGS, detector.targetVersion(root(DANGER_ZONE)).source)
    }

    fun testSharedSettingsFeedTheDetectorToo() {
        settings.setSharedWithTeam(true)
        settings.updateRoot(FALCON) { it.copy(targetCore = "2.20") }
        assertEquals(CoreVersion(2, 20, 0), detector.targetVersion(root(FALCON)).version)
    }

    fun testPathRuleChangesRefreshTheWorkspaceStructure() {
        val workspace = AnsibleWorkspace.getInstance(project)
        var notified = 0
        project.messageBus.connect(testRootDisposable).subscribe(AnsibleStructureListener.TOPIC, AnsibleStructureListener { notified++ })

        val before = workspace.structureTracker.modificationCount
        settings.update { it.copy(paths = it.paths.copy(detachedRule = false)) }
        assertTrue(workspace.structureTracker.modificationCount > before)
        assertEquals(1, notified)

        settings.update { it.copy(paths = it.paths.copy(extraIgnoredPaths = listOf("docs/**"))) }
        assertEquals(2, notified)
    }

    fun testOtherChangesLeaveTheStructureAlone() {
        var notified = 0
        project.messageBus.connect(testRootDisposable).subscribe(AnsibleStructureListener.TOPIC, AnsibleStructureListener { notified++ })
        settings.updateRoot(FALCON) { it.copy(preset = Preset.STRICT, targetCore = "2.19") }
        settings.update { it.copy(paths = it.paths.copy(schemaStoreExclusion = false)) }
        assertEquals(0, notified)
    }

    fun testTheMoleculeSettingsChangeNoStructureButReachTheListeners() {
        // Plan amendment R20: classification no longer depends on them, so nothing is rescanned; the settings event
        // still goes out, which restarts highlighting and rebuilds the tool window's snapshot.
        var structure = 0
        project.messageBus.connect(testRootDisposable).subscribe(AnsibleStructureListener.TOPIC, AnsibleStructureListener { structure++ })
        val events = ArrayList<Pair<MoleculeSettings, MoleculeSettings>>()
        project.messageBus.connect(testRootDisposable).subscribe(
            AnsibilitySettingsListener.TOPIC,
            object : AnsibilitySettingsListener {
                override fun projectSettingsChanged(old: ProjectSettings, new: ProjectSettings) {
                    events += old.molecule to new.molecule
                }
            },
        )
        val workspace = AnsibleWorkspace.getInstance(project)
        val before = workspace.structureTracker.modificationCount
        settings.update { it.copy(molecule = it.molecule.copy(runTests = false)) }
        settings.update { it.copy(molecule = it.molecule.copy(showInNavigation = true)) }
        assertEquals(0, structure)
        assertEquals(before, workspace.structureTracker.modificationCount)
        assertEquals(
            listOf(MoleculeSettings() to MoleculeSettings(runTests = false), MoleculeSettings(runTests = false) to MoleculeSettings(runTests = false, showInNavigation = true)),
            events,
        )
    }

    fun testStructuralPathSelection() {
        val base = PathSettings()
        assertEquals(AnsibilitySettingsWiring.structuralPaths(base), AnsibilitySettingsWiring.structuralPaths(base.copy(schemaStoreExclusion = false)))
        assertFalse(AnsibilitySettingsWiring.structuralPaths(base) == AnsibilitySettingsWiring.structuralPaths(base.copy(detachedRule = false)))
        assertFalse(AnsibilitySettingsWiring.structuralPaths(base) == AnsibilitySettingsWiring.structuralPaths(base.copy(extraIgnoredPaths = listOf("x/**"))))
        assertEquals(
            mapOf(FALCON to "2.19"),
            AnsibilitySettingsWiring.targetCores(ProjectSettings(mapOf(FALCON to RootSettings(targetCore = "2.19"), GOLDEN to RootSettings(preset = Preset.STRICT)))),
        )
    }
}
