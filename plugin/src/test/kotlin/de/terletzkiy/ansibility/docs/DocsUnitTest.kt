package de.terletzkiy.ansibility.docs

import de.terletzkiy.ansibility.api.LocalAnsibleInstall
import de.terletzkiy.ansibility.semantics.CoreVersion
import de.terletzkiy.ansibility.semantics.schema.OptionSpec
import de.terletzkiy.ansibility.semantics.schema.OptionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.file.Path

/** Pure helpers of the docs area: link URLs, option paths, examples, source file candidates, offset mapping. */
class DocsUnitTest {

    @Test
    fun linkUrlsRoundTrip() {
        assertEquals("psi_element://ansibility-module/ansible.builtin.copy", DocLink.moduleUrl("ansible.builtin.copy"))
        assertEquals(DocLink.Module("ansible.builtin.copy"), DocLink.parse(DocLink.moduleUrl("ansible.builtin.copy")))
        val option = DocLink.optionUrl("ansible.builtin.include_tasks", listOf("free-form"))
        assertEquals(DocLink.Option("ansible.builtin.include_tasks", listOf("free-form")), DocLink.parse(option))
        assertEquals(
            DocLink.Option("community.docker.docker_container", listOf("mounts", "type")),
            DocLink.parse("psi_element://ansibility-option/community.docker.docker_container/mounts/type"),
        )
        assertNull(DocLink.parse("psi_element://ansibility-option/ansible.builtin.copy"))
        assertNull(DocLink.parse("psi_element://ansibility-module/"))
        assertNull(DocLink.parse("https://docs.ansible.com/"))
    }

    @Test
    fun optionPathsFollowAliasesAndStopAtTheDocs() {
        val interval = OptionSpec("interval")
        val healthcheck = OptionSpec("healthcheck", type = OptionType.Dict, options = mapOf("interval" to interval))
        val path = OptionSpec("path", type = OptionType.Path, aliases = listOf("dest", "name"))
        val options = mapOf("healthcheck" to healthcheck, "path" to path)
        assertEquals(listOf(healthcheck, interval), ModuleOptions.chain(options, listOf("healthcheck", "interval")))
        assertEquals(listOf(path), ModuleOptions.chain(options, listOf("dest")))
        assertNull(ModuleOptions.chain(options, listOf("healthcheck", "nope")))
        assertNull(ModuleOptions.chain(options, emptyList()))
        assertEquals(listOf(healthcheck), ModuleOptions.documentedPrefix(options, listOf("healthcheck", "nope", "deeper")))
    }

    @Test
    fun firstExample() {
        val examples = """
            - name: First
              ansible.builtin.copy:
                src: a

            # Second, with a comment
            - name: Second
              ansible.builtin.copy:
                src: b
        """.trimIndent()
        assertEquals("- name: First\n  ansible.builtin.copy:\n    src: a", ModuleDocumentationTarget.firstExample(examples))
        assertEquals("# Leading comment\n- name: Only", ModuleDocumentationTarget.firstExample("# Leading comment\n- name: Only\n"))
        val long = (listOf("- name: Long") + (1..30).map { "  line$it: x" }).joinToString("\n")
        val cut = ModuleDocumentationTarget.firstExample(long)!!.lines()
        assertEquals(16, cut.size)
        assertEquals("# …", cut.last())
        assertNull(ModuleDocumentationTarget.firstExample(null))
        assertNull(ModuleDocumentationTarget.firstExample("  \n"))
    }

    @Test
    fun moduleSourceCandidates() {
        val install = LocalAnsibleInstall(
            CoreVersion(2, 21, 4),
            "/usr/bin/ansible",
            moduleLocation = "/opt/site-packages/ansible",
            collectionPaths = listOf("/home/u/.ansible/collections", "/usr/share/ansible/collections"),
        )
        assertEquals(
            listOf(
                Path.of("/opt/site-packages/ansible/modules/template.py"),
                Path.of("/home/u/.ansible/collections/ansible/modules/template.py"),
                Path.of("/usr/share/ansible/collections/ansible/modules/template.py"),
            ),
            ModuleSourceLocator.candidates("ansible/modules/template.py", install),
        )
        assertEquals(listOf(Path.of("/abs/module.py")), ModuleSourceLocator.candidates("/abs/module.py", null))
        assertEquals(emptyList<Path>(), ModuleSourceLocator.candidates("ansible/modules/template.py", null))
        assertEquals(emptyList<Path>(), ModuleSourceLocator.candidates("", install))
    }

    @Test
    fun decodedIndexMapsHostOffsetsBack() {
        // Decoded "a\"b" from the host text "a\\\"b" (an escaped quote takes two host characters).
        val host = intArrayOf(0, 1, 3, 4)
        val lookup = { index: Int -> host.getOrElse(index) { -1 } }
        assertEquals(0, TaskSites.decodedIndex(0, 3, lookup))
        assertEquals("inside the escape", 1, TaskSites.decodedIndex(2, 3, lookup))
        assertEquals(2, TaskSites.decodedIndex(3, 3, lookup))
        assertEquals(3, TaskSites.decodedIndex(9, 3, lookup))
        assertNull(TaskSites.decodedIndex(-1, 3, lookup))
    }

    @Test
    fun typeTexts() {
        assertEquals("list / elements=dict", DocsHtml.typeText(OptionSpec("mounts", type = OptionType.List, elements = OptionType.Dict)))
        assertEquals("path · required", DocsHtml.typeAndRequired(OptionSpec("dest", type = OptionType.Path, required = true)))
        assertEquals("str", DocsHtml.typeAndRequired(OptionSpec("name")))
        assertEquals("Conditional expression, determines if it runs.", DocsHtml.firstSentence(listOf("Conditional expression, determines if it runs. More text.")))
        assertEquals("See loop_control.", DocsHtml.firstSentence(listOf("See :ref:`loop_control`."), rst = true))
    }
}
