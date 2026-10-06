package de.terletzkiy.ansibility.model.task

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.terletzkiy.ansibility.fixtures.RequiresInfraFixture
import de.terletzkiy.ansibility.model.role.ModelFixture
import de.terletzkiy.ansibility.semantics.CoreVersion
import de.terletzkiy.ansibility.semantics.yaml.Resolved
import de.terletzkiy.ansibility.semantics.yaml.YMap
import de.terletzkiy.ansibility.semantics.yaml.YScalar
import de.terletzkiy.ansibility.semantics.yaml.YSeq
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLKeyValue

/** [TaskFileModels] and [TaskModelBuilder] on fixture role files and on synthetic task lists and playbooks. */
@RequiresInfraFixture
class TaskFileModelTest : BasePlatformTestCase() {
    override fun getTestDataPath(): String = ModelFixture.testDataPath

    private fun textOf(file: YAMLFile, range: TextRange): String = file.text.substring(range.startOffset, range.endOffset)

    private fun parse(text: String, kind: TaskFileKind, name: String = "tasks.yml"): Pair<YAMLFile, TaskFileModel> {
        val file = myFixture.configureByText(name, text) as YAMLFile
        return file to TaskFileModels.of(file, kind)
    }

    private fun tasks(text: String, kind: TaskFileKind = TaskFileKind.TASKS): List<TaskNode> = parse(text, kind).second.tasks()

    // ------------------------------------------------------------------ fixture files

    fun testHaproxyConfigureTasks() {
        ModelFixture.copyInfra(myFixture, "golden/roles/haproxy")
        val file = ModelFixture.yaml(myFixture, "golden/roles/haproxy/tasks/configure.yml")
        val model = TaskFileModels.of(file)
        assertEquals("ROLE_TASKS files are task lists", TaskFileKind.TASKS, model.kind)
        val tasks = model.tasks()
        assertEquals(
            listOf(
                "ansible.builtin.template", "ansible.builtin.file", "ansible.builtin.apt",
                "ansible.builtin.template", "ansible.builtin.file", "ansible.builtin.template",
            ),
            tasks.map { it.module?.name },
        )
        val configure = tasks.first()
        assertEquals("Configure server", configure.name?.text)
        assertEquals(listOf("Reload haproxy"), configure.notify.map { it.text })
        assertEquals("Reload haproxy", textOf(file, configure.notify.single().range))
        assertEquals("ansible.builtin.template", textOf(file, configure.module!!.nameRange))
        assertEquals(listOf("src", "dest", "owner", "group", "mode"), configure.module.args.options.keys.toList())
        assertEquals(SrcKind.STATIC, configure.src?.kind)
        assertEquals("templates/haproxy.cfg.j2", configure.src?.text)
        assertEquals(emptyList<Any>(), configure.unknownKeys)
        assertEquals(listOf("Restart rsyslog"), tasks[3].notify.map { it.text })
        assertEquals("rsyslog", (tasks[2].module!!.args.option("name") as YScalar).text)
        assertNull("file is not a template/copy src", tasks[1].src)
        assertSame("cached per file", model, TaskFileModels.of(file))

        val element = ModelAnchors.element(file, configure.range)
        assertTrue("the task range is a PSI mapping: $element", element?.text?.startsWith("name: Configure server") == true)
    }

    fun testGrafanaNginxLoopsAndTemplatedSrc() {
        ModelFixture.copyInfra(myFixture, "golden/roles/grafana")
        val file = ModelFixture.yaml(myFixture, "golden/roles/grafana/tasks/nginx.yml")
        val tasks = TaskFileModels.of(file).tasks()
        assertEquals(5, tasks.size)

        val certs = tasks[2]
        assertEquals("loop", certs.loop?.keyword)
        assertEquals("{{ grafana_nginx_sites }}", (certs.loop?.value as YScalar).text)
        assertEquals("item", certs.loopVar)
        assertEquals(
            listOf("item.floating.ssl.client_cert_ca is defined", "item.floating.ssl.client_cert_ca_src is defined"),
            certs.conditions.map { it.value.text },
        )
        assertEquals(SrcKind.WHOLE_VAR, certs.src?.kind)
        assertTrue(certs.src!!.isCopy)
        assertFalse(certs.src.remoteSrc)

        val site = tasks[3]
        assertEquals("Add nginx configuration", site.name?.text)
        assertEquals(SrcKind.DYNAMIC_PREFIX, site.src?.kind)
        assertEquals("templates/nginx/", site.src?.staticPrefix)
        assertEquals("\"templates/nginx/{{ item.template }}\"", textOf(file, TaskModelBuilder.rangeOf(site.src!!.value)))
        assertEquals("grafana_nginx_config_result", site.register?.text)
        assertEquals("{{ grafana_nginx_sites }}", (site.loop?.value as YScalar).text)
        assertNull(tasks[0].loop)
        assertNull(tasks[0].loopVar)
    }

