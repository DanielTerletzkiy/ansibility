package de.terletzkiy.ansibility.vars.hints

import com.intellij.codeInsight.hints.declarative.DeclarativeInlayHintsSettings
import com.intellij.codeInsight.hints.declarative.impl.util.DeclarativeHintsDumpUtil
import com.intellij.codeInsight.hints.declarative.impl.views.TextInlayPresentationEntry
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.PlatformTestUtil
import de.terletzkiy.ansibility.api.AnsibleContextService
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.context.host.HostModeRestarts
import de.terletzkiy.ansibility.settings.EnvironmentChoice
import de.terletzkiy.ansibility.settings.RootContext
import de.terletzkiy.ansibility.settings.SettingsTestSupport
import de.terletzkiy.ansibility.vars.VarsTestCase
import de.terletzkiy.ansibility.vars.hints.VarSourcesFixture.DEFAULTS
import de.terletzkiy.ansibility.vars.hints.VarSourcesFixture.PROD_ALL
import de.terletzkiy.ansibility.vars.hints.VarSourcesFixture.TASKS
import de.terletzkiy.ansibility.vars.hints.VarSourcesFixture.TEMPLATE

/**
 * The R31 fixes' own cases ([VarSourcesFixture]): per environment what its hosts get, grouped and cut after about 45
 * characters (decisions 1, 5); one hint per name in a scalar or template line; values that agree need the option; task
 * vars, block vars and `set_fact` named where they win, no hint where the value depends on the path (decision 3); vars
 * and inventory files only with their option, Molecule files never (decision 4); accessor values (decision 6); a context
 * switch recomputes the visible editor once and a background editor when it is shown (decision 7); the click finds its
 * reference after an edit (decision 9).
 */
class VarSourceHintsFixesTest : VarsTestCase() {
    override fun setUp() {
        super.setUp()
        VarSourcesFixture.create { path, text -> myFixture.tempDirFixture.createFile(path, text) }
        refreshRoots()
    }

