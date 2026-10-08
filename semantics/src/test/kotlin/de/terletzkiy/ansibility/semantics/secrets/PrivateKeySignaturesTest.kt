package de.terletzkiy.ansibility.semantics.secrets

import de.terletzkiy.ansibility.semantics.secrets.KeyMaterial.PRIVATE
import de.terletzkiy.ansibility.semantics.secrets.KeyMaterial.begin
import de.terletzkiy.ansibility.semantics.secrets.KeyMaterial.end
import de.terletzkiy.ansibility.semantics.secrets.KeyMaterial.pem
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.relativeTo

/**
 * The private-key signatures of plan amendment R21 (D160/D161) on throwaway keys made at test time ([KeyMaterial]):
 * what is a key and how it is protected, where keys hide, what never counts, the bounded windows, and a guard that no
 * committed file of the repository holds a complete private key.
 */
class PrivateKeySignaturesTest {
    private val rsaPkcs8: ByteArray get() = KeyMaterial.rsa.private.encoded
    private val ecPkcs8: ByteArray get() = KeyMaterial.ec.private.encoded

    private fun single(text: String): KeyHit {
        val hits = PrivateKeySignatures.scanText(text)
        assertEquals(1, hits.size, hits.toString())
        return hits.single()
    }

    private fun verdict(text: String): Pair<KeyFormat, KeyProtection> = single(text).let { it.format to it.protection }

    // ------------------------------------------------------------------------------------------------ text keys

    @Test
    fun `PEM, OpenSSH, PuTTY, OpenPGP and SSH2 keys are found with their protection`() {
        val rsaProc = listOf("Proc-Type: 4,ENCRYPTED", "DEK-Info: AES-128-CBC,0123456789ABCDEF0123456789ABCDEF")
        val cases = listOf(
            "PKCS#8 RSA" to pem(PRIVATE, rsaPkcs8) to (KeyFormat.PKCS8 to KeyProtection.NONE),
            "PKCS#8 EC" to pem(PRIVATE, ecPkcs8) to (KeyFormat.PKCS8 to KeyProtection.NONE),
            "PKCS#8 Ed25519 (64 characters)" to pem(PRIVATE, KeyMaterial.ed25519.private.encoded) to (KeyFormat.PKCS8 to KeyProtection.NONE),
            "PKCS#1" to pem("RSA $PRIVATE", KeyMaterial.innerKey(rsaPkcs8)) to (KeyFormat.RSA to KeyProtection.NONE),
            "PKCS#1 Proc-Type" to pem("RSA $PRIVATE", KeyMaterial.randomBytes(1200), rsaProc) to (KeyFormat.RSA to KeyProtection.PASSPHRASE),
            "SEC1" to pem("EC $PRIVATE", KeyMaterial.innerKey(ecPkcs8)) to (KeyFormat.EC to KeyProtection.NONE),
            "SEC1 Proc-Type" to pem("EC $PRIVATE", KeyMaterial.randomBytes(128), rsaProc) to (KeyFormat.EC to KeyProtection.PASSPHRASE),
            "DSA" to pem("DSA $PRIVATE", KeyMaterial.dsa()) to (KeyFormat.DSA to KeyProtection.NONE),
            "encrypted PKCS#8" to pem("ENCRYPTED $PRIVATE", KeyMaterial.encryptedPkcs8(rsaPkcs8)) to (KeyFormat.PKCS8 to KeyProtection.PASSPHRASE),
            "other PEM" to pem("ANY $PRIVATE", rsaPkcs8) to (KeyFormat.PEM to KeyProtection.NONE),
            "SEC1 without parameters (52 characters)" to pem("EC $PRIVATE", KeyMaterial.innerKey(ecPkcs8)) to (KeyFormat.EC to KeyProtection.NONE),
            "OpenSSH none" to KeyMaterial.openssh("none") to (KeyFormat.OPENSSH to KeyProtection.NONE),
            "OpenSSH aes256-ctr" to KeyMaterial.openssh("aes256-ctr") to (KeyFormat.OPENSSH to KeyProtection.PASSPHRASE),
            "OpenSSH chacha20" to KeyMaterial.openssh("chacha20-poly1305@openssh.com") to (KeyFormat.OPENSSH to KeyProtection.PASSPHRASE),
            "PuTTY v2 none" to KeyMaterial.putty(2, "none") to (KeyFormat.PUTTY to KeyProtection.NONE),
            "PuTTY v3 none" to KeyMaterial.putty(3, "none") to (KeyFormat.PUTTY to KeyProtection.NONE),
            "PuTTY v3 aes256-cbc" to KeyMaterial.putty(3, "aes256-cbc") to (KeyFormat.PUTTY to KeyProtection.PASSPHRASE),
            "OpenPGP RSA plaintext" to KeyMaterial.pgp(0) to (KeyFormat.PGP to KeyProtection.NONE),
            "OpenPGP RSA protected" to KeyMaterial.pgp(254) to (KeyFormat.PGP to KeyProtection.PASSPHRASE),
            "OpenPGP EdDSA plaintext" to KeyMaterial.pgp(0, algorithm = 22) to (KeyFormat.PGP to KeyProtection.NONE),
            "OpenPGP EdDSA protected" to KeyMaterial.pgp(255, algorithm = 22) to (KeyFormat.PGP to KeyProtection.PASSPHRASE),
            "SSH2 none" to KeyMaterial.ssh2("none") to (KeyFormat.SSH2 to KeyProtection.NONE),
            "SSH2 3des" to KeyMaterial.ssh2("3des-cbc") to (KeyFormat.SSH2 to KeyProtection.PASSPHRASE),
        )
        for ((case, expected) in cases) {
            val (name, text) = case
            val hit = single(text)
            assertEquals(expected, hit.format to hit.protection, name)
            assertEquals(0, hit.line, name)
            assertEquals(0, hit.offset, name)
            val marker = text.substring(hit.offset, hit.offset + hit.length)
            assertTrue(marker.startsWith("-----BEGIN ") || marker.startsWith("---- BEGIN ") || marker.startsWith("PuTTY-User-Key-File-"), "$name: $marker")
            assertFalse(hit.escaped, name)
        }
    }

