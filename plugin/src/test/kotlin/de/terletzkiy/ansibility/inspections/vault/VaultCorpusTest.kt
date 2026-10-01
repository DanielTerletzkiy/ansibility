package de.terletzkiy.ansibility.inspections.vault

import com.intellij.psi.PsiFileFactory
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.terletzkiy.ansibility.api.VaultEnvelopeKind
import de.terletzkiy.ansibility.fixtures.InfraTestData
import de.terletzkiy.ansibility.index.PathFacts
import de.terletzkiy.ansibility.index.vault.VaultIndex
import de.terletzkiy.ansibility.index.vault.VaultIndexEntry
import de.terletzkiy.ansibility.index.vault.VaultIndexer
import de.terletzkiy.ansibility.semantics.diagnostics.DiagnosticCode
import de.terletzkiy.ansibility.semantics.vault.VaultEnvelope
import de.terletzkiy.ansibility.semantics.vault.VaultLayout
import org.jetbrains.yaml.YAMLLanguage
import org.jetbrains.yaml.psi.YAMLFile
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Paths

/**
 * Opt-in structural corpus run over the real infra repo (`ANSIBLE_INFRA_REPO`, read-only; M4.5 acceptance 9). It never
 * decrypts and never opens a secret-named file (`.vault-pass`, `.env*`, `*.password`, key and certificate files,
 * anything below `files/ssl` or `files/ssh`); the detached duplicate below `.claude/worktrees` and `.git` are skipped.
 * It runs the `ansible.vault` indexer and the ANS-V101–V103 classification on each file's text and reports counts,
 * plus file and key (never a value) of every finding.
 *
 * Measured 2026-10-01 (V2b/V4) on the current checkout: 868 inline values in 86 files, all literal `|` blocks with an
 * unlabelled `1.1` header (block indent +2 = 865, +10 = 3; the detector picks +2 in 85 files, +10 in 1); 0 whole-file
 * vaults among the files this test may open, 546 secret-named files skipped; V101 = 1, V102 = 0, V103 = 0. The
 * amendment's 804 values in 88 files predate the current checkout, and its 44 whole-file vaults are all `*.key` files
 * below `files/ssl` and `files/ssh`, which the secret rules forbid opening, so they are not counted here.
 */
class VaultCorpusTest : BasePlatformTestCase() {
    fun testStructuralVaultCountsOfTheInfraRepo() {
        val repo = System.getenv(InfraTestData.INFRA_REPO_ENV)?.takeIf { it.isNotBlank() }?.let(Paths::get)
        if (repo == null || !Files.isDirectory(repo)) {
            println("VaultCorpusTest skipped: set ${InfraTestData.INFRA_REPO_ENV} to the infra repo")
            return
        }
        val inline = ArrayList<Pair<String, VaultIndexEntry>>()
        val wholeFiles = ArrayList<Pair<String, VaultIndexEntry>>()
        val relativeIndents = HashMap<Int, Int>()
        val detected = HashMap<Int, Int>()
        var skippedSecretNames = 0
        var indexerNanos = 0L
        var indexBytes = 0
        Files.walk(repo).use { stream ->
            for (path in stream.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }.toList()) {
                val rel = repo.relativize(path).joinToString("/")
                if (isSkippedDirectory(rel)) continue
                if (isSecretName(rel)) {
                    skippedSecretNames++
                    continue
                }
                if (Files.size(path) > VaultIndex.MAX_FILE_SIZE) continue
                val head = Files.newInputStream(path).use { it.readNBytes(VaultEnvelope.MAGIC.length) }
                if (!VaultEnvelope.isEncryptedFile(head) && !isYamlInput(rel)) continue
                val bytes = Files.readAllBytes(path)
                val text = String(bytes, Charsets.UTF_8)
                if (!VaultEnvelope.isEncryptedFile(bytes) && "!vault" !in text) continue
                val started = System.nanoTime()
                val entries = VaultIndexer.index(rel, bytes, 0L, ::parse).values.flatten()
                indexerNanos += System.nanoTime() - started
                indexBytes += serializedSize(entries)
                for (entry in entries) (if (entry.kind == VaultEnvelopeKind.FILE) wholeFiles else inline) += rel to entry
                if (entries.any { it.kind == VaultEnvelopeKind.INLINE }) {
                    VaultLayout.blocks(text).filter { it.hasHeader }.forEach { relativeIndents.merge(it.relativeIndent, 1, Int::plus) }
                    VaultLayout.detectRelativeIndent(text)?.let { detected.merge(it, 1, Int::plus) }
                }
            }
        }
        val all = inline + wholeFiles
        val findings = all.mapNotNull { (rel, entry) ->
            VaultEnvelopeChecks.codeOf(entry.problem, entry.hint, entry.style)?.let { code -> Triple(code, rel, entry) }
        }
        val count = { code: DiagnosticCode -> findings.count { it.first == code } }
        val styles = inline.groupingBy { it.second.style }.eachCount()
        println(
            "VaultCorpusTest: ${inline.size} inline values in ${inline.map { it.first }.distinct().size} files, " +
                "${wholeFiles.size} whole-file vaults among non-secret-named files, $skippedSecretNames secret-named files skipped; " +
                "styles $styles; block indent relative to the key $relativeIndents; detected per file $detected; " +
                "labels ${all.groupingBy { it.second.label }.eachCount()}; " +
                "V101=${count(DiagnosticCode.V101_MALFORMED_ENVELOPE)} V102=${count(DiagnosticCode.V102_FOLDED_VAULT_VALUE)} " +
                "V103=${count(DiagnosticCode.V103_TRAILING_WHITESPACE)}",
        )
        println("VaultCorpusTest: ansible.vault indexer ${indexerNanos / 1_000_000} ms over the vault-bearing files, ${indexBytes} bytes of index values")
        for ((code, rel, entry) in findings) println("  ${code.id} $rel ${entry.keyPath.joinToString(".")} (${entry.problem}, ${entry.hint})")

