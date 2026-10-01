package de.terletzkiy.ansibility.runtime

import de.terletzkiy.ansibility.semantics.json.Json
import de.terletzkiy.ansibility.semantics.schema.Choices
import de.terletzkiy.ansibility.semantics.schema.DocSnapshot
import de.terletzkiy.ansibility.semantics.schema.OptionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.GZIPInputStream

/**
 * The Kotlin normaliser must produce exactly what `tools/docgen/snapshot/generate.py` produced for the bundled
 * latest line, which was generated from the same local ansible-core 2.21.4 (`ansible-doc -t module -j` output of
 * seven builtin modules captured in `testData/runtime/ansible-doc-builtin-2.21.4.json`).
 */
class AnsibleDocNormalizerTest {
    private val sitePackages = "/opt/homebrew/Cellar/ansible/14.4.0/libexec/lib/python3.14/site-packages"

    private val raw: Map<String, Any?> by lazy { Json.parseObject(File("src/test/testData/runtime/ansible-doc-builtin-2.21.4.json").readText()) }

    @Suppress("UNCHECKED_CAST")
    private val latestModules: Map<String, Any?> by lazy {
        val stream = javaClass.getResourceAsStream(BundledLine.LATEST.resource)!!
        val root = GZIPInputStream(stream).reader(Charsets.UTF_8).use { Json.parseObject(it) }
        root["modules"] as Map<String, Any?>
    }

    @Test
    fun matchesTheGeneratorOutputForBuiltinModules() {
        val normalised = AnsibleDocNormalizer.modules(raw, sitePackages)
        for (fqcn in listOf("copy", "stat", "dnf", "command", "set_fact", "apt_repository").map { "ansible.builtin.$it" }) {
            assertEquals(fqcn, latestModules[fqcn], normalised.modules[fqcn])
        }
    }

    @Test
    fun aliasedModulesAreStoredUnderTheDocumentedName() {
        val normalised = AnsibleDocNormalizer.modules(raw, sitePackages)
        assertEquals(mapOf("ansible.builtin.systemd" to "ansible.builtin.systemd_service"), normalised.aliases)
        val systemd = normalised.modules["ansible.builtin.systemd_service"]
        assertNotNull(systemd)
        assertNull(normalised.modules["ansible.builtin.systemd"])
        // the requested file is systemd.py (a symlink); the snapshot documents systemd_service.py, otherwise identical
        assertEquals("ansible/modules/systemd.py", systemd!!["filename"])
        assertEquals(latestModules["ansible.builtin.systemd_service"], systemd + ("filename" to "ansible/modules/systemd_service.py"))
    }

    @Test
    fun loadsThroughTheSnapshotModel() {
        val normalised = AnsibleDocNormalizer.modules(raw, sitePackages)
        val snapshot = DocSnapshot.fromJson(
            mapOf(
                "format" to 1,
                "core" to "2.21.4",
                "modules" to normalised.modules,
                "routing" to mapOf("aliases" to mapOf("modules" to normalised.aliases)),
            ),
            "local 2.21.4",
        )
        val copy = snapshot.module("copy").doc!!
        assertEquals("local 2.21.4", copy.source)
        assertEquals(OptionType.Path, copy.options.getValue("dest").type)
        assertTrue(copy.options.getValue("dest").required)
        val useBackend = snapshot.module("ansible.builtin.dnf").doc!!.options.getValue("use_backend")
        assertTrue("dict choices become described choices", useBackend.choices is Choices.Described)
        assertEquals("free_form", snapshot.module("ansible.builtin.command").doc!!.freeForm)
        assertTrue(snapshot.module("ansible.builtin.set_fact").doc!!.acceptsArbitraryKeys)
        assertEquals("2.25", snapshot.module("ansible.builtin.apt_repository").doc!!.deprecated!!.removedIn)
        val systemd = snapshot.module("ansible.builtin.systemd")
        assertEquals("ansible.builtin.systemd_service", systemd.resolution.canonical)
        assertNotNull(systemd.doc)
        assertNotNull("returns keep contains", snapshot.module("ansible.builtin.stat").doc!!.returns.getValue("stat").contains?.get("exists"))
    }

