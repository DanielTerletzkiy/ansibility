package de.terletzkiy.ansibility.semantics.vault

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.nodes.MappingNode
import org.yaml.snakeyaml.nodes.Node
import org.yaml.snakeyaml.nodes.ScalarNode
import org.yaml.snakeyaml.nodes.SequenceNode
import java.io.File
import java.io.StringReader

/**
 * Opt-in, **structural only** (M4.5 acceptance 9, the codec's part): when `ANSIBLE_INFRA_REPO` points at the target
 * repository (read-only), every inline `!vault` value of its YAML files is located and its envelope parsed, and the
 * layout detector runs per file. Nothing is ever decrypted: no secret exists in this test, and only YAML files are
 * opened (never `.vault-pass`, `.env*`, `*.password` or key files). Messages carry paths, lines and counts only.
 *
 * Baseline (re-pinned 2026-10-01 on the current checkout): 868 values in 86 files, all `1.1`; relative indents
 * +2 = 865, +10 = 3; the detector picks +2 in 85 files and +10 in the one file of pasted `encrypt_string` blocks. One
 * value is malformed (a hex digit missing, odd length), which ansible-core 2.21.4's own parser rejects the same way;
 * using that variable would fail at run time (ANS-V101). The earlier tern value with stray letters has been fixed.
 */
@EnabledIfEnvironmentVariable(named = "ANSIBLE_INFRA_REPO", matches = ".+")
class VaultCorpusStructureTest {
    private val repo = File(System.getenv("ANSIBLE_INFRA_REPO") ?: ".")

    private fun yamlFiles(): List<File> = repo.walkTopDown()
        .onEnter { dir -> dir.name !in setOf(".git", ".claude", ".idea", ".ansible", "node_modules") }
        .filter { it.isFile && (it.name.endsWith(".yml") || it.name.endsWith(".yaml")) && !it.name.startsWith(".env") }
        .sortedBy { it.path }
        .toList()

    @Test
    fun `every inline vault of the repository is well formed and laid out in the repo's style`() {
        var files = 0
        var headers = 0
        val relative = sortedMapOf<Int, Int>()
        val problems = mutableListOf<String>()
        val malformed = mutableListOf<String>()
        val styles = sortedMapOf<String, Int>()
        val detected = sortedMapOf<Int, Int>()
        for (file in yamlFiles()) {
            val text = file.readText()
            if (VaultEnvelope.MAGIC !in text) continue
            val where = file.relativeTo(repo).path
            val headerLines = text.lineSequence().count { it.trimStart().startsWith("${VaultEnvelope.MAGIC};") }
            val blocks = VaultLayout.blocks(text).filter { it.hasHeader }
            if (blocks.size != headerLines) problems += "$where: ${blocks.size} blocks for $headerLines header lines"
            if (blocks.isEmpty()) continue
            files++
            headers += blocks.size
            blocks.forEach { relative.merge(it.relativeIndent, 1, Int::plus) }
            VaultLayout.detectRelativeIndent(text)?.let { detected.merge(it, 1, Int::plus) }
            for ((line, scalar) in vaultScalars(text)) {
                when (val parsed = VaultEnvelope.parse(scalar)) {
                    is EnvelopeParse.Ok -> styles.merge(parsed.envelope.headerLine(), 1, Int::plus)
                    else -> malformed += "$where:${line + 1}: $parsed"
                }
            }
        }
        println("inline vaults: $headers in $files files; relative indents $relative; detected per file $detected; headers $styles")
        assertEquals(emptyList<String>(), problems, "every header line belongs to a detected block")
        assertEquals(86, files, "files with inline vaults (vault-core §6.1)")
        assertEquals(868, headers, "inline vault headers (vault-core §6.1)")
        assertEquals(mapOf(2 to 865, 10 to 3), relative.toMap(), "body indent relative to the key")
        assertEquals(mapOf(2 to 85, 10 to 1), detected.toMap(), "the style the detector picks per file")
        assertEquals(
            listOf("repos/wren/ansible/group_vars/all/vault.yml:158: Format(reason=ODD_LENGTH, part=BODY, hint=null)"),
            malformed,
            "malformed values (baseline; ansible-core rejects the same one)",
        )
        assertEquals(mapOf("\$ANSIBLE_VAULT;1.1;AES256" to 868 - malformed.size), styles.toMap())
    }

    /** (0-based line, value) of every `!vault` scalar, anywhere in the document. */
    private fun vaultScalars(text: String): List<Pair<Int, String>> {
        val out = mutableListOf<Pair<Int, String>>()
        fun walk(node: Node?) {
            when (node) {
                is ScalarNode -> if (node.tag.value == "!vault" || node.tag.value == "!vault-encrypted") {
                    out += node.startMark.line to node.value
                }
                is MappingNode -> node.value.forEach { walk(it.valueNode) }
                is SequenceNode -> node.value.forEach(::walk)
                else -> Unit
            }
        }
        Yaml(LoaderOptions()).composeAll(StringReader(text)).forEach(::walk)
        return out
    }
}
