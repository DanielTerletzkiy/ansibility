package de.terletzkiy.ansibility.semantics.schema

import de.terletzkiy.ansibility.semantics.CoreVersion
import de.terletzkiy.ansibility.semantics.json.Json
import de.terletzkiy.ansibility.semantics.json.YValueJson
import de.terletzkiy.ansibility.semantics.markup.AnsibleDocMarkup
import de.terletzkiy.ansibility.semantics.markup.MarkupPart
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import java.io.File
import java.io.StringReader

class DocSnapshotTest {
    companion object {
        /** plugin/src/main/resources/ansible-data, found from the test's working directory (the :semantics project). */
        fun dataFile(name: String): File {
            var dir: File? = File(System.getProperty("user.dir")).absoluteFile
            while (dir != null && !File(dir, "settings.gradle.kts").exists()) dir = dir.parentFile
            return File(requireNotNull(dir) { "project root not found" }, "plugin/src/main/resources/ansible-data/$name")
        }
    }

    /** The bundled ansible-core 2.18.8 snapshot with the target repo's pinned collections. */
    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    inner class Pinned {
        private lateinit var snapshot: DocSnapshot

        @BeforeAll
        fun load() {
            val file = dataFile("core-2.18.8.json.gz")
            val start = System.nanoTime()
            snapshot = file.inputStream().use { DocSnapshot.load(it) }
            val millis = (System.nanoTime() - start) / 1_000_000
            println("loaded ${file.name} (${file.length() / 1024} KB gz) in $millis ms: ${snapshot.modules.size} modules")
        }

        @Test
        fun `metadata and pinned collection versions`() {
            assertEquals("2.18.8", snapshot.core)
            assertEquals(CoreVersion(2, 18, 8), snapshot.coreVersion)
            assertEquals(
                mapOf(
                    "ansible.builtin" to "2.18.8", "ansible.mysql" to "5.2.0", "ansible.posix" to "2.2.2", "community.crypto" to "3.3.0",
                    "community.docker" to "5.2.1", "community.general" to "13.2.0", "community.mysql" to "5.0.2",
                ),
                snapshot.collections,
            )
            assertTrue(snapshot.modules.size > 700, "${snapshot.modules.size} modules")
        }

        @Test
        fun `template has its 25 options and dest is a required path`() {
            val template = snapshot.module("ansible.builtin.template").doc!!
            assertEquals(25, template.options.size)
            val dest = template.options.getValue("dest")
            assertEquals(OptionType.Path, dest.type)
            assertTrue(dest.required)
            assertEquals(SpecOrigin.ModuleDoc("ansible.builtin.template", "bundled 2.18.8"), dest.origin)
            assertEquals(OptionType.Path, template.options.getValue("src").type)
            assertEquals(OptionType.Raw, template.options.getValue("mode").type)
            assertEquals("Template a file out to a target host", template.shortDescription)
            assertEquals("ansible/modules/template.py", template.filename)
            assertEquals("full", template.attributes["check_mode"])
            assertEquals("posix", template.attributes["platform"])
            assertTrue("M(ansible.builtin.copy)" in template.seeAlso)
            assertTrue(template.examples!!.startsWith("- name:"))
            assertEquals("bundled 2.18.8", template.source)
            assertTrue(template.returns.containsKey("checksum"))
        }

        @Test
        fun `docker_container mounts has its sub-options`() {
            val mounts = snapshot.module("community.docker.docker_container").doc!!.options.getValue("mounts")
            assertEquals(OptionType.List, mounts.type)
            assertEquals(OptionType.Dict, mounts.elements)
            val sub = mounts.options!!
            assertEquals(18, sub.size)
            assertTrue(sub.getValue("target").required)
            val types = sub.getValue("type").choices!!.values.map(YValueJson::toJson)
            assertTrue(types.containsAll(listOf("bind", "volume", "tmpfs")), "mount types $types")
        }

        @Test
        fun `community mysql modules redirect to ansible mysql`() {
            val lookup = snapshot.module("community.mysql.mysql_user")
            assertEquals("ansible.mysql.mysql_user", lookup.resolution.canonical)
            assertEquals(listOf("community.mysql.mysql_user", "ansible.mysql.mysql_user"), lookup.resolution.chain)
            val deprecation = lookup.resolution.deprecations.single()
            assertEquals("community.mysql.mysql_user", deprecation.name)
            assertEquals("6.0.0", deprecation.notice.removalVersion)
            val doc = lookup.doc!!
            assertEquals("community.mysql.mysql_user", doc.fqcn)
            assertEquals("ansible.mysql.mysql_user", doc.canonicalFqcn)
            assertTrue(doc.options.containsKey("priv"))
        }

        @Test
        fun `systemd shows the systemd_service docs`() {
            val lookup = snapshot.module("ansible.builtin.systemd")
            assertEquals("ansible.builtin.systemd_service", lookup.resolution.canonical)
            assertTrue(lookup.resolution.isAlias)
            assertEquals(snapshot.modules.getValue("ansible.builtin.systemd_service").options, lookup.doc!!.options)
            assertEquals("ansible.builtin.systemd", lookup.doc.fqcn)
            assertFalse(snapshot.modules.containsKey("ansible.builtin.systemd"), "alias docs are stored once")
        }

        @Test
        fun `short names, tombstones and missing modules`() {
            assertEquals("ansible.builtin.copy", snapshot.module("copy").doc!!.fqcn)
            val include = snapshot.module("ansible.builtin.include")
            assertNotNull(include.resolution.tombstone)
            assertNull(include.doc)
            val missing = snapshot.module("ns.nope.module")
            assertNull(missing.doc)
            assertEquals("ns.nope.module", missing.resolution.canonical)
        }

        @Test
        fun `free-form and arbitrary-key modules`() {
            assertEquals("free_form", snapshot.module("ansible.builtin.command").doc!!.freeForm)
            assertEquals("free_form", snapshot.module("ansible.builtin.shell").doc!!.freeForm)
            assertNull(snapshot.module("ansible.builtin.copy").doc!!.freeForm)
            assertTrue(snapshot.module("ansible.builtin.set_fact").doc!!.acceptsArbitraryKeys)
            assertFalse(snapshot.module("ansible.builtin.assert").doc!!.acceptsArbitraryKeys)
        }

        @Test
        fun `keywords carry isa and template mode`() {
            val `when` = snapshot.keyword("when")!!
            assertEquals(TemplateMode.IMPLICIT, `when`.template)
            assertEquals("list", `when`.isa)
            assertTrue("Task" in `when`.appliesTo)
            for (implicit in listOf("changed_when", "failed_when", "until")) assertEquals(TemplateMode.IMPLICIT, snapshot.keyword(implicit)!!.template, implicit)

            val tags = snapshot.keyword("tags")!!
            assertEquals("list", tags.isa)
            assertEquals(OptionType.List, tags.type)
            assertEquals(setOf("Play", "Role", "Block", "Task", "Handler", "PlaybookInclude"), tags.appliesTo)

            assertEquals(TemplateMode.STATIC, snapshot.keyword("register")!!.template)
            assertEquals(OptionType.Dict, snapshot.keyword("loop_control")!!.type)
            assertEquals("class", snapshot.keyword("loop_control")!!.isa)
            assertEquals("item", snapshot.keyword("loop_var")!!.default)
            assertEquals(setOf("LoopControl"), snapshot.keyword("loop_var")!!.appliesTo)
            assertEquals(OptionType.Int, snapshot.keyword("async")!!.type)
            assertNull(snapshot.keyword("max_fail_percentage")!!.type, "percent has no option type")
            assertEquals(setOf("Handler"), snapshot.keyword("listen")!!.appliesTo)
            assertEquals(DocSnapshot.WITH_LOOKUP, snapshot.keyword("with_items")!!.name)
            assertNotNull(snapshot.keyword("local_action"))
            assertNull(snapshot.keyword("accelerate"), "removed keywords accepted nowhere are dropped")
            assertNull(snapshot.keyword("validate_argspec"), "validate_argspec is a 2.19+ play keyword")
            assertTrue(snapshot.keyword("become")!!.description.single().contains(":ref:`become_plugins`"), "RST is kept for the renderer")
        }

        @Test
        fun `filters, tests and lookups`() {
            val toJson = snapshot.plugin("ansible.builtin.to_json", PluginKind.FILTER).doc!!
            assertTrue(toJson.options.getValue("_input").required)
            assertEquals(OptionType.Int, toJson.options.getValue("indent").type, "plugin type 'integer' is normalised")
            assertFalse(toJson.jinjaBuiltin)
            val default = snapshot.plugin("default", PluginKind.FILTER).doc!!
            assertTrue(default.jinjaBuiltin)
            assertEquals(listOf("default_value", "boolean"), default.positional)
            assertTrue(snapshot.plugin("defined", PluginKind.TEST).doc!!.jinjaBuiltin)
            assertNotNull(snapshot.plugin("ansible.builtin.file", PluginKind.LOOKUP).doc)
            assertNotNull(snapshot.plugin("community.general.json_query", PluginKind.FILTER).doc)
            assertEquals("ansible.builtin.bool", snapshot.plugin("formerly_core_filter", PluginKind.FILTER).resolution.canonical)
        }

        @Test
        fun `every description in the snapshot parses as markup`() {
            var paragraphs = 0
            fun check(texts: List<String>) {
                for (text in texts) {
                    paragraphs++
                    AnsibleDocMarkup.parse(text)
                }
            }
            fun checkOptions(options: Map<String, OptionSpec>?) {
                options?.values?.forEach {
                    check(it.description)
                    checkOptions(it.options)
                }
            }
            for (module in snapshot.modules.values) {
                check(module.description)
                check(module.notes)
                check(module.seeAlso)
                checkOptions(module.options)
            }
            for (keyword in snapshot.keywords.values) keyword.description.forEach { AnsibleDocMarkup.parseRst(it) }
            assertTrue(paragraphs > 20_000, "$paragraphs paragraphs")
            val parts = snapshot.modules.values.flatMap { m -> m.options.values.flatMap { o -> o.description.flatMap(AnsibleDocMarkup::parse) } }
            assertTrue(parts.any { it is MarkupPart.Option && it.plugin != null })
            assertTrue(parts.any { it is MarkupPart.ReturnValue })
        }
    }

