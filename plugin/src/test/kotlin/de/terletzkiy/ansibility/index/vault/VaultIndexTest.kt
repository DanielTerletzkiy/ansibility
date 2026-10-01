package de.terletzkiy.ansibility.index.vault

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.indexing.FileBasedIndex
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.VaultEnvelopeKind
import de.terletzkiy.ansibility.context.AnsibleWorkspaceImpl
import de.terletzkiy.ansibility.semantics.vault.FormatHint
import de.terletzkiy.ansibility.semantics.vault.VaultEnvelope
import de.terletzkiy.ansibility.semantics.yaml.ScalarStyle
import de.terletzkiy.ansibility.vault.VaultVectors
import de.terletzkiy.ansibility.vault.crypto.VaultCrypto
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.UUID

/**
 * `ansible.vault` through the real `FileBasedIndex` and its root-scoped queries, on synthetic files only: envelopes are
 * generated at test time with synthetic passwords (`tools/vault/SYNTHETIC.md`), sentinels at random.
 */
class VaultIndexTest : BasePlatformTestCase() {
    private fun add(path: String, text: String): VirtualFile = myFixture.addFileToProject(path, text).virtualFile

    private fun entries(file: VirtualFile): List<VaultIndexEntry> = VaultIndexQueries.envelopes(project, file)

    private fun refreshRoots() {
        AnsibleWorkspaceImpl.getInstance(project)?.structureChanged()
    }

    private fun root(path: String): AnsibleRoot {
        val dir = myFixture.findFileInTempDir(path) ?: error("missing $path")
        return AnsibleWorkspace.getInstance(project).roots().single { it.dir == dir }
    }

    private fun envelopeOf(plaintext: String, password: String = VaultVectors.PW1, label: String? = null): VaultEnvelope =
        VaultVectors.encrypt(plaintext, password, label)

    fun testInlineValuesWithHeaderFactsKeyPathsLinesAndStyles() {
        val v11 = envelopeOf("value one")
        val v12 = envelopeOf("a longer value that needs two blocks", VaultVectors.DEV, "dev")
        val text = buildString {
            append("---\n")
            append(VaultVectors.inline("vault_db_password", v11))
            append("users:\n")
            append("  - name: a\n")
            append("    password: !vault |\n")
            v12.formatLines().forEach { append("      ").append(it).append('\n') }
            append("plain: not secret\n")
            append("legacy: !vault-encrypted |\n")
            v11.formatLines().forEach { append("  ").append(it).append('\n') }
        }
        val file = add("site/group_vars/all/vault.yml", text)
        val found = entries(file)
        assertEquals(3, found.size)

        val first = found[0]
        assertEquals(VaultEnvelopeKind.INLINE, first.kind)
        assertEquals(text.indexOf("!vault |"), first.offset)
        assertEquals(listOf("vault_db_password"), first.keyPath)
        assertEquals("vault_db_password", first.keyName)
        assertEquals("1.1", first.version)
        assertEquals("AES256", first.cipher)
        assertNull(first.label)
        assertEquals(v11.ciphertextLength, first.ciphertextLength)
        assertEquals(v11.formatLines().size - 1, first.hexLines)
        assertTrue(first.wellFormed)
        assertEquals(ScalarStyle.LITERAL, first.style)
        assertEquals(text.toByteArray().size.toLong(), first.fileSize)
        assertEquals(file.timeStamp, first.timestamp)

        val second = found[1]
        assertEquals(listOf("users", "0", "password"), second.keyPath)
        assertEquals("password", second.keyName)
        assertEquals("1.2", second.version)
        assertEquals("dev", second.label)
        assertEquals("dev", second.labelOrDefault())
        assertEquals(48, second.ciphertextLength)

        assertEquals("the deprecated tag is a vault too", listOf("legacy"), found[2].keyPath)
        assertEquals("default", first.labelOrDefault())
        assertEquals("ops", first.labelOrDefault("ops"))
        assertEquals(VaultEnvelopeKind.INLINE, first.toInfo(file).kind)
        assertEquals(first.offset, first.toInfo(file).location.offset)
    }

    fun testRawLabelsAreTheKeys() {
        add("site/a.yml", VaultVectors.inline("a", envelopeOf("x")) + VaultVectors.inline("b", envelopeOf("y", VaultVectors.DEV, "dev")))
        val index = FileBasedIndex.getInstance()
        val scope = GlobalSearchScope.allScope(project)
        assertEquals(1, index.getValues(VaultIndex.NAME, "", scope).flatten().size)
        assertEquals(1, index.getValues(VaultIndex.NAME, "dev", scope).flatten().size)
        assertTrue("no effective label is baked into the index", index.getValues(VaultIndex.NAME, "default", scope).isEmpty())
    }

