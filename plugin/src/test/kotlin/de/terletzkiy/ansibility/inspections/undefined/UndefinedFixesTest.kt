package de.terletzkiy.ansibility.inspections.undefined

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.modcommand.ActionContext
import com.intellij.modcommand.ModCommandExecutor
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile

/** The ANS-V003 quick fixes (plan amendment R7/R8, F8.12 (e)): each edit and its preview. */
class UndefinedFixesTest : UndefinedTestCase() {
    override fun addFixtureFiles() {
        for ((path, text) in ProbeRole.FILES) add(path, text)
    }

    private fun text(file: VirtualFile): String = FileDocumentManager.getInstance().getDocument(file)!!.text

    /** Opens [path] with the caret on the [occurrence]-th `name` after [anchor] and returns the fix called [fix]. */
    private fun fixAt(path: String, anchor: String, name: String, fix: String, occurrence: Int = 0): IntentionAction {
        myFixture.enableInspections(AnsiblePossiblyUndefinedInspection::class.java)
        myFixture.configureFromExistingVirtualFile(vf(path))
        val content = myFixture.editor.document.text
        var at = content.indexOf(anchor)
        assertTrue("$anchor not in $path", at >= 0)
        repeat(occurrence) { at = content.indexOf(anchor, at + 1) }
        myFixture.editor.caretModel.moveToOffset(content.indexOf(name, at) + 1)
        myFixture.doHighlighting()
        return myFixture.getAvailableIntention(fix)
            ?: error("no '$fix' at $anchor; available: ${myFixture.availableIntentions.map { it.text }}")
    }

    /**
     * Runs a template fix through the platform's ModCommand executor. `launchAction` would compare the edited tree with
     * one parsed from the text under the file's name only, which types `.j2` as PyCharm's Jinja2, not as the template the
     * Ansibility overrider makes it inside a root.
     */
    private fun applyInTemplate(action: IntentionAction) {
        val modAction = action.asModCommandAction() ?: error("${action.text} is not a ModCommand action")
        val context = ActionContext.from(myFixture.editor, myFixture.file)
        ModCommandExecutor.executeInteractively(context, action.text, myFixture.editor) { modAction.perform(context) }
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
    }

    fun testWrapRestoresTheFixtureGuard() {
        val original = VfsUtil.loadText(vf(CONFIG_BASE))
        val lines = original.lines().toMutableList()
        lines.removeAt(16)
        lines.removeAt(14)
        write(CONFIG_BASE, lines.joinToString("\n"))
        val action = fixAt(CONFIG_BASE, "bearer_token", "alloy_tenant_api_key", "Wrap in $GUARD…{% endif %}")
        assertTrue(myFixture.getIntentionPreviewText(action)!!.contains("$GUARD\n    bearer_token"))
        applyInTemplate(action)
        assertEquals(original, myFixture.editor.document.text)
    }

    fun testWrapWholeBlockStatement() {
        replace(ProbeRole.TEMPLATE, "{% if probe_opt %}x{% endif %}{# expect: probe_opt #}", "{% if probe_opt %}\nx\n{% endif %}")
        applyInTemplate(fixAt(ProbeRole.TEMPLATE, "{% if probe_opt %}", "probe_opt", "Wrap in {% if probe_opt is defined and probe_opt %}…{% endif %}"))
        assertTrue(
            myFixture.editor.document.text,
            "{% if probe_opt is defined and probe_opt %}\n{% if probe_opt %}\nx\n{% endif %}\n{% endif %}\n" in myFixture.editor.document.text,
        )
    }

    fun testAppendDefaultInTemplate() {
        replace(CONFIG_BASE, GUARD, "")
        applyInTemplate(fixAt(CONFIG_BASE, "bearer_token", "alloy_tenant_api_key", "Append '| default('')'"))
        assertTrue("bearer_token = \"{{ alloy_tenant_api_key | default('') }}\"" in myFixture.editor.document.text)
    }

    fun testAppendDefaultToAForIterable() {
        applyInTemplate(fixAt(ProbeRole.TEMPLATE, "{% for i in probe_opt", "probe_opt", "Append '| default([])'"))
        assertTrue("{% for i in probe_opt | default([]) %}" in myFixture.editor.document.text)
    }

    fun testNoAppendAfterAMethodCall() {
        fixAt(ProbeRole.TEMPLATE, "{{ probe_opt.items()", "probe_opt", "Wrap in {% if probe_opt is defined and probe_opt %}…{% endif %}")
        assertNull(myFixture.getAvailableIntention("Append '| default('')'"))
    }

