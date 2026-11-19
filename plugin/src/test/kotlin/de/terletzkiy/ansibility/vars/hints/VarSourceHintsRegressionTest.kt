package de.terletzkiy.ansibility.vars.hints

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerImpl
import com.intellij.codeInsight.hints.declarative.DeclarativeInlayHintsSettings
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.PlatformTestUtil
import de.terletzkiy.ansibility.api.AnsibleContextService
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.context.host.HostModeRestarts
import de.terletzkiy.ansibility.settings.EnvironmentChoice
import de.terletzkiy.ansibility.settings.RootContext
import de.terletzkiy.ansibility.settings.SettingsTestSupport
import de.terletzkiy.ansibility.vars.VarsTestCase
import de.terletzkiy.ansibility.vars.hints.VarSourcesFixture.PROD_ALL
import de.terletzkiy.ansibility.vars.hints.VarSourcesFixture.PROD_WEB
import de.terletzkiy.ansibility.vars.hints.VarSourcesFixture.SITE
import de.terletzkiy.ansibility.vars.hints.VarSourcesFixture.TASKS
import de.terletzkiy.ansibility.vault.crypto.VaultCrypto

/**
 * The scenarios of the R31 review (commit fc2bf84, adopted by the R31 fixes): a variable source hint must never name a
 * value that the hosts it labels do not get, nor a source that never reaches the role, and a context switch must cost
 * one restart at most. Each test states the correct behaviour.
 *
 * Changed from the review's version where a fix decision changed the expected outcome: entries are read from the
 * hint's full entry list (decision 5 cuts the visible text after about 45 characters and groups environments, decision 1
 * lists the values of hosts that disagree), the winner text may say `on 2 of 3 hosts` (decision 1), values that agree
 * need the option (decision 5), Molecule files get no hint (decision 4) and a context switch restarts the daemon once
 * for the visible editor (decision 7).
 */
class VarSourceHintsRegressionTest : VarsTestCase() {
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

    /** Writes [text] to [path], replacing an existing file's content (the fixture's createFile refuses existing files). */
    private fun write(path: String, text: String) {
        val existing = myFixture.findFileInTempDir(path)
        if (existing == null) myFixture.tempDirFixture.createFile(path, text) else WriteAction.runAndWait<Throwable> { VfsUtil.saveText(existing, text) }
    }

    private val root: AnsibleRoot get() = runReadActionBlocking { AnsibleWorkspace.getInstance(project).contextOf(vf(TASKS))!!.root }

    private fun select(context: RootContext) = AnsibleContextService.getInstance(project).setSelection(root, context)

    private fun hints(path: String, same: Boolean = false): List<VarSourceHints.Hint> =
        inBackgroundReadAction { VarSourceHints.collect(project, psi(path), same) }

    /** The first hint of [name] in [path]. */
    private fun hintOf(path: String, name: String, same: Boolean = false): VarSourceHints.Hint? = hints(path, same).firstOrNull { it.name == name }

    /** The values of the entry that labels [label] (`prod` of `prod 1.4.1 / 1.4.2-hotfix`, `stage` of `stage, test 2.0.0`). */
    private fun entryValues(hint: VarSourceHints.Hint, label: String): List<String> = hint.entries.firstOrNull { label in it.labels }?.values.orEmpty()

    private fun entryValue(hint: VarSourceHints.Hint, label: String): String? = entryValues(hint, label).firstOrNull()

    /** The winner value of a hint with a selected context (`→ 1.4.1 (prod)`, `→ 1.4.1 on 2 of 3 hosts (prod)` → `1.4.1`). */
    private fun winnerValue(text: String): String? = Regex("^→ (\\S+)(?: on \\d+ of \\d+ hosts)? \\(").find(text)?.groupValues?.get(1)

    private fun line(path: String, line: Int): String {
        val text = runReadActionBlocking { psi(path).viewProvider.contents.toString() }
        val start = StringUtil.lineColToOffset(text, line - 1, 0)
        val end = text.indexOf('\n', start).let { if (it < 0) text.length else it }
        val out = StringBuilder()
        var at = start
        for (hint in hints(path).filter { it.offset in start..end }.sortedBy { it.offset }) {
            out.append(text, at, hint.offset).append('‹').append(hint.text).append('›')
            at = hint.offset
        }
        return out.append(text, at, end).toString().trim()
    }

    // ------------------------------------------------------------------------------------------------ review 1

