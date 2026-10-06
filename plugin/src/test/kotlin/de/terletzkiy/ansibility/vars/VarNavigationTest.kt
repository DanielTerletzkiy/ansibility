package de.terletzkiy.ansibility.vars

import com.intellij.codeInsight.navigation.targetPresentation
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.testFramework.PlatformTestUtil
import de.terletzkiy.ansibility.api.AnsibleContextService
import de.terletzkiy.ansibility.api.AnsibleSite
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.SiteNavigation
import de.terletzkiy.ansibility.fixtures.InfraTestData
import de.terletzkiy.ansibility.settings.EnvironmentChoice
import de.terletzkiy.ansibility.settings.RootContext
import org.jetbrains.yaml.psi.YAMLKeyValue

/**
 * Ctrl+B on variables through the plugin's Go to Declaration entry point (plan F1.4, F1.5, F1.6, F4.5, F4.8; X87):
 * M2 acceptance 5, 6, 7 (navigation), 8 (navigation) and 9 on the infra fixture, ordering rules on `vars/site`.
 */
class VarNavigationTest : VarsTestCase() {

    private fun targets(path: String, line: Int, marker: String, delta: Int = 2): List<String> =
        gotoTargets(path, offsetAt(path, line, marker, delta)).map(::describe)

    // ------------------------------------------------------------------------------------------------ M2 acceptance

    /** Acceptance 5, 6, 7 and 8 (falcon, with golden's postfix and the worktree present). */
    fun testFalconNavigation() {
        copyInfra("repos/falcon", "golden/roles/postfix", "golden/roles/haproxy")
        myFixture.copyDirectoryToProject("${InfraTestData.INFRA}/${InfraTestData.WORKTREE_DIR}", "checkouts/${InfraTestData.WORKTREE_DIR}")
        refreshRoots()
        val prod = "repos/falcon/ansible/environments/prod/group_vars/all/vars.yml"

        assertEquals("an override key is a definition: Ctrl+B shows its usages", emptyList<String>(), targets(prod, 471, "postfix_relayhost"))

        val hostVars = "repos/falcon/ansible/environments/prod/host_vars/prod-prod1/vars.yml"
        assertEquals(
            "a host_vars key declared by four roles is still a definition: Ctrl+B shows its usages, no role chooser",
            emptyList<String>(),
            targets(hostVars, 25, "system_networking_main_ip", 2),
        )

        assertEquals(
            "a nested key goes to the nested option",
            listOf("repos/falcon/ansible/roles/haproxy/meta/argument_specs.yml:71"),
            targets(prod, 540, "port", 1),
        )

        val vars = "repos/falcon/ansible/group_vars/all/vars.yml"
        val vault = targets(vars, 172, "vault_system_access_root_pw", 3)
        assertEquals(
            listOf(
                "repos/falcon/ansible/environments/ops/host_vars/ops-ops1/vault.yml:44",
                "repos/falcon/ansible/environments/prod/host_vars/prod-prod1/vault.yml:4",
                "repos/falcon/ansible/environments/prod/host_vars/prod-prod2/vault.yml:4",
                "repos/falcon/ansible/environments/test/host_vars/test-test1/vault.yml:27",
            ),
            vault,
        )
    }

    /** Acceptance 9 and the golden side of 5: nothing from other roots or the worktree. */
    fun testGoldenRegisterNavigation() {
        copyInfra("golden/roles/chronod", "golden/roles/haproxy")
        myFixture.copyDirectoryToProject("${InfraTestData.INFRA}/${InfraTestData.WORKTREE_DIR}", "checkouts/${InfraTestData.WORKTREE_DIR}")
        refreshRoots()
        val nginx = "golden/roles/chronod/tasks/nginx.yml"
        assertEquals(listOf("golden/roles/chronod/tasks/nginx.yml:10"), targets(nginx, 18, "_chronod_nginx_cert"))

        val sysctl = "golden/roles/haproxy/tasks/sysctl.yml"
        assertEquals(
            listOf("golden/roles/haproxy/meta/argument_specs.yml:147", "golden/roles/haproxy/defaults/main.yml:15"),
            targets(sysctl, 11, "haproxy_settings_kernel_somaxconn", 3),
        )
    }