    fun testWithDependenciesIsAJenkinsPluginOption() {
        ModelFixture.copyInfra(myFixture, "golden/roles/jenkins-controller")
        val file = ModelFixture.yaml(myFixture, "golden/roles/jenkins-controller/tasks/jenkins.yml")
        val document = PsiDocumentManager.getInstance(project).getDocument(file)!!
        val line221 = document.getLineStartOffset(220)
        assertTrue(document.getText(TextRange(line221, document.getLineEndOffset(220))).contains("with_dependencies: false"))
        val task = TaskFileModels.of(file).itemAt(line221) as TaskNode
        assertEquals("Install Jenkins plugins using password", task.name?.text)
        assertEquals("community.general.jenkins_plugin", task.module?.canonical)
        assertEquals("false", (task.module!!.args.option("with_dependencies") as YScalar).text)
        assertEquals("the loop is `loop`, not with_dependencies", "loop", task.loop?.keyword)
        assertNull(task.loop?.lookup)
        assertEquals(emptyList<Any>(), task.unknownKeys)
        assertEquals(listOf("until"), task.expressions.map { it.key })
        assertEquals("jenkins_plugin_result", task.register?.text)
    }

    // ------------------------------------------------------------------ module keys, args and loops

    fun testWithKeysAtTaskLevel() {
        val tasks = tasks(
            """
            - name: Real loop
              ansible.builtin.debug:
                msg: "{{ item }}"
              with_items: [a, b]
            - name: Misplaced option
              community.general.jenkins_plugin:
                name: git
              with_dependencies: false
            - name: Dict loop
              ansible.builtin.debug:
                var: item.key
              with_dict: "{{ users }}"
              loop_control:
                loop_var: user
                index_var: idx
                label: "{{ user.key }}"
                extended: true
            """.trimIndent(),
        )
        assertEquals("with_items", tasks[0].loop?.keyword)
        assertEquals("items", tasks[0].loop?.lookup)
        assertNull(tasks[1].loop)
        assertEquals(listOf("with_dependencies"), tasks[1].unknownKeys.map { it.key.text })
        assertEquals("community.general.jenkins_plugin", tasks[1].module?.name)
        assertEquals("dict", tasks[2].loop?.lookup)
        val control = tasks[2].loopControl!!
        assertEquals("user", control.loopVar?.text)
        assertEquals("idx", control.indexVar?.text)
        assertEquals("{{ user.key }}", (control.label as YScalar).text)
        assertEquals("true", (control.extended as YScalar).text)
        assertEquals("user", tasks[2].loopVar)
        assertEquals(listOf("var"), tasks[2].expressions.map { it.key })
    }

