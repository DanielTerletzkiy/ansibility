package de.terletzkiy.ansibility.golden.remote

import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.coroutineToIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.EditorNotifications
import de.terletzkiy.ansibility.golden.AnsibilityGoldenBundle.message
import de.terletzkiy.ansibility.golden.GoldenNotifications
import de.terletzkiy.ansibility.model.drift.DriftTier
import de.terletzkiy.ansibility.model.drift.RoleDriftService
import de.terletzkiy.ansibility.model.role.ExternalGolden
import de.terletzkiy.ansibility.model.role.ExternalGoldenChange
import de.terletzkiy.ansibility.model.role.ExternalGoldenListener
import de.terletzkiy.ansibility.model.role.RoleCatalog
import de.terletzkiy.ansibility.model.role.RoleCatalogSnapshot
import de.terletzkiy.ansibility.toolwindow.AnsibleToolWindowPanel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.TestOnly
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * X128 "golden moved" (plan amendment R25): after a fetch brought a new commit of the git mirror, and drift has been
 * recomputed for the affected names, "golden main moved to 9c1d2e0: 3 roles changed — web and db now differ here ·
 * Show". Only when a tier of one of your copies changed; never for the first clone (no commit before); once per new
 * commit. Show opens the Roles tab with Drifted Only.
 *
 * - **Before**: [ExternalGoldenListener.beforeChange] (the VFS holds the new files, the catalog and drift still show
 *   the old commit; drift does not follow VFS events below a git mirror) keeps the cached tier of every copy outside
 *   the golden root. So that there is something to compare with, the names that have such copies are computed once per
 *   mirror and session in the background (`RoleDriftService.requestNames`, never the all-names pass).
 * - **Changed roles**: `git ls-tree -r` of the old and the new commit below the roles path (trees only, local, no
 *   network), compared by blob id; when the old commit is gone (a re-clone) every name with your copies is checked and
 *   the count is left out.
 * - **After**: `RoleDriftService.drift(name)` of those names (it re-walks the copies), compared tier by tier.
 *
 * Group: the "Ansibility" balloon ([GoldenNotifications.NOTICE_GROUP_ID]), not the sticky "Ansibility Golden" one: it
 * is news, not a result to act on, and a background refresh may bring one every 30 minutes; it stays in the
 * Notifications tool window, and a newer one replaces it.
 */
@Service(Service.Level.PROJECT)
class GoldenMovedNotifier(private val project: Project, private val scope: CoroutineScope) {
    /** The tier of one copy outside the golden root before the change. */
    private class Known(val name: String, val tier: DriftTier)

    private class Captured(val commit: String, val tiers: Map<VirtualFile, Known>)

    /** One copy whose tier changed. */
    private class Transition(val name: String, val before: DriftTier, val after: DriftTier)

    @Volatile
    private var captured: Captured? = null

    @Volatile
    private var last: Notification? = null

    private val primed: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val finished = AtomicLong()

    /** The report running now (tests wait for it). */
    @Volatile
    var reportJob: Job? = null
        private set

    /** How many reports finished (with or without a notification). */
    val reportCount: Long get() = finished.get()

    /** Shows the Roles tab with Drifted Only (the notification's Show); replaced in tests. */
    @Volatile
    internal var show: (Project) -> Unit = AnsibleToolWindowPanel::showDriftedRoles

    internal fun beforeChange(change: ExternalGoldenChange) {
        val new = change.new ?: return
        if (!change.commitChanged || new.kind != GoldenMirrorState.Kind.GIT) return
        captured = Captured(new.state.commit ?: return, tiers(RoleCatalog.getInstance(project).snapshot()))
    }

    internal fun changed(change: ExternalGoldenChange) {
        if (project.isDisposed) return
        // The read-only banner names the commit.
        EditorNotifications.getInstance(project).updateAllNotifications()
        val new = change.new ?: return
        if (new.kind != GoldenMirrorState.Kind.GIT) return
        prime(new)
        if (!change.commitChanged) return
        val before = captured?.takeIf { it.commit == new.state.commit } ?: return
        captured = null
        val oldCommit = change.old?.state?.commit ?: return
        reportJob = scope.launch {
            try {
                report(new, oldCommit, before.tiers)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LOG.warn("golden moved: the report failed (${e.javaClass.name})")
            } finally {
                finished.incrementAndGet()
            }
        }
    }

    /** The cached tier of each copy outside the golden root, of every name that has such a copy. */
    private fun tiers(catalog: RoleCatalogSnapshot): Map<VirtualFile, Known> {
        val drift = RoleDriftService.getInstance(project)
        val tiers = HashMap<VirtualFile, Known>()
        for (name in catalog.names) {
            val local = catalog.copies(name).filter { !it.isExternal }
            if (local.isEmpty()) continue
            val known = drift.cached(name) ?: continue
            for (copy in local) known.copyOf(copy.dir)?.let { tiers[copy.dir] = Known(name, it.tier) }
        }
        return tiers
    }

    /** Once per mirror and session: computes the names with copies outside the golden root, so a later fetch has a "before". */
    private fun prime(external: ExternalGolden) {
        if (!primed.add(external.baseDir.path)) return
        val catalog = RoleCatalog.getInstance(project).snapshot()
        val names = catalog.names.filter { name -> catalog.copies(name).any { !it.isExternal } }
        if (names.isNotEmpty()) RoleDriftService.getInstance(project).requestNames(names)
    }

