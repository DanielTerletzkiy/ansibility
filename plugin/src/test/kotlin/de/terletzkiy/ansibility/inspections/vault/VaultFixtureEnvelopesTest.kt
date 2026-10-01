package de.terletzkiy.ansibility.inspections.vault

import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.VaultEnvelopeKind
import de.terletzkiy.ansibility.context.AnsibleWorkspaceImpl
import de.terletzkiy.ansibility.fixtures.InfraTestData
import de.terletzkiy.ansibility.index.vault.VaultIndex
import de.terletzkiy.ansibility.index.vault.VaultIndexQueries
import de.terletzkiy.ansibility.index.vault.VaultIndexer
import de.terletzkiy.ansibility.semantics.vault.EnvelopeParse
import de.terletzkiy.ansibility.semantics.vault.SecretBytes
import de.terletzkiy.ansibility.semantics.vault.VaultAes256
import de.terletzkiy.ansibility.semantics.vault.VaultEnvelope
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLScalar
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

/**
 * The sanitised infra fixture after V2b (D15 as amended, M4.5 acceptance 7): every `!vault` value is a well-formed
 * `1.1` envelope (label `default`) that decrypts with the synthetic fixture password of `tools/vault/SYNTHETIC.md` to
 * the dummy, ANS-V101–V103 find nothing, and `ansible.vault` lists every fixture value under its root.
 */
class VaultFixtureEnvelopesTest : BasePlatformTestCase() {
    override fun getTestDataPath(): String = InfraTestData.testDataPath.toString()

    fun testEveryFixtureEnvelopeIsTheSyntheticOneAndTheChecksAndTheIndexAgree() {
        myFixture.copyDirectoryToProject("${InfraTestData.INFRA}/repos", "repos")
        AnsibleWorkspaceImpl.getInstance(project)?.structureChanged()
        val base = myFixture.findFileInTempDir("repos") ?: error("repos not copied")
        val yamlFiles = ArrayList<VirtualFile>()
        VfsUtilCore.iterateChildrenRecursively(base, null) { file ->
            if (!file.isDirectory && (file.name.endsWith(".yml") || file.name.endsWith(".yaml"))) yamlFiles += file
            true
        }

        val password = SecretBytes.of(InfraTestData.FIXTURE_VAULT_PASSWORD.toByteArray(Charsets.UTF_8))
        val problems = ArrayList<String>()
        var values = 0
        var indexerNanos = 0L
        val valuesPerFile = HashMap<VirtualFile, Int>()
        for (file in yamlFiles) {
            val psi = PsiManager.getInstance(project).findFile(file) as? YAMLFile ?: continue
            val started = System.nanoTime()
            val entries = VaultIndexer.inline(psi, 0, 0)
            indexerNanos += System.nanoTime() - started
            if (entries.isEmpty()) continue
            valuesPerFile[file] = entries.size
            for (entry in entries) {
                values++
                val where = "${file.path.substringAfter("/src/")}:${entry.keyPath.joinToString(".")}"
                if (!entry.wellFormed) problems += "$where: ${entry.problem}"
                if (entry.version != "1.1" || entry.label != null) problems += "$where: header ${entry.version};${entry.label}"
                val scalar = psi.findElementAt(entry.offset)?.parent as? YAMLScalar
                val envelope = scalar?.let { (VaultEnvelope.parse(it.textValue) as? EnvelopeParse.Ok)?.envelope }
                if (envelope == null) {
                    problems += "$where: no envelope at the indexed offset"
                    continue
                }
                val plaintext = VaultAes256.decrypt(envelope, password)
                if (plaintext == null) {
                    problems += "$where: does not decrypt with the fixture password"
                } else {
                    if (!InfraTestData.FIXTURE_VAULT_PLAINTEXT.matches(String(plaintext, Charsets.US_ASCII))) problems += "$where: not the dummy"
                    plaintext.fill(0)
                }
            }
            VaultEnvelopeChecks.of(psi).forEach { problems += "${file.path.substringAfter("/src/")}: ${it.code.id}" }
        }
        password.zero()
        assertEquals(emptyList<String>(), problems)
        assertEquals("the fixture's vault values (tools/fixtures/manifest.tsv: vault=)", 159, values)

        val indexed = AnsibleWorkspace.getInstance(project).roots().flatMap { root ->
            VaultIndexQueries.envelopes(project, root).map { it.file to it.value }
        }.distinct()
        assertEquals("ansible.vault lists every fixture value under its root", values, indexed.size)
        assertTrue(indexed.all { (_, entry) -> entry.kind == VaultEnvelopeKind.INLINE && entry.wellFormed })
        assertEquals(valuesPerFile, indexed.groupingBy { it.first }.eachCount())
        val bytes = ByteArrayOutputStream().also { out ->
            DataOutputStream(out).use { VaultIndex.EXTERNALIZER.save(it, indexed.map { entry -> entry.second }) }
        }.size()
        println("ansible.vault on the fixture: $values values in ${valuesPerFile.size} files, $bytes bytes of index values, " +
            "${indexerNanos / 1_000_000} ms indexer time over ${yamlFiles.size} YAML files (PSI already built)")
    }
}