    // ------------------------------------------------------------------------------------------------ ordering rules

    fun testReferenceOrdering() {
        copyVarsData("site")
        assertEquals(
            "own spec, own defaults, then other roles; no inventory while roles declare it",
            listOf("site/roles/web/meta/argument_specs.yml:5", "site/roles/web/defaults/main.yml:3", "site/roles/other/meta/argument_specs.yml:5"),
            targets(TASKS, 31, "web_port"),
        )
        assertEquals(
            "from a playbook: roles applied by the same play first",
            listOf("site/roles/web/meta/argument_specs.yml:5", "site/roles/web/defaults/main.yml:3", "site/roles/other/meta/argument_specs.yml:5"),
            targets(PLAYBOOK, 13, "web_port"),
        )
        assertEquals(
            "from another role: its own spec first",
            listOf("site/roles/other/meta/argument_specs.yml:5", "site/roles/web/meta/argument_specs.yml:5", "site/roles/web/defaults/main.yml:3"),
            targets("site/roles/other/tasks/main.yml", 4, "web_port"),
        )
    }

    fun testRuntimeVariablesNearestBeforeTheCaretFirst() {
        copyVarsData("site")
        assertEquals(listOf("site/roles/web/tasks/main.yml:5", "site/roles/web/tasks/main.yml:42"), targets(TASKS, 8, "web_stat", 1))
        assertEquals(listOf("site/roles/web/tasks/main.yml:5", "site/roles/web/tasks/main.yml:42"), targets(TASKS, 41, "web_stat", 1))
    }

    fun testNestedAccessorsAndKeysGoToTheNestedOption() {
        copyVarsData("site")
        assertEquals(listOf("site/roles/web/meta/argument_specs.yml:11"), targets(TASKS, 4, "inner", 1))
        assertEquals(listOf("site/roles/web/meta/argument_specs.yml:11"), targets(GROUP_VARS, 4, "inner", 1))
        assertEquals("the root name goes to the variable", "site/roles/web/meta/argument_specs.yml:8", targets(TASKS, 4, "web_nested", 1).first())
    }

    /**
     * A nested key is never its own target (before, Ctrl+B on a nested argument_specs option that one role declares
     * jumped to itself): the nested option of the other declaring roles, or nothing, as on a role's own declaration.
     */
    fun testANestedSpecOptionNeverGoesToItself() {
        copyVarsData("site")
        assertEquals("only web declares web_nested.inner", emptyList<String>(), targets(SPEC, 11, "inner", 1))
        assertEquals("a nested defaults key still goes to the nested option", listOf("$SPEC:11"), targets(DEFAULTS, 5, "inner", 1))

        val third = "site/roles/third/meta/argument_specs.yml"
        createFile(
            third,
            """
            ---
            argument_specs:
              main:
                options:
                  web_nested:
                    type: dict
                    options:
                      inner:
                        type: int
            """.trimIndent() + "\n",
        )
        assertEquals("the other declaring role only", listOf("$third:8"), targets(SPEC, 11, "inner", 1))
        assertEquals(listOf("$SPEC:11"), targets(third, 8, "inner", 1))
    }

    fun testVarsFileKeys() {
        copyVarsData("site")
        assertEquals("an override key: nothing, Ctrl+B shows usages (Ctrl+U goes to what it overrides)", emptyList<String>(), targets(GROUP_VARS, 2, "web_port", 1))
        // D-FU1 (plan amendment FU): a role's own declaration gets no targets; the platform then shows its usages.
        assertEquals("from the defaults key: nothing, Ctrl+B shows usages", emptyList<String>(), targets(DEFAULTS, 3, "web_port", 1))
        assertEquals("from the spec option: nothing, Ctrl+B shows usages", emptyList<String>(), targets(SPEC, 5, "web_port", 1))
        assertEquals("from a role vars key: nothing", emptyList<String>(), targets("site/roles/web/vars/main.yml", 2, "web_internal", 1))
        assertEquals("an inventory-only key: nothing either", emptyList<String>(), targets(GROUP_VARS, 5, "inventory_only", 1))
        assertEquals("vault indirection", listOf("site/environments/dev/group_vars/all/vault.yml:2"), targets(GROUP_VARS, 6, "vault_web_secret", 1))
    }

