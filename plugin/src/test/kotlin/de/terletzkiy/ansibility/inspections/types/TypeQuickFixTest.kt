package de.terletzkiy.ansibility.inspections.types

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.psi.PsiDocumentManager
import de.terletzkiy.ansibility.types.TypeCheckTestCase

/**
 * The quick fixes of the type inspections (🟣 CLAUDE X80) on a vars file of the falcon root: each fix's text, where it
 * is offered, and the YAML it writes.
 */
class TypeQuickFixTest : TypeCheckTestCase() {

    override fun setUp() {
        super.setUp()
        copyInfra("repos/falcon/ansible")
    }

    private fun fixesOn(path: String, line: Int): List<String> {
        val info = highlights(path).firstOrNull { lineOf(it) == line } ?: error("no highlight on line $line")
        myFixture.editor.caretModel.moveToOffset(info.startOffset)
        return myFixture.availableIntentions.map { it.text }.filter { text -> OUR_FIXES.any { text.startsWith(it) } }.sorted()
    }

    private fun apply(path: String, line: Int, fix: String): String {
        fixesOn(path, line)
        myFixture.launchAction(myFixture.availableIntentions.first { it.text == fix })
        return lineText(line)
    }

    fun testValueFixes() {
        val path = "repos/falcon/ansible/environments/prod/group_vars/fixes.yml"
        createFile(
            path,
            """
            haproxy_backports_version: 3.10
            haproxy_log_path: yes
            haproxy_stats_http_port: '8404'
            haproxy_apply_kernel_params: 1
            haproxy_balance: roundrobbin
            """.trimIndent() + "\n",
        )
        assertEquals(listOf("Quote value"), fixesOn(path, 1))
        assertEquals("the author's spelling, not YAML's 3.1", "haproxy_backports_version: \"3.10\"", apply(path, 1, "Quote value"))
        assertEquals("haproxy_log_path: \"yes\"", apply(path, 2, "Quote value"))
        assertEquals(listOf("Unquote value"), fixesOn(path, 3))
        assertEquals("haproxy_stats_http_port: 8404", apply(path, 3, "Unquote value"))
        assertEquals(listOf("Replace with 'true'"), fixesOn(path, 4))
        assertEquals("haproxy_apply_kernel_params: true", apply(path, 4, "Replace with 'true'"))
        assertEquals(listOf("Replace with nearest choice 'roundrobin'"), fixesOn(path, 5))
        assertEquals("haproxy_balance: roundrobin", apply(path, 5, "Replace with nearest choice 'roundrobin'"))
        assertEmpty(highlights(path))
    }

    fun testQuotedChoiceKeepsItsQuotesAndTaggedScalarsGetNoValueFix() {
        val path = "repos/falcon/ansible/environments/prod/group_vars/quoted.yml"
        createFile(path, "haproxy_balance: 'leastcon'\nhaproxy_backports_version: !!float 3.2\n")
        assertEquals("haproxy_balance: 'leastconn'", apply(path, 1, "Replace with nearest choice 'leastconn'"))
        assertEquals("replacing would drop the tag", emptyList<String>(), fixesOn(path, 2))
    }

    fun testUnsupportedKeyFixesAndNoFixForMissingKeys() {
        val path = "repos/falcon/ansible/environments/prod/group_vars/servers.yml"
        createFile(
            path,
            """
            haproxy_servers:
              - name: web1
                ip: 192.0.2.1
                port: 444
                wieght: 100
              - name: web2
                port: 444
            """.trimIndent() + "\n",
        )
        assertEquals(listOf("Add sub-option 'wieght' to haproxy argument_specs", "Remove unsupported key 'wieght'"), fixesOn(path, 5))
        assertEquals("a missing key has no fix", emptyList<String>(), fixesOn(path, 6))
        fixesOn(path, 5)
        myFixture.launchAction(myFixture.availableIntentions.first { it.text == "Remove unsupported key 'wieght'" })
        assertEquals("    port: 444", lineText(4))
        assertEquals("  - name: web2", lineText(5))
    }

    fun testAddSubOptionInfersTheTypeFromTheValue() {
        val path = "repos/falcon/ansible/environments/prod/group_vars/servers.yml"
        createFile(
            path,
            """
            haproxy_servers:
              - name: web1
                ip: 192.0.2.1
                port: 444
                backup: true
            """.trimIndent() + "\n",
        )
        fixesOn(path, 5)
        myFixture.launchAction(myFixture.availableIntentions.first { it.text == "Add sub-option 'backup' to haproxy argument_specs" })
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        val spec = FileDocumentManager.getInstance().getDocument(vf("repos/falcon/ansible/roles/haproxy/meta/argument_specs.yml"))!!.text
        val servers = spec.substring(spec.indexOf("      haproxy_servers:")).substringBefore("\n\n")
        assertTrue(servers, servers.endsWith("          weight:\n            type: int\n            description: Optional server weight for load distribution.\n          backup:\n            type: bool"))
        assertEmpty(highlights(path))
    }

    private companion object {
        val OUR_FIXES = listOf("Quote value", "Unquote value", "Replace with", "Remove unsupported key", "Add sub-option", "Update ")
    }
}