    override fun tearDown() {
        try {
            DeclarativeInlayHintsSettings.getInstance().setProviderEnabled(VarSourceHints.PROVIDER_ID, true)
            SettingsTestSupport.resetAll(project)
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    private val root: AnsibleRoot get() = runReadActionBlocking { AnsibleWorkspace.getInstance(project).contextOf(vf(TASKS))!!.root }

    private fun select(context: RootContext) = AnsibleContextService.getInstance(project).setSelection(root, context)

    /** Writes [text] to [path] through its document (a document already loaded would otherwise keep the old text). */
    private fun write(path: String, text: String) {
        val existing = myFixture.findFileInTempDir(path) ?: return run { myFixture.tempDirFixture.createFile(path, text) }
        val document = runReadActionBlocking { FileDocumentManager.getInstance().getDocument(existing)!! }
        WriteCommandAction.runWriteCommandAction(project) {
            document.setText(text)
            PsiDocumentManager.getInstance(project).commitDocument(document)
            FileDocumentManager.getInstance().saveDocument(document)
        }
    }

    private fun hints(path: String, same: Boolean = false, varsFiles: Boolean = false): List<VarSourceHints.Hint> =
        inBackgroundReadAction { VarSourceHints.collect(project, psi(path), same, varsFiles) }

    /** Line [line] of [path] with every hint inserted as `‹text›` at its offset. */
    private fun line(path: String, line: Int, same: Boolean = false, varsFiles: Boolean = false): String {
        val text = runReadActionBlocking { psi(path).viewProvider.contents.toString() }
        val start = StringUtil.lineColToOffset(text, line - 1, 0)
        val end = text.indexOf('\n', start).let { if (it < 0) text.length else it }
        val out = StringBuilder()
        var at = start
        for (hint in hints(path, same, varsFiles).filter { it.offset in start..end }.sortedBy { it.offset }) {
            out.append(text, at, hint.offset).append('‹').append(hint.text).append('›')
            at = hint.offset
        }
        return out.append(text, at, end).toString().trim()
    }

    private fun task(name: String, body: String) = "---\n- name: ${name.replaceFirstChar { it.uppercase() }}\n$body"

    // ------------------------------------------------------------------------------------------------ decisions 1 and 5

    fun testEachEnvironmentNamesWhatItsHostsGetAndTheTooltipHasEveryValue() {
        val hint = hints(TASKS).first { it.name == "app_version" }
        assertEquals("default 1.2.0 · dev, stage, test 2.0.0 · +1", hint.text)
        assertEquals("[default 1.2.0, dev, stage, test 2.0.0, prod 1.4.1 / 1.4.2-hotfix]", hint.entries.toString())
        assertTrue(hint.tooltip, "prod (prod-web1, prod-web3): group_vars web · prod · environments/prod/group_vars/web.yml = 1.4.1" in hint.tooltip)
        assertTrue(hint.tooltip, "prod (prod-web2): host_vars prod-web2 · prod · environments/prod/host_vars/prod-web2.yml = 1.4.2-hotfix" in hint.tooltip)
        assertTrue(hint.tooltip, "dev, stage, test: playbook group_vars all · group_vars/all.yml = 2.0.0" in hint.tooltip)
        assertFalse("a value no host gets is no row: ${hint.tooltip}", "1.4.0" in hint.tooltip || "1.3.5" in hint.tooltip)
        assertTrue(hint.text.length <= VarSourceHints.MAX_VISIBLE + 5)
    }

    fun testHostsThatDisagreeShowTwoValuesThenACount() {
        write("s/environments/prod/host_vars/prod-web1.yml", "---\napp_port: 1\n")
        write("s/environments/prod/host_vars/prod-web3.yml", "---\napp_port: 3\n")
        refreshRoots()
        assertEquals("default 80 · prod 1 / 3 +1", hints(TASKS).first { it.name == "app_port" }.text)
    }

    fun testOneHintPerNameInAScalarOrATemplateLine() {
        val tasks = "s/roles/app/tasks/twice.yml"
        write(tasks, task("twice", "  ansible.builtin.debug:\n    msg: \"{{ app_port }}:{{ app_port }} {{ app_version }}\"\n  when: app_port > 0 and app_port < 9\n"))
        write("s/roles/app/templates/twice.j2", "a={{ app_port }} b={{ app_port }}\nc={{ app_port }}\n")
        refreshRoots()
        assertEquals("msg: \"{{ app_port‹default 80 · prod 8443› }}:{{ app_port }} {{ app_version‹default 1.2.0 · dev, stage, test 2.0.0 · +1› }}\"", line(tasks, 4))
        assertEquals("when: app_port‹default 80 · prod 8443› > 0 and app_port < 9", line(tasks, 5))
        assertEquals("a={{ app_port‹default 80 · prod 8443› }} b={{ app_port }}", line("s/roles/app/templates/twice.j2", 1))
        assertEquals("c={{ app_port‹default 80 · prod 8443› }}", line("s/roles/app/templates/twice.j2", 2))
    }

    fun testValuesThatAgreeGetAHintOnlyWithTheOption() {
        write(DEFAULTS, VarSourcesFixture.FILES.getValue(DEFAULTS) + "app_same: 7\n")
        write("s/environments/prod/group_vars/web.yml", "---\napp_version: 1.4.1\napp_same: 7\n")
        val tasks = "s/roles/app/tasks/same.yml"
        write(tasks, task("same", "  ansible.builtin.debug:\n    msg: \"{{ app_same }}\"\n"))
        refreshRoots()
        assertEquals("msg: \"{{ app_same }}\"", line(tasks, 4))
        assertEquals("msg: \"{{ app_same‹default 7› }}\"", line(tasks, 4, same = true))
    }

    fun testASelectedEnvironmentWhoseHostsAgreeOnAValueFromSeveralFiles() {
        write("s/environments/prod/host_vars/prod-web1.yml", "---\napp_port: 8443\n")
        refreshRoots()
        select(RootContext(environment = EnvironmentChoice.Named("prod")))
        assertEquals("→ 8443 (prod) · default 80", hints(TASKS).first { it.name == "app_port" }.text)
    }

    // ------------------------------------------------------------------------------------------------ decision 3

    fun testTaskVarsBlockVarsAndSetFactAreNamedWhereTheyWin() {
        val tasks = "s/roles/app/tasks/local.yml"
        write(
            tasks,
            "---\n- name: Task vars\n  ansible.builtin.debug:\n    msg: \"{{ app_port }}\"\n  vars:\n    app_port: 1\n" +
                "- name: Block\n  vars:\n    app_port: 2\n  block:\n    - name: In the block\n      ansible.builtin.debug:\n        msg: \"{{ app_port }}\"\n" +
                "- name: Plain\n  ansible.builtin.debug:\n    msg: \"{{ app_port }}\"\n" +
                "- name: Fix\n  ansible.builtin.set_fact:\n    app_port: 9000\n" +
                "- name: After\n  ansible.builtin.debug:\n    msg: \"{{ app_port }}\"\n",
        )
        refreshRoots()
        assertEquals("msg: \"{{ app_port‹task vars 1› }}\"", line(tasks, 4))
        assertEquals("msg: \"{{ app_port‹block vars 2› }}\"", line(tasks, 13))
        assertEquals("before the set_fact", "msg: \"{{ app_port‹default 80 · prod 8443› }}\"", line(tasks, 16))
        assertEquals("msg: \"{{ app_port‹set_fact 9000› }}\"", line(tasks, 22))
        val hint = hints(tasks).last { it.name == "app_port" }
        assertTrue(hint.tooltip, "set_fact" in hint.tooltip && "roles/app/tasks/local.yml = 9000" in hint.tooltip)
    }

    fun testASetFactThatMayNotRunOrRunsElsewhereLeavesNoHint() {
        val conditional = "s/roles/app/tasks/conditional.yml"
        write(
            conditional,
            "---\n- name: Maybe\n  ansible.builtin.set_fact:\n    app_port: 9000\n  when: app_flag | default(false)\n" +
                "- name: Use\n  ansible.builtin.debug:\n    msg: \"{{ app_port }}\"\n",
        )
        refreshRoots()
        assertEquals("msg: \"{{ app_port }}\"", line(conditional, 8))
        assertEquals(
            "a set_fact in another task file of the role: the order is unknown",
            "msg: \"{{ app_version‹default 1.2.0 · dev, stage, test 2.0.0 · +1› }} {{ app_port }} {{ app_only_default }} {{ app_token }} {{ app_password }}\"",
            line(TASKS, 8),
        )
    }

    fun testASetFactThatOnlyReshapesTheValueKeepsTheEnvironmentValues() {
        val tasks = "s/roles/app/tasks/reshape.yml"
        write(
            tasks,
            "---\n- name: Int\n  ansible.builtin.set_fact:\n    app_port: \"{{ app_port | int }}\"\n" +
                "- name: Use\n  ansible.builtin.debug:\n    msg: \"{{ app_port }}\"\n",
        )
        refreshRoots()
        assertEquals("msg: \"{{ app_port‹default 80 · prod 8443› }}\"", line(tasks, 7))
    }

    fun testIncludeVarsOfTheNameInTheRoleLeavesNoHint() {
        write("s/roles/app/vars/extra.yml", "---\napp_port: 7\n")
        val tasks = "s/roles/app/tasks/load.yml"
        write(tasks, "---\n- name: Load\n  ansible.builtin.include_vars:\n    file: extra.yml\n- name: Use\n  ansible.builtin.debug:\n    msg: \"{{ app_port }}\"\n")
        refreshRoots()
        assertEquals("msg: \"{{ app_port }}\"", line(tasks, 7))
    }

    // ------------------------------------------------------------------------------------------------ decision 4

    fun testVarsAndInventoryFilesOnlyWithTheOptionAndThenWhatTheFileSees() {
        assertEquals("app_url: \"https://example.com/{{ app_version }}\"", line(PROD_ALL, 8))
        assertEquals("app_url: \"https://example.com/{{ app_version‹prod 1.4.1 / 1.4.2-hotfix› }}\"", line(PROD_ALL, 8, varsFiles = true))
        write(DEFAULTS, VarSourcesFixture.FILES.getValue(DEFAULTS) + "app_url: \"http://localhost:{{ app_port }}\"\n")
        refreshRoots()
        assertEquals("rule 3 in role defaults: the same file's key, one value", "app_url: \"http://localhost:{{ app_port }}\"", line(DEFAULTS, 7, varsFiles = true))
        assertEquals("app_url: \"http://localhost:{{ app_port‹defaults 80› }}\"", line(DEFAULTS, 7, same = true, varsFiles = true))
    }

    fun testNeverInMoleculeFiles() {
        val converge = "s/roles/app/molecule/default/converge.yml"
        write(converge, "---\n- name: Converge\n  hosts: all\n  tasks:\n    - name: Show\n      ansible.builtin.debug:\n        msg: \"{{ app_version }} {{ app_port }}\"\n")
        refreshRoots()
        assertEquals(emptyList<String>(), hints(converge, same = true, varsFiles = true).map { it.text })
    }

    // ------------------------------------------------------------------------------------------------ decision 6

    fun testAnAccessorOfANonMappingValueIsUnknownAndNeverTheContainer() {
        write(DEFAULTS, VarSourcesFixture.FILES.getValue(DEFAULTS) + "app_cfg: {port: 80, name: app}\n")
        write("s/environments/prod/group_vars/web.yml", "---\napp_version: 1.4.1\napp_cfg: \"{{ base_cfg }}\"\n")
        val tasks = "s/roles/app/tasks/cfg.yml"
        write(tasks, task("cfg", "  ansible.builtin.debug:\n    msg: \"{{ app_cfg.port }}\"\n- name: Major\n  ansible.builtin.debug:\n    msg: \"{{ app_version.major }}\"\n"))
        refreshRoots()
        assertEquals("msg: \"{{ app_cfg.port‹default 80 · prod ?› }}\"", line(tasks, 4))
        assertEquals("every value unknown: no hint", "msg: \"{{ app_version.major }}\"", line(tasks, 7))
    }

    // ------------------------------------------------------------------------------------------------ decision 7

    private fun editorText(path: String, line: Int): String {
        myFixture.configureFromExistingVirtualFile(vf(path))
        myFixture.doHighlighting()
        val dump = DeclarativeHintsDumpUtil.dumpHints(myFixture.editor.document.text, myFixture.editor) { list ->
            list.getEntries().joinToString("") { (it as TextInlayPresentationEntry).text }
        }
        return dump.lines()[line - 1].trim()
    }

    fun testOneRestartPerSwitchForTheVisibleEditorAndABackgroundEditorWhenItIsShown() {
        assertTrue(editorText(TASKS, 4).contains("default 1.2.0"))
        assertTrue(editorText(TEMPLATE, 1).contains("default 1.2.0"))
        val restarts = HostModeRestarts.getInstance(project)
        val switches = restarts.inlaySwitchCount
        val recomputes = restarts.inlayRecomputeCount
        select(RootContext(environment = EnvironmentChoice.Named("prod")))
        PlatformTestUtil.waitWithEventsDispatching("the switch recomputes the visible editor", { restarts.inlaySwitchCount > switches }, 10)
        repeat(10) { PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue(); Thread.sleep(10) }
        assertEquals(switches + 1, restarts.inlaySwitchCount)
        assertEquals("the template's editor only, the task file's is in the background", recomputes + 1, restarts.inlayRecomputeCount)
        // Shown again, the task file's editor follows the selection too.
        val shown = VarSourceHintsRefresh.getInstance(project).shownRecomputeCount
        FileEditorManager.getInstance(project).openFile(vf(TASKS), true)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        assertEquals("recomputed when shown", shown + 1, VarSourceHintsRefresh.getInstance(project).shownRecomputeCount)
        assertTrue(editorText(TASKS, 4), "→ 1.4.1 on 2 of 3 hosts (prod)" in editorText(TASKS, 4))

        VarSourceHintsRefresh.setEnabled(false)
        select(RootContext(environment = EnvironmentChoice.Named("test")))
        repeat(10) { PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue(); Thread.sleep(10) }
        assertEquals("no restart while the hints are off", switches + 1, restarts.inlaySwitchCount)
    }

    // ------------------------------------------------------------------------------------------------ decision 9

    fun testTheClickFindsItsReferenceAfterAnEditAbove() {
        val hint = hints(TASKS).first { it.name == "app_port" }
        val (distance, name) = VarSourceHints.parsePayload(hint.payload)!!
        myFixture.configureFromExistingVirtualFile(vf(TASKS))
        val document = myFixture.editor.document
        val inserted = "# a comment above\n"
        WriteCommandAction.runWriteCommandAction(project) {
            document.insertString(0, inserted)
            PsiDocumentManager.getInstance(project).commitDocument(document)
        }
        val moved = hint.offset + inserted.length
        assertEquals(hint.reference + inserted.length, VarSourceHintClickHandler.referenceStart(document.immutableCharSequence, moved, distance, name))
        // The old offset no longer starts the name: the stale position is never used.
        assertFalse(StringUtil.startsWith(document.immutableCharSequence, hint.reference, name))
    }
}
