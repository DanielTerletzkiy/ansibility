package de.terletzkiy.ansibility.context.host

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.PlatformTestUtil
import de.terletzkiy.ansibility.api.HostKey
import de.terletzkiy.ansibility.api.SourceLocation
import de.terletzkiy.ansibility.api.VarService
import de.terletzkiy.ansibility.api.VarsLayer
import de.terletzkiy.ansibility.model.inventory.ModelCaches

/**
 * [RootEffectiveSummaries] and [RootEffectiveSummary] (plan amendment R7/R8, A.9 changes 3 and 6) on the infra fixture:
 * winners equal the "Explain precedence" chains in every context, statuses equal `definitionStatus`, the never-winning
 * set holds the structurally shadowed definitions, the summary is current exactly while its inputs are, the background
 * rebuild is debounced, and the daemon restarts only for open files whose definitions changed status.
 */
class RootEffectiveSummaryTest : HostContextTestCase() {
    private val summaries: RootEffectiveSummaries get() = RootEffectiveSummaries.getInstance(project)

    override fun tearDown() {
        try {
            // A rebuild still running would race with the next test's counters.
            waitFor("the summary worker is idle") { !summaries.isBusy }
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    /** Appends [text] to [path] and commits it, as typing does (never saved). */
    private fun type(path: String, text: String) {
        WriteCommandAction.runWriteCommandAction(project) {
            val document = FileDocumentManager.getInstance().getDocument(vf(path))!!
            document.insertString(document.textLength, text)
            PsiDocumentManager.getInstance(project).commitDocument(document)
        }
    }

    /** Dispatches EDT events until [condition] holds, for at most [timeoutMillis]. */
    private fun waitFor(what: String, timeoutMillis: Long = 30_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) fail("timed out waiting until $what")
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            Thread.sleep(POLL_MILLIS)
        }
    }

    fun testWinnersAgreeWithExplainInEveryContext() {
        var checked = 0
        for (path in listOf(FALCON, PLATFORM, PELICAN)) {
            val summary = summaries.compute(root(path))
            assertTrue("$path has contexts", summary.contexts.isNotEmpty())
            for (target in summary.contexts) {
                val names = (context.executionView(target, null) ?: context.inventoryView(target))!!.vars.map { it.name } + "no_such_variable"
                for (name in names) {
                    val expected = impl.explain(target, name, null).winner?.source
                    val actual = summary.winner(name, target)
                    assertEquals("$name in ${label(target)}", expected?.let { "${at(it)} ${it.layer}" }, actual?.let { "${at(it.location)} ${it.layer}" })
                    checked++
                }
            }
        }
        assertTrue("checked $checked names", checked > 1000)
    }

    fun testWinnersForARunningRoleAgreeWithExplain() {
        val falcon = root(FALCON)
        val summary = summaries.compute(falcon)
        for (role in listOf("postfix", "haproxy", "system")) {
            val targets = context.reach(falcon, role).targets
            val scope = summary.scope(targets, role)
            for (target in targets) {
                val names = context.executionView(target, role)!!.vars.map { it.name }
                for (name in names) {
                    val expected = impl.explain(target, name, role).winner?.source
                    val actual = summary.winner(name, target, role)
                    assertEquals("$name for $role in ${label(target)}", expected?.let { "${at(it)} ${it.role}" }, actual?.let { "${at(it.location)} ${it.role}" })
                    assertSame("a resolved scope answers the same", actual, scope.winners(name)[targets.indexOf(target)])
                }
            }
        }
    }

    /** Completion's hot path (Testing §5: "+0 ms, summary lookups only"): every name of the root over a template's scope. */
    fun testCompletionTailsAreLookups() {
        val falcon = root(FALCON)
        val summary = summaries.compute(falcon)
        val scope = context.hostScope(vf(POSTFIX_TEMPLATE))
        val resolved = summary.scope(scope.targets, impl.runningRole(scope))
        val names = summary.contexts.flatMap { context.executionView(it, null)?.vars?.map { v -> v.name } ?: context.inventoryView(it)!!.vars.map { v -> v.name } }.distinct()
        repeat(WARMUP_ROUNDS) { names.forEach { resolved.winners(it) } }
        val start = System.nanoTime()
        var tails = 0
        for (name in names) tails += resolved.winners(name).count { it != null }
        val nanos = System.nanoTime() - start
        println("Completion tails from the summary: ${names.size} names × ${scope.targets.size} targets in %.2f ms ($tails tails)".format(java.util.Locale.ROOT, nanos / 1e6))
        assertTrue(tails > 0)
        assertTrue("lookups only: ${nanos / 1_000_000} ms", nanos < TAILS_BUDGET_NANOS)
    }

