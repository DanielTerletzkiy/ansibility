package de.terletzkiy.ansibility.settings

import com.intellij.openapi.components.service
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.terletzkiy.ansibility.context.AnsibleWorkspaceImpl
import de.terletzkiy.ansibility.context.ContextTestTree
import de.terletzkiy.ansibility.context.ContextTestTree.FALCON
import de.terletzkiy.ansibility.context.ContextTestTree.GOLDEN

/**
 * The role-drift settings (plan amendment R24, D177): the golden root (default None) and Ignore molecule/, their
 * storage, sharing, and that a change of them alone re-tiers drift without a rescan and without restarting
 * highlighting.
 */
class DriftSettingsTest : BasePlatformTestCase() {
    private lateinit var settings: AnsibilityProjectSettings

    override fun setUp() {
        super.setUp()
        ContextTestTree.create(myFixture)
        AnsibleWorkspaceImpl.getInstance(project)!!.structureChanged()
        settings = AnsibilityProjectSettings.getInstance(project)
    }

    override fun tearDown() {
        try {
            SettingsTestSupport.resetAll(project)
        } finally {
            super.tearDown()
        }
    }

    private fun golden(golden: GoldenRoot) = settings.update { it.copy(drift = it.drift.copy(golden = golden)) }

    fun testTheIgnoreMoleculeCommentSaysWhatItAffects() {
        // Review fix U4: drift and Compare only; Align and Push always include molecule/.
        val comment = AnsibilitySettingsBundle.message("drift.ignore.molecule.comment")
        assertTrue(comment, comment.contains("Compare") && comment.contains("badges"))
        assertTrue(comment, comment.contains("Align and Push always include molecule/"))
    }

    fun testDefaultsAreNoGoldenRootAndMoleculeTakesPart() {
        assertEquals(DriftSettings(GoldenRoot.None, ignoreMolecule = false), DriftSettings.DEFAULT)
        assertEquals("None until the user picks (approved)", DriftSettings.DEFAULT, ProjectSettings.DEFAULT.drift)
        assertEquals(DriftSettings.DEFAULT, ProjectSettingsBean().toSettings().drift)
        assertEquals(DriftSettings.DEFAULT, settings.settings.drift)
    }

    fun testGoldenRootEncoding() {
        for (golden in listOf(GoldenRoot.None, GoldenRoot.FirstRoleLibrary, GoldenRoot.Root(GOLDEN), GoldenRoot.Root(FALCON), GoldenRoot.Root("/abs/path"))) {
            assertEquals(golden, GoldenRoot.decode(GoldenRoot.encode(golden)))
        }
        assertNull(GoldenRoot.encode(GoldenRoot.None))
        for (text in listOf(null, "", "  ", "root:", "golden", "first-role-libraries")) {
            assertEquals("'$text' is no choice", GoldenRoot.None, GoldenRoot.decode(text))
        }
    }

    fun testEachDriftValueSurvivesTheXmlRoundTripAlone() {
        val values = listOf(
            DriftSettings(GoldenRoot.FirstRoleLibrary),
            DriftSettings(GoldenRoot.Root(FALCON)),
            DriftSettings(ignoreMolecule = true),
        )
        for (drift in values) {
            val (xml, bean) = SettingsTestSupport.xmlRoundTrip(ProjectSettingsBean().apply { fill(ProjectSettings(drift = drift)) }, ProjectSettingsBean())
            assertEquals(xml, drift, bean.toSettings().drift)
        }
        val (xml, _) = SettingsTestSupport.xmlRoundTrip(ProjectSettingsBean().apply { fill(ProjectSettings.DEFAULT) }, ProjectSettingsBean())
        assertEquals("None and molecule taking part write nothing", "<component />", xml)
    }

    fun testTheGoldenRootIsSharedWithTheTeam() {
        golden(GoldenRoot.Root(GOLDEN))
        settings.setSharedWithTeam(true)
        val shared = project.service<AnsibilitySharedProjectSettings>().state
        assertEquals(GoldenRoot.Root(GOLDEN), shared.toSettings().drift.golden)
    }

    fun testADriftChangeAloneMovesOnlyTheDriftTracker() {
        val events = ArrayList<Pair<ProjectSettings, ProjectSettings>>()
        project.messageBus.connect(testRootDisposable).subscribe(
            AnsibilitySettingsListener.TOPIC,
            object : AnsibilitySettingsListener {
                override fun projectSettingsChanged(old: ProjectSettings, new: ProjectSettings) {
                    events += old to new
                }
            },
        )
        val main = settings.modificationTracker.modificationCount
        val drift = settings.driftModificationTracker.modificationCount
        golden(GoldenRoot.FirstRoleLibrary)
        settings.update { it.copy(drift = it.drift.copy(ignoreMolecule = true)) }
        assertEquals("the caches over the other settings stay valid", main, settings.modificationTracker.modificationCount)
        assertEquals(drift + 2, settings.driftModificationTracker.modificationCount)
        assertEquals("the listeners still hear about it", 2, events.size)
        assertEquals(GoldenRoot.FirstRoleLibrary, events.first().second.drift.golden)

        settings.update { it.copy(molecule = it.molecule.copy(runTests = false)) }
        assertTrue(settings.modificationTracker.modificationCount > main)
        assertEquals("another change leaves the drift tracker", drift + 2, settings.driftModificationTracker.modificationCount)
        settings.update { it.copy(molecule = it.molecule.copy(runTests = true), drift = it.drift.copy(golden = GoldenRoot.None)) }
        assertEquals("a change of both moves both", drift + 3, settings.driftModificationTracker.modificationCount)
    }

    fun testADriftChangeAloneNeitherRescansNorRestartsHighlighting() {
        val workspace = AnsibleWorkspaceImpl.getInstance(project)!!
        val roots = workspace.roots()
        golden(GoldenRoot.Root(FALCON))
        assertSame("the root scan is still valid: no rescan", roots, workspace.roots())
        settings.update { it.copy(paths = it.paths.copy(schemaStoreExclusion = false)) }
        assertNotSame("other settings do invalidate it", roots, workspace.roots())

        val base = ProjectSettings.DEFAULT
        val drift = base.copy(drift = DriftSettings(GoldenRoot.FirstRoleLibrary, ignoreMolecule = true))
        assertTrue(base.differsOnlyInDrift(drift))
        assertFalse("no change is no drift change", base.differsOnlyInDrift(base))
        assertFalse(base.differsOnlyInDrift(drift.copy(molecule = MoleculeSettings(runTests = false))))
        assertFalse("drift alone does not restart highlighting", AnsibilitySettingsWiring.affectsHighlighting(base, drift))
        assertTrue(AnsibilitySettingsWiring.affectsHighlighting(base, base.copy(molecule = MoleculeSettings(runTests = false))))
        assertTrue(AnsibilitySettingsWiring.affectsHighlighting(base, drift.copy(paths = PathSettings(detachedRule = false))))
        assertFalse(AnsibilitySettingsWiring.affectsHighlighting(base, base))
    }
}
