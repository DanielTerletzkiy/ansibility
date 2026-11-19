package de.terletzkiy.ansibility.vars.hints

import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.impl.PsiManagerEx
import com.intellij.psi.impl.source.PsiFileImpl
import de.terletzkiy.ansibility.api.AnsibleContextService
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.context.MoleculeNavigationFixture
import de.terletzkiy.ansibility.settings.EnvironmentChoice
import de.terletzkiy.ansibility.settings.RootContext
import de.terletzkiy.ansibility.settings.SettingsTestSupport
import de.terletzkiy.ansibility.vars.VarTargetElement
import de.terletzkiy.ansibility.vars.VarsTestCase
import de.terletzkiy.ansibility.vars.hints.VarSourcesFixture.DEFAULTS
import de.terletzkiy.ansibility.vars.hints.VarSourcesFixture.DEV_ALL
import de.terletzkiy.ansibility.vars.hints.VarSourcesFixture.MOLECULE
import de.terletzkiy.ansibility.vars.hints.VarSourcesFixture.OTHER_DEFAULTS
import de.terletzkiy.ansibility.vars.hints.VarSourcesFixture.OTHER_SPEC
import de.terletzkiy.ansibility.vars.hints.VarSourcesFixture.PLAYBOOK_ALL
import de.terletzkiy.ansibility.vars.hints.VarSourcesFixture.PROD_ALL
import de.terletzkiy.ansibility.vars.hints.VarSourcesFixture.PROD_WEB
import de.terletzkiy.ansibility.vars.hints.VarSourcesFixture.PROD_WEB2
import de.terletzkiy.ansibility.vars.hints.VarSourcesFixture.SPEC
import de.terletzkiy.ansibility.vars.hints.VarSourcesFixture.STAGE_ALL
import de.terletzkiy.ansibility.vars.hints.VarSourcesFixture.TASKS
import de.terletzkiy.ansibility.vars.hints.VarSourcesFixture.TEMPLATE
import de.terletzkiy.ansibility.vars.hints.VarSourcesFixture.TEST_ALL
import de.terletzkiy.ansibility.vault.crypto.VaultCrypto
import org.jetbrains.yaml.psi.YAMLKeyValue
import java.util.Locale

/**
 * Plan amendment R31, Part 1 (D229–D231): Ctrl+B from a usage inside a role lists the spec, the winner for a selected
 * context, the role's default, every override by precedence and environment, then the other roles, each row with its
 * layer, environment, `path:line` and value; vault values are never shown; rule 3, D-FU1 and the Molecule setting are
 * unchanged ([VarSourcesFixture]).
 */
class VarSourcesNavigationTest : VarsTestCase() {
    override fun setUp() {
        super.setUp()
        VarSourcesFixture.create { path, text -> myFixture.tempDirFixture.createFile(path, text) }
        refreshRoots()
    }

