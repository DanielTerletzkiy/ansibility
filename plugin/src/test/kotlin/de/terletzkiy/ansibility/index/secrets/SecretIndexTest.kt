package de.terletzkiy.ansibility.index.secrets

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.indexing.FileBasedIndex
import de.terletzkiy.ansibility.index.PathFacts
import de.terletzkiy.ansibility.inspections.vault.TestHashes
import de.terletzkiy.ansibility.inspections.vault.TestKeys
import de.terletzkiy.ansibility.semantics.secrets.KeyFormat
import de.terletzkiy.ansibility.semantics.secrets.KeyProtection
import de.terletzkiy.ansibility.semantics.vault.VaultEnvelope
import de.terletzkiy.ansibility.semantics.vault.VaultShapeKind
import de.terletzkiy.ansibility.vault.VaultVectors
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.KeyStore
import javax.crypto.spec.SecretKeySpec

/**
 * `ansibility.secrets` (plan amendment R21, D164) through the real `FileBasedIndex`, on synthetic files only: keys are
 * made at test time ([TestKeys]: markers assembled at run time, throwaway JDK keys), envelopes are encrypted at test
 * time with the synthetic passwords of `tools/vault/SYNTHETIC.md`. The index holds verdicts per kind and never content.
 */
class SecretIndexTest : BasePlatformTestCase() {
    private fun add(path: String, text: String): VirtualFile = myFixture.addFileToProject(path, text).virtualFile

    private fun addBinary(path: String, bytes: ByteArray): VirtualFile {
        val file = myFixture.tempDirFixture.createFile(path)
        WriteAction.runAndWait<Throwable> { file.setBinaryContent(bytes) }
        return file
    }

    private fun verdicts(file: VirtualFile): Map<String, List<SecretVerdict>> =
        FileBasedIndex.getInstance().getFileData(SecretIndex.NAME, file, project)

    /** `encrypt_string` output (`!vault |` then the indented envelope) saved as a file. */
    private fun pasted(envelope: VaultEnvelope, tag: String = "!vault |"): String =
        tag + "\n" + envelope.formatLines().joinToString("") { "  $it\n" }

    private fun envelope(plaintext: String = "secret"): VaultEnvelope = VaultVectors.encrypt(plaintext, VaultVectors.PW1)

    fun testEnvelopesAnsibleDoesNotSeeAreIndexedWithTheirShapeAndLine() {
        val tagLine = add("site/roles/web/files/ssl/web.key", pasted(envelope()))
        val keyed = add("site/roles/web/files/db.password", pasted(envelope(), "db_password: !vault |"))
        val document = add("site/group_vars/all/vault.yml", pasted(envelope()))
        val preamble = add("site/roles/web/templates/app.conf.j2", "# rendered\n\n" + envelope().format())
        assertEquals(
            listOf(SecretVerdict(SecretIndexKind.NOT_WHOLE_FILE, shape = VaultShapeKind.TAG_LINE, line = 0)),
            verdicts(tagLine)[SecretIndexKind.NOT_WHOLE_FILE.name],
        )
        assertEquals(VaultShapeKind.YAML_VALUE, verdicts(keyed).getValue(SecretIndexKind.NOT_WHOLE_FILE.name).single().shape)
        assertEquals(
            "a vars file that is one !vault value",
            VaultShapeKind.VARS_DOCUMENT,
            verdicts(document).getValue(SecretIndexKind.NOT_WHOLE_FILE.name).single().shape,
        )
        val shape = verdicts(preamble).getValue(SecretIndexKind.NOT_WHOLE_FILE.name).single()
        assertEquals(VaultShapeKind.PREAMBLE, shape.shape)
        assertEquals("the header's line", 2, shape.line)
        assertEquals("only the shape", setOf(SecretIndexKind.NOT_WHOLE_FILE.name), verdicts(tagLine).keys)
    }