    fun testStatusesAgreeWithDefinitionStatusForInventoryDefinitions() {
        val falcon = root(FALCON)
        val summary = summaries.compute(falcon)
        val names = listOf("postfix_relayhost", "ansible_user", "ansible_host", "keepalived_priority", "inventory_docs_client_structure")
        var compared = 0
        for (name in names) {
            for (definition in VarService.getInstance(project).symbol(falcon, name).definitions) {
                // Role files are evaluated with their role running and molecule scenarios are no summary contexts.
                if (definition.layer == null || definition.layer in setOf(VarsLayer.ROLE_DEFAULTS, VarsLayer.ROLE_VARS, VarsLayer.MOLECULE_INVENTORY)) continue
                val status = context.definitionStatus(definition)
                val fromSummary = summary.status(definition.location, name)
                val label = "$name at ${at(definition.location)}"
                if (fromSummary == null) {
                    assertTrue("$label is loaded by no context", status.winsOn.isEmpty() && status.shadowedOn.isEmpty())
                    continue
                }
                assertEquals(label, status.winsOn.toSet(), fromSummary.winsOn.toSet())
                assertEquals(label, status.shadowedOn.mapValues { at(it.value) }, fromSummary.shadowedOn.mapValues { at(it.value.location) })
                compared++
            }
        }
        assertTrue("compared $compared definitions", compared >= 5)
    }

    fun testNeverWinningHoldsTheStructurallyShadowedDefinitions() {
        val summary = summaries.compute(root(FALCON))
        val relayhost = summary.neverWinningStatuses().single { it.definition.name == "postfix_relayhost" && at(it.definition.location) == "$FALCON_PROD_ALL:471" }
        assertEquals(VarsLayer.INVENTORY_GROUP_VARS_ALL, relayhost.definition.layer)
        assertEquals("all", relayhost.definition.group)
        assertEquals(listOf("prod/prod-prod1", "prod/prod-prod2"), relayhost.shadowedOn.keys.map(::label))
        assertTrue(relayhost.shadowedOn.values.all { at(it.location) == "$FALCON_PLAYBOOK_ALL:156" && it.layer == VarsLayer.PLAYBOOK_GROUP_VARS_ALL })
        val winner = summary.definitionAt(SourceLocation(vf(FALCON_PLAYBOOK_ALL), offset(FALCON_PLAYBOOK_ALL, 156)), "postfix_relayhost")
        assertNotNull("the winner is interned", winner)
        assertFalse(summary.neverWinning().any { it === winner })
        assertTrue("winners are never vault-flagged unless they are vaults", summary.neverWinning().none { it.isVault && it.layer == VarsLayer.PLAYBOOK_GROUP_VARS_ALL && it.name == "postfix_relayhost" })
    }

    fun testANestedRootSharesItsParentsSummary() {
        val parent = summaries.compute(root(PELICAN))
        assertSame(parent, summaries.compute(root(DANGER_ZONE)))
        assertTrue("danger-zone plays are contexts of pelican", parent.contexts.any { it.playbookDir == vf(DANGER_ZONE) })
        assertTrue(parent.contexts.all { it.host.root == PELICAN })
    }

    fun testTheSummaryIsCurrentExactlyWhileItsInputsAre() {
        val falcon = root(FALCON)
        assertNull("nothing is built in the background in tests", summaries.current(falcon))
        val first = summaries.compute(falcon)
        assertSame(first, summaries.current(falcon))
        val builds = summaries.stats.computations

        type("$FALCON/roles/postfix/tasks/main.yml", "\n- name: Typed\n  ansible.builtin.debug:\n    msg: typed\n")
        assertSame("typing in a role's tasks keeps the summary", first, summaries.current(falcon))
        assertSame(first, summaries.compute(falcon))
        assertEquals(builds, summaries.stats.computations)

        type(FALCON_PROD_ALL, "\nha2_summary_marker: 1\n")
        assertNull("an edit of a loaded vars file makes it stale", summaries.current(falcon))
        assertSame("the last complete summary stays readable", first, summaries.latest(falcon))
        val second = summaries.compute(falcon)
        assertNotSame(first, second)
        val marker = second.definitions("ha2_summary_marker").single()
        assertEquals(VarsLayer.INVENTORY_GROUP_VARS_ALL, marker.layer)
        val status = second.status(marker.location, "ha2_summary_marker")!!
        assertEquals(listOf("prod/prod-prod1", "prod/prod-prod2"), status.winsOn.map(::label))
        assertTrue(first.definitions("ha2_summary_marker").isEmpty())
    }

