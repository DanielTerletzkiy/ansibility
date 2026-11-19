package de.terletzkiy.ansibility.vars.hints

import com.intellij.codeInsight.hints.declarative.DeclarativeInlayHintsSettings
import com.intellij.codeInsight.hints.declarative.InlayActionHandler
import com.intellij.codeInsight.hints.declarative.InlayProviderPassInfo
import com.intellij.codeInsight.hints.declarative.impl.DeclarativeInlayHintsPass
import com.intellij.codeInsight.hints.declarative.impl.util.DeclarativeHintsDumpUtil
import com.intellij.codeInsight.hints.declarative.impl.views.TextInlayPresentationEntry
import com.intellij.codeInsight.multiverse.codeInsightContext
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.testFramework.PlatformTestUtil
import de.terletzkiy.ansibility.api.AnsibleContextService
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.context.MoleculeNavigationFixture
import de.terletzkiy.ansibility.context.host.HostModeRestarts
import de.terletzkiy.ansibility.settings.EnvironmentChoice
import de.terletzkiy.ansibility.settings.RootContext
import de.terletzkiy.ansibility.settings.SettingsTestSupport
import de.terletzkiy.ansibility.vars.VarTargetElement
import de.terletzkiy.ansibility.vars.VarsTestCase
import de.terletzkiy.ansibility.vars.hints.VarSourcesFixture.DEFAULTS
import de.terletzkiy.ansibility.vars.hints.VarSourcesFixture.PROD_WEB
import de.terletzkiy.ansibility.vars.hints.VarSourcesFixture.SPEC
import de.terletzkiy.ansibility.vars.hints.VarSourcesFixture.TASKS
import de.terletzkiy.ansibility.vars.hints.VarSourcesFixture.TEMPLATE
import de.terletzkiy.ansibility.vault.crypto.VaultCrypto

/**
 * Plan amendment R31, Part 2 (D232–D237, as amended by the R31 fixes): the variable source hints on YAML values, bare
 * conditions and `.j2` templates, several on one line; only where the values differ unless the option is on; what each
 * environment's hosts get, grouped, about 45 characters then `+N`; the winner first with a selected context and after a
 * switch; vault and `no_log` values as `🔒` (no hint when every value is one), nothing decrypted; no hint for loop
 * variables, template locals, registered results, facts, magic variables and undefined names; the click opens the
 * Ctrl+B list; the toggle action, the platform setting and the provider are one switch ([VarSourcesFixture]). The
 * review's scenarios are in [VarSourceHintsRegressionTest], the fixes' own cases in [VarSourceHintsFixesTest].
 */
class VarSourceHintsTest : VarsTestCase() {
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

    private fun hints(path: String, single: Boolean = false): List<VarSourceHints.Hint> =
        inBackgroundReadAction { VarSourceHints.collect(project, psi(path), single) }

    /** Line [line] of [path] with every hint inserted as `‹text›` at its offset. */
    private fun line(path: String, line: Int, single: Boolean = false): String = render(path, hints(path, single).map { it.offset to it.text }, line)

    private fun render(path: String, hints: List<Pair<Int, String>>, line: Int): String {
        val text = runReadActionBlocking { psi(path).viewProvider.contents.toString() }
        val start = StringUtil.lineColToOffset(text, line - 1, 0)
        val end = text.indexOf('\n', start).let { if (it < 0) text.length else it }
        val out = StringBuilder()
        var at = start
        for ((offset, hint) in hints.filter { it.first in start..end }.sortedBy { it.first }) {
            out.append(text, at, offset).append('‹').append(hint).append('›')
            at = offset
        }
        return out.append(text, at, end).toString().trim()
    }

    // ------------------------------------------------------------------------------------------------ text (D232–D234)

    fun testHintsInValuesBareConditionsAndSeveralOnOneLine() {
        assertEquals("name: \"app-{{ app_version‹$VERSION› }}-{{ app_port‹$PORT› }}\"", line(TASKS, 4))
        assertEquals("when: app_version‹$VERSION› is version('1.0', '>=') and app_port‹$PORT› > 0", line(TASKS, 5))
        // R31 fixes, decision 5: variables whose every value is a vault or no_log value get no hint.
        assertEquals(
            "msg: \"{{ app_version‹$VERSION› }} {{ app_port‹$PORT› }} {{ app_only_default }} {{ app_token }} {{ app_password }}\"",
            line(TASKS, 8),
        )
    }

