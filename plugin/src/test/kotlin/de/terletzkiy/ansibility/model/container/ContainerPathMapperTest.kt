package de.terletzkiy.ansibility.model.container

import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.terletzkiy.ansibility.model.role.ModelFixture
import de.terletzkiy.ansibility.yaml.PsiYValueAdapter
import org.jetbrains.yaml.psi.YAMLFile

/**
 * [ContainerPathMapper] with the compose files of the infra repo (recreated here: the fixture keeps none) and
 * the molecule convention of `golden/docker/ansible-molecule/bin/entrypoint.sh` (`cd /ansible/roles/<role>`).
 */
class ContainerPathMapperTest : BasePlatformTestCase() {
    override fun getTestDataPath(): String = ModelFixture.testDataPath

    private val mapper: ContainerPathMapper get() = ContainerPathMapper.getInstance(project)

    private fun vf(path: String) = ModelFixture.file(myFixture, path)

    override fun setUp() {
        super.setUp()
        ModelFixture.copyInfra(myFixture, "golden/roles/haproxy", "golden/playbooks", "repos/pelican", "repos/falcon")
        myFixture.addFileToProject(
            "docker-compose.ansible-molecule.yaml",
            "services:\n  ansible-molecule:\n    volumes:\n      - ./golden/roles:/ansible/roles\n      - /var/run/docker.sock:/var/run/docker.sock:z\n",
        )
        myFixture.addFileToProject("docker-compose.ansible-lint.yaml", "services:\n  ansible-lint:\n    volumes:\n      - ./golden:/ansible\n")
        myFixture.addFileToProject(
            "repos/pelican/docker-compose.ansible-playbook.yaml",
            "services:\n  ansible-playbook:\n    volumes:\n      - ./:/ansible\n      - \$ANSIBLE_LOCAL_SSH_KNOWN_HOSTS:/root/.ssh/known_hosts\n" +
                "      - \${SSH_AGENT_SOCK-\${SSH_AUTH_SOCK}}:/root/.ssh/agent\n",
        )
        myFixture.addFileToProject(
            "repos/pelican/docker-compose.ansible-lint.yaml",
            "services:\n  ansible-lint:\n    volumes:\n      - type: bind\n        source: ./ansible\n        target: /ansible/\n      - named:/data\n      - /anonymous\n",
        )
        ModelFixture.rescan(project)
    }

    fun testMoleculeConventionMapsToTheEnclosingRolesDir() {
        val converge = vf("golden/roles/haproxy/molecule/default/converge.yml")
        assertEquals(vf("golden/roles/haproxy"), mapper.map("/ansible/roles/haproxy", converge))
        assertEquals(vf("golden/roles/haproxy/templates/haproxy.cfg.j2"), mapper.map("/ansible/roles/haproxy/templates/haproxy.cfg.j2", converge))
        assertEquals(vf("golden/roles"), mapper.map("/ansible/roles/", converge))
        assertNull(mapper.map("/ansible/roles/missing", converge))
        assertEquals(MappingSource.MOLECULE, mapper.mappings(converge).first().source)

        val agent = vf("repos/falcon/ansible/roles/jenkins-agent-docker/molecule/default/converge.yml")
        assertEquals(
            "a repo's molecule maps to the repo's own roles dir, not golden's",
            vf("repos/falcon/ansible/roles/jenkins-agent-docker"),
            mapper.map("/ansible/roles/jenkins-agent-docker", agent),
        )
    }

    fun testComposeVolumesOfTheRootAndItsAncestors() {
        val goldenPlaybook = vf("golden/playbooks/playbook-setup-system.yml")
        assertEquals(vf("golden/playbooks/playbook-setup-keycloak.yml"), mapper.map("/ansible/playbooks/playbook-setup-keycloak.yml", goldenPlaybook))
        assertEquals("the longer prefix wins", vf("golden/roles/haproxy"), mapper.map("/ansible/roles/haproxy", goldenPlaybook))
        assertEquals(vf("golden/roles/haproxy"), mapper.map("/ansible/./roles/x/../haproxy", goldenPlaybook))

        val replisync = vf("repos/pelican/ansible/playbook-setup-replisync.yml")
        assertEquals("the nearest compose file (./ansible:/ansible) first", replisync, mapper.map("/ansible/playbook-setup-replisync.yml", replisync))
        assertEquals(
            "./ansible:/ansible has no ansible/ansible/, so ./:/ansible answers",
            replisync,
            mapper.map("/ansible/ansible/playbook-setup-replisync.yml", replisync),
        )
        assertNull("env-var and named volumes are skipped", mapper.map("/root/.ssh/known_hosts", replisync))
        assertNull(mapper.map("/data", replisync))
        assertNull("relative paths are not container paths", mapper.map("ansible/playbook-setup-replisync.yml", replisync))

        assertEquals(
            "the root dir and its ancestors up to the project dir, nearest first",
            listOf(
                Triple("/ansible", "repos/pelican/docker-compose.ansible-lint.yaml", "repos/pelican/ansible"),
                Triple("/ansible", "repos/pelican/docker-compose.ansible-playbook.yaml", "repos/pelican"),
                Triple("/ansible", "docker-compose.ansible-lint.yaml", "golden"),
                Triple("/ansible/roles", "docker-compose.ansible-molecule.yaml", "golden/roles"),
            ),
            mapper.mappings(replisync).map { Triple(it.containerPath, rel(it.composeFile!!), rel(it.hostDir)) },
        )
    }

    private fun rel(file: VirtualFile): String =
        VfsUtilCore.getRelativePath(file, myFixture.tempDirFixture.getFile("")!!)!!

    fun testComposeVolumesParser() {
        val file = myFixture.configureByText(
            "docker-compose.yml",
            "x-common: &common\n  volumes:\n    - ./a:/a:ro\nservices:\n  one:\n    <<: *common\n  two:\n    volumes:\n      - source: ./b\n        target: /b\n      - type: volume\n        source: data\n        target: /data\n",
        ) as YAMLFile
        assertEquals(
            listOf(ComposeVolumes.Volume("./a", "/a"), ComposeVolumes.Volume("./b", "/b")),
            ComposeVolumes.parse(PsiYValueAdapter.documentValue(file)),
        )
        assertEquals("/", ContainerPathMapper.normalize("/"))
        assertEquals("/a/c", ContainerPathMapper.normalize("//a/b/../c/"))
        assertNull(ContainerPathMapper.normalize("a/b"))
    }
}
