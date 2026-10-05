package de.terletzkiy.ansibility.vars.usages

import com.intellij.codeInsight.navigation.actions.GotoDeclarationOrUsageHandler2.GTDUOutcome
import com.intellij.find.FindManager
import com.intellij.find.findUsages.FindUsagesHandlerFactory
import com.intellij.find.findUsages.PsiElement2UsageTargetAdapter
import com.intellij.find.impl.FindManagerBase
import com.intellij.injected.editor.EditorWindow
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.application.impl.NonBlockingReadActionImpl
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.editor.ex.util.EditorUtil
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.TestActionEvent
import com.intellij.usageView.UsageViewUtil
import com.intellij.usages.UsageInfo2UsageAdapter
import com.intellij.usages.UsageView
import de.terletzkiy.ansibility.lang.jinja.psi.AnsibleJinjaFile
import de.terletzkiy.ansibility.vault.crypto.VaultCrypto
import org.jetbrains.yaml.psi.YAMLKeyValue

/**
 * F1.10 entry points and results on `testData/vars/site`: `web_port` is declared by the specs of `roles/web` and
 * `roles/other`, defaulted in `roles/web/defaults`, overridden in `environments/dev` and molecule, and read in the
 * role's template (twice), its tasks (a `when:` expression, an `assert.that` item, a folded shell value), the other
 * role's tasks and the playbook's `set_fact` value. Every start finds the same twelve usages.
 */
class VarFindUsagesTest : UsagesTestCase() {

    override fun setUp() {
        super.setUp()
        copyVarsData("site")
    }

    // ------------------------------------------------------------------------------------------------ starts

    fun testFindUsagesFromATemplateUse() {
        at(TEMPLATE, 1, "web_port")
        assertEquals(WEB_PORT, describeUsages(findUsagesViaAction()))
    }

    fun testFindUsagesFromAnInjectedTaskValueSeesTheInjectedFragment() {
        at(TASKS, 34, "web_port")
        val context = injectedContext()
        assertTrue("the action's PSI_FILE is the injected fragment", context.getData(CommonDataKeys.PSI_FILE) is AnsibleJinjaFile)
        assertTrue("the action's EDITOR is the injected editor", context.getData(CommonDataKeys.EDITOR) is EditorWindow)
        assertEquals("web_port", targetAtCaret()?.name)
        assertEquals(WEB_PORT, describeUsages(findUsagesViaAction()))
    }

    fun testFindUsagesFromBareExpressions() {
        at(TASKS, 9, "web_port")
        assertEquals("a when: expression", WEB_PORT, describeUsages(findUsagesViaAction()))
        at(TASKS, 31, "web_port")
        assertEquals("an assert that: item", WEB_PORT, describeUsages(findUsagesViaAction()))
    }

    fun testFindUsagesFromVariableKeys() {
        for ((path, line) in listOf(DEFAULTS to 3, SPEC to 5, GROUP_VARS to 2, MOLECULE to 10, OTHER_SPEC to 5)) {
            at(path, line, "web_port")
            assertEquals("from $path:$line", WEB_PORT, describeUsages(findUsagesViaAction()))
        }
    }

    /** The usage view of the real action names the groups, from a use and from a key (the key-value path of the action). */
    fun testTheActionsUsageViewGroupsEveryUsage() {
        for ((path, line) in listOf(TEMPLATE to 1, DEFAULTS to 3)) {
            at(path, line, "web_port")
            val groups = findUsagesViaActionWithGroups().associate { (usage, group) -> describeUsages(listOf(usage)).single() to group }
            assertEquals("from $path:$line", WEB_PORT.toSet(), groups.keys)
            assertEquals("from $path:$line", "Read: template", groups.getValue("$TEMPLATE:1:web_port R"))
            assertEquals("from $path:$line", "Set: role default", groups.getValue("$DEFAULTS:3:web_port W"))
            assertEquals("from $path:$line", "Spec: argument spec", groups.getValue("$SPEC:5:web_port W"))
            assertEquals("from $path:$line", "Read: condition", groups.getValue("$TASKS:9:web_port R"))
        }
    }

    fun testShowUsagesIsEnabledInsideAnInjection() {
        at(TASKS, 34, "web_port")
        val action = ActionManager.getInstance().getAction("ShowUsages")
        val event = TestActionEvent.createTestEvent(action, EditorUtil.getEditorDataContext(hostEditor()))
        ActionUtil.updateAction(action, event)
        assertTrue("Ctrl+Alt+F7 is enabled (its first target is ours)", event.presentation.isEnabled)
        assertEquals("web_port", ((event.getData(UsageView.USAGE_TARGETS_KEY)?.first() as PsiElement2UsageTargetAdapter).element as VarSymbolElement).name)
    }