    @Test
    fun `keys are found inside YAML values, JSON strings, CRLF files and certificate bundles`() {
        val key = pem(PRIVATE, rsaPkcs8)
        val yaml = "---\ntls_cert: web\ntls_key: |\n" + key.lines().filter { it.isNotEmpty() }.joinToString("") { "  $it\n" } + "after: 1\n"
        val inYaml = single(yaml)
        assertEquals(3, inYaml.line)
        assertEquals(begin(PRIVATE), yaml.substring(inYaml.offset, inYaml.offset + inYaml.length))

        val escaped = key.trimEnd('\n').replace("\n", "\\n") + "\\n"
        val json = "{\n  \"type\": \"service_account\",\n  \"private_key\": \"$escaped\",\n  \"client_email\": \"falcon@example.invalid\"\n}\n"
        val inJson = single(json)
        assertEquals(KeyFormat.PKCS8 to KeyProtection.NONE, inJson.format to inJson.protection)
        assertEquals(2, inJson.line)
        assertTrue(inJson.escaped)
        assertEquals(begin(PRIVATE), json.substring(inJson.offset, inJson.offset + inJson.length))
        val crlfEscapes = json.replace("\\n", "\\r\\n")
        assertEquals(KeyFormat.PKCS8, single(crlfEscapes).format)
        val slashes = json.replace("/", "\\/")
        assertTrue(slashes.contains("\\/"), "the body has a slash to escape")
        assertEquals(KeyFormat.PKCS8, single(slashes).format, "JSON may escape slashes")

        val crlf = "# web\r\n" + key.replace("\n", "\r\n")
        assertEquals(1, single(crlf).line)

        val certificate = pem("CERTIFICATE", KeyMaterial.certificate.encoded)
        val bundle = certificate + certificate + key
        val inBundle = single(bundle)
        assertEquals(bundle.lines().indexOf(begin(PRIVATE)), inBundle.line)

        val ecParameters = pem("EC PARAMETERS", KeyMaterial.unhex("06082A8648CE3D030107"))
        assertEquals(KeyFormat.EC, single(ecParameters + pem("EC $PRIVATE", KeyMaterial.innerKey(ecPkcs8))).format)

        val two = key + "\n" + KeyMaterial.openssh("none")
        assertEquals(listOf(KeyFormat.PKCS8, KeyFormat.OPENSSH), PrivateKeySignatures.scanText(two).map { it.format })
    }

