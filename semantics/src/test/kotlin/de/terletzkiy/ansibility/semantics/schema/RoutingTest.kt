package de.terletzkiy.ansibility.semantics.schema

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RoutingTest {
    private val routing = Routing(
        entries = mapOf(
            "modules" to mapOf(
                "community.mysql.mysql_user" to RouteEntry(
                    redirect = "ansible.mysql.mysql_user",
                    deprecation = RouteNotice("Use ansible.mysql.mysql_user instead.", "6.0.0"),
                ),
                "ansible.builtin.old" to RouteEntry(redirect = "ns.coll.middle"),
                "ns.coll.middle" to RouteEntry(redirect = "ns.coll.final", deprecation = RouteNotice("middle is going away", removalDate = "2030-01-01")),
                "ns.coll.gone" to RouteEntry(tombstone = RouteNotice("Removed, use ns.coll.new.", "3.0.0")),
                "ns.coll.a" to RouteEntry(redirect = "ns.coll.b"),
                "ns.coll.b" to RouteEntry(redirect = "ns.coll.a"),
            ),
            "action" to mapOf("ansible.builtin.include" to RouteEntry(tombstone = RouteNotice("Use include_tasks or import_tasks instead.", removalDate = "2023-05-16"))),
            "filter" to mapOf("ansible.builtin.formerly_core_filter" to RouteEntry(redirect = "ansible.builtin.bool")),
        ),
        moduleAliases = mapOf("ansible.builtin.systemd" to "ansible.builtin.systemd_service"),
    )

    @Test
    fun `a deprecated redirect`() {
        val r = routing.resolve("community.mysql.mysql_user")
        assertEquals("ansible.mysql.mysql_user", r.canonical)
        assertEquals(listOf("community.mysql.mysql_user", "ansible.mysql.mysql_user"), r.chain)
        assertTrue(r.isRedirected)
        assertEquals(listOf(RoutedNotice("community.mysql.mysql_user", RouteNotice("Use ansible.mysql.mysql_user instead.", "6.0.0"))), r.deprecations)
        assertNull(r.tombstone)
    }

    @Test
    fun `chains are followed and short names are builtin`() {
        val r = routing.resolve("old")
        assertEquals("ansible.builtin.old", r.requested)
        assertEquals(listOf("ansible.builtin.old", "ns.coll.middle", "ns.coll.final"), r.chain)
        assertEquals("ns.coll.final", r.canonical)
        assertEquals(listOf("ns.coll.middle"), r.deprecations.map { it.name })
        assertEquals("ansible.builtin.copy", routing.resolve("ansible.legacy.copy").canonical)
    }

    @Test
    fun `aliases apply after redirects and only to modules`() {
        val r = routing.resolve("ansible.builtin.systemd")
        assertEquals("ansible.builtin.systemd_service", r.canonical)
        assertTrue(r.isAlias)
        assertFalse(r.isRedirected)
        assertEquals("ansible.builtin.systemd", routing.resolve("ansible.builtin.systemd", PluginKind.FILTER).canonical)
    }

    @Test
    fun `tombstones and action routing`() {
        assertEquals(RoutedNotice("ns.coll.gone", RouteNotice("Removed, use ns.coll.new.", "3.0.0")), routing.resolve("ns.coll.gone").tombstone)
        assertEquals("ansible.builtin.include", routing.resolve("include").tombstone?.name, "modules fall back to action routing")
        assertNull(routing.resolve("include", PluginKind.FILTER).tombstone)
    }

    @Test
    fun `plugin kinds use their own section`() {
        assertEquals("ansible.builtin.bool", routing.resolve("formerly_core_filter", PluginKind.FILTER).canonical)
        assertEquals("ansible.builtin.formerly_core_filter", routing.resolve("formerly_core_filter").canonical)
    }

    @Test
    fun `redirect loops stop`() {
        val r = routing.resolve("ns.coll.a")
        assertTrue(r.loop)
        assertEquals(listOf("ns.coll.a", "ns.coll.b"), r.chain)
    }

    @Test
    fun `unrouted names resolve to themselves`() {
        val r = routing.resolve("ansible.builtin.template")
        assertEquals("ansible.builtin.template", r.canonical)
        assertEquals(listOf("ansible.builtin.template"), r.chain)
        assertFalse(r.isRedirected || r.isAlias || r.loop)
        assertEquals(6, routing.redirectCount, "redirects in every section are counted")
    }
}