    /** The snapshot of the local (latest) ansible-core line. */
    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    inner class Latest {
        private lateinit var snapshot: DocSnapshot

        @BeforeAll
        fun load() {
            snapshot = dataFile("core-latest.json.gz").inputStream().use { DocSnapshot.load(it, "local latest") }
        }

        @Test
        fun `is a newer core line with the same collections`() {
            assertTrue(snapshot.coreVersion!! > CoreVersion.PINNED)
            assertEquals(setOf("ansible.builtin", "ansible.mysql", "ansible.posix", "community.crypto", "community.docker", "community.general", "community.mysql"), snapshot.collections.keys)
            assertEquals("local latest", snapshot.module("ansible.builtin.template").doc!!.source)
            assertNotNull(snapshot.module("community.general.keycloak_authentication_v2").doc, "the bundled collections are not shadowed by ~/.ansible")
            assertNotNull(snapshot.keyword("validate_argspec"))
            assertNotNull(snapshot.module("ansible.builtin.apt_repository").doc!!.deprecated)
            assertEquals("ansible.mysql.mysql_user", snapshot.module("community.mysql.mysql_user").resolution.canonical)
        }
    }

    @Test
    fun `synthetic snapshot covers the normalisation rules`() {
        val json = """
            {"format":1,"core":"2.99.0","label":"test","collections":{"ansible.builtin":"2.99.0"},
             "modules":{"ns.coll.mod":{
               "short_description":"A module","description":["One.","Two."],
               "options":{"mode":{"type":"str","choices":{"fast":["Go fast."],"safe":["Careful."]},"default":"safe"},
                          "items":{"type":"list","elements":"dict","options":{"name":{"type":"str","required":true}}},
                          "flag":{"type":"bool","default":false,"aliases":["f"],"version_added":"1.1.0","no_log":true,
                                  "deprecated":{"why":"old","alternative":"new","removed_at_date":"2030-01-01"}}},
               "returns":{"info":{"type":"complex","returned":"always","sample":{"a":1},"contains":{"a":{"type":"int"}}}},
               "attributes":{"check_mode":{"support":"partial","details":["x"]}},
               "seealso":[{"module":"ns.coll.other"},{"plugin":"ns.coll.p","plugin_type":"lookup"},
                          {"name":"Docs (web), latest","link":"https://x.test","description":["Read it."]},{"ref":"some_ref","description":"A ref."}],
               "examples":"- ns.coll.mod: {}","free_form":"free_form","accepts_arbitrary_keys":true,"deprecated":{"why":"gone soon","removed_in":"3.0.0"}}},
             "keywords":{"serial":{"applies_to":["Play"],"isa":"list","default":[],"template":"explicit","description":["Batches."]}},
             "filters":{},"tests":{},"lookups":{},
             "routing":{"modules":{"ns.coll.old":{"redirect":"ns.coll.mod","deprecation":{"removal_version":"2.0.0","warning_text":"Use mod."}}},
                        "aliases":{"modules":{"ns.coll.alias":"ns.coll.mod"}}}}
        """.trimIndent()
        val snapshot = DocSnapshot.load(StringReader(json))
        val mod = snapshot.module("ns.coll.old").doc!!
        assertEquals("ns.coll.old", mod.fqcn)
        assertEquals("ns.coll.mod", mod.canonicalFqcn)
        assertEquals("bundled 2.99.0", mod.source)
        val mode = mod.options.getValue("mode")
        val described = mode.choices as Choices.Described
        assertEquals(listOf("fast", "safe"), described.values.map(YValueJson::toJson))
        assertEquals(listOf("Go fast."), described.described.first().second)
        assertEquals("safe", YValueJson.toJson(mode.default))
        assertEquals(OptionType.Dict, mod.options.getValue("items").elements)
        assertTrue(mod.options.getValue("items").options!!.getValue("name").required)
        assertNull(mode.options)
        val flag = mod.options.getValue("flag")
        assertEquals(false, YValueJson.toJson(flag.default))
        assertEquals(listOf("f"), flag.aliases)
        assertEquals("1.1.0", flag.versionAdded)
        assertTrue(flag.noLog)
        assertEquals(Deprecation("old", "new", "2030-01-01", null), flag.deprecated)
        val info = mod.returns.getValue("info")
        assertEquals(OptionType.Dict, info.type)
        assertEquals("""{"a":1}""", info.sample)
        assertEquals(OptionType.Int, info.contains!!.getValue("a").type)
        assertEquals(mapOf("check_mode" to "partial"), mod.attributes)
        assertEquals(
            listOf("M(ns.coll.other)", "P(ns.coll.p#lookup)", "L(Docs [web]  latest,https://x.test) – Read it.", "R(some_ref,some_ref) – A ref."),
            mod.seeAlso,
        )
        assertEquals(MarkupPart.Link("Docs [web]  latest", "https://x.test"), AnsibleDocMarkup.parse(mod.seeAlso[2]).first())
        assertEquals("free_form", mod.freeForm)
        assertTrue(mod.acceptsArbitraryKeys)
        assertEquals(Deprecation("gone soon", null, "3.0.0", null), mod.deprecated)
        assertEquals("ns.coll.mod", snapshot.module("ns.coll.alias").resolution.canonical)
        assertEquals("[]", snapshot.keyword("serial")!!.default)
        assertEquals(OptionType.List, snapshot.keyword("serial")!!.type)
    }

    @Test
    fun `newer formats are rejected, plain JSON streams load`() {
        assertThrows<IllegalArgumentException> { DocSnapshot.fromJson(Json.parseObject("""{"format":2}""")) }
        val plain = """{"format":1,"core":"2.18.8"}""".byteInputStream()
        val snapshot = DocSnapshot.load(plain)
        assertEquals(emptyMap<String, ModuleDoc>(), snapshot.modules)
        assertEquals("ansible.builtin.x", snapshot.module("x").resolution.canonical)
    }
}