    private suspend fun report(external: ExternalGolden, oldCommit: String, before: Map<VirtualFile, Known>) {
        if (before.isEmpty()) return
        val newCommit = external.state.commit ?: return
        val changedRoles = changedRoles(external, oldCommit, newCommit)
        val names = before.values.map { it.name }.distinct().filter { changedRoles == null || it in changedRoles }
        val service = RoleDriftService.getInstance(project)
        val transitions = ArrayList<Transition>()
        for (name in names) {
            val drift = service.drift(name) ?: continue
            for ((dir, known) in before) {
                if (known.name != name) continue
                val after = drift.copyOf(dir)?.tier ?: continue
                if (after != known.tier) transitions += Transition(name, known.tier, after)
            }
        }
        if (transitions.isEmpty()) return
        val text = text(external, newCommit, changedRoles?.size, transitions)
        notify(text)
    }

    /** The role names whose files differ between the two commits (trees only), or null when that cannot be told. */
    private suspend fun changedRoles(external: ExternalGolden, oldCommit: String, newCommit: String): Set<String>? {
        val base = external.state.baseDir ?: return null
        val roles = external.state.rolesDir ?: return null
        val rolesPath = GoldenMirrorLastChangeLookup.relativeTo(base, roles) ?: return null
        val listings = listOf(oldCommit, newCommit).map { commit ->
            val result = local(base, GoldenMirrorCommands.lsTreeFilesArgs(commit, rolesPath))
            if (!result.success) return null
            result.output
        }
        return GoldenMirrorCommands.changedRoles(listings[0], listings[1], rolesPath)
    }

    private suspend fun local(dir: Path, args: List<String>): GitResult =
        withContext(Dispatchers.IO) { coroutineToIndicator { _ -> GoldenMirrorLastChangeLookup.runLocal(project, dir, GitOp.LS_TREE, args) } }

    @Nls
    private fun text(external: ExternalGolden, commit: String, changedCount: Int?, transitions: List<Transition>): String {
        val ref = external.state.ref
        val short = commit.take(SHORT_COMMIT)
        val head = if (ref != null) message("moved.head", external.name, ref, short) else message("moved.head.noRef", external.name, short)
        val lead = if (changedCount != null && changedCount > 0) message("moved.lead", head, message("moved.count", changedCount)) else head
        // One phrase per name, the weightiest of its copies' changes: now differs, else now matches, else changed.
        val differ = transitions.filter { !it.before.differs && it.after.differs }.map { it.name }.distinct()
        val match = transitions.filter { it.after == DriftTier.IDENTICAL && it.before != DriftTier.IDENTICAL }.map { it.name }.distinct().filter { it !in differ }
        val other = transitions.map { it.name }.distinct().filter { it !in differ && it !in match }
        val parts = listOfNotNull(
            differ.takeIf { it.isNotEmpty() }?.let { message("moved.differ", names(it), it.size) },
            match.takeIf { it.isNotEmpty() }?.let { message("moved.match", names(it), it.size, external.name) },
            other.takeIf { it.isNotEmpty() }?.let { message("moved.other", names(it)) },
        )
        return message("moved.text", lead, parts.joinToString(message("moved.separator")))
    }

    /** "web", "web and db", "web, db and base", "web, db, base and 4 more". */
    private fun names(names: List<String>): String = when {
        names.size == 1 -> names.single()
        names.size <= MAX_NAMES -> message("moved.names", names.dropLast(1).joinToString(", "), names.last())
        else -> message("moved.names.more", names.take(MAX_NAMES - 1).joinToString(", "), names.size - (MAX_NAMES - 1))
    }

    private fun notify(@Nls text: String) {
        if (project.isDisposed) return
        last?.expire()
        val notification = NotificationGroupManager.getInstance().getNotificationGroup(GoldenNotifications.NOTICE_GROUP_ID)
            .createNotification(message("moved.title"), text, NotificationType.INFORMATION)
            .addAction(NotificationAction.createSimpleExpiring(message("moved.show")) { show(project) })
        last = notification
        notification.notify(project)
    }

    @TestOnly
    fun resetForTests() {
        captured = null
        last = null
        primed.clear()
        show = AnsibleToolWindowPanel::showDriftedRoles
    }

    companion object {
        private val LOG = logger<GoldenMovedNotifier>()
        private const val SHORT_COMMIT = 7
        private const val MAX_NAMES = 4

        fun getInstance(project: Project): GoldenMovedNotifier = project.service()
    }
}

/** Forwards [ExternalGoldenListener] to [GoldenMovedNotifier] (`projectListeners` in `ansibility-golden.xml`). */
class GoldenMovedListener(private val project: Project) : ExternalGoldenListener {
    override fun beforeChange(change: ExternalGoldenChange) {
        if (!project.isDisposed) GoldenMovedNotifier.getInstance(project).beforeChange(change)
    }

    override fun changed(change: ExternalGoldenChange) {
        if (!project.isDisposed) GoldenMovedNotifier.getInstance(project).changed(change)
    }
}
