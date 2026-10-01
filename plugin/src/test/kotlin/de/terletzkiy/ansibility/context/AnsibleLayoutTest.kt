package de.terletzkiy.ansibility.context

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnsibleLayoutTest {
    @Test
    fun groupFileNames() {
        assertEquals("all", AnsibleLayout.groupFromFileName("all.yml"))
        assertEquals("keycloak", AnsibleLayout.groupFromFileName("keycloak.yaml"))
        assertEquals("database", AnsibleLayout.groupFromFileName("database.json"))
        assertEquals("all", AnsibleLayout.groupFromFileName("all"))
        assertNull("foreign extension", AnsibleLayout.groupFromFileName("README.md"))
        assertNull("hidden", AnsibleLayout.groupFromFileName(".gitkeep"))
        assertNull("backup", AnsibleLayout.groupFromFileName("all.yml~"))
    }

    @Test
    fun hostFileNamesKeepDots() {
        assertEquals("test-test1", AnsibleLayout.hostFromFileName("test-test1.yml"))
        assertEquals("preview-dev1.bike.example.de", AnsibleLayout.hostFromFileName("preview-dev1.bike.example.de.yml"))
        assertEquals("preview-dev1.bike.example.de", AnsibleLayout.hostFromFileName("preview-dev1.bike.example.de"))
        assertEquals("prod-prod1", AnsibleLayout.hostFromFileName("prod-prod1"))
        assertNull(AnsibleLayout.hostFromFileName("README.md"))
        assertNull(AnsibleLayout.hostFromFileName(".DS_Store"))
    }

    @Test
    fun varsDirectoryEntriesFollowAnsibleCore() {
        assertTrue(AnsibleLayout.isVarsFileInDirectory("vars.yml"))
        assertTrue(AnsibleLayout.isVarsFileInDirectory("mysql_users.yml"))
        assertTrue(AnsibleLayout.isVarsFileInDirectory("vault.yaml"))
        assertTrue(AnsibleLayout.isVarsFileInDirectory("settings.json"))
        assertTrue("no extension", AnsibleLayout.isVarsFileInDirectory("vars"))
        assertFalse(AnsibleLayout.isVarsFileInDirectory("notes.md"))
        assertFalse(AnsibleLayout.isVarsFileInDirectory(".gitkeep"))
        assertFalse(AnsibleLayout.isVarsFileInDirectory("vars.yml~"))
        assertTrue(AnsibleLayout.isVarsSubdirectory("extra"))
        assertFalse("ansible-core only recurses into directories without extension", AnsibleLayout.isVarsSubdirectory("extra.d"))
        assertFalse(AnsibleLayout.isVarsSubdirectory(".hidden"))
    }

    @Test
    fun fileNamePredicates() {
        assertTrue(AnsibleLayout.isPlaybookName("playbook-setup-system.yml"))
        assertTrue(AnsibleLayout.isPlaybookName("playbook-clone-to-replisync.yaml"))
        assertFalse(AnsibleLayout.isPlaybookName("site.yml"))
        assertFalse(AnsibleLayout.isPlaybookName("playbook-notes.md"))
        assertTrue(AnsibleLayout.isHostsFileName("hosts.yml"))
        assertTrue(AnsibleLayout.isHostsFileName("hosts.yaml"))
        assertFalse(AnsibleLayout.isHostsFileName("hosts.ini"))
        assertTrue(AnsibleLayout.isMoleculeTasksName("verify_per_repo_tasks.yml"))
        assertFalse(AnsibleLayout.isMoleculeTasksName("verify.yml"))
        assertTrue(AnsibleLayout.isDockerfileName("Dockerfile"))
        assertTrue(AnsibleLayout.isDockerfileName("Dockerfile.alpine"))
        assertFalse("molecule templates are not images", AnsibleLayout.isDockerfileName("Dockerfile.j2"))
        assertEquals("converge", AnsibleLayout.stem("converge.yml"))
        assertEquals("main", AnsibleLayout.stem("main"))
    }
}
