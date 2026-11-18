package de.terletzkiy.ansibility.vault.exclusion

import com.intellij.find.ngrams.TrigramIndex
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.openapi.vfs.newvfs.persistent.PersistentFS
import com.intellij.openapi.vfs.newvfs.persistent.PersistentFSImpl
import com.intellij.psi.impl.cache.impl.id.IdIndex
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.indexing.FileBasedIndex
import de.terletzkiy.ansibility.vault.envelope.VaultEnvelopes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * R30 review (adopted as product tests by the R30 review fixes): password files that still keep index data or end up in
 * a cache, and legitimate files the name patterns excluded. Same measurements as `PasswordFileExclusionTest` (real
 * temporary content roots, sentinel words).
 */
class PasswordFileExclusionRegressionTest : BasePlatformTestCase() {
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
            scopes.forEach { it.cancel() }
            settings.resetForTests()
            rebuilds.close()
            roots.forEach { PsiTestUtil.removeContentEntry(module, it) }
            IndexingTestUtil.waitUntilIndexesAreReady(project)
            PasswordFileReindex.maxVisits = 1_000_000
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    private fun root(name: String, files: Map<String, String>, links: Map<String, String> = emptyMap()): Map<String, VirtualFile> {
        val dir: Path = FileUtil.createTempDirectory("r30-review-$name", null, true).toPath().toRealPath()
        VfsRootAccess.allowRootAccess(testRootDisposable, dir.toString())
        files.forEach { (relative, text) ->
            val path = dir.resolve(relative)
            Files.createDirectories(path.parent)
            Files.writeString(path, text)
        }
        links.forEach { (link, target) -> Files.createSymbolicLink(dir.resolve(link), dir.resolve(target)) }
        val vdir = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(dir)!!
        vdir.refresh(false, true)
        PsiTestUtil.addContentRoot(module, vdir)
        roots += vdir
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        return files.keys.associateWith { vdir.findFileByRelativePath(it)!! }
    }

    private fun words(file: VirtualFile): Int = runReadActionBlocking { FileBasedIndex.getInstance().getFileData(IdIndex.NAME, file, project).size }

    private fun trigrams(file: VirtualFile): Int = runReadActionBlocking { FileBasedIndex.getInstance().getFileData(TrigramIndex.INDEX_ID, file, project).size }

    private fun excluded(file: VirtualFile): Boolean = runReadActionBlocking { ProjectFileIndex.getInstance(project).isExcluded(file) }

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

    /**
     * Finding: a name added for a password file (by hand, by the page's Add or by the tab's Keep Out) is an exclusion
     * pattern the platform also matches against every folder name and every other file of that name, so the folders and
     * files of an inventory named like a password file leave the project.
     */
    fun testAPasswordFileNameDoesNotExcludeFoldersOrOtherFilesOfThatName() {
        val files = root(
            "folders",
            mapOf(
                "secrets/prod" to "zqxwvreviewprod\n",
                "inventories/prod/hosts.yml" to "all:\n  hosts:\n    web1:\n",
                "inventories/prod/group_vars/all/vault.yml" to "db_password: x\n",
                "group_vars/prod/vars.yml" to "app_port: 8080\n",
            ),
        )
        settings.update(listOf("prod"), excludeEnvLocal = false)
        rebuilds.rebuild()
        assertTrue("the password file is kept out", excluded(files.getValue("secrets/prod")))
        val collateral = listOf("inventories/prod/hosts.yml", "inventories/prod/group_vars/all/vault.yml", "group_vars/prod/vars.yml")
            .filter { excluded(files.getValue(it)) }
        assertEmpty("legitimate Ansible files excluded because a folder above them is named like the password file", collateral)
    }

