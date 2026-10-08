package de.terletzkiy.ansibility.semantics.secrets

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit

/**
 * Opt-in oracle (plan amendment R21): the detector against what `openssl` and `ssh-keygen` really write. Set
 * `ANSIBILITY_KEY_TOOLS` to a directory for throwaway keys (for example a scratch directory); each test makes its keys
 * in a fresh folder below it and deletes it afterwards. Nothing is committed and no real key is ever read.
 */
class PrivateKeyToolOracleTest {
    private lateinit var dir: Path

    @BeforeEach
    fun setUp() {
        val base = System.getenv("ANSIBILITY_KEY_TOOLS")
        assumeTrue(!base.isNullOrBlank(), "ANSIBILITY_KEY_TOOLS is not set")
        Files.createDirectories(Paths.get(base!!))
        dir = Files.createTempDirectory(Paths.get(base), "keys")
    }

    @AfterEach
    fun tearDown() {
        if (::dir.isInitialized) dir.toFile().deleteRecursively()
    }

    private fun run(vararg command: String) {
        val process = try {
            ProcessBuilder(*command).directory(dir.toFile()).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        } catch (e: java.io.IOException) {
            assumeTrue(false, "${command[0]} is not available: ${e.javaClass.simpleName}")
            return
        }
        process.outputStream.close()
        check(process.waitFor(60, TimeUnit.SECONDS)) { "${command[0]} timed out" }
        check(process.exitValue() == 0) { "${command.joinToString(" ")} failed with ${process.exitValue()}" }
    }

    private fun text(name: String): List<Pair<KeyFormat, KeyProtection>> =
        PrivateKeySignatures.scanText(Files.readString(dir.resolve(name))).map { it.format to it.protection }

    private fun binary(name: String): Pair<KeyFormat, KeyProtection>? =
        PrivateKeySignatures.scanBinary(Files.readAllBytes(dir.resolve(name)), name)?.let { it.format to it.protection }

