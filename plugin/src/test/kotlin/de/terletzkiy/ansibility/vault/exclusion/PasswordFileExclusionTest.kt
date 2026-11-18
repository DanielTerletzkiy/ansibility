package de.terletzkiy.ansibility.vault.exclusion

import com.intellij.find.ngrams.TrigramIndex
import com.intellij.history.LocalHistory
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.psi.impl.cache.impl.id.IdIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiSearchHelper
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.indexing.FileBasedIndex
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
 * Plan amendment R30 (D223–D226, D228): [PasswordFileExcluder] keeps vault password files out of the IDE's indexes. Each
 * test adds real temporary folders as content roots (as a project opening them does), with synthetic sentinel words as
 * "passwords", and measures what the platform keeps: `isExcluded`, `isInContent`, the word index (`IdIndex`) and the
 * trigram index (Find in Files) data of the file, a word search, and Local History.
 */
class PasswordFileExclusionTest : BasePlatformTestCase() {
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

    /** A new content root with [files] (relative path → text), indexed. */
    private fun root(name: String, files: Map<String, String>): Map<String, VirtualFile> {
        val dir: Path = FileUtil.createTempDirectory("r30-$name", null, true).toPath().toRealPath()
        VfsRootAccess.allowRootAccess(testRootDisposable, dir.toString())
        files.forEach { (relative, text) ->
            val path = dir.resolve(relative)
            Files.createDirectories(path.parent)
            Files.writeString(path, text)
        }
        val vdir = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(dir)!!
        vdir.refresh(false, true)
        PsiTestUtil.addContentRoot(module, vdir)
        roots += vdir
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        return files.keys.associateWith { vdir.findFileByRelativePath(it)!! }
    }

    /** What the IDE keeps of [file]: index data, word hits for [word], content and Local History. */
    private class Kept(val excluded: Boolean, val inContent: Boolean, val words: Int, val trigrams: Int, val hits: Int, val localHistory: Boolean) {
        val nothing: Boolean get() = excluded && !inContent && words == 0 && trigrams == 0 && hits == 0 && !localHistory

        override fun toString(): String = "excluded=$excluded inContent=$inContent words=$words trigrams=$trigrams hits=$hits localHistory=$localHistory"
    }

    private fun kept(file: VirtualFile, word: String): Kept {
        val index = FileBasedIndex.getInstance()
        val fileIndex = ProjectFileIndex.getInstance(project)
        return runReadActionBlocking {
            var hits = 0
            val search = PsiSearchHelper.getInstance(project)
            val scope = GlobalSearchScope.everythingScope(project)
            search.processAllFilesWithWord(word, scope, { hits++; true }, true)
            search.processAllFilesWithWordInText(word, scope, { hits++; true }, true)
            Kept(
                fileIndex.isExcluded(file),
                fileIndex.isInContent(file),
                index.getFileData(IdIndex.NAME, file, project).size,
                index.getFileData(TrigramIndex.INDEX_ID, file, project).size,
                hits,
                LocalHistory.getInstance().isUnderControl(file),
            )
        }
    }

    /** Makes the platform build the file sets of every content root again (as a project opening does). */
    private fun rebuildFileSets() = rebuilds.rebuild()

    /** Runs [block] on a background thread while the EDT dispatches events; rethrows its exception. */
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

    // ------------------------------------------------------------------------------------------------ D223, D224

    fun testTheFourBuiltInNamesAreKeptOutOfEveryIndexAndLocalHistory() {
        val files = root(
            "builtin",
            mapOf(
                ".vault-pass" to "zqxwvsentinelalpha\n",
                ".vault_pass" to "zqxwvsentinelbravo\n",
                ".vault-password" to "zqxwvsentinelcharlie\n",
                ".vault_password" to "zqxwvsentineldelta\n",
                "repos/falcon/ansible/.vault-pass" to "zqxwvsentinelecho\n",
                "notes.txt" to "zqxwvsentinelfoxtrot\n",
            ),
        )
        assertEquals(PasswordFileNames.BUILT_IN, listOf(".vault-pass", ".vault_pass", ".vault-password", ".vault_password"))
        val words = mapOf(
            ".vault-pass" to "zqxwvsentinelalpha",
            ".vault_pass" to "zqxwvsentinelbravo",
            ".vault-password" to "zqxwvsentinelcharlie",
            ".vault_password" to "zqxwvsentineldelta",
            "repos/falcon/ansible/.vault-pass" to "zqxwvsentinelecho",
        )
        for ((path, word) in words) {
            val kept = kept(files.getValue(path), word)
            assertTrue("$path: $kept", kept.nothing)
        }
        val control = kept(files.getValue("notes.txt"), "zqxwvsentinelfoxtrot")
        assertTrue("the measurement sees an ordinary file: $control", !control.excluded && control.inContent && control.words > 0 && control.trigrams > 0 && control.hits == 1)
    }