    fun testHintsInTemplatesButNotOnLocals() {
        assertEquals("version={{ app_version‹$VERSION› }}", line(TEMPLATE, 1))
        assertEquals("a local named like a variable gets none", "{% for app_port in [1] %}{{ app_port }}{% endfor %}", line(TEMPLATE, 2))
        assertEquals("port={{ app_port‹$PORT› }}", line(TEMPLATE, 3))
    }

    fun testNoHintForLoopVariablesRegisteredResultsFactsMagicAndUndefinedNames() {
        assertEquals("the loop_var app_port", "msg: \"{{ app_port }}\"", line(TASKS, 11))
        assertEquals("msg: \"{{ app_status.rc }} {{ ansible_user }} {{ inventory_hostname }} {{ undefined_thing }}\"", line(TASKS, 20))
    }

    fun testOnlyOverriddenVariablesUnlessTheOptionIsOn() {
        assertEquals("loop: \"{{ app_items }}\"", line(TASKS, 12))
        assertEquals("loop: \"{{ app_items‹default [a, b]› }}\"", line(TASKS, 12, single = true))
        assertTrue(line(TASKS, 8, single = true), "{{ app_only_default‹default lonely› }}" in line(TASKS, 8, single = true))
    }

    /**
     * D233 as amended by the R31 fixes (decision 5): a value only one environment sets is one value, so it gets a hint
     * only with "Also when every environment gets the same value".
     */
    fun testAVariableOnlyOneEnvironmentSetsGetsAHintOnlyWithTheOption() {
        myFixture.tempDirFixture.createFile("s/environments/stage/group_vars/web.yml", "---\nstage_only: blue\n")
        val tasks = "s/roles/app/tasks/single.yml"
        myFixture.tempDirFixture.createFile(tasks, "---\n- name: One\n  ansible.builtin.debug:\n    msg: \"{{ stage_only }} {{ app_only_default }}\"\n")
        assertEquals("msg: \"{{ stage_only }} {{ app_only_default }}\"", line(tasks, 4))
        assertEquals("msg: \"{{ stage_only‹stage blue› }} {{ app_only_default‹default lonely› }}\"", line(tasks, 4, single = true))
    }

    /**
     * The hint follows the constant accessors as written and names the accessed value (R31 fixes, decision 6). One hint
     * per name in a scalar (decision 5), so each accessor has its own scalar here.
     */
    fun testAHintAfterAnAttributePathDoesNotSplitIt() {
        myFixture.tempDirFixture.createFile("s/environments/stage/group_vars/web.yml", "---\napp_map: {major: 2, minor: 3, x: 4}\n")
        val defaults = VarSourcesFixture.FILES.getValue(DEFAULTS) + "app_map: {major: 1, minor: 2, x: 3}\n"
        WriteAction.runAndWait<Throwable> { VfsUtil.saveText(vf(DEFAULTS), defaults) }
        val text = "---\n- name: Paths\n  ansible.builtin.debug:\n    msg: \"{{ app_map.major }}\"\n" +
            "- name: Minor\n  ansible.builtin.debug:\n    msg: \"{{ app_map['minor'] }}\"\n" +
            "- name: Spaced\n  ansible.builtin.debug:\n    msg: \"{{ app_map [ \\\"x\\\" ] }}\"\n"
        myFixture.tempDirFixture.createFile("s/roles/app/tasks/paths.yml", text)
        assertEquals("msg: \"{{ app_map.major‹default 1 · stage 2› }}\"", line("s/roles/app/tasks/paths.yml", 4))
        assertEquals("msg: \"{{ app_map['minor']‹default 2 · stage 3› }}\"", line("s/roles/app/tasks/paths.yml", 7))
        assertEquals("msg: \"{{ app_map‹default 3 · stage 4› [ \\\"x\\\" ] }}\"", line("s/roles/app/tasks/paths.yml", 10))
    }

