package de.terletzkiy.ansibility.model.drift

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure drift rules (plan amendment R9, testing item 2): categories, tiers, comparison, variants, sensitivity. */
class DriftRulesTest {
    private fun entry(path: String, content: String, sensitive: Boolean = DriftRules.isSensitivePath(path)): FileEntry =
        FileEntry.of(path, content.toByteArray(), sensitive)

    private fun copy(vararg files: Pair<String, String>): List<FileEntry> = files.map { (path, content) -> entry(path, content) }.sortedBy { it.relPath }

    @Test
    fun categoriesFollowTheRoleAnatomy() {
        assertEquals(DriftCategory.MOLECULE, DriftRules.categoryOf("molecule/default/verify.yml"))
        assertEquals(DriftCategory.MOLECULE, DriftRules.categoryOf("molecule"))
        assertEquals(DriftCategory.SPEC_DEFAULTS, DriftRules.categoryOf("meta/argument_specs.yml"))
        assertEquals(DriftCategory.SPEC_DEFAULTS, DriftRules.categoryOf("meta/argument_specs.yaml"))
        assertEquals(DriftCategory.SPEC_DEFAULTS, DriftRules.categoryOf("defaults/main.yml"))
        assertEquals(DriftCategory.SPEC_DEFAULTS, DriftRules.categoryOf("defaults/main/extra.yml"))
        assertEquals(DriftCategory.SPEC_DEFAULTS, DriftRules.categoryOf("vars/main.yml"))
        for (path in listOf("tasks/main.yml", "handlers/main.yml", "templates/a.j2", "files/x.txt", "meta/main.yml", "README.md", "moleculefoo/x")) {
            assertEquals(path, DriftCategory.BEHAVIOUR, DriftRules.categoryOf(path))
        }
    }

    @Test
    fun theTierIsTheHighestCategoryTouched() {
        assertEquals(DriftTier.IDENTICAL, DriftRules.tierOf(emptyList()))
        assertEquals(DriftTier.MOLECULE_ONLY, DriftRules.tierOf(listOf("molecule/default/verify.yml", "molecule/default/converge.yml")))
        assertEquals(DriftTier.SPEC_DEFAULTS, DriftRules.tierOf(listOf("meta/argument_specs.yml")))
        assertEquals("spec plus molecule stays spec/defaults", DriftTier.SPEC_DEFAULTS, DriftRules.tierOf(listOf("meta/argument_specs.yml", "molecule/default/verify.yml")))
        assertEquals(DriftTier.BEHAVIOUR, DriftRules.tierOf(listOf("defaults/main.yml", "tasks/main.yml")))
        assertEquals(DriftTier.BEHAVIOUR, DriftRules.tierOf(listOf("meta/main.yml")))
        assertTrue(DriftTier.MOLECULE_ONLY.differs && DriftTier.SPEC_DEFAULTS.differs && DriftTier.BEHAVIOUR.differs)
        assertFalse(DriftTier.IDENTICAL.differs || DriftTier.REFERENCE.differs || DriftTier.NO_REFERENCE.differs)
    }

    @Test
    fun theSkipListMatchesThePlan() {
        for (dir in listOf("__pycache__", ".git", ".ansible", ".pytest_cache", "node_modules")) assertTrue(dir, DriftRules.isSkippedDirectory(dir))
        for (dir in listOf("tasks", "molecule", "files", "ssl")) assertFalse(dir, DriftRules.isSkippedDirectory(dir))
        assertTrue(DriftRules.isSkippedFile(".DS_Store"))
        assertTrue(DriftRules.isSkippedFile("module.cpython-312.pyc"))
        assertFalse(DriftRules.isSkippedFile("module.py"))
        assertFalse(DriftRules.isSkippedFile(".yamllint"))
    }

    @Test
    fun sensitiveNamesAreRecognised() {
        val sensitive = listOf(
            "molecule/default/files/ssl/molecule-ca.key", "files/ssl/ca.pem", "files/ssh/users/jenkins.ed25519.key",
            "molecule/default/files/ssh/hosts/bitbucket.org", "files/secret.password", "templates/deployment/.env",
            "files/.env.local", "files/.vault-pass", "vars/vault.yml", "files/keystore.jks", "files/cert.crt",
        )
        for (path in sensitive) assertTrue(path, DriftRules.isSensitivePath(path))
        for (path in listOf("tasks/main.yml", "files/ssl", "templates/ssl.conf.j2", "files/keys.yml", "files/ssh_config.j2", "defaults/main.yml")) {
            assertFalse(path, DriftRules.isSensitivePath(path))
        }
    }

    @Test
    fun wholeFileVaultsAreRecognisedByTheirHeader() {
        assertTrue(DriftRules.isWholeFileVault("\$ANSIBLE_VAULT;1.1;AES256\n6162\n".toByteArray()))
        assertFalse(DriftRules.isWholeFileVault(" \$ANSIBLE_VAULT;1.1;AES256\n".toByteArray()))
        assertFalse(DriftRules.isWholeFileVault("key: !vault |\n  \$ANSIBLE_VAULT;1.1;AES256\n".toByteArray()))
        assertFalse(DriftRules.isWholeFileVault("\$ANSIBLE".toByteArray()))
        val vault = entry("files/secrets.yml", "\$ANSIBLE_VAULT;1.1;AES256\n3132\n")
        assertTrue("a whole-file vault is sensitive whatever its name", vault.isSensitive)
    }

