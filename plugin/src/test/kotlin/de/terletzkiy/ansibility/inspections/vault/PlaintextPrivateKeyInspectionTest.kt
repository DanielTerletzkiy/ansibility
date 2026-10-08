package de.terletzkiy.ansibility.inspections.vault

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.codeInspection.LocalInspectionEP
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.ExtensionTestUtil
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.terletzkiy.ansibility.context.AnsibleWorkspaceImpl
import de.terletzkiy.ansibility.settings.AnsibilityProjectSettings
import de.terletzkiy.ansibility.vault.VaultVectors
import de.terletzkiy.ansibility.vault.identity.VaultProjectSettings
import de.terletzkiy.ansibility.vault.keys.PlaintextKeyExclusions
import de.terletzkiy.ansibility.vault.vcs.TrackedStatus
import de.terletzkiy.ansibility.vault.vcs.TrackedStatusLookup
import de.terletzkiy.ansibility.vault.vcs.TrackedStatuses
import de.terletzkiy.ansibility.vault.vcs.impl.VcsTrackedStatusLookup

/**
 * ANS-V108 "Plaintext private key" (plan amendment R21, D160–D162) on a synthetic root `site/` with throwaway keys made
 * at test time ([TestKeys]) and a scripted VCS status ([FakeStatusLookup]): what is reported where, at which severity
 * for which VCS status, with which message and range, what is excluded, and that a status change re-highlights the
 * file. Messages never carry key material.
 */
class PlaintextPrivateKeyInspectionTest : BasePlatformTestCase() {
    private lateinit var vcs: FakeStatusLookup
    private var ignoredPaths: List<String> = emptyList()

