package de.terletzkiy.ansibility.inspections.keywords

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.vfs.VfsUtil
import de.terletzkiy.ansibility.context.TargetVersionDetector
import de.terletzkiy.ansibility.inspections.modules.ModuleChecksTestCase
import de.terletzkiy.ansibility.semantics.CoreVersion

/**
 * ANS-K001 and ANS-K002 through the highlighting pass (plan F5.10; M4 acceptance 5 and 6): typing a rejected keyword
 * value or a typo into a real task file and undoing it, scalar list keywords staying clean, play, block, include and
 * `loop_control` keys, the target-version keyword sets and the rename fix.
 */
class KeywordInspectionTest : ModuleChecksTestCase() {

    // ------------------------------------------------------------------------------------------------ M4 acceptance 5

    fun testTypingRetriesAbcIsRedK001AndUndoingIsClean() {
        copyInfra("golden/roles/haproxy")
        myFixture.configureFromTempProjectFile(CONFIGURE)
        type(9, "notify: Reload haproxy", "notify: Reload haproxy\n  retries: abc")
        val info = infos().single()
        assertEquals(HighlightSeverity.ERROR, info.severity)
        assertEquals("AnsibleKeywordValue", info.inspectionToolId)
        assertEquals("abc", text(info))
        assertEquals(
            "ansible-core 2.18.8 rejects `retries` here: the field 'retries' has an invalid value ('abc'), and could not be converted to int.",
            info.description,
        )
        type(10, "retries: abc", "retries: 3")
        assertEmpty(infos())
    }

    fun testTypingBecomeUsrIsRedK002AndTheFixRenamesIt() {
        copyInfra("golden/roles/haproxy")
        myFixture.configureFromTempProjectFile(CONFIGURE)
        type(17, "group: root", "group: root\n  become_usr: root")
        val info = infos().single()
        assertEquals(HighlightSeverity.ERROR, info.severity)
        assertEquals("AnsibleUnknownKeyword", info.inspectionToolId)
        assertEquals("become_usr", text(info))
        assertEquals(
            "Task has two action keys: `ansible.builtin.file` and `become_usr` — did you mean `become_user`? ansible-core 2.18.8 " +
                "fails to load it (conflicting action statements: ansible.builtin.file, become_usr)",
            info.description,
        )
        myFixture.editor.caretModel.moveToOffset(info.startOffset)
        applyFix("Rename to 'become_user'")
        assertEquals("  become_user: root", lineText(18))
        assertEmpty(infos())
    }

    // ------------------------------------------------------------------------------------------------ M4 acceptance 6

    fun testScalarListKeywordsStayClean() {
        copyInfra("golden/roles/haproxy", "golden/playbooks")
        val path = "golden/playbooks/playbook-scalars.yml"
        createFile(
            path,
            """
            - name: Scalars
              hosts: haproxy
              serial: 1
              tags: haproxy
              roles:
                - { role: haproxy, tags: ['haproxy'] }
              tasks:
                - name: Configure
                  ansible.builtin.template:
                    src: haproxy.cfg.j2
                    dest: /etc/haproxy/haproxy.cfg
                    mode: "0644"
                  notify: Reload haproxy
                  tags: haproxy
                  when: haproxy_enabled
                  changed_when: false
                  retries: "{{ haproxy_retries }}"
                  loop_control:
                    pause: 2
                    label: "{{ item }}"
                  loop: [1, 2]
              handlers:
                - name: Reload haproxy
                  ansible.builtin.systemd_service:
                    name: haproxy
                    state: reloaded
                  listen: reload all
            """.trimIndent() + "\n",
        )
        assertEmpty(highlights(path))
    }

    fun testRealFilesStayClean() {
        copyInfra("golden/roles/haproxy", "golden/roles/jenkins-controller", "golden/playbooks")
        for (path in listOf(CONFIGURE, "golden/roles/jenkins-controller/tasks/jenkins.yml", "golden/playbooks/playbook-setup-jenkins.yml")) {
            assertEmpty(path, highlights(path))
        }
    }

    // ------------------------------------------------------------------------------------------------ K001 shapes

