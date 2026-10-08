package de.terletzkiy.ansibility.semantics.secrets

import java.io.ByteArrayOutputStream
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.EncryptedPrivateKeyInfo
import javax.crypto.KeyGenerator
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.PBEParameterSpec

/**
 * Throwaway key material for the detector tests, made at test time and never written to disk: keys from the JDK's
 * generators, and the other formats (OpenSSH, PuTTY, OpenPGP, ssh.com) built around random bytes. Markers are assembled
 * at run time, so no committed file holds a complete private-key block (secret scanners, DEV.md rule 2).
 */
internal object KeyMaterial {
    private val random = SecureRandom()

    /** `PRIVATE KEY`, assembled. */
    val PRIVATE: String = "PRIVATE" + " KEY"

    fun begin(label: String): String = "-----BEGIN $label-----"

    fun end(label: String): String = "-----END $label-----"

    fun randomBytes(count: Int): ByteArray = ByteArray(count).also(random::nextBytes)

    fun base64Lines(bytes: ByteArray, width: Int = 64): List<String> = Base64.getEncoder().encodeToString(bytes).chunked(width)

    /** A PEM block of [label] around [bytes], with RFC 1421 [headers] (followed by a blank line) when given. */
    fun pem(label: String, bytes: ByteArray, headers: List<String> = emptyList(), width: Int = 64): String = buildString {
        append(begin(label)).append('\n')
        if (headers.isNotEmpty()) {
            headers.forEach { append(it).append('\n') }
            append('\n')
        }
        base64Lines(bytes, width).forEach { append(it).append('\n') }
        append(end(label)).append('\n')
    }

    val rsa: KeyPair by lazy { KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair() }
    val ec: KeyPair by lazy { KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair() }
    val ed25519: KeyPair by lazy { KeyPairGenerator.getInstance("Ed25519").generateKeyPair() }

    /** The private key inside a PKCS#8 PrivateKeyInfo: PKCS#1 for RSA, SEC1 for EC. */
    fun innerKey(pkcs8: ByteArray): ByteArray {
        val top = DerReader(pkcs8).next(0)
        var at = top.contentStart
        DerReader(pkcs8).next(at).also { at = it.end }
        DerReader(pkcs8).next(at).also { at = it.end }
        val octets = DerReader(pkcs8).next(at)
        check(octets.tag == 0x04)
        return pkcs8.copyOfRange(octets.contentStart, octets.end)
    }

    /**
     * PKCS#8 encrypted with PBES2 (the JDK's `PBEWithHmacSHA256AndAES_256`): an `ENCRYPTED PRIVATE KEY`. The JDK
     * encrypts and encodes the PBES2 parameters; the EncryptedPrivateKeyInfo around them is written here, because
     * `EncryptedPrivateKeyInfo` does not take PBES2 parameters by that name.
     */
    fun encryptedPkcs8(pkcs8: ByteArray): ByteArray {
        val algorithm = "PBEWithHmacSHA256AndAES_256"
        val key = SecretKeyFactory.getInstance(algorithm).generateSecret(PBEKeySpec("synthetic-falcon".toCharArray()))
        val cipher = Cipher.getInstance(algorithm)
        cipher.init(Cipher.ENCRYPT_MODE, key, PBEParameterSpec(randomBytes(16), 1000, IvParameterSpec(randomBytes(16))))
        val encrypted = cipher.doFinal(pkcs8)
        val encoded = seq(seq(oid("2A864886F70D01050D"), cipher.parameters.encoded), tlv(0x04, encrypted))
        EncryptedPrivateKeyInfo(encoded).also { check(it.encryptedData.contentEquals(encrypted)) }
        return encoded
    }

    /** OpenSSL's `DSA PRIVATE KEY` structure: version 0 and five INTEGERs (p, q, g, y, x), random here. */
    fun dsa(): ByteArray = seq(tlv(0x02, byteArrayOf(0)), *List(5) { integer(randomBytes(64)) }.toTypedArray())

    // ------------------------------------------------------------------------------------------------ DER

    fun tlv(tag: Int, vararg parts: ByteArray): ByteArray {
        val content = parts.fold(ByteArray(0)) { acc, part -> acc + part }
        val n = content.size
        val length = when {
            n < 0x80 -> byteArrayOf(n.toByte())
            n < 0x100 -> byteArrayOf(0x81.toByte(), n.toByte())
            n < 0x10000 -> byteArrayOf(0x82.toByte(), (n shr 8).toByte(), n.toByte())
            else -> byteArrayOf(0x83.toByte(), (n shr 16).toByte(), (n shr 8).toByte(), n.toByte())
        }
        return byteArrayOf(tag.toByte()) + length + content
    }