    fun testAnExcludedPasswordFileStaysOutAfterItChangesOnDisk() {
        val file = root("changed", mapOf(".vault-pass" to "zqxwvsentinelgolf\n")).getValue(".vault-pass")
        Files.writeString(file.toNioPath(), "zqxwvsentinelhotelhotel\n")
        file.parent.refresh(false, true)
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        assertEquals("zqxwvsentinelhotelhotel\n", String(file.contentsToByteArray(), Charsets.UTF_8))
        assertTrue(kept(file, "zqxwvsentinelhotelhotel").nothing)
    }

    fun testAnExtraNameAppliesWhenAContentRootIsSetUpAgain() {
        val before = root("before", mapOf("team.vaultpw" to "zqxwvsentinelindia\n")).getValue("team.vaultpw")
        settings.update(listOf("*.vaultpw"), excludeEnvLocal = false)
        assertTrue(settings.isKeptOut("team.vaultpw"))
        assertFalse("D225: a root set up before the change keeps its file sets until the project opens again", kept(before, "zqxwvsentinelindia").excluded)

        val after = root("after", mapOf("ops.vaultpw" to "zqxwvsentineljuliett\n", "ops.vaultpw.txt" to "zqxwvsentinelkilo\n")).getValue("ops.vaultpw")
        assertTrue("a new content root takes the list as it is now", kept(after, "zqxwvsentineljuliett").nothing)
        assertFalse("a pattern is a whole name", kept(roots.last().findChild("ops.vaultpw.txt")!!, "zqxwvsentinelkilo").excluded)

        rebuildFileSets()
        assertTrue("reopening sets every root up again", kept(before, "zqxwvsentinelindia").excluded)
    }

    fun testEnvLocalOnlyWithTheCheckbox() {
        val plain = root("env-off", mapOf(".env.local" to "ANSIBLE_LOCAL_VAULT_PASSWORD_FILE=.vault-pass\n")).getValue(".env.local")
        assertFalse("D228: off by default", kept(plain, "ANSIBLE_LOCAL_VAULT_PASSWORD_FILE").excluded)
        assertFalse(settings.isKeptOut(".env.local"))

        settings.update(emptyList(), excludeEnvLocal = true)
        assertTrue(".env.local" in settings.patterns())
        val ticked = root("env-on", mapOf(".env.local" to "ANSIBLE_LOCAL_VAULT_PASSWORD_FILE=.vault-pass\n")).getValue(".env.local")
        assertTrue(kept(ticked, "ANSIBLE_LOCAL_VAULT_PASSWORD_FILE").excluded)
        assertFalse("never .env.local.skel", settings.isKeptOut(".env.local.skel"))
    }

    // ------------------------------------------------------------------------------------------------ D226

    fun testRequestReindexDropsTheIndexDataOfAPasswordFileIndexedBefore() {
        val stale = root("stale", mapOf("secrets/stale.pw" to "zqxwvsentinellima\n", "notes.txt" to "zqxwvsentinelmike\n")).getValue("secrets/stale.pw")
        assertTrue("indexed like any file", kept(stale, "zqxwvsentinellima").words > 0)

        settings.update(listOf("stale.pw"), excludeEnvLocal = false)
        rebuildFileSets()
        val before = kept(stale, "zqxwvsentinellima")
        assertTrue("excluded now", before.excluded && !before.inContent && !before.localHistory)
        assertTrue("but the data indexed before is still there: $before", before.words > 0 && before.trigrams > 0)

        assertEquals(listOf(stale), runReadActionBlocking { PasswordFileReindex.excludedFiles(project, settings.patterns()) }.filter { it == stale })
        assertEquals("handed to the platform once", 1, await { PasswordFileReindex.runIfDue(project) })
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        val after = kept(stale, "zqxwvsentinellima")
        assertTrue("requestReindex drops the word and trigram data and adds none: $after", after.nothing)
        assertTrue("other files keep theirs", kept(roots.last().findChild("notes.txt")!!, "zqxwvsentinelmike").words > 0)
    }

    fun testTheReindexRunsOncePerProjectAndListVersion() {
        root("once", mapOf(".vault-pass" to "zqxwvsentinelnovember\n", "group_vars/all.yml" to "a: 1\n"))
        val key = PasswordFileReindex.projectKey(project)
        assertNull(settings.reindexedFingerprint(key))
        val first = await { PasswordFileReindex.runIfDue(project) }
        assertTrue("the excluded .vault-pass at least: $first", first != null && first >= 1)
        assertEquals(settings.fingerprint(), settings.reindexedFingerprint(key))
        assertNull("not again for the same list", await { PasswordFileReindex.runIfDue(project) })

        settings.update(listOf("*.vaultpw"), excludeEnvLocal = false)
        assertNotNull("a changed list is due again (at the next start)", await { PasswordFileReindex.runIfDue(project) })
        assertNull(await { PasswordFileReindex.runIfDue(project) })
    }

