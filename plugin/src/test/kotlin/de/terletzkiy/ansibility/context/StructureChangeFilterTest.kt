package de.terletzkiy.ansibility.context

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StructureChangeFilterTest {
    private val base = "/home/ci/build/infra"
    private val filter = StructureChangeFilter(listOf(base))

    private fun created(path: String, isDirectory: Boolean = false) = filter.isRelevantPath("$base/$path", isDirectory)

    @Test
    fun structuralFilesAreRelevant() {
        assertTrue(created("repos/falcon/ansible/ansible.cfg"))
        assertTrue(created("repos/falcon/ansible/environments/prod/hosts.yml"))
        assertTrue(created("golden/roles/haproxy/meta/argument_specs.yml"))
        assertTrue(created("golden/docker/ansible-lint/Dockerfile"))
        assertTrue(created("golden/docker/ansible-molecule/requirements.yml"))
        assertTrue(created("repos/falcon/.git"))
        assertTrue(created("repos/falcon/docker-compose.ansible-playbook.yaml"))
        assertTrue("playbook files make nested roots", created("repos/pelican/ansible/danger_zone/database/playbook-x.yml"))
    }

    @Test
    fun filesBelowStructuralDirectoriesAreRelevant() {
        assertTrue(created("golden/roles/haproxy/tasks/new.yml"))
        assertTrue(created("repos/falcon/ansible/environments/prod/group_vars/all/vars.yml"))
        assertTrue(created("repos/platform/ansible/group_vars/system.yml"))
        assertTrue(created("repos/falcon/ansible/environments/test/host_vars/preview-dev1.bike.example.de/vars.yml"))
    }

    @Test
    fun directoriesAreRelevantUnlessSkipped() {
        assertTrue(created("repos/new", isDirectory = true))
        assertFalse(created("node_modules", isDirectory = true))
        assertFalse(created("web/node_modules/pkg", isDirectory = true))
        assertTrue("the base itself", filter.isRelevantPath(base, isDirectory = true))
    }

    @Test
    fun ordinaryFilesAreIgnored() {
        assertFalse(created("docs/README.md"))
        assertFalse(created("repos/falcon/ansible/playbook-notes.md"))
        assertFalse(created("repos/falcon/ansible/site.yml"))
    }

    @Test
    fun eventsInsideSkippedTreesAreIgnored() {
        assertFalse(created(".git/index"))
        assertFalse(created(".git/worktrees/wt/HEAD"))
        assertFalse(created("web/node_modules/pkg/ansible.cfg"))
    }

    @Test
    fun onlyThePathBelowTheBaseCounts() {
        assertTrue("the base lives under a directory called build", created("repos/falcon/ansible/ansible.cfg"))
        assertFalse("outside every base", filter.isRelevantPath("/elsewhere/ansible.cfg", isDirectory = false))
        assertFalse("prefix of another directory", filter.isRelevantPath("/home/ci/build/infra2/ansible.cfg", isDirectory = false))
        assertFalse("nothing scanned yet", StructureChangeFilter(emptyList()).isRelevantPath("$base/ansible.cfg", isDirectory = false))
    }

    @Test
    fun contentChangesOfStructuralFiles() {
        assertTrue(StructureChangeFilter.isRelevantContentChange(listOf("repos", "falcon", "ansible", "ansible.cfg")))
        assertTrue(StructureChangeFilter.isRelevantContentChange(listOf("golden", "docker", "ansible-lint", "Dockerfile")))
        assertTrue(StructureChangeFilter.isRelevantContentChange(listOf("repos", "falcon", ".git")))
        assertFalse("task edits never bump the structure", StructureChangeFilter.isRelevantContentChange(listOf("roles", "x", "tasks", "main.yml")))
        assertFalse(StructureChangeFilter.isRelevantContentChange(listOf("node_modules", "ansible.cfg")))
    }
}