    fun testLocalsAndUnknownNames() {
        copyVarsData("site")
        assertEquals("a template local goes to its binding", listOf("site/roles/web/templates/site.conf.j2:4"), targets(TEMPLATE, 5, "local_name", 1))
        assertEquals(emptyList<String>(), targets(TASKS, 13, "web_list", 1))
    }

    // ------------------------------------------------------------------------------------------------ rule 0: vars files

    /**
     * The user's report: `color_prompt_environment: "{{ environment_group | upper }}"` offered only the spec options.
     * A reference inside a vars file goes to the definition that file sees; the key itself shows its usages.
     */
    fun testReferenceInAVarsFileGoesToTheSameFilesDefinition() {
        copyInfra("repos/falcon")
        refreshRoots()
        val ops = "repos/falcon/ansible/environments/ops/group_vars/all/vars.yml"
        assertEquals("only the key below, so Ctrl+B jumps", listOf("$ops:56"), targets(ops, 47, "environment_group", 2))
        assertEquals("the key is a definition: Ctrl+B shows its usages", emptyList<String>(), targets(ops, 56, "environment_group", 1))
    }

    fun testPlatformGotoDeclarationJumpsFromAVarsFileReference() {
        copyInfra("repos/falcon")
        refreshRoots()
        val ops = "repos/falcon/ansible/environments/ops/group_vars/all/vars.yml"
        myFixture.configureFromTempProjectFile(ops)
        myFixture.editor.caretModel.moveToOffset(offsetAt(ops, 47, "environment_group", 2))
        myFixture.performEditorAction(IdeActions.ACTION_GOTO_DECLARATION)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        val editor = FileEditorManager.getInstance(project).selectedTextEditor!!
        assertEquals(vf(ops), editor.virtualFile)
        assertEquals(offsetAt(ops, 56, "environment_group"), editor.caretModel.offset)
    }

    /** Playbook-level `group_vars` apply to every environment: the winners of their hosts, narrowed by a selected environment. */
    fun testReferenceInPlaybookGroupVarsGoesToTheWinnersOfItsHosts() {
        copyInfra("repos/falcon")
        refreshRoots()
        val vars = "repos/falcon/ansible/group_vars/all/vars.yml"
        val env = "repos/falcon/ansible/environments"
        assertEquals(
            setOf("$env/ops/group_vars/all/vars.yml:56", "$env/prod/group_vars/all/vars.yml:520", "$env/test/group_vars/all/vars.yml:292"),
            targets(vars, 4, "environment_group", 2).toSet(),
        )
        val root = runReadActionBlocking { AnsibleWorkspace.getInstance(project).contextOf(vf(vars))!!.root }
        val context = AnsibleContextService.getInstance(project)
        context.setSelection(root, RootContext(environment = EnvironmentChoice.Named("prod")))
        try {
            assertEquals(listOf("$env/prod/group_vars/all/vars.yml:520"), targets(vars, 4, "environment_group", 2))
        } finally {
            context.setSelection(root, RootContext.DEFAULT)
        }
    }

    /** falcon test: a group_vars/all value that six preview hosts override in host_vars; Ctrl+B offers what the hosts see. */
    fun testReferenceInGroupVarsOffersTheHostVarsOverrides() {
        copyInfra("repos/falcon")
        refreshRoots()
        val test = "repos/falcon/ansible/environments/test"
        val got = targets("$test/group_vars/all/vars.yml", 114, "app_falcon_mono_nginx_host_journey", 3)
        assertEquals("the same file's winner first", "$test/group_vars/all/vars.yml:128", got.first())
        assertTrue("every preview host's own value: $got", got.drop(1).isNotEmpty() && got.drop(1).all { it.startsWith("$test/host_vars/preview-") && it.endsWith("/vars.yml:23") })
    }

