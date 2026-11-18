package de.terletzkiy.ansibility.vault.exclusion

import com.intellij.notification.Notification
import com.intellij.notification.Notifications
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.psi.impl.cache.impl.id.IdIndex
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.indexing.FileBasedIndex
import de.terletzkiy.ansibility.vault.VaultVectors
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * R30 review fixes (decisions 1–4 and 7) with real temporary content roots: exact-path entries keep out that file only
 * (also through a linked folder), a name never takes a folder, a vars or vault file or a script out of the project,
 * Rotate Vault Password's temporary file is built in, names follow the file system's case rule, the stale-data walk
 * survives link cycles, a content root added later gets the stale-data pass, and the Local History notice comes once
 * per project.
 */
class KeepOutRulesTest : BasePlatformTestCase() {
    private val roots = ArrayList<VirtualFile>()
    private val scopes = ArrayList<CoroutineScope>()
    private val settings: IndexExclusionSettings get() = IndexExclusionSettings.getInstance()
    private lateinit var rebuilds: FileSetRebuilds

    override fun setUp() {
        super.setUp()
        settings.resetForTests()
        rebuilds = FileSetRebuilds(project)
    }

    override fun tearDown() {
        try {
            PasswordFileReindex.automaticInTests = false
            PasswordFileReindexService.getInstance(project).gateForTests?.complete(Unit)
            PasswordFileReindexService.getInstance(project).gateForTests = null
            PlatformTestUtil.waitWithEventsDispatching("the stale-data pass ends", { PasswordFileReindexService.getInstance(project).isIdle() }, 60)
            scopes.forEach { it.cancel() }
            settings.resetForTests()
            rebuilds.close()
            roots.forEach { PsiTestUtil.removeContentEntry(module, it) }
            IndexingTestUtil.waitUntilIndexesAreReady(project)
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    /** A new folder with [files] and symbolic [links] (link → target, relative to the folder), added as a content root unless [add] is false. */
    private fun folder(name: String, files: Map<String, String>, links: Map<String, String> = emptyMap(), add: Boolean = true): VirtualFile {
        val dir: Path = FileUtil.createTempDirectory("r30f-$name", null, true).toPath().toRealPath()
        VfsRootAccess.allowRootAccess(testRootDisposable, dir.toString())
        files.forEach { (relative, text) ->
            val path = dir.resolve(relative)
            Files.createDirectories(path.parent)
            Files.writeString(path, text)
        }
        links.forEach { (link, target) ->
            val path = dir.resolve(link)
            Files.createDirectories(path.parent)
            Files.createSymbolicLink(path, dir.resolve(target))
        }
        val vdir = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(dir)!!
        vdir.refresh(false, true)
        if (add) addRoot(vdir)
        return vdir
    }

    private fun addRoot(dir: VirtualFile) {
        PsiTestUtil.addContentRoot(module, dir)
        if (dir !in roots) roots += dir
        IndexingTestUtil.waitUntilIndexesAreReady(project)
    }

    private fun excluded(file: VirtualFile): Boolean = runReadActionBlocking { ProjectFileIndex.getInstance(project).isExcluded(file) }

    private fun words(file: VirtualFile): Int = runReadActionBlocking { FileBasedIndex.getInstance().getFileData(IdIndex.NAME, file, project).size }

    private fun <T> await(block: suspend () -> T): T {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }
        val future = CompletableFuture<T>()
        scope.launch {
            try {
                future.complete(block())
            } catch (e: Throwable) {
                future.completeExceptionally(e)
            }
        }
        PlatformTestUtil.waitWithEventsDispatching("timed out", { future.isDone }, 60)
        return future.get(1, TimeUnit.SECONDS)
    }

    // ------------------------------------------------------------------------------------------------ decision 1

    fun testAnExactPathKeepsOutThatFileOnlyAlsoThroughALinkedFolder() {
        val dir = folder(
            "exact",
            mapOf(
                "secrets/team.pw" to "zqxwvexactone\n",
                "other/team.pw" to "zqxwvexacttwo\n",
                "team.pw/hosts.yml" to "all:\n  hosts:\n",
            ),
            links = mapOf("current" to "secrets"),
        )
        val file = dir.findFileByRelativePath("secrets/team.pw")!!
        assertEquals("a canonical absolute path", file.path, KeepOutRules.pathOf(file))
        settings.update(emptyList(), excludeEnvLocal = false, paths = listOf(KeepOutRules.pathOf(file)))
        rebuilds.rebuild()
        assertTrue("the file", excluded(file))
        assertTrue("the same file through a linked folder", excluded(dir.findFileByRelativePath("current/team.pw")!!))
        assertFalse("another file of that name", excluded(dir.findFileByRelativePath("other/team.pw")!!))
        assertFalse("a folder of that name", excluded(dir.findChild("team.pw")!!))
        assertFalse("and its files", excluded(dir.findFileByRelativePath("team.pw/hosts.yml")!!))
        assertTrue(settings.isKeptOut(file))
        assertFalse(settings.isKeptOut(dir.findFileByRelativePath("other/team.pw")!!))
    }

    fun testANameNeverTakesAVarsOrVaultFileAScriptOrAFolderOutOfTheProject() {
        val dir = folder(
            "guard",
            mapOf(
                "secrets/prod" to "zqxwvguardprod\n",
                "group_vars/prod" to "app_port: 8080\n",
                "inventories/prod/hosts.yml" to "all:\n",
                "roles/web/files/prod" to "plain\n",
                "secrets/team.key" to "zqxwvguardkey\n",
                "group_vars/all/vault.yml" to "db_password: x\n",
                "secrets/sealed.bin" to VaultVectors.encrypt("sealed\n", VaultVectors.PW1).format(),
                "bin/tool" to "#!/bin/sh\necho hi\n",
            ),
        )
        Files.setPosixFilePermissions(dir.findFileByRelativePath("bin/tool")!!.toNioPath(), PosixFilePermissions.fromString("rwxr-xr-x"))
        settings.update(listOf("prod", "*.key", "vault.yml", "sealed.bin", "tool"), excludeEnvLocal = false)
        rebuilds.rebuild()
        assertTrue("a plain password file of that name", excluded(dir.findFileByRelativePath("secrets/prod")!!))
        val kept = listOf("group_vars/prod", "inventories/prod", "inventories/prod/hosts.yml", "roles/web/files/prod", "secrets/team.key", "group_vars/all/vault.yml", "secrets/sealed.bin", "bin/tool")
            .filter { excluded(dir.findFileByRelativePath(it)!!) }
        assertEmpty("vars and vault files, folders and scripts stay in the project", kept)
        assertEquals(KeepOutGuard.Reason.VARS_OR_VAULT, KeepOutGuard.reasonNotToKeepOut(dir.findFileByRelativePath("secrets/sealed.bin")!!))
        assertEquals(KeepOutGuard.Reason.SCRIPT, KeepOutGuard.reasonNotToKeepOut(dir.findFileByRelativePath("bin/tool")!!))
    }

    fun testRotateVaultPasswordsTemporaryFileIsBuiltIn() {
        val dir = folder("temp", mapOf("secrets/.ansibility-rotate-123.tmp" to "zqxwvtempfile\n", "secrets/rotate.tmp" to "x\n"))
        assertTrue(excluded(dir.findFileByRelativePath("secrets/.ansibility-rotate-123.tmp")!!))
        assertEquals(0, words(dir.findFileByRelativePath("secrets/.ansibility-rotate-123.tmp")!!))
        assertFalse(excluded(dir.findFileByRelativePath("secrets/rotate.tmp")!!))
    }

    // ------------------------------------------------------------------------------------------------ decision 3

    fun testNamesFollowTheFileSystemsCaseRule() {
        val dir = folder("case", mapOf("secrets/TEAM.VaultPW" to "zqxwvcaseone\n", "secrets/Team-1.PW" to "zqxwvcasetwo\n"))
        settings.update(listOf("team.vaultpw", "team-?.pw"), excludeEnvLocal = false)
        rebuilds.rebuild()
        val insensitive = !dir.fileSystem.isCaseSensitive
        assertEquals(insensitive, excluded(dir.findFileByRelativePath("secrets/TEAM.VaultPW")!!))
        assertEquals(insensitive, excluded(dir.findFileByRelativePath("secrets/Team-1.PW")!!))
        assertEquals("the same matcher in the walk", insensitive, dir.findFileByRelativePath("secrets/TEAM.VaultPW")!! in runReadActionBlocking { PasswordFileReindex.excludedFiles(project, settings.patterns()) })
        assertEquals(!SystemInfo.isFileSystemCaseSensitive, settings.isKeptOut("TEAM.VAULTPW"))
    }

    // ------------------------------------------------------------------------------------------------ decision 2

    fun testTheWalkEndsOnLinkCycles() {
        val dir = folder(
            "cycles",
            mapOf("y/team.pw" to "zqxwvcycle\n", "x/note.txt" to "x\n"),
            links = mapOf("loop" to ".", "x/up" to "x/..", "x/to_y" to "y", "y/to_x" to "x"),
        )
        settings.update(listOf("team.pw"), excludeEnvLocal = false)
        rebuilds.rebuild()
        val found = runReadActionBlocking { PasswordFileReindex.excludedFiles(project, settings.patterns()) }.filter { VfsUtilCoreRelative.of(dir, it) != null }
        val relative = found.map { VfsUtilCoreRelative.of(dir, it)!! }.sorted()
        assertTrue(relative.toString(), "y/team.pw" in relative && "x/to_y/team.pw" in relative)
        assertTrue("each link followed once on a path: $relative", relative.none { it.contains("to_x/to_y/to_x") })
    }

    fun testAContentRootAddedLaterGetsTheStaleDataPass() {
        val dir = folder("late", mapOf("secrets/late.vaultpw" to "zqxwvlateroot\n"), add = false)
        val file = dir.findFileByRelativePath("secrets/late.vaultpw")!!
        addRoot(dir)
        assertTrue("indexed while its name was not listed", words(file) > 0)
        PsiTestUtil.removeContentEntry(module, dir)
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        settings.update(listOf("*.vaultpw"), excludeEnvLocal = false)

        val service = PasswordFileReindexService.getInstance(project)
        val gate = CompletableDeferred<Unit>()
        service.gateForTests = gate
        PasswordFileReindex.automaticInTests = true
        service.rootsChanged(runReadActionBlocking { ProjectRootManager.getInstance(project).contentRootUrls.toSet() })
        addRoot(dir)
        assertTrue("excluded now", excluded(file))
        assertTrue("precondition: the data from before is still there", words(file) > 0)

        gate.complete(Unit)
        PlatformTestUtil.waitWithEventsDispatching("the pass of the new content root", { service.isIdle() }, 60)
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        assertEquals("the new content root's stale data is gone", 0, words(file))
    }

    // ------------------------------------------------------------------------------------------------ decision 7

    fun testTheLocalHistoryNoticeComesOncePerProject() {
        val shown = CopyOnWriteArrayList<Notification>()
        project.messageBus.connect(testRootDisposable).subscribe(Notifications.TOPIC, object : Notifications {
            override fun notify(notification: Notification) {
                shown += notification
            }
        })
        folder("history", mapOf(".vault-pass" to "zqxwvhistory\n", "notes.txt" to "x\n"))
        await { PasswordFileReindex.runIfDue(project) }
        val notice = shown.filter { it.displayId == LocalHistoryNotice.DISPLAY_ID }
        assertEquals(1, notice.size)
        assertTrue(notice.single().content, notice.single().content.contains(".vault-pass") && notice.single().content.contains("Clear Local History"))
        assertTrue("nothing is cleared for you: no action", notice.single().actions.isEmpty())

        await { PasswordFileReindex.runIfDue(project) }
        assertEquals("once per project", 1, shown.count { it.displayId == LocalHistoryNotice.DISPLAY_ID })
    }

    /** `VfsUtilCore.getRelativePath`, null outside [base]. */
    private object VfsUtilCoreRelative {
        fun of(base: VirtualFile, file: VirtualFile): String? = com.intellij.openapi.vfs.VfsUtilCore.getRelativePath(file, base, '/')
    }
}
