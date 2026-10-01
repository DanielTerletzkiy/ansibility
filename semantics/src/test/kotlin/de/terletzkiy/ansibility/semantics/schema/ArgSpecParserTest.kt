package de.terletzkiy.ansibility.semantics.schema

import de.terletzkiy.ansibility.semantics.json.YValueJson
import de.terletzkiy.ansibility.semantics.testutil.YamlText
import de.terletzkiy.ansibility.semantics.yaml.YEmpty
import de.terletzkiy.ansibility.semantics.yaml.YMap
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ArgSpecParserTest {
    private fun parse(yaml: String, role: String? = "haproxy") = ArgSpecParser.parse(YamlText.parse(yaml.trimIndent()), role)

    @Test
    fun `reads entry points and nested options`() {
        val result = parse(
            """
            argument_specs:
              main:
                short_description: Install HAProxy
                description:
                  - First paragraph.
                  - Second paragraph.
                options:
                  haproxy_servers:
                    type: list
                    elements: dict
                    default: []
                    description: Backend servers.
                    options:
                      name: {type: str, required: true, description: Server name}
                      port:
                        type: int
                        required: true
                        description: Port.
                  haproxy_balance:
                    type: str
                    choices: [roundrobin, leastconn]
                    default: roundrobin
                  untyped:
                    description: No type means str.
            """,
        )
        assertEquals(emptyList<SpecIssue>(), result.issues)
        val main = result.entryPoints.getValue("main")
        assertEquals("Install HAProxy", main.shortDescription)
        assertEquals(listOf("First paragraph.", "Second paragraph."), main.description)
        assertEquals(listOf("haproxy_servers", "haproxy_balance", "untyped"), main.options.keys.toList())

        val servers = main.options.getValue("haproxy_servers")
        assertEquals(OptionType.List, servers.type)
        assertEquals(OptionType.Dict, servers.elements)
        assertEquals(emptyList<Any?>(), YValueJson.toJson(servers.default))
        assertEquals(listOf("Backend servers."), servers.description)
        assertEquals(SpecOrigin.RoleSpec("haproxy", "main"), servers.origin)
        val port = servers.options!!.getValue("port")
        assertEquals(OptionType.Int, port.type)
        assertTrue(port.required)
        assertEquals(SpecOrigin.RoleSpec("haproxy", "main"), port.origin)

        val balance = main.options.getValue("haproxy_balance")
        assertEquals(listOf("roundrobin", "leastconn"), balance.choices!!.values.map(YValueJson::toJson))
        assertEquals("roundrobin", YValueJson.toJson(balance.default))
        assertNull(balance.options, "no sub-options means free-form")

        assertEquals(OptionType.Str, main.options.getValue("untyped").type)
    }

    @Test
    fun `folded and literal descriptions lose trailing line breaks`() {
        val main = parse(
            """
            argument_specs:
              main:
                short_description: >
                  Folded
                  short description
                options:
                  a:
                    type: str
                    description: >
                      Folded lines are
                      joined with spaces.
                  b:
                    type: str
                    description:
                      - |
                        Literal
                        block
                      - plain
            """,
        ).entryPoints.getValue("main")
        assertEquals("Folded short description", main.shortDescription)
        assertEquals(listOf("Folded lines are joined with spaces."), main.options.getValue("a").description)
        assertEquals(listOf("Literal\nblock", "plain"), main.options.getValue("b").description)
    }

    @Test
    fun `dict choices become described choices`() {
        val option = parse(
            """
            argument_specs:
              main:
                options:
                  mode:
                    type: str
                    choices:
                      fast: Go fast.
                      safe: [Be careful., Really.]
            """,
        ).entryPoints.getValue("main").options.getValue("mode")
        val choices = option.choices as Choices.Described
        assertEquals(listOf("fast", "safe"), choices.values.map(YValueJson::toJson))
        assertEquals(listOf(listOf("Go fast."), listOf("Be careful.", "Really.")), choices.described.map { it.second })
    }

    @Test
    fun `keeps aliases, version_added, no_log and deprecation`() {
        val option = parse(
            """
            argument_specs:
              main:
                options:
                  password:
                    type: str
                    aliases: [pass, pwd]
                    no_log: true
                    version_added: "1.2.0"
                    deprecated:
                      why: Replaced.
                      alternative: Use O(secret).
                      removed_in: "3.0.0"
            """,
        ).entryPoints.getValue("main").options.getValue("password")
        assertEquals(listOf("pass", "pwd"), option.aliases)
        assertTrue(option.noLog)
        assertEquals("1.2.0", option.versionAdded)
        assertEquals(Deprecation("Replaced.", "Use O(secret).", "3.0.0", null), option.deprecated)
    }

    @Test
    fun `explicit empty default and empty options are kept apart from absent ones`() {
        val options = parse(
            """
            argument_specs:
              main:
                options:
                  nothing:
                    type: str
                    default:
                  closed:
                    type: dict
                    options: {}
                  open:
                    type: dict
                    options:
            """,
        ).entryPoints.getValue("main").options
        assertTrue(options.getValue("nothing").default is YEmpty)
        assertEquals(emptyMap<String, OptionSpec>(), options.getValue("closed").options)
        assertNull(options.getValue("open").options)
    }

    @Test
    fun `invalid specs are reported, never thrown`() {
        val result = parse(
            """
            argument_specs:
              main:
                short_description: [not, a, string]
                options:
                  typo:
                    type: string
                    required: "false"
                    choices: abc
                    aliases: single
                    descripton: misspelled key
                  scalar_option: just text
                  nested:
                    type: dict
                    options: [a, b]
              broken: 42
            """,
        )
        val messages = result.issues.map { it.path.joinToString(".") + ": " + it.message }
        val main = result.entryPoints.getValue("main")
        val typo = main.options.getValue("typo")
        assertEquals(OptionType.Invalid("string"), typo.type)
        assertTrue(typo.required, "Python truthiness: a non-empty string is true")
        assertNull(typo.choices)
        assertEquals(listOf("single"), typo.aliases)
        assertEquals(OptionType.Str, main.options.getValue("scalar_option").type)
        assertNull(main.options.getValue("nested").options)
        assertTrue(result.entryPoints.containsKey("broken"))

        fun has(prefix: String) = assertTrue(messages.any { it.startsWith(prefix) }, "missing '$prefix' in $messages")
        has("main.short_description: ")
        has("main.typo.type: Unknown type 'string'")
        has("main.typo.required: 'required' should be a boolean")
        has("main.typo.choices: ")
        has("main.typo.aliases: ")
        has("main.typo.descripton: Unknown option key")
        has("main.scalar_option: Option 'scalar_option' must be a mapping")
        has("main.nested.options: ")
        has("broken: Entry point 'broken' must be a mapping")
        assertTrue(result.issues.all { it.range != null }, "every issue points at the source")
    }

    @Test
    fun `documents without argument_specs are reported`() {
        assertTrue(parse("galaxy_info: {}").issues.single().message.contains("argument_specs"))
        assertTrue(ArgSpecParser.parse(YEmpty()).issues.isNotEmpty())
        assertEquals(emptyMap<String, ArgumentSpec>(), parse("argument_specs:").entryPoints)
    }

    @Test
    fun `python truthiness`() {
        fun truthy(yaml: String) = ArgSpecParser.pythonTruthy(YamlText.parse("v: $yaml").let { (it as YMap)["v"] })
        assertTrue(truthy("yes"))
        assertFalse(truthy("no"))
        assertFalse(truthy("0"))
        assertTrue(truthy("'0'"))
        assertFalse(truthy("''"))
        assertFalse(truthy("[]"))
        assertTrue(truthy("[0]"))
        assertFalse(truthy("~"))
        assertFalse(truthy("0.0"))
    }
}