    /**
     * Fixture as committed: prod's `group_vars/all` sets 1.4.0, but `playbook group_vars/all` (2.0.0, level 5) beats it
     * everywhere and prod's `group_vars/web` (1.4.1) / `host_vars/prod-web2` (1.4.2-hotfix) beat that on every host that
     * runs the role. The no-context hint must not label prod with a value no prod host gets.
     */
    fun testTheProdEntryNamesAValueSomeProdHostGets() {
        val plain = hintOf(TASKS, "app_version")!!
        val shown = entryValues(plain, "prod")
        val got = HashSet<String>()
        for (host in listOf("prod-web1", "prod-web2", "prod-web3")) {
            select(RootContext(environment = EnvironmentChoice.Named("prod"), host = host))
            got += winnerValue(hintOf(TASKS, "app_version")!!.text)!!
        }
        assertEquals(setOf("1.4.1", "1.4.2-hotfix"), got)
        assertTrue("hint '${plain.text}' (${plain.entries}) labels prod with $shown, prod hosts get $got", shown.isNotEmpty() && got.containsAll(shown))
    }

    /** stage sets 1.3.5 in its inventory `group_vars/all`, but every stage host gets the playbook-level 2.0.0. */
    fun testTheStageEntryIsNotAValueThePlaybookLevelShadows() {
        val plain = hintOf(TASKS, "app_version")!!
        select(RootContext(environment = EnvironmentChoice.Named("stage")))
        val stage = hintOf(TASKS, "app_version")!!.text
        assertEquals("the engine: stage gets the playbook value", "2.0.0", winnerValue(stage))
        assertEquals("hint '${plain.text}' (${plain.entries}) vs stage's winner '$stage'", "2.0.0", entryValue(plain, "stage"))
    }

    /** The role's own `vars/main.yml` (level 14) beats every inventory value: prod's 8443 never applies. */
    fun testRoleVarsShadowEveryEnvironmentValue() {
        write("s/roles/app/vars/main.yml", "---\napp_port: 9999\n")
        refreshRoots()
        val plain = hintOf(TASKS, "app_port")!!
        select(RootContext(environment = EnvironmentChoice.Named("prod")))
        val prod = hintOf(TASKS, "app_port")!!.text
        assertEquals("the engine: prod gets the role var ($prod)", "9999", winnerValue(prod))
        assertEquals("hint '${plain.text}' (${plain.entries}) labels prod with a shadowed value", "9999", entryValue(plain, "prod"))
    }

    /** After `app_cfg.port` the hint should tell the port per source; the whole-dict preview cut at 24 hides it. */
    fun testAHintAfterAnAccessorShowsTheAccessedValue() {
        write("s/roles/app/defaults/main.yml", "---\napp_version: 1.2.0\napp_port: 80\napp_cfg: {name: myapp, image: registry.example.org/app:1.2.0, port: 80}\n")
        write("s/environments/test/group_vars/web.yml", "---\napp_cfg: {name: myapp, image: registry.example.org/app:1.4.0, port: 8443}\n")
        val tasks = "s/roles/app/tasks/cfg.yml"
        write(tasks, "---\n- name: Port\n  ansible.builtin.debug:\n    msg: \"{{ app_cfg.port }}\"\n")
        refreshRoots()
        val plain = hintOf(tasks, "app_cfg")!!.text
        assertTrue("the hint after app_cfg.port: '$plain'", "8443" in plain && "80" in plain)
    }

    // ------------------------------------------------------------------------------------------------ review 2

    /**
     * A `group_vars/db` of the same environment, whose hosts never run the role, is shown as prod's value. Every prod host
     * that runs the role gets 8, one value, so the hint needs "Also when every environment gets the same value"
     * (decision 5).
     */
    fun testGroupVarsOfAGroupThatNeverRunsTheRoleAreNotTheEnvironmentsValue() {
        write(
            "s/environments/prod/hosts.yml",
            "---\nall:\n  children:\n    web:\n      hosts:\n        prod-web1:\n        prod-web2:\n        prod-web3:\n    db:\n      hosts:\n        prod-db1:\n",
        )
        write(PROD_WEB, "---\napp_version: 1.4.1\napp_workers: 8\n")
        myFixture.tempDirFixture.createFile("s/environments/prod/group_vars/db.yml", "---\napp_workers: 1\n")
        val tasks = "s/roles/app/tasks/workers.yml"
        myFixture.tempDirFixture.createFile(tasks, "---\n- name: Workers\n  ansible.builtin.debug:\n    msg: \"{{ app_workers }}\"\n")
        refreshRoots()
        val plain = hintOf(tasks, "app_workers", same = true)!!
        select(RootContext(environment = EnvironmentChoice.Named("prod")))
        val prod = hintOf(tasks, "app_workers", same = true)!!.text
        assertEquals("the engine: every prod host that runs app gets 8", "8", winnerValue(prod))
        assertEquals("hint '${plain.text}' vs prod's winner '$prod'", "8", entryValue(plain, "prod"))
    }