    /**
     * Finding: the walk stops at its budget, then marks the project re-indexed for the list anyway, so "the rest waits
     * for later" never happens: stale index data past the budget stays until the list changes.
     */
    fun testAWalkCutShortByItsBudgetIsDueAgain() {
        val files = LinkedHashMap<String, String>()
        for (i in 0 until 20) files["a%02d.txt".format(i)] = "filler $i\n"
        files["zz/secrets/stale.pw"] = "zqxwvreviewstale\n"
        val stale = root("budget", files).getValue("zz/secrets/stale.pw")
        assertTrue("indexed like any file", words(stale) > 0)

        settings.update(listOf("stale.pw"), excludeEnvLocal = false)
        rebuilds.rebuild()
        assertTrue(excluded(stale))
        PasswordFileReindex.maxVisits = 5
        await { PasswordFileReindex.runIfDue(project) }
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        val leftOver = words(stale) > 0
        PasswordFileReindex.maxVisits = 1_000_000
        val next = await { PasswordFileReindex.runIfDue(project) }
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        assertTrue("precondition: the first walk stopped before the stale file", leftOver)
        assertNotNull("the next start must walk the rest (\"the rest waits for later\")", next)
        assertEquals("stale word data is gone", 0, words(stale))
    }

    /**
     * Finding: the walk does not enter symbolic links to folders, but the platform indexes the files below them (a
     * separate VFS file per path), so the stale data of a password file reached through a linked folder stays.
     */
    fun testAPasswordFileBelowALinkedFolderLosesItsStaleData() {
        val files = root("links", mapOf("shared/env/team.pw" to "zqxwvreviewlinked\n", "notes.txt" to "x\n"), links = mapOf("current" to "shared/env"))
        val vroot = roots.last()
        val linked = vroot.findFileByRelativePath("current/team.pw")!!
        assertTrue("precondition: the platform indexed the file through the link", words(linked) > 0)

        settings.update(listOf("team.pw"), excludeEnvLocal = false)
        rebuilds.rebuild()
        assertTrue(excluded(linked) && excluded(files.getValue("shared/env/team.pw")))
        await { PasswordFileReindex.runIfDue(project) }
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        assertEquals("the real path lost its data", 0, words(files.getValue("shared/env/team.pw")))
        assertEquals("the linked path keeps word data from before", 0, words(linked))
        assertEquals("the linked path keeps trigram data from before", 0, trigrams(linked))
    }

    /**
     * Finding: the platform turns `*.pw` into an extension matcher, which ignores case, while [PasswordFileNames.matches]
     * is case-sensitive: the file is excluded but the re-index walk (and the tab's "listed" check) do not see it.
     */
    fun testTheWalkMatchesNamesAsThePlatformDoes() {
        val upper = root("case", mapOf("secrets/TEAM.PW" to "zqxwvreviewupper\n")).getValue("secrets/TEAM.PW")
        assertTrue(words(upper) > 0)
        settings.update(listOf("*.pw"), excludeEnvLocal = false)
        rebuilds.rebuild()
        assertTrue("the platform keeps TEAM.PW out for *.pw", excluded(upper))
        assertTrue("so the list must say it keeps it out", settings.isKeptOut("TEAM.PW"))
        assertTrue("and the walk must hand it to the platform", upper in runReadActionBlocking { PasswordFileReindex.excludedFiles(project, settings.patterns()) })
    }

    /**
     * Finding (macOS): vault discovery finds `.Vault-Pass` as the conventional `.vault-pass` (java.nio on a
     * case-insensitive file system), but the built-in exact pattern is case-sensitive, so the password file is indexed.
     */
    fun testAConventionalPasswordFileInAnotherCaseIsKeptOut() {
        if (SystemInfo.isFileSystemCaseSensitive) return
        val file = root("mixed", mapOf("repo/.Vault-Pass" to "zqxwvreviewmixed\n")).getValue("repo/.Vault-Pass")
        assertTrue("precondition: java.nio sees it as .vault-pass", Files.exists(file.toNioPath().parent.resolve(".vault-pass")))
        assertTrue("the password file Ansible and discovery read is kept out", excluded(file))
        assertEquals(0, words(file))
    }

