package de.terletzkiy.ansibility.golden.remote

import com.intellij.ide.PowerSaveMode
import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.coroutineToIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.util.io.NioFiles
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.intellij.util.EnvironmentUtil
import de.terletzkiy.ansibility.golden.GoldenNotifications
import de.terletzkiy.ansibility.golden.remote.GoldenMirrorCommands.RefKind
import de.terletzkiy.ansibility.golden.remote.GoldenMirrorCommands.RemoteRef
import de.terletzkiy.ansibility.golden.remote.GoldenMirrorState.Kind
import de.terletzkiy.ansibility.settings.AnsibilityProjectSettings
import de.terletzkiy.ansibility.settings.AnsibilitySettingsListener
import de.terletzkiy.ansibility.settings.DriftSettings
import de.terletzkiy.ansibility.settings.GoldenRoot
import de.terletzkiy.ansibility.settings.ProjectSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.annotations.TestOnly
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.attribute.FileTime
import java.time.Duration
import java.time.Instant
import kotlin.io.path.isDirectory
import kotlin.io.path.name

/**
 * The golden mirror (plan amendment R25, D193–D196, D201–D203; X125, X127): implements [GoldenMirrors] for the
 * project's external golden root. One mutex serializes every git command and every change of the cache directory.
 *
 * Cache (D194): `<IDE system dir>/ansibility/golden/<key>`, key = [GoldenGitUrls.mirrorKey] of URL and ref; the
 * directory is readable only by you (0700); `.no-hooks` (empty) is `core.hooksPath`, `.template` the clone template.
 * Clones go into `<key>.partial` and replace `<key>` only when complete; a failed clone or refresh keeps the last good
 * mirror (D196). After every change the VFS below the directory is refreshed (asynchronously).
 *
 * Commands ([GoldenMirrorCommands], through [GoldenGitTransport.current]):
 * - first clone: `clone --depth N --filter=blob:none --sparse …`, then `sparse-checkout init --cone` and `set <roles>`
 *   (the roles path found automatically: `roles/`, else the first `roles_path` of the repository's `ansible.cfg`, else
 *   the top level, `sparse-checkout disable`); a server that refuses the filter gets a clone without one;
 * - refresh: `ls-remote` first; only when the commit moved `fetch --depth N` and `checkout --force --detach <commit>`
 *   (retried once, then re-cloned); a changed default branch, depth, or 50 fetches since the clone re-clone;
 * - the mirrored commit from a local `log -1`.
 *
 * Scheduling (D195): every `refreshMinutes` (0: on demand only) while the project is open, trusted and not in power-save
 * mode, after this machine's consent (D203); exponential backoff after failures (interval × 2^failures, at most an
 * hour). Background refreshes never prompt (SILENT) and pause after an authentication failure until an explicit action
 * succeeds (the Git plugin deletes a stored password on such a failure); SSH URLs with an external SSH agent (1Password,
 * Secretive…, which show their own approval prompts) are not refreshed automatically unless this machine opted in
 * ([GoldenMirrorConsents.sshAgentRefresh], R25 step 2). The first clone, Fetch Now and
 * Test Connection may ask for credentials (EXPLICIT); the first clone shows background progress.
 *
 * Errors (D196): a short text without credentials in the state; one balloon per failure streak (group "Ansibility").
 * Never decrypts, never logs a URL or git output.
 */
class GoldenMirrorService(private val project: Project, private val scope: CoroutineScope) : GoldenMirrors {
    /** Who asked for a refresh. */
    private enum class Trigger { AUTOMATIC, EXPLICIT_FULL, EXPLICIT_SILENT }

    /** The parts of the state that are not on disk. */
    private data class Dynamic(
        val fetching: Boolean = false,
        val error: String? = null,
        val pausedReason: String? = null,
        val needsConsent: Boolean = false,
        val consentDeclined: Boolean = false,
    )

    private class MirrorFailure(val text: String, val auth: Boolean) : Exception(text)

    private val mutex = Mutex()
    private val lock = Any()

    // Guarded by [lock].
    private var stateConfig: MirrorConfig? = null
    private var meta: GoldenMirrorMeta? = null
    private var dynamic = Dynamic()
    private var failures = 0
    private var lastAttempt: Instant? = null
    private var streakNotified = false
    private val authPaused = HashSet<String>()
    private val consentAsked = HashSet<String>()
    private val firstCloneTried = HashSet<String>()
    private var consentNotification: Notification? = null

    @Volatile
    private var current: GoldenMirrorState? = null

    private val publications = Channel<GoldenMirrorState?>(Channel.UNLIMITED)
    private val wakeups = Channel<Unit>(Channel.CONFLATED)

    @Volatile
    private var started = false

    // Test seams.
    @Volatile
    private var transportOverride: GoldenGitTransport? = null

    @Volatile
    private var baseOverride: Path? = null

    @Volatile
    private var clock: () -> Instant = Instant::now

    @Volatile
    private var trustedOverride: Boolean? = null

    @Volatile
    private var powerSaveOverride: Boolean? = null

    @Volatile
    private var externalAgentOverride: Boolean? = null

