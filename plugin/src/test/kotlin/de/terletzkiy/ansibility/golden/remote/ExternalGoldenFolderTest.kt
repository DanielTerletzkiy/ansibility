package de.terletzkiy.ansibility.golden.remote

import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.vfs.ReadonlyStatusHandler
import com.intellij.openapi.vfs.WritingAccessProvider
import com.intellij.testFramework.EdtTestUtil
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.WorkspaceScopeService
import de.terletzkiy.ansibility.golden.GoldenTestSupport
import de.terletzkiy.ansibility.golden.sync.FileOp
import de.terletzkiy.ansibility.golden.sync.FileStamp
import de.terletzkiy.ansibility.golden.sync.OpContent
import de.terletzkiy.ansibility.golden.sync.RoleWriter
import de.terletzkiy.ansibility.golden.sync.WriteResult
import de.terletzkiy.ansibility.model.drift.DriftFixture
import de.terletzkiy.ansibility.model.drift.DriftTier
import de.terletzkiy.ansibility.model.role.ExternalGoldenRoot
import de.terletzkiy.ansibility.model.role.RoleCatalog
import de.terletzkiy.ansibility.settings.AnsibilityProjectSettings
import de.terletzkiy.ansibility.settings.GoldenRoot
import java.nio.file.Files

/**
 * The external golden root wired in as the golden root (plan amendment R25 step 2, D197–D199) with a folder outside the
 * project (X125): the catalog's golden copies and their drift, never a root of the workspace or of a scope, read-only
 * for the platform (WritingAccessProvider), for the write engine (RoleWriter) and in the editor (the banner).
 */
class ExternalGoldenFolderTest : ExternalGoldenTestCase() {
    fun testTheFolderRolesAreTheGoldenCopiesAndTheProjectsCopiesFollowThem() {
        useFolderGolden()

        val catalog = RoleCatalog.getInstance(project).snapshot()
        val external = catalog.external ?: error("no external golden root")
        assertEquals("the synthetic root is the golden root", external.root, catalog.golden)
        assertEquals("golden", external.name)
        val web = catalog.copies("web")
        assertEquals("the golden copy first, then the seven repos", 8, web.size)
        val golden = web.first()
        assertTrue(golden.isExternal)
        assertTrue(golden.isReference)
        assertEquals(goldenVf("roles/web"), golden.dir)
        assertTrue("the project's copies are neither golden nor external", web.drop(1).none { it.isReference || it.isExternal })
        assertEquals(golden, catalog.reference("web"))
        assertEquals("a golden-only name shows too", listOf("app-same", "base", "solo", "web"), catalog.names)
        assertTrue(catalog.copies("base").first().isExternal)
    }

    fun testDriftTiersAgainstTheFolder() {
        useFolderGolden()
        val web = DriftFixture.drift(drift, "web")
        assertEquals(goldenVf("roles/web"), web.reference?.dir)
        val tiers = web.copies.associate { (if (it.copy.isExternal) "golden" else it.copy.root.displayName) to it.tier }
        assertEquals(DriftTier.REFERENCE, tiers["golden"])
        assertEquals(DriftTier.IDENTICAL, tiers["same"])
        assertEquals(DriftTier.MOLECULE_ONLY, tiers["mol"])
        assertEquals(DriftTier.MOLECULE_ONLY, tiers["mol2"])
        assertEquals(DriftTier.SPEC_DEFAULTS, tiers["spec"])
        assertEquals(DriftTier.SPEC_DEFAULTS, tiers["specmol"])
        assertEquals(DriftTier.BEHAVIOUR, tiers["tasks"])
        assertEquals(DriftTier.BEHAVIOUR, tiers["missing"])
        assertEquals("the wording names the external golden root", "golden", web.goldenName)
    }

    fun testAFolderEditedOutsideTheIdeIsFollowed() {
        useFolderGolden()
        assertEquals(DriftTier.IDENTICAL, DriftFixture.drift(drift, "web").copyOf(localCopy("same").dir)!!.tier)
        assertTrue(drift.isCurrent("web"))
        Files.writeString(folder.resolve("roles/web/tasks/main.yml"), "- name: changed in the golden checkout\n  debug: msg=x\n")
        refresh()
        // Unlike a git mirror (changed only by a fetch), a folder's VFS events mark its copies dirty.
        DriftFixture.waitFor("the folder's edit marks the golden copy dirty") { !drift.isCurrent("web") }
        assertEquals(DriftTier.BEHAVIOUR, DriftFixture.drift(drift, "web").copyOf(localCopy("same").dir)!!.tier)
    }