    fun testAppendDefaultInYamlKeepsTheQuoting() {
        myFixture.launchAction(fixAt(ProbeRole.OPEN_TASKS, "msg: \"{{ probe_opt }}\" # M-msg", "probe_opt", "Append '| default('')'"))
        assertTrue("msg: \"{{ probe_opt | default('') }}\" # M-msg" in myFixture.editor.document.text)
        myFixture.launchAction(fixAt(ProbeRole.OPEN_TASKS, "'{{ probe_opt }}' # M-single", "probe_opt", "Append '| default(\"\")'"))
        assertTrue(myFixture.editor.document.text, "msg: '{{ probe_opt | default(\"\") }}' # M-single" in myFixture.editor.document.text)
    }

    fun testAddWhenToATaskWithoutOne() {
        val action = fixAt(ProbeRole.OPEN_TASKS, "msg: \"{{ probe_opt }}\" # M-msg", "probe_opt", "Add 'when: probe_opt is defined'")
        myFixture.launchAction(action)
        assertTrue(
            myFixture.editor.document.text,
            "  ansible.builtin.debug:\n    msg: \"{{ probe_opt }}\" # M-msg\n  when: probe_opt is defined\n- name: Guarded by when" in myFixture.editor.document.text,
        )
        assertEmpty(analyse(ProbeRole.OPEN_TASKS).filter { "M-msg" in VfsUtil.loadText(vf(ProbeRole.OPEN_TASKS)).lines()[lineOf(ProbeRole.OPEN_TASKS, it.use.nameRange.startOffset) - 1] })
    }

    fun testAddWhenTurnsAScalarWhenIntoAList() {
        myFixture.launchAction(fixAt(ProbeRole.OPEN_TASKS, "when: probe_opt # M-truthy", "probe_opt", "Add 'when: probe_opt is defined'"))
        assertTrue(myFixture.editor.document.text, "  when:\n    - probe_opt is defined\n    - probe_opt # M-truthy\n" in myFixture.editor.document.text)
    }

    fun testAddWhenPrependsToAList() {
        myFixture.launchAction(fixAt(ProbeRole.OPEN_TASKS, "- probe_opt # M-order", "probe_opt", "Add 'when: probe_opt is defined'"))
        assertTrue(
            myFixture.editor.document.text,
            "  when:\n    - probe_opt is defined\n    - probe_opt # M-order\n    - probe_opt is defined\n" in myFixture.editor.document.text,
        )
    }

    fun testAddRoleDefaultAndSpecDefault() {
        replace(CONFIG_BASE, GUARD, "")
        val action = fixAt(CONFIG_BASE, "bearer_token", "alloy_tenant_api_key", "Add 'alloy_tenant_api_key' to alloy/defaults/main.yml and the argument spec")
        assertNotNull(myFixture.getIntentionPreviewText(action))
        applyInTemplate(action)
        assertTrue(text(vf("$ALLOY/defaults/main.yml")).endsWith("  - traces\nalloy_tenant_api_key: ''\n"))
        val spec = text(vf("$ALLOY/meta/argument_specs.yml"))
        assertTrue(
            spec,
            "      alloy_tenant_api_key:\n        type: str\n        description: Bearer token for Loki and Mimir remote write authentication. Omit to skip authentication.\n        default: ''\n" in spec,
        )
    }

    fun testAddRoleDefaultUsesTheSpecType() {
        replace(ProbeRole.TEMPLATE, "{% for i in probe_opt %}", "{% for i in probe_list %}")
        applyInTemplate(fixAt(ProbeRole.TEMPLATE, "{% for i in probe_list", "probe_list", "Add 'probe_list' to probe/defaults/main.yml and the argument spec"))
        assertEquals("---\nprobe_defaulted: 1\nprobe_list: []\n", text(vf(ProbeRole.DEFAULTS)))
        assertTrue(text(vf(ProbeRole.SPEC)).contains("        description: Optional list.\n        default: []\n"))
    }

    fun testAddRoleDefaultCreatesTheDefaultsFile() {
        myFixture.tempDirFixture.findOrCreateDir("$FALCON/roles/bare/defaults")
        add("$FALCON/roles/bare/tasks/main.yml", "- ansible.builtin.debug:\n    msg: \"{{ bare_value }}\"")
        add("$FALCON/playbook-bare.yml", "- hosts: monitoring_client\n  roles:\n    - bare")
        refreshRoots()
        myFixture.launchAction(fixAt("$FALCON/roles/bare/tasks/main.yml", "msg:", "bare_value", "Add 'bare_value' to bare/defaults/main.yml"))
        assertEquals("---\nbare_value: ''\n", text(vf("$FALCON/roles/bare/defaults/main.yml")))
    }
}