    override fun tearDown() {
        try {
            SettingsTestSupport.resetAll(project)
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    private val root: AnsibleRoot get() = runReadActionBlocking { AnsibleWorkspace.getInstance(project).contextOf(vf(TASKS))!!.root }

    private fun select(context: RootContext) = AnsibleContextService.getInstance(project).setSelection(root, context)

    /** The Ctrl+B targets of the reference [marker] on [line] of [path]: `path:line` and the row text. */
    private fun rows(path: String, line: Int, marker: String): List<Pair<String, String>> =
        gotoTargets(path, offsetAt(path, line, marker, 1)).map { describe(it) to (it as VarTargetElement).locationString }

    private fun targets(path: String, line: Int, marker: String): List<String> = rows(path, line, marker).map { it.first }

    /** Every override of app_version, lowest precedence first, then by environment and path. */
    private val overrides = listOf("$DEV_ALL:2", "$PROD_ALL:2", "$STAGE_ALL:2", "$TEST_ALL:2", "$PLAYBOOK_ALL:2", "$PROD_WEB:2", "$PROD_WEB2:2")

    fun testFromARoleTaskEveryDefinitionInOneList() {
        assertEquals(
            listOf("$SPEC:5", "$DEFAULTS:2") + overrides + listOf("$OTHER_SPEC:5", "$OTHER_DEFAULTS:2"),
            targets(TASKS, 8, "app_version"),
        )
        // The same list from a bare condition and from a template of the role.
        assertEquals(targets(TASKS, 8, "app_version"), targets(TASKS, 5, "app_version"))
        assertEquals(targets(TASKS, 8, "app_version"), targets(TEMPLATE, 1, "app_version"))
    }

    fun testEachRowSaysLayerEnvironmentPlaceAndValue() {
        val rows = rows(TASKS, 8, "app_version").toMap()
        assertEquals("spec · app · roles/app/meta/argument_specs.yml:5", rows["$SPEC:5"])
        assertEquals("defaults · app · roles/app/defaults/main.yml:2 = 1.2.0", rows["$DEFAULTS:2"])
        assertEquals("group_vars all · prod · environments/prod/group_vars/all.yml:2 = 1.4.0", rows["$PROD_ALL:2"])
        assertEquals("group_vars web · prod · environments/prod/group_vars/web.yml:2 = 1.4.1", rows["$PROD_WEB:2"])
        assertEquals("host_vars prod-web2 · prod · environments/prod/host_vars/prod-web2.yml:2 = 1.4.2-hotfix", rows["$PROD_WEB2:2"])
        assertEquals("playbook group_vars all · group_vars/all.yml:2 = 2.0.0", rows["$PLAYBOOK_ALL:2"])
        assertEquals("group_vars all · dev · environments/dev/group_vars/all.yml:2 = 1.3.9-dev-build-with-a-long-name", rows["$DEV_ALL:2"])
        assertEquals("spec · other · roles/other/meta/argument_specs.yml:5", rows["$OTHER_SPEC:5"])
        assertEquals("defaults · other · roles/other/defaults/main.yml:2 = 9.9.9", rows["$OTHER_DEFAULTS:2"])
    }

    fun testTheWinnerForASelectedEnvironmentComesRightAfterTheSpec() {
        select(RootContext(environment = EnvironmentChoice.Named("prod")))
        val rows = rows(TASKS, 8, "app_version")
        assertEquals(listOf("$SPEC:5", "$PROD_WEB:2", "$DEFAULTS:2"), rows.take(3).map { it.first })
        assertEquals("✓ wins on 2 of 3 hosts of prod · group_vars web · prod · environments/prod/group_vars/web.yml:2 = 1.4.1", rows[1].second)
        assertEquals("every definition still once", targets(TASKS, 8, "app_version").toSet().size, rows.size)
        assertEquals(11, rows.size)
    }

    fun testTheWinnerForASelectedHost() {
        select(RootContext(environment = EnvironmentChoice.Named("prod"), host = "prod-web2"))
        val rows = rows(TASKS, 8, "app_version")
        assertEquals("$PROD_WEB2:2", rows[1].first)
        assertEquals("✓ wins for prod-web2 · host_vars prod-web2 · prod · environments/prod/host_vars/prod-web2.yml:2 = 1.4.2-hotfix", rows[1].second)
    }

    /** In test, the playbook-level `group_vars/all` (level 5) beats the inventory's `group_vars/all` (level 4). */
    fun testTheWinnerInTestIsThePlaybookLevelValue() {
        select(RootContext(environment = EnvironmentChoice.Named("test")))
        val rows = rows(TASKS, 8, "app_version")
        assertEquals("$PLAYBOOK_ALL:2", rows[1].first)
        assertTrue(rows[1].second, rows[1].second.startsWith("✓ wins for test-web1 · playbook group_vars all · group_vars/all.yml:2 = 2.0.0"))
    }

    fun testAllModeMarksNoWinner() {
        assertFalse(rows(TASKS, 8, "app_version").any { "wins" in it.second })
    }

    fun testVaultAndNoLogRowsNeverShowAValue() {
        val decrypts = VaultCrypto.getInstance(project).decryptAttempts
        val token = rows(TASKS, 8, "app_token").toMap()
        assertEquals("group_vars all · prod · environments/prod/group_vars/all.yml:4 = 🔒 vault", token["$PROD_ALL:4"])
        assertEquals("group_vars all · test · environments/test/group_vars/all.yml:3 = 🔒 vault", token["$TEST_ALL:3"])
        val password = rows(TASKS, 8, "app_password")
        assertEquals(listOf("$SPEC:10", "$DEFAULTS:4", "$PROD_ALL:5"), password.map { it.first })
        assertEquals("defaults · app · roles/app/defaults/main.yml:4 = 🔒 no_log", password[1].second)
        val text = (token.values + password.map { it.second }).joinToString("\n")
        for (secret in listOf("6162", "hunter2", "also-secret", "ANSIBLE_VAULT")) assertFalse(text, secret in text)
        assertEquals("nothing was decrypted", decrypts, VaultCrypto.getInstance(project).decryptAttempts)
    }

    /** Rule 3 (D231): a reference inside a vars file goes to what that file sees there, not to the spec list. */
    fun testAReferenceInAVarsFileKeepsRuleThree() {
        val got = targets(PROD_ALL, 8, "app_version")
        assertEquals(setOf("$PROD_WEB:2", "$PROD_WEB2:2"), got.toSet())
    }

    /** D-FU1 (D231): Ctrl+B on the role's own declaration still shows usages (no targets). */
    fun testTheRolesOwnDeclarationStillShowsUsages() {
        assertEquals(emptyList<String>(), gotoTargets(DEFAULTS, offsetAt(DEFAULTS, 2, "app_version", 1)))
        assertEquals(emptyList<String>(), gotoTargets(SPEC, offsetAt(SPEC, 5, "app_version", 1)))
    }

    // ------------------------------------------------------------------------------------------------ R31 fixes

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

    /**
     * Decision 2: the override rows list only what can reach the role's hosts at the usage: no `group_vars` of a group
     * that never runs the role, no role params given to another role, no play vars of a play that never applies it.
     */
    fun testOverridesThatNeverReachTheRoleAreNotListed() {
        write(
            "s/environments/prod/hosts.yml",
            "---\nall:\n  children:\n    web:\n      hosts:\n        prod-web1:\n        prod-web2:\n        prod-web3:\n    db:\n      hosts:\n        prod-db1:\n",
        )
        write("s/environments/prod/group_vars/db.yml", "---\napp_port: 5432\n")
        write("s/environments/prod/group_vars/web.yml", "---\napp_version: 1.4.1\napp_port: 8080\n")
        write(
            VarSourcesFixture.SITE,
            "---\n- name: Web\n  hosts: web\n  roles:\n    - app\n- name: Other\n  hosts: web\n  roles:\n    - role: other\n      app_port: 1234\n",
        )
        write("s/db.yml", "---\n- name: Db\n  hosts: db\n  vars:\n    app_port: 5433\n  tasks:\n    - name: Ping\n      ansible.builtin.ping:\n")
        refreshRoots()
        val rows = rows(TASKS, 8, "app_port").map { it.second }
        assertTrue(rows.joinToString("\n"), rows.any { it.startsWith("group_vars web · prod ·") && it.endsWith("= 8080") })
        for (unreachable in listOf("5432", "1234", "5433")) assertFalse(rows.joinToString("\n"), rows.any { it.endsWith("= $unreachable") })
    }

    /** Low 7 of the review: a vars directory several inventories share names every environment it serves. */
    fun testASharedVarsDirectoryRowNamesEveryEnvironment() {
        write("f/inventory/prod.yml", "all:\n  children:\n    web:\n      hosts:\n        prod-web1:\n")
        write("f/inventory/staging.yml", "all:\n  children:\n    web:\n      hosts:\n        stage-web1:\n")
        write("f/inventory/group_vars/all.yml", "app_mode: shared\n")
        write("f/site.yml", "- hosts: web\n  roles:\n    - app\n")
        write("f/roles/app/defaults/main.yml", "app_mode: default-mode\n")
        val tasks = "f/roles/app/tasks/main.yml"
        write(tasks, "- name: Mode\n  ansible.builtin.debug:\n    msg: \"{{ app_mode }}\"\n")
        refreshRoots()
        val rows = rows(tasks, 3, "app_mode").toMap()
        assertEquals("group_vars all · prod, staging · inventory/group_vars/all.yml:1 = shared", rows["f/inventory/group_vars/all.yml:1"])
    }

    /**
     * Decision 11: playbook-level vars of a nested playbook root are labelled with its directory: from a playbook there,
     * their path (relative to the nested root) reads like the project root's own `group_vars/all.yml`.
     */
    fun testPlaybookVarsOfANestedPlaybookRootNameTheirDirectory() {
        val playbook = "s/deploy/playbook-deploy.yml"
        write(playbook, "---\n- name: Deploy\n  hosts: web\n  roles:\n    - app\n  tasks:\n    - name: Show\n      ansible.builtin.debug:\n        msg: \"{{ app_version }}\"\n")
        write("s/deploy/roles/helper/tasks/main.yml", "---\n- name: Help\n  ansible.builtin.ping:\n")
        write("s/deploy/group_vars/all.yml", "---\napp_version: 3.0.0\n")
        refreshRoots()
        val rows = rows(playbook, 9, "app_version").map { it.second }
        assertTrue(rows.joinToString("\n"), "playbook group_vars all · deploy · group_vars/all.yml:2 = 3.0.0" in rows)
        assertTrue("the project root's own playbook vars stay unlabelled", rows(TASKS, 8, "app_version").any { it.second == "playbook group_vars all · group_vars/all.yml:2 = 2.0.0" })
    }

    /**
     * Decision 10: Ctrl+B on a variable that 300 `host_vars` files set builds its rows from the index (line numbers from
     * the text), and looks up the element written there only for the row that is chosen. (The inventory model reads
     * those files anyway, through their PSI, for every effective value; the rows add no lookup of their own.)
     */
    fun testHundredsOfHostVarsRowsLookUpNoElement() {
        val hosts = (1..300).map { "prod-web$it" }
        write("s/environments/prod/hosts.yml", "---\nall:\n  children:\n    web:\n      hosts:\n" + hosts.joinToString("") { "        $it:\n" })
        for ((i, host) in hosts.withIndex()) {
            write("s/environments/prod/host_vars/$host.yml", "---\nother: 1\napp_port: ${8000 + i}\n")
        }
        refreshRoots()
        val files = hosts.map { vf("s/environments/prod/host_vars/$it.yml") }
        fun parsed(): Int = runReadActionBlocking {
            val manager = PsiManagerEx.getInstanceEx(project).fileManager
            files.count { (manager.getCachedPsiFile(it) as? PsiFileImpl)?.treeElement != null }
        }
        val offset = offsetAt(TASKS, 8, "app_port", 1)
        val before = parsed()
        var start = System.nanoTime()
        val targets = gotoTargets(TASKS, offset)
        val cold = (System.nanoTime() - start) / 1_000_000
        start = System.nanoTime()
        gotoTargets(TASKS, offset)
        val warm = (System.nanoTime() - start) / 1_000_000
        println(String.format(Locale.ROOT, "Ctrl+B with 300 host_vars rows: %d targets, first call %d ms (files parsed by the model: %d before, %d after), second %d ms", targets.size, cold, before, parsed(), warm))
        assertTrue(targets.size > 300)
        val rows = targets.map { it as VarTargetElement }.filter { "/host_vars/" in it.location.file.path }
        assertEquals(300, rows.size)
        assertTrue("no row looked up its element", rows.none { it.isResolved })
        val chosen = rows.first { describe(it).startsWith("s/environments/prod/host_vars/prod-web7.yml") }
        assertEquals("prod-web7's row", "host_vars prod-web7 · prod · environments/prod/host_vars/prod-web7.yml:3 = 8006", chosen.locationString)
        assertEquals("app_port", (runReadActionBlocking { chosen.navigationElement } as YAMLKeyValue).keyText)
        assertTrue(chosen.isResolved)
    }

    /** Molecule definitions follow "Show Molecule in navigation and search" (R20, D231). */
    fun testMoleculeFollowsTheSetting() {
        assertFalse(targets(TASKS, 8, "app_version").any { it.startsWith(MOLECULE) })
        MoleculeNavigationFixture.showInNavigationUntil(project, testRootDisposable)
        val shown = targets(TASKS, 8, "app_version")
        assertTrue(shown.toString(), "$MOLECULE:13" in shown)
        assertTrue("among the overrides, before the other roles", shown.indexOf("$MOLECULE:13") < shown.indexOf("$OTHER_SPEC:5"))
    }
}
