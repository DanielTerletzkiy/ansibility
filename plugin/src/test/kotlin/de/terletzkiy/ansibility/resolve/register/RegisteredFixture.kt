package de.terletzkiy.ansibility.resolve.register

/**
 * A synthetic root for the typed register results tests (plan amendment FU, F1.12): role `keepalived` with the user's
 * example task (`command` + `until` on its own result, generic Ansible), `stat`, `uri`, a short module name in a loop,
 * a loop over `results`, `ansible.legacy.shell` and `command` registering one name, a module without documented
 * returns, an async job and a template rendered by a later task; a second root registers `keepalived_conf` with `uri`.
 */
object RegisteredFixture {
    const val TASKS: String = "site/roles/keepalived/tasks/main.yml"
    const val TEMPLATE: String = "site/roles/keepalived/templates/report.j2"
    const val HANDLERS: String = "site/roles/keepalived/handlers/main.yml"
    const val OTHER_TASKS: String = "other/roles/remote/tasks/main.yml"

    val TASKS_TEXT: String = """
        - name: Install keepalived
          ansible.builtin.apt:
            name: keepalived
        - name: Check the config
          ansible.builtin.stat:
            path: /etc/keepalived/keepalived.conf
          register: keepalived_conf
        - name: Wait for keepalived to assign the floating IP
          ansible.builtin.command: ip -4 -o addr show
          register: keepalived_floating_ip_check
          changed_when: false
          until: (keepalived_floating_ip ~ '/') in keepalived_floating_ip_check.stdout
        - name: Report
          ansible.builtin.debug:
            msg: "{{ keepalived_conf.stat.exists }} {{ keepalived_floating_ip_check.rc }} {{ keepalived_floating_ip_check.changed }}"
        - name: Ask the API
          ansible.builtin.uri:
            url: http://localhost/status
          register: keepalived_api
        - name: Use the API
          ansible.builtin.debug:
            msg: "{{ keepalived_api.status }} {{ keepalived_api.json }} {{ keepalived_api.nope }}"
        - name: Probe each address
          command: ping -c1 {{ item }}
          loop: [a, b]
          register: keepalived_pings
        - name: Loop over results
          ansible.builtin.debug:
            msg: "{{ item.stdout }} {{ keepalived_pings.results[0].stdout }}"
          loop: "{{ keepalived_pings.results }}"
        - name: Render
          ansible.builtin.template:
            src: report.j2
            dest: /tmp/report
        - name: Shell variant
          ansible.legacy.shell: echo a
          register: keepalived_either
          when: keepalived_mode == 'shell'
        - name: Command variant
          ansible.builtin.command: echo b
          register: keepalived_either
          when: keepalived_mode != 'shell'
        - name: Restart service
          service:
            name: keepalived
            state: restarted
          register: keepalived_service
        - name: Long job
          ansible.builtin.command: /usr/bin/long
          async: 600
          poll: 0
          register: keepalived_job
        - name: Placeholder
          ansible.builtin.debug:
            msg: "{{ PLACEHOLDER }}"
    """.trimIndent() + "\n"

    val TEMPLATE_TEXT: String = """
        {{ keepalived_conf.stat.exists }}
        {% for r in keepalived_pings.results %}{{ r.item }} {{ r.stdout }}{% endfor %}
    """.trimIndent() + "\n"

    val OTHER_TEXT: String = """
        - name: Other root
          ansible.builtin.uri:
            url: http://localhost/
          register: keepalived_conf
        - name: Use it
          ansible.builtin.debug:
            msg: "{{ PLACEHOLDER }}"
    """.trimIndent() + "\n"

    /** Creates the files through [create] (path, text). */
    fun create(create: (String, String) -> Unit) {
        create("site/ansible.cfg", "[defaults]\n")
        create("site/playbook.yml", "- hosts: all\n  roles:\n    - keepalived\n")
        create(TASKS, TASKS_TEXT)
        create(TEMPLATE, TEMPLATE_TEXT)
        create(HANDLERS, "- name: Reload\n  ansible.builtin.debug:\n    msg: \"{{ PLACEHOLDER }}\"\n")
        create("other/ansible.cfg", "[defaults]\n")
        create("other/playbook.yml", "- hosts: all\n  roles:\n    - remote\n")
        create(OTHER_TASKS, OTHER_TEXT)
    }

    /** The 1-based line of the first line of [text] that contains [marker] (after [after], when given). */
    fun lineOf(text: String, marker: String, after: String? = null): Int {
        val lines = text.lines()
        val from = after?.let { a -> lines.indexOfFirst { a in it }.also { check(it >= 0) { "no '$a'" } } } ?: 0
        val index = (from until lines.size).firstOrNull { marker in lines[it] } ?: error("no '$marker' in the fixture")
        return index + 1
    }
}