    fun testTheWalkFindsOnlyExcludedPasswordFilesAndKeepsToItsBudget() {
        val files = root(
            "walk",
            mapOf(
                ".vault-pass" to "x\n",
                "deep/er/.vault_password" to "x\n",
                "roles/web/tasks/main.yml" to "- debug: msg=hi\n",
                "vault-client.py" to "print('x')\n",
            ),
        )
        val found = runReadActionBlocking { PasswordFileReindex.excludedFiles(project, settings.patterns()) }
        assertTrue(found.containsAll(listOf(files.getValue(".vault-pass"), files.getValue("deep/er/.vault_password"))))
        assertFalse(files.getValue("roles/web/tasks/main.yml") in found)
        assertFalse("a script is no password file name", files.getValue("vault-client.py") in found)

        PasswordFileReindex.maxVisits = 1
        assertTrue("stops at the budget", runReadActionBlocking { PasswordFileReindex.excludedFiles(project, settings.patterns()) }.size <= 1)
    }

    // ------------------------------------------------------------------------------------------------ settings

    fun testNamesAreFileNamesOnly() {
        assertNull(PasswordFileNames.problemOf("team.vaultpw"))
        assertNull(PasswordFileNames.problemOf("*.vault-pass"))
        assertNull(PasswordFileNames.problemOf("vault?.pw"))
        assertEquals(PasswordFileNames.Problem.EMPTY, PasswordFileNames.problemOf("  "))
        assertEquals(PasswordFileNames.Problem.PATH, PasswordFileNames.problemOf("secrets/dev.pw"))
        assertEquals(PasswordFileNames.Problem.PATH, PasswordFileNames.problemOf("secrets\\dev.pw"))
        assertEquals(PasswordFileNames.Problem.DOTS, PasswordFileNames.problemOf(".."))
        assertEquals(PasswordFileNames.Problem.DOTS, PasswordFileNames.problemOf("a..b"))
        assertEquals(PasswordFileNames.Problem.TOO_BROAD, PasswordFileNames.problemOf("*"))
        assertEquals(PasswordFileNames.Problem.TOO_BROAD, PasswordFileNames.problemOf("*.*"))
        assertEquals(PasswordFileNames.Problem.TOO_BROAD, PasswordFileNames.problemOf(".*"))
        assertEquals(PasswordFileNames.Problem.CHARACTER, PasswordFileNames.problemOf("a;b"))
        assertTrue(PasswordFileNames.matches("*.vaultpw", "team.vaultpw"))
        assertTrue(PasswordFileNames.matches("vault?.pw", "vault1.pw"))
        assertFalse(PasswordFileNames.matches("vault?.pw", "vault12.pw"))
        assertFalse("literal dots", PasswordFileNames.matches("a.b", "axb"))
        assertFalse("case-sensitive", PasswordFileNames.matches(".vault-pass", ".VAULT-PASS"))
    }

    fun testSettingsSurviveARoundTripAndDropInvalidNames() {
        settings.update(listOf(" team.vaultpw ", "", "secrets/dev.pw", "*", ".vault-pass", "team.vaultpw", "ops-*.pw"), excludeEnvLocal = true)
        assertEquals("trimmed, valid, no built-in name, no duplicate", listOf("team.vaultpw", "ops-*.pw"), settings.extraNames)
        assertEquals(PasswordFileNames.BUILT_IN + listOf("team.vaultpw", "ops-*.pw", ".env.local"), settings.patterns())
        settings.markReindexed("/work/infra", settings.fingerprint())

        val loaded = IndexExclusionSettings()
        loaded.loadState(com.intellij.util.xmlb.XmlSerializer.deserialize(com.intellij.configurationStore.serialize(settings.state)!!, IndexExclusionSettings.StateBean::class.java))
        assertEquals(settings.extraNames, loaded.extraNames)
        assertTrue(loaded.excludeEnvLocal)
        assertEquals(settings.fingerprint(), loaded.fingerprint())
        assertEquals(settings.fingerprint(), loaded.reindexedFingerprint("/work/infra"))

        val planted = IndexExclusionSettings.StateBean().apply { extraNames = mutableListOf("../x", "*.*", "ok.pw") }
        loaded.loadState(planted)
        assertEquals("invalid names in the file are ignored", listOf("ok.pw"), loaded.extraNames)
        assertFalse(loaded.excludeEnvLocal)
        assertFalse("the fingerprint follows the list", settings.fingerprint() == loaded.fingerprint())

        assertEquals(listOf("more.pw"), settings.addNames(listOf("more.pw", "team.vaultpw", ".vault_pass", "bad/name")))
        assertEquals(listOf("team.vaultpw", "ops-*.pw", "more.pw"), settings.extraNames)
    }

    fun testAChangeIsAnnouncedOnTheApplicationBus() {
        var events = 0
        com.intellij.openapi.application.ApplicationManager.getApplication().messageBus.connect(testRootDisposable)
            .subscribe(IndexExclusionListener.TOPIC, IndexExclusionListener { events++ })
        settings.update(listOf("a.pw"), excludeEnvLocal = false)
        settings.update(listOf("a.pw"), excludeEnvLocal = false)
        assertEquals("only real changes", 1, events)
        settings.update(listOf("a.pw"), excludeEnvLocal = true)
        assertEquals(2, events)
    }
}
