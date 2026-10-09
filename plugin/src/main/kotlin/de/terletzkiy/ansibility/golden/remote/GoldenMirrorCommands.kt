package de.terletzkiy.ansibility.golden.remote

import java.nio.file.Path
import java.time.Instant
import java.time.OffsetDateTime

/**
 * The git command lines of the golden mirror (plan amendment R25, D194, D201, D202): safety configuration, environment
 * and arguments, and the parsers of git's answers. Pure; [GoldenMirrorService] runs them through a [GoldenGitTransport].
 *
 * Nothing from the repository runs (D201): every invocation gets [safetyConfig] (hooks from an empty directory we own,
 * no symlinks, no fsmonitor, no submodules, no LFS filters, no automatic gc or maintenance, only the ssh, https, git and
 * file protocols, credentials in URLs refused) and [environment] (the same protocol allow-list, LFS smudge skipped, no
 * terminal prompts).
 */
object GoldenMirrorCommands {
    /** `--sparse` and `git sparse-checkout` need git 2.25. */
    const val MIN_GIT_MAJOR: Int = 2
    const val MIN_GIT_MINOR: Int = 25
    const val MIN_GIT_TEXT: String = "2.25"

    /** The protocols git may use (D201): `GIT_ALLOW_PROTOCOL` and the `protocol.*.allow` configuration. */
    val PROTOCOLS: List<String> = listOf("ssh", "https", "git", "file")

    /** The `-c` options of every invocation; [hooksDir] is an empty directory we own (`core.hooksPath`). */
    fun safetyConfig(hooksDir: Path): List<String> = listOf(
        "core.hooksPath=$hooksDir",
        "core.symlinks=false",
        "core.fsmonitor=false",
        "core.protectNTFS=true",
        "core.protectHFS=true",
        "submodule.recurse=false",
        "fetch.recurseSubmodules=false",
        "filter.lfs.smudge=",
        "filter.lfs.process=",
        "filter.lfs.required=false",
        "gc.auto=0",
        "maintenance.auto=false",
        "advice.detachedHead=false",
        "transfer.credentialsInUrl=die",
        "protocol.allow=never",
    ) + PROTOCOLS.map { "protocol.$it.allow=always" }

    /**
     * The extra `-c` options of the system git (no IDE behind it, D202): no askpass program and no interactive
     * credential helper. Never on the Git plugin's path, where they would switch off the IDE's own credential prompts.
     */
    val SYSTEM_GIT_CONFIG: List<String> = listOf("core.askPass=", "credential.interactive=false")

    /**
     * The environment added to every invocation: the protocol allow-list, LFS smudge skipped, no terminal prompts (git
     * would otherwise ask on the IDE's terminal); BACKGROUND commands also tell Git Credential Manager not to prompt;
     * local commands ([network] false) never fetch missing objects lazily.
     */
    fun environment(interaction: Interaction, network: Boolean): Map<String, String> = buildMap {
        put("GIT_ALLOW_PROTOCOL", PROTOCOLS.joinToString(":"))
        put("GIT_LFS_SKIP_SMUDGE", "1")
        put("GIT_TERMINAL_PROMPT", "0")
        if (interaction == Interaction.BACKGROUND) put("GCM_INTERACTIVE", "never")
        if (!network) put("GIT_NO_LAZY_FETCH", "1")
    }

    /**
     * The whole environment of the system git (D202, never prompts): the [inherited] environment without
     * `GIT_ASKPASS`/`SSH_ASKPASS`, with [added], `SSH_ASKPASS_REQUIRE=never`, `GCM_INTERACTIVE=never`, `LC_ALL=C`, and
     * SSH in batch mode (`GIT_SSH_COMMAND="ssh -o BatchMode=yes"`) unless the user chose an SSH command
     * (`GIT_SSH`, `GIT_SSH_COMMAND` or `core.sshCommand`, [sshCommandConfigured]), which we must not replace.
     */
    fun systemGitEnvironment(inherited: Map<String, String>, added: Map<String, String>, sshCommandConfigured: Boolean): Map<String, String> {
        val env = LinkedHashMap(inherited)
        env.remove("GIT_ASKPASS")
        env.remove("SSH_ASKPASS")
        env.putAll(added)
        env["SSH_ASKPASS_REQUIRE"] = "never"
        env["GCM_INTERACTIVE"] = "never"
        env["GIT_TERMINAL_PROMPT"] = "0"
        env["LC_ALL"] = "C"
        val userSsh = sshCommandConfigured || !inherited["GIT_SSH"].isNullOrBlank() || !inherited["GIT_SSH_COMMAND"].isNullOrBlank()
        if (!userSsh) env["GIT_SSH_COMMAND"] = "ssh -o BatchMode=yes"
        return env
    }