    fun testTheCatalogFollowsTheResolutionThatComesLater() {
        val later = temp.resolve("later").resolve("golden")
        MirrorSettings.useFolder(project, later.toString())
        GoldenMirrorService.getInstance(project)?.let { service -> GoldenTestSupport.await { service.reconcileForTests() } }
        sync()
        assertNull("no folder yet: no golden root", RoleCatalog.getInstance(project).snapshot().golden)

        copyTree(folder, later)
        GoldenTestSupport.await { GoldenMirrorService.getInstance(project)!!.fetchNowAndWait(true) }
        sync()
        val catalog = RoleCatalog.getInstance(project).snapshot()
        assertNotNull("the resolution bumps the catalog's tracker", catalog.golden)
        assertTrue(catalog.copies("web").first().isExternal)
    }

    fun testNeverARootOfTheWorkspaceOrOfAScope() {
        useFolderGolden()
        val external = external()
        val file = goldenVf("roles/web/tasks/main.yml")
        runReadActionBlocking {
            val workspace = AnsibleWorkspace.getInstance(project)
            assertTrue("roots: ${workspace.roots().map { it.dir.path }}", workspace.roots().none { external.contains(it.dir) || it.dir == external.baseDir })
            assertNull("no resolution falls back to it (D199)", workspace.rootFor(file))
            assertNull(workspace.contextOf(file))
            val scope = WorkspaceScopeService.getInstance(project).current()
            assertFalse(scope.contains(file))
            assertFalse(scope.contains(goldenVf("roles/web")))
            assertTrue("no reference root outside the project", scope.references.isEmpty())
            assertTrue(scope.roots.none { it == external.root })
        }
    }

    fun testWritingIsDenied() {
        useFolderGolden()
        val file = goldenVf("roles/web/tasks/main.yml")
        val local = vf("${DriftFixture.roleDir("same")}/tasks/main.yml")
        assertFalse(WritingAccessProvider.isPotentiallyWritable(file, project))
        assertTrue(WritingAccessProvider.isPotentiallyWritable(local, project))
        val provider = WritingAccessProvider.EP.getExtensions(project).filterIsInstance<GoldenMirrorWritingAccessProvider>().single()
        assertEquals(listOf(file), provider.requestWriting(listOf(file, local)))
        val status = EdtTestUtil.runInEdtAndGet<ReadonlyStatusHandler.OperationStatus, Throwable> {
            ReadonlyStatusHandler.getInstance(project).ensureFilesWritable(listOf(file))
        }
        assertTrue("the platform refuses edits there", status.hasReadonlyFiles())
        assertTrue(ExternalGoldenRoot.getInstance(project).isUnder(folder.resolve("roles/new/file.yml")))
        assertFalse(ExternalGoldenRoot.getInstance(project).isUnder(local))
    }

    fun testTheWriterRefusesTheExternalGolden() {
        useFolderGolden()
        val dir = goldenVf("roles/web")
        val before = text(folder.resolve("roles/web/tasks/main.yml"))
        val file = goldenVf("roles/web/tasks/main.yml")
        val result = EdtTestUtil.runInEdtAndGet<WriteResult, Throwable> {
            RoleWriter.apply(project, dir, listOf(FileOp.Write("tasks/main.yml", OpContent.Text("- debug: msg=written\n"), expected = FileStamp.of(file))), "label")
        }
        assertEquals(WriteResult.Status.INVALID_TARGET, result.status)
        assertEquals(before, text(folder.resolve("roles/web/tasks/main.yml")))
    }

    fun testTheBannerSaysReadOnly() {
        useFolderGolden()
        val text = GoldenMirrorBanner.text(project, goldenVf("roles/web/tasks/main.yml"))
        assertNotNull(text)
        assertTrue(text, text!!.startsWith("Golden folder ") && text.endsWith(" — read-only here"))
        assertTrue(text, text.contains("outside/golden"))
        assertNull(GoldenMirrorBanner.text(project, vf("${DriftFixture.roleDir("same")}/tasks/main.yml")))
        assertNotNull(GoldenMirrorBannerProvider().collectNotificationData(project, goldenVf("roles/web/tasks/main.yml")))
        MirrorSettings.useNone(project)
        assertNull("without the setting, no banner", GoldenMirrorBanner.text(project, goldenVf("roles/web/tasks/main.yml")))
    }

    fun testWithoutTheSettingNothingIsExternal() {
        useFolderGolden()
        assertNotNull(RoleCatalog.getInstance(project).snapshot().external)
        AnsibilityProjectSettings.getInstance(project).update { it.copy(drift = it.drift.copy(golden = GoldenRoot.None)) }
        val catalog = RoleCatalog.getInstance(project).snapshot()
        assertNull(catalog.external)
        assertNull(catalog.golden)
        assertTrue(catalog.copies("web").none { it.isExternal })
        assertFalse(ExternalGoldenRoot.getInstance(project).isUnder(goldenVf("roles/web/tasks/main.yml")))
    }
}
