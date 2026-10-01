package de.terletzkiy.ansibility.navigation

import com.intellij.codeInsight.navigation.targetPresentation
import com.intellij.openapi.application.runReadActionBlocking
import de.terletzkiy.ansibility.api.AnsibleSite
import de.terletzkiy.ansibility.fixtures.InfraTestData

/**
 * Ctrl+B on reference values through the plugin's Go to Declaration entry point (plan F1.8, X50): M3 acceptance 7 and
 * every other reference kind on the infra fixture, plus root scoping.
 */
class RefsNavigationTest : RefsTestCase() {

    private fun targets(path: String, line: Int, marker: String, delta: Int = 1): List<String> =
        gotoTargets(path, offsetAt(path, line, marker, delta)).map(::describe)

    // ------------------------------------------------------------------------------------------------ M3 acceptance 7

    fun testQualifiedNotifyResolvesToTheNamedRolesHandler() {
        copyInfra("golden/roles/coolify")
        assertEquals(
            listOf("golden/roles/coolify/handlers/main.yml:8"),
            targets("golden/roles/coolify/tasks/oauth2-proxy.yml", 17, "coolify : Reload oauth2-proxy"),
        )
    }

    fun testCrossRoleHandlerOfAnotherRole() {
        copyInfra("golden/roles/grafana", "golden/roles/keycloak", "golden/playbooks")
        val otel = "golden/roles/grafana/tasks/otel.yml"
        val targets = gotoTargets(otel, offsetAt(otel, 10, "Restart otel-collector", 2))
        assertEquals(listOf("golden/roles/keycloak/handlers/main.yml:7"), targets.map(::describe))
        val target = targets.single() as RefTargetElement
        assertTrue("labelled as a cross-role handler: ${target.label}", target.label!!.startsWith("cross-role (play scope"))
        val presentation = runReadActionBlocking { targetPresentation(target as com.intellij.psi.PsiElement) }
        assertEquals("Restart otel-collector", presentation.presentableText)
        assertEquals("keycloak · cross-role (play scope, no play applies both roles) · roles/keycloak/handlers/main.yml:7", presentation.containerText)
    }

    fun testHandlerOfARoleThePlayAppliesAlongside() {
        copyInfra("golden/roles/grafana", "golden/roles/keycloak")
        createFile(
            "golden/playbooks/playbook-observability.yml",
            "- name: Observability\n  hosts: all\n  roles:\n    - { role: keycloak }\n    - { role: grafana }\n",
        )
        val otel = "golden/roles/grafana/tasks/otel.yml"
        val target = gotoTargets(otel, offsetAt(otel, 10, "Restart otel-collector", 2)).single() as RefTargetElement
        assertEquals("golden/roles/keycloak/handlers/main.yml:7", describe(target))
        assertEquals("cross-role (play scope)", target.label)
    }

    fun testMoleculeContainerRolePathOpensTheRole() {
        copyInfra("golden/roles/haproxy")
        assertEquals(
            listOf("golden/roles/haproxy/tasks/main.yml"),
            targets("golden/roles/haproxy/molecule/default/converge.yml", 7, "/ansible/roles/haproxy", 5),
        )
    }

    // ------------------------------------------------------------------------------------------------ other kinds

    fun testIncludeTasksTargets() {
        copyInfra("golden/roles/haproxy")
        val main = "golden/roles/haproxy/tasks/main.yml"
        assertEquals(listOf("golden/roles/haproxy/tasks/apt.yml"), targets(main, 3, "apt.yml"))
        assertEquals(listOf("golden/roles/haproxy/tasks/configure.yml"), targets(main, 6, "configure.yml"))
        assertEquals(listOf("golden/roles/haproxy/tasks/observability.yml"), targets(main, 19, "observability.yml", 3))
    }

    fun testTemplateSourceAndNotifyInHaproxyConfigure() {
        copyInfra("golden/roles/haproxy")
        val configure = "golden/roles/haproxy/tasks/configure.yml"
        assertEquals(listOf("golden/roles/haproxy/templates/haproxy.cfg.j2"), targets(configure, 4, "templates/haproxy.cfg.j2", 12))
        assertEquals(listOf("golden/roles/haproxy/templates/rsyslog-haproxy.conf.j2"), targets(configure, 26, "rsyslog-haproxy"))
        assertEquals(listOf("golden/roles/haproxy/handlers/main.yml:30"), targets(configure, 9, "Reload haproxy"))
        assertEquals(listOf("golden/roles/haproxy/handlers/main.yml:18"), targets(configure, 31, "Restart rsyslog"))
    }