    /**
     * The first clone (D194) into [target] (a directory name below the working directory): shallow ([depth] commits),
     * without blobs ([filter]; servers that cannot filter send the whole shallow tree), only the root files checked
     * out (`--sparse`), one branch or tag ([ref]; blank: the default branch), no tags, no submodules, our own
     * [templateDir] (no hooks or config from the user's templates), the git transport even for local paths
     * (`--no-local`, so depth and filter apply).
     */
    fun cloneArgs(url: String, ref: String, depth: Int, filter: Boolean, progress: Boolean, templateDir: Path, target: String): List<String> = buildList {
        add("--depth")
        add(depth.coerceAtLeast(1).toString())
        if (filter) add("--filter=blob:none")
        add("--sparse")
        add("--single-branch")
        add("--no-tags")
        add("--no-recurse-submodules")
        add("--template=$templateDir")
        add("--no-local")
        if (progress) add("--progress")
        if (ref.isNotBlank()) {
            add("--branch")
            add(ref.trim())
        }
        add("--")
        add(url)
        add(target)
    }

    /** `ls-remote` of what the mirror follows: the default branch (`--symref HEAD`), else the branch and tag [ref]. */
    fun lsRemoteArgs(url: String, ref: String): List<String> =
        if (ref.isBlank()) listOf("--symref", "--", url, "HEAD") else listOf("--", url) + refPatterns(ref)

    /** Test Connection (D195): the default branch, plus [ref] when set. */
    fun testConnectionArgs(url: String, ref: String): List<String> =
        listOf("--symref", "--", url, "HEAD") + if (ref.isBlank()) emptyList() else refPatterns(ref)

    private fun refPatterns(ref: String): List<String> {
        val name = ref.trim()
        return listOf("refs/heads/$name", "refs/tags/$name", "refs/tags/$name^{}")
    }

    /** The refspec that fetches [remote] into the mirror (a branch into `refs/remotes/origin`, a tag onto itself). */
    fun refspec(remote: RemoteRef): String = when (remote.kind) {
        RefKind.BRANCH -> "+refs/heads/${remote.name}:refs/remotes/origin/${remote.name}"
        RefKind.TAG -> "+refs/tags/${remote.name}:refs/tags/${remote.name}"
        RefKind.HEAD -> "+HEAD:refs/remotes/origin/HEAD"
    }

    /**
     * A refresh fetch (D194): [depth] commits of [remote], no tags, no submodules. A partial clone keeps its filter
     * (`remote.origin.partialclonefilter`), so none is passed: a mirror cloned without one would refuse it.
     */
    fun fetchArgs(remote: RemoteRef, depth: Int): List<String> =
        listOf("--depth", depth.coerceAtLeast(1).toString(), "--no-tags", "--no-recurse-submodules", "origin", refspec(remote))

    /** The forced checkout of the fetched [commit] (the mirror is ours: local state is simply replaced). */
    fun checkoutArgs(commit: String): List<String> {
        require(GoldenGitUrls.isCommit(commit)) { "not a commit hash" }
        return listOf("--force", "--detach", commit)
    }

    /** Cone mode first (git 2.25–2.34 take no `--cone` on `set` and would read it as a pattern). */
    val SPARSE_INIT_ARGS: List<String> = listOf("init", "--cone")

    /** Only [rolesPath] (and the root files) checked out; validated by [GoldenGitUrls.rolesPathProblem]. */
    fun sparseSetArgs(rolesPath: String): List<String> {
        require(GoldenGitUrls.rolesPathProblem(rolesPath) == null && rolesPath.isNotBlank()) { "invalid roles path" }
        return listOf("set", rolesPath)
    }

    /** The whole tree: the roles are the repository's top-level directories. */
    val SPARSE_DISABLE_ARGS: List<String> = listOf("disable")

    val REV_PARSE_HEAD_ARGS: List<String> = listOf("--verify", "HEAD")

    /** The branch the clone checked out, or `HEAD` when it is detached (a tag). */
    val ABBREV_HEAD_ARGS: List<String> = listOf("--abbrev-ref", "HEAD")

    /** The mirrored commit: hash, author, author date (strict ISO 8601), subject, separated by NUL. */
    val LOG_ARGS: List<String> = listOf("-1", "--no-show-signature", "--format=%H%x00%an%x00%aI%x00%s", "HEAD")

    /**
     * The extra environment of the local lookups (R25 step 2): pathspecs are paths, never `:(magic)` patterns, and git
     * never reads the user's pager or editor.
     */
    val LOCAL_LOOKUP_ENV: Map<String, String> = mapOf("GIT_LITERAL_PATHSPECS" to "1", "GIT_PAGER" to "cat")

