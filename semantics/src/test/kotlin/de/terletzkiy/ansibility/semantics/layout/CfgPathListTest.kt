package de.terletzkiy.ansibility.semantics.layout

import de.terletzkiy.ansibility.semantics.CoreVersion
import de.terletzkiy.ansibility.semantics.coerce.PathEnvironment
import de.terletzkiy.ansibility.semantics.layout.LayoutRulesData.Record
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DynamicContainer
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

/**
 * [CfgPathList] beyond `inventory`: `probe-cfg-paths` pins `roles_path` and `collections_path[s]` (pathspecs: split at
 * `:`, not stripped), `playbook_dir` (a path), more `inventory` spellings and the ignore-extension lists, each
 * against `ansible-config dump` on 2.18.8 and 2.21.4. Then the D53 policy and the hints.
 */
class CfgPathListTest {
    private val probe = LayoutRulesData.case("probe-cfg-paths")

    @TestFactory
    fun `probe records`(): List<DynamicContainer> = LayoutRulesData.VERSIONS.map { version ->
        DynamicContainer.dynamicContainer(version, probe.records(version).map { record ->
            DynamicTest.dynamicTest(record.run) { check(record) }
        })
    }

    private fun check(record: Record) {
        assertEquals(0, record.rc, "$record")
        val read = CfgSyntax.read(record.cfgText())
        assertTrue(read.isValid, "$record: ${read.errors}")
        val doc = read.document
        val context = CfgPathContext(record.cfgDir, LayoutRulesData.PROJECT, record.followEnvironment())
        fun value(key: String) = doc.value(CfgPathList.SECTION, key)
        fun expectedPaths(name: String): List<String>? =
            record.setting(name)?.takeIf(record::fromCfg)?.strings?.map(record::absolute)

        assertEquals(expectedPaths("DEFAULT_ROLES_PATH"), value(CfgPathList.ROLES_PATH)?.let { v ->
            CfgPathList.rolesPath(v, context).map { it.path }
        }, "$record: roles_path")

        val collections = CfgPathList.collectionsPath(
            value(CfgPathList.COLLECTIONS_PATH), value(CfgPathList.LEGACY_COLLECTIONS_PATHS), record.core, context,
        )
        assertEquals(expectedPaths("COLLECTIONS_PATHS"), collections?.entries?.map { it.path }, "$record: collections")

        val playbookDir = record.setting("PLAYBOOK_DIR")?.takeIf(record::fromCfg)?.let { record.absolute(it.value as String) }
        val ourPlaybookDir = value(CfgPathList.PLAYBOOK_DIR)?.let { CfgPathList.playbookDir(it, context).path }
        assertEquals(playbookDir, ourPlaybookDir, "$record: playbook_dir")

        assertEquals(expectedPaths("DEFAULT_HOST_LIST"), value(CfgPathList.INVENTORY)?.let { v ->
            CfgPathList.inventory(v, context).map { it.path }
        }, "$record: inventory")

        val ignore = IgnoreExtensions.resolve(
            value(IgnoreExtensions.DEFAULTS_KEY),
            doc.value(IgnoreExtensions.INVENTORY_SECTION, IgnoreExtensions.INVENTORY_KEY),
            record.core,
        )
        assertEquals(record.setting("INVENTORY_IGNORE_EXTS")!!.strings, ignore, "$record: ignore extensions")
    }

    @Test
    fun `collections_path wins and collections_paths counts only below 2_19`() {
        val context = CfgPathContext("/p")
        val old = CoreVersion(2, 18, 8)
        val new = CoreVersion(2, 19, 0)
        assertEquals(
            CollectionsPath("collections_path", listOf(path("s", "/p/s"))),
            CfgPathList.collectionsPath("s", "plural", old, context),
        )
        assertEquals(
            CollectionsPath("collections_paths", listOf(path("plural", "/p/plural"))),
            CfgPathList.collectionsPath(null, "plural", old, context),
        )
        assertNull(CfgPathList.collectionsPath(null, "plural", new, context))
        assertNull(CfgPathList.collectionsPath(null, null, old, context))
    }