    fun testListenTopicGoesToItsNotifiers() {
        copyInfra("golden/roles/haproxy")
        val handlers = "golden/roles/haproxy/handlers/main.yml"
        assertEquals(listOf("golden/roles/haproxy/tasks/systemd.yml:16"), targets(handlers, 8, "Reload systemd", 3))
        assertEquals(
            "a notify of the topic reaches the handler that listens to it",
            listOf("golden/roles/haproxy/handlers/main.yml:7"),
            targets("golden/roles/haproxy/tasks/systemd.yml", 16, "Reload systemd"),
        )
    }

    fun testIncludeRoleWithTasksFromAndHandlersFrom() {
        copyInfra("golden/roles/grafana")
        val converge = "golden/roles/grafana/molecule/default/converge.yml"
        assertEquals(listOf("golden/roles/grafana/tasks/main.yml"), targets(converge, 38, "/ansible/roles/grafana", 3))
        assertEquals(listOf("golden/roles/grafana/tasks/alerting.yml"), targets(converge, 39, "alerting.yml"))
        assertEquals(listOf("golden/roles/grafana/handlers/molecule.yml"), targets(converge, 40, "molecule.yml"))
    }

    fun testTasksFromWithoutExtensionAndTheBrokenKeycloakReference() {
        copyInfra("golden/roles/keycloak", "golden/playbooks")
        val playbook = KEYCLOAK_PLAYBOOK
        assertEquals(listOf("golden/roles/keycloak/tasks/main.yml"), targets(playbook, 50, "keycloak", 2))
        assertEquals("configure does not exist (only configuration.yml)", emptyList<String>(), targets(playbook, 51, "configure", 2))
        assertEquals(listOf("golden/roles/keycloak/tasks/main.yml"), targets(playbook, 40, "keycloak", 2))
        createFile("golden/playbooks/playbook-configure.yml", "- hosts: all\n  tasks:\n    - ansible.builtin.include_role:\n        name: keycloak\n        tasks_from: configuration\n")
        assertEquals(listOf("golden/roles/keycloak/tasks/configuration.yml"), targets("golden/playbooks/playbook-configure.yml", 5, "configuration", 2))
    }

    fun testTemplateNameValueInVarsFile() {
        copyInfra("repos/wren")
        val vars = "repos/wren/ansible/environments/prod/group_vars/all/vars.yml"
        assertEquals(
            listOf("repos/wren/ansible/roles/app-wren-mono/templates/nginx/frontend.protected.site.proxy.conf.j2"),
            targets(vars, 165, "frontend.protected.site.proxy.conf.j2", 3),
        )
    }

    fun testDynamicSourcePrefixListsEveryCandidate() {
        copyInfra("repos/wren")
        val nginx = "repos/wren/ansible/roles/app-wren-mono/tasks/nginx.yml"
        val all = targets(nginx, 14, "templates/nginx/", 3)
        assertEquals(6, all.size)
        assertTrue(all.all { it.startsWith("repos/wren/ansible/roles/app-wren-mono/templates/nginx/") })
    }

    fun testTemplateIncludeInATemplate() {
        copyInfra("repos/thrush")
        // The fixture keeps only the templates of app-thrush-mono; a task file makes it a role (and repos/thrush/ansible a root).
        createFile("repos/thrush/ansible/roles/app-thrush-mono/tasks/main.yml", "---\n")
        val compose = "repos/thrush/ansible/roles/app-thrush-mono/templates/deployment/docker-compose.yml.j2"
        assertEquals(
            listOf("repos/thrush/ansible/roles/app-thrush-mono/templates/deployment/docker-compose.inbound-email-watcher.yml.j2"),
            targets(compose, 110, "deployment/docker-compose.inbound", 3),
        )
    }

