package de.terletzkiy.ansibility.context

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AnsibleCfgTest {
    /** The shape of `repos/platform/ansible/ansible.cfg` (identical in the other repos minus remote_*). */
    private val platform = """
        [defaults]
        interpreter_python = auto_silent
        host_key_checking = False
        remote_user = provisioner
        remote_port = 2222
        callback_plugins = ./roles/inventory-docs-client/callback_plugins
        # a comment
        ; another comment
        [ssh_connection]
        pipelining = True
        ssh_args = -C -o ControlMaster=auto -o ControlPersist=10m -o ControlPath=~/.ssh/control-%C
    """.trimIndent()

    @Test
    fun readsSectionsAndValues() {
        val cfg = AnsibleCfg.parse(platform)
        assertEquals("provisioner", cfg.value("defaults", "remote_user"))
        assertEquals("2222", cfg.value("defaults", "remote_port"))
        assertEquals("True", cfg.value("ssh_connection", "pipelining"))
        assertEquals(
            "-C -o ControlMaster=auto -o ControlPersist=10m -o ControlPath=~/.ssh/control-%C",
            cfg.value("ssh_connection", "ssh_args"),
        )
        assertNull(cfg.value("defaults", "roles_path"))
        assertEquals(emptyList<String>(), cfg.rolesPath)
    }

    @Test
    fun keysAreCaseInsensitiveAndColonSeparatorsWork() {
        val cfg = AnsibleCfg.parse("[defaults]\nRoles_Path: ./roles\n")
        assertEquals("./roles", cfg.value("defaults", "roles_path"))
    }

    @Test
    fun inlineSemicolonCommentsNeedWhitespace() {
        val cfg = AnsibleCfg.parse("[defaults]\nremote_user = provisioner ; set by ops\nvault_identity = a;b\n")
        assertEquals("provisioner", cfg.value("defaults", "remote_user"))
        assertEquals("a;b", cfg.value("defaults", "vault_identity"))
    }

    @Test
    fun rolesPathIsSplitOnColons() {
        val cfg = AnsibleCfg.parse("[defaults]\nroles_path = ./galaxy_roles:../shared/roles : /etc/ansible/roles\n")
        assertEquals(listOf("./galaxy_roles", "../shared/roles", "/etc/ansible/roles"), cfg.rolesPath)
    }

    @Test
    fun continuationLinesAppend() {
        val cfg = AnsibleCfg.parse("[defaults]\nroles_path = ./a\n    ./b\nforks = 5\n")
        assertEquals("./a\n./b", cfg.value("defaults", "roles_path"))
        assertEquals("5", cfg.value("defaults", "forks"))
    }

    @Test
    fun keysBeforeAnySectionAreIgnored() {
        val cfg = AnsibleCfg.parse("orphan = 1\n[defaults]\nforks = 5\n")
        assertEquals(setOf("defaults"), cfg.sections.keys)
    }
}