    /**
     * The platform hands indexers of text types the decoded text without its byte order mark: the mark is read from the
     * file, so a vault behind one is no whole-file vault.
     */
    fun testAByteOrderMarkBeforeAVaultIsNotAWholeFileVault() {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val marked = addBinary("site/roles/web/files/ssl/bom.vault", bom + envelope().format().toByteArray())
        val crlf = addBinary("site/roles/web/files/ssl/crlf.key", pasted(envelope()).replace("\n", "\r\n").toByteArray())
        assertEquals(
            listOf(SecretVerdict(SecretIndexKind.NOT_WHOLE_FILE, shape = VaultShapeKind.BYTE_ORDER_MARK, line = 0)),
            verdicts(marked)[SecretIndexKind.NOT_WHOLE_FILE.name],
        )
        assertEquals(VaultShapeKind.TAG_LINE, verdicts(crlf).getValue(SecretIndexKind.NOT_WHOLE_FILE.name).single().shape)
    }

    /**
     * The mark is read from the file's own bytes, never from the session's `VirtualFile.getBOM` (set by the first reader
     * and not cleared when a pull changes the file): the same bytes give the same verdict, whatever was loaded before.
     */
    fun testTheByteOrderMarkFollowsTheBytesWhenAPullAddsOrRemovesIt() {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val vault = envelope().format().toByteArray()
        val file = addBinary("site/roles/web/files/ssl/flip.vault", vault)
        assertEmpty("a whole-file vault", verdicts(file).keys)
        WriteAction.runAndWait<Throwable> { file.setBinaryContent(bom + vault) }
        assertEquals("a pull added the mark", VaultShapeKind.BYTE_ORDER_MARK, verdicts(file).getValue(SecretIndexKind.NOT_WHOLE_FILE.name).single().shape)
        WriteAction.runAndWait<Throwable> { file.setBinaryContent(vault) }
        assertEmpty("a pull removed it again", verdicts(file).keys)
    }

    fun testNormalVaultsAndInlineValuesAreNotSecretsIndexFindings() {
        val whole = add("site/roles/web/files/ssl/web.key", envelope().format())
        val inline = add("site/group_vars/all/vault.yml", VaultVectors.inline("vault_db_password", envelope()))
        val certificate = add("site/roles/web/files/ssl/web.crt", TestKeys.certificate())
        val empty = add("site/roles/web/files/ssl/empty.key", "")
        assertEmpty("a whole-file vault is ansible.vault's", verdicts(whole).keys)
        assertEmpty("key: !vault | in YAML is the normal form", verdicts(inline).keys)
        assertEmpty("certificates are no keys", verdicts(certificate).keys)
        assertEmpty("an empty file says nothing", verdicts(empty).keys)
    }

    fun testTextKeysWithFormatProtectionAndLine() {
        val key = TestKeys.plaintextKey()
        val bundle = add("docker/certs/haproxy.pem", TestKeys.certificate() + key)
        val protected = add("site/roles/web/files/ssl/old.key", TestKeys.protectedKey())
        val yaml = add("site/group_vars/all/tls.yml", "tls_key: |\n" + TestKeys.plaintextKey(indent = 2))
        val template = add("site/roles/web/templates/key.pem.j2", key)

        val found = verdicts(bundle).getValue(SecretIndexKind.TEXT_KEY.name).single()
        assertEquals(KeyFormat.PKCS8, found.format)
        assertEquals(KeyProtection.NONE, found.protection)
        assertEquals("the key after the certificate", TestKeys.certificate().lines().size - 1, found.line)
        assertEquals(KeyProtection.PASSPHRASE, verdicts(protected).getValue(SecretIndexKind.TEXT_KEY.name).single().protection)
        assertEquals(KeyFormat.RSA, verdicts(protected).getValue(SecretIndexKind.TEXT_KEY.name).single().format)
        assertEquals("a key in a YAML value", 1, verdicts(yaml).getValue(SecretIndexKind.TEXT_KEY.name).single().line)
        assertEquals(KeyFormat.PKCS8, verdicts(template).getValue(SecretIndexKind.TEXT_KEY.name).single().format)
    }