    fun testRejectedPlayAndLoopControlValues() {
        copyInfra("golden/roles/haproxy", "golden/playbooks")
        val path = "golden/playbooks/playbook-broken.yml"
        createFile(
            path,
            """
            - name: Broken
              hosts: all
              serial: [a]
              become: maybe
              tasks:
                - name: Loop
                  ansible.builtin.debug:
                    msg: hi
                  loop: [1]
                  loop_control:
                    pause: x
                    paws: 1
                - name: Tags
                  ansible.builtin.debug:
                    msg: hi
                  tags: 5
                  register: "bad-name"
            """.trimIndent() + "\n",
        )
        assertEquals(
            listOf(
                "3: ERROR AnsibleKeywordValue: ansible-core 2.18.8 crashes on `serial` instead of reporting an error: ValueError: " +
                    "invalid literal for int() with base 10: 'a'",
                "4: ERROR AnsibleKeywordValue: ansible-core 2.18.8 rejects `become` here: the field 'become' has an invalid value " +
                    "('maybe'), and could not be converted to bool.",
                "11: ERROR AnsibleKeywordValue: ansible-core 2.18.8 rejects `pause` here: the field 'pause' has an invalid value ('x'), " +
                    "and could not be converted to float.",
                "12: ERROR AnsibleUnknownKeyword: Unknown loop_control keyword `paws` — did you mean `pause`? ansible-core 2.18.8 fails " +
                    "to load it ('paws' is not a valid attribute for a LoopControl)",
                "16: ERROR AnsibleKeywordValue: ansible-core 2.18.8 rejects `tags` here: tags must be specified as a list",
                "17: ERROR AnsibleKeywordValue: ansible-core 2.18.8 rejects `register` here: Invalid variable name in 'register' " +
                    "specified: 'bad-name'",
            ),
            highlights(path),
        )
    }

    // ------------------------------------------------------------------------------------------------ K002 shapes

    fun testUnknownKeysOfPlaysBlocksImportsAndIncludes() {
        copyInfra("golden/roles/haproxy", "golden/playbooks")
        val path = "golden/playbooks/playbook-keys.yml"
        createFile(
            path,
            """
            - name: Play
              hosts: all
              user: root
              gather_fact: false
              roles:
                - role: haproxy
                  haproxy_extra: 1
              tasks:
                - name: Block
                  block:
                    - ansible.builtin.debug:
                        msg: hi
                  retries: 3
                - name: Include
                  ansible.builtin.include_tasks: configure.yml
                  become: true
                  tags: [configure]
                - name: A lookup the bundled docs do not know may be a project plugin
                  ansible.builtin.debug:
                    msg: "{{ item }}"
                  with_nolookup: [1]
            - ansible.builtin.import_playbook: playbook-setup-jenkins.yml
              tags: jenkins
              hosts: all
            """.trimIndent() + "\n",
        )
        assertEquals(
            listOf(
                "4: ERROR AnsibleUnknownKeyword: Unknown play keyword `gather_fact` — did you mean `gather_facts`? ansible-core " +
                    "2.18.8 fails to load it ('gather_fact' is not a valid attribute for a Play)",
                "13: ERROR AnsibleUnknownKeyword: Unknown block keyword `retries`: ansible-core 2.18.8 fails to load it ('retries' " +
                    "is not a valid attribute for a Block)",
                "16: ERROR AnsibleUnknownKeyword: `become` is not accepted by the dynamic include `ansible.builtin.include_tasks` " +
                    "(use `apply:` or a block); ansible-core 2.18.8 fails to load it ('become' is not a valid attribute for a TaskInclude)",
                "24: ERROR AnsibleUnknownKeyword: Unknown import_playbook keyword `hosts`: ansible-core 2.18.8 fails to load it " +
                    "('hosts' is not a valid attribute for a PlaybookInclude)",
            ),
            highlights(path),
        )
    }

    fun testInvalidTaskAttributeFailedOffOnlyWarns() {
        copyInfra("repos/falcon")
        val cfg = myFixture.findFileInTempDir("repos/falcon/ansible/ansible.cfg")!!
        WriteAction.runAndWait<Throwable> { VfsUtil.saveText(cfg, VfsUtil.loadText(cfg).replace("[defaults]\n", "[defaults]\ninvalid_task_attribute_failed = False\n")) }
        refreshRoots()
        val path = "repos/falcon/ansible/roles/jenkins-agent-docker/tasks/extra.yml"
        createFile(
            path,
            """
            - ansible.builtin.include_tasks: main.yml
              become: true
            - ansible.builtin.file:
                path: /tmp/x
              becom: true
            """.trimIndent() + "\n",
        )
        val lines = highlights(path)
        assertEquals(2, lines.size)
        assertTrue(lines[0], lines[0].startsWith("2: WARNING AnsibleUnknownKeyword: `become` is not accepted by the dynamic include"))
        assertTrue("a second action key fails whatever the setting: ${lines[1]}", lines[1].startsWith("5: ERROR AnsibleUnknownKeyword: Task has two action keys"))
    }

    fun testKeywordSetsFollowTheTarget() {
        copyInfra("golden/roles/haproxy", "golden/playbooks")
        val path = "golden/playbooks/playbook-argspec.yml"
        createFile(path, "- hosts: all\n  validate_argspec: main\n  tasks: []\n")
        assertEquals(
            listOf(
                "2: ERROR AnsibleUnknownKeyword: Unknown play keyword `validate_argspec`: ansible-core 2.18.8 fails to load it " +
                    "('validate_argspec' is not a valid attribute for a Play)",
            ),
            highlights(path),
        )
        TargetVersionDetector.getInstance(project).overrideFor = { CoreVersion(2, 21, 4) }
        assertEmpty(highlights(path))
    }

    private companion object {
        const val CONFIGURE = "golden/roles/haproxy/tasks/configure.yml"
    }
}