    @Test
    fun `certificates, public keys, parameters and incomplete examples are never keys`() {
        val publicKey = KeyMaterial.rsa.public.encoded
        val negatives = mapOf(
            "certificate" to pem("CERTIFICATE", KeyMaterial.certificate.encoded),
            "trusted certificate" to pem("TRUSTED CERTIFICATE", KeyMaterial.certificate.encoded),
            "CSR" to pem("CERTIFICATE REQUEST", KeyMaterial.randomBytes(600)),
            "CRL" to pem("X509 CRL", KeyMaterial.randomBytes(400)),
            "public key" to pem("PUBLIC KEY", publicKey),
            "RSA public key" to pem("RSA PUBLIC KEY", KeyMaterial.randomBytes(270)),
            "PGP public key" to pem("PGP PUBLIC KEY BLOCK", KeyMaterial.randomBytes(400)),
            "PGP signature" to pem("PGP SIGNATURE", KeyMaterial.randomBytes(200)),
            "PKCS7" to pem("PKCS7", KeyMaterial.randomBytes(500)),
            "EC parameters" to pem("EC PARAMETERS", KeyMaterial.unhex("06082A8648CE3D030107")),
            "DH parameters" to pem("DH PARAMETERS", KeyMaterial.randomBytes(264)),
            "ssh public key" to "ssh-ed25519 " + KeyMaterial.base64Lines(KeyMaterial.randomBytes(51), 200).single() + " falcon@tern\n",
            "known_hosts" to "web.example.invalid ssh-ed25519 " + KeyMaterial.base64Lines(KeyMaterial.randomBytes(51), 200).single() + "\n",
            "truncated example" to "tls_private_key: |\n  ${begin(PRIVATE)}\n  MIIE\n",
            "marker in code" to "val marker = \"${begin(PRIVATE)}\"\nval end = \"${end(PRIVATE)}\"\n",
            "short body" to begin(PRIVATE) + "\n" + "MIIE".repeat(11) + "\n" + end(PRIVATE) + "\n",
            "placeholder body" to begin("RSA $PRIVATE") + "\n" + "X".repeat(64) + "\n" + "X".repeat(64) + "\n" + end("RSA $PRIVATE") + "\n",
            "zero body" to begin(PRIVATE) + "\n" + "A".repeat(128) + "\n" + end(PRIVATE) + "\n",
            "elided body" to begin("RSA $PRIVATE") + "\nMIIEowIBAAKCAQEA" + "A".repeat(60) + "\n...\n" + end("RSA $PRIVATE") + "\n",
            "no end" to begin(PRIVATE) + "\n" + KeyMaterial.base64Lines(rsaPkcs8).joinToString("\n") + "\n# the rest is elsewhere\n",
            "mismatched end" to begin("RSA $PRIVATE") + "\n" + KeyMaterial.base64Lines(rsaPkcs8).joinToString("\n") + "\n" + end(PRIVATE) + "\n",
            "OpenSSH without its magic" to KeyMaterial.openssh("none", magic = "not-a-key-v1\u0000\u0000\u0000"),
            "PuTTY without private lines" to KeyMaterial.putty(3, "none", privateLines = false),
            "PuTTY in prose" to "Convert it with puttygen: the file starts with PuTTY-User-Key-File-3: ssh-ed25519\nand so on.\n",
        )
        for ((name, text) in negatives) assertEquals(emptyList<KeyHit>(), PrivateKeySignatures.scanText(text), name)
    }