    fun testSegmentsStayWithinThePlatformLimitAndTheTooltipListsEveryDefinition() {
        val hint = hints(TASKS).first { it.name == "app_version" }
        assertTrue(hint.segments.toString(), hint.segments.all { it.isNotEmpty() && it.length <= 30 })
        val tooltip = hint.tooltip
        for (row in listOf(
            "defaults · app · roles/app/defaults/main.yml = 1.2.0",
            "group_vars web · prod · environments/prod/group_vars/web.yml = 1.4.1",
            "host_vars prod-web2 · prod · environments/prod/host_vars/prod-web2.yml = 1.4.2-hotfix",
            "playbook group_vars all · group_vars/all.yml = 2.0.0",
        )) {
            assertTrue(tooltip, row in tooltip)
        }
        assertFalse("other roles' defaults are not this role's sources: $tooltip", "9.9.9" in tooltip)
    }

    fun testVaultAndNoLogValuesAreLocksAndNothingIsDecrypted() {
        val decrypts = VaultCrypto.getInstance(project).decryptAttempts
        // R31 fixes, decision 5: a variable whose every value is a lock gets no hint; a vault value among plain ones is a lock.
        myFixture.tempDirFixture.createFile("s/environments/stage/group_vars/web.yml", "---\napp_port: !vault \"\$ANSIBLE_VAULT;1.1;AES256\\n6162\"\n")
        val hints = hints(TASKS)
        assertFalse(hints.joinToString { it.name }, hints.any { it.name == "app_token" || it.name == "app_password" })
        val all = hints.joinToString("\n") { it.text + "\n" + it.tooltip }
        for (secret in listOf("6162", "hunter2", "also-secret", "ANSIBLE_VAULT")) assertFalse(all, secret in all)
        assertEquals("default 80 · prod 8443 · stage 🔒", hints.first { it.name == "app_port" }.text)
        assertTrue(all, "🔒 vault" in all)
        assertEquals("nothing was decrypted", decrypts, VaultCrypto.getInstance(project).decryptAttempts)
    }

    fun testMoleculeFollowsTheSetting() {
        assertFalse(line(TASKS, 4), "molecule" in hints(TASKS).first { it.name == "app_version" }.tooltip)
        MoleculeNavigationFixture.showInNavigationUntil(project, testRootDisposable)
        assertTrue("molecule" in hints(TASKS).first { it.name == "app_version" }.tooltip)
    }

    // ------------------------------------------------------------------------------------------------ winner (D234)

    /** R31 fixes, decision 1: the winner text says how many of the selection's hosts get it, then the default. */
    fun testTheWinnerComesFirstWithASelectedContext() {
        select(RootContext(environment = EnvironmentChoice.Named("prod")))
        assertEquals(
            "name: \"app-{{ app_version‹→ 1.4.1 on 2 of 3 hosts (prod) · default 1.2.0› }}-{{ app_port‹→ 8443 (prod) · default 80› }}\"",
            line(TASKS, 4),
        )
        assertTrue("lock-only variables get no hint in any context: ${line(TASKS, 8)}", "{{ app_token }} {{ app_password }}" in line(TASKS, 8))

        select(RootContext(environment = EnvironmentChoice.Named("prod"), host = "prod-web2"))
        assertTrue(line(TASKS, 4), "app_version‹→ 1.4.2-hotfix (prod-web2) · default 1.2.0›" in line(TASKS, 4))

        select(RootContext(environment = EnvironmentChoice.Named("test")))
        assertTrue(line(TASKS, 4), "app_version‹→ 2.0.0 (test-web1" !in line(TASKS, 4))
        assertTrue(line(TASKS, 4), "app_version‹→ 2.0.0 (test) · default 1.2.0›" in line(TASKS, 4))

        select(RootContext.DEFAULT)
        assertEquals("name: \"app-{{ app_version‹$VERSION› }}-{{ app_port‹$PORT› }}\"", line(TASKS, 4))
    }

    // ------------------------------------------------------------------------------------------------ the editor