    @Test
    fun normalisesTheShapesAnsibleDocEmits() {
        val entry = mapOf(
            "doc" to mapOf(
                "module" to "thing",
                "collection" to "acme.tools",
                "short_description" to "  Does things  ",
                "description" to "One paragraph\n",
                "version_added" to "",
                "filename" to "/home/u/.ansible/collections/ansible_collections/acme/tools/plugins/modules/thing.py",
                "options" to mapOf(
                    "mode" to mapOf(
                        "description" to listOf("A.", null, "B.\n"),
                        "choices" to mapOf("fast" to "Quick.", "safe" to listOf("Careful.")),
                        "required" to false,
                        "aliases" to emptyList<String>(),
                    ),
                    "nested" to mapOf("type" to "dict", "suboptions" to mapOf("x" to mapOf("type" to "int", "default" to 3))),
                    "flag" to mapOf("type" to "bool", "default" to null, "no_log" to true, "version_added" to 1.5),
                ),
                "seealso" to listOf(mapOf("module" to "acme.tools.other", "description" to "See it."), mapOf("name" to "", "junk" to 1)),
                "attributes" to mapOf("platform" to mapOf("support" to "N/A", "platforms" to listOf("posix", "windows"))),
                "deprecated" to mapOf("why" to "old", "removed_in" to "", "alternative" to null),
            ),
            "examples" to "\n- acme.tools.thing:\n\n",
            "return" to mapOf("big" to mapOf("sample" to "x".repeat(2000), "returned" to listOf("always", "changed"), "type" to "str")),
        )
        val normalised = AnsibleDocNormalizer.modules(mapOf("acme.tools.thing" to entry), null)
        val doc = normalised.modules.getValue("acme.tools.thing")
        assertEquals("Does things", doc["short_description"])
        assertEquals(listOf("One paragraph"), doc["description"])
        assertNull("empty version_added is dropped", doc["version_added"])
        assertEquals("ansible_collections/acme/tools/plugins/modules/thing.py", doc["filename"])
        @Suppress("UNCHECKED_CAST")
        val options = doc["options"] as Map<String, Map<String, Any?>>
        assertEquals(listOf("A.", "B."), options.getValue("mode")["description"])
        assertEquals(mapOf("fast" to listOf("Quick."), "safe" to listOf("Careful.")), options.getValue("mode")["choices"])
        assertNull("required: false and empty aliases are dropped", options.getValue("mode")["required"] ?: options.getValue("mode")["aliases"])
        assertEquals(mapOf("x" to mapOf("type" to "int", "default" to 3)), options.getValue("nested")["options"])
        assertEquals(mapOf("type" to "bool", "version_added" to "1.5", "no_log" to true), options.getValue("flag"))
        assertEquals(listOf(mapOf("module" to "acme.tools.other", "description" to listOf("See it."))), doc["seealso"])
        assertEquals(mapOf("platform" to mapOf("support" to "N/A", "platforms" to "posix, windows")), doc["attributes"])
        assertEquals(mapOf("why" to "old"), doc["deprecated"])
        assertEquals("- acme.tools.thing:", doc["examples"])
        assertEquals("oversized samples are dropped", mapOf("big" to mapOf("returned" to "always, changed", "type" to "str")), doc["returns"])
    }

    @Test
    fun sampleLimitIsMeasuredLikePython() {
        // len(json.dumps({"a": [1, "é"]}, sort_keys=True)) == 20 in CPython
        assertEquals(20, AnsibleDocNormalizer.pythonJsonLength(mapOf("a" to listOf(1L, "é"))))
        assertEquals("\"a\\nb\"", 6, AnsibleDocNormalizer.pythonJsonLength("a\nb"))
        assertEquals(4, AnsibleDocNormalizer.pythonJsonLength(null))
        assertEquals(5, AnsibleDocNormalizer.pythonJsonLength(false))
        assertEquals(2, AnsibleDocNormalizer.pythonJsonLength(emptyList<Any>()))
    }

    @Test
    fun relativeFileNames() {
        assertEquals("ansible/modules/copy.py", AnsibleDocNormalizer.relativeFilename("$sitePackages/ansible/modules/copy.py", sitePackages))
        assertEquals(
            "ansible_collections/community/mysql/plugins/modules/mysql_user.py",
            AnsibleDocNormalizer.relativeFilename("/Users/dev/.ansible/collections/ansible_collections/community/mysql/plugins/modules/mysql_user.py", sitePackages),
        )
        assertEquals("custom.py", AnsibleDocNormalizer.relativeFilename("/elsewhere/library/custom.py", sitePackages))
        assertNull(AnsibleDocNormalizer.relativeFilename("", sitePackages))
    }
}
