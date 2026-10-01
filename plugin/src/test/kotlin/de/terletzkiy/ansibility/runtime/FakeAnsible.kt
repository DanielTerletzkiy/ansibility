package de.terletzkiy.ansibility.runtime

import de.terletzkiy.ansibility.semantics.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A fake local Ansible install for tests: dummy `ansible`/`ansible-doc` executables in [bin] (never run), a
 * collections tree, and a [ProcessRunner] that answers `ansible --version` and `ansible-doc -t module -j …` from
 * canned data and records every command.
 */
class FakeAnsible(val home: Path, var core: String = "2.21.4") : ProcessRunner {
    val bin: Path = Files.createDirectories(home.resolve("bin"))
    val sitePackages: Path = Files.createDirectories(home.resolve("site-packages"))
    val collections: Path = Files.createDirectories(home.resolve("collections"))
    val calls: MutableList<ToolCommand> = CopyOnWriteArrayList()

    /** `ansible-doc -j` entries by FQCN. */
    val docs: MutableMap<String, Any?> = LinkedHashMap()

    /** When set, `ansible-doc` fails for any call with more than one name. */
    @Volatile
    var failMultiNameBatches: Boolean = false

    init {
        for (tool in AnsibleTool.entries) {
            val file = bin.resolve(tool.executableName)
            Files.writeString(file, "#!/bin/sh\nexit 99\n")
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"))
        }
        Files.createDirectories(sitePackages.resolve("ansible"))
    }

    val ansible: Path get() = bin.resolve("ansible")

    /** Adds `<collections>/ansible_collections/<ns>/<name>/MANIFEST.json`. */
    fun installCollection(name: String, version: String, into: Path = collections) {
        val (namespace, collection) = name.split('.', limit = 2)
        val dir = Files.createDirectories(into.resolve("ansible_collections").resolve(namespace).resolve(collection))
        Files.writeString(dir.resolve("MANIFEST.json"), """{"collection_info": {"namespace": "$namespace", "name": "$collection", "version": "$version"}}""")
    }

    /** Adds a minimal module doc for [fqcn]. */
    fun addModule(fqcn: String, shortDescription: String, options: Map<String, Any?> = mapOf("name" to mapOf("type" to "str", "required" to true, "description" to "The name."))) {
        docs[fqcn] = mapOf(
            "doc" to mapOf(
                "module" to fqcn.substringAfterLast('.'),
                "collection" to fqcn.substringBeforeLast('.'),
                "short_description" to shortDescription,
                "description" to listOf("$shortDescription."),
                "options" to options,
                "filename" to "$sitePackages/ansible_collections/${fqcn.replace('.', '/')}.py",
            ),
            "examples" to "- $fqcn:\n    name: x\n",
            "return" to null,
        )
    }

    fun versionOutput(): String = """
        ansible [core $core]
          config file = None
          configured module search path = ['$home/plugins/modules']
          ansible python module location = $sitePackages/ansible
          ansible collection location = $collections
          executable location = $ansible
          python version = 3.14.7 (main) [Clang] ($home/bin/python)
          jinja version = 3.1.6
    """.trimIndent() + "\n"

    /** The `ansible-doc -t module -j` calls, as their module lists. */
    fun docCalls(): List<List<String>> = calls.filter { it.arguments.take(3) == listOf("-t", "module", "-j") }.map { it.arguments.drop(3) }

    fun versionCalls(): Int = calls.count { it.arguments == listOf("--version") }

    override suspend fun run(command: ToolCommand): ToolResult {
        calls += command
        val args = command.arguments
        return when {
            args == listOf("--version") -> ToolResult(0, versionOutput(), "")
            args.take(3) == listOf("-t", "module", "-j") -> {
                val names = args.drop(3)
                if (failMultiNameBatches && names.size > 1) return ToolResult(1, "", "ERROR! a module failed to load")
                val found = names.filter { it in docs }.associateWith { docs[it] }
                val warnings = names.filter { it !in docs }.joinToString("\n") { "[WARNING]: $it was not found" }
                ToolResult(0, Json.write(found), warnings)
            }
            else -> ToolResult(2, "", "unexpected command $args")
        }
    }
}