    fun testBinaryKeystoresAndDerKeys() {
        val der = addBinary("site/roles/web/files/ssl/web.der", TestKeys.pkcs8())
        val keystore = KeyStore.getInstance("JCEKS").apply {
            load(null, null)
            setEntry("app", KeyStore.SecretKeyEntry(SecretKeySpec(ByteArray(16) { it.toByte() }, "AES")), KeyStore.PasswordProtection("changeit".toCharArray()))
        }
        val jceks = addBinary("site/roles/web/files/app.jceks", ByteArrayOutputStream().also { keystore.store(it, "changeit".toCharArray()) }.toByteArray())
        val random = addBinary("site/roles/web/files/blob.p12", ByteArray(512) { (it * 7).toByte() })

        val derKey = verdicts(der).getValue(SecretIndexKind.BINARY_KEY.name).single()
        assertEquals(KeyFormat.DER, derKey.format)
        assertEquals(KeyProtection.NONE, derKey.protection)
        assertEquals(-1, derKey.line)
        assertEquals(KeyFormat.JAVA_KEYSTORE, verdicts(jceks).getValue(SecretIndexKind.BINARY_KEY.name).single().format)
        assertEmpty("random bytes with a keystore name are no keystore", verdicts(random).keys)
    }

    fun testKeyLikeNamesWithoutAReadableKey() {
        val plain = add("site/roles/web/files/ssl/web.key", "not a key, not a vault\n")
        val password = add("site/roles/web/files/admin.password", "hunter-two-synthetic\n")
        val blank = add("site/roles/web/files/ssl/blank.key", "\n  \n")
        val elsewhere = add("site/docs/web.key", "not a key\n")
        val binary = addBinary("site/roles/web/files/ssl/bin.key", ByteArray(64) { (it * 13 + 1).toByte() })
        assertEquals(listOf(SecretVerdict(SecretIndexKind.KEY_LIKE_NAME)), verdicts(plain)[SecretIndexKind.KEY_LIKE_NAME.name])
        assertEquals(listOf(SecretVerdict(SecretIndexKind.KEY_LIKE_NAME)), verdicts(password)[SecretIndexKind.KEY_LIKE_NAME.name])
        assertEmpty("blank", verdicts(blank).keys)
        assertEmpty("not below files", verdicts(elsewhere).keys)
        assertEquals("a binary key-like file that is no key either", setOf(SecretIndexKind.KEY_LIKE_NAME.name), verdicts(binary).keys)
    }

    /** User report 2026-10-08: key-like files that hold only password hashes get no verdict (the Vault tab reads them). */
    fun testKeyLikeFilesThatHoldOnlyPasswordHashesGetNoVerdict() {
        val mysql = add("site/roles/db/files/mysql/users/alice.password", TestHashes.MYSQL_NATIVE + "\n")
        val shadow = add("site/roles/db/files/ssh/users/alice.password", TestHashes.SHA512_CRYPT + "\n")
        val bcrypt = add("site/roles/web/files/htpasswd/web.password", "alice:${TestHashes.BCRYPT}\n")
        val plain = add("site/roles/db/files/mysql/users/bob.password", "synthetic-bob-pw\n")
        val mixed = add("site/roles/db/files/ssh/users/carol.password", TestHashes.SHA512_CRYPT + "\nsynthetic-carol-pw\n")
        val hexKey = add("site/roles/web/files/ssl/web.key", TestHashes.sha256Hex("synthetic-alice-pw") + "\n")
        val hexPassword = add("site/roles/web/files/admin.password", TestHashes.sha256Hex("synthetic-alice-pw") + "\n")
        val postgres = add("site/roles/db/files/postgres/users/bob.password", TestHashes.postgresMd5() + "\n")
        val key = add("site/roles/db/files/ssh/users/deploy.password", TestHashes.SHA512_CRYPT + "\n" + TestKeys.plaintextKey())
        for (file in listOf(mysql, shadow, bcrypt)) assertEmpty(file.path, verdicts(file).keys)
        for (file in listOf(plain, mixed, hexKey, hexPassword, postgres)) {
            assertEquals(file.path, mapOf(SecretIndexKind.KEY_LIKE_NAME.name to listOf(SecretVerdict(SecretIndexKind.KEY_LIKE_NAME))), verdicts(file))
        }
        assertEquals("a private key after a hash is still a key", setOf(SecretIndexKind.TEXT_KEY.name), verdicts(key).keys)
        assertEquals(KeyProtection.NONE, verdicts(key).getValue(SecretIndexKind.TEXT_KEY.name).single().protection)

        WriteAction.runAndWait<Throwable> { mysql.setBinaryContent("synthetic-alice-pw\n".toByteArray()) }
        assertEquals("a hash replaced by a plaintext password", setOf(SecretIndexKind.KEY_LIKE_NAME.name), verdicts(mysql).keys)
    }

