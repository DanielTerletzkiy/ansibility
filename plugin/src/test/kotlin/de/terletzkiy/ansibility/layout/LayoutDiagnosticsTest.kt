package de.terletzkiy.ansibility.layout

import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.context.AnsibleWorkspaceImpl
import de.terletzkiy.ansibility.context.layout.LayoutDiagnostics
import de.terletzkiy.ansibility.context.layout.LayoutDiagnostics.Code
import de.terletzkiy.ansibility.context.layout.LayoutReport
import de.terletzkiy.ansibility.context.layout.LayoutVarsInspection

/** The layout diagnostics ANS-L001–L008 and the Show Detected Layout report (plan amendment R10, F10.7, X108). */
class LayoutDiagnosticsTest : BasePlatformTestCase() {

    fun testCfgSyntaxErrorIsL004() {
        val cfg = add("ansible.cfg", "[defaults]\ninventory = a.ini\ninventory = b.ini\n")
        add("a.ini", "[web]\nw1\n")
        add("b.ini", "[web]\nw1\n")
        singleRoot()
        assertEquals(listOf(Code.L004), banner(cfg).map { it.code })
    }

    fun testCfgEntryProblemsAreL002AndL003() {
        val cfg = add("ansible.cfg", "[defaults]\ninventory = ./staging.ini, ./missing.ini, \"quoted.ini\"\n")
        add("staging.ini", "[web]\nstage1\n")
        add("site.yml", "- hosts: web\n  tasks: []\n")
        singleRoot()
        val codes = banner(cfg).map { it.code }
        assertFalse("an existing entry is fine", codes.isEmpty())
        assertTrue(codes.all { it == Code.L003 })
        assertEquals(2, codes.size)
    }

    fun testUnparsableInventoryIsL001() {
        add("ansible.cfg", "[defaults]\ninventory = ./hosts.yml\n")
        // A list is no YAML inventory, and `[web:bogus]` is an unknown INI section type: both plugins fail.
        val hosts = add("hosts.yml", "[web:bogus]\n")
        add("site.yml", "- hosts: all\n  tasks: []\n")
        singleRoot()
        assertEquals(listOf(Code.L001), banner(hosts).map { it.code })
    }

    fun testIniSkippedByExtensionOnOldCoreIsL007() {
        add("ansible.cfg", "[defaults]\ninventory = ./inventory\n[inventory]\nignore_extensions = .ini\n")
        val ini = add("inventory/prod.ini", "[web]\np1\n")
        add("inventory/staging.yml", "all:\n  hosts:\n    s1:\n")
        add("site.yml", "- hosts: all\n  tasks: []\n")
        singleRoot()
        assertEquals(listOf(Code.L007), banner(ini).map { it.code })
    }

    fun testExecutableOrPluginInventoryIsL008() {
        add("ansible.cfg", "[defaults]\ninventory = ./inventory\n")
        val plugin = add("inventory/aws_ec2.yml", "plugin: amazon.aws.aws_ec2\nregions: [eu-central-1]\n")
        add("inventory/static.yml", "all:\n  hosts:\n    s1:\n")
        add("site.yml", "- hosts: all\n  tasks: []\n")
        singleRoot()
        assertEquals(listOf(Code.L008), banner(plugin).map { it.code })
    }

    fun testHiddenVarsFileIsL005() {
        add("hosts.ini", "[web]\nw1\n")
        add("site.yml", "- hosts: web\n  tasks: []\n")
        add("group_vars/web.yml", "a: 1\n")
        val hidden = add("group_vars/web/main.yml", "b: 2\n")
        add("group_vars/web.yaml", "c: 3\n")
        singleRoot()
        val shadowed = myFixture.findFileInTempDir("group_vars/web.yaml")
        val codes = vars(shadowed).map { it.code } + vars(hidden).map { it.code }
        assertTrue("a second file of the same entity is hidden: $codes", Code.L005 in codes)
    }

    fun testVarsDirNextToNothingIsL006() {
        add("hosts.ini", "[web]\nw1\n")
        add("site.yml", "- hosts: web\n  tasks: []\n")
        val orphan = add("old/group_vars/web.yml", "a: 1\n")
        val used = add("group_vars/web.yml", "a: 1\n")
        singleRoot()
        assertEquals(listOf(Code.L006), vars(orphan).map { it.code })
        assertEquals(emptyList<Code>(), vars(used).map { it.code })
    }

    fun testConventionLayoutHasNoProblems() {
        add("ansible.cfg", "[defaults]\nroles_path = roles\n")
        val hosts = add("environments/prod/hosts.yml", "all:\n  hosts:\n    a1:\n")
        val vars = add("environments/prod/group_vars/all.yml", "x: 1\n")
        add("site.yml", "- hosts: all\n  tasks: []\n")
        singleRoot()
        assertEquals(emptyList<Code>(), banner(hosts).map { it.code })
        assertEquals(emptyList<Code>(), vars(vars).map { it.code })
    }

    fun testReportListsInventoriesSourcesAndProblems() {
        add("ansible.cfg", "[defaults]\ninventory = ./staging.ini, ./missing.ini\n")
        add("staging.ini", "[web]\nstage1\nstage2\n[db]\ndb1\n")
        add("group_vars/web.yml", "x: 1\n")
        add("site.yml", "- hosts: web\n  tasks: []\n")
        val root = singleRoot()
        val report = runReadActionBlocking { LayoutReport.text(project, root) }
        assertTrue(report, report.contains("staging.ini"))
        assertTrue(report, report.contains("3 hosts"))
        assertTrue(report, report.contains("2 groups"))
        assertTrue(report, report.contains("ansible.cfg: inventory = ./staging.ini, ./missing.ini"))
        assertTrue(report, report.contains("group_vars/"))
        assertTrue(report, report.contains("missing.ini"))
        assertTrue(report, report.contains("Project (ansible.cfg)"))
    }

    fun testVarsInspectionReportsOrphans() {
        add("hosts.ini", "[web]\nw1\n")
        add("site.yml", "- hosts: web\n  tasks: []\n")
        add("old/group_vars/web.yml", "a: 1\n")
        singleRoot()
        myFixture.enableInspections(LayoutVarsInspection())
        myFixture.configureFromTempProjectFile("old/group_vars/web.yml")
        assertTrue(myFixture.doHighlighting().any { it.description?.contains("ANS-L006") == true || it.inspectionToolId == "AnsibleLayoutVars" })
    }

    private fun banner(file: VirtualFile) = runReadActionBlocking { LayoutDiagnostics.bannerProblems(project, file) }

    private fun vars(file: VirtualFile) = runReadActionBlocking {
        val context = AnsibleWorkspace.getInstance(project).contextOf(file)!!
        LayoutDiagnostics.varsProblems(project, file, context)
    }

    private fun add(path: String, text: String): VirtualFile = myFixture.addFileToProject(path, text).virtualFile

    private fun singleRoot(): AnsibleRoot {
        AnsibleWorkspaceImpl.getInstance(project)!!.structureChanged()
        return runReadActionBlocking { AnsibleWorkspace.getInstance(project).roots() }.single()
    }
}