    fun testNestedKeysLocalsOfOtherKindsAndModulesOfferNoVariableTarget() {
        at(GROUP_VARS, 4, "inner")
        assertNull("accessor-level usages are v1.x", targetAtCaret())
        at(TASKS, 3, "ansible.builtin.stat")
        assertNull("a module key", targetAtCaret())
    }

    // ------------------------------------------------------------------------------------------------ Ctrl+B (D-FU1)

    fun testCtrlBOnARolesOwnDeclarationShowsUsages() {
        for ((path, line) in listOf(DEFAULTS to 3, SPEC to 5)) {
            val offset = at(path, line, "web_port")
            assertEquals("$path:$line", GTDUOutcome.SU, gtdu(offset))
            // Show Usages hands the platform the key-value; our factory turns it into the variable.
            val keyValue = runReadActionBlocking { PsiTreeUtil.getParentOfType(hostFile().findElementAt(offset), YAMLKeyValue::class.java) }!!
            val manager = (FindManager.getInstance(project) as FindManagerBase).findUsagesManager
            val handler = runReadActionBlocking { manager.getFindUsagesHandler(keyValue, FindUsagesHandlerFactory.OperationMode.USAGES_WITH_DEFAULT_OPTIONS) }
            assertInstanceOf(handler, VarFindUsagesHandler::class.java)
            assertEquals(WEB_PORT.map { it.dropLast(2) }, describeInfos(findUsagesOf(keyValue)))
        }
    }

    fun testCtrlBOnUsesOverrideKeysAndNestedKeysStillNavigates() {
        for ((path, line) in listOf(TASKS to 9, TASKS to 34, TEMPLATE to 1, GROUP_VARS to 2, MOLECULE to 10)) {
            val offset = at(path, line, "web_port")
            assertEquals("$path:$line", GTDUOutcome.GTD, gtdu(offset))
        }
        assertEquals(
            "a use still goes to the spec, then the defaults",
            listOf("site/roles/web/meta/argument_specs.yml:5", "site/roles/web/defaults/main.yml:3"),
            gotoTargets(TASKS, offsetAt(TASKS, 9, "web_port", 1)).take(2).map(::describe),
        )
        assertEquals("a nested key", GTDUOutcome.GTD, gtdu(at(GROUP_VARS, 4, "inner")))
    }

    /** Acceptance 3 on an inventory-only key: Ctrl+B keeps X87 (the sibling definitions), Alt+F7 lists the usages. */
    fun testCtrlBOnAnInventoryOnlyKeyKeepsX87() {
        assertEquals(GTDUOutcome.GTD, gtdu(at(GROUP_VARS, 5, "inventory_only")))
        assertEquals(listOf("site/environments/stage/group_vars/all/vars.yml:2"), gotoTargets(GROUP_VARS, offsetAt(GROUP_VARS, 5, "inventory_only", 1)).map(::describe))
        assertEquals(
            listOf("site/environments/dev/group_vars/all/vars.yml:5:inventory_only W", "site/environments/stage/group_vars/all/vars.yml:2:inventory_only W"),
            describeUsages(findUsagesViaAction()),
        )
    }

    // ------------------------------------------------------------------------------------------------ presentation

    fun testTheUsageViewGroupsReadsBeforeWritesAndNamesTheTarget() {
        at(TEMPLATE, 1, "web_port")
        val symbol = targetAtCaret()!!
        val tree = myFixture.getUsageViewTreeTextRepresentation(symbol)
        val groups = listOf(
            "Read: condition (2)", "Read: task (2)", "Read: template (2)", "Read: templated value (1)",
            "Set: group_vars · dev (1)", "Set: molecule inventory (1)", "Set: role default (1)", "Spec: argument spec (2)",
        )
        val positions = groups.map { group -> tree.indexOf(group).also { assertTrue("group '$group' in:\n$tree", it >= 0) } }
        assertEquals("groups sorted reads, writes, declarations:\n$tree", positions.sorted(), positions)
        assertTrue("the target node:\n$tree", tree.contains("Variable web_port · site"))
        runReadActionBlocking {
            assertEquals("variable", UsageViewUtil.getType(symbol))
            assertEquals("web_port", UsageViewUtil.getShortName(symbol))
        }
    }

    fun testTheDialogScopeNarrowsTheSearch() {
        at(TEMPLATE, 1, "web_port")
        val symbol = targetAtCaret()!!
        val tasksOnly = findUsagesOf(symbol, GlobalSearchScope.fileScope(project, vf(TASKS)))
        assertEquals(WEB_PORT.filter { it.startsWith(TASKS) }.map { it.dropLast(2) }, describeInfos(tasksOnly))
    }

