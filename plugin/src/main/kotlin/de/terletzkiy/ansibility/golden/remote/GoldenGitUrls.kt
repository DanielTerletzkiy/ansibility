package de.terletzkiy.ansibility.golden.remote

import de.terletzkiy.ansibility.run.GitChecks
import de.terletzkiy.ansibility.settings.DriftSettings
import java.security.MessageDigest

/**
 * The repository URL, branch and roles path of the golden mirror (plan amendment R25, D193, D201): validation, display
 * and the mirror's cache key. Pure; used by the settings page (validation) and by [GoldenMirrorService] (it refuses to
 * fetch an invalid setting).
 *
 * Accepted: ssh (`git@host:path`, `ssh://`), https, git, `file://` and absolute local paths (`~/` allowed). Refused:
 * a blank URL, user info in http(s) URLs and passwords in any URL (the settings may be committed), the command
 * transports `<transport>::<address>` (`ext::` runs a command), a leading `-` (git would read an option), other
 * schemes (http, ftp, rsync…), relative paths and control characters.
 */
object GoldenGitUrls {
    /** Why a URL is refused. */
    enum class Problem { BLANK, CREDENTIALS, TRANSPORT, OPTION, UNSUPPORTED }

    /** Why a branch/tag or roles path is refused. */
    enum class FieldProblem { REF, ROLES_PATH }

    private val SCHEME = Regex("""^([A-Za-z][A-Za-z0-9+.-]*)://(.*)$""", RegexOption.DOT_MATCHES_ALL)
    private val TRANSPORT = Regex("""^[A-Za-z][A-Za-z0-9+.-]*::""")
    private val DOS_DRIVE = Regex("""^[A-Za-z]:[\\/]""")
    private val SSH_SCHEMES = setOf("ssh", "git+ssh", "ssh+git")
    private val ACCEPTED_SCHEMES = SSH_SCHEMES + setOf("https", "git", "file")
    private val CONTROL = Regex("""[\x00-\x1f\x7f]""")
    private val SHA = Regex("""^[0-9a-f]{40}([0-9a-f]{24})?$""")

    /** Why [url] is refused, or null when it is fine. */
    fun problem(url: String): Problem? {
        val text = url.trim()
        if (text.isEmpty()) return Problem.BLANK
        if (CONTROL.containsMatchIn(text)) return Problem.UNSUPPORTED
        if (text.startsWith("-")) return Problem.OPTION
        if (TRANSPORT.containsMatchIn(text)) return Problem.TRANSPORT
        SCHEME.find(text)?.let { match ->
            val scheme = match.groupValues[1].lowercase()
            val rest = match.groupValues[2]
            val authority = rest.substringBefore('/')
            if ('@' in authority) {
                val userInfo = authority.substringBeforeLast('@')
                if (scheme == "http" || scheme == "https" || ':' in userInfo) return Problem.CREDENTIALS
            }
            if (scheme !in ACCEPTED_SCHEMES) return Problem.UNSUPPORTED
            if (scheme != "file" && authority.substringAfterLast('@').isEmpty()) return Problem.UNSUPPORTED
            return null
        }
        if (isLocalPath(text)) return if (DriftSettings.expandFolder(text) != null) null else Problem.UNSUPPORTED
        if (isScpLike(text)) {
            val beforePath = text.substringBefore(':')
            // `alice:secret@host:path` reads as host "alice" with a password-like path: refuse it.
            val afterFirstColon = text.substringAfter(':')
            if ('@' in afterFirstColon.substringBefore('/') && ':' in afterFirstColon) return Problem.CREDENTIALS
            if (beforePath.substringAfterLast('@').isEmpty()) return Problem.UNSUPPORTED
            return null
        }
        return Problem.UNSUPPORTED
    }

    /** Whether [url] reaches its repository over SSH (`ssh://` or the scp-like `[user@]host:path`). */
    fun isSsh(url: String): Boolean {
        val text = url.trim()
        SCHEME.find(text)?.let { return it.groupValues[1].lowercase() in SSH_SCHEMES }
        return !isLocalPath(text) && !TRANSPORT.containsMatchIn(text) && isScpLike(text)
    }