    /**
     * The last commit of HEAD's history that changed [relPath] (X127; "." for the whole tree), as [LOG_ARGS] prints it.
     * In a shallow mirror an unchanged path reports the oldest fetched commit, which has no parent.
     */
    fun lastChangeArgs(relPath: String): List<String> =
        listOf("-1", "--no-show-signature", "--format=%H%x00%an%x00%aI%x00%s", "HEAD", "--", relPath.ifEmpty { "." })

    /** Every file of [commit] below [rolesPath] (the whole tree for "") with its blob id: X128's changed roles. Trees only. */
    fun lsTreeFilesArgs(commit: String, rolesPath: String): List<String> {
        require(GoldenGitUrls.isCommit(commit)) { "not a commit hash" }
        return listOf("-r", "--full-tree", commit) + if (rolesPath.isEmpty()) emptyList() else listOf("--", rolesPath)
    }

    /**
     * The role names whose files differ between two `ls-tree -r` listings ([before], [after]) below [rolesPath]: a file
     * added, removed or with another blob. The first path segment below [rolesPath] is the role.
     */
    fun changedRoles(before: List<String>, after: List<String>, rolesPath: String): Set<String> {
        val prefix = if (rolesPath.isEmpty()) "" else rolesPath.trimEnd('/') + "/"
        fun parse(lines: List<String>): Map<String, String> = lines.mapNotNull { line ->
            val tab = line.indexOf('\t')
            if (tab < 0) return@mapNotNull null
            val fields = line.substring(0, tab).trim().split(' ')
            val path = line.substring(tab + 1)
            if (!path.startsWith(prefix) || fields.size < 3) null else path.removePrefix(prefix) to fields[2]
        }.toMap()
        val old = parse(before)
        val new = parse(after)
        return (old.keys + new.keys).filter { old[it] != new[it] }.mapNotNull { path -> path.substringBefore('/').takeIf { '/' in path } }.toSortedSet()
    }

    /** The top-level directories of HEAD (trees are always present in a blob-less clone). */
    val LS_TREE_TOP_ARGS: List<String> = listOf("-d", "--name-only", "HEAD")

    /** Whether [path] is a directory of HEAD: lists it when it is. */
    fun lsTreePathArgs(path: String): List<String> = listOf("-d", "--name-only", "HEAD", "--", path)

    /** What the remote says the mirror should be at. */
    data class RemoteRef(val commit: String, val kind: RefKind, val name: String)

    enum class RefKind { BRANCH, TAG, HEAD }

