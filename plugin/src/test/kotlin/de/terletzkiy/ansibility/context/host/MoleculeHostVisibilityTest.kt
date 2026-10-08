package de.terletzkiy.ansibility.context.host

import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.terletzkiy.ansibility.api.AnsibleContextService
import de.terletzkiy.ansibility.api.HostScope
import de.terletzkiy.ansibility.api.HostScopeOrigin
import de.terletzkiy.ansibility.context.AnsibleWorkspaceImpl
import de.terletzkiy.ansibility.context.MoleculeNavigationFixture
import de.terletzkiy.ansibility.context.switching.FileScopeSegment
import de.terletzkiy.ansibility.render.service.TemplatePreviewService
import de.terletzkiy.ansibility.settings.SettingsTestSupport

/**
 * Plan amendment R20, D153 and D156 on the host side: presentation (host scopes, effective values, definition status)
 * leaves Molecule hosts out of production files while "Show Molecule in navigation and search" is off; inspections
 * ([AnsibleContextService.allHostsScope]) and Molecule files keep them.
 */
class MoleculeHostVisibilityTest : BasePlatformTestCase() {
    override fun setUp() {
        super.setUp()
        MoleculeNavigationFixture.create { path, text -> myFixture.tempDirFixture.createFile(path, text) }
        // A role library without inventory: its role's only hosts are its Molecule scenario's (D156).
        myFixture.tempDirFixture.createFile("$LIB/ansible.cfg", "[defaults]\nroles_path = roles\n")
        myFixture.tempDirFixture.createFile(LIB_DEFAULTS, "---\nedge_port: 1\n")
        myFixture.tempDirFixture.createFile(
            LIB_MOLECULE,
            "---\ndriver:\n  name: default\nplatforms:\n  - name: edge-instance\nprovisioner:\n  name: ansible\n  inventory:\n    group_vars:\n      all:\n        edge_port: 2\n",
        )
        myFixture.tempDirFixture.createFile(LIB_CONVERGE, "---\n- name: Converge\n  hosts: all\n  roles: [edge]\n")
        myFixture.tempDirFixture.createFile(LIB_TASKS, "---\n- name: Motd\n  ansible.builtin.template:\n    src: motd.j2\n    dest: /etc/motd\n")
        myFixture.tempDirFixture.createFile(LIB_TEMPLATE, "port {{ edge_port }}\n")
        AnsibleWorkspaceImpl.getInstance(project)!!.structureChanged()
        IndexingTestUtil.waitUntilIndexesAreReady(project)
    }

    override fun tearDown() {
        try {
            SettingsTestSupport.resetAll(project)
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    private val service: AnsibleContextService get() = AnsibleContextService.getInstance(project)

    private fun vf(path: String): VirtualFile = myFixture.findFileInTempDir(path) ?: error("missing $path")

    private fun show(show: Boolean) = MoleculeNavigationFixture.showInNavigation(project, show)

    private fun hostScope(path: String): HostScope = runReadActionBlocking { service.hostScope(vf(path)) }

    private fun cardScope(path: String): HostScope = runReadActionBlocking { AnsibleContextServiceImpl.getInstance(project)!!.cardScope(vf(path)) }

    private fun allHostsScope(path: String): HostScope = runReadActionBlocking { service.allHostsScope(vf(path)) }

    fun testAGoldenRoleCardFallsBackToNoInventoryWhileMoleculeIsHidden() {
        val off = cardScope(LIB_DEFAULTS)
        assertEquals(HostScopeOrigin.RootWide, off.origin)
        assertEquals(emptyList<Any>(), off.targets)
        assertEquals("$LIB has no inventory", off.emptyReason)
        val inspections = allHostsScope(LIB_DEFAULTS)
        assertTrue("inspections keep the scenario's hosts: ${inspections.hosts}", inspections.hosts.isNotEmpty() && inspections.hosts.all { it.isMolecule })
        show(true)
        val on = cardScope(LIB_DEFAULTS)
        assertTrue("on: the scenario's hosts, as before R20: ${on.hosts}", on.hosts.isNotEmpty() && on.hosts.all { it.isMolecule })
        assertEquals("inspections unchanged", inspections.targets, allHostsScope(LIB_DEFAULTS).targets)
        assertEquals("the scenario's own files always use it", HostScopeOrigin.Molecule(vf(LIB_MOLECULE).parent), cardScope(LIB_MOLECULE).origin)
    }

    /** D156 is about cards (and the status bar): Template Preview and the other hostScope users keep a golden role's hosts. */
    fun testAGoldenRoleKeepsItsMoleculeHostsOutsideCardsWhileMoleculeIsHidden() {
        val scope = hostScope(LIB_DEFAULTS)
        assertTrue("hostScope keeps the scenario's hosts: ${scope.origin}", scope.origin is HostScopeOrigin.RoleReach && scope.hosts.all { it.isMolecule })
        assertTrue(scope.hosts.isNotEmpty())
        val preview = runReadActionBlocking { TemplatePreviewService.getInstance(project).renderingHosts(vf(LIB_TEMPLATE)) }
        assertEquals("the preview's context picker offers the scenario host", listOf("edge-instance"), preview.orEmpty().map { it.host })
        val rendered = runReadActionBlocking { TemplatePreviewService.getInstance(project).render(vf(LIB_TEMPLATE), "port {{ edge_port }}\n", null) }
        assertTrue("rendered with the scenario's value: ${rendered.text}", rendered.text.startsWith("port 2"))
    }

    /** The status-bar file segment follows the setting like the cards (D156). */
    fun testTheStatusBarSegmentOfAGoldenRoleFollowsTheSetting() {
        fun segment() = runReadActionBlocking { FileScopeSegment().segment(project, vf(LIB_DEFAULTS))?.text }
        assertNull("off: root-wide, no file segment", segment())
        show(true)
        assertNotNull("on: the role's scenario hosts", segment())
    }

    fun testMoleculeFilesKeepTheirScenarioWhileMoleculeIsHidden() {
        val scope = hostScope(LIB_CONVERGE)
        assertTrue(scope.origin.toString(), scope.origin is HostScopeOrigin.Molecule)
        assertTrue("${scope.hosts}", scope.hosts.isNotEmpty() && scope.hosts.all { it.isMolecule })
    }

    /** The effective value's Molecule companions (the cards' "molecule default: = …" line) follow the setting. */
    fun testRoleFilesGetMoleculeCompanionsOnlyWhenMoleculeIsShown() {
        fun companions(): Int = runReadActionBlocking {
            service.effective(service.hostScope(vf(MoleculeNavigationFixture.DEFAULTS)), "web_port").molecule.size
        }
        assertTrue("the role runs on prod: ${hostScope(MoleculeNavigationFixture.DEFAULTS).hosts}", hostScope(MoleculeNavigationFixture.DEFAULTS).hosts.none { it.isMolecule })
        assertEquals("off", 0, companions())
        show(true)
        assertEquals("on: the scenario's value on its own line", 1, companions())
    }

    private companion object {
        const val LIB = "lib"
        const val LIB_DEFAULTS = "$LIB/roles/edge/defaults/main.yml"
        const val LIB_MOLECULE = "$LIB/roles/edge/molecule/default/molecule.yml"
        const val LIB_CONVERGE = "$LIB/roles/edge/molecule/default/converge.yml"
        const val LIB_TASKS = "$LIB/roles/edge/tasks/main.yml"
        const val LIB_TEMPLATE = "$LIB/roles/edge/templates/motd.j2"
    }
}