        assertTrue("the repo has inline vault values", inline.size > 700)
        assertEquals("no folded or flattened value (the repo writes `!vault |` everywhere)", 0, count(DiagnosticCode.V102_FOLDED_VAULT_VALUE))
        assertEquals("no trailing whitespace on a payload line", 0, count(DiagnosticCode.V103_TRAILING_WHITESPACE))
        assertEquals("ANS-V101 baseline (see V101_BASELINE)", V101_BASELINE, count(DiagnosticCode.V101_MALFORMED_ENVELOPE))
    }

    private fun parse(text: CharSequence): YAMLFile? =
        PsiFileFactory.getInstance(project).createFileFromText("corpus.yml", YAMLLanguage.INSTANCE, text) as? YAMLFile

    private fun isYamlInput(rel: String): Boolean = VaultIndex.isYamlInput(PathFacts.of(rel))

    private fun serializedSize(entries: List<VaultIndexEntry>): Int =
        ByteArrayOutputStream().also { out -> DataOutputStream(out).use { VaultIndex.EXTERNALIZER.save(it, entries) } }.size()

    private companion object {
        /**
         * The pinned ANS-V101 count of the repo, reviewed like code. Measured 2026-10-01 (V2b): one value,
         * `repos/wren/ansible/group_vars/all/vault.yml` key `vault_keycloak_doc_builder_frontend_test_client_secret`
         * (an odd number of hex digits). The amendment's earlier baseline of 2 also had one value in tern
         * (`environments/test/group_vars/all/vault.yml`, a non-hex digit), which the current checkout no longer has.
         */
        const val V101_BASELINE = 1

        private val SKIPPED_DIRECTORIES = setOf(".git", ".claude", ".idea", "node_modules", ".venv", "__pycache__", ".ansible")
        private val SECRET_NAMES = setOf(".vault-pass", ".vault_pass", ".vault-password", ".initial-root-pass")
        private val SECRET_SUFFIXES = listOf(".key", ".pem", ".crt", ".pub", ".password", ".p12", ".pfx", ".jks", ".keystore", ".asc", ".gpg")

        fun isSkippedDirectory(rel: String): Boolean = rel.split('/').dropLast(1).any { it in SKIPPED_DIRECTORIES }

        /** File names the secret rules forbid opening, whatever they hold. */
        fun isSecretName(rel: String): Boolean {
            val segments = rel.split('/')
            val name = segments.last().lowercase()
            if (name in SECRET_NAMES || name.startsWith(".env") || name.startsWith("id_rsa") || name.startsWith("id_ed25519")) return true
            if (SECRET_SUFFIXES.any { name.endsWith(it) }) return true
            return segments.dropLast(1).zipWithNext().any { (a, b) -> a == "files" && (b == "ssl" || b == "ssh") }
        }
    }
}
