package de.terletzkiy.ansibility.navigation

import com.intellij.openapi.project.DumbService
import com.intellij.testFramework.DumbModeTestUtils
import de.terletzkiy.ansibility.api.AnsibleSite

/** What [RefsSiteClassifier] reports, and that the dispatcher order leaves keys and Jinja to the other areas. */
class RefsSiteClassifierTest : RefsTestCase() {

    private fun siteAt(path: String, line: Int, marker: String, delta: Int = 1): AnsibleSite? = ours(path, offsetAt(path, line, marker, delta))

    fun testTaskFileSites() {
        copyInfra("golden/roles/haproxy", "golden/roles/keycloak", "golden/playbooks")
        val main = "golden/roles/haproxy/tasks/main.yml"
        val site = siteAt(main, 6, "configure.yml") as AnsibleSite.TaskFileRef
        assertEquals("haproxy", site.roleName)
        assertEquals("configure.yml", site.path)
        assertEquals("the range is the value", "configure.yml", psi(main).text.substring(site.range.startOffset, site.range.endOffset))

        val tasksFrom = siteAt(KEYCLOAK_PLAYBOOK, 51, "configure", 2) as AnsibleSite.TaskFileRef
        assertEquals(AnsibleSite.TaskFileRef("keycloak", "configure", tasksFrom.range), tasksFrom)
        assertEquals(AnsibleSite.RoleRef("keycloak", (siteAt(KEYCLOAK_PLAYBOOK, 50, "keycloak", 2) as AnsibleSite.RoleRef).range), siteAt(KEYCLOAK_PLAYBOOK, 50, "keycloak", 2))
        assertTrue(siteAt(KEYCLOAK_PLAYBOOK, 40, "keycloak", 2) is AnsibleSite.RoleRef)
    }

    fun testTemplateAndHandlerSites() {
        copyInfra("golden/roles/haproxy", "golden/roles/grafana")
        val configure = "golden/roles/haproxy/tasks/configure.yml"
        assertEquals("templates/haproxy.cfg.j2", (siteAt(configure, 4, "templates/") as AnsibleSite.TemplateRef).path)
        assertFalse((siteAt(configure, 4, "templates/") as AnsibleSite.TemplateRef).isCopySource)
        assertEquals("Reload haproxy", (siteAt(configure, 9, "Reload") as AnsibleSite.HandlerRef).name)
        assertEquals("a list item", "Restart otel-collector", (siteAt("golden/roles/grafana/tasks/otel.yml", 10, "Restart") as AnsibleSite.HandlerRef).name)
        assertEquals("a listen topic", "Reload systemd", (siteAt("golden/roles/haproxy/handlers/main.yml", 8, "Reload systemd") as AnsibleSite.HandlerRef).name)
        val copy = siteAt("golden/roles/haproxy/tasks/systemd.yml", 20, "files/override.conf", 2) as AnsibleSite.TemplateRef
        assertEquals(AnsibleSite.TemplateRef("files/override.conf", true, copy.range), copy)
    }

    fun testCopyWithRemoteSourceAndUnmappedAbsolutePathsAreNotSites() {
        copyInfra("golden/roles/loki")
        val prepare = "golden/roles/loki/molecule/default/prepare.yml"
        assertNull("remote_src: true (line 82)", siteAt(prepare, 80, "/etc/ssl/molecule", 3))
        createFile(
            "golden/roles/loki/tasks/extra.yml",
            "- ansible.builtin.template:\n    src: /etc/nginx/nginx.conf\n    dest: /tmp/x\n" +
                "- ansible.builtin.copy:\n    src: files/x.conf\n    remote_src: \"{{ flag }}\"\n    dest: /tmp/y\n",
        )
        assertNull("an absolute path on the managed host", siteAt("golden/roles/loki/tasks/extra.yml", 2, "/etc/nginx", 2))
        assertNull("a templated remote_src may be true", siteAt("golden/roles/loki/tasks/extra.yml", 5, "files/x.conf", 2))
    }