    fun testTheTargetNavigatesToThePrimaryDeclarationOffTheEdt() {
        at(TEMPLATE, 1, "web_port")
        val symbol = targetAtCaret()!!
        assertEquals(
            "the spec of the role the search started in",
            "site/roles/web/meta/argument_specs.yml:5",
            describe(runReadActionBlocking { VarUsageSearch.primaryDeclaration(project, symbol) }!!),
        )
        symbol.navigate(true)
        awaitEdt()
        val editor = FileEditorManager.getInstance(project).selectedTextEditor!!
        assertEquals(vf(SPEC), editor.virtualFile)
        assertEquals(offsetAt(SPEC, 5, "web_port"), editor.caretModel.offset)
    }

    /** A `vars_prompt` entry's `name:` value sets the variable: a write, like a `register:` value. */
    fun testAVarsPromptNameIsAWrite() {
        val path = "site/prompt.yml"
        createFile(
            path,
            """
            ---
            - hosts: all
              vars_prompt:
                - name: web_answer
                  prompt: Answer?
              tasks:
                - ansible.builtin.debug:
                    msg: "{{ web_answer }}"
            """.trimIndent() + "\n",
        )
        at(path, 8, "web_answer", 2)
        val groups = findUsagesViaActionWithGroups().map { (usage, group) -> describeUsages(listOf(usage)).single() to group }.sortedBy { it.first }
        assertEquals(listOf("$path:4:web_answer W" to "Set: vars_prompt", "$path:8:web_answer R" to "Read: task"), groups)
    }

    // ------------------------------------------------------------------------------------------------ vault safety

    fun testVaultRowsShowOnlyTheKeyAndNothingIsDecrypted() {
        val decrypts = VaultCrypto.getInstance(project).decryptAttempts
        at(VAULT, 5, "plain_in_vault")
        val plain = findUsagesViaAction().single() as UsageInfo2UsageAdapter
        val text = rowText(plain)
        assertTrue("the key is shown: '$text'", text.contains("plain_in_vault"))
        assertFalse("the value is not: '$text'", text.contains("plaintext-never-shown"))
        assertEquals("the row still opens the real file", vf(VAULT), plain.file)
        assertEquals(offsetAt(VAULT, 5, "plain_in_vault"), runReadActionBlocking { plain.navigationOffset })

        at(GROUP_VARS, 6, "vault_web_secret", 3)
        val rows = findUsagesViaAction().associateBy { describeUsages(listOf(it)).single() }
        assertEquals(setOf("$VAULT:2:vault_web_secret W", "$GROUP_VARS:6:vault_web_secret R"), rows.keys)
        val definition = rowText(rows.getValue("$VAULT:2:vault_web_secret W"))
        assertFalse("no vault tag or payload: '$definition'", definition.contains("!vault") || definition.contains("ANSIBLE_VAULT"))
        assertEquals("nothing was decrypted", decrypts, VaultCrypto.getInstance(project).decryptAttempts)
    }

    private fun awaitEdt() {
        repeat(20) {
            NonBlockingReadActionImpl.waitForAsyncTaskCompletion()
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        }
    }

    companion object {
        const val TEMPLATE = "site/roles/web/templates/site.conf.j2"
        const val TASKS = "site/roles/web/tasks/main.yml"
        const val DEFAULTS = "site/roles/web/defaults/main.yml"
        const val SPEC = "site/roles/web/meta/argument_specs.yml"
        const val OTHER_SPEC = "site/roles/other/meta/argument_specs.yml"
        const val GROUP_VARS = "site/environments/dev/group_vars/all/vars.yml"
        const val MOLECULE = "site/roles/web/molecule/default/molecule.yml"
        const val VAULT = "site/environments/dev/group_vars/all/vault.yml"

        /** Every usage of `web_port`, as `path:line:text R|W`. */
        val WEB_PORT = listOf(
            "site/environments/dev/group_vars/all/vars.yml:2:web_port W",
            "site/playbook.yml:13:web_port R",
            "site/roles/other/meta/argument_specs.yml:5:web_port W",
            "site/roles/other/tasks/main.yml:4:web_port R",
            "site/roles/web/defaults/main.yml:3:web_port W",
            "site/roles/web/meta/argument_specs.yml:5:web_port W",
            "site/roles/web/molecule/default/molecule.yml:10:web_port W",
            "site/roles/web/tasks/main.yml:31:web_port R",
            "site/roles/web/tasks/main.yml:34:web_port R",
            "site/roles/web/tasks/main.yml:9:web_port R",
            "site/roles/web/templates/site.conf.j2:1:web_port R",
            "site/roles/web/templates/site.conf.j2:4:web_port R",
        )
    }
}
