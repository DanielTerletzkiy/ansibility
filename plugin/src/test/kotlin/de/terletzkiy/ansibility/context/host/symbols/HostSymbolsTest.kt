package de.terletzkiy.ansibility.context.host.symbols

import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.terletzkiy.ansibility.api.AnsibleSite
import de.terletzkiy.ansibility.api.HostConstruct
import de.terletzkiy.ansibility.api.HostPatternSite
import de.terletzkiy.ansibility.api.InventoryNameSite
import de.terletzkiy.ansibility.api.SiteClassifier
import de.terletzkiy.ansibility.context.AnsibleWorkspaceImpl

/** Hosts and groups as symbols (plan amendment R7/R8, F8.8): classification, hover, Ctrl+B and completion. */
class HostSymbolsTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        add("ansible.cfg", "[defaults]\nroles_path = roles\n")
        add(
            "environments/prod/hosts.yml",
            "all:\n  children:\n    keepalived:\n      hosts:\n        prod-lb1:\n          ansible_host: 192.0.2.10\n        prod-lb2:\n" +
                "    database_primary:\n      hosts:\n        prod-db1:\n          ansible_host: 192.0.2.20\n" +
                "    monitoring_client:\n      children:\n        keepalived:\n        database_primary:\n",
        )
        add(
            "environments/test/hosts.yml",
            "all:\n  children:\n    database_primary:\n      hosts:\n        test-db1:\n          ansible_host: 198.51.100.10\n" +
                "    monitoring_client:\n      hosts:\n        test-db1:\n",
        )
        add("site.yml", "- hosts: keepalived\n  roles:\n    - db\n- hosts: database_primary:&monitoring_client\n  roles:\n    - db\n")
        add(
            "roles/db/tasks/main.yml",
            "- name: sync\n  command: sync\n  delegate_to: \"{{ groups['database_primary'][0] }}\"\n" +
                "- name: mon\n  debug: msg=hi\n  when: \"'monitoring_client' in group_names\"\n" +
                "- name: log\n  debug: msg=hi\n  when: \"'logging_client' in group_names\"\n" +
                "- name: local\n  debug: msg=hi\n  delegate_to: localhost\n" +
                "- name: hv\n  debug:\n    msg: \"{{ hostvars['prod-db1'].ansible_host }}\"\n",
        )
        AnsibleWorkspaceImpl.getInstance(project)!!.structureChanged()
    }

    fun testPlayHostsPatternHover() {
        val site = siteAt("site.yml", "keepalived") as HostPatternSite
        assertEquals(HostConstruct.PLAY_HOSTS, site.construct)
        assertEquals("keepalived", site.pattern)
        val lines = hover("site.yml", site)
        assertTrue(lines.toString(), "prod: prod-lb1, prod-lb2" in lines)
        assertTrue(lines.toString(), "test: no hosts (group not defined there)" in lines)
    }

    fun testIntersectionPattern() {
        val site = siteAt("site.yml", "database_primary:&") as HostPatternSite
        assertEquals("database_primary:&monitoring_client", site.pattern)
        val lines = hover("site.yml", site)
        assertTrue(lines.toString(), "prod: prod-db1" in lines)
        assertTrue(lines.toString(), "test: test-db1" in lines)
    }

    fun testGroupsSubscriptInDelegateTo() {
        val site = siteAt("roles/db/tasks/main.yml", "database_primary") as InventoryNameSite
        assertEquals(HostConstruct.GROUPS, site.construct)
        assertTrue(site.isGroup)
        val lines = hover("roles/db/tasks/main.yml", site)
        assertTrue(lines.toString(), "prod: [prod-db1]" in lines)
        assertTrue(lines.toString(), "test: [test-db1]" in lines)
    }

    fun testGroupNamesLiteral() {
        val site = siteAt("roles/db/tasks/main.yml", "monitoring_client") as InventoryNameSite
        assertEquals(HostConstruct.GROUP_NAMES, site.construct)
        val lines = hover("roles/db/tasks/main.yml", site)
        assertEquals("true on 4 hosts:", lines.first())
    }

    fun testUndefinedGroupNames() {
        val site = siteAt("roles/db/tasks/main.yml", "logging_client") as InventoryNameSite
        val lines = hover("roles/db/tasks/main.yml", site)
        assertTrue(lines.single(), lines.single().startsWith("false on every host: group logging_client is defined in no inventory of"))
    }

    fun testLocalhostDelegate() {
        val site = siteAt("roles/db/tasks/main.yml", "localhost") as HostPatternSite
        assertEquals(HostConstruct.DELEGATE_TO, site.construct)
        assertEquals(listOf("implicit localhost, not in the inventory"), hover("roles/db/tasks/main.yml", site))
    }

    fun testHostvarsHost() {
        val site = siteAt("roles/db/tasks/main.yml", "prod-db1") as InventoryNameSite
        assertEquals(HostConstruct.HOSTVARS, site.construct)
        assertFalse(site.isGroup)
        assertEquals(listOf("prod → prod-db1 (192.0.2.20)"), hover("roles/db/tasks/main.yml", site))
    }

    fun testOtherPositionsAreNotHostSymbols() {
        val text = myFixture.findFileInTempDir("roles/db/tasks/main.yml").let { String(it.contentsToByteArray()) }
        assertNull("the groups word itself stays a variable", classify("roles/db/tasks/main.yml", text.indexOf("groups[") + 1))
        assertNull("a module name is not a host symbol", classify("roles/db/tasks/main.yml", text.indexOf("command") + 1))
    }

    fun testNavigationGoesToTheGroupKeyPerEnvironment() {
        val site = siteAt("roles/db/tasks/main.yml", "database_primary")
        val file = myFixture.findFileInTempDir("roles/db/tasks/main.yml")
        val targets = runReadActionBlocking { HostSymbolNavigation.locations(project, file, site) }
        assertEquals(listOf("prod", "test"), targets.map { it.file.parent.name })
        targets.forEach { location ->
            val text = String(location.file.contentsToByteArray())
            assertTrue(text.substring(location.offset).startsWith("database_primary"))
        }
    }

    fun testCompletionInPlayHosts() {
        add("site2.yml", "- hosts: web:prod-\n  tasks: []\n")
        AnsibleWorkspaceImpl.getInstance(project)!!.structureChanged()
        myFixture.configureFromTempProjectFile("site2.yml")
        myFixture.editor.caretModel.moveToOffset("- hosts: web:prod-".length)
        val items = myFixture.completeBasic()?.map { it.lookupString }
        assertEquals(listOf("prod-db1", "prod-lb1", "prod-lb2"), items?.sorted())

        add("site3.yml", "- hosts: data\n  tasks: []\n")
        AnsibleWorkspaceImpl.getInstance(project)!!.structureChanged()
        myFixture.configureFromTempProjectFile("site3.yml")
        myFixture.editor.caretModel.moveToOffset("- hosts: data".length)
        assertNull("the single match is inserted", myFixture.completeBasic())
        assertTrue(myFixture.editor.document.text, myFixture.editor.document.text.startsWith("- hosts: database_primary"))
    }

    fun testHostvarsMemberGoesToTheWinningDefinition() {
        val path = "roles/db/tasks/main.yml"
        val text = String(myFixture.findFileInTempDir(path).contentsToByteArray())
        val offset = text.indexOf("ansible_host }}") + 1
        val targets = runReadActionBlocking {
            val psi = psiManager.findFile(myFixture.findFileInTempDir(path))!!
            val site = SiteClassifier.EP_NAME.extensionList.firstNotNullOf { it.classify(psi, offset) }
            assertTrue(site.toString(), site is AnsibleSite.VarRef)
            HostvarsMemberNavigation().targets(site, psi).map { it.containingFile.virtualFile.path.substringAfter("environments/") to it.text }
        }
        assertEquals(listOf("prod/hosts.yml" to "ansible_host: 192.0.2.20"), targets)
    }

    private fun siteAt(path: String, needle: String): AnsibleSite {
        val text = String(myFixture.findFileInTempDir(path).contentsToByteArray())
        val offset = text.indexOf(needle)
        assertTrue("$needle in $path", offset >= 0)
        return classify(path, offset + 1) ?: error("no host site at $needle")
    }

    private fun classify(path: String, offset: Int): AnsibleSite? = runReadActionBlocking {
        val psi = psiManager.findFile(myFixture.findFileInTempDir(path))!!
        SiteClassifier.EP_NAME.extensionList.firstNotNullOfOrNull { it.classify(psi, offset) }
            ?.takeIf { it is HostPatternSite || it is InventoryNameSite }
    }

    private fun hover(path: String, site: AnsibleSite): List<String> = runReadActionBlocking {
        val scope = HostSymbols.scope(project, myFixture.findFileInTempDir(path))!!
        HostSymbolTexts.hover(site, scope)!!.lines
    }

    private fun add(path: String, text: String) = myFixture.addFileToProject(path, text).virtualFile
}
