package de.terletzkiy.ansibility.semantics.vault

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.security.SecureRandom
import java.util.Base64

/** Plan amendment R29, D218: every generator's alphabet and length, unbiased draws, injected randomness. */
class SecretGeneratorTest {
    /** A deterministic SecureRandom: the same seed gives the same values. */
    private fun seeded(seed: Long = 29): SecureRandom = SecureRandom.getInstance("SHA1PRNG").apply { setSeed(seed) }

    private fun ascii(bytes: ByteArray): String = String(bytes, Charsets.US_ASCII)

    @Test
    fun `alphanumeric values have the length and only letters and digits`() {
        val random = seeded()
        for (length in listOf(1, 8, 16, 32, 64, 512)) {
            val value = ascii(SecretGenerator(GeneratorKind.ALPHANUMERIC, length).generate(random))
            assertEquals(length, value.length)
            assertTrue(value.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' }, value)
        }
    }

    @Test
    fun `hex values are lowercase unless asked for uppercase, odd lengths included`() {
        val random = seeded()
        val lower = ascii(SecretGenerator(GeneratorKind.HEX, 32).generate(random))
        assertEquals(32, lower.length)
        assertTrue(lower.all { it in '0'..'9' || it in 'a'..'f' }, lower)
        val upper = ascii(SecretGenerator(GeneratorKind.HEX, 31, upperHex = true).generate(random))
        assertEquals(31, upper.length)
        assertTrue(upper.all { it in '0'..'9' || it in 'A'..'F' }, upper)
    }

    @Test
    fun `base64 encodes the number of random bytes, padded`() {
        val random = seeded()
        for (bytes in listOf(1, 2, 3, 16, 32, 33)) {
            val generator = SecretGenerator(GeneratorKind.BASE64, bytes)
            val value = generator.generate(random)
            assertEquals(generator.characters, value.size)
            assertEquals(0, value.size % 4)
            assertEquals(bytes, Base64.getDecoder().decode(value).size)
            assertTrue(ascii(value).all { it in SecretGenerator.alphabet(GeneratorKind.BASE64) })
        }
        assertEquals(44, SecretGenerator(GeneratorKind.BASE64, 32).characters)
    }

    @Test
    fun `URL-safe base64 is unpadded unless asked, and decodes to the byte count`() {
        val random = seeded()
        val unpadded = SecretGenerator(GeneratorKind.BASE64_URL, 32)
        val value = unpadded.generate(random)
        assertEquals(43, value.size)
        assertEquals(unpadded.characters, value.size)
        assertFalse(ascii(value).any { it == '=' || it == '+' || it == '/' })
        assertEquals(32, Base64.getUrlDecoder().decode(value).size)
        val padded = SecretGenerator(GeneratorKind.BASE64_URL, 32, padded = true).generate(random)
        assertEquals(44, padded.size)
        assertEquals('='.code.toByte(), padded.last())
    }

    @Test
    fun `alphanumeric with symbols uses exactly the documented symbols and never starts with one`() {
        assertEquals("-_.!#%+=", SecretGenerator.SYMBOLS)
        val random = seeded()
        val seen = HashSet<Char>()
        repeat(400) {
            val value = ascii(SecretGenerator(GeneratorKind.ALPHANUMERIC_SYMBOLS, 24).generate(random))
            assertEquals(24, value.length)
            assertTrue(value[0].isLetterOrDigit(), value)
            assertTrue(value.all { it.isLetterOrDigit() && it.code < 128 || it in SecretGenerator.SYMBOLS }, value)
            seen += value.toSet()
        }
        // Every symbol and every alphanumeric character turns up over many draws.
        assertEquals(SecretGenerator.alphabet(GeneratorKind.ALPHANUMERIC_SYMBOLS).toSet(), seen)
    }

    @Test
    fun `every character of each alphabet appears over many draws and none dominates`() {
        val random = seeded(7)
        for ((kind, upper) in listOf(GeneratorKind.ALPHANUMERIC to false, GeneratorKind.HEX to false, GeneratorKind.HEX to true)) {
            val alphabet = SecretGenerator.alphabet(kind, upper)
            val counts = HashMap<Char, Int>()
            repeat(200) { for (b in SecretGenerator(kind, 100, upperHex = upper).generate(random)) counts.merge(b.toInt().toChar(), 1, Int::plus) }
            assertEquals(alphabet.toSet(), counts.keys, "$kind")
            // 20,000 draws: an unbiased draw gives each of the k characters about 20000/k; a modulo bias of 256 % 62
            // would give the first 8 characters 25 % more. A loose band catches gross bias without flaking.
            val expected = 20_000.0 / alphabet.length
            for ((char, count) in counts) assertTrue(count in (expected * 0.75).toInt()..(expected * 1.25).toInt(), "$kind '$char': $count of ~$expected")
        }
    }

    @Test
    fun `the injected random decides the value`() {
        val generator = SecretGenerator(GeneratorKind.ALPHANUMERIC, 32)
        assertArrayEquals(generator.generate(seeded(1)), generator.generate(seeded(1)))
        assertNotEquals(ascii(generator.generate(seeded(1))), ascii(generator.generate(seeded(2))))
    }

    @Test
    fun `lengths are bounded and the default is 32 alphanumeric`() {
        assertEquals(SecretGenerator(GeneratorKind.ALPHANUMERIC, 32), SecretGenerator.DEFAULT)
        assertThrows(IllegalArgumentException::class.java) { SecretGenerator(GeneratorKind.ALPHANUMERIC, 0) }
        assertThrows(IllegalArgumentException::class.java) { SecretGenerator(GeneratorKind.HEX, 513) }
        assertThrows(IllegalArgumentException::class.java) { SecretGenerator(GeneratorKind.BASE64, 385) }
        assertEquals(512, SecretGenerator(GeneratorKind.BASE64, 384).characters)
    }

    @Test
    fun `a generator prints its recipe, never a value`() {
        val text = SecretGenerator(GeneratorKind.HEX, 32).toString()
        assertTrue("HEX" in text && "32" in text, text)
    }
}