    fun testFreeFormArgsAndTheArgsKeyword() {
        val (file, model) = parse(
            """
            - name: Command string
              ansible.builtin.command: /usr/bin/make install chdir=/src
              args:
                creates: /usr/bin/app
                chdir: /ignored
            - name: Shell with executable
              ansible.builtin.shell: set -o pipefail && ls | wc -l
              args:
                executable: /bin/bash
            - name: Old style
              ansible.builtin.copy: src=files/a.conf dest=/etc/a.conf remote_src=yes
            - name: Ping
              ansible.builtin.ping:
            """.trimIndent(),
            TaskFileKind.TASKS,
        )
        val (command, shell, copy, ping) = model.tasks()
        val commandArgs = command.module!!.args
        assertEquals("/usr/bin/make install", commandArgs.rawParams)
        assertEquals("the inline option beats args:", "/src", (commandArgs.option("chdir") as YScalar).text)
        assertEquals("/usr/bin/app", (commandArgs.option("creates") as YScalar).text)
        assertEquals("chdir", textOf(file, TaskModelBuilder.rangeOf(commandArgs.options.getValue("chdir").key)))
        assertEquals("/src", textOf(file, TaskModelBuilder.rangeOf(commandArgs.options.getValue("chdir").value)))
        assertNotNull(commandArgs.argsKeyword)
        assertEquals("set -o pipefail && ls | wc -l", shell.module!!.args.rawParams)
        assertEquals("/bin/bash", (shell.module.args.option("executable") as YScalar).text)
        val copyArgs = copy.module!!.args
        assertNull(copyArgs.rawParams)
        assertEquals(listOf("src", "dest", "remote_src"), copyArgs.options.keys.toList())
        assertEquals("files/a.conf", textOf(file, TaskModelBuilder.rangeOf(copyArgs.options.getValue("src").value)))
        assertEquals("k=v values are strings", Resolved.Str("yes"), (copyArgs.option("remote_src") as YScalar).resolved)
        assertEquals(SrcKind.STATIC, copy.src?.kind)
        assertTrue(copy.src!!.remoteSrc)
        assertEquals(emptyMap<String, Any>(), ping.module!!.args.options)
        assertEquals("ansible.builtin.ping", ping.module.name)
    }

    fun testActionAndLocalActionForms() {
        val (file, model) = parse(
            """
            - name: Action string
              action: ansible.builtin.copy src=a dest=/b
            - name: Local action dict
              local_action:
                module: ansible.builtin.command
                cmd: whoami
            """.trimIndent(),
            TaskFileKind.TASKS,
        )
        val (string, dict) = model.tasks()
        assertEquals(ModuleForm.ACTION, string.module?.form)
        assertEquals("ansible.builtin.copy", string.module?.name)
        assertEquals("ansible.builtin.copy", textOf(file, string.module!!.nameRange))
        assertEquals(listOf("src", "dest"), string.module.args.options.keys.toList())
        assertEquals("/b", textOf(file, TaskModelBuilder.rangeOf(string.module.args.options.getValue("dest").value)))
        assertEquals(ModuleForm.LOCAL_ACTION, dict.module?.form)
        assertEquals("ansible.builtin.command", dict.module?.canonical)
        assertEquals(listOf("cmd"), dict.module!!.args.options.keys.toList())
    }

    fun testModuleKeySelectionAndUnknownKeys() {
        val tasks = tasks(
            """
            - name: Misplaced keyword
              hosts: all
              ansible.builtin.systemd:
                name: nginx
            - name: Custom module
              acme.custom.thing:
                a: 1
            - name: No module
              when: true
            """.trimIndent(),
        )
        assertEquals("ansible.builtin.systemd", tasks[0].module?.name)
        assertEquals("ansible.builtin.systemd_service", tasks[0].module?.canonical)
        assertEquals(listOf("hosts"), tasks[0].unknownKeys.map { it.key.text })
        assertEquals("acme.custom.thing", tasks[1].module?.canonical)
        assertNull(tasks[2].module)
        assertEquals(listOf("true"), tasks[2].conditions.map { it.value.text })
    }