    @Test
    fun entriesNeverPrintTheirHash() {
        val text = entry("files/molecule-ca.key", "secret-material").toString()
        assertTrue(text, text.contains("***"))
        assertEquals("FileEntry(files/molecule-ca.key, 15 bytes, ***)", text)
    }

    @Test
    fun contentIdentityRules() {
        assertTrue(entry("a", "x").sameContent(entry("b", "x")))
        assertFalse(entry("a", "x").sameContent(entry("a", "y")))
        assertTrue("size-only entries compare by length", FileEntry.sizeOnly("a.key", 3).sameContent(FileEntry.sizeOnly("a.key", 3)))
        assertFalse(FileEntry.sizeOnly("a.key", 3).sameContent(FileEntry.sizeOnly("a.key", 4)))
        assertFalse("a size-only entry never equals a hashed one", FileEntry.sizeOnly("a.key", 1).sameContent(entry("a.key", "x")))
        assertFalse("an unreadable file never equals anything", FileEntry.unreadable("a", 1, false).sameContent(FileEntry.unreadable("a", 1, false)))
        val moved = entry("tasks/a.yml", "x").withPath("tasks/b.yml")
        assertEquals("tasks/b.yml", moved.relPath)
        assertTrue(moved.sameContent(entry("tasks/a.yml", "x")))
    }

    @Test
    fun compareListsChangedOnlyInReferenceAndOnlyHere() {
        val golden = copy("tasks/main.yml" to "a", "defaults/main.yml" to "b", "files/ssl/ca.key" to "k1", "tasks/install.yml" to "c")
        val repo = copy("tasks/main.yml" to "a2", "defaults/main.yml" to "b", "files/ssl/ca.key" to "k2", "tasks/extra.yml" to "d")
        val paths = DriftRules.compare(golden, repo)
        assertEquals(listOf("files/ssl/ca.key", "tasks/main.yml"), paths.changed)
        assertEquals(listOf("tasks/install.yml"), paths.onlyInReference)
        assertEquals(listOf("tasks/extra.yml"), paths.onlyHere)
        assertEquals(setOf("files/ssl/ca.key"), paths.sensitive)
        assertEquals(4, paths.size)
        assertEquals(listOf("files/ssl/ca.key", "tasks/extra.yml", "tasks/install.yml", "tasks/main.yml"), paths.all)
        assertTrue(DriftRules.compare(golden, golden).isEmpty)
    }

    @Test
    fun variantsGroupByteIdenticalCopiesInOrderOfFirstAppearance() {
        val a = copy("tasks/main.yml" to "a")
        val b = copy("tasks/main.yml" to "b")
        val c = copy("tasks/main.yml" to "a", "tasks/x.yml" to "x")
        assertEquals(listOf(0, 1, 0, 2, 1), DriftRules.variants(listOf(a, b, a, c, b)))
        assertEquals(emptyList<Int>(), DriftRules.variants(emptyList()))
    }

    @Test
    fun evaluateAgainstAReference() {
        val golden = copy("tasks/main.yml" to "t", "molecule/default/verify.yml" to "isfile", "meta/argument_specs.yml" to "s")
        val fixed = copy("tasks/main.yml" to "t", "molecule/default/verify.yml" to "isreg", "meta/argument_specs.yml" to "s")
        val spec = copy("tasks/main.yml" to "t", "molecule/default/verify.yml" to "isreg", "meta/argument_specs.yml" to "s2")
        val result = DriftRules.evaluate(listOf(golden, golden, fixed, fixed, spec), referenceIndex = 0, options = DriftOptions())
        assertEquals(
            listOf(DriftTier.REFERENCE, DriftTier.IDENTICAL, DriftTier.MOLECULE_ONLY, DriftTier.MOLECULE_ONLY, DriftTier.SPEC_DEFAULTS),
            result.map { it.tier },
        )
        assertEquals(listOf(0, 0, 1, 1, 2), result.map { it.variant })
        assertEquals(listOf("molecule/default/verify.yml"), result[2].paths.changed)
        assertTrue(result[0].paths.isEmpty && result[1].paths.isEmpty)
        assertEquals(3, result[0].fileCount)

        val ignoring = DriftRules.evaluate(listOf(golden, fixed, spec), referenceIndex = 0, options = DriftOptions(ignoreMolecule = true))
        assertEquals(listOf(DriftTier.REFERENCE, DriftTier.IDENTICAL, DriftTier.SPEC_DEFAULTS), ignoring.map { it.tier })
        assertEquals("with molecule ignored, the fixed copy joins golden's variant", listOf(0, 0, 1), ignoring.map { it.variant })
        assertEquals(2, ignoring[0].fileCount)
    }

    @Test
    fun evaluateWithoutAReferenceGivesVariantsOnly() {
        val a = copy("tasks/main.yml" to "a")
        val b = copy("tasks/main.yml" to "b")
        val result = DriftRules.evaluate(listOf(a, b, a), referenceIndex = null, options = DriftOptions())
        assertEquals(List(3) { DriftTier.NO_REFERENCE }, result.map { it.tier })
        assertEquals(listOf(0, 1, 0), result.map { it.variant })
        assertTrue(result.all { it.paths.isEmpty })
    }
}
