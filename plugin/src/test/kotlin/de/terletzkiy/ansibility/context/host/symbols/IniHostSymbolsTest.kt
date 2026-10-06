package de.terletzkiy.ansibility.context.host.symbols

import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.terletzkiy.ansibility.api.HostConstruct
import de.terletzkiy.ansibility.api.InventoryNameSite
import de.terletzkiy.ansibility.api.SiteClassifier
import de.terletzkiy.ansibility.context.AnsibleWorkspaceImpl

/** Group and host names inside an INI inventory: classification and Ctrl+B to their other definitions. */
class IniHostSymbolsTest : BasePlatformTestCase() {
    override fun setUp() {
        super.setUp()
        myFixture.addFileToProject(
            "hosts.ini",
            "[web]\nweb1 ansible_host=192.0.2.10\nweb2\n\n[db]\ndb1\nweb1\n\n[prod:children]\nweb\ndb\n\n[web:vars]\nhttp_port=80\n",
        )
        myFixture.addFileToProject("site.yml", "- hosts: web\n  tasks:\n    - debug: msg=hi\n")
        AnsibleWorkspaceImpl.getInstance(project)!!.structureChanged()
    }

    fun testNamesAreClassified() {
        val header = siteAt("[web]", 1)
        assertEquals(InventoryNameSite("web", true, HostConstruct.INVENTORY_FILE, header.range), header)
        val child = siteAt("children]\nweb", "children]\n".length + 1)
        assertTrue(child.isGroup)
        assertEquals("web", child.name)
        val host = siteAt("db1", 1)
        assertFalse(host.isGroup)
        assertEquals("db1", host.name)
        assertNull("a :vars key is a variable", classify(text().indexOf("http_port") + 1))
    }

    fun testChildGroupGoesToItsSection() {
        val text = text()
        val offset = text.indexOf("children]\nweb") + "children]\n".length + 1
        val targets = locations(offset)
        assertEquals(listOf(text.indexOf("[web]") + 1), targets)
    }

    fun testHostListedTwiceGoesToTheOtherLine() {
        val text = text()
        val second = text.indexOf("web1", text.indexOf("[db]"))
        val targets = locations(second + 1)
        assertEquals(listOf(text.indexOf("web1")), targets)
    }

    private fun text(): String = String(myFixture.findFileInTempDir("hosts.ini").contentsToByteArray())

    private fun classify(offset: Int): Any? = runReadActionBlocking {
        val psi = psiManager.findFile(myFixture.findFileInTempDir("hosts.ini"))!!
        SiteClassifier.EP_NAME.extensionList.firstNotNullOfOrNull { it.classify(psi, offset) }
    }

    private fun siteAt(anchor: String, shift: Int): InventoryNameSite = classify(text().indexOf(anchor) + shift) as InventoryNameSite

    private fun locations(offset: Int): List<Int> = runReadActionBlocking {
        val file = myFixture.findFileInTempDir("hosts.ini")
        val site = classify(offset) as InventoryNameSite
        HostSymbolNavigation.locations(project, file, site).filter { it.file == file }.map { it.offset }
    }
}
