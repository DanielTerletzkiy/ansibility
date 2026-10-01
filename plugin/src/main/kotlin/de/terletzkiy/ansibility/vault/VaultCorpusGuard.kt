package de.terletzkiy.ansibility.vault

import com.intellij.openapi.application.ApplicationManager
import org.jetbrains.annotations.TestOnly
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Thrown in corpus mode when anything asks the vault services to read a secret source of, unlock or decrypt files
 * of the real infra repository (DEV.md rule 2, plan amendment R7/R8 Testing additions §3). The message names the
 * file only.
 */
class VaultCorpusGuardException(file: String) : IllegalStateException("Vault access refused in corpus mode: $file")

/**
 * The corpus guard: while tests run (`isUnitTestMode`), every path below the infra repository named by
 * `ANSIBLE_INFRA_REPO` (the opt-in corpus switch) is protected, so a structural corpus test can never decrypt real
 * vault data or read its password files, even through a bug elsewhere. Outside tests it does nothing.
 */
object VaultCorpusGuard {
    /** The environment variable that points opt-in corpus tests at the real infra repository. */
    const val INFRA_REPO_ENV: String = "ANSIBLE_INFRA_REPO"

    @Volatile
    private var protectedOverride: List<Path>? = null

    /** Replaces the protected directories (tests that prove the guard point it at a synthetic tree); null restores the default. */
    @TestOnly
    fun protectForTests(directories: List<Path>?) {
        protectedOverride = directories?.map { it.toAbsolutePath().normalize() }
    }

    /** The directories protected right now: none outside tests. */
    fun protectedDirectories(): List<Path> {
        val application = ApplicationManager.getApplication()
        if (application == null || !application.isUnitTestMode) return emptyList()
        protectedOverride?.let { return it }
        val repo = System.getenv(INFRA_REPO_ENV)?.takeIf { it.isNotBlank() } ?: return emptyList()
        val path = try {
            Paths.get(repo)
        } catch (_: InvalidPathException) {
            return emptyList()
        }
        return listOfNotNull(path.toAbsolutePath().normalize(), runCatching { path.toRealPath() }.getOrNull())
    }

    /** True when [path] lies in a protected directory. */
    fun isProtected(path: Path): Boolean {
        val directories = protectedDirectories()
        if (directories.isEmpty()) return false
        val candidates = listOfNotNull(path.toAbsolutePath().normalize(), runCatching { path.toRealPath() }.getOrNull())
        return candidates.any { candidate -> directories.any { candidate.startsWith(it) } }
    }

    /** [isProtected] for a `/`-separated path string (a `VirtualFile.path`). */
    fun isProtected(path: String): Boolean {
        val parsed = try {
            Paths.get(path)
        } catch (_: InvalidPathException) {
            return false
        }
        return isProtected(parsed)
    }

    /** @throws VaultCorpusGuardException when [path] is protected */
    fun check(path: Path) {
        if (isProtected(path)) refuse(path.toString())
    }

    /** @throws VaultCorpusGuardException when [path] is protected */
    fun check(path: String) {
        if (isProtected(path)) refuse(path)
    }

    private fun refuse(path: String): Nothing {
        VaultLog.refused(path)
        throw VaultCorpusGuardException(path)
    }
}
