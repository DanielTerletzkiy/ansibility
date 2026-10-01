package de.terletzkiy.ansibility.semantics.validate

import de.terletzkiy.ansibility.semantics.CoreVersion
import de.terletzkiy.ansibility.semantics.coerce.CoreSemantics
import de.terletzkiy.ansibility.semantics.testutil.YamlText
import de.terletzkiy.ansibility.semantics.value.PyRepr
import de.terletzkiy.ansibility.semantics.value.PyValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ArgumentSpecValidatorTest {
    private val validator = ArgumentSpecValidator()

    private fun run(spec: String, values: String, semantics: CoreSemantics = CoreSemantics.PINNED): ArgSpecResult {
        val params = PyValue.fromYValue(YamlText.map(values)) { "{{" in it.text } as PyValue.Dict
        return ArgumentSpecValidator(semantics).validate(SpecText.options(spec), params)
    }

    @Test
    fun `error order and paths follow ansible-core`() {
        val result = run(
            """
            clients:
              type: list
              elements: dict
              options:
                id: {type: str, required: true}
                port: {type: int, choices: [80, 443]}
            """.trimIndent(),
            "clients: [{id: a, port: '8080'}, {port: 443, extra: 1}, x]",
        )
        // Recorded with ansible-core 2.21.4 for the same spec and values.
        assertEquals(
            listOf("NoLogError", "ElementError", "ArgumentValueError", "RequiredError", "UnsupportedError"),
            result.errors.map { it.errorClass },
        )
        assertEquals(
            "Elements value for option 'clients' is of type <class 'str'> and we were unable to convert to dict: " +
                "dictionary requested, could not parse JSON or key=value",
            result.errors[1].message,
        )
        assertEquals("value of port must be one of: 80, 443, got: 8080 found in clients", result.errors[2].message)
        val element = result.errors.first { it.kind == ErrorKind.ELEMENT }
        assertEquals(listOf("clients", "2"), element.path)
        val required = result.errors.first { it.kind == ErrorKind.REQUIRED }
        assertEquals(listOf("clients", "1"), required.path)
        assertEquals(listOf("id"), required.names)
        assertEquals("missing required arguments: id found in clients", required.message)
        val choice = result.errors.first { it.kind == ErrorKind.ARGUMENT_VALUE }
        assertEquals(listOf("clients", "0", "port"), choice.path)
        val unsupported = result.errors.last()
        assertEquals(listOf(listOf("clients", "1", "extra")), unsupported.unsupportedPaths)
        assertEquals("clients.extra. Supported parameters include: id, port.", unsupported.message)
        assertEquals(
            "{'clients': [{'id': 'a', 'port': 8080}, {'port': 443, 'extra': 1, 'id': None}]}",
            PyRepr.repr(result.validated!!),
        )
    }

    @Test
    fun `aliases copy onto the canonical name`() {
        val result = run("name: {type: str, aliases: [n]}\ncount: {type: int, aliases: [c]}", "n: x\nc: '3'")
        assertTrue(result.accepted)
        assertEquals("{'n': 'x', 'c': '3', 'name': 'x', 'count': 3}", PyRepr.repr(result.validated!!))
    }

    @Test
    fun `none handling and version differences`() {
        val skipped = run("a: {type: int}", "a: ~")
        assertTrue(skipped.accepted, "None is skipped for an optional option without a default")
        val defaulted = run("a: {type: int, default: 1}", "a: ~")
        assertEquals(listOf(ErrorKind.ARGUMENT_TYPE), defaulted.errors.map { it.kind })
        assertTrue(defaulted.errors.single().valueWasNone)
        assertEquals(listOf(ErrorKind.ARGUMENT_TYPE), run("s: {type: str, required: true}", "s: ~").errors.map { it.kind })
        assertTrue(run("s: {type: str, required: true}", "s: ~", CoreSemantics(CoreVersion(2, 21, 4))).accepted)
    }

    @Test
    fun `crashes are reported with the path being validated`() {
        val result = run("a: {type: str}\nb: {type: int}", "a: 1\nb: 'inf'")
        assertNull(result.validated)
        assertEquals(ValidationCrash("OverflowError", "cannot convert Infinity to integer", listOf("b")), result.crash)
    }

    @Test
    fun `templated values are indeterminate, not errors`() {
        val result = run("port: {type: int, choices: [1]}\nd: {type: dict, options: {x: {type: int}}}", "port: '{{ p }}'\nd: {x: '{{ q }}', y: 1}")
        assertEquals(listOf(listOf("port"), listOf("d", "x")), result.indeterminate)
        assertEquals(listOf(ErrorKind.UNSUPPORTED), result.errors.map { it.kind })
    }

    @Test
    fun `raw is not coerced before choices and the bool rescue needs exactly one boolean spelling`() {
        assertEquals(listOf(ErrorKind.ARGUMENT_VALUE), run("m: {type: raw, choices: ['1', '2']}", "m: 2").errors.map { it.kind })
        assertTrue(run("m: {type: str, choices: ['1', '2']}", "m: 2").accepted)
        val rescued = run("f: {type: str, choices: [all, 'no', none, safe, urllib2, 'yes']}", "f: false")
        assertTrue(rescued.accepted)
        assertEquals(PyValue.Str("no"), rescued.validated!!["f"])
        assertEquals(1, run("f: {type: str, choices: ['yes', 'true']}", "f: yes").errors.size)
    }
}