    /** The platform pass with the registered provider: the hints the editor shows, as `‹text›` on [line]. */
    private fun editorLine(path: String, line: Int): String {
        myFixture.configureFromExistingVirtualFile(vf(path))
        myFixture.doHighlighting()
        val dump = DeclarativeHintsDumpUtil.dumpHints(myFixture.editor.document.text, myFixture.editor) { list ->
            list.getEntries().joinToString("") { (it as TextInlayPresentationEntry).text }
        }
        return dump.lines()[line - 1].trim()
    }

    fun testTheRegisteredProviderShowsTheHintsInTheEditor() {
        val shown = editorLine(TASKS, 4)
        assertTrue(shown, VERSION in shown && PORT in shown)
        assertTrue(editorLine(TEMPLATE, 1), VERSION in editorLine(TEMPLATE, 1))
    }

    /** The same pass with the provider and its option given explicitly, as the settings preview runs it. */
    fun testThePassWithTheOptionOn() {
        myFixture.configureFromExistingVirtualFile(vf(TASKS))
        val file = myFixture.file
        val options = mapOf(VarSourceHints.OPTION_SINGLE to true, VarSourceHints.OPTION_VARS_FILES to false)
        val info = InlayProviderPassInfo(VarSourceHintsProvider(), VarSourceHints.PROVIDER_ID, options)
        val pass = ActionUtil.underModalProgress(project, "") { DeclarativeInlayHintsPass(file, myFixture.editor, listOf(info), false, false) }
        pass.setContext(file.codeInsightContext)
        ActionUtil.underModalProgress(project, "") { pass.doCollectInformation(EmptyProgressIndicator()) }
        pass.applyInformationToEditor()
        val dump = DeclarativeHintsDumpUtil.dumpHints(myFixture.editor.document.text, myFixture.editor) { list ->
            list.getEntries().joinToString("") { (it as TextInlayPresentationEntry).text }
        }
        assertTrue(dump, "default [a, b]" in dump && "default lonely" in dump)
    }

    fun testAContextSwitchRecomputesTheEditorsHints() {
        assertTrue(editorLine(TASKS, 4).contains(VERSION))
        val restarts = HostModeRestarts.getInstance(project)
        val before = restarts.inlayRecomputeCount
        select(RootContext(environment = EnvironmentChoice.Named("prod")))
        PlatformTestUtil.waitWithEventsDispatching("the selection change recomputes the hints", { restarts.inlayRecomputeCount > before }, 10)
        val shown = editorLine(TASKS, 4)
        assertTrue(shown, "→ 1.4.1 on 2 of 3 hosts (prod)" in shown)
    }

    fun testAContextSwitchRestartsNothingWhileTheHintsAreOff() {
        assertTrue(editorLine(TASKS, 4).contains(VERSION))
        VarSourceHintsRefresh.setEnabled(false)
        val restarts = HostModeRestarts.getInstance(project)
        val before = restarts.restartCount
        val recomputes = restarts.inlayRecomputeCount
        select(RootContext(environment = EnvironmentChoice.Named("prod")))
        repeat(10) { PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue(); Thread.sleep(10) }
        assertEquals(before, restarts.restartCount)
        assertEquals(recomputes, restarts.inlayRecomputeCount)
    }

    // ------------------------------------------------------------------------------------------------ click and toggle

    fun testTheClickHandlerIsRegisteredAndListsTheCtrlBTargets() {
        val handler = InlayActionHandler.getActionHandler(VarSourceHints.HANDLER_ID)
        assertInstanceOf(handler, VarSourceHintClickHandler::class.java)
        val hint = hints(TASKS).first { it.name == "app_version" }
        // R31 fixes, decision 9: the payload holds the hint's distance from its reference, found again from the inlay.
        val (distance, name) = VarSourceHints.parsePayload(hint.payload)!!
        assertEquals(hint.offset - hint.reference to "app_version", distance to name)
        myFixture.configureFromExistingVirtualFile(vf(TASKS))
        val document = myFixture.editor.document
        assertEquals(hint.reference, VarSourceHintClickHandler.referenceStart(document.immutableCharSequence, hint.offset, distance, name))
        val targets = inBackgroundReadAction { VarSourceHintClickHandler.targets(project, document, hint.reference) }
        assertEquals(gotoTargets(TASKS, hint.reference).map(::describe), targets.map(::describe))
        assertEquals(listOf("$SPEC:5", "$DEFAULTS:2"), targets.take(2).map(::describe))
        assertTrue(targets.all { it is VarTargetElement })
        assertTrue(targets.map(::describe).contains("$PROD_WEB:2"))
    }