    fun testWholeFileVaultsOfAnyTypeAndNonVaults() {
        val envelope = envelopeOf("-----BEGIN NOTHING-----", label = "new")
        val key = add("site/files/ssl/star.autogen.key", envelope.format())
        val yaml = add("site/group_vars/all/secret.yml", envelopeOf("a: 1").format())
        val plain = add("site/files/readme.txt", "not a vault\n")
        val pem = add("site/certs/x.vault", "\$ANSIBLE_VAULT;1.1;AES256\n" + "zz".repeat(40) + "\n")

        val keyEntry = entries(key).single()
        assertEquals(VaultEnvelopeKind.FILE, keyEntry.kind)
        assertEquals(0, keyEntry.offset)
        assertEquals(emptyList<String>(), keyEntry.keyPath)
        assertNull(keyEntry.keyName)
        assertEquals("1.2", keyEntry.version)
        assertEquals("new", keyEntry.label)
        assertEquals(envelope.formatLines().size - 1, keyEntry.hexLines)
        assertNull(keyEntry.style)
        assertEquals(key.length, keyEntry.fileSize)

        assertEquals("a whole-file vault YAML file has no inline values", VaultEnvelopeKind.FILE, entries(yaml).single().kind)
        assertEquals(emptyList<VaultIndexEntry>(), entries(plain))
        val malformed = entries(pem).single()
        assertEquals(VaultEnvelopeProblem.NON_HEX_DIGIT, malformed.problem)
        assertEquals("1.1", malformed.version)
        assertNull(malformed.ciphertextLength)
    }

    fun testMalformedValuesAreIndexedWithTheirReason() {
        val good = envelopeOf("value")
        val lines = good.formatLines()
        val text = buildString {
            append("lower: !vault |\n")
            append("  \$ANSIBLE_VAULT;1.1;aes256\n")
            lines.drop(1).forEach { append("  ").append(it).append('\n') }
            append("trailing: !vault |\n")
            append("  ").append(lines[0]).append('\n')
            lines.drop(1).forEachIndexed { i, line -> append("  ").append(line).append(if (i == 0) " " else "").append('\n') }
            append("folded: !vault >\n")
            lines.forEach { append("  ").append(it).append('\n') }
            append("empty: !vault\n")
            append("quoted: !vault \" ").append(lines[0]).append("\"\n")
        }
        val found = entries(add("site/host_vars/web1.yml", text)).associateBy { it.keyName }
        assertEquals(VaultEnvelopeProblem.UNKNOWN_CIPHER, found.getValue("lower").problem)
        assertEquals("aes256", found.getValue("lower").cipher)
        assertEquals(VaultEnvelopeProblem.ODD_LENGTH, found.getValue("trailing").problem)
        assertEquals(FormatHint.TRAILING_WHITESPACE, found.getValue("trailing").hint)
        assertEquals(ScalarStyle.FOLDED, found.getValue("folded").style)
        assertEquals(FormatHint.FOLDED_BLOCK, found.getValue("folded").hint)
        assertEquals(0, found.getValue("folded").hexLines)
        assertEquals(VaultEnvelopeProblem.EMPTY, found.getValue("empty").problem)
        assertEquals(VaultEnvelopeProblem.LEADING_WHITESPACE, found.getValue("quoted").problem)
        assertEquals("", found.getValue("quoted").version)
        assertFalse(found.values.any { it.wellFormed })
    }

    fun testVarsFilesWithoutYamlNameAndTemplatesAndFilesYaml() {
        val vars = add("site/group_vars/web", VaultVectors.inline("vault_x", envelopeOf("x")))
        val template = add("site/roles/r/templates/app.yml", VaultVectors.inline("vault_x", envelopeOf("x")))
        val j2 = add("site/roles/r/templates/app.yml.j2", VaultVectors.inline("vault_x", envelopeOf("x")))
        assertEquals(1, entries(vars).size)
        assertEquals("templates are rendered before Ansible loads them", emptyList<VaultIndexEntry>(), entries(template))
        assertEquals(emptyList<VaultIndexEntry>(), entries(j2))
    }

