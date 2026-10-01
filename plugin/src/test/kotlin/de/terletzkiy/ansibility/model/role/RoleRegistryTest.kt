package de.terletzkiy.ansibility.model.role

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.terletzkiy.ansibility.api.RoleRegistry
import de.terletzkiy.ansibility.semantics.schema.OptionType

/** [RoleRegistryImpl] on fixture roles (golden, falcon) and the synthetic `model-roles/site` root. */
class RoleRegistryTest : BasePlatformTestCase() {
    override fun getTestDataPath(): String = ModelFixture.testDataPath

    private val registry: RoleRegistry get() = RoleRegistry.getInstance(project)

    private fun rel(file: VirtualFile, base: String): String = VfsUtilCore.getRelativePath(file, ModelFixture.file(myFixture, base))!!

    fun testServiceIsTheImplementation() {
        assertTrue(registry is RoleRegistryImpl)
    }

    fun testGoldenHaproxyRoleInfo() {
        ModelFixture.copyInfra(myFixture, "golden/roles/haproxy", "golden/roles/grafana")
        val golden = ModelFixture.root(myFixture, "golden")
        assertEquals(listOf("grafana", "haproxy"), registry.roles(golden).map { it.name })

        val haproxy = registry.role(golden, "haproxy")!!
        val dir = ModelFixture.file(myFixture, "golden/roles/haproxy")
        assertEquals(dir, haproxy.ref.dir)
        assertEquals(golden.dir, haproxy.ref.rootDir)
        assertEquals("meta/argument_specs.yml", rel(haproxy.specFile!!, "golden/roles/haproxy"))
        assertEquals(setOf("main"), haproxy.argumentSpecs.keys)
        val servers = haproxy.argumentSpecs.getValue("main").options.getValue("haproxy_servers")
        assertEquals(OptionType.List, servers.type)
        assertEquals(OptionType.Dict, servers.elements)
        assertEquals(listOf("name", "ip", "port", "weight"), servers.options!!.keys.toList())
        assertEquals(OptionType.Int, servers.options!!.getValue("port").type)
        assertTrue(servers.options!!.getValue("name").required)
        assertFalse(servers.options!!.getValue("weight").required)
        assertEquals(listOf("defaults/main.yml"), haproxy.defaultsFiles.map { rel(it, "golden/roles/haproxy") })
        assertEquals(emptyList<VirtualFile>(), haproxy.varsFiles)
        assertEquals(listOf("handlers/main.yml"), haproxy.handlerFiles.map { rel(it, "golden/roles/haproxy") })
        assertEquals("tasks/main.yml", rel(haproxy.taskFiles.first(), "golden/roles/haproxy"))
        assertEquals(
            listOf("access", "apt", "configure", "main", "observability", "sysctl", "systemd"),
            haproxy.taskFiles.map { it.nameWithoutExtension }.sorted(),
        )
        assertEquals("templates", haproxy.templatesDir?.name)
        assertEquals("files", haproxy.filesDir?.name)
        assertEquals(emptyList<String>(), haproxy.metaDependencies)

        val grafana = registry.role(golden, "grafana")!!
        assertEquals(listOf("handlers/main.yml", "handlers/molecule.yml"), grafana.handlerFiles.map { rel(it, "golden/roles/grafana") })
        assertEquals(listOf("vars/main.yml"), grafana.varsFiles.map { rel(it, "golden/roles/grafana") })
        assertNull(registry.role(golden, "keycloak"))
    }

    fun testFalconRolesStayInTheirRoot() {
        ModelFixture.copyInfra(myFixture, "golden/roles/haproxy", "repos/falcon")
        val falcon = ModelFixture.root(myFixture, "repos/falcon/ansible")
        val golden = ModelFixture.root(myFixture, "golden")
        assertEquals(
            listOf("docker", "grafana", "haproxy", "jenkins-agent-docker", "loki", "nginx", "postfix", "system", "totp-token"),
            registry.roles(falcon).map { it.name },
        )
        val falconHaproxy = registry.role(falcon, "haproxy")!!
        assertEquals(ModelFixture.file(myFixture, "repos/falcon/ansible/roles/haproxy"), falconHaproxy.ref.dir)
        assertEquals(falcon.dir, falconHaproxy.ref.rootDir)
        assertNotSame(registry.role(golden, "haproxy")!!.ref.dir, falconHaproxy.ref.dir)

        val configure = ModelFixture.file(myFixture, "repos/falcon/ansible/roles/haproxy/tasks/configure.yml")
        assertEquals("roleOf resolves inside the file's own root", falconHaproxy.ref, registry.roleOf(configure)?.ref)
        assertEquals(falconHaproxy.ref, registry.roleOf(ModelFixture.file(myFixture, "repos/falcon/ansible/roles/haproxy/tasks"))?.ref)
        assertEquals(falconHaproxy.ref, registry.roleOf(ModelFixture.file(myFixture, "repos/falcon/ansible/roles/haproxy"))?.ref)
        assertNull(registry.roleOf(ModelFixture.file(myFixture, "repos/falcon/ansible/playbook-setup-system.yml")))

        val agent = registry.role(falcon, "jenkins-agent-docker")!!
        assertEquals(listOf("docker"), agent.metaDependencies)
        assertEquals(emptyList<VirtualFile>(), agent.defaultsFiles)
        assertEquals(emptyList<VirtualFile>(), agent.handlerFiles)
        assertNotNull(agent.argumentSpecs["main"])
        assertNull(agent.templatesDir)
        assertEquals("tasks", registry.role(falcon, "totp-token")!!.taskFiles.first().parent.name)
    }