    @Test
    fun `truncated and placeholder bodies that start like a key are no key`() {
        val firstLine = KeyMaterial.base64Lines(rsaPkcs8).first()
        val negatives = mapOf(
            "one placeholder line" to begin(PRIVATE) + "\nMIIEvQIBADANBgkqhkiG9w0BAQEFAASCBKcwggSjAgEAAoIBAQCREPLACEME\n" + end(PRIVATE) + "\n",
            "the first line of a real key" to begin(PRIVATE) + "\n" + firstLine + "\n" + end(PRIVATE) + "\n",
            "a real key with a line missing" to begin(PRIVATE) + "\n" + KeyMaterial.base64Lines(rsaPkcs8).drop(1).joinToString("\n") + "\n" + end(PRIVATE) + "\n",
            "in a YAML value" to "nginx_ssl_key: |\n  ${begin(PRIVATE)}\n  MIIEvQIBADANBgkqhkiG9w0BAQEFAASCBKcwggSjAgEAAoIBAQCREPLACEME\n  ${end(PRIVATE)}\n",
        )
        for ((name, text) in negatives) assertEquals(emptyList<KeyHit>(), PrivateKeySignatures.scanText(text), name)
        assertEquals(KeyFormat.PKCS8, single(pem(PRIVATE, rsaPkcs8)).format, "the whole key still counts")
    }

    @Test
    fun `keys base64-encoded as a whole, flattened onto one line or in an escaped PuTTY string are found`() {
        fun encoded(text: String) = java.util.Base64.getEncoder().encodeToString(text.toByteArray())
        val key = pem(PRIVATE, rsaPkcs8)
        val certificate = pem("CERTIFICATE", KeyMaterial.certificate.encoded)
        val secret = "apiVersion: v1\nkind: Secret\ntype: kubernetes.io/tls\ndata:\n  tls.crt: ${encoded(certificate)}\n  tls.key: ${encoded(key)}\n"
        val hit = single(secret)
        assertEquals(KeyFormat.PKCS8 to KeyProtection.NONE, hit.format to hit.protection)
        assertTrue(hit.encoded)
        assertEquals(5, hit.line)
        assertEquals("LS0tLS1CRUdJTi", secret.substring(hit.offset, hit.offset + hit.length), "the encoded marker only")
        assertEquals(emptyList<KeyHit>(), PrivateKeySignatures.scanText("data:\n  tls.crt: ${encoded(certificate)}\n"), "an encoded certificate")
        assertTrue(PrivateKeySignatures.mayHoldKey(secret))
        assertEquals(KeyProtection.PASSPHRASE, single("k: " + encoded(KeyMaterial.openssh("aes256-ctr")) + "\n").protection)

        val flattened = begin(PRIVATE) + " " + KeyMaterial.base64Lines(rsaPkcs8).joinToString(" ") + " " + end(PRIVATE)
        val onOneLine = single("TLS_KEY=\"$flattened\"\n")
        assertEquals(KeyFormat.PKCS8 to KeyProtection.NONE, onOneLine.format to onOneLine.protection)
        assertEquals(begin(PRIVATE), "TLS_KEY=\"$flattened\"\n".substring(onOneLine.offset, onOneLine.offset + onOneLine.length))
        val flatPlaceholder = begin(PRIVATE) + " MIIEvQIBADANBgkqhkiG9w0BAQEFAASCBKcwggSjAgEAAoIBAQCREPLACEME " + end(PRIVATE)
        assertEquals(emptyList<KeyHit>(), PrivateKeySignatures.scanText(flatPlaceholder), "a flattened placeholder")

        val putty = KeyMaterial.putty(3, "none").trimEnd('\n').replace("\n", "\\n")
        val json = "{\n  \"ppk\": \"$putty\"\n}\n"
        val inJson = single(json)
        assertEquals(KeyFormat.PUTTY to KeyProtection.NONE, inJson.format to inJson.protection)
        assertTrue(inJson.escaped)
        assertEquals(1, inJson.line)
    }