    /** Role params given to another role (`roles: [{role: other, app_port: 1234}]`) never reach role app (ansible-core: role params of the task's own role and dep chain only). */
    fun testRoleParamsOfAnotherRoleAreNoSourceOfThisRolesVariable() {
        write(
            SITE,
            "---\n- name: Web\n  hosts: web\n  roles:\n    - app\n- name: Other\n  hosts: web\n  roles:\n    - role: other\n      app_port: 1234\n",
        )
        refreshRoots()
        val plain = hintOf(TASKS, "app_port")!!
        assertFalse("role params of role other in app's hint: '${plain.text}'", "1234" in plain.text + plain.tooltip)
    }

    /** Play vars of a play that never applies the role (`hosts: db`, no app) never reach role app. */
    fun testPlayVarsOfAPlayThatNeverAppliesTheRoleAreNoSource() {
        myFixture.tempDirFixture.createFile(
            "s/db.yml",
            "---\n- name: Db\n  hosts: db\n  vars:\n    app_port: 5432\n  tasks:\n    - name: Ping\n      ansible.builtin.ping:\n",
        )
        refreshRoots()
        val plain = hintOf(TASKS, "app_port")!!
        assertFalse("play vars of a play without role app in app's hint: '${plain.text}'", "5432" in plain.text + plain.tooltip)
    }

    // ------------------------------------------------------------------------------------------------ review 5

    /** The task's own `vars:` fix the value for this reference on every host: the inventory values are not its sources. */
    fun testTaskVarsOfTheReferencesOwnTaskShadowEverything() {
        val tasks = "s/roles/app/tasks/pinned.yml"
        myFixture.tempDirFixture.createFile(tasks, "---\n- name: Pinned\n  ansible.builtin.debug:\n    msg: \"{{ app_port }}\"\n  vars:\n    app_port: 1\n")
        refreshRoots()
        val shown = line(tasks, 4)
        assertFalse("a task var reads like the inventory value: $shown", "default 80 · prod 8443" in shown)
    }

    /** A `set_fact` of the same role before the reference sets the value on every host (precedence 19). */
    fun testASetFactOfTheSameRoleBeforeTheReference() {
        val tasks = "s/roles/app/tasks/facts.yml"
        myFixture.tempDirFixture.createFile(
            tasks,
            "---\n- name: Fix\n  ansible.builtin.set_fact:\n    app_port: 9000\n- name: Use\n  ansible.builtin.debug:\n    msg: \"{{ app_port }}\"\n",
        )
        refreshRoots()
        val shown = line(tasks, 7)
        assertFalse("after set_fact the hint still reads like the inventory value: $shown", "default 80 · prod 8443" in shown)
    }

    // ------------------------------------------------------------------------------------------------ review 4

    /** In a prod vars file only prod's hosts read the value: other environments' values are noise there (rule 3 shows prod only). */
    fun testAHintInAProdVarsFileNamesNoOtherEnvironment() {
        val shown = line(PROD_ALL, 8)
        for (other in listOf("dev ", "stage ", "test ")) assertFalse("'$other' in a prod vars file: $shown", "· $other" in shown)
    }

    // ------------------------------------------------------------------------------------------------ review 6

    private val daemon: DaemonCodeAnalyzerImpl get() = DaemonCodeAnalyzer.getInstance(project) as DaemonCodeAnalyzerImpl

    private fun dirty(file: VirtualFile): Boolean =
        !daemon.fileStatusMap.allDirtyScopesAreNullFor(FileDocumentManager.getInstance().getDocument(file)!!)

