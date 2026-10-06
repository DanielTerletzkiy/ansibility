package de.terletzkiy.ansibility.context.host

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.psi.PsiDocumentManager
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.PlayGraph
import de.terletzkiy.ansibility.api.RootKind
import de.terletzkiy.ansibility.api.VarsLayer
import de.terletzkiy.ansibility.fixtures.RequiresInfraFixture
import de.terletzkiy.ansibility.model.effective.EnvironmentViews
import de.terletzkiy.ansibility.model.effective.ExecutionSources
import de.terletzkiy.ansibility.model.effective.HostViews
import de.terletzkiy.ansibility.model.inventory.InventoryModels
import de.terletzkiy.ansibility.model.inventory.ModelCacheKind
import de.terletzkiy.ansibility.model.inventory.ModelCaches
import de.terletzkiy.ansibility.settings.EnvironmentChoice
import de.terletzkiy.ansibility.settings.RootContext

/**
 * The counter-based budgets of plan amendment R7/R8, Testing §5, on the infra fixture (falcon, platform, pelican with its
 * danger zone, golden's postfix):
 * - typing in `roles/<r>/tasks/main.yml` invalidates **0** inventory views (and no other model value built without it);
 * - editing `environments/prod/group_vars/all/vars.yml` invalidates only that root's prod views;
 * - switching env, host or play recomputes **0** model caches.
 */
@RequiresInfraFixture
class CachingBudgetTest : HostContextTestCase() {
    private val caches: ModelCaches get() = ModelCaches.getInstance(project)

    private val views: HostViews get() = HostViews.getInstance(project)

    /** One (environment, playbook dir) entry of a root. */
    private data class ViewKey(val root: String, val environment: String, val playbookDir: String)

    /** Every root with environments, without the nested ones (they share their parent's entries). */
    private fun inventoryRoots(): List<AnsibleRoot> =
        listOf(FALCON, PLATFORM, PELICAN).map(::root).filter { it.kind != RootKind.NESTED_PLAYBOOK }

    /**
     * The views of every (environment, playbook dir) a root's contexts use: the root's own directory and every playbook
     * dir of a play that runs against its inventory (the danger-zone plays of pelican).
     */
    private fun allViews(): Map<ViewKey, EnvironmentViews> {
        val out = LinkedHashMap<ViewKey, EnvironmentViews>()
        for (root in inventoryRoots()) {
            val dirs = (listOf(root.dir) + impl.model.playHits(root).map { it.play.playbookDir }).distinct()
            for (environment in InventoryModels.getInstance(project).environments(root)) {
                for (dir in dirs) out[ViewKey(rel(root.dir), environment.name, rel(dir))] = views.views(root, environment.name, dir)!!
            }
        }
        return out
    }

    /** Appends [text] to [path] and commits it, as typing does (the file is never saved). */
    private fun type(path: String, text: String) {
        WriteCommandAction.runWriteCommandAction(project) {
            val document = FileDocumentManager.getInstance().getDocument(vf(path))!!
            document.insertString(document.textLength, text)
            PsiDocumentManager.getInstance(project).commitDocument(document)
        }
    }

    fun testTypingInARoleTaskFileInvalidatesNoInventoryView() {
        val before = allViews()
        assertTrue("the fixture has views of several roots and environments: ${before.keys}", before.size >= 6)
        assertTrue("pelican's prod is viewed with the danger-zone playbook dir too", ViewKey(PELICAN, "prod", DANGER_ZONE) in before)
        val reach = context.reach(root(FALCON), "postfix")
        val target = reach.targets.first()
        val inputs = ExecutionSources.getInstance(project).inputs(root(FALCON), target.play, "postfix")
        val graphs = PlayGraph.getInstance(project).playbooks(root(FALCON)).associateWith { PlayGraph.getInstance(project).playsOf(it) }
        val snapshot = caches.snapshot()

        type("$FALCON/roles/postfix/tasks/main.yml", "\n- name: Typed while the test runs\n  ansible.builtin.debug:\n    msg: typed\n")
        type("$FALCON/roles/postfix/tasks/main.yml", "# and once more\n")

        val after = allViews()
        for ((key, entry) in before) assertSame("$key", entry, after[key])
        assertSame(reach, context.reach(root(FALCON), "postfix"))
        assertSame(inputs, ExecutionSources.getInstance(project).inputs(root(FALCON), target.play, "postfix"))
        for ((playbook, plays) in graphs) assertSame(playbook.path, plays, PlayGraph.getInstance(project).playsOf(playbook))
        val now = caches.snapshot()
        assertEquals("0 inventory views invalidated", 0L, now.computationsOf(HostViews.CACHE_NAME, snapshot))
        assertEquals(
            "no model value read the task file",
            emptyMap<String, Long>(),
            now.invalidationsSince(snapshot).filterKeys { it != "execution.includeVars" },
        )
    }