    /**
     * Reads `ls-remote` [lines] for [ref] (blank: the default branch, from `--symref HEAD`): a branch wins over a tag of
     * the same name, a peeled tag (`^{}`) over the tag object; null when the remote has no such branch or tag.
     */
    fun parseLsRemote(lines: List<String>, ref: String): RemoteRef? {
        val refs = LinkedHashMap<String, String>()
        var headTarget: String? = null
        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val tab = line.indexOf('\t')
            if (tab < 0) continue
            val left = line.substring(0, tab).trim()
            val right = line.substring(tab + 1).trim()
            if (left.startsWith("ref: ")) {
                if (right == "HEAD") headTarget = left.removePrefix("ref: ").trim()
            } else if (GoldenGitUrls.isCommit(left)) {
                refs.putIfAbsent(right, left)
            }
        }
        val name = ref.trim()
        if (name.isEmpty()) {
            val commit = refs["HEAD"] ?: return null
            val branch = headTarget?.removePrefix("refs/heads/")?.takeIf { headTarget.startsWith("refs/heads/") }
            return if (branch != null) RemoteRef(commit, RefKind.BRANCH, branch) else RemoteRef(commit, RefKind.HEAD, "HEAD")
        }
        refs["refs/heads/$name"]?.let { return RemoteRef(it, RefKind.BRANCH, name) }
        (refs["refs/tags/$name^{}"] ?: refs["refs/tags/$name"])?.let { return RemoteRef(it, RefKind.TAG, name) }
        return null
    }

    /** The default branch `ls-remote --symref` names, or null. */
    fun defaultBranch(lines: List<String>): String? = parseLsRemote(lines, "")?.takeIf { it.kind == RefKind.BRANCH }?.name

    /** A commit as [LOG_ARGS] prints it. */
    data class CommitInfo(val hash: String, val author: String, val instant: Instant?, val subject: String)

    fun parseCommit(lines: List<String>): CommitInfo? {
        val parts = lines.firstOrNull { it.isNotBlank() }?.split('\u0000') ?: return null
        if (parts.size < 4 || !GoldenGitUrls.isCommit(parts[0].trim())) return null
        val instant = runCatching { OffsetDateTime.parse(parts[2].trim()).toInstant() }.getOrNull()
        return CommitInfo(parts[0].trim(), parts[1], instant, parts.drop(3).joinToString(" "))
    }

    /**
     * The relative `roles_path` entries of an `ansible.cfg` ([text]), in order: the `[defaults]` section's
     * `roles_path`, split on `:`, without `./`, trailing slashes, absolute paths, `~`, variables or `..`.
     */
    fun rolesPathsFromAnsibleCfg(text: String): List<String> {
        var section = ""
        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) continue
            if (line.startsWith("[") && line.endsWith("]")) {
                section = line.substring(1, line.length - 1).trim().lowercase()
                continue
            }
            if (section != "defaults") continue
            val separator = line.indexOfAny(charArrayOf('=', ':'))
            if (separator <= 0 || line.substring(0, separator).trim().lowercase() != "roles_path") continue
            return line.substring(separator + 1).split(':')
                .map { it.trim().trim('"', '\'').trim().removePrefix("./").trimEnd('/') }
                .filter { it.isNotEmpty() && !it.startsWith("~") && '$' !in it && GoldenGitUrls.rolesPathProblem(it) == null }
        }
        return emptyList()
    }

    private val AUTH_PATTERNS = listOf(
        Regex("""permission denied""", RegexOption.IGNORE_CASE),
        Regex("""authentication failed""", RegexOption.IGNORE_CASE),
        Regex("""could not read (username|password)""", RegexOption.IGNORE_CASE),
        Regex("""terminal prompts disabled""", RegexOption.IGNORE_CASE),
        Regex("""host key verification failed""", RegexOption.IGNORE_CASE),
        Regex("""access denied""", RegexOption.IGNORE_CASE),
        Regex("""invalid (username|credentials)""", RegexOption.IGNORE_CASE),
        Regex("""\b(401|403)\b"""),
        Regex("""error: 400\b"""),
        Regex("""repository .*not found""", RegexOption.IGNORE_CASE),
    )

    /**
     * Whether a failed command was refused for credentials. The Git plugin's SILENT mode DELETES a stored password
     * when it sees such an answer (403, 400, "repository not found" included), so background refreshes pause after one.
     */
    fun isAuthFailure(result: GitResult): Boolean =
        result.authenticationFailed || result.errorOutput.any { line -> AUTH_PATTERNS.any { it.containsMatchIn(line) } }

    /** Whether the server refused the blob filter (rather than ignoring it): the clone is retried without one. */
    fun isFilterRefused(result: GitResult): Boolean =
        result.errorOutput.any { line ->
            val lower = line.lowercase()
            "filter" in lower && ("not support" in lower || "unsupported" in lower || "unknown" in lower || "not allowed" in lower || "fatal" in lower)
        }

    private val CAUSES = listOf(
        "permission denied", "authentication failed", "could not read username", "could not read password",
        "terminal prompts disabled", "host key verification failed", "could not resolve host", "connection refused",
        "connection timed out", "operation timed out", "not found", "does not appear to be a git repository",
        "remote branch", "unable to access", "access denied", "not allowed",
    )
    private val PROGRESS = Regex("""^(remote: )?(Counting|Compressing|Receiving|Resolving|Enumerating|Updating|Checking out|Filtering|Cloning into|Total)\b.*""")
    private const val MAX_ERROR = 200

    /**
     * A short, credential-free text for a failed [result] (D196): the line naming the cause (permission denied, host
     * not found…; a `fatal:`/`error:` one first), else the last `fatal:`/`error:` line, else the last line; prefixes
     * removed, URLs scrubbed, cut at 200 characters.
     */
    fun shortError(result: GitResult): String {
        val lines = result.errorOutput.flatMap { it.split('\r', '\n') }.map { it.trim() }
            .filter { it.isNotEmpty() && !PROGRESS.matches(it) && !it.startsWith("hint:") }
        fun fatal(line: String) = line.startsWith("fatal:") || line.startsWith("error:")
        val causes = lines.filter { line -> CAUSES.any { it in line.lowercase() } }
        val chosen = causes.firstOrNull(::fatal)
            ?: causes.firstOrNull()
            ?: lines.lastOrNull(::fatal)
            ?: lines.lastOrNull()
            ?: return "exit ${result.exitCode}"
        val text = chosen.removePrefix("fatal:").removePrefix("error:").removePrefix("remote:").trim().trimEnd('.')
        val scrubbed = GoldenGitUrls.scrub(text)
        return if (scrubbed.length > MAX_ERROR) scrubbed.take(MAX_ERROR - 1) + "…" else scrubbed
    }

    /** Whether `git --version` output [text] names git 2.25 or later. */
    fun isSupportedVersion(text: String): Boolean {
        val match = Regex("""(\d+)\.(\d+)""").find(text) ?: return false
        val major = match.groupValues[1].toInt()
        val minor = match.groupValues[2].toInt()
        return major > MIN_GIT_MAJOR || major == MIN_GIT_MAJOR && minor >= MIN_GIT_MINOR
    }
}
