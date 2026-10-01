package de.terletzkiy.ansibility.model.molecule

import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.terletzkiy.ansibility.model.role.ModelFixture
import de.terletzkiy.ansibility.semantics.yaml.YScalar
import de.terletzkiy.ansibility.semantics.yaml.YSeq

/** [MoleculeScenarioModel] on the golden haproxy, coolify, chronod and redis scenarios of the fixture. */
class MoleculeScenarioModelTest : BasePlatformTestCase() {
    override fun getTestDataPath(): String = ModelFixture.testDataPath

    private fun vf(path: String): VirtualFile = ModelFixture.file(myFixture, path)

    private fun rel(file: VirtualFile, base: String): String = VfsUtilCore.getRelativePath(file, vf(base))!!

    fun testHaproxyInlineInventory() {
        ModelFixture.copyInfra(myFixture, "golden/roles/haproxy")
        val config = ModelFixture.yaml(myFixture, "golden/roles/haproxy/molecule/default/molecule.yml")
        val scenario = MoleculeScenarioModel.of(config)!!
        assertEquals("default", scenario.name)
        assertEquals(vf("golden/roles/haproxy/molecule/default"), scenario.scenarioDir)
        assertEquals(vf("golden/roles/haproxy"), scenario.roleDir)
        assertEquals(
            listOf("haproxy_deb13-\${MOLECULE_RUN_ID:-local}", "haproxy_deb12-\${MOLECULE_RUN_ID:-local}"),
            scenario.platforms.map { it.name?.text },
        )
        assertEquals(listOf("debian:13", "debian:12"), scenario.platforms.map { it.image })

        assertEquals(listOf("all"), scenario.groupVars.map { it.target })
        val all = scenario.groupVars("all")!!
        assertEquals(
            listOf("system_hostname", "system_networking_main_ip", "haproxy_bind_ip", "haproxy_apply_kernel_params", "haproxy_servers"),
            all.keys,
        )
        assertEquals(1, (all["haproxy_servers"] as YSeq).items.size)
        val ip = all.entries.single { it.key.text == "system_networking_main_ip" }
        val text = config.text
        assertEquals("system_networking_main_ip", ip.key.range!!.let { text.substring(it.start, it.end) })
        assertEquals("127.0.0.1", (ip.value as YScalar).range!!.let { text.substring(it.start, it.end) })
        assertEquals(listOf("haproxy_deb12-\${MOLECULE_RUN_ID:-local}", "haproxy_deb13-\${MOLECULE_RUN_ID:-local}"), scenario.hostVars.map { it.target })
        assertEquals("bookworm", (scenario.hostVars("haproxy_deb12-\${MOLECULE_RUN_ID:-local}")!!["system_apt_debian_version"] as YScalar).text)
        assertNull(scenario.hosts)
        assertEquals(listOf("converge", "verify", "prepare"), scenario.playbooks.keys.toList())
        assertEquals(vf("golden/roles/haproxy/molecule/default/converge.yml"), scenario.playbooks["converge"])
        assertEquals(emptyList<VirtualFile>(), scenario.varsFiles)
        val nameRange: TextRange = scenario.platforms.first().name!!.range
        assertEquals("haproxy_deb13-\${MOLECULE_RUN_ID:-local}", text.substring(nameRange.startOffset, nameRange.endOffset))
        assertSame("cached per file", scenario, MoleculeScenarioModel.of(config))
    }

    fun testVarsFilesAndScenarioLookup() {
        ModelFixture.copyInfra(myFixture, "golden/roles/coolify", "golden/roles/redis")
        val coolify = MoleculeScenarioModel.forScenarioDir(project, vf("golden/roles/coolify/molecule/default"))!!
        assertEquals(listOf("molecule/vars/vars.yml"), coolify.varsFiles.map { rel(it, "golden/roles/coolify") })

        val auth = MoleculeScenarioModel.scenarioOf(project, vf("golden/roles/redis/molecule/auth/converge.yml"))!!
        assertEquals("auth", auth.name)
        assertEquals(listOf("molecule/auth/vars/main.yml"), auth.varsFiles.map { rel(it, "golden/roles/redis") })
        assertNull(MoleculeScenarioModel.forScenarioDir(project, vf("golden/roles/redis/tasks")))
    }

    fun testInlineHostsLinksAndDeclaredPlaybooks() {
        myFixture.addFileToProject(
            "roles/demo/molecule/custom/molecule.yml",
            """
            scenario:
              name: custom-name
            platforms:
              - name: node1
                groups: [keepalived_master]
                children: loadbalancer
            provisioner:
              playbooks:
                converge: playbooks/run.yml
              inventory:
                hosts:
                  all:
                    children:
                      keepalived_master:
                        hosts:
                          node1:
                links:
                  group_vars: ../../inventory/group_vars/
            """.trimIndent(),
        )
        myFixture.addFileToProject("roles/demo/molecule/custom/playbooks/run.yml", "- hosts: all\n")
        myFixture.addFileToProject("roles/demo/molecule/custom/verify.yml", "- hosts: all\n")
        myFixture.addFileToProject("roles/demo/molecule/custom/group_vars/all.yml", "a: 1\n")
        myFixture.addFileToProject("roles/demo/molecule/custom/vars.yml", "b: 1\n")
        myFixture.addFileToProject("roles/demo/molecule/custom/notes.md", "no\n")
        val scenario = MoleculeScenarioModel.of(ModelFixture.yaml(myFixture, "roles/demo/molecule/custom/molecule.yml"))!!
        assertEquals("custom-name", scenario.name)
        assertEquals(listOf("keepalived_master"), scenario.platforms.single().groups.map { it.text })
        assertEquals(listOf("loadbalancer"), scenario.platforms.single().children.map { it.text })
        assertTrue(scenario.hosts is de.terletzkiy.ansibility.semantics.yaml.YMap)
        assertEquals("../../inventory/group_vars/", scenario.inventoryLinks["group_vars"]?.text)
        assertEquals(vf("roles/demo/molecule/custom/playbooks/run.yml"), scenario.playbooks["converge"])
        assertEquals(vf("roles/demo/molecule/custom/verify.yml"), scenario.playbooks["verify"])
        assertEquals(listOf("group_vars/all.yml", "vars.yml"), scenario.varsFiles.map { rel(it, "roles/demo/molecule/custom") })
    }
}