    fun testRoleAnatomyEdgeCases() {
        ModelFixture.copyModelRoles(myFixture)
        val site = ModelFixture.root(myFixture, "site")
        assertEquals(
            "ghosts (role-state), non-roles (docs-only) and shadowed copies are not listed",
            listOf("base", "common", "cycle-a", "cycle-b", "dirdefaults", "empty-options", "specinmeta", "web"),
            registry.roles(site).map { it.name },
        )
        assertEquals("roles_path entries count", ModelFixture.file(myFixture, "site/shared-roles/common"), registry.role(site, "common")!!.ref.dir)
        assertEquals(
            "the nearest roles dir wins",
            ModelFixture.file(myFixture, "site/roles/web"),
            registry.role(site, "web")!!.ref.dir,
        )
        val shadowed = registry.roleOf(ModelFixture.file(myFixture, "site/shared-roles/web/tasks/main.yml"))
        assertEquals("a shadowed copy is still its files' role", ModelFixture.file(myFixture, "site/shared-roles/web"), shadowed?.ref?.dir)
        assertNull(registry.roleOf(ModelFixture.file(myFixture, "site/roles/role-state")))
        assertNull(registry.role(site, "role-state"))

        val web = registry.role(site, "web")!!
        val base = "site/roles/web"
        assertEquals(listOf("defaults/main/10-base.yml", "defaults/main/20-more.yml", "defaults/other.yml"), web.defaultsFiles.map { rel(it, base) })
        assertEquals(listOf("vars/main.yml"), web.varsFiles.map { rel(it, base) })
        assertEquals(listOf("tasks/main.yml", "tasks/extra.yaml", "tasks/sub/nested.yml"), web.taskFiles.map { rel(it, base) })
        assertEquals(listOf("handlers/main.yaml", "handlers/molecule.yml"), web.handlerFiles.map { rel(it, base) })
        assertEquals("role names, a path by its last segment", listOf("base", "common", "common"), web.metaDependencies)
        assertEquals(listOf("main", "extra"), web.argumentSpecs.keys.toList())
        val options = web.argumentSpecs.getValue("main").options
        assertEquals("options: {} is kept as an empty map", emptyMap<String, Any>(), options.getValue("web_settings").options)
        assertNull("a missing options is free-form (null)", options.getValue("web_extra").options)
        assertEquals(emptyMap<String, Any>(), web.argumentSpecs.getValue("extra").options)

        assertEquals(emptyMap<String, Any>(), registry.role(site, "empty-options")!!.argumentSpecs.getValue("main").options)
        val specInMeta = registry.role(site, "specinmeta")!!
        assertEquals("meta/main.yml", rel(specInMeta.specFile!!, "site/roles/specinmeta"))
        assertEquals(OptionType.Bool, specInMeta.argumentSpecs.getValue("main").options.getValue("specinmeta_flag").type)
        assertEquals(
            "a defaults/main/ directory alone makes a role",
            listOf("defaults/main/values.yml"),
            registry.role(site, "dirdefaults")!!.defaultsFiles.map { rel(it, "site/roles/dirdefaults") },
        )
        val baseRole = registry.role(site, "base")!!
        assertNull(baseRole.specFile)
        assertEquals(emptyMap<String, Any>(), baseRole.argumentSpecs)
        assertEquals(emptyList<VirtualFile>(), baseRole.taskFiles)
    }

    fun testSpecEditsInvalidateTheRole() {
        ModelFixture.copyModelRoles(myFixture)
        val site = ModelFixture.root(myFixture, "site")
        assertFalse("web_added" in registry.role(site, "web")!!.argumentSpecs.getValue("main").options)
        val spec = ModelFixture.yaml(myFixture, "site/roles/web/meta/argument_specs.yml")
        WriteCommandAction.runWriteCommandAction(project) {
            val document = PsiDocumentManager.getInstance(project).getDocument(spec)!!
            val at = document.text.indexOf("      web_extra:")
            document.insertString(at, "      web_added:\n        type: str\n")
            PsiDocumentManager.getInstance(project).commitDocument(document)
        }
        assertEquals(OptionType.Str, registry.role(site, "web")!!.argumentSpecs.getValue("main").options.getValue("web_added").type)
    }
}
