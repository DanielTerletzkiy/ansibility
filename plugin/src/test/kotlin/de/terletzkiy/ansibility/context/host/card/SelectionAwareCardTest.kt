package de.terletzkiy.ansibility.context.host.card

import de.terletzkiy.ansibility.context.MoleculeNavigationFixture
import de.terletzkiy.ansibility.context.switching.ContextSwitcher
import de.terletzkiy.ansibility.fixtures.RequiresInfraFixture
import de.terletzkiy.ansibility.model.inventory.ModelCacheKind
import de.terletzkiy.ansibility.model.inventory.ModelCaches
import de.terletzkiy.ansibility.settings.EnvironmentChoice
import de.terletzkiy.ansibility.settings.RootContext

/**
 * D32 for the card: presentation follows the selection (an environment narrows the Effective section and collapses the
 * other environments' "Set in" rows, a host gives one line and the ✓ / struck-through / "not for" marks), while the
 * model behind it is never recomputed for a selection change. Every selection is reset in `finally` (the light project
 * is shared).
 */
@RequiresInfraFixture
class SelectionAwareCardTest : HostCardTestCase() {
    private val prod = RootContext(EnvironmentChoice.Named("prod"))
    private val prod1 = RootContext(EnvironmentChoice.Named("prod"), "prod-prod1")

    /** The "Set in" row of [html] as plain text. */
    private fun setIn(html: String): String = row(html, "Set in")!!

    fun testAllModeRanksSetInByEffect() {
        val hidden = setIn(card(POSTFIX_TEMPLATE, 9, "postfix_relayhost"))
        assertFalse("R20/D153: no Molecule row while Molecule is hidden: $hidden", "molecule.yml" in hidden)
        val hiddenWinner = hidden.indexOf("group_vars/all/vars.yml:156 · playbook group_vars/all · level 5 · $relayHost · wins on 4 of 4 hosts")
        val hiddenLoser = hidden.indexOf("environments/prod/group_vars/all/vars.yml:471 · inventory group_vars/all · level 4 · $prodRelayHost · shadowed on 2 of 4 hosts")
        assertTrue("winners first: $hidden", hiddenWinner == "all environments (playbook vars) ".length && hiddenWinner < hiddenLoser)

        MoleculeNavigationFixture.showInNavigationUntil(project, testRootDisposable)
        val html = card(POSTFIX_TEMPLATE, 9, "postfix_relayhost")
        val setIn = setIn(html)
        val winner = setIn.indexOf("group_vars/all/vars.yml:156 · playbook group_vars/all · level 5 · $relayHost · wins on 4 of 4 hosts")
        val molecule = setIn.indexOf("roles/postfix/molecule/default/molecule.yml:56 · molecule inventory · $moleculeRelayHost · wins on 2 of 2 molecule hosts")
        val prodLoser = setIn.indexOf("environments/prod/group_vars/all/vars.yml:471 · inventory group_vars/all · level 4 · $prodRelayHost · shadowed on 2 of 4 hosts")
        val testLoser = setIn.indexOf("environments/test/group_vars/all/vars.yml:323 · inventory group_vars/all · level 4 · $testRelayHost · shadowed on 1 of 4 hosts")
        assertTrue("winners first: $setIn", winner == "all environments (playbook vars) ".length && winner < molecule && molecule < prodLoser && prodLoser < testLoser)
        assertFalse("nothing collapses in All mode", "Other environments" in setIn)
    }

    fun testAnEnvironmentNarrowsTheSectionAndCollapsesOtherEnvironments() {
        withSelection(FALCON, prod) {
            val html = card(POSTFIX_TEMPLATE, 9, "postfix_relayhost")
            val section = effective(html)
            assertTrue(section, section.startsWith("Effective on 2 hosts (play System) — 1 value prod-prod1, prod-prod2 = $relayHost"))
            assertTrue(section, "environments/prod/group_vars/all/vars.yml:471 (L4, prod ×2) · roles/postfix/defaults/main.yml:2 (L2)" in section)
            assertFalse("test is not selected", "test-test1" in section || ":323" in section)
            assertFalse("molecule outcomes only while no environment is selected", "molecule default" in section)
            val setIn = setIn(html)
            assertTrue(setIn, "wins on 2 of 2 hosts" in setIn && "shadowed on 2 of 2 hosts" in setIn)
            assertTrue("the other environment collapses into one line: $setIn", setIn.endsWith("Other environments env test: environments/test/group_vars/all/vars.yml:323"))
            assertFalse("collapsed rows show no value", "$testRelayHost · " in setIn.substringAfter("Other environments"))
        }
    }

