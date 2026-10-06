package de.terletzkiy.ansibility.vars

import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.terletzkiy.ansibility.api.AnsibleSite
import de.terletzkiy.ansibility.api.SiteClassifier
import de.terletzkiy.ansibility.context.AnsibleWorkspaceImpl
import de.terletzkiy.ansibility.refactoring.VarRenamer
import de.terletzkiy.ansibility.vars.usages.VarOccurrences
import de.terletzkiy.ansibility.vars.usages.VarScope
import de.terletzkiy.ansibility.vars.usages.VarUsageSearch
import org.jetbrains.yaml.psi.YAMLKeyValue

/** Ctrl+B on a nested key of a mapping variable shows the usages of that member (`host_ips['ops-pxe1']`). */
class MemberKeyNavigationTest : BasePlatformTestCase() {
    fun testNestedKeyShowsMemberUsages() {
        myFixture.addFileToProject("ansible.cfg", "[defaults]\nroles_path = roles\n")
        myFixture.addFileToProject("environments/prod/hosts.yml", "all:\n  hosts:\n    ops-pxe1:\n")
        myFixture.addFileToProject(
            "environments/prod/group_vars/all.yml",
            "host_ips:\n  build-build1: 10.0.0.250\n  ops-pxe1: 10.0.0.247\n  ops-ops1: 10.0.0.251\n",
        )
        myFixture.addFileToProject("site.yml", "- hosts: all\n  roles:\n    - fw\n")
        myFixture.addFileToProject(
            "roles/fw/tasks/main.yml",
            "- name: allow\n  debug:\n    msg: \"{{ host_ips['ops-pxe1'] }}\"\n" +
                "- name: other\n  debug:\n    msg: \"{{ host_ips['ops-ops1'] }}\"\n",
        )
        myFixture.addFileToProject(
            "roles/fw/defaults/main.yml",
            "ip_whitelist:\n  - \"{{ host_ips['ops-pxe1'] }}\" # ops-pxe1\n",
        )
        AnsibleWorkspaceImpl.getInstance(project)!!.structureChanged()

        val vars = myFixture.findFileInTempDir("environments/prod/group_vars/all.yml")
        val offset = String(vars.contentsToByteArray()).indexOf("ops-pxe1") + 1
        runReadActionBlocking {
            val psi = psiManager.findFile(vars)!!
            val site = SiteClassifier.EP_NAME.extensionList.firstNotNullOf { it.classify(psi, offset) }
            assertTrue(site.toString(), site is AnsibleSite.VarKey)
            assertEquals(emptyList<Any>(), VarNavigation().targets(site, psi))

            val symbol = VarUsageSearch.symbolAt(psi, offset)!!
            assertTrue(symbol.scope is VarScope.Member)
            assertEquals("Member host_ips['ops-pxe1'] · ${symbol.root.displayName}", symbol.presentableText)
            val key = PsiTreeUtil.getParentOfType(psi.findElementAt(offset), YAMLKeyValue::class.java)!!
            assertEquals(symbol, VarUsageSearch.symbolOfKey(key))

            val occurrences = VarOccurrences.of(project, symbol, null)
            assertEquals(listOf(vars), occurrences.filter { it.write }.map { it.file })
            val reads = occurrences.filter { !it.write }
            assertEquals(setOf("defaults", "tasks"), reads.map { it.file.parent.name }.toSet())
            reads.forEach { assertEquals("ops-pxe1", String(it.file.contentsToByteArray()).substring(it.range.startOffset, it.range.endOffset)) }
        }

        val symbol = runReadActionBlocking { VarRenamer.symbolAt(psiManager.findFile(vars)!!, offset)!! }
        assertNull(VarRenamer.refusal(project, symbol))
        assertNotNull(VarRenamer.invalidMemberName("ops pxe"))
        val plan = runReadActionBlocking { VarRenamer.plan(project, symbol, "ops-pxe2") }
        assertEquals(3, plan.edits.size)
        plan.apply(project, "rename")
        val document = { path: String -> FileDocumentManager.getInstance().getDocument(myFixture.findFileInTempDir(path))!!.text }
        assertTrue(document("environments/prod/group_vars/all.yml").contains("  ops-pxe2: 10.0.0.247\n"))
        assertTrue(document("roles/fw/tasks/main.yml").contains("host_ips['ops-pxe2']"))
        assertTrue("other members stay", document("roles/fw/tasks/main.yml").contains("host_ips['ops-ops1']"))
        assertTrue(document("roles/fw/defaults/main.yml").contains("host_ips['ops-pxe2'] }}\" # ops-pxe1"))
    }
}