    @Test
    fun `openssl keys, certificates and keystores`() {
        run("openssl", "genpkey", "-algorithm", "RSA", "-pkeyopt", "rsa_keygen_bits:2048", "-out", "k.pem")
        run("openssl", "rsa", "-in", "k.pem", "-traditional", "-out", "rsa.pem")
        run("openssl", "rsa", "-in", "k.pem", "-traditional", "-aes128", "-passout", "pass:synthetic", "-out", "rsa-enc.pem")
        run("openssl", "pkcs8", "-topk8", "-in", "k.pem", "-v2", "aes256", "-passout", "pass:synthetic", "-out", "enc.pem")
        run("openssl", "ecparam", "-name", "prime256v1", "-genkey", "-out", "ec.pem")
        run("openssl", "genpkey", "-algorithm", "ED25519", "-out", "ed.pem")
        run("openssl", "pkey", "-in", "k.pem", "-outform", "DER", "-out", "k.der")
        run("openssl", "rsa", "-in", "k.pem", "-traditional", "-outform", "DER", "-out", "rsa.key")
        run("openssl", "pkcs8", "-topk8", "-in", "k.pem", "-v2", "aes256", "-passout", "pass:synthetic", "-outform", "DER", "-out", "enc.pk8")
        run("openssl", "req", "-x509", "-key", "k.pem", "-subj", "/CN=falcon", "-days", "1", "-out", "cert.pem")
        run("openssl", "x509", "-in", "cert.pem", "-outform", "DER", "-out", "cert.der")
        run("openssl", "pkey", "-in", "k.pem", "-pubout", "-out", "pub.pem")
        run("openssl", "req", "-new", "-key", "k.pem", "-subj", "/CN=falcon", "-out", "csr.pem")
        run("openssl", "pkcs12", "-export", "-inkey", "k.pem", "-in", "cert.pem", "-passout", "pass:synthetic", "-out", "web.p12")
        run("openssl", "pkcs12", "-export", "-inkey", "k.pem", "-in", "cert.pem", "-keypbe", "NONE", "-certpbe", "NONE", "-nomac", "-passout", "pass:", "-out", "plain.p12")
        run("openssl", "pkcs12", "-export", "-nokeys", "-in", "cert.pem", "-certpbe", "NONE", "-nomac", "-passout", "pass:", "-out", "trust.p12")
        run("openssl", "pkcs12", "-export", "-nokeys", "-in", "cert.pem", "-passout", "pass:synthetic", "-out", "trust-enc.p12")
        Files.writeString(dir.resolve("bundle.pem"), Files.readString(dir.resolve("cert.pem")) + Files.readString(dir.resolve("k.pem")))

        assertEquals(listOf(KeyFormat.PKCS8 to KeyProtection.NONE), text("k.pem"))
        assertEquals(listOf(KeyFormat.RSA to KeyProtection.NONE), text("rsa.pem"))
        assertEquals(listOf(KeyFormat.RSA to KeyProtection.PASSPHRASE), text("rsa-enc.pem"))
        assertEquals(listOf(KeyFormat.PKCS8 to KeyProtection.PASSPHRASE), text("enc.pem"))
        assertEquals(listOf(KeyFormat.EC to KeyProtection.NONE), text("ec.pem"), "EC PARAMETERS, then the key")
        assertEquals(listOf(KeyFormat.PKCS8 to KeyProtection.NONE), text("ed.pem"))
        assertEquals(listOf(KeyFormat.PKCS8 to KeyProtection.NONE), text("bundle.pem"))
        assertEquals(emptyList<Pair<KeyFormat, KeyProtection>>(), text("cert.pem"))
        assertEquals(emptyList<Pair<KeyFormat, KeyProtection>>(), text("pub.pem"))
        assertEquals(emptyList<Pair<KeyFormat, KeyProtection>>(), text("csr.pem"))
        assertEquals(KeyFormat.DER to KeyProtection.NONE, binary("k.der"))
        assertEquals(KeyFormat.DER to KeyProtection.NONE, binary("rsa.key"))
        assertEquals(KeyFormat.DER to KeyProtection.PASSPHRASE, binary("enc.pk8"))
        assertNull(binary("cert.der"))
        assertEquals(KeyFormat.PKCS12 to KeyProtection.PASSPHRASE, binary("web.p12"))
        assertEquals(KeyFormat.PKCS12 to KeyProtection.NONE, binary("plain.p12"))
        assertNull(binary("trust.p12"), "certificates only, all in sight")
        assertEquals(KeyFormat.PKCS12 to KeyProtection.UNKNOWN, binary("trust-enc.p12"), "certificates out of sight")
    }

    @Test
    fun `ssh-keygen keys`() {
        run("ssh-keygen", "-q", "-t", "ed25519", "-N", "", "-C", "falcon", "-f", "id_ed25519")
        run("ssh-keygen", "-q", "-t", "ed25519", "-N", "synthetic-tern", "-C", "falcon", "-f", "id_protected")
        run("ssh-keygen", "-q", "-t", "rsa", "-b", "2048", "-m", "PEM", "-N", "", "-C", "falcon", "-f", "id_rsa")
        run("ssh-keygen", "-q", "-t", "ecdsa", "-N", "", "-C", "falcon", "-f", "id_ecdsa")
        assertEquals(listOf(KeyFormat.OPENSSH to KeyProtection.NONE), text("id_ed25519"))
        assertEquals(listOf(KeyFormat.OPENSSH to KeyProtection.PASSPHRASE), text("id_protected"))
        assertEquals(listOf(KeyFormat.RSA to KeyProtection.NONE), text("id_rsa"))
        assertEquals(listOf(KeyFormat.OPENSSH to KeyProtection.NONE), text("id_ecdsa"))
        assertEquals(emptyList<Pair<KeyFormat, KeyProtection>>(), text("id_ed25519.pub"))
        assertNull(binary("id_ed25519"), "a text key is scanText's")
    }
}
