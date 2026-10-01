package de.terletzkiy.ansibility.context

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybookProbeTest {
    @Test
    fun playsWithHosts() {
        assertTrue(PlaybookProbe.looksLikePlaybook("- hosts: all\n  roles:\n    - system\n"))
        assertTrue(PlaybookProbe.looksLikePlaybook("---\n# Setup\n- name: Setup\n  hosts: all\n  become: true\n"))
        assertTrue(PlaybookProbe.looksLikePlaybook("-   name: Wide indent\n    hosts: database:replisync\n"))
    }

    @Test
    fun importPlaybookLists() {
        // repos/platform/ansible/playbook-setup-all.yml
        assertTrue(PlaybookProbe.looksLikePlaybook("- name: System\n  ansible.builtin.import_playbook: playbook-setup-system.yml\n"))
        assertTrue(PlaybookProbe.looksLikePlaybook("- import_playbook: other.yml\n"))
    }

    @Test
    fun taskListsAreNotPlaybooks() {
        val tasks = """
            - name: Configure
              ansible.builtin.template:
                src: hosts.j2
                dest: /etc/hosts
                hosts: not-a-play-key
            - name: Second
              ansible.builtin.debug:
                msg: hi
        """.trimIndent()
        assertFalse(PlaybookProbe.looksLikePlaybook(tasks))
    }

    @Test
    fun mappingsAndEmptyFilesAreNotPlaybooks() {
        assertFalse(PlaybookProbe.looksLikePlaybook("---\nhosts: all\n"))
        assertFalse(PlaybookProbe.looksLikePlaybook("services:\n  web: {}\n"))
        assertFalse(PlaybookProbe.looksLikePlaybook(""))
        assertFalse(PlaybookProbe.looksLikePlaybook("# only a comment\n"))
    }
}