    @Test
    fun `tilde and variables are not followed by default`() {
        val value = "~/inv/hosts.ini, \$INVDIR/hosts.ini, \${INVDIR}/x, ~, a/~/b.ini, \$1"
        val entries = CfgPathList.inventory(value, CfgPathContext("/p/cfg"))
        assertEquals(
            listOf(
                setOf(MachineDependence.HOME), setOf(MachineDependence.ENVIRONMENT), setOf(MachineDependence.ENVIRONMENT),
                setOf(MachineDependence.HOME), emptySet(), setOf(MachineDependence.ENVIRONMENT),
            ),
            entries.map { it.dependsOn },
        )
        assertEquals(listOf(null, null, null, null, "/p/cfg/a/~/b.ini", null), entries.map { it.path })
        assertEquals(listOf(false, false, false, false, true, false), entries.map { it.isFollowed })
    }

    @Test
    fun `the follow switch expands variables before the tilde, against the given environment only`() {
        val follow = PathEnvironment(mapOf("TILDE" to "~/t", "INVDIR" to "/p/sub"), home = "/h")
        val entries = CfgPathList.inventory("\$TILDE/x.ini, \$INVDIR/hosts.ini, \$NOPE/y, ~/z", CfgPathContext("/p/cfg", "/p", follow))
        assertEquals(listOf("/h/t/x.ini", "/p/sub/hosts.ini", "/p/cfg/\$NOPE/y", "/h/z"), entries.map { it.path })
        assertTrue(entries.all { it.isFollowed })
        assertEquals(
            setOf(MachineDependence.ENVIRONMENT, MachineDependence.HOME, MachineDependence.OUTSIDE_PROJECT),
            entries[0].dependsOn,
        )
        assertEquals(setOf(MachineDependence.ENVIRONMENT), entries[1].dependsOn)
    }

    @Test
    fun `paths outside the project are resolved but not followed without the switch`() {
        val context = CfgPathContext("/p/cfg", projectDir = "/p")
        val (outside, parent, absolute, inside) = CfgPathList.inventory("../../x.ini, ../hosts.ini, /srv/inv, {{CWD}}/h", context)
        assertEquals("/x.ini", outside.path)
        assertEquals(setOf(MachineDependence.OUTSIDE_PROJECT), outside.dependsOn)
        assertFalse(outside.isFollowed)
        assertEquals("/p/hosts.ini", parent.path)
        assertTrue(parent.isFollowed, "inside the project, outside the cfg dir: followed")
        assertFalse(absolute.isFollowed)
        assertEquals("/p/cfg/h", inside.path)
        assertTrue(inside.isFollowed, "{{CWD}} is the cfg directory")
        val followed = CfgPathList.inventory("../../x.ini", CfgPathContext("/p/cfg", "/p", PathEnvironment.EMPTY)).single()
        assertTrue(followed.isFollowed)
        assertEquals(setOf(MachineDependence.OUTSIDE_PROJECT), followed.dependsOn)
    }

    @Test
    fun `the rules never read the process environment`() {
        assumeTrue(System.getenv("PATH") != null && System.getenv("HOME") != null, "the test JVM has PATH and HOME")
        val value = "\$PATH/x, \${HOME}/y, ~/z"
        val unfollowed = CfgPathList.inventory(value, CfgPathContext("/p"))
        assertTrue(unfollowed.none { it.isFollowed || it.path != null }, "$unfollowed")
        val emptyEnvironment = CfgPathList.inventory(value, CfgPathContext("/p", follow = PathEnvironment.EMPTY))
        assertEquals(listOf("/p/\$PATH/x", "/p/\${HOME}/y", "/p/~/z"), emptyEnvironment.map { it.path })
        val roles = CfgPathList.rolesPath("\$PATH", CfgPathContext("/p", follow = PathEnvironment.EMPTY))
        assertEquals(listOf("/p/\$PATH"), roles.map { it.path })
    }