    fun testTemplateNameValueWithSpecChoicesOpensTheSameRootsTemplate() {
        // loki_nginx_sites[].floating.template declares choices (golden/roles/loki/meta/argument_specs.yml:102); falcon has
        // its own loki role, so the value at the falcon ops vars resolves into falcon, never into golden.
        copyInfra("repos/falcon", "golden/roles/loki")
        val vars = "repos/falcon/ansible/environments/ops/group_vars/all/vars.yml"
        val offset = offsetAt(vars, 542, "main.site.proxy_protocol.conf.j2", 3)
        val site = classify(vars, offset) as AnsibleSite.TemplateRef
        assertEquals("main.site.proxy_protocol.conf.j2", site.path)
        assertEquals(
            listOf("repos/falcon/ansible/roles/loki/templates/nginx/main.site.proxy_protocol.conf.j2"),
            targets(vars, 542, "main.site.proxy_protocol.conf.j2", 3),
        )
    }

    fun testVarsFilesAndImportPlaybookArePlaybookFileSites() {
        copyInfra("golden/roles/coolify", "repos/pelican")
        val converge = "golden/roles/coolify/molecule/default/converge.yml"
        val site = classify(converge, offsetAt(converge, 6, "../vars/vars.yml", 3)) as AnsibleSite.PlaybookFileRef
        assertEquals("../vars/vars.yml", site.path)
        val clone = "repos/pelican/ansible/danger_zone/database/playbook-clone-to-replisync.yml"
        assertTrue(classify(clone, offsetAt(clone, 34, "../../playbook-setup-replisync.yml", 6)) is AnsibleSite.PlaybookFileRef)
    }

    fun testVarsFilesAndImportPlaybook() {
        copyInfra("golden/roles/coolify", "repos/pelican")
        assertEquals(
            listOf("golden/roles/coolify/molecule/vars/vars.yml"),
            targets("golden/roles/coolify/molecule/default/converge.yml", 6, "../vars/vars.yml", 3),
        )
        assertEquals(
            listOf("repos/pelican/ansible/playbook-setup-replisync.yml"),
            targets("repos/pelican/ansible/danger_zone/database/playbook-clone-to-replisync.yml", 34, "../../playbook-setup-replisync.yml", 6),
        )
    }

    fun testMetaDependency() {
        copyInfra("repos/falcon")
        assertEquals(
            listOf("repos/falcon/ansible/roles/docker/tasks/main.yml"),
            targets("repos/falcon/ansible/roles/jenkins-agent-docker/meta/main.yml", 3, "docker", 2),
        )
    }

    fun testPlayRolesOfAProjectRoot() {
        copyInfra("repos/falcon")
        assertEquals(
            listOf("repos/falcon/ansible/roles/haproxy/tasks/main.yml"),
            targets("repos/falcon/ansible/playbook-setup-system.yml", 52, "haproxy", 2),
        )
    }

    // ------------------------------------------------------------------------------------------------ scoping

    fun testDetachedWorktreeNeverResolvesIntoTheMainCheckout() {
        copyInfra("golden/roles/haproxy")
        myFixture.copyDirectoryToProject("${InfraTestData.INFRA}/${InfraTestData.WORKTREE_DIR}", InfraTestData.WORKTREE_DIR)
        refreshRoots()
        val worktreeTasks = "${InfraTestData.WORKTREE_DIR}/golden/roles/haproxy/tasks/extra.yml"
        createFile(worktreeTasks, "- ansible.builtin.include_tasks: configure.yml\n")
        assertEquals("the worktree has no configure.yml; the main checkout's is never a target", emptyList<String>(), targets(worktreeTasks, 1, "configure.yml"))
        assertEquals(listOf("golden/roles/haproxy/tasks/configure.yml"), targets("golden/roles/haproxy/tasks/main.yml", 6, "configure.yml"))
    }

    fun testOtherSitesAreLeftToTheirAreas() {
        copyInfra("golden/roles/haproxy")
        val configure = "golden/roles/haproxy/tasks/configure.yml"
        val site = runReadActionBlocking { RefsNavigation().targets(de.terletzkiy.ansibility.api.AnsibleSite.ModuleKey("ansible.builtin.template", com.intellij.openapi.util.TextRange(0, 1)), psi(configure)) }
        assertEmpty(site)
    }
}