    /**
     * A context switch recomputes the hints of the visible editor of the switched root, with one daemon restart per switch
     * (decision 7: `DeclarativeInlayHintsPassFactory.scheduleRecompute` restarts the whole project, so the review's
     * "the outside file stays clean" cannot hold while hints show; the number of restarts can): an editor of the root in
     * the background, whose hints were never shown, costs nothing, and nothing restarts while the hints are off.
     */
    fun testAContextSwitchRestartsOnlyTheFilesOfItsRoot() {
        val outside = myFixture.tempDirFixture.createFile("notes/readme.yml", "---\na: 1\n")
        myFixture.configureFromExistingVirtualFile(outside)
        repeat(5) {
            myFixture.doHighlighting()
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            if (!dirty(outside)) return@repeat
        }
        assertFalse("the outside file is up to date before the switch", dirty(outside))
        val editors = FileEditorManager.getInstance(project)
        editors.openFile(vf(VarSourcesFixture.TEMPLATE), false)
        // The visible editor shows the root's hints.
        myFixture.configureFromExistingVirtualFile(vf(TASKS))
        myFixture.doHighlighting()
        try {
            val restarts = HostModeRestarts.getInstance(project)
            val switches = restarts.inlaySwitchCount
            val recomputes = restarts.inlayRecomputeCount
            select(RootContext(environment = EnvironmentChoice.Named("prod")))
            PlatformTestUtil.waitWithEventsDispatching("the switch recomputes the root's hints", { restarts.inlaySwitchCount > switches }, 10)
            repeat(10) { PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue(); Thread.sleep(10) }
            assertEquals("one restart for the switch", switches + 1, restarts.inlaySwitchCount)
            assertEquals("only the visible editor is recomputed", recomputes + 1, restarts.inlayRecomputeCount)

            DeclarativeInlayHintsSettings.getInstance().setProviderEnabled(VarSourceHints.PROVIDER_ID, false)
            select(RootContext(environment = EnvironmentChoice.Named("test")))
            repeat(10) { PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue(); Thread.sleep(10) }
            assertEquals("nothing while the hints are off", switches + 1, restarts.inlaySwitchCount)
            assertEquals(recomputes + 1, restarts.inlayRecomputeCount)
        } finally {
            editors.closeFile(vf(VarSourcesFixture.TEMPLATE))
        }
    }

    // ------------------------------------------------------------------------------------------------ checks expected to pass

    /** Control for review 6: a consumer without inlays (HostModeRestarts' per-file restart only) leaves the outside file alone. */
    fun testControlAPerFileRestartLeavesTheOutsideFileAlone() {
        DeclarativeInlayHintsSettings.getInstance().setProviderEnabled(VarSourceHints.PROVIDER_ID, false)
        try {
            val outside = myFixture.tempDirFixture.createFile("notes/readme.yml", "---\na: 1\n")
            myFixture.configureFromExistingVirtualFile(outside)
            repeat(5) {
                myFixture.doHighlighting()
                PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            }
            assertFalse(dirty(outside))
            val editors = FileEditorManager.getInstance(project)
            editors.openFile(vf(TASKS), false)
            try {
                val parent = Disposer.newDisposable()
                Disposer.register(testRootDisposable, parent)
                val restarts = HostModeRestarts.getInstance(project)
                restarts.register(parent)
                val before = restarts.restartCount
                select(RootContext(environment = EnvironmentChoice.Named("prod")))
                PlatformTestUtil.waitWithEventsDispatching("the switch restarts the root's file", { restarts.restartCount > before }, 10)
                repeat(10) { PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue(); Thread.sleep(10) }
                assertFalse("the outside file was not restarted", dirty(outside))
            } finally {
                editors.closeFile(vf(TASKS))
            }
        } finally {
            DeclarativeInlayHintsSettings.getInstance().setProviderEnabled(VarSourceHints.PROVIDER_ID, true)
        }
    }

    /** With a selected context the winner path (card engine) also decrypts nothing and shows no secret. */
    fun testCheckWinnerPathDecryptsNothing() {
        val decrypts = VaultCrypto.getInstance(project).decryptAttempts
        select(RootContext(environment = EnvironmentChoice.Named("prod")))
        val all = hints(TASKS).joinToString("\n") { it.text + "\n" + it.tooltip }
        for (secret in listOf("6162", "hunter2", "also-secret", "ANSIBLE_VAULT")) assertFalse(all, secret in all)
        select(RootContext(environment = EnvironmentChoice.Named("prod"), host = "prod-web1"))
        val host = hints(TASKS).joinToString("\n") { it.text + "\n" + it.tooltip }
        for (secret in listOf("6162", "hunter2", "also-secret", "ANSIBLE_VAULT")) assertFalse(host, secret in host)
        assertEquals(decrypts, VaultCrypto.getInstance(project).decryptAttempts)
    }