    /**
     * Finding: the Vault tab (`SecretSnapshotBuilder.passwordFiles`) and the commit check call
     * `VaultEnvelopes.isWholeFileVault` on every plaintext password file, which reads it through the VFS; the platform
     * then keeps the content in its persistent VFS content storage, a copy outside the indexes.
     */
    fun testTheVaultMagicCheckDoesNotCopyAPasswordFileIntoTheVfsContentStorage() {
        // 14 bytes: the length of the vault magic, the shortest file the check reads.
        val file = root("magic", mapOf(".vault-pass" to "zqxwvreview14\n")).getValue(".vault-pass")
        val fs = PersistentFS.getInstance() as PersistentFSImpl
        assertTrue(excluded(file))
        assertEquals("precondition: nothing stored yet", 0, fs.getCurrentContentId(file))
        assertFalse(runReadActionBlocking { VaultEnvelopes.isWholeFileVault(file) })
        assertEquals("no copy of the password in the VFS content storage", 0, fs.getCurrentContentId(file))
    }

    /**
     * Finding: "once per project and list version" cannot see index data that came while the exclusion was not active
     * (the plugin disabled for a session, safe mode, an older plugin version): when the plugin is back with the same
     * list, the fingerprint matches and the password file keeps that data.
     */
    fun testDataIndexedWhileThePluginWasOffIsDroppedWhenItIsBack() {
        val file = root("off", mapOf(".vault-pass" to "zqxwvreviewoff\n")).getValue(".vault-pass")
        assertNotNull("first start with R30", await { PasswordFileReindex.runIfDue(project) })

        // A session without the plugin: no exclusion, the platform indexes the file.
        val off = com.intellij.openapi.util.Disposer.newDisposable("plugin off")
        val ep = com.intellij.openapi.extensions.ExtensionPointName<com.intellij.workspaceModel.core.fileIndex.WorkspaceFileIndexContributor<*>>(
            "com.intellij.workspaceModel.fileIndexContributor",
        )
        com.intellij.testFramework.ExtensionTestUtil.maskExtensions(ep, ep.extensionList.filter { it !is PasswordFileExcluder }, off)
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        assertFalse(excluded(file))
        FileBasedIndex.getInstance().requestReindex(file)
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        assertTrue("precondition: indexed while the plugin was off", words(file) > 0)

        // The plugin is back, same list.
        com.intellij.openapi.util.Disposer.dispose(off)
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        assertTrue(excluded(file))
        await { PasswordFileReindex.runIfDue(project) }
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        assertEquals("the password file's word data from the session without the plugin is gone", 0, words(file))
    }

    /**
     * Finding (D224 "Password scripts are not excluded"): the built-in names are excluded whatever the file is, so a
     * conventional `.vault-pass` that is an executable password script (code, no secret) leaves the project too.
     */
    fun testAPasswordScriptWithAConventionalNameIsNotExcluded() {
        val script = root("script", mapOf("repo/.vault-pass" to "#!/bin/sh\nexec pass show team/ansible\n")).getValue("repo/.vault-pass")
        Files.setPosixFilePermissions(script.toNioPath(), java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"))
        assertFalse("D224: password scripts are not excluded", excluded(script))
    }

    fun testTheVaultMagicCheckOfALongerPasswordFile() {
        val file = root("magic-long", mapOf(".vault-pass" to "zqxwvreviewlongerpassword0123456789\n")).getValue(".vault-pass")
        val fs = PersistentFS.getInstance() as PersistentFSImpl
        assertEquals("precondition: nothing stored yet", 0, fs.getCurrentContentId(file))
        assertFalse(runReadActionBlocking { VaultEnvelopes.isWholeFileVault(file) })
        assertEquals("no copy of the password in the VFS content storage", 0, fs.getCurrentContentId(file))
    }
}