    /** Role `defaults/`: a default built from another default goes to that default, not to the spec. */
    fun testReferenceInRoleDefaultsGoesToTheSameFilesDefault() {
        copyInfra("repos/falcon")
        refreshRoots()
        val defaults = "repos/falcon/ansible/roles/haproxy/defaults/main.yml"
        assertEquals(listOf("$defaults:1"), targets(defaults, 4, "haproxy_backports_version", 2))
        assertEquals(listOf("$defaults:12"), targets(defaults, 15, "haproxy_settings_maximum_connections", 2))
    }

    /** Tasks and templates keep the documented order (the own role's spec first). */
    fun testTasksAndTemplatesKeepTheSpecFirst() {
        copyVarsData("site")
        assertEquals("site/roles/web/meta/argument_specs.yml:5", targets(TASKS, 31, "web_port").first())
    }

    fun testTargetsNavigateAndCarryTheCard() {
        copyVarsData("site")
        val target = gotoTargets(TASKS, offsetAt(TASKS, 34, "web_port", 1)).first() as VarTargetElement
        val navigation = runReadActionBlocking { target.navigationElement }
        assertTrue(navigation is YAMLKeyValue)
        assertEquals("web_port", (navigation as YAMLKeyValue).keyText)
        assertTrue(target.canNavigate())
        target.navigate(true)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        val editor = FileEditorManager.getInstance(project).selectedTextEditor!!
        assertEquals(vf("site/roles/web/meta/argument_specs.yml"), editor.virtualFile)
        assertEquals(target.location.offset, editor.caretModel.offset)
        assertEquals(target, gotoTargets(TASKS, offsetAt(TASKS, 34, "web_port", 1)).first())
    }

    fun testPlatformGotoDeclarationActionJumpsToASingleTarget() {
        copyInfra("golden/roles/chronod")
        val nginx = "golden/roles/chronod/tasks/nginx.yml"
        myFixture.configureFromTempProjectFile(nginx)
        myFixture.editor.caretModel.moveToOffset(offsetAt(nginx, 18, "_chronod_nginx_cert", 2))
        myFixture.performEditorAction(IdeActions.ACTION_GOTO_DECLARATION)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        val editor = FileEditorManager.getInstance(project).selectedTextEditor!!
        assertEquals(vf(nginx), editor.virtualFile)
        assertEquals(offsetAt(nginx, 10, "_chronod_nginx_cert"), editor.caretModel.offset)
    }

    fun testPlatformGotoDeclarationActionJumpsToATemplateLocal() {
        copyVarsData("site")
        myFixture.configureFromTempProjectFile(TEMPLATE)
        myFixture.editor.caretModel.moveToOffset(offsetAt(TEMPLATE, 5, "local_name", 2))
        myFixture.performEditorAction(IdeActions.ACTION_GOTO_DECLARATION)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        val editor = FileEditorManager.getInstance(project).selectedTextEditor!!
        assertEquals(offsetAt(TEMPLATE, 4, "local_name"), editor.caretModel.offset)
    }

    fun testOnlyVarSitesAreNavigated() {
        copyVarsData("site")
        val navigation = SiteNavigation.EP_NAME.extensionList.filterIsInstance<VarNavigation>().single()
        val site = AnsibleSite.HandlerRef("Reload", TextRange(0, 1))
        assertEquals(emptyList<PsiElement>(), runReadActionBlocking { navigation.targets(site, psi(TASKS)) })
    }

    companion object {
        const val TASKS = "site/roles/web/tasks/main.yml"
        const val DEFAULTS = "site/roles/web/defaults/main.yml"
        const val SPEC = "site/roles/web/meta/argument_specs.yml"
        const val TEMPLATE = "site/roles/web/templates/site.conf.j2"
        const val GROUP_VARS = "site/environments/dev/group_vars/all/vars.yml"
        const val PLAYBOOK = "site/playbook.yml"
    }
}