    fun seq(vararg parts: ByteArray): ByteArray = tlv(0x30, *parts)

    fun oid(hex: String): ByteArray = tlv(0x06, unhex(hex))

    fun integer(bytes: ByteArray): ByteArray = tlv(0x02, byteArrayOf(0) + bytes)

    fun unhex(hex: String): ByteArray = ByteArray(hex.length / 2) { hex.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    /** A self-signed certificate for [keys] (RSA), built by hand: the JDK has no public certificate builder. */
    fun selfSigned(keys: KeyPair): X509Certificate {
        val algorithm = seq(oid("2A864886F70D01010B"), byteArrayOf(0x05, 0x00))
        val name = seq(tlv(0x31, seq(oid("550403"), tlv(0x0C, "falcon".toByteArray()))))
        val validity = seq(tlv(0x17, "250101000000Z".toByteArray()), tlv(0x17, "350101000000Z".toByteArray()))
        val tbs = seq(tlv(0xA0, tlv(0x02, byteArrayOf(2))), tlv(0x02, byteArrayOf(1)), algorithm, name, validity, name, keys.public.encoded)
        val signature = Signature.getInstance("SHA256withRSA").apply {
            initSign(keys.private)
            update(tbs)
        }.sign()
        val der = seq(tbs, algorithm, tlv(0x03, byteArrayOf(0) + signature))
        return CertificateFactory.getInstance("X.509").generateCertificate(der.inputStream()) as X509Certificate
    }

    val certificate: X509Certificate by lazy { selfSigned(rsa) }

    // ------------------------------------------------------------------------------------------------ keystores

    private val storePassword = "synthetic-tern".toCharArray()

    fun pkcs12WithKey(): ByteArray = store("PKCS12") { setKeyEntry("falcon", rsa.private, storePassword, arrayOf(certificate)) }

    fun jksWithKey(): ByteArray = store("JKS") { setKeyEntry("falcon", rsa.private, storePassword, arrayOf(certificate)) }

    fun jksTruststore(count: Int = 3): ByteArray = store("JKS") { repeat(count) { setCertificateEntry("thrush-$it", certificate) } }

    fun jceksWithSecretKey(): ByteArray = store("JCEKS") {
        setEntry("db", KeyStore.SecretKeyEntry(KeyGenerator.getInstance("AES").apply { init(128) }.generateKey()), KeyStore.PasswordProtection(storePassword))
    }

    private fun store(type: String, fill: KeyStore.() -> Unit): ByteArray {
        val store = KeyStore.getInstance(type)
        store.load(null, null)
        store.fill()
        return ByteArrayOutputStream().also { store.store(it, storePassword) }.toByteArray()
    }

    /** A PKCS#12 PFX around SafeBags written in the clear ([bags]), and optionally an encryptedData content. */
    fun pkcs12Of(bags: List<ByteArray>, encryptedData: Boolean = false): ByteArray {
        val data = "2A864886F70D010701"
        val safeContents = seq(*bags.toTypedArray())
        val contents = mutableListOf(seq(oid(data), tlv(0xA0, tlv(0x04, safeContents))))
        if (encryptedData) contents += seq(oid("2A864886F70D010706"), tlv(0xA0, seq(tlv(0x02, byteArrayOf(0)), seq(oid(data), tlv(0x80, randomBytes(64))))))
        val authSafe = seq(*contents.toTypedArray())
        return seq(tlv(0x02, byteArrayOf(3)), seq(oid(data), tlv(0xA0, tlv(0x04, authSafe))), seq(seq(seq(oid("608648016503040201"), byteArrayOf(5, 0)), tlv(0x04, randomBytes(32))), tlv(0x04, randomBytes(8)), tlv(0x02, byteArrayOf(8))))
    }

    fun keyBag(pkcs8: ByteArray): ByteArray = seq(oid("2A864886F70D010C0A0101"), tlv(0xA0, pkcs8))

    fun certBag(certificate: ByteArray): ByteArray =
        seq(oid("2A864886F70D010C0A0103"), tlv(0xA0, seq(oid("2A864886F70D01091601"), tlv(0xA0, tlv(0x04, certificate)))))

    // ------------------------------------------------------------------------------------------------ other formats

    private fun u32(value: Int): ByteArray = byteArrayOf((value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte())

    private fun sshString(bytes: ByteArray): ByteArray = u32(bytes.size) + bytes

    private fun sshString(text: String): ByteArray = sshString(text.toByteArray(Charsets.US_ASCII))

    /** An `openssh-key-v1` key: [cipher] `none` (plaintext) or a real cipher name, with random key bytes. */
    fun openssh(cipher: String, magic: String = "openssh-key-v1\u0000"): String {
        val kdf = if (cipher == "none") "none" else "bcrypt"
        val kdfOptions = if (cipher == "none") ByteArray(0) else sshString(randomBytes(16)) + u32(16)
        val publicKey = sshString("ssh-ed25519") + sshString(randomBytes(32))
        val bytes = magic.toByteArray(Charsets.US_ASCII) + sshString(cipher) + sshString(kdf) + sshString(kdfOptions) + u32(1) +
            sshString(publicKey) + sshString(randomBytes(144))
        return pem("OPENSSH $PRIVATE", bytes, width = 70)
    }

    /** ssh.com's `SSH2 ENCRYPTED PRIVATE KEY` with [cipher] (`none` is plaintext), a multi-line comment header. */
    fun ssh2(cipher: String): String {
        val body = sshString("if-modn{sign{rsa-pkcs1-sha1},encrypt{rsa-pkcs1v2-oaep}}") + sshString(cipher) + sshString(randomBytes(400))
        val bytes = u32(0x3f6ff9eb) + u32(8 + body.size) + body
        val label = "SSH2 ENCRYPTED $PRIVATE"
        return "---- BEGIN $label ----\nComment: \"rsa-key-falcon \\\ncontinued\"\n" + base64Lines(bytes, 70).joinToString("") { "$it\n" } + "---- END $label ----\n"
    }

    /** A PuTTY key file of [version] 2 or 3 with [encryption] (`none` is plaintext). */
    fun putty(version: Int, encryption: String, privateLines: Boolean = true): String = buildString {
        append("PuTTY-User-Key-File-$version: ssh-ed25519\n")
        append("Encryption: $encryption\n")
        append("Comment: eddsa-key-falcon\n")
        val public = base64Lines(randomBytes(51))
        append("Public-Lines: ${public.size}\n")
        public.forEach { append(it).append('\n') }
        if (version == 3 && encryption != "none") {
            append("Key-Derivation: Argon2id\nArgon2-Memory: 8192\nArgon2-Passes: 13\nArgon2-Parallelism: 1\nArgon2-Salt: ")
            append(randomBytes(16).joinToString("") { "%02x".format(it) }).append('\n')
        }
        if (privateLines) {
            val private = base64Lines(randomBytes(48))
            append("Private-Lines: ${private.size}\n")
            private.forEach { append(it).append('\n') }
        }
        append("Private-MAC: ").append(randomBytes(32).joinToString("") { "%02x".format(it) }).append('\n')
    }

    /**
     * An armored OpenPGP secret key: a v4 Secret-Key packet (tag 5) of [algorithm] (1 = RSA, 22 = EdDSA) with S2K
     * usage [usage] (0 = plaintext, 254 = protected), random key material behind it.
     */
    fun pgp(usage: Int, algorithm: Int = 1): String {
        val publicMaterial = when (algorithm) {
            1 -> mpi(2048) + mpi(17)
            22 -> byteArrayOf(9) + unhex("2B060104 01DA470F01".replace(" ", "")) + mpi(263)
            else -> error("algorithm $algorithm")
        }
        val body = byteArrayOf(4) + randomBytes(4) + byteArrayOf(algorithm.toByte()) + publicMaterial + byteArrayOf(usage.toByte()) + randomBytes(300)
        val length = body.size - 192
        val packet = byteArrayOf(0xC5.toByte(), ((length shr 8) + 192).toByte(), length.toByte()) + body
        val label = "PGP $PRIVATE BLOCK"
        return begin(label) + "\nVersion: falcon 1.0\n\n" + base64Lines(packet).joinToString("") { "$it\n" } + "=AbCd\n" + end(label) + "\n"
    }

    /** An OpenPGP multiprecision integer of [bits] bits (the top bit set), random below it. */
    private fun mpi(bits: Int): ByteArray {
        val bytes = randomBytes((bits + 7) / 8)
        bytes[0] = (bytes[0].toInt() and (0xFF ushr (8 * bytes.size - bits)) or (0x80 ushr (8 * bytes.size - bits))).toByte()
        return byteArrayOf((bits shr 8).toByte(), bits.toByte()) + bytes
    }

    /** One DER element of a test fixture: tag, content start, end. */
    class Element(val tag: Int, val contentStart: Int, val end: Int)

    class DerReader(private val bytes: ByteArray) {
        fun next(at: Int): Element {
            val tag = bytes[at].toInt() and 0xFF
            var length = bytes[at + 1].toInt() and 0xFF
            var start = at + 2
            if (length >= 0x80) {
                val octets = length - 0x80
                length = 0
                repeat(octets) { length = (length shl 8) or (bytes[start++].toInt() and 0xFF) }
            }
            return Element(tag, start, start + length)
        }
    }
}
