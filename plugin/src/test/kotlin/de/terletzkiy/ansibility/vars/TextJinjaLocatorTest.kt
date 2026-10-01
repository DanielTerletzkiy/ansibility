package de.terletzkiy.ansibility.vars

import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.vfs.VfsUtilCore
import de.terletzkiy.ansibility.api.AnsibleSite
import de.terletzkiy.ansibility.api.JinjaContainer
import de.terletzkiy.ansibility.api.JinjaLocator
import de.terletzkiy.ansibility.lang.jinja.AnsibleJinjaFileType
import de.terletzkiy.ansibility.lang.jinja.filetype.J2Mappings
import org.jetbrains.yaml.YAMLFileType
import org.jetbrains.yaml.psi.YAMLFile

/** [TextJinjaLocator] (plan A.4): references in YAML scalars, bare expressions and template files of any file type. */
class TextJinjaLocatorTest : VarsTestCase() {
    private val locator = TextJinjaLocator()

    override fun setUp() {
        super.setUp()
        copyVarsData("site")
    }

    private fun locate(path: String, line: Int, marker: String, delta: Int = 0): AnsibleSite.VarRef? {
        val offset = offsetAt(path, line, marker, delta)
        return runReadActionBlocking { locator.locate(psi(path), offset) }
    }

    /** The host text a reference's range covers. */
    private fun rangeText(path: String, ref: AnsibleSite.VarRef): String = VfsUtilCore.loadText(vf(path)).substring(ref.range.startOffset, ref.range.endOffset)

    fun testIsRegisteredLast() {
        val locators = JinjaLocator.EP_NAME.extensionList
        assertTrue(locators.any { it is TextJinjaLocator })
        assertTrue("the M5 PSI locator must be able to go first", locators.last() is TextJinjaLocator)
    }

    fun testTemplatedScalarAndAttributes() {
        val ref = locate(TASKS, 4, "web_nested", 2)!!
        assertEquals("web_nested", ref.name)
        assertEquals(emptyList<String>(), ref.attrPath)
        assertEquals(JinjaContainer.YAML_TEMPLATE, ref.container)
        assertEquals("web_nested", rangeText(TASKS, ref))

        val attribute = locate(TASKS, 4, "inner", 2)!!
        assertEquals("web_nested", attribute.name)
        assertEquals(listOf("inner"), attribute.attrPath)
        assertEquals("web_nested.inner", rangeText(TASKS, attribute))

        val loop = locate(TASKS, 13, "web_list")!!
        assertEquals("web_list", loop.name)
        assertEquals(JinjaContainer.YAML_TEMPLATE, loop.container)
    }

    fun testImplicitExpressions() {
        val debugVar = locate(TASKS, 8, "exists", 1)!!
        assertEquals("web_stat", debugVar.name)
        assertEquals(listOf("stat", "exists"), debugVar.attrPath)
        assertEquals(JinjaContainer.YAML_EXPRESSION, debugVar.container)
        assertEquals("web_stat.stat.exists", rangeText(TASKS, debugVar))

        val middle = locate(TASKS, 8, "stat.exists", 1)!!
        assertEquals("the caret on an attribute yields the path up to it", listOf("stat"), middle.attrPath)

        val condition = locate(TASKS, 9, "web_port", 3)!!
        assertEquals("web_port", condition.name)
        assertEquals(JinjaContainer.YAML_EXPRESSION, condition.container)
        assertNull("filter names are not variables", locate(TASKS, 9, "int", 1))
        assertNull("keywords are not variables", locate(TASKS, 9, "and", 1))

        val assertion = locate(TASKS, 31, "web_port")!!
        assertEquals("web_port", assertion.name)
        assertEquals(JinjaContainer.YAML_EXPRESSION, assertion.container)
    }

    fun testOffsetsMapThroughFoldedAndEscapedScalars() {
        val folded = locate(TASKS, 35, "web_version", 4)!!
        assertEquals("web_version", folded.name)
        assertEquals("web_version", rangeText(TASKS, folded))

        val escaped = locate(TASKS, 38, "web_version", 2)!!
        assertEquals("web_version", escaped.name)
        assertEquals("the double-quoted escape before it is mapped back", "web_version", rangeText(TASKS, escaped))
    }

