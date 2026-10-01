package de.terletzkiy.ansibility.semantics.schema

import de.terletzkiy.ansibility.semantics.json.Json
import de.terletzkiy.ansibility.semantics.json.YValueJson
import de.terletzkiy.ansibility.semantics.testutil.YamlText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.io.File
import java.math.BigInteger

/**
 * Parity with `ansible-doc -t role -r argspecs <role> -j` (ansible-core 2.21.4) for the synthetic role corpus.
 *
 * Inputs are the roles in `src/test/resources/argspecs/<role>/meta/` (written for this test, they cover every spec shape
 * the parser handles); the expected output is the ansible-doc JSON in `src/test/resources/roledocs/<role>.json`,
 * unmodified except for its repository-relative `path` (regenerate with `tools/docgen/snapshot/regen_roledocs.sh`).
 */
class ArgSpecParityTest {
    private val specDir = File(javaClass.getResource("/argspecs")!!.toURI())
    private val docDir = File(javaClass.getResource("/roledocs")!!.toURI())

    private fun roles(): List<String> = specDir.listFiles { f -> f.isDirectory }!!.map { it.name }.sorted()

    /** The spec file ansible-doc reads: `RoleMixin.ROLE_ARGSPEC_FILES`, argument_specs before main, `.yml`, `.yaml`, `.json`. */
    private fun specFile(role: String): File =
        listOf("argument_specs", "main").flatMap { base -> listOf(".yml", ".yaml", ".json").map { File(specDir, "$role/meta/$base$it") } }
            .first { it.isFile }

    private fun parse(role: String): ArgSpecParseResult = ArgSpecParser.parse(YamlText.parse(specFile(role).readText()), role)

    @TestFactory
    fun `ArgSpecParser equals ansible-doc for every synthetic role`(): List<DynamicTest> {
        val roles = roles()
        assertEquals(listOf("agent", "base", "cache", "db", "mail", "metrics", "proxy", "queue", "users", "web"), roles, "the synthetic roles")
        assertEquals(roles.map { "$it.json" }, docDir.list()!!.sorted(), "one ansible-doc output per role")
        return roles.map { role -> DynamicTest.dynamicTest(role) { checkRole(role) } }
    }

    private fun checkRole(role: String) {
        val parsed = parse(role)
        assertEquals(emptyList<SpecIssue>(), parsed.issues, "synthetic specs are valid")

        val output = Json.parseObject(File(docDir, "$role.json").readText())
        assertEquals(setOf(role), output.keys, "$role: ansible-doc documents exactly this role")
        @Suppress("UNCHECKED_CAST")
        val doc = output[role] as Map<String, Any?>
        assertEquals("semantics/src/test/resources/argspecs/$role", doc["path"], "$role: the regenerated path is repository-relative")
        @Suppress("UNCHECKED_CAST")
        val entryPoints = doc["entry_points"] as Map<String, Map<String, Any?>>
        assertEquals(entryPoints.keys, parsed.entryPoints.keys, "$role: entry points")

        for ((name, expected) in entryPoints) {
            val actual = parsed.entryPoints.getValue(name)
            assertEquals(expected["short_description"], actual.shortDescription, "$role.$name short_description")
            assertEquals(paragraphs(expected["description"]), actual.description, "$role.$name description")
            @Suppress("UNCHECKED_CAST")
            val options = (expected["options"] as Map<String, Map<String, Any?>>?).orEmpty()
            compareOptions(options, actual.options, "$role.$name")
        }
    }

