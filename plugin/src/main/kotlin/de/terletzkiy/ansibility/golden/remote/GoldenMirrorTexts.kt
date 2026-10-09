package de.terletzkiy.ansibility.golden.remote

import de.terletzkiy.ansibility.golden.AnsibilityGoldenBundle.message
import java.time.Duration
import java.time.Instant

/** The texts of the golden mirror (plan amendment R25), from `AnsibilityGoldenBundle`. URLs are passed in already shown without credentials. */
object GoldenMirrorTexts {
    fun urlProblem(problem: GoldenGitUrls.Problem): String = when (problem) {
        GoldenGitUrls.Problem.BLANK -> message("mirror.url.blank")
        GoldenGitUrls.Problem.CREDENTIALS -> message("mirror.url.credentials")
        GoldenGitUrls.Problem.TRANSPORT -> message("mirror.url.transport")
        GoldenGitUrls.Problem.OPTION -> message("mirror.url.option")
        GoldenGitUrls.Problem.UNSUPPORTED -> message("mirror.url.unsupported")
    }

    fun refProblem(): String = message("mirror.ref.invalid")

    fun rolesPathProblem(): String = message("mirror.rolesPath.invalid")

    fun unavailable(): String = message("mirror.unavailable")

    fun gitMissing(): String = message("mirror.git.missing")

    fun gitTooOld(): String = message("mirror.git.tooOld", GoldenMirrorCommands.MIN_GIT_TEXT)

    fun gitDidNotFinish(): String = message("mirror.git.timeout")

    fun untrusted(): String = message("mirror.untrusted")

    fun declined(): String = message("mirror.declined")

    fun powerSave(): String = message("mirror.paused.powerSave")

    fun authPaused(): String = message("mirror.paused.auth")

    fun sshAgent(): String = message("mirror.paused.sshAgent")

    fun cacheProblem(errorClass: String): String = message("mirror.cache.problem", errorClass)

    fun refNotFound(ref: String): String = message("mirror.ref.notFound", ref)

    fun rolesPathNotFound(path: String): String = message("mirror.rolesPath.notFound", path)

    fun connectedEmpty(): String = message("mirror.test.empty")

    fun connectedDefault(branch: String, commit: String): String = message("mirror.test.default", branch, commit)

    fun connectedRef(tag: Boolean, name: String, commit: String): String =
        message(if (tag) "mirror.test.tag" else "mirror.test.branch", name, commit)

    fun connectedNoRef(ref: String, defaultBranch: String): String = message("mirror.test.noRef", ref, defaultBranch)

    fun consentTitle(): String = message("mirror.consent.title")

    fun consentText(url: String): String = message("mirror.consent.text", url)

    fun consentFetch(): String = message("mirror.consent.fetch")

    fun consentDecline(): String = message("mirror.consent.decline")

    fun progressClone(name: String): String = message("mirror.progress.clone", name)

    fun progressFetch(name: String): String = message("mirror.progress.fetch", name)

    /** "git@host:infra/golden.git (main)", or "(default branch)" while the branch is not known. */
    fun source(url: String, ref: String): String = message("mirror.source", url, ref.ifBlank { message("mirror.source.defaultBranch") })

    fun folderInvalid(): String = message("mirror.folder.invalid")

    fun folderMissing(folder: String): String = message("mirror.folder.missing", folder)

    fun failureTitle(): String = message("mirror.failure.title")

    fun failureText(name: String, error: String): String = message("mirror.failure.text", name, error)

    /**
     * One line for the settings page: "golden · main @ 3f2a1c9 · fetched 2 min ago", "fetching golden…", the error,
     * the pause reason, or "not fetched yet".
     */
    fun status(state: GoldenMirrorState?, now: Instant = Instant.now()): String {
        if (state == null) return ""
        if (state.kind == GoldenMirrorState.Kind.FOLDER) {
            return state.error ?: message("mirror.status.folder", state.name, state.rolesDir?.toString().orEmpty())
        }
        val parts = ArrayList<String>()
        when {
            state.fetching -> parts += message("mirror.status.fetching", state.name)
            state.commit != null -> parts += message("mirror.status.commit", state.name, state.ref ?: "HEAD", state.shortCommit.orEmpty(), ago(state.fetchedAt, now))
            else -> parts += message("mirror.status.none", state.name)
        }
        state.error?.let { parts += message("mirror.status.error", it) }
        if (state.needsConsent) parts += message("mirror.status.consent")
        state.pausedReason?.let { parts += it }
        return parts.joinToString(" · ")
    }

    /** "just now", "5 min ago", "3 h ago", "2 days ago"; "never" without a time. */
    fun ago(at: Instant?, now: Instant = Instant.now()): String {
        if (at == null) return message("mirror.ago.never")
        val duration = Duration.between(at, now).coerceAtLeast(Duration.ZERO)
        return when {
            duration.toMinutes() < 1 -> message("mirror.ago.now")
            duration.toHours() < 1 -> message("mirror.ago.minutes", duration.toMinutes())
            duration.toDays() < 1 -> message("mirror.ago.hours", duration.toHours())
            else -> message("mirror.ago.days", duration.toDays())
        }
    }
}