    fun testAHostGivesOneLineAndHostMarks() {
        withSelection(FALCON, prod1) {
            val html = card(POSTFIX_TEMPLATE, 9, "postfix_relayhost")
            val section = effective(html)
            assertTrue(section, section.startsWith("Effective on prod › prod-prod1 (play System) = $relayHost · group_vars/all/vars.yml:156"))
            assertTrue(section, "· Explain precedence ›" in section)
            assertTrue(section, "shadowed: environments/prod/group_vars/all/vars.yml:471 (L4, prod ×1)" in section)
            val setIn = setIn(html)
            assertTrue(setIn, "$relayHost · ✓" in setIn)
            // R20/D153: the role's template lists no Molecule row while Molecule is hidden (the default).
            assertFalse(setIn, "molecule.yml" in setIn)
            val struck = html.substringAfter("<p>Set in</p>").substringBefore("</tr>")
            assertTrue("the loser is struck through: $struck", Regex("<s><a [^>]*>environments/prod/group_vars/all/vars.yml:471</a>[^<]*<code>[^<]*</code></s>").containsMatchIn(struck))

            val definition = card(FALCON_PROD_ALL, 471, "postfix_relayhost")
            assertEquals("✗ ineffective for prod-prod1: shadowed by group_vars/all/vars.yml:156 (L5 beats L4)", row(definition, "Effect"))

            // With Molecule shown, the scenario's value is listed and does not apply to the selected host.
            MoleculeNavigationFixture.showInNavigationUntil(project, testRootDisposable)
            val shown = setIn(card(POSTFIX_TEMPLATE, 9, "postfix_relayhost"))
            assertTrue(shown, "$moleculeRelayHost · not for prod-prod1" in shown)
        }
    }

    fun testADefinitionOutsideTheSelectionSaysItIsNotLoaded() {
        withSelection(FALCON, RootContext(EnvironmentChoice.Named("test"), "test-test1")) {
            // Follow editor (on): the file scope wins, and the row names the selection it is not loaded for.
            val followed = card(FALCON_PROD_ALL, 471, "postfix_relayhost")
            assertEquals(
                "not loaded for test › test-test1 ✗ ineffective for prod-prod1, prod-prod2: shadowed by group_vars/all/vars.yml:156 (L5 beats L4)",
                row(followed, "Effect"),
            )
            assertTrue(effective(followed), "your Ansible context test › test-test1 does not load it" in effective(followed))

            ContextSwitcher.setFollowEditor(project, false)
            try {
                val fixed = card(FALCON_PROD_ALL, 471, "postfix_relayhost")
                assertEquals("not loaded for test › test-test1", row(fixed, "Effect"))
                assertEquals("Effective This file is not loaded for your Ansible context test › test-test1", effective(fixed))
            } finally {
                ContextSwitcher.setFollowEditor(project, true)
            }
        }
    }

    /** "The selection is a filter, never a cache key": switching recomputes no model cache, only presentation entries. */
    fun testSwitchingTheSelectionRecomputesNoModelCache() {
        val selections = listOf(RootContext.DEFAULT, prod, prod1, RootContext(EnvironmentChoice.Named("test"), "test-test1"))
        fun exercise(selection: RootContext) = withSelection(FALCON, selection) {
            card(POSTFIX_TEMPLATE, 9, "postfix_relayhost")
            card(FALCON_PROD_ALL, 471, "postfix_relayhost")
            card(POSTFIX_DEFAULTS, 2, "postfix_relayhost")
        }
        selections.forEach(::exercise)
        val caches = ModelCaches.getInstance(project)
        val snapshot = caches.snapshot()
        for (selection in selections.reversed() + selections) exercise(selection)
        val now = caches.snapshot()
        assertEquals(emptyMap<String, Long>(), now.computationsSince(snapshot, ModelCacheKind.MODEL))
        assertEquals("the card views are cached per selection-aware scope too", 0L, now.computationsOf(HostCardViews.CACHE_NAME, snapshot))
    }
}
