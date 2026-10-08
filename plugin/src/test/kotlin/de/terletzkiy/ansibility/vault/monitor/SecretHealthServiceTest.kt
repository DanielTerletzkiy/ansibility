package de.terletzkiy.ansibility.vault.monitor

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent
import com.intellij.testFramework.ExtensionTestUtil
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.terletzkiy.ansibility.context.AnsibleWorkspaceImpl
import de.terletzkiy.ansibility.inspections.vault.FakeStatusLookup
import de.terletzkiy.ansibility.inspections.vault.TestHashes
import de.terletzkiy.ansibility.inspections.vault.TestKeys
import de.terletzkiy.ansibility.semantics.diagnostics.DiagnosticCode
import de.terletzkiy.ansibility.settings.AnsibilityProjectSettings
import de.terletzkiy.ansibility.vault.VaultVectors
import de.terletzkiy.ansibility.vault.identity.VaultProjectSettings
import de.terletzkiy.ansibility.vault.keys.PlaintextKeyExclusions
import de.terletzkiy.ansibility.vault.vcs.TrackedStatus
import de.terletzkiy.ansibility.vault.vcs.TrackedStatusLookup
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The [SecretHealthService] snapshot (plan amendment R21, D164/D165) on a synthetic root `site/` and a scripted VCS
 * status ([FakeStatusLookup]): what is listed under which repository and category, at which level and with which
 * status, what is left out, and that it follows VFS changes, VCS status changes and the exclusions. Keys are made at
 * test time ([TestKeys]); envelopes with the synthetic passwords of `tools/vault/SYNTHETIC.md`.
 */
class SecretHealthServiceTest : BasePlatformTestCase() {
    private lateinit var vcs: FakeStatusLookup
    private lateinit var monitor: Monitor
    private var ignoredPaths: List<String> = emptyList()

    override fun setUp() {
        super.setUp()
        ignoredPaths = AnsibilityProjectSettings.getInstance(project).settings.paths.extraIgnoredPaths
        vcs = FakeStatusLookup(TrackedStatus.TRACKED)
        // An extension point can be masked once per test: the late-VCS test masks it with its own lookup.
        if (name != "testPlaintextKeysWaitUntilTheVcsKnowsTheStatuses") {
            ExtensionTestUtil.maskExtensions(TrackedStatusLookup.EP_NAME, listOf<TrackedStatusLookup>(vcs), testRootDisposable)
        }
        monitor = Monitor(project, testRootDisposable)
        create("site/ansible.cfg", "[defaults]\nroles_path = roles\n")
    }

