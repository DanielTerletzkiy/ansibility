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
        for (golden in listOf(GoldenRoot.None, GoldenRoot.FirstRoleLibrary, GoldenRoot.Root(GOLDEN), GoldenRoot.Root(FALCON), GoldenRoot.Root("/abs/path"), GoldenRoot.Git, GoldenRoot.Folder)) {
            assertEquals(golden, GoldenRoot.decode(GoldenRoot.encode(golden)))
        }
        assertNull(GoldenRoot.encode(GoldenRoot.None))
        for (text in listOf(null, "", "  ", "root:", "golden", "first-role-libraries", "gits", "folders")) {
            assertEquals("'$text' is no choice", GoldenRoot.None, GoldenRoot.decode(text))
        }
        // R25 (D193, X125): the stored forms.
        assertEquals("git", GoldenRoot.encode(GoldenRoot.Git))
        assertEquals("folder", GoldenRoot.encode(GoldenRoot.Folder))
        assertEquals("root:git", GoldenRoot.encode(GoldenRoot.Root("git")))
        assertEquals(GoldenRoot.Root("git"), GoldenRoot.decode("root:git"))
    }

    fun testTheExternalGoldenRootsDefaultsAndTheirXmlRoundTrip() {
        assertEquals(RemoteGolden(url = "", ref = "", rolesPath = "", refreshMinutes = 30, historyDepth = 1), DriftSettings.DEFAULT.remote)
        assertEquals("", DriftSettings.DEFAULT.folder)
        assertEquals(listOf(5, 10, 30, 60, 0), RemoteGolden.REFRESH_CHOICES)
        val drift = DriftSettings(
            golden = GoldenRoot.Git,
            remote = RemoteGolden("git@git.example.org:infra/golden.git", "main", "ansible/roles", 0, 50),
            folder = "~/src/golden",
        )
        val (xml, bean) = SettingsTestSupport.xmlRoundTrip(ProjectSettingsBean().apply { fill(ProjectSettings(drift = drift)) }, ProjectSettingsBean())
        assertEquals(xml, drift, bean.toSettings().drift)
        for (expected in listOf("driftGoldenRoot\" value=\"git\"", "driftRemoteUrl", "driftRemoteRef", "driftRemoteRolesPath", "driftRemoteRefreshMinutes", "driftRemoteHistoryDepth", "driftFolder")) {
            assertTrue(xml, expected in xml)
        }
        val folder = DriftSettings(golden = GoldenRoot.Folder, folder = "/srv/golden")
        val (folderXml, folderBean) = SettingsTestSupport.xmlRoundTrip(ProjectSettingsBean().apply { fill(ProjectSettings(drift = folder)) }, ProjectSettingsBean())
        assertEquals(folderXml, folder, folderBean.toSettings().drift)
        assertFalse("defaults are not written", "driftRemote" in folderXml)
    }

    fun testRemoteValuesAreNormalizedAndClamped() {
        val remote = RemoteGolden("  https://git.example.org/golden.git ", " main ", "./roles/", refreshMinutes = -5, historyDepth = 0)
        assertEquals(RemoteGolden("https://git.example.org/golden.git", "main", "roles", -5, 0), remote.normalized())
        assertEquals(0, remote.effectiveRefreshMinutes)
        assertEquals(1, remote.effectiveHistoryDepth)
        assertEquals(10_000, remote.copy(historyDepth = 1_000_000).effectiveHistoryDepth)
        settings.update { it.copy(drift = it.drift.copy(remote = remote, folder = " /srv/golden ")) }
        assertEquals("stored normalized", remote.normalized(), settings.settings.drift.remote)
        assertEquals("/srv/golden", settings.settings.drift.folder)
    }

    fun testTheFolderExpandsTheHome() {
        val home = java.nio.file.Path.of(System.getProperty("user.home"))
        assertEquals(home.resolve("src/golden"), DriftSettings(folder = "~/src/golden").folderPath())
        assertEquals(home, DriftSettings(folder = "~").folderPath())
        assertEquals(java.nio.file.Path.of("/srv/golden"), DriftSettings(folder = "/srv/x/../golden").folderPath())
        assertNull("relative", DriftSettings(folder = "src/golden").folderPath())
        assertNull(DriftSettings(folder = "  ").folderPath())
    }

    fun testTheExternalGoldenRootsAreSharedAndDriftOnly() {
        val main = settings.modificationTracker.modificationCount
        val drift = settings.driftModificationTracker.modificationCount
        val workspace = AnsibleWorkspaceImpl.getInstance(project)!!
        val roots = workspace.roots()
        settings.update { it.copy(drift = it.drift.copy(golden = GoldenRoot.Git, remote = RemoteGolden("git@git.example.org:infra/golden.git"))) }
        settings.update { it.copy(drift = it.drift.copy(remote = it.drift.remote.copy(ref = "main", refreshMinutes = 5, historyDepth = 3, rolesPath = "roles"))) }
        settings.update { it.copy(drift = it.drift.copy(golden = GoldenRoot.Folder, folder = "/srv/golden")) }
        assertEquals("no rescan, no restart", main, settings.modificationTracker.modificationCount)
        assertEquals(drift + 3, settings.driftModificationTracker.modificationCount)
        assertSame(roots, workspace.roots())
        val base = ProjectSettings.DEFAULT
        assertFalse(AnsibilitySettingsWiring.affectsHighlighting(base, base.copy(drift = DriftSettings(remote = RemoteGolden("https://git.example.org/x.git")))))
        assertFalse(AnsibilitySettingsWiring.affectsHighlighting(base, base.copy(drift = DriftSettings(golden = GoldenRoot.Folder, folder = "/x"))))

        settings.update { it.copy(drift = it.drift.copy(golden = GoldenRoot.Git)) }
        settings.setSharedWithTeam(true)
        val shared = project.service<AnsibilitySharedProjectSettings>().state.toSettings().drift
        assertEquals(GoldenRoot.Git, shared.golden)
        assertEquals(RemoteGolden("git@git.example.org:infra/golden.git", "main", "roles", 5, 3), shared.remote)
        assertEquals("/srv/golden", shared.folder)
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
