package de.terletzkiy.ansibility.inspections.vault

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import de.terletzkiy.ansibility.vault.vcs.TrackedStatus
import de.terletzkiy.ansibility.vault.vcs.TrackedStatusLookup
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Throwaway key material for the ANS-V108 plugin tests (plan amendment R21), made at test time and never written to
 * the repository: keys from the JDK's generator, protected keys around random bytes. Markers are assembled at run time,
 * so no committed file holds a complete private-key block (the repository guard in `:semantics`, DEV.md rule 2).
 */
object TestKeys {
    private val random = SecureRandom()

    /** `PRIVATE KEY`, assembled. */
    val PRIVATE: String = "PRIVATE" + " KEY"

    fun begin(label: String): String = "-----BEGIN $label-----"

    fun end(label: String): String = "-----END $label-----"

    private fun randomBytes(count: Int): ByteArray = ByteArray(count).also(random::nextBytes)

    /** A PEM block of [label] around [bytes], with RFC 1421 [headers] when given, [indent] spaces before every line. */
    fun pem(label: String, bytes: ByteArray, headers: List<String> = emptyList(), indent: Int = 0): String {
        val prefix = " ".repeat(indent)
        val lines = buildList {
            add(begin(label))
            if (headers.isNotEmpty()) {
                addAll(headers)
                add("")
            }
            addAll(Base64.getEncoder().encodeToString(bytes).chunked(64))
            add(end(label))
        }
        return lines.joinToString("") { (if (it.isEmpty()) "" else prefix + it) + "\n" }
    }

    /** A fresh EC P-256 private key as PKCS#8 DER. */
    fun pkcs8(): ByteArray = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair().private.encoded

    /** A fresh unencrypted PKCS#8 key (`PRIVATE KEY`), ERROR. */
    fun plaintextKey(indent: Int = 0): String = pem(PRIVATE, pkcs8(), indent = indent)

    /** A passphrase-protected PEM key (`Proc-Type: 4,ENCRYPTED` around random bytes), WARNING. */
    fun protectedKey(): String = pem(
        "RSA $PRIVATE",
        randomBytes(608),
        listOf("Proc-Type: 4,ENCRYPTED", "DEK-Info: AES-128-CBC," + randomBytes(16).joinToString("") { "%02X".format(it) }),
    )

    /** A certificate-shaped block (random bytes): never a finding. */
    fun certificate(): String = pem("CERTIFICATE", randomBytes(700))

    /**
     * A PKCS#12 file whose key sits in a plain `keyBag` (an unprotected key: ERROR, D161), built by hand around a fresh
     * PKCS#8 key: the JDK writes shrouded bags only.
     */
    fun pkcs12WithPlainKey(): ByteArray {
        val data = "2A864886F70D010701"
        val keyBag = seq(oid("2A864886F70D010C0A0101"), tlv(0xA0, pkcs8()))
        val authSafe = seq(seq(oid(data), tlv(0xA0, tlv(0x04, seq(keyBag)))))
        val mac = seq(seq(seq(oid("608648016503040201"), byteArrayOf(5, 0)), tlv(0x04, randomBytes(32))), tlv(0x04, randomBytes(8)), tlv(0x02, byteArrayOf(8)))
        return seq(tlv(0x02, byteArrayOf(3)), seq(oid(data), tlv(0xA0, tlv(0x04, authSafe))), mac)
    }

    /** A JCEKS keystore with a secret key entry (always password-protected: a weaker signal, WARNING). */
    fun jceksWithSecretKey(): ByteArray {
        val store = java.security.KeyStore.getInstance("JCEKS").apply { load(null, null) }
        val password = "changeit".toCharArray()
        store.setEntry("app", java.security.KeyStore.SecretKeyEntry(javax.crypto.spec.SecretKeySpec(randomBytes(32), "AES")), java.security.KeyStore.PasswordProtection(password))
        return java.io.ByteArrayOutputStream().also { store.store(it, password) }.toByteArray()
    }

    private fun tlv(tag: Int, vararg parts: ByteArray): ByteArray {
        val content = parts.fold(ByteArray(0)) { acc, part -> acc + part }
        val n = content.size
        val length = when {
            n < 0x80 -> byteArrayOf(n.toByte())
            n < 0x100 -> byteArrayOf(0x81.toByte(), n.toByte())
            else -> byteArrayOf(0x82.toByte(), (n shr 8).toByte(), n.toByte())
        }
        return byteArrayOf(tag.toByte()) + length + content
    }

    private fun seq(vararg parts: ByteArray): ByteArray = tlv(0x30, *parts)

    private fun oid(hex: String): ByteArray = tlv(0x06, ByteArray(hex.length / 2) { hex.substring(2 * it, 2 * it + 2).toInt(16).toByte() })

    /** The first body line of the PEM [block]: a sentinel no message may carry. */
    fun bodyLine(block: String): String = block.lines().first { it.isNotBlank() && !it.contains("-----") && !it.contains(':') }.trim()
}

/**
 * A scripted [TrackedStatusLookup]: statuses by file name ([default] otherwise), the registered watchers, and VCS
 * repository roots ([repositories]; the innermost one holding a file is its repository).
 */
class FakeStatusLookup(var default: TrackedStatus = TrackedStatus.NO_VCS) : TrackedStatusLookup {
    val statuses = ConcurrentHashMap<String, TrackedStatus>()
    val watchers = CopyOnWriteArrayList<(VirtualFile?) -> Unit>()
    val repositories = CopyOnWriteArrayList<VirtualFile>()

    /** How many statuses were asked for (a status event without a change asks for some, but builds nothing). */
    val asked = java.util.concurrent.atomic.AtomicInteger()

    override fun status(project: Project, file: VirtualFile): TrackedStatus {
        asked.incrementAndGet()
        return statuses[file.name] ?: default
    }

    override fun repositoryOf(project: Project, file: VirtualFile): VirtualFile? =
        repositories.filter { com.intellij.openapi.vfs.VfsUtilCore.isAncestor(it, file, false) }.maxByOrNull { it.path.length }

    override fun watch(project: Project, parent: Disposable, changed: (VirtualFile?) -> Unit) {
        watchers += changed
    }

    /** Tells every watcher that [file]'s status changed (null: many may have). */
    fun fire(file: VirtualFile?) = watchers.forEach { it(file) }
}
