package de.terletzkiy.ansibility.context.host.card

import de.terletzkiy.ansibility.settings.EnvironmentChoice
import de.terletzkiy.ansibility.settings.RootContext

/**
 * F8.2 "This definition gains a status line" (the Effect row, `ansibilityHostDefinition`) on the platform fixture: a
 * definition that wins on some hosts and loses on another, a group that reaches its hosts through a child group, a
 * file no inventory loads, and the definitions static evaluation cannot place (runtime facts, declarations).
 */
class DefinitionCardSectionTest : HostCardTestCase() {
    override fun addFixtureFiles() {
        super.addFixtureFiles()
        add(
            RUNTIME_TASKS,
            """
            ---
            - name: Pick the relay at runtime
              ansible.builtin.set_fact:
                postfix_relayhost: "{{ groups['all'] | first }}"
            """,
        )
    }

    /** The plan's `database.yml:22` example: effective on three database hosts, shadowed on prod-training1 by its host_vars. */
    fun testEffectiveOnSomeHostsAndShadowedOnAnother() {
        val html = card(DATABASE, 22, "percona_config_innodb_buffer_pool_size")
        val effect = row(html, "Effect")!!
        assertTrue(effect, effect.startsWith("✓ effective for "))
        val effective = effect.substringAfter("✓ effective for ").substringBefore(" · ").split(", ").toSet()
        assertEquals(setOf("prod-platform1", "prod-mlflow1", "prod-alias1"), effective)
        assertTrue(effect, effect.endsWith("✗ shadowed for prod-training1 by environments/prod/host_vars/prod-training1/mysql.yml:63 (L9 beats L6)"))
        assertTrue("the winner is linked to its card", Regex("<a href=\"psi_element://ansibility-var/def/[^\"]*\">environments/prod/host_vars/prod-training1/mysql.yml:63</a>").containsMatchIn(html))

        val section = effective(html)
        assertTrue(section, section.startsWith("Effective on 4 hosts ("))
        assertTrue(section, "— 2 values" in section)
        assertTrue(section, "prod-training1 = 4G · environments/prod/host_vars/prod-training1/mysql.yml:63 · L9 inventory host_vars/prod-training1" in section)
    }

    /** The plan's `mysql_users.yml:12` example: its group lists no hosts of its own; the child group `analytics` brings them. */
    fun testAGroupThatReachesItsHostsThroughAChildGroup() {
        val effect = row(card(groupUsers, 12, "mysql_users_human"), "Effect")!!
        assertTrue(effect, effect.startsWith("applies to prod-mlflow1, prod-training1 (via child group analytics)"))
    }

    /** Host mode narrows the child-group line too: it names the selected host only. */
    fun testHostModeNarrowsTheChildGroupLine() {
        withSelection(PLATFORM, RootContext(EnvironmentChoice.Named("prod"), "prod-mlflow1")) {
            val effect = row(card(groupUsers, 12, "mysql_users_human"), "Effect")!!
            assertTrue(effect, effect.startsWith("applies to prod-mlflow1 (via child group analytics)"))
            assertFalse("prod-training1 is not selected: $effect", "prod-training1" in effect)
        }
    }

    /** A group_vars file of a group the inventory does not have: the reason, in the Effect row and the Effective section. */
    fun testAFileNoInventoryLoadsSaysWhy() {
        val html = card(APP_PLATFORM, 3, keyOn(APP_PLATFORM, 3))
        assertEquals("app_platform is not a group of prod: this file is never loaded", row(html, "Effect"))
        assertEquals("Effective app_platform is not a group of prod: this file is never loaded", effective(html))
    }

    /** Host mode (D32: presentation follows the selection): the row covers the selected host only, winning or shadowed. */
    fun testHostModeCoversTheSelectedHostOnly() {
        withSelection(PLATFORM, RootContext(EnvironmentChoice.Named("prod"), "prod-mlflow1")) {
            val effect = row(card(DATABASE, 22, "percona_config_innodb_buffer_pool_size"), "Effect")
            assertEquals("host mode covers the selected host only", "✓ effective for prod-mlflow1", effect)
        }
        withSelection(PLATFORM, RootContext(EnvironmentChoice.Named("prod"), "prod-training1")) {
            val effect = row(card(DATABASE, 22, "percona_config_innodb_buffer_pool_size"), "Effect")
            assertEquals("✗ ineffective for prod-training1: shadowed by environments/prod/host_vars/prod-training1/mysql.yml:63 (L9 beats L6)", effect)
        }
    }

    /** Runtime facts get their reason; spec declarations get no row at all. */
    fun testDefinitionsStaticEvaluationCannotPlace() {
        val runtime = card(RUNTIME_TASKS, 4, "postfix_relayhost")
        assertEquals("Set at runtime; static evaluation cannot tell where it takes effect", row(runtime, "Effect"))
        assertNull("a declaration is no value", row(card(POSTFIX_SPEC, 6, "postfix_relayhost"), "Effect"))
    }

    /** `environments/prod/group_vars/<group>/mysql_users.yml` of the one prod group with such a directory (the fixture's alias). */
    private val groupUsers: String
        get() = vf("$PLATFORM/environments/prod/group_vars").children.single { it.isDirectory && it.findChild("mysql_users.yml") != null }
            .let { "$PLATFORM/environments/prod/group_vars/${it.name}/mysql_users.yml" }

    private companion object {
        const val DATABASE = "$PLATFORM/environments/prod/group_vars/database.yml"
        const val APP_PLATFORM = "$PLATFORM/environments/prod/group_vars/app_platform.yml"
        const val RUNTIME_TASKS = "$FALCON/roles/postfix/tasks/relay.yml"
    }
}