    fun testTemplateFile() {
        val ref = locate(TEMPLATE, 1, "web_port", 1)!!
        assertEquals("web_port", ref.name)
        assertEquals(JinjaContainer.TEMPLATE_FILE, ref.container)
        assertEquals("web_port", rangeText(TEMPLATE, ref))

        assertNull("comments are skipped", locate(TEMPLATE, 2, "commented_var", 1))
        assertNull("raw bodies are skipped", locate(TEMPLATE, 3, "raw_var", 1))
        assertNull("string literals are skipped", locate(TEMPLATE, 5, "not_var", 1))
        assertNull("filters are not variables", locate(TEMPLATE, 5, "default", 1))

        val local = locate(TEMPLATE, 5, "local_name", 1)!!
        assertEquals("local_name", local.name)
        assertTrue(local.localNames.toString(), "local_name" in local.localNames)

        val nested = locate(TEMPLATE, 5, "inner", 1)!!
        assertEquals("web_nested", nested.name)
        assertEquals(listOf("inner"), nested.attrPath)
        assertFalse("web_nested" in nested.localNames)

        val loopTarget = locate(TEMPLATE, 6, "srv.name", 1)!!
        assertEquals("srv", loopTarget.name)
        assertTrue("srv" in loopTarget.localNames)

        val subscript = locate(TEMPLATE, 7, "cert_file", 1)!!
        assertEquals("item", subscript.name)
        assertEquals(listOf("floating", "ssl", "cert_file"), subscript.attrPath)
        assertEquals("item.floating.ssl['cert_file']", rangeText(TEMPLATE, subscript))
        assertEquals(listOf("floating", "ssl"), locate(TEMPLATE, 7, "ssl", 1)!!.attrPath)
    }

    fun testSetFactValueInPlaybook() {
        val ref = locate(PLAYBOOK, 13, "web_port", 1)!!
        assertEquals("web_port", ref.name)
        assertEquals(JinjaContainer.YAML_TEMPLATE, ref.container)
    }

    fun testPositionsOutsideJinja() {
        assertNull("a key is not a reference", locate(TASKS, 5, "register"))
        assertNull("plain text of a scalar", locate(TASKS, 4, "/etc/", 1))
        assertNull("module names", locate(TASKS, 3, "ansible.builtin.stat", 2))
        assertNull("outside the delimiters of a template", locate(TEMPLATE, 1, "listen", 1))
    }

    fun testArgumentSpecDescriptionsAreNeverJinja() {
        copyInfra("golden/roles/haproxy")
        val spec = "golden/roles/haproxy/meta/argument_specs.yml"
        assertNull(locate(spec, 44, "{{ haproxy_backports_version", 4))
    }

    fun testTemplateWhateverItsFileType() {
        copyInfra("repos/falcon")
        val template = "repos/falcon/ansible/roles/postfix/templates/main.cf.j2"
        for (type in listOf<FileType>(YAMLFileType.YML, PlainTextFileType.INSTANCE, AnsibleJinjaFileType)) {
            withJ2As(type) {
                val file = psi(template)
                assertEquals(type, file.fileType)
                if (type == YAMLFileType.YML) assertTrue(file is YAMLFile)
                val ref = locate(template, 9, "postfix_relayhost", 1)!!
                assertEquals("postfix_relayhost", ref.name)
                assertEquals(JinjaContainer.TEMPLATE_FILE, ref.container)
                assertEquals("postfix_relayhost", rangeText(template, ref))
            }
        }
    }

    /**
     * Types `.j2` files inside roots as [type] for [action] (`*.j2` mapped to it, with "Keep YAML for .j2" on since the
     * M5 overrider claims them otherwise; [de.terletzkiy.ansibility.lang.jinja.AnsibleJinjaFileType] keeps the claim
     * over a `*.j2 → YAML` mapping), then restores the mapping and the settings.
     */
    private fun withJ2As(type: FileType, action: () -> Unit) = J2Mappings.withJ2As(type, action)

    companion object {
        const val TASKS = "site/roles/web/tasks/main.yml"
        const val TEMPLATE = "site/roles/web/templates/site.conf.j2"
        const val PLAYBOOK = "site/playbook.yml"
    }
}
