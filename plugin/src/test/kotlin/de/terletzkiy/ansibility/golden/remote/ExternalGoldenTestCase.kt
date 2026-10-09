package de.terletzkiy.ansibility.golden.remote

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.util.io.NioFiles
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.registerOrReplaceServiceInstance
import de.terletzkiy.ansibility.api.WorkspaceScopeService
import de.terletzkiy.ansibility.context.AnsibleWorkspaceImpl
import de.terletzkiy.ansibility.golden.GoldenTestSupport
import de.terletzkiy.ansibility.model.drift.DriftFixture
import de.terletzkiy.ansibility.model.drift.RoleDriftService
import de.terletzkiy.ansibility.model.role.ExternalGolden
import de.terletzkiy.ansibility.model.role.ExternalGoldenRoot
import de.terletzkiy.ansibility.model.role.ModelFixture
import de.terletzkiy.ansibility.model.role.RoleCatalog
import de.terletzkiy.ansibility.model.role.RoleCopy
import de.terletzkiy.ansibility.settings.SettingsTestSupport
import de.terletzkiy.ansibility.workspace.WorkspaceScopeServiceImpl
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.isDirectory

/**
 * Base of the tests of the external golden root (plan amendment R25 step 2) on real files: the synthetic drift tree's
 * repos (`testData/toolwindow/drift/repos`, seven copies of `web`) as the project (a content root on the local file
 * system), and, outside the project, a copy of its `golden/` (roles `web` and `base`) as the folder golden root
 * ([useFolderGolden], X125) or a git repository for the mirror ([mirror]). Drift runs on a fresh service of its own.
 */
abstract class ExternalGoldenTestCase : BasePlatformTestCase() {
    protected lateinit var temp: Path
    protected lateinit var projectDir: Path
    protected lateinit var drift: RoleDriftService
    private var contentRoot: VirtualFile? = null

    /** The folder golden root ([useFolderGolden]): a copy of the drift tree's `golden/`. */
    protected val folder: Path get() = temp.resolve("outside").resolve("golden")

    override fun setUp() {
        super.setUp()
        temp = FileUtil.createTempDirectory("ansibility-external-golden", null, true).toPath().toRealPath()
        VfsRootAccess.allowRootAccess(testRootDisposable, temp.toString())
        projectDir = temp.resolve("project")
        copyTree(Paths.get(ModelFixture.testDataPath, DriftFixture.DRIFT, "repos"), projectDir.resolve("repos"))
        copyTree(Paths.get(ModelFixture.testDataPath, DriftFixture.DRIFT, "golden"), folder)
        ExternalGoldenRoot.getInstance(project).resetForTests()
        GoldenMovedNotifier.getInstance(project).resetForTests()
        GoldenMirrorConsents.getInstance().resetForTests()
        drift = DriftFixture.freshService(project, testRootDisposable)
        project.registerOrReplaceServiceInstance(RoleDriftService::class.java, drift, testRootDisposable)
        refresh()
    }

    override fun tearDown() {
        try {
            FileDocumentManager.getInstance().saveAllDocuments()
            SettingsTestSupport.resetAll(project)
            GoldenMirrorService.getInstance(project)?.let { service ->
                GoldenTestSupport.await { service.reconcileForTests() }
                service.resetForTests()
            }
            GoldenTestSupport.await { ExternalGoldenRoot.getInstance(project).syncForTests() }
            ExternalGoldenRoot.getInstance(project).resetForTests()
            GoldenMovedNotifier.getInstance(project).resetForTests()
            GoldenMirrorConsents.getInstance().resetForTests()
            (WorkspaceScopeService.getInstance(project) as? WorkspaceScopeServiceImpl)?.resetForTests()
            contentRoot?.let { PsiTestUtil.removeContentEntry(module, it) }
            AnsibleWorkspaceImpl.getInstance(project)?.structureChanged()
            NioFiles.deleteRecursively(temp)
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    /** Refreshes the VFS below the temp directory, adds the project directory as a content root once, rescans the roots. */
    protected fun refresh() {
        GoldenTestSupport.fsync()
        val dir = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(temp) ?: error("no VFS dir for $temp")
        VfsUtil.markDirtyAndRefresh(false, true, true, dir)
        if (contentRoot == null) {
            val projectRoot = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(projectDir) ?: error("no project dir")
            PsiTestUtil.addContentRoot(module, projectRoot)
            contentRoot = projectRoot
        }
        AnsibleWorkspaceImpl.getInstance(project)!!.structureChanged()
    }

    /** Makes [folder] the golden root (X125) and waits until the catalog has it. */
    protected fun useFolderGolden(path: Path = folder) {
        MirrorSettings.useFolder(project, path.toString())
        GoldenMirrorService.getInstance(project)?.let { service -> GoldenTestSupport.await { service.reconcileForTests() } }
        sync()
    }

    /** Resolves the external golden root now (as after a state change) and waits. */
    protected fun sync() {
        GoldenTestSupport.await { ExternalGoldenRoot.getInstance(project).syncForTests() }
    }

    protected fun external(): ExternalGolden = RoleCatalog.getInstance(project).snapshot().external ?: error("no external golden root in the catalog")

    /** The VFS file at [relative] below the project directory. */
    protected fun vf(relative: String): VirtualFile =
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(projectDir.resolve(relative)) ?: error("no file $relative")

    /** The VFS file at [relative] below the external golden folder. */
    protected fun goldenVf(relative: String, base: Path = folder): VirtualFile =
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(base.resolve(relative)) ?: error("no golden file $relative")

    /** The copy of [role] in the repo [team] of the project. */
    protected fun localCopy(team: String, role: String = "web"): RoleCopy =
        GoldenTestSupport.copy(project, vf(DriftFixture.roleDir(team, role)))

    /** The external golden root's copy of [role]. */
    protected fun goldenCopy(role: String = "web"): RoleCopy =
        RoleCatalog.getInstance(project).snapshot().copies(role).firstOrNull { it.isExternal } ?: error("no external copy of $role")

    protected fun text(path: Path): String {
        GoldenTestSupport.fsync()
        return Files.readString(path)
    }

    protected fun copyTree(from: Path, to: Path) {
        Files.walk(from).use { paths ->
            paths.forEach { source ->
                val target = to.resolve(from.relativize(source).toString())
                if (source.isDirectory()) Files.createDirectories(target) else {
                    Files.createDirectories(target.parent)
                    Files.copy(source, target)
                }
            }
        }
    }
}
