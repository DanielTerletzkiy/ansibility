package de.terletzkiy.ansibility.model.drift

import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.terletzkiy.ansibility.fixtures.InfraTestData
import de.terletzkiy.ansibility.model.role.ModelFixture
import de.terletzkiy.ansibility.model.role.RoleCatalog

/**
 * Opt-in check of role drift against the real infra repo (read-only): set `ANSIBLE_INFRA_REPO` to its path. It pins
 * the measured split of plan amendment R9 (F9.5, testing item 6): 65 names, 354 copies, 50 shared; over the 274 repo
 * copies of golden's 35 roles 195 identical, 10 molecule-only, 17 spec-defaults and 52 tasks-templates; 13 golden
 * names identical everywhere; the haproxy, percona and keycloak variants; the chain sizes 1, 12 and 2.
 *
 * Nothing secret is read: the drift service runs in [SensitiveContent.SIZE_ONLY] (key files, `*.password`, `.env*`,
 * `files/ssl`, `files/ssh` and `vault*` files are fingerprinted by length only; none of them differs in the measured
 * data), and the same names are excluded from the test module's content entry, so the IDE never indexes them either.
 * Whole-file vaults with other names are hashed as ciphertext and never decrypted. The repo is only read, never
 * edited: the edit-while-typing check runs on the synthetic tree ([RoleDriftServiceTest]).
 */
class RoleDriftCorpusTest : BasePlatformTestCase() {
    private val repo: VirtualFile? by lazy {
        System.getenv(InfraTestData.INFRA_REPO_ENV)?.let { LocalFileSystem.getInstance().refreshAndFindFileByPath(it) }
    }

    private fun withRepo(action: (VirtualFile) -> Unit) {
        val repo = repo ?: return
        ModuleRootModificationUtil.updateModel(module) { model -> model.addContentEntry(repo).setExcludePatterns(UNREAD_PATTERNS) }
        try {
            ModelFixture.rescan(project)
            action(repo)
        } finally {
            PsiTestUtil.removeContentEntry(module, repo)
            ModelFixture.rescan(project)
        }
    }

    fun testMeasuredDriftOfTheRealRepo() = withRepo { repo ->
        val service = DriftFixture.freshService(project, testRootDisposable)
        service.sensitiveContent = SensitiveContent.SIZE_ONLY
        val catalog = RoleCatalog.getInstance(project).snapshot()
        assertEquals("names", 65, catalog.names.size)
        assertEquals("copies", 354, catalog.copyCount)
        assertEquals("names with more than one copy", 50, catalog.sharedNameCount)

        val coldStart = System.nanoTime()
        val drifts = DriftFixture.await { service.driftAll() }.associateBy { it.name }
        val coldMs = (System.nanoTime() - coldStart) / 1_000_000
        val cold = service.counters.toString()

        val withGolden = drifts.values.filter { it.reference != null }
        assertEquals("golden roles", 35, withGolden.size)
        assertTrue(withGolden.all { it.reference!!.root.dir == repo.findChild("golden") })
        val repoCopies = withGolden.flatMap { drift -> drift.copies.filter { !it.copy.isReference } }
        assertEquals("repo copies of golden roles", 274, repoCopies.size)
        val split = repoCopies.groupingBy { it.tier }.eachCount()
        assertEquals(
            mapOf(DriftTier.IDENTICAL to 195, DriftTier.MOLECULE_ONLY to 10, DriftTier.SPEC_DEFAULTS to 17, DriftTier.BEHAVIOUR to 52),
            split,
        )
        assertEquals("golden names identical in all copies", 13, withGolden.count { it.identicalEverywhere })
        assertEquals("names without a golden copy", 30, drifts.values.count { it.reference == null })

        val haproxy = drifts.getValue("haproxy")
        assertEquals(
            listOf(listOf("golden", "raven"), listOf("falcon", "heron", "pelican", "platform", "tern", "thrush", "wren")),
            haproxy.variants.map { variant -> variant.copies.map { it.root.displayName }.sorted() },
        )
        assertEquals(9, drifts.getValue("percona").variants.size)
        assertEquals(5, drifts.getValue("keycloak").variants.size)
        assertEquals(7, drifts.getValue("postfix").variants.size)
        assertEquals(
            listOf("falcon", "heron", "pelican", "platform", "wren"),
            drifts.getValue("keycloak").copies.filter { it.tier == DriftTier.SPEC_DEFAULTS }.map { it.copy.root.displayName },
        )
        assertTrue(drifts.getValue("docker").identicalEverywhere)
        assertEquals(9, drifts.getValue("docker").copies.size)

        assertEquals("haproxy falcon: verify.yml only", 1, copy(drifts, "haproxy", "falcon").paths.size)
        val percona = copy(drifts, "percona", "heron").paths
        assertEquals("percona heron: 3 changed + 9 only in golden", 12, percona.size)
        assertEquals(3, percona.changed.size)
        assertEquals(9, percona.onlyInReference.size)
        val system = copy(drifts, "system", "falcon").paths
        assertEquals(listOf("meta/argument_specs.yml", "molecule/default/verify.yml"), system.all)

        val clone = drifts.getValue("clone-percona-to-primary")
        assertNull(clone.reference)
        assertEquals(listOf("pelican › danger_zone/database", "wren › danger_zone/database"), clone.copies.map { it.copy.root.displayName })
        assertEquals(2, clone.variants.size)

        assertEquals("no key, password or env file was read", 0, service.counters.sensitiveContentReads)
        assertTrue("sensitive files were fingerprinted by length", service.counters.sizeOnlyFiles > 0)

        val hashed = service.counters.filesHashed
        val warmStart = System.nanoTime()
        val warm = DriftFixture.await { service.driftAll() }
        val warmMs = (System.nanoTime() - warmStart) / 1_000_000
        assertEquals("a warm pass rehashes nothing", hashed, service.counters.filesHashed)
        assertEquals(drifts.values.toList(), warm)

        println(
            "Role drift on the real repo: ${catalog.names.size} names, ${catalog.copyCount} copies; repo copies of golden roles " +
                "${repoCopies.size}: $split; cold ${coldMs} ms ($cold), warm ${warmMs} ms",
        )
    }

    private fun copy(drifts: Map<String, RoleDrift>, name: String, team: String): CopyDrift =
        drifts.getValue(name).copies.single { it.copy.root.displayName == team }

    private companion object {
        /** Names the test module never indexes (the drift service reads none of them in size-only mode either). */
        val UNREAD_PATTERNS: List<String> = listOf(
            "*.key", "*.pem", "*.crt", "*.password", "*.p12", "*.pfx", "*.jks", "*.keystore",
            ".env*", ".vault-pass", ".initial-root-pass", "vault*", "ssl", "ssh", ".claude",
        )
    }
}
