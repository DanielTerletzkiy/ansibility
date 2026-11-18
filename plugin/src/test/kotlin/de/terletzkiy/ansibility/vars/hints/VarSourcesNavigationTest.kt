package de.terletzkiy.ansibility.vars.hints

import com.intellij.openapi.application.runReadActionBlocking
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

    /** Molecule definitions follow "Show Molecule in navigation and search" (R20, D231). */
    fun testMoleculeFollowsTheSetting() {
        assertFalse(targets(TASKS, 8, "app_version").any { it.startsWith(MOLECULE) })
        MoleculeNavigationFixture.showInNavigationUntil(project, testRootDisposable)
        val shown = targets(TASKS, 8, "app_version")
        assertTrue(shown.toString(), "$MOLECULE:13" in shown)
        assertTrue("among the overrides, before the other roles", shown.indexOf("$MOLECULE:13") < shown.indexOf("$OTHER_SPEC:5"))
    }
}