    private fun compareOptions(expected: Map<String, Map<String, Any?>>, actual: Map<String, OptionSpec>, path: String) {
        assertEquals(expected.keys.sorted(), actual.keys.sorted(), "$path: option names")
        for ((name, exp) in expected) {
            val act = actual.getValue(name)
            val where = "$path.$name"
            assertEquals(exp["type"] ?: "str", act.type.name, "$where type")
            assertEquals(exp["elements"], act.elements?.name, "$where elements")
            assertEquals(exp["required"] == true, act.required, "$where required")
            assertEquals(exp.containsKey("default"), act.default != null, "$where has default")
            assertEquals(exp["default"], YValueJson.toJson(act.default), "$where default")
            compareChoices(exp["choices"], act.choices, where)
            assertEquals(paragraphs(exp["description"]), act.description, "$where description")
            assertEquals(exp["aliases"] ?: emptyList<String>(), act.aliases, "$where aliases")
            assertEquals(exp["version_added"], act.versionAdded, "$where version_added")
            assertEquals(exp["no_log"] == true, act.noLog, "$where no_log")
            compareDeprecation(exp["deprecated"], act.deprecated, where)
            @Suppress("UNCHECKED_CAST")
            val nested = exp["options"] as Map<String, Map<String, Any?>>?
            assertEquals(nested != null, act.options != null, "$where has nested options")
            if (nested != null) compareOptions(nested, act.options!!, where)
        }
    }

    /** ansible-doc prints `choices` as given: a list, or a mapping of choice to description (keys become JSON strings). */
    private fun compareChoices(expected: Any?, actual: Choices?, where: String) {
        when (expected) {
            null -> assertEquals(null, actual, "$where choices")
            is Map<*, *> -> {
                assertTrue(actual is Choices.Described, "$where choices are described")
                val described = (actual as Choices.Described).described
                assertEquals(expected.keys.toList(), described.map { (key, _) -> YValueJson.toJson(key).toString() }.sorted(), "$where choice keys")
                assertEquals(
                    expected.entries.associate { (key, text) -> key to paragraphs(text) },
                    described.associate { (key, text) -> YValueJson.toJson(key).toString() to text },
                    "$where choice descriptions",
                )
            }
            else -> {
                assertTrue(actual is Choices.Values, "$where choices are a list")
                assertEquals(expected, actual!!.values.map(YValueJson::toJson), "$where choices")
            }
        }
    }

    private fun compareDeprecation(expected: Any?, actual: Deprecation?, where: String) {
        if (expected == null) return assertEquals(null, actual, "$where deprecated")
        @Suppress("UNCHECKED_CAST")
        val exp = expected as Map<String, Any?>
        assertEquals(
            Deprecation(
                why = exp["why"] as String?,
                alternative = exp["alternative"] as String?,
                removedIn = (exp["removed_in"] ?: exp["removed_at_date"]) as String?,
                removedFromCollection = exp["removed_from_collection"] as String?,
            ),
            actual,
            "$where deprecated",
        )
    }

    /** ansible-doc passes descriptions through: a string or a list; block scalars keep a trailing newline. */
    private fun paragraphs(value: Any?): List<String> = when (value) {
        null -> emptyList()
        is String -> listOf(value.trimEnd('\n'))
        is List<*> -> value.map { (it as String).trimEnd('\n') }
        else -> error("unexpected description $value")
    }

