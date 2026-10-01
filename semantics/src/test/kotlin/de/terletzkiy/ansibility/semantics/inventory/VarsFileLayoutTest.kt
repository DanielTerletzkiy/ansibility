package de.terletzkiy.ansibility.semantics.inventory

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class VarsFileLayoutTest {
    /** An in-memory tree: paths ending in `/` are directories. */
    private fun tree(vararg paths: String): DirectoryLister {
        val dirs = HashMap<String, MutableList<DirEntry>>()
        dirs[""] = ArrayList()
        for (path in paths) {
            val parts = path.trimEnd('/').split('/')
            var parent = ""
            parts.forEachIndexed { i, part ->
                val isDir = i < parts.size - 1 || path.endsWith("/")
                val full = if (parent.isEmpty()) part else "$parent/$part"
                val entries = dirs.getOrPut(parent) { ArrayList() }
                if (entries.none { it.name == part }) entries += DirEntry(part, isDir)
                if (isDir) dirs.getOrPut(full) { ArrayList() }
                parent = full
            }
        }
        return DirectoryLister { dirs[it]?.toList() }
    }

    @Test
    fun `a directory hides the file forms and loads recursively in sorted order`() {
        val base = tree(
            "group_vars/web.yml",
            "group_vars/web/20-b.yml",
            "group_vars/web/10-a.yaml",
            "group_vars/web/.hidden.yml",
            "group_vars/web/backup.yml~",
            "group_vars/web/notes.txt",
            "group_vars/web/noext",
            "group_vars/web/nested/x.json",
            "group_vars/web/skip.d/inside.yml",
            "group_vars/web/Zeta.yml",
        )
        assertEquals(
            listOf(
                "group_vars/web/10-a.yaml",
                "group_vars/web/20-b.yml",
                "group_vars/web/Zeta.yml",
                "group_vars/web/nested/x.json",
                "group_vars/web/noext",
            ),
            VarsFileLayout.findVarsFiles(base, VarsFileLayout.GROUP_VARS, "web"),
        )
    }

    @Test
    fun `the first existing candidate wins`() {
        val base = tree("group_vars/db.yaml", "group_vars/db.yml", "group_vars/db.json", "group_vars/cache", "host_vars/h1.json")
        assertEquals(listOf("group_vars/db.yml"), VarsFileLayout.findVarsFiles(base, "group_vars", "db"))
        assertEquals(listOf("group_vars/cache"), VarsFileLayout.findVarsFiles(base, "group_vars", "cache"))
        assertEquals(listOf("host_vars/h1.json"), VarsFileLayout.findVarsFiles(base, "host_vars", "h1"))
    }

    @Test
    fun `missing directories, unknown entities and chroot names load nothing`() {
        val base = tree("group_vars/all.yml")
        assertEquals(emptyList<String>(), VarsFileLayout.findVarsFiles(base, "host_vars", "h1"))
        assertEquals(emptyList<String>(), VarsFileLayout.findVarsFiles(base, "group_vars", "web"))
        assertEquals(emptyList<String>(), VarsFileLayout.findVarsFiles(base, "group_vars", "/chroot/all"))
        assertEquals(listOf("group_vars/all.yml"), VarsFileLayout.findVarsFiles(base, "group_vars", "all"))
    }

    @Test
    fun `host names with dots are directory names`() {
        val base = tree("host_vars/preview-dev1.example.test/vars.yml", "host_vars/preview-dev1.example.test/vault.yml")
        assertEquals(
            listOf("host_vars/preview-dev1.example.test/vars.yml", "host_vars/preview-dev1.example.test/vault.yml"),
            VarsFileLayout.findVarsFiles(base, "host_vars", "preview-dev1.example.test"),
        )
    }

    @Test
    fun `custom extensions`() {
        val base = tree("group_vars/web/a.yml", "group_vars/web/b.cfg")
        assertEquals(listOf("group_vars/web/b.cfg"), VarsFileLayout.findVarsFiles(base, "group_vars", "web", listOf(".cfg")))
    }
}