    override fun setUp() {
        super.setUp()
        ignoredPaths = AnsibilityProjectSettings.getInstance(project).settings.paths.extraIgnoredPaths
        myFixture.enableInspections(AnsiblePlaintextPrivateKeyInspection(), AnsibleVaultNotWholeFileInspection())
        vcs = FakeStatusLookup()
        // The registration test looks at the real extensions; every other test scripts the VCS status.
        if (name != "testTheVcsFragmentRegistersTheStatusLookup") {
            ExtensionTestUtil.maskExtensions(TrackedStatusLookup.EP_NAME, listOf<TrackedStatusLookup>(vcs), testRootDisposable)
        }
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

    /** Creates [path] with [text], re-detects the roots and settles the project. */
    private fun create(path: String, text: String): VirtualFile =
        myFixture.tempDirFixture.createFile(path, text).also {
            (AnsibleWorkspaceImpl.getInstance(project) ?: error("AnsibleWorkspace is not AnsibleWorkspaceImpl")).structureChanged()
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            IndexingTestUtil.waitUntilIndexesAreReady(project)
        }

    /** Opens [path] with [text] and returns its ANS-V108 highlights. */
    private fun infos(path: String, text: String): List<HighlightInfo> {
        myFixture.configureFromExistingVirtualFile(create(path, text))
        return highlight()
    }

    private fun highlight(): List<HighlightInfo> =
        myFixture.doHighlighting().filter { it.inspectionToolId == SHORT_NAME }.sortedBy { it.startOffset }

    private fun highlighted(info: HighlightInfo): String = myFixture.editor.document.text.substring(info.startOffset, info.endOffset)

    private fun fixes(): List<String> = myFixture.getAllQuickFixes().map { it.text }.filter { it in FIX_NAMES }

    // ------------------------------------------------------------------------------------------------ keys

    fun testAPlaintextKeyIsAnErrorOnItsBeginMarkerWithoutAnyKeyMaterialInTheMessage() {
        val key = TestKeys.plaintextKey()
        val info = infos("site/roles/web/files/ssl/web.key", key).single()
        assertEquals(HighlightSeverity.ERROR, info.severity)
        assertEquals(TestKeys.begin(TestKeys.PRIVATE), highlighted(info))
        assertEquals(
            "Plaintext private key (PKCS#8): encrypt it with Ansibility Vault. A key that was ever committed must be rotated, because git history keeps it.",
            info.description,
        )
        assertFalse(info.description, TestKeys.bodyLine(key).take(12) in info.description!!)
    }

    fun testTheVcsStatusDecidesSeverityAndMessage() {
        val key = TestKeys.plaintextKey()
        // file name to (status, severity, message start); ignored files get nothing.
        val cases = listOf(
            Triple("committed.key", TrackedStatus.TRACKED, HighlightSeverity.ERROR to "Plaintext private key (PKCS#8) in a committed file: everyone with the repository has it. Encrypt it with Ansibility Vault and rotate the key"),
            Triple("staged.key", TrackedStatus.ADDED, HighlightSeverity.ERROR to "Plaintext private key (PKCS#8) in a file added to version control: encrypt it before you commit. A key that was ever committed must be rotated"),
            Triple("local.key", TrackedStatus.UNTRACKED, HighlightSeverity.WARNING to "Plaintext private key (PKCS#8), not committed yet: encrypt it before you commit, or .gitignore it."),
            Triple("novcs.key", TrackedStatus.NO_VCS, HighlightSeverity.ERROR to "Plaintext private key (PKCS#8): encrypt it with Ansibility Vault."),
        )
        for ((name, status, expected) in cases) {
            vcs.statuses[name] = status
            val info = infos("site/roles/web/files/ssl/$name", key).single()
            assertEquals(name, expected.first, info.severity)
            assertTrue("$name: ${info.description}", info.description!!.startsWith(expected.second))
        }
        vcs.statuses["ignored.key"] = TrackedStatus.IGNORED
        assertEmpty("a git-ignored key is no leak", infos("site/roles/web/files/ssl/ignored.key", key))
    }

    fun testProtectedKeysAreWarnings() {
        vcs.default = TrackedStatus.TRACKED
        val info = infos("site/roles/web/files/ssl/protected.key", TestKeys.protectedKey()).single()
        assertEquals(HighlightSeverity.WARNING, info.severity)
        assertTrue(info.description, info.description!!.startsWith("Passphrase-protected private key (RSA, PKCS#1) in a committed file: the passphrase can be attacked offline."))
        assertEquals(TestKeys.begin("RSA ${TestKeys.PRIVATE}"), highlighted(info))
    }

    fun testKeysAreFoundAnywhereAndCertificatesAndExamplesNever() {
        val key = TestKeys.plaintextKey()
        val bundle = TestKeys.certificate() + TestKeys.certificate() + key
        val info = infos("docker/certs/haproxy.pem", bundle).single()
        assertEquals("the key after the chain, outside any root", TestKeys.begin(TestKeys.PRIVATE), highlighted(info))
        assertEquals(
            "outside every root Ansibility Vault cannot encrypt it: the message says what to do instead",
            "Plaintext private key (PKCS#8) outside every Ansible root: keep it out of version control (.gitignore it). A key that was ever committed must be rotated, because git history keeps it.",
            info.description,
        )
        assertEquals("on its own line", 2 * TestKeys.certificate().lines().size - 2, myFixture.editor.document.getLineNumber(info.startOffset))
        assertEmpty("certificates", infos("docker/certs/chain.pem", TestKeys.certificate()))
        val truncated = TestKeys.begin(TestKeys.PRIVATE) + "\nMIIEvQIBADANBg\n...\n"
        assertEmpty("a truncated example in docs", infos("README.md", "# TLS\n\nA key looks like this:\n\n    $truncated"))
        val escaped = "{\"private_key\": \"" + key.replace("\n", "\\n") + "\"}\n"
        assertEquals("a key in a JSON string", 1, infos("site/files/service-account.json", escaped).size)
    }

    fun testAKeyInATemplateIsReported() {
        val info = infos("site/roles/web/templates/web.key.j2", "# {{ ansible_managed }}\n" + TestKeys.plaintextKey()).single()
        assertEquals("AnsibleJinja", myFixture.file.language.id)
        assertEquals("the template's text is what the host gets", TestKeys.begin(TestKeys.PRIVATE), highlighted(info))
    }

    fun testAKeyInAYamlValueIsReportedOnTheValueWithEncryptValue() {
        val text = "---\ntls_port: 443\ntls_key: |\n" + TestKeys.plaintextKey(indent = 2)
        val info = infos("site/group_vars/all/tls.yml", text).single()
        assertEquals(HighlightSeverity.ERROR, info.severity)
        assertEquals(TestKeys.begin(TestKeys.PRIVATE), highlighted(info))
        assertEquals("a YAML value gets Encrypt value, not Encrypt file", listOf("Encrypt value"), fixes())
    }

    // ------------------------------------------------------------------------------------------------ names

    fun testKeyLikeNamesWithoutAReadableKeyAreWarningsOnAnEmptyRange() {
        vcs.default = TrackedStatus.TRACKED
        val info = infos("site/roles/web/files/ssl/web.key", "not a pem file, but a secret by its name\n").single()
        assertEquals(HighlightSeverity.WARNING, info.severity)
        assertEquals("Not vaulted: a *.key file below files/ssl holds a secret by convention, and this committed file is plaintext. Encrypt it with Ansibility Vault; a secret that was ever committed must be rotated, because git history keeps it.", info.description)
        assertEquals("nothing of the file is highlighted (or exported by Inspect Code)", "", highlighted(info))
        val password = infos("site/roles/db/files/conf/db.password", "synthetic-tern\n").single()
        assertEquals(HighlightSeverity.WARNING, password.severity)
        assertTrue(password.description, password.description!!.startsWith("Not vaulted: a *.password file below files holds a secret by convention"))
        assertFalse(password.description, "synthetic-tern" in password.description!!)

        assertEmpty("an empty password file", infos("site/roles/db/files/empty.password", "\n"))
        assertEmpty("a whole-file vault", infos("site/roles/db/files/sealed.password", VaultVectors.encrypt("synthetic-tern", VaultVectors.PW1).format()))
        assertEmpty("a key file below files without ssl or ssh: the content decides", infos("site/roles/web/files/tls/web.key", "plain\n"))
        val wrapped = "!vault |\n" + VaultVectors.encrypt("synthetic-tern", VaultVectors.PW1).formatLines().joinToString("") { "  $it\n" }
        val v107 = myFixture.run { configureFromExistingVirtualFile(create("site/roles/db/files/wrapped.password", wrapped)); doHighlighting() }
        assertEquals("a wrapped vault is ANS-V107's, not ANS-V108's", listOf("AnsibleVaultNotWholeFile"), v107.mapNotNull { it.inspectionToolId }.filter { it.startsWith("Ansible") })
    }

    /** User report 2026-10-08: `*.password` files that hold an already hashed password are no plaintext secret. */
    fun testKeyLikeFilesThatHoldOnlyPasswordHashesAreNotReported() {
        vcs.default = TrackedStatus.TRACKED
        assertEmpty("a MySQL hash", infos("site/roles/db/files/mysql/users/alice.password", TestHashes.MYSQL_NATIVE + "\n"))
        assertEmpty("a sha512crypt hash", infos("site/roles/db/files/ssh/users/alice.password", TestHashes.SHA512_CRYPT + "\n"))
        assertEmpty("a locked account's hash", infos("site/roles/db/files/ssh/users/bob.password", "!" + TestHashes.SHA512_CRYPT + "\n"))
        assertEmpty("a bcrypt hash", infos("site/roles/web/files/web.password", TestHashes.BCRYPT))
        assertEmpty("htpasswd lines", infos("site/roles/web/files/htpasswd/web.password", "alice:${TestHashes.BCRYPT}\nbob:${TestHashes.APR1}\n"))
        assertEmpty("a hash in a *.key file below files/ssh", infos("site/roles/db/files/ssh/users/alice.key", TestHashes.SHA512_CRYPT + "\n"))

        val plain = infos("site/roles/db/files/mysql/users/bob.password", "synthetic-bob-pw\n").single()
        assertEquals("a plaintext password is still reported", HighlightSeverity.WARNING, plain.severity)
        assertTrue(plain.description, plain.description!!.startsWith("Not vaulted: a *.password file below files holds a secret by convention"))
        val reported = mapOf(
            "a hash next to a plaintext line" to ("site/roles/db/files/ssh/users/carol.password" to TestHashes.SHA512_CRYPT + "\nsynthetic-carol-pw\n"),
            "a hex string in a *.key file" to ("site/roles/web/files/ssl/web.key" to TestHashes.sha256Hex("synthetic-alice-pw") + "\n"),
            "a hex string in a *.password file (openssl rand -hex)" to ("site/roles/web/files/admin.password" to TestHashes.sha256Hex("synthetic-alice-pw") + "\n"),
            "a PostgreSQL md5 hash logs in as it is" to ("site/roles/db/files/postgres/users/bob.password" to TestHashes.postgresMd5() + "\n"),
            "a comment next to the hash" to ("site/roles/web/files/htpasswd/admin.password" to "# alice: synthetic-alice-pw\nalice:${TestHashes.BCRYPT}\n"),
            "a password in the name field" to ("site/roles/web/files/htpasswd/ops.password" to "synthetic-alice-pw!:${TestHashes.BCRYPT}\n"),
        )
        for ((what, file) in reported) {
            val infos = infos(file.first, file.second)
            assertEquals(what, listOf(HighlightSeverity.WARNING), infos.map { it.severity })
        }

        val key = infos("site/roles/db/files/ssh/users/deploy.password", TestHashes.SHA512_CRYPT + "\n" + TestKeys.plaintextKey()).single()
        assertEquals("a private key after a hash is still a plaintext key", HighlightSeverity.ERROR, key.severity)
        assertEquals(TestKeys.begin(TestKeys.PRIVATE), highlighted(key))
    }

    // ------------------------------------------------------------------------------------------------ exclusions

    fun testMoleculeFoldersAndIgnoredPathsAreExcludedAndTheAllowlistCanChange() {
        val key = TestKeys.plaintextKey()
        assertEmpty("Molecule's test keys by default", infos("site/roles/web/molecule/default/files/test.key", key))
        PlaintextKeyExclusions.setAllowlist(project, listOf("  ", "docker/**"))
        assertEquals(listOf("docker/**"), VaultProjectSettings.getInstance(project).plaintextKeyAllowlist)
        assertEquals("no default once the allowlist was replaced", 1, infos("site/roles/web/molecule/default/files/test2.key", key).size)
        assertEmpty(infos("docker/certs/web.key", key))
        AnsibilityProjectSettings.getInstance(project).update { it.copy(paths = it.paths.copy(extraIgnoredPaths = listOf("legacy/**"))) }
        assertEmpty("Ansibility's ignored paths", infos("legacy/certs/web.key", key))
    }

    fun testChangingTheAllowlistReHighlightsAtOnce() {
        myFixture.configureFromExistingVirtualFile(create("site/roles/web/files/ssl/web.key", TestKeys.plaintextKey()))
        assertEquals(1, highlight().size)
        PlaintextKeyExclusions.setAllowlist(project, listOf("site/roles/**"))
        assertEmpty("allowlisted: the open file is re-highlighted without any edit", highlight())
        PlaintextKeyExclusions.setAllowlist(project, emptyList())
        assertEquals(1, highlight().size)
    }

    fun testNoFindingsWithoutAnAnsibleRoot() {
        WriteAction.runAndWait<Exception> { myFixture.tempDirFixture.getFile("site/ansible.cfg")!!.delete(this) }
        AnsibleWorkspaceImpl.getInstance(project)!!.structureChanged()
        assertEmpty(infos("other/web.key", TestKeys.plaintextKey()))
    }

    // ------------------------------------------------------------------------------------------------ VCS changes

    fun testAStatusChangeReHighlightsTheOpenFile() {
        val file = create("site/roles/web/files/ssl/web.key", TestKeys.plaintextKey())
        myFixture.configureFromExistingVirtualFile(file)
        assertEquals(HighlightSeverity.ERROR, highlight().single().severity)
        val statuses = TrackedStatuses.getInstance(project)
        val before = statuses.restartedForTests.size
        vcs.statuses["web.key"] = TrackedStatus.IGNORED
        vcs.fire(file)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        assertEquals(listOf(file), statuses.restartedForTests.drop(before))
        assertEmpty("ignored now", highlight())
        vcs.fire(myFixture.tempDirFixture.createFile("site/other.txt", "no key\n"))
        repeat(3) { vcs.fire(null) }
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        assertEquals("refreshes that change no status that matters restart nothing", listOf(file), statuses.restartedForTests.drop(before))
        vcs.statuses.remove("web.key")
        vcs.fire(null)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        assertEquals("a refresh that changed its status restarts the open file with findings", listOf(file, file), statuses.restartedForTests.drop(before))
        assertEquals(HighlightSeverity.ERROR, highlight().single().severity)
    }

    fun testAnAllowlistGlobThatMatchesEveryPathIsIgnored() {
        PlaintextKeyExclusions.setAllowlist(project, listOf("**", "**/*"))
        assertEquals("a repository cannot switch the check off with one line", 1, infos("site/roles/web/files/ssl/web.key", TestKeys.plaintextKey()).size)
        PlaintextKeyExclusions.setAllowlist(project, listOf("site/roles/web/files/ssl/**"))
        assertEmpty(infos("site/roles/web/files/ssl/db.key", TestKeys.plaintextKey()))
    }

    // ------------------------------------------------------------------------------------------------ registration

    fun testRegisteredUnderAnsibilityVaultWithoutLanguageAndWithADescription() {
        val ep = LocalInspectionEP.LOCAL_INSPECTION.extensionList.single { it.shortName == SHORT_NAME }
        assertEquals("Ansibility", ep.groupPath)
        assertEquals("Vault", AnsibilityVaultChecksBundle.message(ep.groupKey))
        assertEquals("Plaintext private key (ANS-V108)", AnsibilityVaultChecksBundle.message(ep.key))
        assertNull("keys live in files of any type", ep.language)
        assertEquals("ERROR", ep.level)
        assertNotNull(javaClass.getResource("/inspectionDescriptions/$SHORT_NAME.html"))
    }

    fun testTheVcsFragmentRegistersTheStatusLookup() {
        // The test IDE has VCS support, so the optional ansibility-vcs.xml is loaded with its lookup.
        assertEquals(listOf(VcsTrackedStatusLookup::class.java), TrackedStatusLookup.EP_NAME.extensionList.map { it.javaClass })
        ExtensionTestUtil.maskExtensions(TrackedStatusLookup.EP_NAME, emptyList(), testRootDisposable)
        assertEquals("without VCS support every file is outside version control", TrackedStatus.NO_VCS,
            TrackedStatuses.getInstance(project).status(myFixture.tempDirFixture.createFile("x.key", "")))
        assertEquals(TrackedStatus.IGNORED, VcsTrackedStatusLookup.of(com.intellij.openapi.vcs.FileStatus.IGNORED))
        assertEquals(TrackedStatus.UNTRACKED, VcsTrackedStatusLookup.of(com.intellij.openapi.vcs.FileStatus.UNKNOWN))
        assertEquals(TrackedStatus.ADDED, VcsTrackedStatusLookup.of(com.intellij.openapi.vcs.FileStatus.ADDED))
        assertEquals(TrackedStatus.TRACKED, VcsTrackedStatusLookup.of(com.intellij.openapi.vcs.FileStatus.MODIFIED))
        assertEquals(TrackedStatus.TRACKED, VcsTrackedStatusLookup.of(com.intellij.openapi.vcs.FileStatus.NOT_CHANGED))
        assertEquals("a light project has no VCS mapping", TrackedStatus.NO_VCS,
            VcsTrackedStatusLookup().status(project, myFixture.tempDirFixture.createFile("y.key", "")))
    }

    private companion object {
        const val SHORT_NAME = "AnsiblePlaintextPrivateKey"
        val FIX_NAMES = setOf("Encrypt file", "Encrypt value")
    }
}