    @TestFactory
    fun `the synthetic corpus exercises every spec shape the parser handles`(): List<DynamicTest> {
        val specs = roles().associateWith { parse(it) }
        val entryPoints = specs.values.flatMap { it.entryPoints.values }
        val all = entryPoints.flatMap { flatten(it.options.values) }
        val texts = roles().associateWith { specFile(it).readText() }
        val paragraphs = entryPoints.flatMap { it.description } + all.flatMap { it.description } +
            all.mapNotNull { it.choices as? Choices.Described }.flatMap { c -> c.described.flatMap { it.second } }
        val defaults = all.mapNotNull { it.default }.map { YValueJson.toJson(it) }
        return listOf(
            DynamicTest.dynamicTest("option count") {
                assertEquals(287, all.size, "synthetic specs declare ${all.size} options including nested ones")
            },
            DynamicTest.dynamicTest("nested options five levels deep") {
                assertTrue(entryPoints.any { depth(it.options) >= 5 })
            },
            DynamicTest.dynamicTest("every type and several element types") {
                val known = listOf("str", "bool", "int", "float", "list", "dict", "path", "raw", "jsonarg", "json", "bytes", "bits")
                assertEquals(known.sorted(), all.map { it.type.name }.distinct().sorted(), "option types")
                assertEquals(
                    listOf("bool", "dict", "float", "int", "list", "path", "raw", "str"),
                    all.mapNotNull { it.elements?.name }.distinct().sorted(),
                    "element types",
                )
                assertTrue(all.any { it.elements == OptionType.Dict && it.options != null }, "dict elements with sub-options")
            },
            DynamicTest.dynamicTest("choices as a list, as a mapping, of integers and on list elements") {
                assertTrue(all.any { it.choices is Choices.Values })
                assertTrue(all.any { it.choices is Choices.Described })
                assertTrue(all.any { o -> o.choices?.values.orEmpty().any { YValueJson.toJson(it) is Long } })
                assertTrue(all.any { it.type == OptionType.List && it.choices != null })
            },
            DynamicTest.dynamicTest("defaults of every JSON kind") {
                assertTrue(all.any { it.default != null && YValueJson.toJson(it.default) == null }, "null default")
                for (kind in listOf(Boolean::class, Long::class, BigInteger::class, Double::class, String::class, List::class, Map::class)) {
                    assertTrue(defaults.any { kind.isInstance(it) }, "a ${kind.simpleName} default")
                }
                assertTrue(defaults.any { it == emptyList<Any>() } && defaults.any { it == emptyMap<String, Any>() } && defaults.any { it == "" })
            },
            DynamicTest.dynamicTest("flags, aliases, version_added and deprecations") {
                assertTrue(all.any { it.required })
                assertTrue(all.any { it.noLog })
                assertTrue(all.any { it.aliases.size >= 2 })
                assertTrue(all.any { it.versionAdded != null })
                assertTrue(all.any { it.deprecated?.removedIn == "3.0.0" }, "deprecation with removed_in")
                assertTrue(all.any { it.deprecated?.removedIn == "2027-06-30" }, "deprecation with removed_at_date")
            },
            DynamicTest.dynamicTest("closed and free-form sub-options") {
                assertTrue(all.any { it.options == emptyMap<String, OptionSpec>() }, "options: {}")
                assertTrue(all.any { it.type == OptionType.Dict && it.options == null }, "free-form dict")
            },
            DynamicTest.dynamicTest("entry points") {
                assertTrue(specs.values.any { it.entryPoints.size >= 4 }, "a role with four entry points")
                assertTrue(entryPoints.any { it.options.isEmpty() && it.shortDescription == null && it.description.isEmpty() }, "a null entry point")
                assertTrue(entryPoints.any { it.shortDescription == null && it.description.isNotEmpty() })
            },
            DynamicTest.dynamicTest("descriptions and markup") {
                assertTrue(all.any { it.description.size >= 2 }, "a description list")
                assertTrue(all.any { it.description.isEmpty() }, "a missing or empty description")
                assertTrue(paragraphs.any { '\n' in it }, "a literal block keeps its line breaks")
                for (token in listOf("C(", "O(", "V(", "U(", "M(", "I(", "B(", "L(", "R(", "E(", "P(", "RV(", "HORIZONTALLINE")) {
                    val markup = Regex("(?<![A-Za-z])" + Regex.escape(token))
                    assertTrue(paragraphs.any { markup.containsMatchIn(it) }, "markup $token")
                }
                assertTrue(paragraphs.any { it.any { c -> c.code > 0x7f } }, "non-ASCII text")
            },
            DynamicTest.dynamicTest("YAML features of the spec files") {
                val text = texts.values.joinToString("\n")
                for (feature in listOf(": &", ": *", "<<: *", "!unsafe", ": >-", ": |-", ": |+", ": >+", "\n...\n")) {
                    assertTrue(feature in text, "YAML feature '${feature.trim()}'")
                }
                assertTrue(roles().any { specFile(it).name == "main.yml" }, "a spec inside meta/main.yml")
            },
        )
    }

    private fun flatten(options: Collection<OptionSpec>): List<OptionSpec> =
        options.flatMap { listOf(it) + flatten(it.options?.values.orEmpty()) }

    private fun depth(options: Map<String, OptionSpec>?): Int =
        if (options.isNullOrEmpty()) 0 else 1 + options.values.maxOf { depth(it.options) }
}
