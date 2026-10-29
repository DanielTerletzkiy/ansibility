package de.terletzkiy.ansibility.semantics.vault

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.security.SecureRandom

/** Plan amendment R29, D217/D218 rule 2: "same as now" shapes, short values, and values that are no password. */
class SecretShapeTest {
    private fun shape(text: String): SecretShape = SecretShape.of(text.toByteArray(Charsets.UTF_8))

    private fun generator(text: String): SecretGenerator = (shape(text) as SecretShape.Password).generator

    private fun reason(text: String): NotPasswordReason = (shape(text) as SecretShape.NotPassword).reason

    private val random: SecureRandom = SecureRandom.getInstance("SHA1PRNG").apply { setSeed(218) }

    private fun made(kind: GeneratorKind, length: Int, upper: Boolean = false, padded: Boolean = false): String =
        String(SecretGenerator(kind, length, upper, padded).generate(random), Charsets.US_ASCII)

    @Test
    fun `hex keeps its length and case`() {
        assertEquals(SecretGenerator(GeneratorKind.HEX, 32), generator("0f1e2d3c4b5a69788796a5b4c3d2e1f0"))
        assertEquals(SecretGenerator(GeneratorKind.HEX, 32, upperHex = true), generator("0F1E2D3C4B5A69788796A5B4C3D2E1F0"))
        assertEquals(SecretGenerator(GeneratorKind.HEX, 7), generator("abc1234"))
        // Mixed case is no hex value: alphanumeric.
        assertEquals(SecretGenerator(GeneratorKind.ALPHANUMERIC, 8), generator("DeadBeef"))
    }

    @Test
    fun `an all-digit value is alphanumeric, not hex`() {
        assertEquals(SecretGenerator(GeneratorKind.ALPHANUMERIC, 8), generator("12345678"))
        assertTrue((shape("12345678") as SecretShape.Password).short)
    }

    @Test
    fun `base64 keeps its decoded byte count`() {
        // openssl rand -base64 32: 44 characters ending in '='.
        val value = "q83vEjRWeJq8ELYyzw/D+8rG2RkzWl1vrc+1FnN8E2U="
        assertEquals(44, value.length)
        assertEquals(SecretGenerator(GeneratorKind.BASE64, 32), generator(value))
        assertEquals(SecretGenerator(GeneratorKind.BASE64, 16), generator("AAECAwQFBgcICQoLDA0ODw=="))
        assertEquals(SecretGenerator(GeneratorKind.BASE64, 3), generator("a+/b"))
    }

    @Test
    fun `a 44-character alphanumeric value ending in = is base64 of 32 bytes`() {
        val value = "Qz8".repeat(14) + "Q="
        assertEquals(SecretGenerator(GeneratorKind.BASE64, 32), generator(value))
        // Without the '=' (and without '+' or '/') the same characters are plain alphanumeric.
        assertEquals(SecretGenerator(GeneratorKind.ALPHANUMERIC, 44), generator("Qz8".repeat(14) + "Qz"))
        // '=' in the middle, or three of them, is no base64: symbols.
        assertEquals(GeneratorKind.ALPHANUMERIC_SYMBOLS, generator("AB=C").kind)
        assertEquals(GeneratorKind.ALPHANUMERIC_SYMBOLS, generator("A===").kind)
    }

    @Test
    fun `URL-safe base64 keeps its byte count and padding`() {
        assertEquals(SecretGenerator(GeneratorKind.BASE64_URL, 32), generator("q83vEjRWeJq8ELYyzw_D-8rG2RkzWl1vrc-1FnN8E2U"))
        assertEquals(SecretGenerator(GeneratorKind.BASE64_URL, 32, padded = true), generator("q83vEjRWeJq8ELYyzw_D-8rG2RkzWl1vrc-1FnN8E2U="))
        // A length no base64 has (4n + 1) with '-' is alphanumeric with symbols.
        assertEquals(GeneratorKind.ALPHANUMERIC_SYMBOLS, generator("ab-cd").kind)
    }

    @Test
    fun `alphanumeric and symbols keep their length`() {
        assertEquals(SecretGenerator(GeneratorKind.ALPHANUMERIC, 32), generator("aZ09".repeat(8)))
        assertEquals(SecretGenerator(GeneratorKind.ALPHANUMERIC_SYMBOLS, 20), generator("s3cr3t!pa\$\$word#2026"))
        // Non-ASCII letters are printable: symbols, length in characters.
        assertEquals(SecretGenerator(GeneratorKind.ALPHANUMERIC_SYMBOLS, 9), generator("pässwört9"))
    }

    @Test
    fun `short values are marked and keep their length`() {
        val vrrp = shape("Ab3dEf7h") as SecretShape.Password
        assertTrue(vrrp.short)
        assertEquals(SecretGenerator(GeneratorKind.ALPHANUMERIC, 8), vrrp.generator)
        assertFalse((shape("a".repeat(16)) as SecretShape.Password).short)
        assertTrue((shape("a".repeat(15)) as SecretShape.Password).short)
    }

    @Test
    fun `values that are no password say why`() {
        assertEquals(NotPasswordReason.EMPTY, reason(""))
        assertEquals(NotPasswordReason.SEVERAL_LINES, reason("line one\nline two"))
        assertEquals(NotPasswordReason.SEVERAL_LINES, reason("secret\n"))
        assertEquals(NotPasswordReason.PEM_BLOCK, reason("-----BEGIN CERTIFICATE-----\nMIIB\n-----END CERTIFICATE-----\n"))
        assertEquals(NotPasswordReason.STRUCTURED, reason("""{"user":"app"}"""))
        assertEquals(NotPasswordReason.STRUCTURED, reason("[1,2]"))
        assertEquals(NotPasswordReason.STRUCTURED, reason("---"))
        assertEquals(NotPasswordReason.SPACES, reason("correct horse battery staple"))
        assertEquals(NotPasswordReason.CONTROL_CHARACTERS, reason("ab\u0001cd"))
        assertEquals(NotPasswordReason.TOO_LONG, reason("a".repeat(513)))
        assertTrue(shape("a".repeat(512)) is SecretShape.Password)
        assertEquals(NotPasswordReason.NOT_UTF8, (SecretShape.of(byteArrayOf(0xC3.toByte(), 0x28)) as SecretShape.NotPassword).reason)
    }

    @Test
    fun `a generated value has the shape of its generator`() {
        val generators = listOf(
            SecretGenerator(GeneratorKind.ALPHANUMERIC, 32),
            SecretGenerator(GeneratorKind.HEX, 32),
            SecretGenerator(GeneratorKind.HEX, 16, upperHex = true),
            SecretGenerator(GeneratorKind.BASE64, 32),
            SecretGenerator(GeneratorKind.BASE64_URL, 32),
        )
        for (generator in generators) {
            // A drawn value may by chance miss the marks of its kind (no '+', '/' or a letter a-f); most do not.
            val matches = (1..20).count { generator(made(generator.kind, generator.length, generator.upperHex, generator.padded)) == generator }
            assertTrue(matches >= 15, "$generator: $matches of 20")
        }
    }

    @Test
    fun `the shape reads the bytes without changing them`() {
        val bytes = "0f1e2d3c".toByteArray()
        val copy = bytes.copyOf()
        SecretShape.of(bytes)
        assertArrayEquals(copy, bytes)
    }
}
