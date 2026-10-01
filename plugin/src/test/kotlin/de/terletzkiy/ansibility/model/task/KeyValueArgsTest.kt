package de.terletzkiy.ansibility.model.task

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The port of ansible-core's `parse_kv` word splitting. */
class KeyValueArgsTest {
    private fun options(parsed: KeyValueArgs.Parsed) = parsed.options.associate { it.key to it.value }

    @Test
    fun keyValueWordsBecomeOptions() {
        val parsed = KeyValueArgs.parse("src=a.j2 dest='/etc/my file' mode=0644", checkRaw = false)
        assertEquals(mapOf("src" to "a.j2", "dest" to "/etc/my file", "mode" to "0644"), options(parsed))
        assertNull(parsed.rawParams)
    }

    @Test
    fun wordsWithoutEqualsAreFreeForm() {
        val parsed = KeyValueArgs.parse("setup.yml", checkRaw = false)
        assertEquals(emptyMap<String, String>(), options(parsed))
        assertEquals("setup.yml", parsed.rawParams)
    }

    @Test
    fun freeFormModulesKeepOnlyTheirOwnOptions() {
        val parsed = KeyValueArgs.parse("echo a=b chdir=/tmp creates=/x", checkRaw = true)
        assertEquals(mapOf("chdir" to "/tmp", "creates" to "/x"), options(parsed))
        assertEquals("echo a=b", parsed.rawParams)
    }

    @Test
    fun quotesAndJinjaDoNotSplit() {
        val text = """msg="{{ a | default('x y') }}" path={{ base }}/{{ name }} "quoted word" """
        val words = KeyValueArgs.words(text).map { text.substring(it.first, it.second) }
        assertEquals(listOf("""msg="{{ a | default('x y') }}"""", "path={{ base }}/{{ name }}", "\"quoted word\""), words)
        val parsed = KeyValueArgs.parse(text, checkRaw = false)
        assertEquals("{{ a | default('x y') }}", options(parsed)["msg"])
        assertEquals("\"quoted word\"", parsed.rawParams)
    }

    @Test
    fun escapedAndLeadingEqualsAreNotSplits() {
        val parsed = KeyValueArgs.parse("""=x a\=b c=d\=e""", checkRaw = false)
        assertEquals(mapOf("c" to """d\=e"""), options(parsed))
        assertEquals("=x a=b", parsed.rawParams)
    }

    @Test
    fun offsetsPointIntoTheText() {
        val text = "src=a.j2  dest='x'"
        val option = KeyValueArgs.parse(text, checkRaw = false).options.single { it.key == "dest" }
        assertEquals("dest", text.substring(option.keyStart, option.keyEnd))
        assertEquals("x", text.substring(option.valueStart, option.valueEnd))
    }
}