    fun testEditingProdGroupVarsAllInvalidatesOnlyThatRootsProdViews() {
        val before = allViews()
        val snapshot = caches.snapshot()

        type(FALCON_PROD_ALL, "\nha2_typed_marker: 1\n")

        val after = allViews()
        val changed = before.keys.filter { after[it] !== before[it] }
        val falconProd = before.keys.filter { it.root == FALCON && it.environment == "prod" }
        assertEquals("exactly falcon's prod views are recomputed", falconProd, changed)
        assertTrue(falconProd.isNotEmpty())
        assertEquals(falconProd.size.toLong(), caches.snapshot().computationsOf(HostViews.CACHE_NAME, snapshot))

        val prod1 = context.inventoryView(context.selectionScope(root(FALCON), RootContext(EnvironmentChoice.Named("prod"), "prod-prod1")).targets.single())!!
        val marker = prod1["ha2_typed_marker"]!!.winner
        assertEquals(VarsLayer.INVENTORY_GROUP_VARS_ALL, marker.layer)
        assertEquals(FALCON_PROD_ALL, rel(marker.file))
        val test1 = context.inventoryView(context.selectionScope(root(FALCON), RootContext(EnvironmentChoice.Named("test"), "test-test1")).targets.single())!!
        assertNull("test views never load prod's group_vars", test1["ha2_typed_marker"])
    }

    fun testSwitchingTheSelectionRecomputesNoModelCache() {
        val falcon = root(FALCON)
        val system = PlayGraph.getInstance(project).playsOf(vf("$FALCON/playbook-setup-system.yml")).single { it.ref.name == "System" }.ref
        val selections = listOf(
            RootContext.DEFAULT,
            RootContext(EnvironmentChoice.Named("prod")),
            RootContext(EnvironmentChoice.Named("prod"), "prod-prod1"),
            RootContext(EnvironmentChoice.Named("prod"), "prod-prod2", PlayKeys.of(falcon, system)),
            RootContext(EnvironmentChoice.Named("test"), "test-test1"),
            RootContext(EnvironmentChoice.All, null, PlayKeys.of(falcon, system)),
        )
        val files = listOf(POSTFIX_TEMPLATE, POSTFIX_DEFAULTS, FALCON_PROD_ALL, FALCON_PLAYBOOK_ALL, "$FALCON/environments/prod/hosts.yml", "$FALCON/playbook-setup-system.yml", POSTFIX_MOLECULE)
        val definitions = listOf(
            definition(falcon, "postfix_relayhost", FALCON_PLAYBOOK_ALL, 156),
            definition(falcon, "postfix_relayhost", FALCON_PROD_ALL, 471),
            definition(falcon, "postfix_relayhost", POSTFIX_DEFAULTS, 2),
        )
        fun exercise(selection: RootContext) {
            context.setSelection(falcon, selection)
            for (path in files) {
                val scope = context.hostScope(vf(path))
                val breakdown = context.effective(scope, "postfix_relayhost")
                assertNotNull(breakdown)
                context.inventoryFacts(scope)
                val role = impl.runningRole(scope)
                for (target in scope.targets) {
                    context.inventoryView(target)
                    context.executionView(target, role)
                    impl.explain(target, "postfix_relayhost", role)
                }
            }
            val selectionScope = context.selectionScope(falcon)
            context.effective(selectionScope, "postfix_relayhost")
            context.inventoryFacts(selectionScope)
            definitions.forEach(context::definitionStatus)
        }

        // First pass: whatever a selection evaluates for the first time is computed once.
        selections.forEach(::exercise)
        val snapshot = caches.snapshot()
        // Switching among them again, in another order, recomputes nothing in the model.
        for (selection in selections.reversed() + selections) exercise(selection)
        val now = caches.snapshot()
        assertEquals("0 model recomputations after switching", emptyMap<String, Long>(), now.computationsSince(snapshot, ModelCacheKind.MODEL))
        assertEquals("the selection only keys presentation entries, which are cached too", emptyMap<String, Long>(), now.computationsSince(snapshot, ModelCacheKind.PRESENTATION))
    }

    fun testANewSelectionComputesOnlyPresentationEntries() {
        val falcon = root(FALCON)
        context.hostScope(vf(POSTFIX_TEMPLATE)).targets.forEach { context.inventoryView(it) }
        context.effective(context.hostScope(vf(POSTFIX_TEMPLATE)), "postfix_relayhost")
        val snapshot = caches.snapshot()
        context.setSelection(falcon, RootContext(EnvironmentChoice.Named("prod"), "prod-prod2"))
        val scope = context.hostScope(vf(POSTFIX_TEMPLATE))
        assertEquals(listOf("prod/prod-prod2"), hosts(scope))
        context.effective(scope, "postfix_relayhost")
        val now = caches.snapshot()
        assertEquals(emptyMap<String, Long>(), now.computationsSince(snapshot, ModelCacheKind.MODEL))
        assertEquals(mapOf("presentation.hostScopes" to 1L), now.computationsSince(snapshot, ModelCacheKind.PRESENTATION))
    }
}
