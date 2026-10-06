package de.terletzkiy.ansibility.semantics.inventory

import de.terletzkiy.ansibility.semantics.CoreVersion
import de.terletzkiy.ansibility.semantics.inventory.InventoryDirectoryWalk.Options
import de.terletzkiy.ansibility.semantics.inventory.InventoryDirectoryWalk.SkipReason
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class InventoryDirectoryWalkTest {
    private val tree = MemoryInventoryFiles(
        listOf(
            "inv/b.yml", "inv/A.yml", "inv/Zeta", "inv/alpha", "inv/10-static.ini", "inv/x.ini.disabled", "inv/README.md",
            "inv/notes.txt", "inv/old.bak", "inv/x.retry", "inv/x.cfg", "inv/x.pyc", "inv/x.orig", "inv/x~", "inv/.hidden",
            "inv/.git/config", "inv/group_vars/all.yml", "inv/host_vars/h.yml", "inv/vars_plugins/x.ini", "inv/sub/c.yml",
            "inv/sub/deeper/d.yml", "inv/conf.d/e.yml", "inv/dir.ini/f.yml",
        ).associateWith { "" },
    )

    private fun names(walk: InventoryDirectoryWalk.Walk<String>) = walk.files.map { it.removePrefix("inv/") }

    @Test
    fun `entries are read in code point order with subdirectories recursed in place`() {
        val walk = InventoryDirectoryWalk.walk("inv", tree, Options(CoreVersion(2, 21, 4)))
        assertEquals(
            listOf(
                "10-static.ini", "A.yml", "Zeta", "alpha", "b.yml", "conf.d/e.yml", "dir.ini/f.yml", "sub/c.yml", "sub/deeper/d.yml",
                "x.ini.disabled",
            ),
            names(walk),
        )
    }

    @Test
    fun `hidden entries and vars directories are always skipped`() {
        val skipped = InventoryDirectoryWalk.walk("inv", tree, Options(ignoreExtensions = emptyList())).skipped
        val always = skipped.filter { it.reason == SkipReason.ALWAYS }.map { it.file.removePrefix("inv/") }.toSet()
        assertEquals(setOf(".git", ".hidden", "group_vars", "host_vars", "vars_plugins"), always)
    }

    @Test
    fun `before 2_19 the default list also skips ini files and directories`() {
        val legacy = InventoryDirectoryWalk.walk("inv", tree, Options(CoreVersion(2, 18, 8)))
        assertEquals(listOf("A.yml", "Zeta", "alpha", "b.yml", "conf.d/e.yml", "sub/c.yml", "sub/deeper/d.yml", "x.ini.disabled"), names(legacy))
        val ini = legacy.skipped.filter { it.rule == ".ini" }.map { it.file }
        assertEquals(listOf("inv/10-static.ini", "inv/dir.ini"), ini)
        assertEquals(InventoryDirectoryWalk.defaultIgnoreExtensions(CoreVersion(2, 18, 19)), InventoryDirectoryWalk.defaultIgnoreExtensions(CoreVersion(2, 18, 8)))
        assertTrue(".ini" in InventoryDirectoryWalk.defaultIgnoreExtensions(CoreVersion(2, 18, 19)))
        assertFalse(".ini" in InventoryDirectoryWalk.defaultIgnoreExtensions(CoreVersion(2, 19, 0)))
        assertFalse(".ini" in InventoryDirectoryWalk.defaultIgnoreExtensions(null))
    }

    @Test
    fun `the default list skips backups, docs and retry files by suffix`() {
        val skipped = InventoryDirectoryWalk.walk("inv", tree, Options(CoreVersion(2, 21, 4))).skipped
            .filter { it.reason == SkipReason.EXTENSION }.associate { it.file.removePrefix("inv/") to it.rule }
        assertEquals(
            mapOf("README.md" to ".md", "notes.txt" to ".txt", "old.bak" to ".bak", "x.retry" to ".retry", "x.cfg" to ".cfg", "x.pyc" to ".pyc", "x.orig" to ".orig", "x~" to "~"),
            skipped,
        )
    }

    @Test
    fun `an explicit extension list replaces the default on every version`() {
        val custom = Options(CoreVersion(2, 18, 8), ignoreExtensions = listOf(".md", ".bak"))
        val walk = InventoryDirectoryWalk.walk("inv", tree, custom)
        assertTrue("10-static.ini" in names(walk) && "notes.txt" in names(walk) && "x.retry" in names(walk))
        assertFalse("README.md" in names(walk) || "old.bak" in names(walk))
        // An empty list reads every file except the always-skipped ones.
        assertEquals(18, InventoryDirectoryWalk.walk("inv", tree, Options(ignoreExtensions = emptyList())).files.size)
    }

    @Test
    fun `ignore patterns are searched anywhere in the name, and an invalid one is reported`() {
        val options = Options(ignorePatterns = listOf("dup|^x\\.", "("))
        assertEquals(listOf("("), options.invalidPatterns)
        val walk = InventoryDirectoryWalk.walk("inv", tree, options)
        assertFalse(names(walk).any { it.startsWith("x.") })
        assertEquals(SkipReason.PATTERN, options.skipReason("05-dup.ini")!!.first)
        assertEquals(SkipReason.PATTERN, options.skipReason("50-dup.yml")!!.first)
        assertEquals(null, options.skipReason("hosts"))
    }

    @Test
    fun `a walk stops descending at the depth limit`() {
        val deep = MemoryInventoryFiles(mapOf(("d/" + "s/".repeat(InventoryDirectoryWalk.MAX_DEPTH + 3) + "h.yml") to "", "d/top.yml" to ""))
        assertEquals(listOf("d/top.yml"), InventoryDirectoryWalk.walk("d", deep).files)
    }
}
