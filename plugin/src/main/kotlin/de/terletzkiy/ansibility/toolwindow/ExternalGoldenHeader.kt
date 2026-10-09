package de.terletzkiy.ansibility.toolwindow

import com.intellij.openapi.util.io.FileUtil
import de.terletzkiy.ansibility.golden.AnsibilityGoldenBundle
import de.terletzkiy.ansibility.golden.remote.GoldenMirrorState
import de.terletzkiy.ansibility.golden.remote.GoldenMirrorTexts
import de.terletzkiy.ansibility.model.drift.AnsibilityDriftBundle
import de.terletzkiy.ansibility.model.drift.DriftTexts
import org.jetbrains.annotations.Nls
import java.time.Instant

/**
 * The Roles tab's header line for the external golden root (plan amendment R25, D195/D196/D203), composed from the
 * mirror's state; pure, so every state is testable. The panel turns [links] into [HeaderLink]s.
 *
 * - git, fetched: "Golden root: golden (git, main @ 3f2a1c9, fetched 2 min ago) · 41 names · 6 drifting · Fetch Now ·
 *   Change…";
 * - folder: "Golden root: ~/src/golden (folder) · 41 names · 6 drifting · Change…";
 * - fetching: "… · fetching golden…" (the first clone: "Golden root: fetching golden…");
 * - failed (D196, the last good mirror stays in use, "as of"): "Golden root: golden (git, as of 3f2a1c9, 2 h ago) · … ·
 *   golden: fetch failed 3 min ago (Permission denied (publickey)) · Retry · Change…";
 * - paused: the reason · Fetch Now (for the SSH agent also "Allow background refreshes");
 * - consent (D203): "Waiting for your OK to fetch from <source> · Fetch · Not on this machine · Change…".
 *
 * The relative times follow [now]; the panel renders it again every [TICK_MS].
 */
internal object ExternalGoldenHeader {
    /** What a header link does. */
    enum class Link { FETCH_NOW, RETRY, CONSENT, DECLINE, ALLOW_SSH_AGENT, CHANGE }

    class Line(@Nls val text: String, val links: List<Link>)

    /** How often the relative times are rendered again while the Roles tab is shown. */
    const val TICK_MS: Int = 30_000

    /**
     * The line for [state] (null: no external golden root although the setting says so, e.g. the service is gone).
     * [names] and [drifting] are null while the golden copies are not listed (not fetched yet, folder missing);
     * [errorSince] is when the current error was reported.
     */
    fun compose(state: GoldenMirrorState?, names: Int?, drifting: String?, errorSince: Instant?, now: Instant): Line {
        val links = ArrayList<Link>()
        val parts = ArrayList<String>()
        if (state == null) return Line(golden(AnsibilityGoldenBundle.message("header.external.none")), listOf(Link.CHANGE))
        if (state.needsConsent) {
            parts += AnsibilityGoldenBundle.message("header.external.consent", state.source)
            links += Link.CONSENT
            links += Link.DECLINE
            links += Link.CHANGE
            return Line(parts.joinToString(SEPARATOR), links)
        }
        val counts = if (names != null) listOfNotNull(AnsibilityDriftBundle.message("drift.header.names", names), drifting) else emptyList()
        when (state.kind) {
            GoldenMirrorState.Kind.FOLDER -> {
                val error = state.error
                if (error != null && names == null) {
                    parts += golden(error)
                } else {
                    val path = state.baseDir?.toString() ?: state.source
                    parts += golden(AnsibilityGoldenBundle.message("header.external.folder", FileUtil.getLocationRelativeToUserHome(path)))
                    parts += counts
                }
            }
            GoldenMirrorState.Kind.GIT -> {
                val short = state.shortCommit
                parts += when {
                    short == null && state.fetching -> golden(AnsibilityGoldenBundle.message("header.external.fetching", state.name))
                    short == null -> golden(AnsibilityGoldenBundle.message("header.external.git.none", state.name))
                    state.error != null -> golden(AnsibilityGoldenBundle.message("header.external.git.asOf", state.name, short, GoldenMirrorTexts.ago(state.fetchedAt, now)))
                    else -> golden(AnsibilityGoldenBundle.message("header.external.git", state.name, state.ref ?: "HEAD", short, GoldenMirrorTexts.ago(state.fetchedAt, now)))
                }
                if (short != null && state.fetching) parts += AnsibilityGoldenBundle.message("header.external.fetching", state.name)
                parts += counts
                val error = state.error
                val paused = state.pausedReason
                when {
                    error != null && !state.fetching -> {
                        parts += AnsibilityGoldenBundle.message("header.external.failed", state.name, GoldenMirrorTexts.ago(errorSince ?: now, now), error)
                        links += Link.RETRY
                    }
                    paused != null -> {
                        parts += paused
                        links += Link.FETCH_NOW
                        if (paused == GoldenMirrorTexts.sshAgent()) links += Link.ALLOW_SSH_AGENT
                    }
                    !state.fetching -> links += Link.FETCH_NOW
                }
            }
        }
        links += Link.CHANGE
        return Line(parts.joinToString(SEPARATOR), links)
    }

    /** The text of [link]. */
    @Nls
    fun text(link: Link): String = when (link) {
        Link.FETCH_NOW -> AnsibilityGoldenBundle.message("header.link.fetchNow")
        Link.RETRY -> AnsibilityGoldenBundle.message("header.link.retry")
        Link.CONSENT -> AnsibilityGoldenBundle.message("header.link.consent")
        Link.DECLINE -> AnsibilityGoldenBundle.message("header.link.decline")
        Link.ALLOW_SSH_AGENT -> AnsibilityGoldenBundle.message("header.link.allowSshAgent")
        Link.CHANGE -> AnsibilityDriftBundle.message("drift.header.change")
    }

    /** "Golden root: [text]". */
    @Nls
    private fun golden(@Nls text: String): String = AnsibilityDriftBundle.message("drift.header.golden", text)

    /** The drifting part: [DriftTexts.drifting]. */
    @Nls
    fun drifting(count: Int, complete: Boolean): String = DriftTexts.drifting(count, complete)

    private const val SEPARATOR = " · "
}
