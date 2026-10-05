package de.terletzkiy.ansibility.typeflow

import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.context.AnsibleWorkspaceImpl
import de.terletzkiy.ansibility.resolve.register.RegisteredFixture
import de.terletzkiy.ansibility.semantics.typeflow.AValue
import de.terletzkiy.ansibility.semantics.typeflow.BaseType

/**
 * The registered-member logical types of the T020 chains (plan amendment FU, F1.12: "`{{ x.rc }}` is an int") on
 * [RegisteredFixture]: documented members have their documented type, the whole result and undocumented members stay
 * unknown, and so do names that other definitions set too.
 */
class ChainResolverRegisteredTest : BasePlatformTestCase() {
    override fun setUp() {
        super.setUp()
        RegisteredFixture.create { path, text -> myFixture.tempDirFixture.createFile(path, text) }
        myFixture.tempDirFixture.createFile("site/roles/keepalived/defaults/main.yml", "keepalived_service: {changed: 1}\n")
        (AnsibleWorkspaceImpl.getInstance(project) ?: error("AnsibleWorkspace is not AnsibleWorkspaceImpl")).structureChanged()
    }

    private fun memberType(name: String, vararg path: String): AValue? = runReadActionBlocking {
        val file = myFixture.findFileInTempDir(RegisteredFixture.TASKS)!!
        val context = AnsibleWorkspace.getInstance(project).contextOf(file)!!
        ChainResolver(project, context.root, context.roleDir).memberType(name, path.toList())
    }

    fun testDocumentedMembersHaveTheirDocumentedType() {
        assertEquals(AValue.of(BaseType.INT), memberType("keepalived_floating_ip_check", "rc"))
        assertEquals(AValue.of(BaseType.STR), memberType("keepalived_floating_ip_check", "stdout"))
        assertEquals(AValue.of(BaseType.BOOL), memberType("keepalived_floating_ip_check", "changed"))
        assertEquals(AValue.of(BaseType.INT), memberType("keepalived_floating_ip_check", "attempts"))
        assertEquals(AValue.of(BaseType.BOOL), memberType("keepalived_conf", "stat", "exists"))
        assertEquals(AValue.of(BaseType.STR), memberType("keepalived_pings", "results", "0", "stdout"))
        assertEquals("both tasks document a str", AValue.of(BaseType.STR), memberType("keepalived_either", "stdout"))
    }

    fun testEverythingElseStaysUnknown() {
        assertNull("the whole result", memberType("keepalived_floating_ip_check"))
        assertNull("undocumented", memberType("keepalived_api", "nope"))
        assertNull("raw", memberType("keepalived_api", "json"))
        assertNull("a role default sets it too", memberType("keepalived_service", "changed"))
        assertNull("not registered", memberType("keepalived_mode", "x"))
        assertNull("a fact", memberType("ansible_facts", "os_family"))
    }

    fun testDefinitionsOfRegisteredNamesStayRuntimeOnly() {
        val definitions = runReadActionBlocking {
            val file = myFixture.findFileInTempDir(RegisteredFixture.TASKS)!!
            val context = AnsibleWorkspace.getInstance(project).contextOf(file)!!
            ChainResolver(project, context.root, context.roleDir).definitions("keepalived_floating_ip_check")
        }!!
        assertTrue(definitions.all { it.value == null })
    }
}