    /**
     * An `inventory/` directory with one file per environment shares `inventory/group_vars` (FlatLayoutTest: the file's
     * environments are prod and staging). The definition reaches both; the hint and the Ctrl+B row name only the first.
     */
    fun testASharedInventoryVarsDirectoryIsEveryServedEnvironmentsValue() {
        write("f/inventory/prod.yml", "all:\n  children:\n    web:\n      hosts:\n        prod-web1:\n")
        write("f/inventory/staging.yml", "all:\n  children:\n    web:\n      hosts:\n        stage-web1:\n")
        write("f/inventory/group_vars/all.yml", "app_mode: shared\n")
        write("f/site.yml", "- hosts: web\n  roles:\n    - app\n")
        write("f/roles/app/defaults/main.yml", "app_mode: default-mode\n")
        val tasks = "f/roles/app/tasks/main.yml"
        write(tasks, "- name: Mode\n  ansible.builtin.debug:\n    msg: \"{{ app_mode }}\"\n")
        refreshRoots()
        val context = runReadActionBlocking { AnsibleWorkspace.getInstance(project).contextOf(vf("f/inventory/group_vars/all.yml"))!! }
        assertEquals("the classifier: one vars dir, two environments", listOf("prod", "staging"), context.environments)
        val plain = hintOf(tasks, "app_mode")!!
        val fRoot = context.root
        AnsibleContextService.getInstance(project).setSelection(fRoot, RootContext(environment = EnvironmentChoice.Named("staging")))
        val staging = hintOf(tasks, "app_mode")!!.text
        assertEquals("the engine: staging gets the shared value ($staging)", "shared", winnerValue(staging))
        assertEquals("hint '${plain.text}' has no staging entry", "shared", entryValue(plain, "staging"))
    }

    /**
     * A Molecule scenario's playbook runs against the scenario's own inventory: the environments' values never reach it.
     * Decision 4: Molecule files get no hint at all (the review expected the scenario's value 0.0.1-mol there).
     */
    fun testAMoleculePlaybookHintNamesTheScenarioValueNotTheEnvironments() {
        val converge = "s/roles/app/molecule/default/converge.yml"
        write(converge, "---\n- name: Converge\n  hosts: all\n  tasks:\n    - name: Show\n      ansible.builtin.debug:\n        msg: \"{{ app_version }}\"\n")
        refreshRoots()
        val shown = line(converge, 7)
        assertFalse("no hint in a Molecule file: $shown", "‹" in shown)
        assertFalse("production environments are not sources of a Molecule run: $shown", "prod 1.4.0" in shown)
    }

    /** Editing a definition in another file's unsaved document changes the computed hint (no save needed). */
    fun testCheckAnUnsavedEditOfAnotherFileReachesTheHint() {
        select(RootContext(environment = EnvironmentChoice.Named("prod")))
        assertEquals("1.4.1", winnerValue(hintOf(TASKS, "app_version")!!.text))
        val document = runReadActionBlocking { FileDocumentManager.getInstance().getDocument(vf(PROD_WEB))!! }
        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) {
            document.setText("---\napp_version: 1.5.0\n")
            com.intellij.psi.PsiDocumentManager.getInstance(project).commitDocument(document)
        }
        assertEquals("1.5.0", winnerValue(hintOf(TASKS, "app_version")!!.text))
        select(RootContext.DEFAULT)
        assertTrue(hintOf(TASKS, "app_version")!!.text, "1.5.0" in hints(TASKS).first { it.name == "app_version" }.tooltip)
    }

    /** Probe: Molecule scenario playbook and a Jinja `{% set %}` local (printed, not asserted). */
    fun testProbeMoleculeAndSetLocal() {
        write("s/roles/app/molecule/default/converge.yml", "---\n- name: Converge\n  hosts: all\n  tasks:\n    - name: Show\n      ansible.builtin.debug:\n        msg: \"{{ app_version }}\"\n")
        write("s/roles/app/templates/set.j2", "{% set app_port = 5 %}{{ app_port }}\n{{ app_port }}\n")
        refreshRoots()
        println("PROBE molecule converge: " + line("s/roles/app/molecule/default/converge.yml", 7))
        println("PROBE set local: " + line("s/roles/app/templates/set.j2", 1) + " | " + line("s/roles/app/templates/set.j2", 2))
    }

    /** Probe: what a hint shows for an aliased value and a merge key (printed, not asserted). */
    fun testProbeAnchorsAndMergeKeys() {
        myFixture.tempDirFixture.createFile(
            "s/environments/test/group_vars/web.yml",
            "---\n_base: &base\n  app_port: 7000\n<<: *base\napp_only_default: &lonely lonely-test\napp_items: *lonely\n",
        )
        refreshRoots()
        println("PROBE app_port: " + line(TASKS, 4))
        println("PROBE app_only_default: " + line(TASKS, 8))
        println("PROBE app_items: " + line(TASKS, 12))
    }
}
