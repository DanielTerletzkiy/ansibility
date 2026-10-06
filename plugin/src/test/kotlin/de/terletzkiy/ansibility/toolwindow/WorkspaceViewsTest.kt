package de.terletzkiy.ansibility.toolwindow

import com.intellij.navigation.NavigationItem
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.util.CommonProcessors
import com.intellij.util.indexing.FindSymbolParameters
import de.terletzkiy.ansibility.api.ScopeChoice
import de.terletzkiy.ansibility.api.WorkspaceScopeService
import de.terletzkiy.ansibility.settings.RootKeys
import de.terletzkiy.ansibility.toolwindow.model.EnvironmentNameNode
import de.terletzkiy.ansibility.toolwindow.model.RoleFileNode
import de.terletzkiy.ansibility.toolwindow.model.RoleNode
import de.terletzkiy.ansibility.toolwindow.model.TreeView
import de.terletzkiy.ansibility.toolwindow.model.WorkspaceNode
import de.terletzkiy.ansibility.workspace.ScopeWidgetSegment
import de.terletzkiy.ansibility.workspace.actions.UseEnvInAllRootsAction
import de.terletzkiy.ansibility.workspace.crossroot.CrossRootVars
import de.terletzkiy.ansibility.workspace.search.AnsibleSearchScopes
import de.terletzkiy.ansibility.workspace.search.AnsibleSymbolContributor
import de.terletzkiy.ansibility.workspace.search.AnsibleSymbolItem

/** Plan amendment R9 without drift: the Roles node and file browsing, the Roles and Environments tabs, F9.8 and F9.9. */
class WorkspaceViewsTest : ToolWindowTestCase() {
    override fun setUp() {
        super.setUp()
        copyWholeFixture()
    }

    fun testRootListsItsRolesWithLazyFileBrowsing() {
        assertEquals("Roles (9)", names(path("falcon")).last())
        val haproxy = path("falcon", "Roles (9)", "haproxy") as RoleNode
        assertTrue(haproxy.presentation().text, haproxy.presentation().text.contains("appl"))
        assertEquals("$FALCON/roles/haproxy/meta/argument_specs.yml:1", describe(haproxy.target))
        val files = children(haproxy).filterIsInstance<RoleFileNode>()
        val firstFile = files.indexOfFirst { !it.file.isDirectory }
        assertTrue("directories come first: ${files.map { it.file.name }}", firstFile == -1 || files.drop(firstFile).none { it.file.isDirectory })
        assertTrue(files.map { it.file.name }.toString(), "tasks" in files.map { it.file.name })
        val main = path(haproxy, "tasks", "main.yml")
        assertEquals("$FALCON/roles/haproxy/tasks/main.yml:1", describe(main.target))
    }

    fun testRolesTabGroupsCopiesByName() {
        val roles = WorkspaceNode(snapshot(), TreeView.ROLES)
        val grafana = path(roles, "grafana")
        val copies = names(grafana)
        assertEquals("the library copy comes first", listOf("golden", "falcon"), copies)
        assertTrue(grafana.presentation().text, grafana.presentation().text.contains("copies"))
        assertEquals("the same role directory is listed once", copies.size, copies.distinct().size)
    }

    fun testEnvironmentsTabGroupsInventoriesByName() {
        val environments = WorkspaceNode(snapshot(), TreeView.ENVIRONMENTS)
        val prod = path(environments, "prod")
        val repos = names(prod)
        assertTrue(repos.toString(), "falcon" in repos && repos.size > 1)
        assertEquals("Groups", names(path(prod, "falcon")).first())
    }

    fun testEnvironmentRowSetsTheContextOfEveryRootInScope() {
        val prod = path(WorkspaceNode(snapshot(), TreeView.ENVIRONMENTS), "prod") as EnvironmentNameNode
        val targets = UseEnvInAllRootsAction.targets(project, prod)
        assertEquals(prod.environments.size, targets.size)
        assertTrue(targets.all { it.environment == "prod" && it.host == null })
    }

    fun testScopeSegmentNamesTheScopeAndWarnsOutsideIt() {
        val service = WorkspaceScopeService.getInstance(project)
        val segment = ScopeWidgetSegment()
        val platformFile = vf("$PLATFORM/ansible.cfg")
        assertNull("All roots shows nothing", runReadActionBlocking { segment.segment(project, platformFile) })
        try {
            service.set(ScopeChoice.Roots(setOf(RootKeys.keyOf(project, vf(FALCON)))))
            assertEquals("scope falcon\u26a0", runReadActionBlocking { segment.segment(project, platformFile) }?.text)
            assertEquals("scope falcon", runReadActionBlocking { segment.segment(project, vf("$FALCON/ansible.cfg")) }?.text)
        } finally {
            service.set(ScopeChoice.AllRoots)
        }
    }

    fun testVariableInAllReposListsEveryRoot() {
        val report = runReadActionBlocking { CrossRootVars.report(project, "postfix_relayhost", null) }
        val roots = report.groups.map { it.root.displayName }
        assertTrue(roots.toString(), "falcon" in roots && roots.size > 1)
        assertTrue(report.definitionCount >= roots.size)
        val falcon = report.groups.first { it.root.displayName == "falcon" }
        assertTrue(falcon.definitions.any { it.definition.location.file.path.endsWith("group_vars/all/vars.yml") })
    }

    fun testSymbolsContributorFindsRolesAndVariablesPerRoot() {
        val contributor = AnsibleSymbolContributor()
        val names = CommonProcessors.CollectProcessor<String>()
        runReadActionBlocking { contributor.processNames(names, com.intellij.psi.search.GlobalSearchScope.allScope(project), null) }
        assertTrue("haproxy" in names.results && "postfix_relayhost" in names.results)
        val items = CommonProcessors.CollectProcessor<NavigationItem>()
        runReadActionBlocking { contributor.processElementsWithName("haproxy", items, FindSymbolParameters.simple(project, true)) }
        val locations = items.results.map { it.presentation!!.locationString!! }
        assertTrue(locations.toString(), locations.any { it.startsWith("role · falcon · roles/haproxy/") })
        val variables = CommonProcessors.CollectProcessor<NavigationItem>()
        runReadActionBlocking { contributor.processElementsWithName("postfix_relayhost", variables, FindSymbolParameters.simple(project, true)) }
        assertTrue(variables.results.map { (it as AnsibleSymbolItem).location.file.path }.any { it.contains("/falcon/") })
    }

    fun testFindChoosersOfferTheCurrentRootAndAllRoots() {
        val file = vf("$FALCON/roles/haproxy/tasks/main.yml")
        val context = SimpleDataContext.getSimpleContext(CommonDataKeys.VIRTUAL_FILE, file)
        val scopes = runReadActionBlocking { AnsibleSearchScopes().getGeneralSearchScopes(project, context) }
        assertEquals(listOf("Ansible root: falcon", "All Ansible roots"), scopes.map { it.displayName })
        assertTrue(scopes[0].contains(file))
        assertFalse(scopes[0].contains(vf("$PLATFORM/ansible.cfg")))
        assertTrue(scopes[1].contains(vf("$PLATFORM/ansible.cfg")))
    }
}