    fun testKeywordsNotifyTagsVarsAndExpressions() {
        val tasks = tasks(
            """
            - name: Everything
              ansible.builtin.assert:
                that:
                  - a is defined
                  - b | bool
              notify: [One, "role : Two"]
              tags: setup, config
              vars:
                local_a: 1
                local_b: "{{ x }}"
              when: x | bool
              changed_when: false
              failed_when:
                - rc != 0
              until: result is success
              register: result
              delegate_to: localhost
              become: true
            """.trimIndent(),
        )
        val task = tasks.single()
        assertEquals(listOf("One", "role : Two"), task.notify.map { it.text })
        assertEquals(listOf("setup", "config"), task.tags.map { it.text })
        assertEquals(listOf("local_a", "local_b"), task.vars.map { it.text })
        assertEquals(
            listOf("that" to "a is defined", "that" to "b | bool", "when" to "x | bool", "changed_when" to "false", "failed_when" to "rc != 0", "until" to "result is success"),
            task.expressions.map { it.key to it.value.text },
        )
        assertEquals("localhost", (task.delegateTo?.value as YScalar).text)
        assertEquals("true", (task.become?.value as YScalar).text)
        assertEquals("result", task.register?.text)
        assertTrue(task.keywords.keys.containsAll(listOf("name", "notify", "tags", "vars", "when", "register")))
    }

    fun testIncludesAndRoleIncludes() {
        val tasks = tasks(
            """
            - name: Free-form include
              ansible.builtin.include_tasks: setup.yml
            - name: Dict import
              ansible.builtin.import_tasks:
                file: other.yml
            - name: Include with apply
              include_tasks:
                file: "{{ item }}.yml"
                apply:
                  tags: [x]
            - name: Include role
              ansible.builtin.include_role:
                name: /ansible/roles/grafana
                tasks_from: alerting.yml
                handlers_from: molecule.yml
                vars_from: extra
                defaults_from: more
                public: true
              vars:
                grafana_user: root
            - name: Import role
              ansible.builtin.import_role:
                name: keycloak
            """.trimIndent(),
        )
        assertEquals(IncludeKind.INCLUDE, tasks[0].taskInclude?.kind)
        assertEquals("setup.yml", tasks[0].taskInclude?.file?.text)
        assertEquals(IncludeKind.IMPORT, tasks[1].taskInclude?.kind)
        assertEquals("other.yml", tasks[1].taskInclude?.file?.text)
        assertEquals("{{ item }}.yml", tasks[2].taskInclude?.file?.text)
        assertNotNull(tasks[2].taskInclude?.apply)
        val include = tasks[3].roleInclude!!
        assertEquals(IncludeKind.INCLUDE, include.kind)
        assertEquals("/ansible/roles/grafana", include.name?.text)
        assertEquals("alerting.yml", include.tasksFrom?.text)
        assertEquals("molecule.yml", include.handlersFrom?.text)
        assertEquals("extra", include.varsFrom?.text)
        assertEquals("more", include.defaultsFrom?.text)
        assertEquals("true", (include.public as YScalar).text)
        assertEquals(listOf("grafana_user"), (include.vars?.value as YMap).keys)
        assertEquals(IncludeKind.IMPORT, tasks[4].roleInclude?.kind)
        assertEquals("keycloak", tasks[4].roleInclude?.name?.text)
        assertNull(tasks[4].roleInclude?.tasksFrom)
    }

    fun testBlocksRescueAndAlways() {
        val (_, model) = parse(
            """
            - name: Outer
              when: enabled
              vars:
                block_var: 1
              block:
                - name: Inner
                  ansible.builtin.debug:
                    msg: hi
                - block:
                    - name: Nested
                      ansible.builtin.ping:
              rescue:
                - name: Rescue
                  ansible.builtin.debug:
                    msg: failed
              always:
                - name: Always
                  ansible.builtin.debug:
                    msg: done
            """.trimIndent(),
            TaskFileKind.TASKS,
        )
        val block = model.items.single() as BlockNode
        assertEquals("Outer", block.name?.text)
        assertEquals(listOf("enabled"), block.conditions.map { it.value.text })
        assertEquals(listOf("block_var"), block.vars.map { it.text })
        assertEquals(2, block.block.size)
        assertTrue(block.block[1] is BlockNode)
        assertEquals(listOf("Inner", "Nested", "Rescue", "Always"), model.tasks().map { it.name?.text })
        val nestedOffset = model.tasks()[1].range.startOffset
        assertEquals("Nested", (model.itemAt(nestedOffset) as TaskNode).name?.text)
    }