    fun testRootScopedQueriesCountsAndLabelsMappedAtQueryTime() {
        add("one/ansible.cfg", "[defaults]\n")
        add("two/ansible.cfg", "[defaults]\n")
        add("one/group_vars/all/vault.yml", VaultVectors.inline("a", envelopeOf("a")) + VaultVectors.inline("b", envelopeOf("b")))
        add("one/environments/prod/group_vars/all/vault.yml", VaultVectors.inline("c", envelopeOf("c", VaultVectors.DEV, "dev")))
        add("one/files/ssl/web.key", envelopeOf("key").format())
        add("one/host_vars/h1.yml", "broken: !vault |\n  \$ANSIBLE_VAULT;1.1\n  6162\n")
        add("two/group_vars/all/vault.yml", VaultVectors.inline("z", envelopeOf("z")))
        refreshRoots()
        val one = root("one")

        val all = VaultIndexQueries.envelopes(project, one)
        assertEquals(5, all.size)
        assertTrue("root-scoped", all.none { it.file.path.contains("/two/") })
        assertEquals(all.sortedWith(compareBy({ it.file.path }, { it.value.offset })), all)

        assertEquals(VaultCounts(inline = 4, inlineFiles = 3, files = 1, malformed = 1), VaultIndexQueries.counts(project, one))
        assertEquals(listOf("one/files/ssl/web.key"), VaultIndexQueries.wholeFileVaults(project, one).map { it.file.path.substringAfter("/src/") })
        assertEquals(listOf("broken"), VaultIndexQueries.malformed(project, one).map { it.value.keyName })
        assertEquals(1, VaultIndexQueries.envelopesWithRawLabel(project, one, "dev").size)

        val byLabel = VaultIndexQueries.countsByLabel(project, one, defaultIdentity = "default")
        assertEquals(setOf("default", "dev"), byLabel.keys)
        assertEquals(4, byLabel.getValue("default").total)
        assertEquals(1, byLabel.getValue("dev").inline)
        val renamed = VaultIndexQueries.countsByLabel(project, one, defaultIdentity = "ops")
        assertEquals("vault_identity renames the default label at query time", setOf("ops", "dev"), renamed.keys)
        assertEquals(VaultCounts.of(VaultIndexQueries.envelopes(project, root("two"))), VaultIndexQueries.counts(project, root("two")))
        assertEquals(1, VaultIndexQueries.counts(project, root("two")).inline)
    }

    /** Sentinel test (F7.13): the index data never holds plaintext, payload hex, salt, HMAC or ciphertext. */
    fun testNoPlaintextAndNoPayloadInTheIndexData() {
        val sentinel = "ANSIBILITY-SENTINEL-${UUID.randomUUID()}"
        val inline = envelopeOf(sentinel)
        val whole = envelopeOf(sentinel, VaultVectors.PROD, "prod")
        val decryptsBefore = VaultCrypto.getInstance(project).decryptAttempts
        val inlineFile = add("site/group_vars/all/vault.yml", VaultVectors.inline("vault_sentinel", inline))
        val wholeFile = add("site/files/ssh/users/sentinel.key", whole.format())

        val values = entries(inlineFile) + entries(wholeFile)
        assertEquals(2, values.size)
        val bytes = ByteArrayOutputStream().also { out ->
            DataOutputStream(out).use { VaultIndex.EXTERNALIZER.save(it, values) }
        }.toByteArray()
        val serialized = String(bytes, Charsets.ISO_8859_1)
        val printed = values.joinToString("\n") + values.map { it.toInfo(inlineFile) }.joinToString("\n")
        val forbidden = buildList {
            add(sentinel)
            add(sentinel.toByteArray().joinToString("") { "%02x".format(it) })
            for (envelope in listOf(inline, whole)) {
                envelope.formatLines().drop(1).forEach { add(it.take(32)) }
                listOf(envelope.salt, envelope.hmac, envelope.ciphertext).forEach { part -> add(part.joinToString("") { "%02x".format(it) }.take(32)) }
            }
        }
        for (secret in forbidden) {
            assertFalse("index bytes hold a forbidden value", serialized.contains(secret, ignoreCase = true))
            assertFalse("index entries print a forbidden value", printed.contains(secret, ignoreCase = true))
        }
        assertEquals("indexing never decrypts", decryptsBefore, VaultCrypto.getInstance(project).decryptAttempts)
        assertEquals(values, VaultIndex.EXTERNALIZER.read(DataInputStream(bytes.inputStream())))
    }

    fun testInputFilterAndPayloadLineCount() {
        val filter = VaultIndex.InputFilter
        assertTrue(filter.acceptInput(add("x/vars.yml", "a: 1\n")))
        assertTrue(filter.acceptInput(add("x/files/blob.bin", "data\n")))
        assertTrue(filter.acceptInput(add("x/a.key", "data\n")))
        assertTrue(filter.acceptInput(add("x/b.vault", "data\n")))
        assertFalse(filter.acceptInput(add("x/README.md", "# readme\n")))
        assertEquals(0, VaultIndexer.payloadLines("\$ANSIBLE_VAULT;1.1;AES256 6162"))
        assertEquals(2, VaultIndexer.payloadLines("\$ANSIBLE_VAULT;1.1;AES256\n\n6162\n6364\n"))
    }
}
