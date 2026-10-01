package de.terletzkiy.ansibility.index

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PathFactsTest {
    private fun hint(path: String) = PathFacts.of(path).hint

    @Test
    fun `role files are classified by their role sub-directory`() {
        assertEquals(PathHint.ARGUMENT_SPECS, hint("golden/roles/haproxy/meta/argument_specs.yml"))
        assertEquals(PathHint.ARGUMENT_SPECS, hint("golden/roles/haproxy/meta/argument_specs.yaml"))
        assertEquals(PathHint.ROLE_META, hint("golden/roles/haproxy/meta/main.yml"))
        assertEquals(PathHint.OTHER, hint("golden/roles/haproxy/meta/other.yml"))
        assertEquals(PathHint.DEFAULTS, hint("repos/falcon/ansible/roles/postfix/defaults/main.yml"))
        assertEquals(PathHint.DEFAULTS, hint("golden/roles/x/defaults/main/extra.yml"))
        assertEquals(PathHint.VARS, hint("golden/roles/x/vars/main.yml"))
        assertEquals(PathHint.TASKS, hint("golden/roles/haproxy/tasks/configure.yml"))
        assertEquals(PathHint.HANDLERS, hint("golden/roles/certs-client/handlers/main.yaml"))
        assertEquals(PathHint.HANDLERS, hint("golden/roles/grafana/handlers/molecule.yml"))
        assertEquals(PathHint.TEMPLATE, hint("golden/roles/haproxy/templates/haproxy.cfg.j2"))
        assertEquals("non-.j2 files under templates are templates", PathHint.TEMPLATE, hint("repos/x/roles/percona/templates/binlog-backup.sh"))
        assertEquals(PathHint.TEMPLATE, hint("repos/pelican/ansible/roles/app/templates/deployment/docker-compose.yml"))
        assertEquals("a sub-directory named like a role dir stays a template", PathHint.TEMPLATE, hint("golden/roles/x/templates/vars/a.conf"))
        assertEquals(PathHint.FILES, hint("golden/roles/x/files/foo.yml"))
        assertEquals(PathHint.TEMPLATE, hint("golden/roles/x/files/foo.yml.j2"))
    }

    @Test
    fun `molecule files`() {
        assertEquals(PathHint.MOLECULE_CONFIG, hint("golden/roles/haproxy/molecule/default/molecule.yml"))
        assertEquals(PathHint.OTHER, hint("golden/roles/haproxy/molecule/default/converge.yml"))
        assertEquals(PathHint.TEMPLATE, hint("golden/roles/haproxy/molecule/default/Dockerfile.j2"))
        assertEquals(PathHint.VARS, hint("golden/roles/coolify/molecule/vars/vars.yml"))
        assertEquals(PathHint.GROUP_VARS, hint("golden/roles/x/molecule/default/group_vars/all.yml"))
        assertEquals(PathHint.TEMPLATE, hint("golden/roles/iptables/molecule/default/templates/rules.v4"))
        assertTrue(PathFacts.of("golden/roles/haproxy/molecule/default/molecule.yml").inMolecule)
        assertFalse(PathFacts.of("golden/roles/haproxy/tasks/main.yml").inMolecule)
    }

    @Test
    fun `inventory and playbook level files`() {
        assertEquals(PathHint.GROUP_VARS, hint("repos/falcon/ansible/group_vars/all/vars.yml"))
        assertEquals(PathHint.GROUP_VARS, hint("repos/falcon/ansible/environments/prod/group_vars/all/vars.yml"))
        assertEquals(PathHint.GROUP_VARS, hint("repos/platform/ansible/group_vars/monitoring_client.yml"))
        assertEquals("a group named like a role dir", PathHint.GROUP_VARS, hint("repos/x/ansible/group_vars/templates/vars.yml"))
        assertEquals(PathHint.HOST_VARS, hint("repos/falcon/ansible/environments/test/host_vars/preview-dev2.bike.example.de"))
        assertEquals(PathHint.INVENTORY, hint("repos/falcon/ansible/environments/prod/hosts.yml"))
        assertEquals(PathHint.OTHER, hint("repos/falcon/ansible/playbook-setup-system.yml"))
        assertEquals(PathHint.OTHER, hint("golden/playbooks/playbook-setup-system.yml"))
        assertEquals(PathHint.VARS, hint("repos/x/ansible/vars/common.yml"))
        assertEquals(PathHint.TASKS, hint("repos/x/ansible/tasks/common.yml"))
        assertEquals(PathHint.TEMPLATE, hint("repos/thrush/ansible/files/grafana/alerting/host-disk-free.yml.j2"))
    }

    @Test
    fun `directories far above the tree do not change the hint`() {
        assertEquals(PathHint.TASKS, hint("/home/me/roles/work/golden/roles/haproxy/tasks/main.yml"))
        assertEquals(PathHint.OTHER, hint("/home/me/roles/work/site.yml"))
        assertEquals(PathHint.GROUP_VARS, hint("/home/me/roles/work/repos/falcon/ansible/group_vars/all/vars.yml"))
        assertEquals(PathHint.OTHER, hint("/home/me/vars/projects/work/repos/falcon/ansible/playbook-setup-system.yml"))
    }

    @Test
    fun `vault files`() {
        assertTrue(PathFacts.of("repos/falcon/ansible/group_vars/all/vault.yml").isVaultFile)
        assertTrue(PathFacts.of("repos/falcon/ansible/group_vars/all/vault.yaml").isVaultFile)
        assertTrue(PathFacts.of("repos/falcon/ansible/group_vars/all/vault").isVaultFile)
        assertFalse(PathFacts.of("repos/falcon/ansible/group_vars/all/vars.yml").isVaultFile)
    }

    @Test
    fun `hint codes round-trip`() {
        for (hint in PathHint.entries) assertEquals(hint, PathHint.ofCode(hint.code))
        assertEquals(PathHint.OTHER, PathHint.ofCode(99))
    }
}