    fun testHandlersListen() {
        val (_, model) = parse(
            """
            - name: Reload systemd
              listen: Reload systemd
              ansible.builtin.systemd:
                daemon_reload: true
            - name: Restart web
              listen:
                - web changed
                - config changed
              ansible.builtin.systemd:
                name: web
                state: restarted
            """.trimIndent(),
            TaskFileKind.HANDLERS,
        )
        val handlers = model.tasks()
        assertTrue(handlers.all { it.isHandler })
        assertEquals(listOf("Reload systemd"), handlers[0].listen.map { it.text })
        assertEquals(listOf("web changed", "config changed"), handlers[1].listen.map { it.text })
        assertEquals(emptyList<Any>(), handlers[1].unknownKeys)
        val asTasks = TaskFileModels.of(myFixture.file as YAMLFile, TaskFileKind.TASKS).tasks()
        assertEquals("outside a handler list, listen is not a keyword", listOf("listen"), asTasks[1].unknownKeys.map { it.key.text })
    }

    // ------------------------------------------------------------------ playbooks

    fun testPlaybookStructure() {
        val (file, model) = parse(
            """
            ---
            - name: Web
              hosts: [web, db]
              vars:
                a: 1
              vars_files:
                - vars/common.yml
                - ["vars/{{ env }}.yml", vars/default.yml]
              pre_tasks:
                - name: Pre
                  ansible.builtin.ping:
              roles:
                - common
                - { role: nginx, tags: ['nginx'] }
                - role: /ansible/roles/app
                  app_port: 8080
                  when: app_enabled
              tasks:
                - name: Task
                  ansible.builtin.include_role:
                    name: extra
              post_tasks:
                - name: Post
                  ansible.builtin.ping:
              handlers:
                - name: Handler
                  listen: topic
                  ansible.builtin.ping:
            - ansible.builtin.import_playbook: playbook-other.yml
              when: run_other
            - name: Second
              hosts: database:replisync
              gather_facts: false
            """.trimIndent(),
            TaskFileKind.PLAYBOOK,
            name = "playbook-site.yml",
        )
        assertEquals(2, model.plays.size)
        val web = model.plays[0]
        assertEquals(0, web.index)
        assertEquals(0, web.itemIndex)
        assertEquals("web,db", web.hosts?.pattern)
        assertEquals(listOf("a"), web.vars.map { it.text })
        assertEquals(listOf("vars/common.yml", "vars/{{ env }}.yml", "vars/default.yml"), web.varsFiles.map { it.text })
        assertEquals(listOf("common", "nginx", "/ansible/roles/app"), web.roles.map { it.name?.text })
        assertEquals(listOf("nginx"), web.roles[1].tags.map { it.text })
        assertEquals(listOf("app_port"), web.roles[2].params.map { it.key.text })
        assertEquals(listOf("app_enabled"), web.roles[2].expressions.map { it.value.text })
        assertEquals("nginx", textOf(file, web.roles[1].name!!.range))
        assertEquals(listOf("Pre"), web.preTasks.map { it.name?.text })
        assertEquals("extra", (web.tasks.single() as TaskNode).roleInclude?.name?.text)
        assertEquals(listOf("Post"), web.postTasks.map { it.name?.text })
        assertTrue(web.handlers.single().isHandler)
        assertEquals(listOf("topic"), (web.handlers.single() as TaskNode).listen.map { it.text })
        assertEquals(listOf("Pre", "Task", "Post", "Handler"), model.tasks().map { it.name?.text })

        val import = model.imports.single()
        assertEquals(1, import.itemIndex)
        assertEquals("playbook-other.yml", import.path?.text)
        assertEquals("ansible.builtin.import_playbook", import.key.text)
        assertEquals(listOf("run_other"), import.expressions.map { it.value.text })

        val second = model.plays[1]
        assertEquals(1, second.index)
        assertEquals(2, second.itemIndex)
        assertEquals("database:replisync", second.hosts?.pattern)
        assertEquals(emptyList<Any>(), second.unknownKeys)
        assertSame(web, model.playAt(web.range.startOffset + 3))
    }

