package de.terletzkiy.ansibility.semantics.precedence

import de.terletzkiy.ansibility.semantics.precedence.RoleApplication.Kind
import de.terletzkiy.ansibility.semantics.yaml.ScalarStyle
import de.terletzkiy.ansibility.semantics.yaml.YScalar
import de.terletzkiy.ansibility.semantics.yaml.YValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** [RoleLayering]: which roles' defaults and vars a task sees, in ansible-core's order. */
class RoleLayeringTest {
    private fun app(name: String, kind: Kind = Kind.PLAY_ROLE, requiredBy: String? = null) = RoleApplication("roles/$name", name, kind, requiredBy)

    private fun names(applications: List<RoleApplication>, layering: RoleLayering.Layering) = layering.order.map { applications[it].name }

    @Test
    fun `play-level tasks see every public role in play order`() {
        val play = listOf(app("common"), app("x", Kind.DEPENDENCY, "web"), app("web"), app("db", Kind.IMPORT_ROLE))
        val layering = RoleLayering.order(play, runningRole = null)
        assertEquals(listOf("common", "x", "web", "db"), names(play, layering))
        assertNull(layering.running)
    }

    @Test
    fun `the running role and its dependency chain are re-applied last`() {
        val play = listOf(
            app("y", Kind.DEPENDENCY, "x"),
            app("x", Kind.DEPENDENCY, "web"),
            app("z", Kind.DEPENDENCY, "web"),
            app("web"),
            app("other"),
        )
        val layering = RoleLayering.order(play, runningRole = "web")
        assertEquals(listOf("other", "y", "x", "z", "web"), names(play, layering))
        assertEquals(3, layering.running)
    }

    @Test
    fun `include_role is private by default unless it is the running role`() {
        val play = listOf(app("base"), app("dep", Kind.DEPENDENCY, "dyn"), app("dyn", Kind.INCLUDE_ROLE), app("tail"))
        assertEquals(listOf("base", "tail"), names(play, RoleLayering.order(play, runningRole = null)))
        assertEquals(listOf("base", "tail", "dep", "dyn"), names(play, RoleLayering.order(play, runningRole = "dyn")))
    }

    @Test
    fun `private_role_vars keeps only the running chain`() {
        val play = listOf(app("a"), app("b"), app("c"))
        assertEquals(listOf("b"), names(play, RoleLayering.order(play, runningRole = "b", privateRoleVars = true)))
        assertEquals(emptyList<String>(), names(play, RoleLayering.order(play, runningRole = null, privateRoleVars = true)))
    }

    @Test
    fun `a role applied twice keeps only its last application`() {
        val play = listOf(app("shared", Kind.DEPENDENCY, "a"), app("a"), app("shared", Kind.DEPENDENCY, "b"), app("b"))
        assertEquals(listOf("a", "shared", "b"), names(play, RoleLayering.order(play, runningRole = null)))
    }

    @Test
    fun `a running role that is only a dependency carries its own dependencies`() {
        val play = listOf(app("leaf", Kind.DEPENDENCY, "mid"), app("mid", Kind.DEPENDENCY, "top"), app("top"), app("last"))
        val layering = RoleLayering.order(play, runningRole = "mid")
        assertEquals(listOf("top", "last", "leaf", "mid"), names(play, layering))
        assertEquals(1, layering.running)
    }

    @Test
    fun `an unknown running role changes nothing`() {
        val play = listOf(app("a"), app("b"))
        val layering = RoleLayering.order(play, runningRole = "missing")
        assertEquals(listOf("a", "b"), names(play, layering))
        assertNull(layering.running)
    }

    @Test
    fun `the layering gives ansible-core's winners in the engine`() {
        // roles: [a, b]; both define v in defaults. A task of a sees a's value, a play task sees b's.
        val play = listOf(app("a"), app("b"))
        val defaults = mapOf("a" to "from-a", "b" to "from-b")
        fun winnerFor(running: String?): String {
            val layering = RoleLayering.order(play, running)
            val sources = layering.order.mapIndexed { order, index ->
                val name = play[index].name
                VarSource(VarLayer.ROLE_DEFAULTS, VarOwner.All, name, order, mapOf("v" to YScalar(defaults.getValue(name), ScalarStyle.PLAIN) as YValue))
            }
            val engine = PrecedenceEngine()
            val graph = de.terletzkiy.ansibility.semantics.inventory.YamlInventoryParser.parse(
                de.terletzkiy.ansibility.semantics.testutil.YamlText.parse("all:\n  hosts:\n    h:\n"),
            )
            val inventory = engine.inventoryView(graph, "h", emptyList())!!
            return engine.effectiveOf("v", inventory, sources)!!.winner.source.originId
        }
        assertEquals("b", winnerFor(null))
        assertEquals("a", winnerFor("a"))
        assertEquals("b", winnerFor("b"))
    }
}
