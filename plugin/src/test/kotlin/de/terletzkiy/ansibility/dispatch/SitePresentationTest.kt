package de.terletzkiy.ansibility.dispatch

import com.intellij.openapi.util.TextRange
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.terletzkiy.ansibility.api.AnsibleSite
import de.terletzkiy.ansibility.api.FileKind
import de.terletzkiy.ansibility.api.JinjaContainer
import de.terletzkiy.ansibility.api.KeywordLevel
import de.terletzkiy.ansibility.api.VarDefKind

class SitePresentationTest : BasePlatformTestCase() {
    private val range = TextRange(0, 1)

    fun testDescribesEverySiteKind() {
        val cases = listOf(
            AnsibleSite.VarRef("item", listOf("floating", "ssl"), JinjaContainer.YAML_TEMPLATE, range) to
                "Variable reference item.floating.ssl (Jinja in YAML)",
            AnsibleSite.VarRef("x", emptyList(), JinjaContainer.TEMPLATE_FILE, range, localNames = setOf("x")) to
                "Local variable x (template file)",
            AnsibleSite.VarKey(listOf("haproxy_servers", "name"), FileKind.GROUP_VARS, range) to
                "Variable key haproxy_servers.name (group_vars)",
            AnsibleSite.ModuleKey("ansible.builtin.template", range) to "Module ansible.builtin.template",
            AnsibleSite.ModuleOptionKey("community.docker.docker_container", listOf("healthcheck", "interval"), range) to
                "Option healthcheck.interval of community.docker.docker_container",
            AnsibleSite.KeywordKey("when", KeywordLevel.TASK, range) to "Keyword when (task)",
            AnsibleSite.KeywordKey("pause", KeywordLevel.LOOP_CONTROL, range) to "Keyword pause (loop_control)",
            AnsibleSite.RoleRef("haproxy", range) to "Role haproxy",
            AnsibleSite.TaskFileRef(null, "configure.yml", range) to "Task file configure.yml",
            AnsibleSite.TaskFileRef("haproxy", "configure", range) to "Task file configure of role haproxy",
            AnsibleSite.HandlerRef("Reload haproxy", range) to "Handler Reload haproxy",
            AnsibleSite.TemplateRef("haproxy.cfg.j2", false, range) to "Template haproxy.cfg.j2",
            AnsibleSite.TemplateRef("override.conf", true, range) to "Copy source override.conf",
            AnsibleSite.PlaybookFileRef("../vars/vars.yml", range) to "Playbook-relative file ../vars/vars.yml",
            AnsibleSite.JinjaFilter("default", range) to "Jinja filter default",
            AnsibleSite.JinjaTest("defined", range) to "Jinja test defined",
        )
        for ((site, expected) in cases) assertEquals(expected, SitePresentation.describe(site))
    }

    fun testVariableNames() {
        fun key(kind: FileKind, vararg path: String) = SitePresentation.variableName(AnsibleSite.VarKey(path.toList(), kind, range))

        assertEquals("item", SitePresentation.variableName(AnsibleSite.VarRef("item", listOf("a"), JinjaContainer.YAML_TEMPLATE, range)))
        assertEquals("haproxy_servers", key(FileKind.GROUP_VARS, "haproxy_servers", "name"))
        assertEquals("haproxy_log_path", key(FileKind.ROLE_DEFAULTS, "haproxy_log_path"))
        assertEquals("haproxy_bind_ip", key(FileKind.ROLE_ARGSPEC, "argument_specs", "main", "options", "haproxy_bind_ip", "type"))
        assertEquals("haproxy_bind_ip", key(FileKind.ROLE_ARGSPEC, "haproxy_bind_ip"))
        assertEquals("app_port", key(FileKind.PLAYBOOK, "0", "vars", "app_port"))
        assertEquals("app_port", key(FileKind.ROLE_TASKS, "app_port"))
        assertNull(key(FileKind.GROUP_VARS))
        assertNull(SitePresentation.variableName(AnsibleSite.ModuleKey("ansible.builtin.file", range)))
    }

    fun testDefinitionKindsAreCountedInFirstSeenOrder() {
        assertEquals(
            "group_vars ×2, role default, host_vars",
            SitePresentation.definitionKinds(listOf(VarDefKind.GROUP_VARS, VarDefKind.ROLE_DEFAULT, VarDefKind.GROUP_VARS, VarDefKind.HOST_VARS)),
        )
        assertEquals("", SitePresentation.definitionKinds(emptyList()))
        for (kind in VarDefKind.entries) assertFalse(kind.name, SitePresentation.definitionKindName(kind).isBlank())
    }
}