    fun testAutoKindFromContent() {
        val playbook = myFixture.configureByText("site.yml", "- import_playbook: other.yml\n") as YAMLFile
        assertEquals(TaskFileKind.PLAYBOOK, TaskFileModels.of(playbook).kind)
        val tasks = myFixture.configureByText("list.yml", "- name: x\n  ansible.builtin.ping:\n") as YAMLFile
        assertEquals(TaskFileKind.TASKS, TaskFileModels.of(tasks).kind)
        val vars = myFixture.configureByText("vars.yml", "a: 1\n") as YAMLFile
        val model = TaskFileModels.of(vars)
        assertFalse(model.isSequence)
        assertEquals(emptyList<Any>(), model.tasks())
    }

    fun testModelFollowsEdits() {
        val (file, model) = parse("- name: One\n  ansible.builtin.ping:\n", TaskFileKind.TASKS)
        assertEquals(1, model.tasks().size)
        WriteCommandAction.runWriteCommandAction(project) {
            val document = PsiDocumentManager.getInstance(project).getDocument(file)!!
            document.insertString(document.textLength, "- name: Two\n  ansible.builtin.ping:\n")
            PsiDocumentManager.getInstance(project).commitDocument(document)
        }
        val updated = TaskFileModels.of(file, TaskFileKind.TASKS)
        assertEquals(listOf("One", "Two"), updated.tasks().map { it.name?.text })
        val keyValue = ModelAnchors.element(file, updated.tasks()[1].module!!.nameRange)
        assertTrue("the module key maps back to PSI: $keyValue", keyValue?.text == "ansible.builtin.ping" || keyValue is YAMLKeyValue)
    }

    fun testSyntaxFollowsTheRootsTargetVersion() {
        val play = "---\n- name: Play\n  hosts: all\n  validate_argspec: main\n  tasks: []\n"
        myFixture.addFileToProject("old/ansible.cfg", "[defaults]\n")
        myFixture.addFileToProject("old/docker/ansible-playbook/Dockerfile", "RUN pip install ansible-core==2.18.8\n")
        myFixture.addFileToProject("old/playbook-a.yml", play)
        myFixture.addFileToProject("new/ansible.cfg", "[defaults]\n")
        myFixture.addFileToProject("new/docker/ansible-playbook/Dockerfile", "RUN pip install ansible-core==2.21.4\n")
        myFixture.addFileToProject("new/playbook-a.yml", play)
        ModelFixture.rescan(project)
        val old = TaskFileModels.of(ModelFixture.yaml(myFixture, "old/playbook-a.yml")).plays.single()
        assertEquals("2.18 has no validate_argspec play keyword", listOf("validate_argspec"), old.unknownKeys.map { it.key.text })
        val new = TaskFileModels.of(ModelFixture.yaml(myFixture, "new/playbook-a.yml")).plays.single()
        assertEquals(emptyList<Any>(), new.unknownKeys)
        assertTrue("validate_argspec" in new.keywords)

        val service = TaskSyntaxService.getInstance()
        assertEquals("2.18.8", service.forVersion(null).coreVersion)
        assertEquals("2.18.8", service.forVersion(CoreVersion(2, 17)).coreVersion)
        assertSame(service.forVersion(null), service.forVersion(CoreVersion.PINNED))
        assertNotSame(service.forVersion(null), service.forVersion(CoreVersion(2, 19)))
        assertTrue(CoreVersion.parse(service.forVersion(CoreVersion(3, 0)).coreVersion)!! > CoreVersion.PINNED)
    }

    fun testRangesSurviveMergeKeys() {
        val (file, model) = parse(
            """
            - name: Anchored
              ansible.builtin.template: &tpl
                src: a.j2
                dest: /a
            - name: Merged
              ansible.builtin.template:
                <<: *tpl
                dest: /b
            """.trimIndent(),
            TaskFileKind.TASKS,
        )
        val merged = model.tasks()[1]
        assertEquals("a.j2", merged.src?.text)
        assertEquals("the merged src points at its definition", "a.j2", textOf(file, TaskModelBuilder.rangeOf(merged.src!!.value)))
        assertEquals("/b", (merged.module!!.args.option("dest") as YScalar).text)
        assertTrue(merged.module.args.value is YMap)
        assertFalse(merged.module.args.value is YSeq)
    }
}