    init {
        scope.launch(Dispatchers.Default) {
            for (state in publications) {
                if (!project.isDisposed) project.messageBus.syncPublisher(GoldenMirrorListener.TOPIC).stateChanged(state)
            }
        }
        project.messageBus.connect(scope).subscribe(
            AnsibilitySettingsListener.TOPIC,
            object : AnsibilitySettingsListener {
                override fun projectSettingsChanged(old: ProjectSettings, new: ProjectSettings) {
                    if (old.drift != new.drift) scope.launch { reconcile(atStart = false) }
                }
            },
        )
        ApplicationManager.getApplication().messageBus.connect(scope).subscribe(
            PowerSaveMode.TOPIC,
            PowerSaveMode.Listener { wake() },
        )
    }

    /** Starts the background refreshes (from [GoldenMirrorActivity]); the first pass cleans up the cache. */
    fun start() {
        synchronized(lock) {
            if (started) return
            started = true
        }
        scope.launch {
            reconcile(atStart = true)
            while (isActive) {
                try {
                    tick()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    LOG.warn("golden mirror refresh failed: ${e.javaClass.simpleName}")
                }
                val wait = untilNextTick()
                withTimeoutOrNull(wait.toMillis()) { wakeups.receive() }
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ GoldenMirrors

    override fun state(): GoldenMirrorState? {
        val config = configNow() ?: return null
        val known = current
        if (known != null && synchronized(lock) { stateConfig } == config) return known
        return synchronized(lock) {
            if (stateConfig != config) adopt(config)
            current
        }
    }

    override fun fetchNow(interactive: Boolean) {
        scope.launch { fetchNowAndWait(interactive) }
    }

    override fun consent(allowed: Boolean) {
        val config = configNow()?.takeIf { it.kind == Kind.GIT && GoldenGitUrls.problem(it.url) == null } ?: return
        GoldenMirrorConsents.getInstance().set(config.url, allowed)
        synchronized(lock) { consentNotification }?.expire()
        if (allowed) {
            fetchNow(interactive = true)
        } else {
            update { it.copy(needsConsent = false, consentDeclined = true, pausedReason = GoldenMirrorTexts.declined()) }
        }
    }

    override fun sshAgentRefreshChanged() {
        // Re-evaluates the pause at once (and refreshes when due), instead of at the next tick.
        scope.launch { tick() }
    }

    /**
     * Runs a local, read-only git command in [dir] with the mirror's safety configuration: no network
     * (`GIT_NO_LAZY_FETCH`), no credential callbacks, literal pathspecs. The golden-side last change (X127, R25 step 2)
     * and X128's changed roles. Blocking: background thread, outside read actions.
     */
    internal fun runLocal(dir: Path, op: GitOp, args: List<String>): GitResult {
        require(op == GitOp.LOG || op == GitOp.LS_TREE || op == GitOp.REV_PARSE) { "not a local read-only command" }
        val request = GitRequest(
            project = project,
            workDir = dir,
            op = op,
            args = args,
            remoteUrl = null,
            interaction = Interaction.BACKGROUND,
            config = GoldenMirrorCommands.safetyConfig(baseDir().resolve(NO_HOOKS)),
            env = GoldenMirrorCommands.environment(Interaction.BACKGROUND, network = false) + GoldenMirrorCommands.LOCAL_LOOKUP_ENV,
            timeoutSeconds = LOCAL_TIMEOUT_SECONDS,
        )
        return (transportOverride ?: GoldenGitTransport.current()).run(request)
    }

    override suspend fun testConnection(url: String, ref: String): ConnectionResult {
        GoldenGitUrls.problem(url)?.let { return ConnectionResult(false, GoldenMirrorTexts.urlProblem(it)) }
        if (GoldenGitUrls.refProblem(ref) != null) return ConnectionResult(false, GoldenMirrorTexts.refProblem())
        if (!trusted()) return ConnectionResult(false, GoldenMirrorTexts.untrusted())
        val effective = GoldenGitUrls.effective(url)
        val base = try {
            ensureBase()
        } catch (e: IOException) {
            return ConnectionResult(false, GoldenMirrorTexts.cacheProblem(e.javaClass.simpleName))
        }
        val result = git(GitOp.LS_REMOTE, base, GoldenMirrorCommands.testConnectionArgs(effective, ref), effective, Interaction.EXPLICIT, timeoutSeconds = LS_REMOTE_TIMEOUT_SECONDS)
        if (!result.success) return ConnectionResult(false, GoldenMirrorCommands.shortError(result))
        if (synchronized(lock) { authPaused.remove(effective) }) wake()
        val head = GoldenMirrorCommands.parseLsRemote(result.output, "")
        val defaultBranch = head?.takeIf { it.kind == RefKind.BRANCH }?.name
        if (ref.isBlank()) {
            return if (head == null) ConnectionResult(false, GoldenMirrorTexts.connectedEmpty())
            else ConnectionResult(true, GoldenMirrorTexts.connectedDefault(defaultBranch ?: "HEAD", head.commit.take(7)))
        }
        val hit = GoldenMirrorCommands.parseLsRemote(result.output, ref)
            ?: return ConnectionResult(false, GoldenMirrorTexts.connectedNoRef(ref.trim(), defaultBranch ?: "HEAD"))
        return ConnectionResult(true, GoldenMirrorTexts.connectedRef(hit.kind == RefKind.TAG, hit.name, hit.commit.take(7)))
    }

    // ------------------------------------------------------------------------------------------------ scheduling

    /** Fetch Now: see [GoldenMirrors.fetchNow]. Suspends until done (tests call it directly). */
    internal suspend fun fetchNowAndWait(interactive: Boolean) {
        val config = configNow() ?: return
        if (config.kind == Kind.FOLDER) {
            mutex.withLock { synchronized(lock) { adopt(config) } }
            current?.baseDir?.let { LocalFileSystem.getInstance().refreshNioFiles(listOf(it), true, true, null) }
            return
        }
        if (isInvalid(config)) {
            update { it.copy(error = invalidText(config)) }
            return
        }
        if (!trusted()) {
            update { it.copy(error = GoldenMirrorTexts.untrusted(), pausedReason = GoldenMirrorTexts.untrusted()) }
            return
        }
        // A user action: it is this machine's consent (D203).
        GoldenMirrorConsents.getInstance().set(config.url, true)
        synchronized(lock) { consentNotification }?.expire()
        update { it.copy(needsConsent = false, consentDeclined = false, pausedReason = it.pausedReason.takeIf { reason -> reason != GoldenMirrorTexts.declined() }) }
        refresh(config, if (interactive) Trigger.EXPLICIT_FULL else Trigger.EXPLICIT_SILENT)
    }

    /**
     * One pass of the background schedule: updates the gates' state (consent, pause reasons) and refreshes when the
     * interval or the backoff says so. Returns whether it ran a refresh.
     */
    internal suspend fun tick(): Boolean {
        val config = configNow() ?: return false
        if (config.kind == Kind.FOLDER) return false
        val block = automaticBlock(config)
        update {
            it.copy(
                pausedReason = block?.reason,
                needsConsent = block == Block.CONSENT,
                consentDeclined = block == Block.DECLINED,
                error = if (block == Block.INVALID) invalidText(config) else it.error,
            )
        }
        if (block == Block.CONSENT) askConsentOnce(config)
        if (block != null) return false
        if (!isDue(config)) return false
        refresh(config, Trigger.AUTOMATIC)
        return true
    }

    private enum class Block(val text: () -> String?) {
        INVALID({ null }),
        UNTRUSTED(GoldenMirrorTexts::untrusted),
        CONSENT({ null }),
        DECLINED(GoldenMirrorTexts::declined),
        ON_DEMAND({ null }),
        POWER_SAVE(GoldenMirrorTexts::powerSave),
        AUTH(GoldenMirrorTexts::authPaused),
        SSH_AGENT(GoldenMirrorTexts::sshAgent);

        val reason: String? get() = text()
    }

    /** Why no automatic refresh may run now, or null. */
    private fun automaticBlock(config: MirrorConfig): Block? = when {
        isInvalid(config) -> Block.INVALID
        !trusted() -> Block.UNTRUSTED
        GoldenMirrorConsents.getInstance().answer(config.url) == null -> Block.CONSENT
        GoldenMirrorConsents.getInstance().answer(config.url) == false -> Block.DECLINED
        config.refreshMinutes == 0 -> Block.ON_DEMAND
        powerSave() -> Block.POWER_SAVE
        synchronized(lock) { config.url in authPaused } -> Block.AUTH
        GoldenGitUrls.isSsh(config.url) && externalSshAgent() && !GoldenMirrorConsents.getInstance().sshAgentRefresh -> Block.SSH_AGENT
        else -> null
    }

    private fun isInvalid(config: MirrorConfig): Boolean =
        GoldenGitUrls.problem(config.url) != null || GoldenGitUrls.refProblem(config.ref) != null || GoldenGitUrls.rolesPathProblem(config.rolesPath) != null

    private fun invalidText(config: MirrorConfig): String =
        GoldenGitUrls.problem(config.url)?.let(GoldenMirrorTexts::urlProblem)
            ?: if (GoldenGitUrls.refProblem(config.ref) != null) GoldenMirrorTexts.refProblem() else GoldenMirrorTexts.rolesPathProblem()

    private fun isDue(config: MirrorConfig): Boolean {
        val next = nextAutomatic(config) ?: return true
        return !clock().isBefore(next)
    }

    /** When the next automatic refresh is due: null = now. Interval × 2^failures after failures, at most an hour. */
    private fun nextAutomatic(config: MirrorConfig): Instant? = synchronized(lock) {
        val last = lastAttempt ?: return@synchronized null
        val interval = Duration.ofMinutes(config.refreshMinutes.toLong())
        last.plus(backoff(interval, failures))
    }

    private fun untilNextTick(): Duration {
        val config = configNow() ?: return MAX_TICK
        if (config.kind != Kind.GIT || config.refreshMinutes == 0) return MAX_TICK
        val next = nextAutomatic(config) ?: return MIN_TICK
        val wait = Duration.between(clock(), next)
        return wait.coerceIn(MIN_TICK, MAX_TICK)
    }

    private fun wake() {
        wakeups.trySend(Unit)
    }

    private fun askConsentOnce(config: MirrorConfig) {
        val first = synchronized(lock) { consentAsked.add(config.url) }
        if (!first || project.isDisposed) return
        val notification = NotificationGroupManager.getInstance().getNotificationGroup(GoldenNotifications.NOTICE_GROUP_ID)
            .createNotification(GoldenMirrorTexts.consentTitle(), GoldenMirrorTexts.consentText(GoldenGitUrls.display(config.rawUrl)), NotificationType.INFORMATION)
            .addAction(NotificationAction.createSimpleExpiring(GoldenMirrorTexts.consentFetch()) { consent(true) })
            .addAction(NotificationAction.createSimpleExpiring(GoldenMirrorTexts.consentDecline()) { consent(false) })
        synchronized(lock) { consentNotification = notification }
        notification.notify(project)
    }

    // ------------------------------------------------------------------------------------------------ refresh

    private suspend fun refresh(config: MirrorConfig, trigger: Trigger) {
        mutex.withLock {
            if (configNow() != config) return
            val missing = GoldenMirrorMeta.read(baseDir().resolve(config.key)) == null
            val interaction = when (trigger) {
                Trigger.EXPLICIT_FULL -> Interaction.EXPLICIT
                Trigger.EXPLICIT_SILENT -> Interaction.BACKGROUND
                // The first clone may ask once per session (D202); later automatic attempts never do.
                Trigger.AUTOMATIC -> if (missing && synchronized(lock) { firstCloneTried.add(config.url) }) Interaction.EXPLICIT else Interaction.BACKGROUND
            }
            synchronized(lock) { lastAttempt = clock() }
            update { it.copy(fetching = true) }
            try {
                val result = if (interaction == Interaction.EXPLICIT) {
                    val title = if (missing) GoldenMirrorTexts.progressClone(GoldenGitUrls.repositoryName(config.url)) else GoldenMirrorTexts.progressFetch(GoldenGitUrls.repositoryName(config.url))
                    withBackgroundProgress(project, title) { sync(config, interaction) }
                } else {
                    sync(config, interaction)
                }
                synchronized(lock) {
                    failures = 0
                    streakNotified = false
                    if (trigger != Trigger.AUTOMATIC) authPaused.remove(config.url)
                    meta = result
                }
                update { it.copy(fetching = false, error = null, pausedReason = if (it.pausedReason == GoldenMirrorTexts.authPaused()) null else it.pausedReason) }
            } catch (failure: MirrorFailure) {
                val notify = synchronized(lock) {
                    failures++
                    if (interaction == Interaction.BACKGROUND && failure.auth) authPaused.add(config.url)
                    meta = GoldenMirrorMeta.read(baseDir().resolve(config.key))
                    !streakNotified.also { streakNotified = true }
                }
                val paused = synchronized(lock) { config.url in authPaused }
                update { it.copy(fetching = false, error = failure.text, pausedReason = if (paused) GoldenMirrorTexts.authPaused() else it.pausedReason) }
                if (notify) notifyFailure(config, failure.text)
            } catch (e: CancellationException) {
                update { it.copy(fetching = false) }
                throw e
            }
        }
    }

    /** Clones or refreshes the mirror of [config]; returns its new metadata. Under [mutex]. */
    private suspend fun sync(config: MirrorConfig, interaction: Interaction): GoldenMirrorMeta {
        val base = try {
            ensureBase()
        } catch (e: IOException) {
            throw MirrorFailure(GoldenMirrorTexts.cacheProblem(e.javaClass.simpleName), auth = false)
        }
        val dir = base.resolve(config.key)
        val meta = GoldenMirrorMeta.read(dir)
        if (meta == null || meta.url != config.url || meta.ref != config.ref || meta.depth != config.depth ||
            meta.fetches >= GoldenMirrorMeta.RECLONE_AFTER_FETCHES
        ) {
            return clone(config, base, interaction)
        }
        val listed = git(GitOp.LS_REMOTE, dir, GoldenMirrorCommands.lsRemoteArgs(config.url, config.ref), config.url, interaction, timeoutSeconds = LS_REMOTE_TIMEOUT_SECONDS)
        if (!listed.success) throw failureOf(listed)
        val remote = GoldenMirrorCommands.parseLsRemote(listed.output, config.ref)
            ?: throw MirrorFailure(GoldenMirrorTexts.refNotFound(config.ref.ifBlank { "HEAD" }), auth = false)
        if (config.ref.isBlank() && remote.name != meta.branch) return clone(config, base, interaction)
        val head = git(GitOp.REV_PARSE, dir, GoldenMirrorCommands.REV_PARSE_HEAD_ARGS, null, interaction)
        var next = meta
        if (!head.success || head.output.firstOrNull()?.trim() != remote.commit) {
            val fetched = git(GitOp.FETCH, dir, GoldenMirrorCommands.fetchArgs(remote, config.depth), config.url, interaction)
            if (!fetched.success) throw failureOf(fetched)
            if (!checkout(dir, remote, config, interaction)) return clone(config, base, interaction)
            next = next.copy(fetches = next.fetches + 1)
        }
        if (meta.rolesSetting != config.rolesPath) {
            next = next.copy(rolesSetting = config.rolesPath, rolesPath = applyRoles(dir, config, interaction))
        }
        next = withCommit(dir, next, interaction).copy(fetchedAt = clock())
        next.write(dir)
        refreshVfs(dir)
        return next
    }

    /** `checkout --force --detach`, retried once; false when it still fails (the caller re-clones). */
    private suspend fun checkout(dir: Path, remote: RemoteRef, config: MirrorConfig, interaction: Interaction): Boolean {
        repeat(2) {
            if (git(GitOp.CHECKOUT, dir, GoldenMirrorCommands.checkoutArgs(remote.commit), config.url, interaction).success) return true
        }
        return false
    }

    /** A fresh clone into `<key>.partial`, swapped in for `<key>` when complete; on failure the old mirror stays. */
    private suspend fun clone(config: MirrorConfig, base: Path, interaction: Interaction): GoldenMirrorMeta {
        val dir = base.resolve(config.key)
        val partial = base.resolve(config.key + PARTIAL)
        delete(partial)
        try {
            var filtered = true
            var cloned = git(GitOp.CLONE, base, cloneArgs(config, filter = true, partial), config.url, interaction, progress = true)
            if (!cloned.success && GoldenMirrorCommands.isFilterRefused(cloned)) {
                // A server without partial clone: the whole shallow tree instead (D194).
                delete(partial)
                filtered = false
                cloned = git(GitOp.CLONE, base, cloneArgs(config, filter = false, partial), config.url, interaction, progress = true)
            }
            if (!cloned.success) throw failureOf(cloned)
            setOwnerOnly(partial)
            val head = git(GitOp.REV_PARSE, partial, GoldenMirrorCommands.ABBREV_HEAD_ARGS, null, interaction).output.firstOrNull()?.trim().orEmpty()
            val kind = when {
                config.ref.isBlank() -> RefKind.BRANCH
                head == "HEAD" -> RefKind.TAG
                else -> RefKind.BRANCH
            }
            val rolesPath = applyRoles(partial, config, interaction)
            var meta = GoldenMirrorMeta(
                url = config.url,
                ref = config.ref,
                refKind = kind,
                branch = config.ref.ifBlank { head },
                depth = config.depth,
                rolesSetting = config.rolesPath,
                rolesPath = rolesPath,
                filtered = filtered,
                fetches = 0,
                commit = null,
                author = null,
                commitInstant = null,
                subject = null,
                fetchedAt = clock(),
            )
            meta = withCommit(partial, meta, interaction)
            meta.write(partial)
            swap(partial, dir, base)
            refreshVfs(base)
            return meta
        } catch (e: Throwable) {
            delete(partial)
            throw e
        }
    }

    private fun cloneArgs(config: MirrorConfig, filter: Boolean, partial: Path): List<String> =
        GoldenMirrorCommands.cloneArgs(config.url, config.ref, config.depth, filter, progress = true, templateDir = baseDir().resolve(TEMPLATE), target = partial.name)

    /**
     * The sparse checkout of the roles path (approved: "Roles only"): the setting, else `roles/`, else the first
     * `roles_path` of the repository's `ansible.cfg` that exists, else the whole tree. Returns the roles path ("" = top).
     */
    private suspend fun applyRoles(dir: Path, config: MirrorConfig, interaction: Interaction): String {
        val chosen = if (config.rolesPath.isNotBlank()) {
            if (!isTreeDir(dir, config.rolesPath, interaction)) throw MirrorFailure(GoldenMirrorTexts.rolesPathNotFound(config.rolesPath), auth = false)
            config.rolesPath
        } else {
            val top = git(GitOp.LS_TREE, dir, GoldenMirrorCommands.LS_TREE_TOP_ARGS, null, interaction)
            if (!top.success) throw failureOf(top)
            if (top.output.any { it.trim() == DEFAULT_ROLES }) {
                DEFAULT_ROLES
            } else {
                val cfg = dir.resolve("ansible.cfg")
                val candidates = if (Files.isRegularFile(cfg)) GoldenMirrorCommands.rolesPathsFromAnsibleCfg(readSmall(cfg)) else emptyList()
                candidates.firstOrNull { isTreeDir(dir, it, interaction) }
            }
        }
        if (chosen == null) {
            val disabled = git(GitOp.SPARSE_CHECKOUT, dir, GoldenMirrorCommands.SPARSE_DISABLE_ARGS, config.url, interaction)
            if (!disabled.success) throw failureOf(disabled)
            return ""
        }
        val init = git(GitOp.SPARSE_CHECKOUT, dir, GoldenMirrorCommands.SPARSE_INIT_ARGS, config.url, interaction)
        if (!init.success) throw failureOf(init)
        val set = git(GitOp.SPARSE_CHECKOUT, dir, GoldenMirrorCommands.sparseSetArgs(chosen), config.url, interaction)
        if (!set.success) throw failureOf(set)
        return chosen
    }

    private suspend fun isTreeDir(dir: Path, path: String, interaction: Interaction): Boolean {
        val listed = git(GitOp.LS_TREE, dir, GoldenMirrorCommands.lsTreePathArgs(path), null, interaction)
        return listed.success && listed.output.any { it.trim() == path }
    }

    private suspend fun withCommit(dir: Path, meta: GoldenMirrorMeta, interaction: Interaction): GoldenMirrorMeta {
        val log = git(GitOp.LOG, dir, GoldenMirrorCommands.LOG_ARGS, null, interaction)
        val commit = GoldenMirrorCommands.parseCommit(log.output) ?: return meta
        return meta.copy(commit = commit.hash, author = commit.author, commitInstant = commit.instant, subject = commit.subject)
    }

    private fun failureOf(result: GitResult): MirrorFailure =
        MirrorFailure(GoldenMirrorCommands.shortError(result), auth = !result.startFailed && GoldenMirrorCommands.isAuthFailure(result))

    /** Runs one git command with the safety configuration; a timeout comes back as a failed result. */
    private suspend fun git(
        op: GitOp,
        dir: Path,
        args: List<String>,
        url: String?,
        interaction: Interaction,
        progress: Boolean = false,
        timeoutSeconds: Long = GitRequest.DEFAULT_TIMEOUT_SECONDS,
    ): GitResult {
        val request = GitRequest(
            project = project,
            workDir = dir,
            op = op,
            args = args,
            remoteUrl = url,
            interaction = interaction,
            config = GoldenMirrorCommands.safetyConfig(baseDir().resolve(NO_HOOKS)),
            env = GoldenMirrorCommands.environment(interaction, network = url != null),
            progress = progress,
            timeoutSeconds = timeoutSeconds,
        )
        val transport = transportOverride ?: GoldenGitTransport.current()
        return withTimeoutOrNull(Duration.ofSeconds(timeoutSeconds).plusSeconds(TIMEOUT_GRACE_SECONDS).toMillis()) {
            withContext(Dispatchers.IO) { coroutineToIndicator { _ -> transport.run(request) } }
        } ?: GitResult.notStarted(GoldenMirrorTexts.gitDidNotFinish())
    }

    // ------------------------------------------------------------------------------------------------ settings

    /** Adopts the current setting: the state of the new mirror or folder at once (from disk), clean-up of the old one. */
    private suspend fun reconcile(atStart: Boolean) {
        mutex.withLock {
            val config = configNow()
            val previous = synchronized(lock) {
                val before = stateConfig
                if (before != config || current == null && config != null) adopt(config)
                before
            }
            if (config != null && config.kind == Kind.GIT) touch(baseDir().resolve(config.key))
            cleanUp(atStart, previous?.takeIf { it.kind == Kind.GIT && it.key != config?.key }?.key)
        }
        wake()
    }

    /** Switches the bookkeeping to [config] (under [lock]): new keys start without failures, pauses or backoff. */
    private fun adopt(config: MirrorConfig?) {
        val before = stateConfig
        stateConfig = config
        if (before?.key != config?.key || before?.kind != config?.kind) {
            failures = 0
            lastAttempt = null
            streakNotified = false
            dynamic = Dynamic()
            consentNotification?.expire()
            consentNotification = null
        } else {
            dynamic = dynamic.copy(error = null)
        }
        meta = if (config?.kind == Kind.GIT) GoldenMirrorMeta.read(baseDir().resolve(config.key)) else null
        if (config?.kind == Kind.GIT) {
            val answer = GoldenMirrorConsents.getInstance().answer(config.url)
            dynamic = dynamic.copy(
                needsConsent = answer == null && trusted() && GoldenGitUrls.problem(config.url) == null,
                consentDeclined = answer == false,
            )
        }
        publish(config?.let { compose(it) })
    }

    private fun configNow(): MirrorConfig? = if (project.isDisposed) null else MirrorConfig.of(AnsibilityProjectSettings.getInstance(project).settings)

    /** Applies [change] to the dynamic part of the state and publishes it (when the setting is still the same). */
    private fun update(change: (Dynamic) -> Dynamic) {
        synchronized(lock) {
            val config = stateConfig ?: return
            if (config != configNow()) return
            val next = change(dynamic)
            if (next == dynamic && current != null) return
            dynamic = next
            publish(compose(config))
        }
    }

    /** Under [lock]. */
    private fun publish(state: GoldenMirrorState?) {
        if (state == current && state != null) return
        current = state
        publications.trySend(state)
    }

    /** Under [lock]. */
    private fun compose(config: MirrorConfig): GoldenMirrorState = when (config.kind) {
        Kind.FOLDER -> folderState(config)
        Kind.GIT -> {
            val dir = baseDir().resolve(config.key)
            val known = meta
            val rolesDir = known?.let { if (it.rolesPath.isEmpty()) dir else dir.resolve(it.rolesPath) }?.takeIf { it.isDirectory() }
            val ref = config.ref.ifBlank { known?.branch?.takeIf { it.isNotBlank() && it != "HEAD" } ?: "" }
            GoldenMirrorState(
                kind = Kind.GIT,
                rolesDir = rolesDir,
                baseDir = dir.takeIf { known != null },
                name = GoldenGitUrls.repositoryName(config.url),
                source = GoldenMirrorTexts.source(GoldenGitUrls.display(config.rawUrl), ref),
                commit = known?.commit,
                commitInstant = known?.commitInstant,
                commitAuthor = known?.author,
                commitSubject = known?.subject,
                fetchedAt = known?.fetchedAt,
                fetching = dynamic.fetching,
                error = dynamic.error,
                pausedReason = dynamic.pausedReason,
                needsConsent = dynamic.needsConsent,
                consentDeclined = dynamic.consentDeclined,
                ref = ref.ifBlank { null },
                historyDepth = known?.depth ?: config.depth,
            )
        }
    }

    private fun folderState(config: MirrorConfig): GoldenMirrorState {
        val path = DriftSettings.expandFolder(config.folder)
        val exists = path != null && path.isDirectory()
        val error = when {
            path == null -> GoldenMirrorTexts.folderInvalid()
            !exists -> GoldenMirrorTexts.folderMissing(config.folder)
            else -> null
        }
        val roles = path?.resolve(DEFAULT_ROLES)?.takeIf { exists && it.isDirectory() }
        return GoldenMirrorState(
            kind = Kind.FOLDER,
            rolesDir = if (exists) roles ?: path else null,
            baseDir = path.takeIf { exists },
            name = path?.fileName?.toString() ?: config.folder,
            source = config.folder,
            commit = null,
            commitInstant = null,
            commitAuthor = null,
            commitSubject = null,
            fetchedAt = null,
            fetching = false,
            error = error,
            pausedReason = null,
            needsConsent = false,
        )
    }

    // ------------------------------------------------------------------------------------------------ cache directory

    private fun baseDir(): Path = baseOverride ?: PathManager.getSystemDir().resolve("ansibility").resolve("golden")

    /** The cache directory (0700) with its empty hooks directory and clone template. */
    private fun ensureBase(): Path {
        val base = baseDir()
        Files.createDirectories(base)
        setOwnerOnly(base)
        val hooks = base.resolve(NO_HOOKS)
        Files.createDirectories(hooks)
        // Nothing may live in the hooks directory: git would run it.
        Files.list(hooks).use { entries -> entries.toList() }.forEach(::delete)
        Files.createDirectories(base.resolve(TEMPLATE).resolve("info"))
        return base
    }

    /**
     * Deletes what no open project uses: `*.partial` and `*.old` leftovers, the [previous] mirror of this project, and
     * other mirrors unused for [STALE_AFTER] (other projects may use them; at start only those). Never the current one.
     */
    private fun cleanUp(atStart: Boolean, previous: String?) {
        val base = baseDir()
        if (!base.isDirectory()) return
        val inUse = keysInUse()
        val entries = try {
            Files.list(base).use { it.toList() }
        } catch (_: IOException) {
            return
        }
        var changed = false
        for (entry in entries) {
            val name = entry.name
            if (name == NO_HOOKS || name == TEMPLATE) continue
            val key = name.removeSuffix(PARTIAL).removeSuffix(OLD)
            val leftover = name != key
            val remove = when {
                key in inUse -> false
                leftover -> true
                key == previous -> true
                else -> isStale(entry)
            }
            if (remove) {
                delete(entry)
                changed = true
            }
        }
        if (changed || !atStart) refreshVfs(base)
    }

    /** The mirror keys of every open project (several projects may share the cache). */
    private fun keysInUse(): Set<String> {
        val projects = ProjectManager.getInstance().openProjects.filter { !it.isDisposed } + project
        return projects.mapNotNull { other ->
            MirrorConfig.of(AnsibilityProjectSettings.getInstance(other).settings)?.takeIf { it.kind == Kind.GIT }?.key
        }.toSet()
    }

    private fun isStale(mirror: Path): Boolean {
        val stamp = GoldenMirrorMeta.fileOf(mirror)
        val time = try {
            if (Files.exists(stamp)) Files.getLastModifiedTime(stamp) else Files.getLastModifiedTime(mirror)
        } catch (_: IOException) {
            return true
        }
        return Duration.between(time.toInstant(), clock()) > STALE_AFTER
    }

    private fun touch(mirror: Path) {
        val stamp = GoldenMirrorMeta.fileOf(mirror)
        try {
            if (Files.exists(stamp)) Files.setLastModifiedTime(stamp, FileTime.from(clock()))
        } catch (_: IOException) {
            // Not fatal: the mirror only looks older to the clean-up of other projects.
        }
    }

    private fun swap(partial: Path, dir: Path, base: Path) {
        val old = base.resolve(dir.name + OLD)
        delete(old)
        if (Files.exists(dir)) Files.move(dir, old, StandardCopyOption.ATOMIC_MOVE)
        Files.move(partial, dir, StandardCopyOption.ATOMIC_MOVE)
        delete(old)
    }

    private fun delete(path: Path) {
        try {
            if (Files.exists(path) || Files.isSymbolicLink(path)) NioFiles.deleteRecursively(path)
        } catch (e: IOException) {
            LOG.warn("could not delete a golden mirror directory: ${e.javaClass.simpleName}")
        }
    }

    private fun setOwnerOnly(path: Path) {
        try {
            Files.setPosixFilePermissions(path, OWNER_ONLY)
        } catch (_: UnsupportedOperationException) {
            // Not a POSIX file system (Windows): the profile directory is private already.
        } catch (_: IOException) {
            // Best effort.
        }
    }

    private fun refreshVfs(path: Path) {
        LocalFileSystem.getInstance().refreshNioFiles(listOf(path), true, true, null)
    }

    private fun readSmall(file: Path): String = try {
        Files.newBufferedReader(file).use { reader ->
            val buffer = CharArray(MAX_CFG_CHARS)
            val read = reader.read(buffer)
            if (read <= 0) "" else String(buffer, 0, read)
        }
    } catch (_: IOException) {
        ""
    }

    // ------------------------------------------------------------------------------------------------ environment

    private fun trusted(): Boolean = trustedOverride ?: TrustedProjects.isProjectTrusted(project)

    private fun powerSave(): Boolean = powerSaveOverride ?: PowerSaveMode.isEnabled()

    private fun externalSshAgent(): Boolean = externalAgentOverride ?: ExternalSshAgent.present()

    private fun notifyFailure(config: MirrorConfig, text: String) {
        if (project.isDisposed) return
        NotificationGroupManager.getInstance().getNotificationGroup(GoldenNotifications.NOTICE_GROUP_ID)
            .createNotification(GoldenMirrorTexts.failureTitle(), GoldenMirrorTexts.failureText(GoldenGitUrls.repositoryName(config.url), text), NotificationType.WARNING)
            .notify(project)
    }

    // ------------------------------------------------------------------------------------------------ tests

    /** Test seams; [reset] puts everything back (light projects outlive a test). */
    @TestOnly
    internal fun configureForTests(
        base: Path? = baseOverride,
        transport: GoldenGitTransport? = transportOverride,
        now: (() -> Instant)? = null,
        trusted: Boolean? = trustedOverride,
        powerSave: Boolean? = powerSaveOverride,
        externalAgent: Boolean? = externalAgentOverride,
    ) {
        baseOverride = base
        transportOverride = transport
        now?.let { clock = it }
        trustedOverride = trusted
        powerSaveOverride = powerSave
        externalAgentOverride = externalAgent
    }

    @TestOnly
    internal fun resetForTests() {
        baseOverride = null
        transportOverride = null
        clock = Instant::now
        trustedOverride = null
        powerSaveOverride = null
        externalAgentOverride = null
        synchronized(lock) {
            stateConfig = null
            meta = null
            dynamic = Dynamic()
            failures = 0
            lastAttempt = null
            streakNotified = false
            authPaused.clear()
            consentAsked.clear()
            firstCloneTried.clear()
            consentNotification = null
            current = null
        }
    }

    /** Runs the clean-up as at start (tests). */
    @TestOnly
    internal suspend fun cleanUpForTests(atStart: Boolean, previous: String? = null) = mutex.withLock { cleanUp(atStart, previous) }

    /** Re-reads the setting as after a settings change and waits for it (tests). */
    @TestOnly
    internal suspend fun reconcileForTests() = reconcile(atStart = false)

    /** The cache directory of [url] and [ref] (tests). */
    @TestOnly
    internal fun mirrorDirForTests(url: String, ref: String = ""): Path = baseDir().resolve(GoldenGitUrls.mirrorKey(url, ref))

    @TestOnly
    internal fun failuresForTests(): Int = synchronized(lock) { failures }

    companion object {
        private val LOG = logger<GoldenMirrorService>()
        private const val NO_HOOKS = ".no-hooks"
        private const val TEMPLATE = ".template"
        private const val PARTIAL = ".partial"
        private const val OLD = ".old"
        private const val DEFAULT_ROLES = "roles"
        private const val LS_REMOTE_TIMEOUT_SECONDS = 60L
        private const val LOCAL_TIMEOUT_SECONDS = 30L
        private const val TIMEOUT_GRACE_SECONDS = 5L
        private const val MAX_CFG_CHARS = 64 * 1024
        private val OWNER_ONLY = PosixFilePermissions.fromString("rwx------")
        private val MIN_TICK: Duration = Duration.ofSeconds(5)
        private val MAX_TICK: Duration = Duration.ofMinutes(1)

        /** Mirrors no open project uses are kept this long after their last use (other projects may come back). */
        val STALE_AFTER: Duration = Duration.ofDays(7)

        /** The longest wait between automatic attempts after failures (D195). */
        val MAX_BACKOFF: Duration = Duration.ofHours(1)

        /** [interval] × 2^[failures], at most [MAX_BACKOFF] (the interval itself without failures). */
        fun backoff(interval: Duration, failures: Int): Duration {
            if (failures <= 0) return interval
            val factor = 1L shl failures.coerceAtMost(20)
            val scaled = if (interval.toMinutes() > MAX_BACKOFF.toMinutes() / factor) MAX_BACKOFF else interval.multipliedBy(factor)
            return if (scaled > MAX_BACKOFF) MAX_BACKOFF else scaled
        }

        fun getInstance(project: Project): GoldenMirrorService? = GoldenMirrors.getInstance(project) as? GoldenMirrorService
    }
}

/** The external golden root's setting, as the mirror needs it (plan amendment R25). */
internal data class MirrorConfig(
    val kind: Kind,
    /** [GoldenGitUrls.effective] of the URL. */
    val url: String,
    /** The URL as set (shown without credentials). */
    val rawUrl: String,
    val ref: String,
    val rolesPath: String,
    val refreshMinutes: Int,
    val depth: Int,
    val folder: String,
) {
    val key: String
        get() = GoldenGitUrls.mirrorKey(url, ref)

    companion object {
        fun of(settings: ProjectSettings): MirrorConfig? {
            val drift = settings.drift
            return when (drift.golden) {
                GoldenRoot.Git -> {
                    val remote = drift.remote.normalized()
                    MirrorConfig(
                        kind = Kind.GIT,
                        url = GoldenGitUrls.effective(remote.url),
                        rawUrl = remote.url,
                        ref = remote.ref,
                        rolesPath = remote.rolesPath,
                        refreshMinutes = remote.effectiveRefreshMinutes,
                        depth = remote.effectiveHistoryDepth,
                        folder = "",
                    )
                }
                GoldenRoot.Folder -> MirrorConfig(Kind.FOLDER, "", "", "", "", 0, 0, drift.folder.trim())
                else -> null
            }
        }
    }
}

/**
 * Whether an SSH agent other than the plain macOS launchd agent serves `SSH_AUTH_SOCK` (the Git plugin's rule): such
 * agents (1Password, Secretive…) may show their own approval prompt for every use, so SSH mirrors are then refreshed only
 * on demand (D195).
 */
internal object ExternalSshAgent {
    fun present(): Boolean = isExternal(EnvironmentUtil.getValue("SSH_AUTH_SOCK"))

    /**
     * The rule for the socket path [socket]: none → false; the macOS launchd socket → only when the socket itself is a
     * link (agents such as 1Password or Secretive replace it with one; the Git plugin compares real paths, which also
     * differ for the plain socket below the `/var` link); any other agent socket → true.
     */
    fun isExternal(socket: String?): Boolean {
        if (socket.isNullOrBlank()) return false
        if (!socket.contains("com.apple.launchd")) return true
        return try {
            Files.isSymbolicLink(Path.of(socket))
        } catch (_: Exception) {
            false
        }
    }
}

/** Starts the golden mirror's background refreshes when a project opens (not in tests, which drive it directly). */
class GoldenMirrorActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        if (ApplicationManager.getApplication().isUnitTestMode) return
        GoldenMirrorService.getInstance(project)?.start()
    }
}
