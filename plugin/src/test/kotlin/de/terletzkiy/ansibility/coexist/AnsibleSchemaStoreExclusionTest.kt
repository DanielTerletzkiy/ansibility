package de.terletzkiy.ansibility.coexist

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.replaceService
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jetbrains.jsonSchema.remote.JsonSchemaCatalogExclusion
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.FileKind
import de.terletzkiy.ansibility.context.AnsibleWorkspaceImpl
import de.terletzkiy.ansibility.fixtures.InfraTestData

/** SchemaStore exclusion decisions on paths of the sanitised infra fixture. */
class AnsibleSchemaStoreExclusionTest : BasePlatformTestCase() {
    private val exclusion = AnsibleSchemaStoreExclusion()

    private class FixedSettings(private val exclude: Boolean) : CoexistenceSettings {
        override fun hideOtherAnsibleCompletions(): Boolean = false
        override fun schemaStoreExclusion(project: Project): Boolean = exclude
    }

    override fun getTestDataPath(): String = InfraTestData.testDataPath.toString()

    override fun setUp() {
        super.setUp()
        for (path in listOf("golden/roles/haproxy", "golden/playbooks", "repos/falcon")) {
            myFixture.copyDirectoryToProject("${InfraTestData.INFRA}/$path", path)
        }
        myFixture.tempDirFixture.createFile("outside/tasks/main.yml", "- ansible.builtin.ping:\n")
        myFixture.tempDirFixture.createFile("outside/playbooks/site.yml", "- hosts: all\n")
        AnsibleWorkspaceImpl.getInstance(project)!!.structureChanged()
        useSettings(exclude = true)
    }

    private fun useSettings(exclude: Boolean) {
        ApplicationManager.getApplication().replaceService(CoexistenceSettings::class.java, FixedSettings(exclude), testRootDisposable)
    }

    private fun vf(path: String): VirtualFile = myFixture.findFileInTempDir(path) ?: error("missing $path")

    private fun kind(path: String): FileKind? = AnsibleWorkspace.getInstance(project).contextOf(vf(path))?.kind

    private val excluded = mapOf(
        "golden/roles/haproxy/tasks/configure.yml" to FileKind.ROLE_TASKS,
        "golden/roles/haproxy/handlers/main.yml" to FileKind.ROLE_HANDLERS,
        "golden/playbooks/playbook-setup-system.yml" to FileKind.PLAYBOOK,
        "golden/roles/haproxy/molecule/default/converge.yml" to FileKind.MOLECULE_PLAYBOOK,
        "golden/roles/haproxy/molecule/default/verify.yml" to FileKind.MOLECULE_PLAYBOOK,
        "repos/falcon/ansible/playbook-setup-system.yml" to FileKind.PLAYBOOK,
        "repos/falcon/ansible/roles/loki/tasks/main.yml" to FileKind.ROLE_TASKS,
        "repos/falcon/ansible/roles/postfix/handlers/main.yml" to FileKind.ROLE_HANDLERS,
    )

    private val kept = mapOf(
        "golden/roles/haproxy/meta/argument_specs.yml" to FileKind.ROLE_ARGSPEC,
        "golden/roles/haproxy/defaults/main.yml" to FileKind.ROLE_DEFAULTS,
        "golden/roles/haproxy/molecule/default/molecule.yml" to FileKind.MOLECULE_CONFIG,
        "repos/falcon/ansible/roles/jenkins-agent-docker/meta/main.yml" to FileKind.ROLE_META,
        "repos/falcon/ansible/group_vars/all/vars.yml" to FileKind.GROUP_VARS,
        "repos/falcon/ansible/environments/prod/group_vars/keycloak/vars.yml" to FileKind.GROUP_VARS,
        "repos/falcon/ansible/environments/ops/host_vars/ops-ops1/vars.yml" to FileKind.HOST_VARS,
        "repos/falcon/ansible/environments/prod/hosts.yml" to FileKind.INVENTORY,
    )

    fun testExcludesTaskHandlerAndPlaybookFilesInsideRoots() {
        for ((path, expectedKind) in excluded) {
            assertEquals("precondition: kind of $path", expectedKind, kind(path))
            assertTrue("$path should not get SchemaStore schemas", exclusion.isExcluded(vf(path)))
        }
    }

    fun testKeepsArgumentSpecsMetaVarsAndOtherKinds() {
        for ((path, expectedKind) in kept) {
            assertEquals("precondition: kind of $path", expectedKind, kind(path))
            assertFalse("$path keeps its SchemaStore schema", exclusion.isExcluded(vf(path)))
        }
    }

    fun testKeepsFilesOutsideRootsAndNonYamlFiles() {
        assertNull(AnsibleWorkspace.getInstance(project).rootFor(vf("outside/tasks/main.yml")))
        assertFalse(exclusion.isExcluded(vf("outside/tasks/main.yml")))
        assertFalse(exclusion.isExcluded(vf("outside/playbooks/site.yml")))
        assertFalse(exclusion.isExcluded(vf("golden/roles/haproxy/templates/haproxy.cfg.j2")))
        assertFalse(exclusion.isExcluded(vf("repos/falcon/ansible/ansible.cfg")))
        assertFalse(exclusion.isExcluded(vf("golden/roles/haproxy/tasks")))
    }

    fun testSwitchedOffKeepsEverything() {
        useSettings(exclude = false)
        for (path in excluded.keys) assertFalse(path, exclusion.isExcluded(vf(path)))
    }

    fun testIsRegisteredForTheJsonPlugin() {
        assertNotNull(JsonSchemaCatalogExclusion.EP_NAME.findExtension(AnsibleSchemaStoreExclusion::class.java))
    }
}