    @Test
    fun `inventory entries are stripped, pathspec entries are not`() {
        val context = CfgPathContext("/p")
        assertEquals(listOf("/p/a", "/p/b"), CfgPathList.inventory("  a ,\tb  ", context).map { it.path })
        val roles = CfgPathList.rolesPath("roles : other:", context)
        assertEquals(listOf("/p/roles ", "/p/ other", "/p"), roles.map { it.path })
        assertEquals(listOf(setOf(EntryHint.WHITESPACE), setOf(EntryHint.WHITESPACE), setOf(EntryHint.CFG_DIR)), roles.map { it.hints })
        assertEquals(listOf("/p/a,b"), CfgPathList.rolesPath("a,b", context).map { it.path })
        assertEquals(listOf("/p/hosts.ini:extra.yml"), CfgPathList.inventory("hosts.ini:extra.yml", context).map { it.path })
    }

    @Test
    fun `quotes and backslashes do not escape path-list separators`() {
        val context = CfgPathContext("/p")
        assertEquals(
            listOf("/p/a\\", "/p/b", "/p/\"c", "/p/d\"", "/p/x=y # kept"),
            CfgPathList.inventory("""a\,b, "c,d", x=y # kept""", context).map { it.path },
        )
        assertEquals(
            listOf("/p/roles\\", "/p/escaped", "/p/\"quoted", "/p/part\"", "/p/with space"),
            CfgPathList.rolesPath("""roles\:escaped:"quoted:part":with space""", context).map { it.path },
        )
    }

    @Test
    fun `the cfg directory as a source`() {
        val context = CfgPathContext("/p/demo/")
        for (value in listOf("", ".", "./", "{{CWD}}", "{{CWD}}/")) {
            val entry = CfgPathList.inventory(value, context).single()
            assertEquals("/p/demo", entry.path, value)
            assertEquals(setOf(EntryHint.CFG_DIR), entry.hints, value)
        }
        assertEquals(listOf("/p/demo", "/p/demo"), CfgPathList.inventory(",", context).map { it.path })
        assertEquals("/p/demo", CfgPathList.playbookDir("", context).path)
    }

    @Test
    fun `host list hints need a comma and a host-like entry`() {
        val context = CfgPathContext("/p")
        fun hostList(value: String) = CfgPathList.inventory(value, context).map { EntryHint.HOST_LIST in it.hints }
        assertEquals(listOf(true, true), hostList("web1.example.test,web2.example.test"))
        assertEquals(listOf(true, false), hostList("localhost,"))
        assertEquals(listOf(true, true, true), hostList("192.0.2.10, [2001:db8::1]:2201, db1:2222"))
        assertEquals(listOf(false), hostList("web1.example.test"))
        assertEquals(listOf(false, false, false, false), hostList("hosts.ini, extra/, ./web1, prod.yml"))
        assertEquals(listOf(false, false), hostList("~/web1, \$H"))
    }

    @Test
    fun `comment, quote and line break hints`() {
        val context = CfgPathContext("/p")
        val (hash, leading, glued, quoted, multi) = CfgPathList.inventory("hosts.ini # local, #extra, a#b, 'x.ini', a.ini\nb.ini", context)
        assertEquals(setOf(EntryHint.HASH_COMMENT), hash.hints)
        assertEquals(10, hash.commentStart)
        assertEquals(0, leading.commentStart)
        assertEquals(-1, glued.commentStart)
        assertEquals(emptySet<EntryHint>(), glued.hints)
        assertEquals(setOf(EntryHint.QUOTED), quoted.hints)
        assertEquals("/p/'x.ini'", quoted.path)
        assertEquals(setOf(EntryHint.LINE_BREAK), multi.hints)
        assertNotNull(multi.path)
    }

    private fun path(text: String, resolved: String) =
        CfgPath(text, resolved, emptySet(), isFollowed = true, hints = emptySet())
}