    @Test
    fun `the scan reads only its window and a cut window counts a running block`() {
        val key = pem(PRIVATE, rsaPkcs8)
        val padding = "#\n".repeat(PrivateKeySignatures.TEXT_WINDOW / 2)
        assertEquals(emptyList<KeyHit>(), PrivateKeySignatures.scanText(padding + key), "after the window")
        assertFalse(PrivateKeySignatures.mayHoldKey(padding + key))
        val beforeEnd = "#\n".repeat((PrivateKeySignatures.TEXT_WINDOW - key.length / 2) / 2) + key
        val cut = beforeEnd.substring(0, PrivateKeySignatures.TEXT_WINDOW - 100)
        assertEquals(emptyList<KeyHit>(), PrivateKeySignatures.scanText(cut), "a complete text without END is no key")
        assertEquals(KeyFormat.PKCS8, PrivateKeySignatures.scanText(cut, complete = false).single().format, "a head of a longer file")
        assertEquals(KeyFormat.PKCS8, PrivateKeySignatures.scanText(beforeEnd.substring(0, PrivateKeySignatures.TEXT_WINDOW - 100) + "x".repeat(200)).single().format,
            "a text longer than the window is a head")
        assertFalse(PrivateKeySignatures.mayHoldKey("plain text\n"))
        assertTrue(PrivateKeySignatures.mayHoldKey(key))
    }

    @Test
    fun `a hit carries the marker's position and nothing of the key`() {
        val key = pem(PRIVATE, rsaPkcs8)
        val body = key.lines()[1]
        val hit = single("# web\n$key")
        assertEquals(1, hit.line)
        assertEquals(6, hit.offset)
        assertEquals(begin(PRIVATE).length, hit.length)
        assertFalse(hit.toString().contains(body.take(12)), hit.toString())
    }

    // ------------------------------------------------------------------------------------------------ binary

    @Test
    fun `keystores and DER keys are judged by their structure`() {
        val p12 = KeyMaterial.pkcs12WithKey()
        assertEquals(KeyFormat.PKCS12 to KeyProtection.PASSPHRASE, binary(p12, "web.p12"), "the JDK writes a shrouded key bag")
        assertEquals(KeyFormat.PKCS12 to KeyProtection.NONE, binary(KeyMaterial.pkcs12Of(listOf(KeyMaterial.keyBag(rsaPkcs8))), "web.pfx"))
        assertNull(binary(KeyMaterial.pkcs12Of(listOf(KeyMaterial.certBag(KeyMaterial.certificate.encoded))), "trust.p12"), "certificates only")
        val encryptedCertificates = KeyMaterial.pkcs12Of(listOf(KeyMaterial.certBag(KeyMaterial.certificate.encoded)), encryptedData = true)
        assertNull(binary(encryptedCertificates, "truststore.p12"), "certificates in an encrypted safe: keytool's and OpenSSL's truststores")
        assertEquals(KeyProtection.UNKNOWN, PrivateKeySignatures.scanBinary(encryptedCertificates, "web.p12", complete = false)?.protection,
            "cut before a key bag could show")

        assertEquals(KeyFormat.JAVA_KEYSTORE to KeyProtection.PASSPHRASE, binary(KeyMaterial.jksWithKey(), "app.jks"))
        assertEquals(KeyFormat.JAVA_KEYSTORE to KeyProtection.PASSPHRASE, binary(KeyMaterial.jceksWithSecretKey(), "app.jceks"))
        val truststore = KeyMaterial.jksTruststore()
        assertNull(binary(truststore, "truststore.jks"), "trusted certificates only")
        val head = truststore.copyOf(truststore.size / 2)
        assertEquals(KeyProtection.UNKNOWN, PrivateKeySignatures.scanBinary(head, "truststore.jks", complete = false)?.protection, "cut before the end")
        assertNull(PrivateKeySignatures.scanBinary(head, "truststore.jks"), "a broken keystore is not judged")

        assertEquals(KeyFormat.DER to KeyProtection.NONE, binary(rsaPkcs8, "web.der"))
        assertEquals(KeyFormat.DER to KeyProtection.NONE, binary(ecPkcs8, "web.key"))
        assertEquals(KeyFormat.DER to KeyProtection.NONE, binary(KeyMaterial.ed25519.private.encoded, "web.p8"))
        assertEquals(KeyFormat.DER to KeyProtection.NONE, binary(KeyMaterial.innerKey(rsaPkcs8), "web.key"), "PKCS#1")
        assertEquals(KeyFormat.DER to KeyProtection.NONE, binary(KeyMaterial.innerKey(ecPkcs8), "web.key"), "SEC1")
        assertEquals(KeyFormat.DER to KeyProtection.NONE, binary(KeyMaterial.dsa(), "dsa.der"), "OpenSSL's DSA key")
        assertEquals(KeyFormat.DER to KeyProtection.PASSPHRASE, binary(KeyMaterial.encryptedPkcs8(rsaPkcs8), "web.pk8"))

        assertNull(binary(KeyMaterial.certificate.encoded, "web.der"), "a certificate")
        assertNull(binary(KeyMaterial.rsa.public.encoded, "web.der"), "a public key")
        assertNull(binary(KeyMaterial.randomBytes(2048), "web.key"))
        assertNull(binary(rsaPkcs8, "web.bin"), "only keystore-like names are read")
        assertNull(binary("\$ANSIBLE_VAULT;1.1;AES256\n6162\n".toByteArray(), "web.key"), "a whole-file vault")
    }

