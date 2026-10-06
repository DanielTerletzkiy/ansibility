package de.terletzkiy.ansibility.navigation

import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.vfs.VfsUtilCore
import de.terletzkiy.ansibility.api.AnsibleSite
import de.terletzkiy.ansibility.api.SiteClassifier
import de.terletzkiy.ansibility.fixtures.RequiresInfraFixture
import de.terletzkiy.ansibility.toolwindow.ToolWindowTestCase
import de.terletzkiy.ansibility.vars.VarNavigation
import de.terletzkiy.ansibility.vars.VarTargetElement
import de.terletzkiy.ansibility.vars.gutter.OverrideGotoSuper

/** Plan X42 (override gutters), X53 (Go to Related in a role) and X60 (console hyperlinks), on the infra fixture. */
@RequiresInfraFixture
class WorkflowExtrasTest : ToolWindowTestCase() {
    override fun setUp() {
        super.setUp()
        copyWholeFixture()
    }

    fun testRoleDefaultShowsWhereItIsOverridden() {
        myFixture.configureFromTempProjectFile("$FALCON/roles/postfix/defaults/main.yml")
        val tooltips = myFixture.findAllGutters().mapNotNull { it.tooltipText }
        assertTrue(tooltips.toString(), "Overridden in 3 places (prod, test)" in tooltips)
    }

    fun testPlaybookGroupVarsKeyShowsWhatItOverrides() {
        myFixture.configureFromTempProjectFile("$FALCON/group_vars/all/vars.yml")
        val tooltips = myFixture.findAllGutters().mapNotNull { it.tooltipText }
        assertTrue(tooltips.toString(), "Overrides 3 lower-precedence definitions" in tooltips)
    }

    fun testCtrlBOnAnOverrideShowsUsagesAndGoToSuperListsWhatItOverrides() {
        val file = vf("$FALCON/group_vars/all/vars.yml")
        val text = VfsUtilCore.loadText(file)
        val offset = text.indexOf("\npostfix_relayhost:") + 2
        runReadActionBlocking {
            val psi = myFixture.psiManager.findFile(file)!!
            val site = SiteClassifier.EP_NAME.extensionList.firstNotNullOf { it.classify(psi, offset) }
            assertTrue(site.toString(), site is AnsibleSite.VarKey)
            assertEquals("a definition shows its usages", emptyList<Any>(), VarNavigation().targets(site, psi))
        }
        myFixture.configureFromExistingVirtualFile(file)
        myFixture.editor.caretModel.moveToOffset(offset)
        val targets = runReadActionBlocking { OverrideGotoSuper.targets(myFixture.editor, myFixture.file) }
        val rows = runReadActionBlocking { targets.map { (it as VarTargetElement).presentableText to it.locationString } }
        assertEquals(
            listOf("environments/test/group_vars/all/vars.yml:323", "environments/prod/group_vars/all/vars.yml:471", "roles/postfix/defaults/main.yml:2"),
            rows.map { it.first },
        )
        assertEquals(rows.size, rows.map { it.first }.distinct().size)
        assertTrue(rows.toString(), rows[1].second.contains("level 4") && rows[1].second.contains("= relay.mx.example.de"))
        assertTrue(rows.toString(), rows.last().second.contains("role postfix"))
        val files = targets.map { relative(it.containingFile.virtualFile) }.sorted()
        assertEquals(
            listOf(
                "$FALCON/environments/prod/group_vars/all/vars.yml",
                "$FALCON/environments/test/group_vars/all/vars.yml",
                "$FALCON/roles/postfix/defaults/main.yml",
            ),
            files,
        )
    }

    fun testGoToRelatedListsTheRolesFilesAndTheRenderingTasks() {
        val template = myFixture.configureFromTempProjectFile("$FALCON/roles/haproxy/templates/haproxy.cfg.j2")
        val items = runReadActionBlocking { RoleRelatedItems().getItems(template) }
        val groups = items.map { it.group }.toSet()
        assertTrue(groups.toString(), "Spec" in groups && "Tasks" in groups && "Rendered by" in groups)
        val tasks = runReadActionBlocking { RoleRelatedItems().getItems(myFixture.psiManager.findFile(vf("$FALCON/roles/haproxy/tasks/main.yml"))!!) }
        assertTrue(tasks.any { it.group == "Templates" && it.element?.containingFile?.name == "haproxy.cfg.j2" })
    }

    fun testConsoleLinksContainerTaskPathsAndTaskHeaders() {
        val filter = AnsibleConsoleFilter(project)
        val path = filter.resolvePath("/ansible/roles/haproxy/tasks/main.yml")
        assertNotNull(path)
        assertEquals("$FALCON/roles/haproxy/tasks/main.yml", relative(path!!))
        val line = "task path: /ansible/roles/haproxy/tasks/main.yml:5"
        val result = filter.applyFilter(line, line.length)
        assertNotNull(result)
        assertEquals(line.indexOf("/ansible"), result!!.resultItems.single().highlightStartOffset)
        val (file, index) = filter.findTask("haproxy", "Apply kernel tuning for HAProxy")!!
        assertEquals("$FALCON/roles/haproxy/tasks/main.yml" to 7, relative(file) to index)
        val header = "TASK [haproxy : Apply kernel tuning for HAProxy] ****"
        assertNotNull(filter.applyFilter(header, header.length))
    }

    private fun relative(file: com.intellij.openapi.vfs.VirtualFile): String =
        VfsUtilCore.getRelativePath(file, myFixture.tempDirFixture.getFile("")!!) ?: file.path
}
