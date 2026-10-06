package de.terletzkiy.ansibility.fixtures

import org.junit.Assume.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Location and layout of the sanitised infra-repo fixture (WU0.4) written by `tools/fixtures/sync.py`.
 *
 * Platform tests that need the fixture override `getTestDataPath()` with [testDataPath] and call
 * `myFixture.copyDirectoryToProject(InfraTestData.INFRA, "")`. The tree mirrors the infra repo, so
 * acceptance checks can cite `path:line` of the real repo: sanitising keeps every line number.
 *
 * The tree is shared and read-only for all work units; regenerate it with `sync.py`, never edit it.
 */
object InfraTestData {
    /** Directory name of the fixture below [testDataPath]. */
    const val INFRA: String = "infra"

    /** System property that overrides the test data directory (absolute path of `src/test/testData`). */
    const val TEST_DATA_PROPERTY: String = "ansibility.testData"

    /** Environment variable that points opt-in corpus tests at a local checkout of the infra repo. */
    const val INFRA_REPO_ENV: String = "ANSIBLE_INFRA_REPO"

    /** Submodule roots (`repos/<name>`) that receive a synthetic `.git` link file. */
    val SUBMODULES: List<String> = listOf("falcon", "heron", "pelican", "platform", "raven", "thrush", "wren")

    /** Root of the synthetic detached worktree, relative to the fixture root. */
    const val WORKTREE_DIR: String = ".claude/worktrees/wt-demo"

    /** Content of the synthetic worktree `.git` file (points into `.git/worktrees`, so the root is detached). */
    const val WORKTREE_GIT_LINK: String = "gitdir: /tmp/fake/.git/worktrees/wt-demo\n"

    /**
     * The synthetic fixture vault password (`tools/vault/SYNTHETIC.md`, row `fixture`, label `default`): every vault
     * envelope in the fixture is a `1.1` envelope that decrypts with it to [FIXTURE_VAULT_PLAINTEXT]
     * (`tools/fixtures/vaultfixture.py`, D15 as amended). Synthetic: it protects nothing.
     */
    const val FIXTURE_VAULT_PASSWORD: String = "fixture-pass-d15"

    /** The plaintext of every fixture envelope: `dummy`, padded with `-` to keep the original's line lengths. */
    val FIXTURE_VAULT_PLAINTEXT: Regex = Regex("dummy-*")

    /** Content of the synthetic `.git` file of submodule `repos/<name>` (points into `.git/modules`). */
    fun submoduleGitLink(name: String): String = "gitdir: ../../.git/modules/repos/$name\n"

    /**
     * Every payload line (hex, without indentation) of the fixture's vault envelopes, read from disk once. Security
     * tests check that no card, preview or tree text contains one of them ([containsVaultPayload]).
     */
    val vaultPayloadLines: Set<String> by lazy {
        val lines = HashSet<String>()
        Files.walk(root).use { stream ->
            stream.filter { Files.isRegularFile(it) && it.fileName.toString() != ".git" }.forEach { path ->
                val text = runCatching { Files.readString(path) }.getOrNull() ?: return@forEach
                if (VAULT_MARK !in text) return@forEach
                val fileLines = text.lines()
                fileLines.forEachIndexed { index, line ->
                    if (!line.trimStart().startsWith(VAULT_MARK)) return@forEachIndexed
                    val indent = line.length - line.trimStart().length
                    fileLines.drop(index + 1)
                        .takeWhile { it.length > indent && it.substring(0, indent).isBlank() && HEX.matches(it.substring(indent)) }
                        .forEach { lines += it.substring(indent) }
                }
            }
        }
        lines
    }

    /** True when [text] holds a fixture payload line, or the first 32 digits of one (never true for a masked value). */
    fun containsVaultPayload(text: CharSequence): Boolean =
        vaultPayloadLines.any { line -> line.length >= PAYLOAD_PROBE && text.contains(line.take(PAYLOAD_PROBE)) }

    private const val VAULT_MARK = "\$ANSIBLE_VAULT"
    private const val PAYLOAD_PROBE = 32
    private val HEX = Regex("[0-9A-Fa-f]+")

    /** Absolute path of `plugin/src/test/testData`, independent of the working directory. */
    val testDataPath: Path by lazy {
        val moduleRelative = Paths.get("src", "test", "testData")
        val candidates = listOfNotNull(
            System.getProperty(TEST_DATA_PROPERTY)?.let { Paths.get(it) },
            moduleRelative,
            Paths.get("plugin").resolve(moduleRelative),
        )
        (candidates.firstOrNull { Files.isDirectory(it.resolve(INFRA)) } ?: moduleRelative).toAbsolutePath().normalize()
    }

    /** Absolute path of the fixture root (`testData/infra`). */
    val root: Path get() = testDataPath.resolve(INFRA)

    /** Skips the calling plain JUnit 4 test when the git-ignored fixture is missing (see [RequiresInfraFixture]). */
    fun assumePresent() {
        assumeTrue("the infra fixture is missing ($root)", Files.isDirectory(root))
    }
}
