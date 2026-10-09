package de.terletzkiy.ansibility.golden.remote

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.util.messages.Topic
import java.nio.file.Path
import java.time.Instant

/**
 * The external golden root of plan amendment R25: a shallow, sparse mirror of a git repository in the IDE's cache
 * (D193–D196, D201–D203), or a folder outside the project (X125). The project service registered in
 * `ansibility-golden.xml` (implementation [GoldenMirrorService]).
 *
 * Contract for the golden-root wiring (catalog, drift, header, read-only guards):
 * - [state] is a snapshot, never blocks and may be called on any thread (the EDT and read actions included);
 * - every change of it is published on [GoldenMirrorListener.TOPIC] (project bus, background thread), the first one
 *   after the golden-root setting changes, then for each fetch start, end and error;
 * - after every change of the mirror's files the service refreshes the VFS below [GoldenMirrorState.baseDir]
 *   (asynchronously), so VFS listeners see new, changed and deleted role files;
 * - the mirror is replaced by the next fetch: nothing may write into [GoldenMirrorState.baseDir];
 * - [fetchNow] and [consent] are user actions: call them from an action or a link, never from a background job.
 *
 * Tests replace the service with `ServiceContainerUtil.replaceService(project, GoldenMirrors::class.java, fake,
 * testRootDisposable)`; a fake implements [state], [fetchNow] and [consent] and publishes its own state changes.
 */
interface GoldenMirrors {
    /** The external golden root's current state; null when the golden root setting is not external (Git or Folder). Never blocks; any thread. */
    fun state(): GoldenMirrorState?

    /**
     * Fetch now (D195), in the background; the result shows in [state]. [interactive] = true lets the IDE ask for
     * credentials (FULL); false never prompts (SILENT). A user action: it records this machine's consent to the
     * current URL (D203), runs whatever the refresh interval, power-save mode or the SSH agent say, and a success
     * resumes background refreshes paused by an authentication failure. Untrusted projects never fetch. For a
     * [GoldenMirrorState.Kind.FOLDER] it re-reads the folder.
     */
    fun fetchNow(interactive: Boolean)

    /** D203: this machine's answer for the current URL; allowed starts the first fetch. Kept per user, never in project files. */
    fun consent(allowed: Boolean)

    /**
     * The SSH-agent opt-in ([GoldenMirrorConsents.sshAgentRefresh], per user and machine) changed: the background
     * refreshes of an SSH URL stop or stop pausing for an external SSH agent at once (a user action: the settings page,
     * the Roles header's "Allow background refreshes").
     */
    fun sshAgentRefreshChanged() {}

    /**
     * Test Connection (D195) for the settings page: `git ls-remote` of [url] and [ref] (blank: the default branch),
     * EXPLICIT (the IDE may ask for credentials), whatever the current setting. Suspends; never on a read action.
     */
    suspend fun testConnection(url: String, ref: String): ConnectionResult =
        ConnectionResult(false, GoldenMirrorTexts.unavailable())

    companion object {
        fun getInstance(project: Project): GoldenMirrors = project.service()
    }
}

/** The answer of [GoldenMirrors.testConnection]: whether the repository answered, and a short text without credentials. */
data class ConnectionResult(val success: Boolean, val text: String)

/**
 * A snapshot of the external golden root (plan amendment R25). Kind [Kind.GIT] is the mirror of a repository,
 * [Kind.FOLDER] a folder outside the project (X125; it has no commit and is never fetched).
 */
data class GoldenMirrorState(
    /** GIT or FOLDER (X125). */
    val kind: Kind,
    /** The directory whose child directories are the golden roles; null until there is one. */
    val rolesDir: Path?,
    /** The mirror's (or folder's) top directory, for read-only checks; null until there is one. */
    val baseDir: Path?,
    /** Short UI name: the repository name ("golden") or the folder name. */
    val name: String,
    /** UI text without credentials: "git@host:infra/golden.git (main)" or the folder path. */
    val source: String,
    /** The mirrored commit (GIT): full hash, null until the first fetch. */
    val commit: String?,
    /** The mirrored commit's author date. */
    val commitInstant: Instant?,
    /** The mirrored commit's author name. */
    val commitAuthor: String?,
    /** The mirrored commit's subject line. */
    val commitSubject: String?,
    /** When the mirror was last fetched or confirmed current (`ls-remote`); null until then. */
    val fetchedAt: Instant?,
    /** Whether a clone or refresh is running. */
    val fetching: Boolean,
    /** The last error (short, no credentials), or null; the last good mirror stays in use (D196). */
    val error: String?,
    /** Why background refreshes are paused (auth failure, external SSH agent, power save, untrusted project), or null. */
    val pausedReason: String?,
    /** D203: waiting for this machine's answer ([GoldenMirrors.consent]). */
    val needsConsent: Boolean,
    /** D203: this machine answered "Not on this machine" for the URL; nothing is fetched until [GoldenMirrors.fetchNow]. */
    val consentDeclined: Boolean = false,
    /** The branch or tag the mirror follows: the setting, else the remote's default branch once known; null for FOLDER. */
    val ref: String? = null,
    /** The commits the mirror holds (X127 history depth; 1 = only [commit]); 0 for FOLDER. */
    val historyDepth: Int = 0,
) {
    enum class Kind { GIT, FOLDER }

    /** The first seven characters of [commit]. */
    val shortCommit: String?
        get() = commit?.take(7)
}

/** Published (background thread) whenever the external golden root's state changes; null when the setting is no longer external. */
fun interface GoldenMirrorListener {
    fun stateChanged(state: GoldenMirrorState?)

    companion object {
        @Topic.ProjectLevel
        val TOPIC: Topic<GoldenMirrorListener> = Topic(GoldenMirrorListener::class.java, Topic.BroadcastDirection.NONE)
    }
}