    @Test
    fun `keystore-like names`() {
        for (name in listOf("site.p12", "STORE.PFX", "app.jks", "app.keystore", "trust.truststore", "app.jceks", "web.der", "web.key", "web.p8", "web.pk8", "id_rsa", "id_ed25519")) {
            assertTrue(PrivateKeySignatures.isKeystoreName(name), name)
        }
        for (name in listOf("id_ed25519.pub", "notes.txt", "cert.pem", "cert.crt", "known_hosts", "README")) {
            assertFalse(PrivateKeySignatures.isKeystoreName(name), name)
        }
    }

    private fun binary(bytes: ByteArray, name: String): Pair<KeyFormat, KeyProtection>? =
        PrivateKeySignatures.scanBinary(bytes, name)?.also { assertEquals(-1, it.line) }?.let { it.format to it.protection }

    // ------------------------------------------------------------------------------------------------ guard

    /**
     * No committed source, resource or tool of the repository holds a complete private key (secret scanners, DEV.md
     * rule 2): test keys are made at test time. The infra fixture and the git-ignored local folders are left out.
     */
    @Test
    fun `no committed file holds a complete private key`() {
        val repository: Path = Paths.get("..").toAbsolutePath().normalize()
        val skipped = listOf("plugin/src/test/testData/infra", "tools/fixtures/local", "build", ".gradle")
        val findings = ArrayList<String>()
        var scanned = 0
        for (top in listOf("semantics/src", "plugin/src", "tools", "docs")) {
            val dir = repository.resolve(top)
            if (!Files.isDirectory(dir)) continue
            Files.walk(dir).use { paths ->
                for (path in paths) {
                    val relative = path.relativeTo(repository).toString().replace('\\', '/')
                    if (!path.isRegularFile() || skipped.any { relative.startsWith(it) || "/$it/" in "/$relative" }) continue
                    if (Files.size(path) > PrivateKeySignatures.BINARY_WINDOW) continue
                    val bytes = Files.readAllBytes(path)
                    scanned++
                    val binary = bytes.any { it == 0.toByte() }
                    val hits = if (binary) listOfNotNull(PrivateKeySignatures.scanBinary(bytes, path.name)) else PrivateKeySignatures.scanText(String(bytes, Charsets.UTF_8))
                    hits.forEach { findings += "$relative: ${it.format} ${it.protection} line ${it.line + 1}" }
                }
            }
        }
        assertTrue(scanned > 100, "scanned $scanned files")
        assertEquals(emptyList<String>(), findings)
    }
}
