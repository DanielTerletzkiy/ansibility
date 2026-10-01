package de.terletzkiy.ansibility.semantics.inventory

import de.terletzkiy.ansibility.semantics.testutil.YamlText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Expected results were produced with ansible-core 2.21.4's `InventoryManager.get_hosts` on the same inventory. */
class HostPatternTest {
    private val graph = YamlInventoryParser.parse(
        YamlText.parse(
            """
            all:
              hosts:
                lonely:
            web:
              hosts:
                web1:
                web2:
              children:
                web_eu:
                  hosts:
                    web3:
            db:
              hosts:
                db1:
                db2:
            replisync:
              hosts:
                db2:
                rep1:
            database_primary:
              hosts:
                db1:
            """.trimIndent(),
        ),
    )

    private fun hosts(pattern: String, limit: String? = null) = HostPattern.resolve(graph, pattern, limit).hosts

    @Test
    fun `split follows split_host_pattern`() {
        assertEquals(listOf("a", "b[1]", "c[2:3]", "d"), HostPattern.split("a,b[1], c[2:3] , d"))
        assertEquals(listOf("database", "replisync"), HostPattern.split("database:replisync"))
        assertEquals(listOf("web", "&db", "!x"), HostPattern.split("web:&db:!x"))
        assertEquals(listOf("10.0.0.1:22"), HostPattern.split("10.0.0.1:22"))
        assertEquals(listOf("::1"), HostPattern.split("::1"))
        assertEquals(listOf("web[1:3]"), HostPattern.split("web[1:3]"))
        assertEquals(listOf("~web.*", "x"), HostPattern.split("~web.*:x"))
        assertEquals(listOf("a", "b[1:2]", "c"), HostPattern.split("a:b[1:2]:c"))
        assertEquals(listOf("a", "b", "c"), HostPattern.split(listOf("a:b", "c")))
    }

    @Test
    fun `order puts intersections and exclusions last`() {
        assertEquals(listOf("c", "&b", "!a"), HostPattern.order(listOf("!a", "&b", "c")))
        assertEquals(listOf("all", "!a"), HostPattern.order(listOf("!a")))
    }

    @Test
    fun `all and star select every host in group order`() {
        val all = listOf("lonely", "web1", "web2", "db1", "db2", "rep1", "web3")
        assertEquals(all, hosts("all"))
        assertEquals(all, hosts("*"))
        assertEquals(listOf("lonely"), hosts("ungrouped"))
    }

    @Test
    fun `groups include their children, unions, intersections and exclusions`() {
        assertEquals(listOf("web1", "web2", "web3"), hosts("web"))
        assertEquals(listOf("db1", "db2", "rep1"), hosts("db:replisync"))
        assertEquals(listOf("db2"), hosts("db:&replisync"))
        assertEquals(listOf("db1"), hosts("db:!replisync"))
        assertEquals(listOf("lonely", "web1", "web2", "rep1", "web3"), hosts("!db"))
        assertEquals(listOf("web1", "web2", "web3"), hosts("&web"))
        assertEquals(listOf("web1", "db1"), hosts("web1,db1"))
        assertEquals(listOf("web1", "web2", "web3"), hosts("web:web1"))
    }

    @Test
    fun `wildcards and regular expressions match group and host names`() {
        assertEquals(listOf("web1", "web2", "web3"), hosts("web*"))
        assertEquals(listOf("web1", "web2", "web3"), hosts("web?"))
        assertEquals(listOf("web1", "db1"), hosts("[wd]*1"))
        assertEquals(listOf("db1", "db2"), hosts("~db\\d"))
        assertEquals(listOf("web1"), hosts("~(?P<x>web)1"))
        // '~[' splits to '~' (the bracket is not a complete expression), an empty regex matching everything.
        assertEquals(hosts("all"), hosts("~["))
    }

    @Test
    fun `subscripts select from the matched hosts`() {
        assertEquals(listOf("web1"), hosts("web[0]"))
        assertEquals(listOf("web3"), hosts("web[-1]"))
        assertEquals(listOf("web1", "web2"), hosts("web[0:1]"))
        assertEquals(listOf("web2", "web3"), hosts("web[1:]"))
        assertEquals(listOf("db1"), hosts("db[0:0]"))
        assertEquals(listOf("web2", "web3"), hosts("web[1-2]"))
        val outOfRange = HostPattern.resolve(graph, "web[5]")
        assertTrue(outOfRange.hosts.isEmpty())
        assertEquals(listOf("No hosts matched the subscripted pattern 'web[5]'"), outOfRange.errors)
    }

    @Test
    fun `unmatched patterns, implicit localhost and limits`() {
        val none = HostPattern.resolve(graph, "nomatch:web1")
        assertEquals(listOf("web1"), none.hosts)
        assertEquals(listOf("nomatch"), none.unmatched)

        val local = HostPattern.resolve(graph, "localhost")
        assertEquals(listOf("localhost"), local.hosts)
        assertEquals(setOf("localhost"), local.implicitHosts)

        val withLocal = YamlInventoryParser.parse(YamlText.parse("all:\n  hosts:\n    127.0.0.1:\n"))
        val resolved = HostPattern.resolve(withLocal, "localhost")
        assertEquals(listOf("127.0.0.1"), resolved.hosts)
        assertTrue(resolved.implicitHosts.isEmpty())

        assertEquals(listOf("web1", "web3"), hosts("all", limit = "web:!web2"))
        assertEquals(listOf("Limit files are not supported: @retry.txt"), HostPattern.resolve(graph, "all", "@retry.txt").errors)
    }

    @Test
    fun `invalid regular expressions are errors`() {
        val result = HostPattern.resolve(graph, "~web(")
        assertTrue(result.hosts.isEmpty())
        assertEquals(listOf("Invalid host list pattern: ~web("), result.errors)
    }
}
