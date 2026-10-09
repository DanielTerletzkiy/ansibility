package de.terletzkiy.ansibility.golden.patch

import junit.framework.TestCase
import java.io.File

/**
 * The patch sources (plan amendment R25, X126) write their own git diff on the public line comparison: never the VCS
 * patch builders of `vcs-impl` (VCS types outside `vcs/impl`, partly internal), never a git process, never a vault
 * operation. A source scan, like `GoldenInternalApiGuardTest`.
 */
class PatchInternalApiGuardTest : TestCase() {
    fun testThePatchSourcesUseNeitherTheVcsPatchBuildersNorGitNorTheVault() {
        val sources = sourceDir().walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue("the patch sources are found: ${sourceDir().absolutePath}", sources.size >= 5)
        val violations = sources.flatMap { file ->
            file.readLines().withIndex().mapNotNull { (index, line) ->
                val trimmed = line.trimStart()
                if (trimmed.startsWith("*") || trimmed.startsWith("/*") || trimmed.startsWith("//")) return@mapNotNull null
                val code = line.substringBefore("//")
                BANNED.firstOrNull { (pattern, _) -> pattern.containsMatchIn(code) }?.let { "${file.name}:${index + 1}: ${it.second}" }
            }
        }
        assertEquals(emptyList<String>(), violations)
    }

    private fun sourceDir(): File {
        val relative = "src/main/kotlin/de/terletzkiy/ansibility/golden/patch"
        return listOf(File(relative), File("plugin/$relative")).firstOrNull { it.isDirectory } ?: File(relative)
    }

    private companion object {
        val BANNED: List<Pair<Regex, String>> = listOf(
            Regex("""openapi\.diff\.impl\.patch|\bUnifiedDiffWriter\b|\bIdeaTextPatchBuilder\b|\bTextPatchBuilder\b""") to
                "the VCS patch builders (vcs-impl; GitPatchWriter writes the diff)",
            Regex("""^import com\.intellij\.(openapi\.vcs\.|vcs\.|vcsUtil\.)|^import git4idea\.""") to "VCS or Git plugin API",
            Regex("""\bProcessBuilder\b|\bGeneralCommandLine\b|\bGoldenGitTransport\b""") to "a git process (the patch is only written)",
            Regex("""\bVaultOperations\b|\bdecrypt\(""") to "vault operations (key and vault files go in as they are)",
            Regex("""\bReadAction\.compute\b""") to "ReadAction.compute (deprecated)",
        )
    }
}
