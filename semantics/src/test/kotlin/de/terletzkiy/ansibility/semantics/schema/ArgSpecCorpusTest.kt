package de.terletzkiy.ansibility.semantics.schema

import de.terletzkiy.ansibility.semantics.testutil.YamlText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.io.File

/**
 * Opt-in: parses every role spec of the target repository (read-only) when `ANSIBLE_INFRA_REPO` points at it.
 * Skipped otherwise, so the build never depends on the repository.
 */
@EnabledIfEnvironmentVariable(named = "ANSIBLE_INFRA_REPO", matches = ".+")
class ArgSpecCorpusTest {
    private val repo = File(System.getenv("ANSIBLE_INFRA_REPO") ?: ".")

    private fun specFiles(): List<File> = repo.walkTopDown()
        .onEnter { dir -> dir.name !in setOf(".git", ".claude", ".ansible", "node_modules") }
        .filter { it.isFile && it.name == "argument_specs.yml" && it.parentFile.name == "meta" }
        .sortedBy { it.path }
        .toList()

    @Test
    fun `every role spec in the repository parses without issues`() {
        val files = specFiles()
        assertEquals(311, files.size, "role specs outside .claude/worktrees")
        var options = 0
        val problems = mutableListOf<String>()
        for (file in files) {
            val role = file.parentFile.parentFile.name
            val result = ArgSpecParser.parse(YamlText.parse(file.readText()), role)
            assertTrue(result.entryPoints.containsKey("main"), "${file.path}: main entry point")
            result.issues.forEach { problems += "${file.relativeTo(repo)}: ${it.path.joinToString(".")}: ${it.message}" }
            options += result.entryPoints.values.sumOf { count(it.options) }
        }
        println("parsed ${files.size} specs, $options options including nested ones")
        assertEquals(emptyList<String>(), problems)
        assertEquals(7284, options, "option count measured with PyYAML in the research")
    }

    private fun count(options: Map<String, OptionSpec>?): Int = options.orEmpty().values.sumOf { 1 + count(it.options) }
}