    fun testTheToggleActionFlipsThePlatformSwitchAndItsText() {
        myFixture.configureFromExistingVirtualFile(vf(TASKS))
        val action = ActionManager.getInstance().getAction("Ansibility.ToggleVariableSourceHints")
        assertInstanceOf(action, ToggleVariableSourceHintsAction::class.java)
        val context = SimpleDataContext.builder()
            .add(CommonDataKeys.PROJECT, project)
            .add(CommonDataKeys.VIRTUAL_FILE, vf(TASKS))
            .add(CommonDataKeys.EDITOR, myFixture.editor)
            .build()
        fun event() = AnActionEvent.createEvent(context, action.templatePresentation.clone(), ActionPlaces.EDITOR_POPUP, ActionUiKind.POPUP, null)

        assertTrue("on by default", VarSourceHintsRefresh.isProviderEnabled())
        val shown = event().also { ActionUtil.updateAction(action, it) }
        assertEquals("Ansibility: Hide Variable Source Hints", shown.presentation.text)
        assertTrue(shown.presentation.isEnabledAndVisible)
        assertTrue(editorLine(TASKS, 4).contains(VERSION))

        ActionUtil.performAction(action, event())
        assertEquals(false, DeclarativeInlayHintsSettings.getInstance().isProviderEnabled(VarSourceHints.PROVIDER_ID))
        assertEquals("Ansibility: Show Variable Source Hints", event().also { ActionUtil.updateAction(action, it) }.presentation.text)
        assertFalse("the editor's hints are gone", editorLine(TASKS, 4).contains(VERSION))

        // The platform switch (Settings › Inlay Hints, the hint's own menu) is the same switch.
        DeclarativeInlayHintsSettings.getInstance().setProviderEnabled(VarSourceHints.PROVIDER_ID, true)
        assertEquals("Ansibility: Hide Variable Source Hints", event().also { ActionUtil.updateAction(action, it) }.presentation.text)
        ActionUtil.performAction(action, event())
        ActionUtil.performAction(action, event())
        assertTrue(VarSourceHintsRefresh.isProviderEnabled())
        assertTrue(editorLine(TASKS, 4).contains(VERSION))
    }

    fun testTheToggleIsHiddenInTheMenuOfFilesOutsideRoots() {
        val outside = myFixture.tempDirFixture.createFile("notes/readme.yml", "---\na: 1\n")
        val action = ActionManager.getInstance().getAction("Ansibility.ToggleVariableSourceHints")
        val context = SimpleDataContext.builder().add(CommonDataKeys.PROJECT, project).add(CommonDataKeys.VIRTUAL_FILE, outside).build()
        val event = AnActionEvent.createEvent(context, action.templatePresentation.clone(), ActionPlaces.EDITOR_POPUP, ActionUiKind.POPUP, null)
        ActionUtil.updateAction(action, event)
        assertFalse(event.presentation.isVisible)
        val findAction = AnActionEvent.createEvent(context, action.templatePresentation.clone(), ActionPlaces.ACTION_SEARCH, ActionUiKind.SEARCH_POPUP, null)
        ActionUtil.updateAction(action, findAction)
        assertTrue("Find Action offers it anywhere", findAction.presentation.isEnabledAndVisible)
    }

    private companion object {
        /**
         * R31 fixes, decisions 1 and 5: what the hosts get (prod: 1.4.1 from group_vars web, 1.4.2-hotfix on prod-web2;
         * dev, stage, test: the playbook-level 2.0.0), environments with one value share an entry, about 45 characters.
         */
        const val VERSION = "default 1.2.0 · dev, stage, test 2.0.0 · +1"
        const val PORT = "default 80 · prod 8443"
    }
}