    fun testTheBackgroundRebuildIsDebounced() {
        val falcon = root(FALCON)
        summaries.setBackgroundEnabled(true, testRootDisposable)
        assertNull(summaries.current(falcon))
        waitFor("the first background build") { summaries.current(falcon) != null }
        val builds = summaries.backgroundBuildCount

        type(FALCON_PROD_ALL, "\nha2_debounce_a: 1\n")
        type(FALCON_PROD_ALL, "ha2_debounce_b: 1\n")
        type(FALCON_PROD_ALL, "ha2_debounce_c: 1\n")
        val edited = System.nanoTime()
        assertNull("stale right after the edits", summaries.current(falcon))
        waitFor("the debounced rebuild") { summaries.current(falcon) != null }
        val elapsedMillis = (System.nanoTime() - edited) / 1_000_000
        assertTrue("rebuilt after the quiet period, not before (${elapsedMillis} ms)", elapsedMillis >= RootEffectiveSummaries.DEBOUNCE_MILLIS - SLACK_MILLIS)
        assertEquals("three edits in a burst give one rebuild", builds + 1, summaries.backgroundBuildCount)
        val summary = summaries.current(falcon)!!
        for (name in listOf("ha2_debounce_a", "ha2_debounce_b", "ha2_debounce_c")) assertEquals(name, 1, summary.definitions(name).size)
    }

    fun testTheDaemonRestartsOnlyForOpenFilesWhoseDefinitionsChangedStatus() {
        val falcon = root(FALCON)
        myFixture.configureFromExistingVirtualFile(vf(FALCON_PROD_ALL))
        assertTrue(vf(FALCON_PROD_ALL) in FileEditorManager.getInstance(project).openFiles)
        summaries.setBackgroundEnabled(true, testRootDisposable)
        summaries.request(falcon)
        waitFor("the first background build") { summaries.current(falcon) != null && !summaries.isBusy }
        val restarts = summaries.restartCount

        // A new definition in a file nobody has open: statuses change there only.
        type(FALCON_TEST_ALL, "\nha2_closed_file_key: 1\n")
        waitFor("the rebuild after the closed-file edit") { summaries.current(falcon) != null && !summaries.isBusy }
        repeat(QUIET_ROUNDS) {
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            Thread.sleep(POLL_MILLIS)
        }
        assertEquals("no open file changed status", restarts, summaries.restartCount)

        // A new definition in the open file changes its statuses.
        type(FALCON_PROD_ALL, "\nha2_open_file_key: 1\n")
        waitFor("a restart for the open file") { summaries.restartCount == restarts + 1 }

        // Shadowing it from playbook group_vars (closed) changes the open file's status again.
        type(FALCON_PLAYBOOK_ALL, "\nha2_open_file_key: 2\n")
        waitFor("a second restart for the open file") { summaries.restartCount == restarts + 2 }
        val status = summaries.current(falcon)!!.definitions("ha2_open_file_key").single { it.layer == VarsLayer.INVENTORY_GROUP_VARS_ALL }
            .let { summaries.current(falcon)!!.status(it.location, it.name)!! }
        assertEquals(emptyList<HostKey>(), status.winsOn)
        assertEquals(listOf("prod/prod-prod1", "prod/prod-prod2"), status.shadowedOn.keys.map(::label))
    }

    fun testFootprintAndBuildTime() {
        val report = StringBuilder()
        var bytes = 0L
        for (path in listOf(FALCON, PLATFORM, PELICAN)) {
            val summary = summaries.compute(root(path))
            bytes += summary.estimatedBytes()
            report.append("$path: ${summary.contexts.size} contexts, ${summary.hosts.size} hosts, ${summary.definitionCount} definitions, ")
                .append("%.1f ms, %.1f KB; ".format(java.util.Locale.ROOT, summary.buildNanos / 1e6, summary.estimatedBytes() / 1024.0))
        }
        println("RootEffectiveSummary on the fixture: $report total ${bytes / 1024} KB; caches ${ModelCaches.getInstance(project).stats().size}")
        assertTrue("summaries stay far below the 5 MB budget: $bytes", bytes < BUDGET_BYTES)
    }

    private companion object {
        const val POLL_MILLIS = 20L
        const val QUIET_ROUNDS = 15
        const val SLACK_MILLIS = 100L
        const val BUDGET_BYTES = 5L * 1024 * 1024
        const val WARMUP_ROUNDS = 5

        /** Far above the measured value; completion's own budget is p95 < 100 ms for the whole popup. */
        const val TAILS_BUDGET_NANOS = 50_000_000L
    }
}