    override fun tearDown() {
        try {
            VaultProjectSettings.getInstance(project).loadState(VaultProjectSettings.StateBean())
            AnsibilityProjectSettings.getInstance(project).update { it.copy(paths = it.paths.copy(extraIgnoredPaths = ignoredPaths)) }
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    private fun binary(path: String, bytes: ByteArray): VirtualFile {
        val file = myFixture.tempDirFixture.createFile(path)
        WriteAction.runAndWait<Throwable> { file.setBinaryContent(bytes) }
        (AnsibleWorkspaceImpl.getInstance(project) ?: error("AnsibleWorkspace is not AnsibleWorkspaceImpl")).structureChanged()
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        return file
    }

    private fun create(path: String, text: String): VirtualFile =
        myFixture.tempDirFixture.createFile(path, text).also {
            (AnsibleWorkspaceImpl.getInstance(project) ?: error("AnsibleWorkspace is not AnsibleWorkspaceImpl")).structureChanged()
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            IndexingTestUtil.waitUntilIndexesAreReady(project)
        }

    /** "group | category | path | level | status" per finding. */
    private fun rows(snapshot: SecretSnapshot): List<String> =
        snapshot.findings.map { "${it.group.title} | ${it.category} | ${it.path} | ${it.level} | ${it.status}" }

    private fun envelope() = VaultVectors.encrypt("value", VaultVectors.PW1)

    fun testFindingsByRepositoryAndCategoryWithLevelAndStatus() {
        val key = TestKeys.plaintextKey()
        create("site/roles/web/files/ssl/wrapped.key", pasted(envelope()))
        create("site/roles/web/files/ssl/db.key", key)
        create("site/roles/web/files/ssl/old.key", TestKeys.protectedKey())
        create("site/roles/web/files/ssl/plain.key", "not a key and not a vault\n")
        create("site/host_vars/web1.yml", "broken: !vault |\n  \$ANSIBLE_VAULT;1.1\n  6162\n")
        create("docker/certs/haproxy.pem", TestKeys.certificate() + key)
        create("site/roles/web/molecule/default/files/test.key", key)
        vcs.statuses["local.key"] = TrackedStatus.UNTRACKED
        create("site/roles/web/files/ssl/local.key", key)
        vcs.statuses["ignored.key"] = TrackedStatus.IGNORED
        create("site/roles/web/files/ssl/ignored.key", key)

        val snapshot = monitor.start().await { it.findings.size >= 7 }
        assertEquals(
            listOf(
                "site | BROKEN_VAULT_FILES | roles/web/files/ssl/wrapped.key | ERROR | TRACKED",
                "site | BROKEN_VAULT_VALUES | host_vars/web1.yml | ERROR | TRACKED",
                "site | PLAINTEXT_KEYS | roles/web/files/ssl/db.key | ERROR | TRACKED",
                "site | PLAINTEXT_KEYS | roles/web/files/ssl/local.key | WARNING | UNTRACKED",
                "site | PROTECTED_KEYS | roles/web/files/ssl/old.key | WARNING | TRACKED",
                "site | KEY_LIKE_FILES | roles/web/files/ssl/plain.key | WARNING | TRACKED",
                "Other files in src | PLAINTEXT_KEYS | docker/certs/haproxy.pem | ERROR | TRACKED",
            ),
            rows(snapshot),
        )
        assertEquals(1, snapshot.rootCount)
        assertTrue(snapshot.hasErrors)
        assertEquals(4, snapshot.errorCount)
        val byPath = snapshot.findings.associateBy { it.path }
        assertEquals(DiagnosticCode.V107_NOT_WHOLE_FILE_VAULT, byPath.getValue("roles/web/files/ssl/wrapped.key").code)
        assertEquals(SecretFix.CONVERT, byPath.getValue("roles/web/files/ssl/wrapped.key").fix)
        assertEquals(listOf("a !vault line before the envelope, line 1"), byPath.getValue("roles/web/files/ssl/wrapped.key").details)
        assertEquals(listOf("1 malformed value (ANS-V101)"), byPath.getValue("host_vars/web1.yml").details)
        assertEquals(listOf("PKCS#8, line 1"), byPath.getValue("roles/web/files/ssl/db.key").details)
        assertEquals(listOf("RSA, PKCS#1, passphrase-protected, line 1"), byPath.getValue("roles/web/files/ssl/old.key").details)
        assertEquals(listOf("not vaulted: a *.key file below files/ssl"), byPath.getValue("roles/web/files/ssl/plain.key").details)
        assertEquals("site|roles/web/files/ssl/db.key|PLAINTEXT_KEYS", byPath.getValue("roles/web/files/ssl/db.key").key)
        assertTrue("other groups are keyed apart from roots", byPath.getValue("docker/certs/haproxy.pem").group.id.startsWith("other:"))
        assertFalse("never content", snapshot.findings.flatMap { it.details }.any { TestKeys.bodyLine(key).take(12) in it })
    }

    /**
     * User report 2026-10-08: the Vault tab listed `files/mysql/users/<name>.password` and `files/ssh/users/<name>.password`
     * files that hold an already hashed password. Such files are not listed; plaintext passwords and keys still are.
     */
    fun testKeyLikeFilesThatHoldOnlyPasswordHashesAreNotListed() {
        create("site/roles/db/files/mysql/users/alice.password", TestHashes.MYSQL_NATIVE + "\n")
        create("site/roles/db/files/ssh/users/alice.password", TestHashes.SHA512_CRYPT + "\n")
        create("site/roles/web/files/htpasswd/web.password", "alice:${TestHashes.BCRYPT}\nbob:${TestHashes.APR1}\n")
        val bob = create("site/roles/db/files/mysql/users/bob.password", "synthetic-bob-pw\n")
        create("site/roles/db/files/ssh/users/deploy.password", TestHashes.SHA512_CRYPT + "\n" + TestKeys.plaintextKey())
        // Read as binary (control characters in the raw caching_sha2 salt): the index judges its bytes.
        binary("site/roles/db/files/mysql/users/carol.password", TestHashes.cachingSha2Raw())
        // The shape of `openssl rand -hex 32`: a plaintext secret as far as anyone can tell.
        create("site/roles/web/files/admin.password", TestHashes.sha256Hex("synthetic-alice-pw") + "\n")

        val snapshot = monitor.start().await { it.findings.size >= 3 }
        assertEquals(
            listOf(
                "site | PLAINTEXT_KEYS | roles/db/files/ssh/users/deploy.password | ERROR | TRACKED",
                "site | KEY_LIKE_FILES | roles/db/files/mysql/users/bob.password | WARNING | TRACKED",
                "site | KEY_LIKE_FILES | roles/web/files/admin.password | WARNING | TRACKED",
            ),
            rows(snapshot),
        )

        WriteAction.runAndWait<Throwable> { bob.setBinaryContent((TestHashes.MYSQL_NATIVE + "\n").toByteArray()) }
        assertEquals(
            "a plaintext password replaced by its hash leaves the tab",
            listOf("roles/db/files/ssh/users/deploy.password", "roles/web/files/admin.password"),
            monitor.await { it.findings.size == 2 }.findings.map { it.path },
        )
    }

    fun testUnprotectedPkcs12IsAPlaintextKeyAndAJavaKeystoreAProtectedOne() {
        binary("site/roles/web/files/ssl/web.p12", TestKeys.pkcs12WithPlainKey())
        binary("site/roles/web/files/app.jceks", TestKeys.jceksWithSecretKey())
        val snapshot = monitor.start().await { it.findings.size == 2 }
        assertEquals(
            listOf(
                "site | PLAINTEXT_KEYS | roles/web/files/ssl/web.p12 | ERROR | TRACKED",
                "site | PROTECTED_KEYS | roles/web/files/app.jceks | WARNING | TRACKED",
            ),
            rows(snapshot),
        )
        assertEquals(listOf("PKCS#12 keystore, unprotected key"), snapshot.findings.first().details)
    }

    fun testAFileOutsideEveryRootIsListedUnderTheRootOfItsRepository() {
        create("repo/ansible/ansible.cfg", "[defaults]\n")
        create("repo/docker/certs/web.key", TestKeys.plaintextKey())
        create("multi/a/ansible.cfg", "[defaults]\n")
        create("multi/b/ansible.cfg", "[defaults]\n")
        create("multi/certs/db.pem", TestKeys.plaintextKey())
        create("loose/certs/old.pem", TestKeys.plaintextKey())
        vcs.repositories += myFixture.tempDirFixture.getFile("repo")!!
        vcs.repositories += myFixture.tempDirFixture.getFile("multi")!!
        val byPath = monitor.start().await { it.findings.size == 3 }.findings.associateBy { it.path }
        val inRepo = byPath.getValue("../docker/certs/web.key")
        assertTrue("the one root of its repository", inRepo.group.isRoot)
        assertEquals(myFixture.tempDirFixture.getFile("repo/ansible"), inRepo.group.dir)
        val severalRoots = byPath.getValue("certs/db.pem")
        assertEquals("a repository with several roots is its own group", "multi", severalRoots.group.title)
        assertFalse(severalRoots.group.isRoot)
        assertEquals("without a repository: the content root", "Other files in src", byPath.getValue("loose/certs/old.pem").group.title)
    }

    fun testStatusEventsWithoutAChangeRebuildNothing() {
        val file = create("site/roles/web/files/ssl/web.key", TestKeys.plaintextKey())
        val other = create("site/README.md", "notes\n")
        monitor.start().await { it.findings.size == 1 }
        monitor.refresh()
        val builds = monitor.service.buildCount
        repeat(5) { vcs.fire(null) }
        vcs.fire(file)
        vcs.fire(other)
        PlatformTestUtil.waitWithEventsDispatching("the statuses were compared", { monitor.service.unchangedStatusChecks > 0 }, 30)
        assertEquals("the VCS refreshed its change lists but no status that matters changed", builds, monitor.service.buildCount)

        vcs.statuses["web.key"] = TrackedStatus.UNTRACKED
        vcs.fire(null)
        assertEquals(TrackedStatus.UNTRACKED, monitor.await { it.findings.singleOrNull()?.status == TrackedStatus.UNTRACKED }.findings.single().status)
    }

    fun testPlaintextKeysWaitUntilTheVcsKnowsTheStatuses() {
        val late = object : TrackedStatusLookup by vcs {
            var ready: (() -> Unit)? = null

            override fun whenReady(project: com.intellij.openapi.project.Project, ready: () -> Unit) {
                this.ready = ready
            }
        }
        ExtensionTestUtil.maskExtensions(TrackedStatusLookup.EP_NAME, listOf<TrackedStatusLookup>(late), testRootDisposable)
        vcs.statuses["dev.key"] = TrackedStatus.IGNORED
        create("site/roles/web/files/ssl/dev.key", TestKeys.plaintextKey())
        create("site/roles/web/files/ssl/web.key", TestKeys.plaintextKey())
        create("site/roles/web/files/ssl/wrapped.key", pasted(envelope()))
        monitor.service.readyTimeoutMillis = 50
        val before = monitor.start().await { it.findings.isNotEmpty() }
        Thread.sleep(200)
        val waiting = monitor.refresh()
        assertFalse("the statuses are not known yet, even after the timeout", waiting.statusesKnown)
        assertEquals("only what needs no status: an ignored key could look committed", listOf(SecretCategory.BROKEN_VAULT_FILES), waiting.findings.map { it.category })
        assertEquals(before.findings, waiting.findings)
        (late.ready ?: error("the service did not ask whether the VCS is ready"))()
        val known = monitor.await { it.statusesKnown && it.findings.size == 2 }
        assertEquals(listOf("roles/web/files/ssl/wrapped.key", "roles/web/files/ssl/web.key"), known.findings.map { it.path })
    }

    fun testAVarsDocumentIsOneBrokenVaultFileNotAMalformedValue() {
        create("site/group_vars/all/vault.yml", pasted(envelope()))
        val finding = monitor.start().await { it.findings.isNotEmpty() }.findings.single()
        assertEquals(SecretCategory.BROKEN_VAULT_FILES, finding.category)
        assertEquals(DiagnosticCode.V107_NOT_WHOLE_FILE_VAULT, finding.code)
        assertEquals(listOf("a vars file that is one !vault value, line 1"), finding.details)
    }

    fun testCleanWorkspaceAndNoRoots() {
        create("site/group_vars/all/vault.yml", VaultVectors.inline("vault_x", envelope()))
        val clean = monitor.start().await { true }
        assertEmpty(clean.findings)
        assertEquals(1, clean.rootCount)
        assertFalse(clean.hasErrors)
    }

    fun testFollowsVfsChangesAndVcsStatusChanges() {
        monitor.start().await { it.findings.isEmpty() }
        val file = create("site/roles/web/files/ssl/new.key", TestKeys.plaintextKey())
        assertEquals(listOf("roles/web/files/ssl/new.key"), monitor.await { it.findings.isNotEmpty() }.findings.map { it.path })

        WriteAction.runAndWait<Throwable> { file.setBinaryContent("now just a note\n".toByteArray()) }
        assertEquals(SecretCategory.KEY_LIKE_FILES, monitor.await { it.findings.singleOrNull()?.category == SecretCategory.KEY_LIKE_FILES }.findings.single().category)

        vcs.statuses["new.key"] = TrackedStatus.IGNORED
        vcs.fire(null)
        monitor.await("a file that became ignored is not listed") { it.findings.isEmpty() }

        vcs.statuses.remove("new.key")
        vcs.fire(file)
        monitor.await { it.findings.size == 1 }
        WriteAction.runAndWait<Throwable> { file.delete(this) }
        monitor.await("a deleted file is gone") { it.findings.isEmpty() }
    }

    fun testExclusionsApplyAndRefreshAtOnce() {
        val key = TestKeys.plaintextKey()
        create("site/roles/web/files/ssl/web.key", key)
        create("legacy/certs/old.pem", key)
        monitor.start().await { it.findings.size == 2 }
        PlaintextKeyExclusions.setAllowlist(project, listOf("site/roles/**"))
        assertEquals(listOf("legacy/certs/old.pem"), monitor.await { it.findings.size == 1 }.findings.map { it.path })
        AnsibilityProjectSettings.getInstance(project).update { it.copy(paths = it.paths.copy(extraIgnoredPaths = listOf("legacy/**"))) }
        monitor.await("ignored paths are left out") { it.findings.isEmpty() }
    }

    fun testPublishesOldAndNewSnapshots() {
        val seen = CopyOnWriteArrayList<Pair<Int, Int>>()
        project.messageBus.connect(testRootDisposable).subscribe(SecretHealthListener.TOPIC, SecretHealthListener { old, new -> seen += old.findings.size to new.findings.size })
        monitor.start().await { it.findings.isEmpty() }
        create("site/roles/web/files/ssl/web.key", TestKeys.plaintextKey())
        monitor.await { it.findings.size == 1 }
        assertTrue(seen.toString(), 0 to 1 in seen)
        val count = seen.size
        monitor.refresh()
        assertEquals("an unchanged snapshot is not published again", count, seen.size)
    }

    fun testOnlyEventsOnCandidatePathsAndDirectoriesCount() {
        val root = myFixture.tempDirFixture.findOrCreateDir("site")
        fun created(name: String, directory: Boolean = false) = VFileCreateEvent(null, root, name, directory, null, null, null)
        assertTrue(SecretHealthService.isRelevant(created("web.pem")))
        assertTrue(SecretHealthService.isRelevant(created("group_vars", directory = true)))
        assertFalse(SecretHealthService.isRelevant(created("README.md")))
        val readme = create("site/README.md", "notes\n")
        assertFalse(SecretHealthService.isRelevant(VFileContentChangeEvent(null, readme, readme.modificationStamp, readme.modificationStamp + 1)))
    }
}