    /** Whether [url] is a local path (absolute, `~/`, a DOS drive), not a URL. */
    fun isLocalPath(url: String): Boolean {
        val text = url.trim()
        return text.startsWith("/") || text == "~" || text.startsWith("~/") || text.startsWith("\\\\") || DOS_DRIVE.containsMatchIn(text)
    }

    /** The URL git is given: trimmed, a local path with `~` expanded. */
    fun effective(url: String): String {
        val text = url.trim()
        return if (isLocalPath(text)) DriftSettings.expandFolder(text)?.toString() ?: text else text
    }

    /** [url] for the UI and for errors: trimmed, without the user info of an http(s) URL or a password of any URL. */
    fun display(url: String): String = scrub(GitChecks.withoutCredentials(url.trim()))

    /** [text] with every credential-like user info of a URL in it removed (`https://user:token@host` → `https://host`). */
    fun scrub(text: String): String =
        CREDENTIALS_IN_TEXT.replace(HTTP_USER_IN_TEXT.replace(text, "$1"), "$1")

    private val HTTP_USER_IN_TEXT = Regex("""\b(https?://)[^/@\s]*@""", RegexOption.IGNORE_CASE)
    private val CREDENTIALS_IN_TEXT = Regex("""\b([A-Za-z][A-Za-z0-9+.-]*://)[^/@\s:]*:[^/@\s]*@""")

    /** The repository's short name: the last path segment without `.git` ("golden"), or the URL itself. */
    fun repositoryName(url: String): String {
        val text = effective(url).trimEnd('/', '\\')
        val last = text.substringAfterLast('/').substringAfterLast('\\').let { if (isScpLike(text) && '/' !in text) it.substringAfterLast(':') else it }
        return last.removeSuffix(".git").ifEmpty { display(url) }
    }

    /** The mirror's directory name: the first 16 hex digits of sha256(url + "\n" + ref) (D194). */
    fun mirrorKey(url: String, ref: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest((effective(url) + "\n" + ref.trim()).toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(16)
    }

    /**
     * Why [ref] (a branch or tag name; blank: the default branch) is refused, or null: git's ref-name rules (no
     * `..`, `~^:?*[\`, `@{`, spaces, control characters, leading `-` or `/`, trailing `/`, `.` or `.lock`), and no
     * `refs/` prefix (a short name is wanted).
     */
    fun refProblem(ref: String): FieldProblem? {
        val text = ref.trim()
        if (text.isEmpty()) return null
        val bad = text.startsWith("-") || text.startsWith("/") || text.endsWith("/") || text.endsWith(".") ||
            text.endsWith(".lock") || ".." in text || "@{" in text || "//" in text || text == "@" ||
            text.startsWith("refs/") || text.any { it.isWhitespace() || it in "~^:?*[\\" } || CONTROL.containsMatchIn(text) ||
            text.split('/').any { it.startsWith(".") }
        return if (bad) FieldProblem.REF else null
    }

    /**
     * Why [path] (the roles path inside the repository; blank: automatic) is refused, or null: a relative path of
     * plain segments (no `.`/`..`, no leading `-`, no wildcards or backslashes), as `git sparse-checkout set` takes it.
     */
    fun rolesPathProblem(path: String): FieldProblem? {
        val text = path.trim().removePrefix("./").trimEnd('/')
        if (text.isEmpty()) return null
        val segments = text.split('/')
        val bad = text.startsWith("/") || CONTROL.containsMatchIn(text) || text.any { it in "\\*?[]!#" } ||
            segments.any { it.isEmpty() || it == "." || it == ".." || it.startsWith("-") }
        return if (bad) FieldProblem.ROLES_PATH else null
    }

    /** Whether [value] is a full commit hash (SHA-1 or SHA-256). */
    fun isCommit(value: String): Boolean = SHA.matches(value)

    /** git's rule: a colon before the first slash makes `[user@]host:path` an ssh URL (local paths excluded). */
    private fun isScpLike(text: String): Boolean {
        val colon = text.indexOf(':')
        if (colon <= 0) return false
        val slash = text.indexOf('/')
        return slash < 0 || colon < slash
    }
}
