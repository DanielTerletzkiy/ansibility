package de.terletzkiy.ansibility.model.role

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.model.drift.DriftFixture
import de.terletzkiy.ansibility.settings.AnsibilityProjectSettings
import de.terletzkiy.ansibility.settings.GoldenRoot
import de.terletzkiy.ansibility.settings.SettingsTestSupport

/**
 * The golden-root setting (plan amendment R24, D177) in [GoldenRoots] and [RoleCatalog] on the synthetic drift tree
 * (`testData/toolwindow/drift`: the role library `golden` and seven repos): None marks no reference, "first role
 * library" is R9's per-name rule, a chosen root (of any kind) owns the references, a missing or detached root none.
 */
class GoldenRootsTest : BasePlatformTestCase() {
    override fun getTestDataPath(): String = ModelFixture.testDataPath

    override fun setUp() {
        super.setUp()
        DriftFixture.copy(myFixture)
    }

    override fun tearDown() {
        try {
            SettingsTestSupport.resetAll(project)
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    private val catalog: RoleCatalogSnapshot get() = RoleCatalog.getInstance(project).snapshot()

    private fun golden(golden: GoldenRoot) = AnsibilityProjectSettings.getInstance(project).update { it.copy(drift = it.drift.copy(golden = golden)) }

    /** Each name's reference root, or null. */
    private fun references(): Map<String, String?> = catalog.let { c -> c.names.associateWith { c.reference(it)?.root?.displayName } }

    private fun owners(name: String): List<String> = catalog.copies(name).map { it.root.displayName }

    fun testWithoutAGoldenRootNoCopyIsAReference() {
        val snapshot = catalog
        assertNull(snapshot.golden)
        assertEquals(mapOf("app-same" to null, "base" to null, "solo" to null, "web" to null), references())
        assertTrue(snapshot.names.flatMap(snapshot::copies).none { it.isReference })
        assertEquals("role libraries still lead", listOf("golden") + DriftFixture.WEB_REPOS, owners("web"))
        assertEquals(GoldenResolution.NONE, GoldenRoots.resolve(project, GoldenRoot.None, AnsibleWorkspace.getInstance(project).roots()))
    }

    fun testFirstRoleLibraryIsR9sRule() {
        golden(GoldenRoot.FirstRoleLibrary)
        assertEquals("golden", catalog.golden?.displayName)
        assertEquals(mapOf("app-same" to null, "base" to "golden", "solo" to null, "web" to "golden"), references())
        assertEquals(listOf("golden") + DriftFixture.WEB_REPOS, owners("web"))
    }

    fun testAChosenProjectRootOwnsTheReferencesAndLeads() {
        golden(GoldenRoot.Root("repos/same/ansible"))
        assertEquals("same", catalog.golden?.displayName)
        assertEquals(mapOf("app-same" to "same", "base" to "same", "solo" to "same", "web" to "same"), references())
        assertEquals("the golden copy comes first", listOf("same", "golden", "missing", "mol", "mol2", "spec", "specmol", "tasks"), owners("web"))
        assertEquals(listOf(true) + List(7) { false }, catalog.copies("web").map { it.isReference })
        assertEquals(listOf("same", "tasks"), owners("solo"))
    }

    fun testAChosenRoleLibraryMarksOnlyItsOwnCopies() {
        golden(GoldenRoot.Root("golden"))
        assertEquals(mapOf("app-same" to null, "base" to "golden", "solo" to null, "web" to "golden"), references())
    }

    fun testTheExternalGoldenRootsAreNoRootOfTheProjectYet() {
        // Plan amendment R25, step 1: a git mirror or a folder outside the project resolves to no local root, not missing.
        val roots = AnsibleWorkspace.getInstance(project).roots()
        for (external in listOf(GoldenRoot.Git, GoldenRoot.Folder)) {
            golden(external)
            assertEquals(GoldenResolution(external, null, missing = false), GoldenRoots.resolve(project, external, roots))
            assertNull(catalog.golden)
            assertTrue(references().values.all { it == null })
        }
    }

    fun testAMissingRootMarksNoReference() {
        golden(GoldenRoot.Root("repos/hawk/ansible"))
        val resolution = GoldenRoots.resolve(project, AnsibilityProjectSettings.getInstance(project).settings, AnsibleWorkspace.getInstance(project).roots())
        assertTrue(resolution.missing)
        assertNull(resolution.root)
        assertNull(catalog.golden)
        assertTrue(references().values.all { it == null })
    }

    fun testADetachedRootIsNeverGolden() {
        val roots = AnsibleWorkspace.getInstance(project).roots()
        val library = roots.single { it.displayName == "golden" }
        val detached = roots.map { if (it == library) it.copy(detached = true) else it }
        val resolution = GoldenRoots.resolve(project, GoldenRoot.Root("golden"), detached)
        assertNull(resolution.root)
        assertTrue(resolution.missing)
        assertNull("a detached role library is not the first role library", GoldenRoots.firstRoleLibrary(detached))
        assertEquals(library, GoldenRoots.firstRoleLibrary(roots))
    }

    fun testTheCatalogFollowsTheSettingWithoutARescan() {
        val first = catalog
        val structure = AnsibleWorkspace.getInstance(project).structureTracker.modificationCount
        val stamp = RoleCatalog.getInstance(project).modificationTracker.modificationCount
        golden(GoldenRoot.FirstRoleLibrary)
        assertTrue(RoleCatalog.getInstance(project).modificationTracker.modificationCount > stamp)
        assertNotSame(first, catalog)
        assertEquals("golden", catalog.reference("web")?.root?.displayName)
        assertEquals("no structure change", structure, AnsibleWorkspace.getInstance(project).structureTracker.modificationCount)
    }
}
