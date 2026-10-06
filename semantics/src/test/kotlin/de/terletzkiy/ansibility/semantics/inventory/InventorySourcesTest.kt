package de.terletzkiy.ansibility.semantics.inventory

import de.terletzkiy.ansibility.semantics.CoreVersion
import de.terletzkiy.ansibility.semantics.precedence.InventoryVarSources
import de.terletzkiy.ansibility.semantics.precedence.PrecedenceEngine
import de.terletzkiy.ansibility.semantics.yaml.Resolved
import de.terletzkiy.ansibility.semantics.yaml.YScalar
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class InventorySourcesTest {
    private val v218 = CoreVersion(2, 18, 8)
    private val v221 = CoreVersion(2, 21, 4)

    private fun parse(
        files: Map<String, String>,
        sources: List<String>,
        core: CoreVersion? = null,
        executable: Set<String> = emptySet(),
        plugins: List<String> = InventoryParseOptions.DEFAULT_ENABLED_PLUGINS,
    ): InventoryParse<String> = InventorySources.parseFiles(
        sources,
        MemoryInventoryFiles(files, executable),
        InventoryParseOptions(core = core, enabledPlugins = plugins),
    )

    private fun InventoryParse<String>.inline(host: String, name: String): Any? {
        val view = PrecedenceEngine().inventoryView(graph, host, InventoryVarSources.inline(graph) { files[it].file ?: "" })!!
        return (view[name]?.value as YScalar?)?.resolved?.let { r ->
            when (r) {
                is Resolved.Int -> r.value.toInt()
                is Resolved.Str -> r.value
                else -> r
            }
        }
    }

    private fun InventoryParse<String>.statuses() = files.map { it.status }

    @Nested
    inner class MultipleSources {
        private val a = "[web]\nweb1:2201 x=from-a only_a=1\nshared1\n[web:vars]\ng=from-a-web-vars\n[db]\ndb1\n"
        private val b = "web:\n  hosts:\n    web1:\n      x: from-b\n      only_b: 1\n    web2:\n  vars:\n    g: from-b-web-vars\n  children:\n    db:\n"
        private val files = mapOf("p/a/hosts.ini" to a, "p/b/hosts.yml" to b)

        @Test
        fun `mixed formats merge in order and the later source wins per variable`() {
            val result = parse(files, listOf("p/a/hosts.ini", "p/b/hosts.yml"))
            assertEquals(listOf(InventoryFileFormat.INI, InventoryFileFormat.YAML), result.files.map { it.format })
            assertEquals(listOf("web1", "shared1", "web2"), result.graph.group("web")!!.hosts)
            assertEquals(listOf("db"), result.graph.group("web")!!.children)
            assertEquals("from-b", result.inline("web1", "x"))
            assertEquals(1, result.inline("web1", "only_a"))
            assertEquals(1, result.inline("web1", "only_b"))
            assertEquals("from-b-web-vars", result.inline("shared1", "g"))
            assertEquals(2201, result.inline("web1", "ansible_port"))
        }

        @Test
        fun `swapping the order swaps the winners and the INI port no longer applies`() {
            val result = parse(files, listOf("p/b/hosts.yml", "p/a/hosts.ini"))
            assertEquals(listOf("web1", "web2", "shared1"), result.graph.group("web")!!.hosts)
            assertEquals("from-a", result.inline("web1", "x"))
            assertEquals("from-a-web-vars", result.inline("web2", "g"))
            assertNull(result.inline("web1", "ansible_port"))
        }

        @Test
        fun `locations name the file they come from`() {
            val result = parse(files, listOf("p/a/hosts.ini", "p/b/hosts.yml"))
            val web1 = result.graph.host("web1")!!
            assertEquals(listOf("p/a/hosts.ini", "p/b/hosts.yml"), web1.definitions.map { result.fileOf(it)!!.file })
            assertEquals(listOf(0, 1), result.files.map { it.sourceIndex })
            assertTrue(result.parsed)
        }

        @Test
        fun `a source that fails half-way leaks into the inventory once another source parses`() {
            val files = mapOf("p/partial.ini" to "[early]\nearly1 kept=yes\n[bad section]\n", "p/good.ini" to "[demo]\ngood1\n")
            val alone = parse(files, listOf("p/partial.ini"))
            assertFalse(alone.parsed)
            assertEquals(setOf("all", "ungrouped"), alone.graph.groups.keys)
            assertEquals(listOf(FileStatus.FAILED), alone.statuses())
            assertEquals(3, alone.files.single().failures.single().line)

            val both = parse(files, listOf("p/partial.ini", "p/good.ini"))
            assertTrue(both.parsed)
            assertEquals(listOf("ungrouped", "early", "demo"), both.graph.group("all")!!.children)
            assertEquals("yes", both.inline("early1", "kept"))
            assertEquals(1, both.graph.problems.count { it.severity == ProblemSeverity.ERROR })
        }

        @Test
        fun `a missing source parses nothing and the others still load`() {
            val result = parse(mapOf("p/good.ini" to "[demo]\ngood1\n"), listOf("p/missing.ini", "p/good.ini"))
            assertEquals(listOf(SourceKind.MISSING, SourceKind.FILE), result.sources.map { it.kind })
            assertEquals(listOf(false, true), result.sources.map { it.parsed })
            assertEquals(listOf("good1"), result.graph.group("demo")!!.hosts)
        }

        @Test
        fun `the same source twice changes nothing`() {
            val once = parse(files, listOf("p/a/hosts.ini"))
            val twice = parse(files, listOf("p/a/hosts.ini", "p/a/hosts.ini"))
            assertEquals(once.graph.groups.mapValues { it.value.hosts }, twice.graph.groups.mapValues { it.value.hosts })
            assertEquals(once.inline("web1", "x"), twice.inline("web1", "x"))
        }
    }

    @Nested
    inner class PluginSelection {
        @Test
        fun `an extension-less INI file fails the yaml plugin silently and parses as INI`() {
            val files = MemoryInventoryFiles(mapOf("p/hosts" to "[demo]\nnoext src=hosts\n"))
            val result = InventorySources.parseFiles(listOf("p/hosts"), files)
            val file = result.files.single()
            assertEquals(FileStatus.PARSED, file.status)
            assertEquals(InventoryFileFormat.INI, file.format)
            assertEquals(listOf("yaml"), file.failures.map { it.plugin })
            assertTrue(result.graph.problems.none { it.severity == ProblemSeverity.ERROR })
            assertEquals(1, files.yamlLoads)
        }

        @Test
        fun `an extension-less YAML file and a JSON file parse as YAML`() {
            val result = parse(
                mapOf("p/inv" to "demo:\n  hosts:\n    y1:\n", "p/x.json" to "{\"demo\": {\"hosts\": {\"j1\": {}}}}\n"),
                listOf("p/inv", "p/x.json"),
            )
            assertEquals(listOf(InventoryFileFormat.YAML, InventoryFileFormat.YAML), result.files.map { it.format })
            assertEquals(listOf("y1", "j1"), result.graph.group("demo")!!.hosts)
        }

        @Test
        fun `a yml file with a root plugin key is a dynamic plugin configuration, never parsed`() {
            val files = MemoryInventoryFiles(mapOf("p/aws.yml" to "plugin: demo.cloud.hosts\nregions: [x]\n", "p/hosts.ini" to "[web]\nw1\n"))
            val result = InventorySources.parseFiles(listOf("p/aws.yml", "p/hosts.ini"), files)
            assertEquals(listOf(FileStatus.DYNAMIC, FileStatus.PARSED), result.statuses())
            assertEquals(InventoryFileFormat.PLUGIN_CONFIG, result.files[0].format)
            assertTrue(result.hasDynamicSources)
            assertNull(result.graph.group("regions"))
            assertEquals(1, files.yamlLoads)
        }

        @Test
        fun `a dynamic source alone still counts as parsed, so a failed source's groups are adopted`() {
            val result = parse(mapOf("p/aws.yaml" to "plugin: demo.cloud.hosts\n", "p/partial.ini" to "[early]\ne1\n[bad section]\n"), listOf("p/partial.ini", "p/aws.yaml"))
            assertTrue(result.parsed)
            assertEquals(listOf("e1"), result.graph.group("early")!!.hosts)
        }

        @Test
        fun `an executable script with a shebang is dynamic, an executable static file still parses`() {
            val files = mapOf("p/inv.py" to "#!/usr/bin/env python3\nprint('{}')\n", "p/exec.ini" to "[web]\nw1\n")
            val result = parse(files, listOf("p/inv.py", "p/exec.ini"), executable = setOf("p/inv.py", "p/exec.ini"))
            assertEquals(listOf(FileStatus.DYNAMIC, FileStatus.PARSED), result.statuses())
            assertEquals(InventoryFileFormat.SCRIPT, result.files[0].format)
            assertEquals(listOf("script"), result.files[1].failures.map { it.plugin })
            assertEquals(listOf("w1"), result.graph.group("web")!!.hosts)
        }

        @Test
        fun `before 2_19 a shebang alone makes the script plugin try, and fail, first`() {
            val files = mapOf("p/noexec.sh" to "#!/bin/sh\n# [web]\n")
            val legacy = parse(files, listOf("p/noexec.sh"), v218).files.single()
            assertEquals("script", legacy.failures.first().plugin)
            assertTrue("Permission denied" in legacy.failures.first().message)
            assertEquals(FileStatus.PARSED, legacy.status) // only a comment after the shebang: an empty INI inventory
            val modern = parse(files, listOf("p/noexec.sh"), v221).files.single()
            assertTrue(modern.failures.none { it.plugin == "script" })
        }

        @Test
        fun `TOML is not modelled, and before 2_19 the ini plugin tries it first and leaks a group`() {
            val toml = "[demo.hosts.toml1]\nansible_host = \"192.0.2.9\"\n"
            val modern = parse(mapOf("p/h.toml" to toml), listOf("p/h.toml"), v221)
            assertEquals(FileStatus.UNSUPPORTED, modern.files.single().status)
            assertEquals(InventoryFileFormat.TOML, modern.files.single().format)
            assertTrue(modern.files.single().failures.isEmpty())
            assertNull(modern.graph.group("demo.hosts.toml1"))

            val legacy = parse(mapOf("p/h.toml" to toml), listOf("p/h.toml"), v218)
            assertEquals(FileStatus.UNSUPPORTED, legacy.files.single().status)
            assertEquals(listOf("ini"), legacy.files.single().failures.map { it.plugin })
            assertTrue(legacy.graph.group("demo.hosts.toml1") != null)
        }

        @Test
        fun `vault-encrypted files are not evaluated`() {
            val result = parse(mapOf("p/hosts.yml" to "\$ANSIBLE_VAULT;1.1;AES256\n6162\n"), listOf("p/hosts.yml"))
            assertEquals(FileStatus.ENCRYPTED, result.files.single().status)
            assertEquals(InventoryFileFormat.YAML, result.files.single().format)
            assertTrue(result.parsed)
            assertEquals(setOf("all", "ungrouped"), result.graph.groups.keys)
        }

        @Test
        fun `an empty yml file is parsed by the ini plugin as an empty inventory`() {
            val result = parse(mapOf("p/empty.yml" to "# nothing\n"), listOf("p/empty.yml"))
            assertEquals(FileStatus.PARSED, result.files.single().status)
            assertEquals(InventoryFileFormat.INI, result.files.single().format)
            assertEquals(listOf("auto", "yaml"), result.files.single().failures.map { it.plugin })
        }

        @Test
        fun `YAML in an ini file fails and a broken YAML file reports its YAML error only`() {
            val ini = parse(mapOf("p/y.ini" to "all:\n  hosts:\n    h1:\n"), listOf("p/y.ini"))
            assertEquals(FileStatus.FAILED, ini.files.single().status)
            assertEquals(InventoryFileFormat.INI, ini.files.single().format)

            val text = "web:\n  hosts:\n    h1:\nbad:\n  vars: [1]\n"
            val yaml = parse(mapOf("p/b.yml" to text, "p/good.ini" to "[x]\nx1\n"), listOf("p/b.yml", "p/good.ini"))
            val file = yaml.files[0]
            assertEquals(FileStatus.FAILED, file.status)
            assertEquals(InventoryFileFormat.YAML, file.format)
            assertEquals(listOf("auto", "yaml", "ini"), file.failures.map { it.plugin })
            val errors = yaml.graph.problems.filter { it.severity == ProblemSeverity.ERROR }
            assertEquals(listOf("Invalid \"vars\" entry for \"bad\" group, requires a dictionary, found \"list\" instead."), errors.map { it.message })
            assertEquals(5, file.failures[1].line)
            // ansible-core keeps what the yaml plugin added before the error.
            assertEquals(listOf("h1"), yaml.graph.group("web")!!.hosts)
            assertTrue(yaml.graph.group("bad") != null)
        }

        @Test
        fun `the enabled plugins and their order decide, FQCNs included`() {
            val files = mapOf("p/hosts" to "[demo]\nh1\n", "p/hosts.yml" to "demo:\n  hosts:\n    y1:\n")
            assertEquals(FileStatus.FAILED, parse(files, listOf("p/hosts"), plugins = listOf("yaml")).files.single().status)
            assertEquals(FileStatus.PARSED, parse(files, listOf("p/hosts"), plugins = listOf("ansible.builtin.ini")).files.single().status)
            val iniOnly = parse(files, listOf("p/hosts.yml"), plugins = listOf("host_list", "ini"))
            assertEquals(FileStatus.FAILED, iniOnly.files.single().status)
            val custom = parse(mapOf("p/c.yml" to "plugin: demo.cloud.hosts\n"), listOf("p/c.yml"), plugins = listOf("demo.cloud.hosts"))
            assertEquals(FileStatus.DYNAMIC, custom.files.single().status)
        }

        @Test
        fun `an unreadable file is unparsed`() {
            val fs = object : InventoryFileSystem<String> by MemoryInventoryFiles(mapOf("p/x.ini" to "")) {
                override fun text(file: String): String? = null
            }
            val result = InventorySources.parseFiles(listOf("p/x.ini"), fs)
            assertEquals(FileStatus.UNPARSED, result.files.single().status)
            assertFalse(result.parsed)
        }
    }

    @Nested
    inner class HostLists {
        @Test
        fun `a host list adds ungrouped hosts with ports and skips repeated hosts`() {
            val result = InventorySources.parseAll(
                listOf(SourceSpec.HostList("web1.example.test:2222, [2001:db8::1]:2201,web[1:3].example.test,web1.example.test,")),
                MemoryInventoryFiles(emptyMap()),
            )
            assertEquals(listOf("web1.example.test", "2001:db8::1", "web[1:3].example.test"), result.graph.group("ungrouped")!!.hosts)
            assertEquals(2222, result.inline("web1.example.test", "ansible_port"))
            assertEquals(2201, result.inline("2001:db8::1", "ansible_port"))
            assertEquals(InventoryFileFormat.HOST_LIST, result.files.single().format)
            assertNull(result.files.single().file)
            val definition = result.graph.host("2001:db8::1")!!.definitions.single().range!!
            assertEquals(" [2001:db8::1]:2201".trim(), "web1.example.test:2222, [2001:db8::1]:2201".substring(definition.start, definition.end))
        }
    }

    @Nested
    inner class Directories {
        private val files = mapOf(
            "p/inv/05-dup.ini" to "[demo]\ndup v=from-05\n",
            "p/inv/20-more.yml" to "demo:\n  hosts:\n    yml1:\n",
            "p/inv/50-dup.yml" to "demo:\n  hosts:\n    dup:\n      v: from-50\n",
            "p/inv/Zeta" to "[demo]\nzeta\n",
            "p/inv/README.md" to "[demo]\nmd\n",
            "p/inv/.hidden" to "[demo]\nhidden\n",
            "p/inv/nested/40-nested.ini" to "[demo]\nnested\n",
            "p/inv/nested/41-nested.yml" to "demo:\n  hosts:\n    nested-yml:\n",
            "p/inv/group_vars/all.yml" to "x: 1\n",
        )

        @Test
        fun `a directory is read in sorted byte order, recursing in place`() {
            val result = parse(files, listOf("p/inv"), v221)
            val outcome = result.sources.single()
            assertEquals(SourceKind.DIRECTORY, outcome.kind)
            assertEquals(
                listOf("p/inv/05-dup.ini", "p/inv/20-more.yml", "p/inv/50-dup.yml", "p/inv/Zeta", "p/inv/nested/40-nested.ini", "p/inv/nested/41-nested.yml"),
                outcome.files.map { it.file },
            )
            assertEquals(listOf("dup", "yml1", "zeta", "nested", "nested-yml"), result.graph.group("demo")!!.hosts)
            assertEquals("from-50", result.inline("dup", "v"))
            assertEquals(setOf("p/inv/.hidden", "p/inv/README.md", "p/inv/group_vars"), outcome.skipped.map { it.file }.toSet())
            assertTrue(result.files.all { it.sourceIndex == 0 })
        }

        @Test
        fun `before 2_19 ini files in a directory are skipped and an ini-only directory parses nothing`() {
            val result = parse(files, listOf("p/inv"), v218)
            assertEquals(listOf("yml1", "dup", "zeta", "nested-yml"), result.graph.group("demo")!!.hosts)
            assertTrue(result.sources.single().skipped.any { it.file == "p/inv/05-dup.ini" && it.rule == ".ini" })

            val iniOnly = parse(mapOf("p/d/hosts.ini" to "[web]\nw1\n"), listOf("p/d"), v218)
            assertFalse(iniOnly.parsed)
            assertTrue(iniOnly.files.isEmpty())
            val explicit = parse(mapOf("p/d/hosts.ini" to "[web]\nw1\n"), listOf("p/d/hosts.ini"), v218)
            assertTrue(explicit.parsed)
        }

        @Test
        fun `a file given as a source is read whatever its extension`() {
            val result = parse(files, listOf("p/inv/README.md"), v221)
            assertEquals(listOf("md"), result.graph.group("demo")!!.hosts)
        }
    }
}