    /**
     * A raw MySQL `caching_sha2_password` value whose salt holds control characters makes the IDE read the file as
     * binary (no ANS-V108 run): the index judges its bytes, so the Vault tab does not list it either.
     */
    fun testABinaryKeyLikeFileOfPasswordHashesGetsNoVerdict() {
        val raw = addBinary("site/roles/db/files/mysql/users/alice.password", TestHashes.cachingSha2Raw())
        assertTrue("the control characters make it binary", raw.fileType.isBinary)
        assertEmpty(verdicts(raw).keys)

        val plain = addBinary("site/roles/db/files/mysql/users/bob.password", "synthetic-bob-pw\u0001\u0002\n".toByteArray())
        assertTrue(plain.fileType.isBinary)
        assertEquals("a binary plaintext", setOf(SecretIndexKind.KEY_LIKE_NAME.name), verdicts(plain).keys)
    }

    fun testProjectWideQueriesAreByKind() {
        add("a/roles/web/files/ssl/web.key", pasted(envelope()))
        add("b/roles/db/files/ssl/db.pem", TestKeys.plaintextKey())
        add("b/roles/db/files/ssl/other.pem", TestKeys.plaintextKey())
        val scope = GlobalSearchScope.projectScope(project)
        val index = FileBasedIndex.getInstance()
        assertEquals(1, index.getContainingFiles(SecretIndex.NAME, SecretIndexKind.NOT_WHOLE_FILE.name, scope).size)
        assertEquals(setOf("db.pem", "other.pem"), index.getContainingFiles(SecretIndex.NAME, SecretIndexKind.TEXT_KEY.name, scope).map { it.name }.toSet())
    }

    /** The values hold kinds, lines and protection only: no key body, no envelope, no payload hex, no plaintext. */
    fun testNothingButVerdictsIsStored() {
        val sentinel = "ANSIBILITY-SENTINEL-" + java.util.UUID.randomUUID()
        val wrapped = envelope(sentinel)
        val key = TestKeys.plaintextKey()
        val files = listOf(
            add("site/roles/web/files/ssl/wrapped.key", pasted(wrapped)),
            add("site/roles/web/files/ssl/plain.pem", key),
            add("site/roles/web/files/ssl/name.key", "$sentinel\n"),
        )
        val stored = ByteArrayOutputStream()
        DataOutputStream(stored).use { out -> files.forEach { file -> verdicts(file).values.forEach { SecretIndex.EXTERNALIZER.save(out, it) } } }
        val bytes = String(stored.toByteArray(), Charsets.ISO_8859_1)
        val texts = files.flatMap { file -> verdicts(file).values.flatten().map { it.toString() } }
        val forbidden = listOf(sentinel, TestKeys.bodyLine(key).take(16), wrapped.formatLines()[1].take(16), TestKeys.PRIVATE)
        for (secret in forbidden) {
            assertFalse("index bytes hold $secret", secret in bytes)
            assertTrue("a verdict prints $secret", texts.none { secret in it })
        }
        assertEquals(3, files.count { verdicts(it).isNotEmpty() })
    }

    fun testTheInputIsDecidedByThePathAlone() {
        fun candidate(path: String): Boolean {
            val segments = path.split('/')
            return SecretIndex.isCandidate(segments.last(), segments.dropLast(1).asReversed(), PathFacts.of(path))
        }
        for (path in listOf(
            "site/roles/web/files/anything.txt", "site/roles/web/templates/app.conf", "certs/web.p12", "certs/web.pfx", "x/app.jks",
            "keys/id_ed25519", "keys/id_rsa", "docker/web.pem", "docker/server.key", "site/group_vars/all/vault.yml", "site/group_vars/web",
            "playbook.yml", "conf/app.conf.j2", "keys/web.ppk",
        )) assertTrue(path, candidate(path))
        for (path in listOf("README.md", "src/main.py", "keys/id_rsa.pub", "docs/notes.txt", "package.json")) assertFalse(path, candidate(path))
        assertTrue(SecretIndex.isCandidatePath("/home/u/repo/site/roles/web/files/ssl/web.key"))
        assertFalse(SecretIndex.isCandidatePath("/home/u/repo/README.md"))
    }
}