    fun testKeysAndJinjaBelongToTheOtherAreas() {
        copyInfra("repos/wren")
        val nginx = "repos/wren/ansible/roles/app-wren-mono/tasks/nginx.yml"
        assertNull("a key is never a reference", siteAt(nginx, 14, "src", 1))
        assertTrue("the module key is the docs track's", classify(nginx, offsetAt(nginx, 13, "template", 2)) is AnsibleSite.ModuleKey)
        val dynamic = siteAt(nginx, 14, "templates/nginx/", 3) as AnsibleSite.TemplateRef
        assertEquals("templates/nginx/{{ item.floating.template }}", dynamic.path)
        assertEquals("only the static prefix is ours", "templates/nginx/", psi(nginx).text.substring(dynamic.range.startOffset, dynamic.range.endOffset))
        assertNull("inside the Jinja", siteAt(nginx, 14, "item.floating", 3))
        assertTrue("the vars area answers inside the Jinja", classify(nginx, offsetAt(nginx, 14, "item.floating", 3)) is AnsibleSite.VarRef)
        assertEquals("qualified notify", "app-wren-mono : Reload nginx", (siteAt(nginx, 19, "Reload nginx") as AnsibleSite.HandlerRef).name)
    }

    fun testTemplateNameValueNeedsARenderer() {
        copyInfra("repos/wren")
        val vars = "repos/wren/ansible/environments/prod/group_vars/all/vars.yml"
        val site = siteAt(vars, 165, "frontend.protected", 3) as AnsibleSite.TemplateRef
        assertEquals("frontend.protected.site.proxy.conf.j2", site.path)
        assertNull("a value no dynamic src renders", siteAt(vars, 163, "crm.building", 2))
        assertNull("the key", siteAt(vars, 165, "template", 1))
        DumbModeTestUtils.runInDumbModeSynchronously(project) {
            assertTrue(DumbService.isDumb(project))
            assertNull("template-name values need the indexes", siteAt(vars, 165, "frontend.protected", 3))
        }
    }

    fun testMetaDependenciesVarsFilesImportsAndIncludes() {
        copyInfra("repos/falcon", "golden/roles/coolify", "repos/pelican", "repos/thrush")
        createFile("repos/thrush/ansible/roles/app-thrush-mono/tasks/main.yml", "---\n")
        assertEquals("docker", (siteAt("repos/falcon/ansible/roles/jenkins-agent-docker/meta/main.yml", 3, "docker", 2) as AnsibleSite.RoleRef).name)
        assertEquals(
            AnsibleSite.PlaybookFileRef("../vars/vars.yml", (siteAt("golden/roles/coolify/molecule/default/converge.yml", 6, "../vars", 3) as AnsibleSite.PlaybookFileRef).range),
            siteAt("golden/roles/coolify/molecule/default/converge.yml", 6, "../vars", 3),
        )
        assertEquals("../../playbook-setup-replisync.yml", (siteAt("repos/pelican/ansible/danger_zone/database/playbook-clone-to-replisync.yml", 34, "../../", 3) as AnsibleSite.PlaybookFileRef).path)
        val include = siteAt("repos/thrush/ansible/roles/app-thrush-mono/templates/deployment/docker-compose.yml.j2", 113, "deployment/", 3) as AnsibleSite.TemplateRef
        assertEquals("deployment/docker-compose.messenger-consumer.yml.j2", include.path)
    }

    fun testDispatcherOrderPutsUsBetweenVarsAndTasks() {
        copyInfra("golden/roles/haproxy")
        val main = "golden/roles/haproxy/tasks/main.yml"
        assertTrue("the first classifier to answer is ours", classify(main, offsetAt(main, 6, "configure.yml", 1)) is AnsibleSite.TaskFileRef)
        assertTrue(DumbService.isDumbAware(RefsSiteClassifier()))
    }
}
