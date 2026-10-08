package de.terletzkiy.ansibility.vault.monitor

import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.ThreadingAssertions
import de.terletzkiy.ansibility.vault.actions.VaultFileOperations
import de.terletzkiy.ansibility.vault.ui.AnsibilityVaultUiBundle.message
import org.jetbrains.annotations.TestOnly

/**
 * The vault notification (plan amendment R21, D166): "Ansibility Vault — falcon: 1 broken vault file (web.key),
 * 2 plaintext private keys (db.key, web.pem)", once per new set of findings.
 *
 * - A finding is new when its `SecretFinding.key` (repository, path, category) was not announced yet at its level and
 *   was not silenced, as remembered per user at application level ([SecretNotificationMemory], keyed by the project's
 *   location: a repository cannot silence it). It stays announced while present and for a week after it went away
 *   (branch switches), and a warning that becomes an error (a key committed from the terminal) is announced again.
 * - Nothing is announced before the VCS knows the statuses ([statusesReady]), so a git-ignored key is never announced as
 *   committed right after startup, nor while the Vault tab is in view ([isTabInView]): what the tab shows counts as seen.
 * - Errors use the group [GROUP_ID] (sticky by default), warnings only [WARNINGS_GROUP_ID]; the title is
 *   "Ansibility Vault". Actions: Show (the Vault tab with the new findings selected; never opens a file),
 *   Convert to Whole-File Vault or Encrypt Files… when every new finding has that fix, Don't Show Again for These.
 *   The previous vault notification expires when a new one comes, and when all of its findings are gone.
 * - File names and counts only, never content.
 *
 * EDT.
 */
@Service(Service.Level.PROJECT)
class SecretNotifier(private val project: Project) {
    private var ready = false
    private var shown: Notification? = null
    private var shownKeys: Set<String> = emptySet()

    /** Replaces "is the Vault tab in view" (tests). */
    @TestOnly
    var inViewForTests: (() -> Boolean)? = null

    /** Replaces the clock of [SecretNotificationMemory.remember] (tests). */
    @TestOnly
    var nowForTests: (() -> Long)? = null

    /** The VCS knows the statuses: from the next snapshot on, new findings are announced. */
    fun statusesReady() {
        ThreadingAssertions.assertEventDispatchThread()
        ready = true
    }

    /** Announces the findings of [snapshot] that are new (see the class comment); returns the notification shown, or null. */
    fun snapshotChanged(snapshot: SecretSnapshot): Notification? {
        ThreadingAssertions.assertEventDispatchThread()
        if (!ready || !snapshot.computed || project.isDisposed) return null
        val location = locationOf(project)
        val memory = SecretNotificationMemory.getInstance()
        val current = LinkedHashMap<String, Boolean>()
        for (finding in snapshot.findings) current[finding.key] = finding.isError || current[finding.key] == true
        shown?.let { if (shownKeys.none(current::containsKey)) it.expire() }
        val fresh = snapshot.findings.filter { !memory.isAnnounced(location, it.key, it.isError) }
        memory.remember(location, current, nowForTests?.invoke() ?: System.currentTimeMillis())
        if (fresh.isEmpty() || isTabInView()) return null
        return show(location, fresh)
    }

    private fun show(location: String, fresh: List<SecretFinding>): Notification {
        val errors = fresh.any { it.isError }
        val content = fresh.groupBy { it.group }.map { (group, findings) -> SecretTexts.notificationLine(group, findings, html = true) }
            .joinToString("<br>")
        val notification = NotificationGroupManager.getInstance().getNotificationGroup(if (errors) GROUP_ID else WARNINGS_GROUP_ID)
            .createNotification(message("notification.title"), content, if (errors) NotificationType.ERROR else NotificationType.WARNING)
            .setDisplayId(DISPLAY_ID)
        val keys = fresh.mapTo(LinkedHashSet()) { it.key }
        notification.addAction(NotificationAction.createSimple(message("monitor.notification.action.show")) { SecretTab.show(project, keys) })
        fixAction(fresh)?.let(notification::addAction)
        val levels = fresh.associate { it.key to it.isError }
        notification.addAction(NotificationAction.createSimpleExpiring(message("monitor.notification.action.silence")) {
            SecretNotificationMemory.getInstance().silence(location, levels)
        })
        shown?.expire()
        shown = notification
        shownKeys = keys
        notification.whenExpired { if (shown === notification) shown = null }
        notification.notify(project)
        return notification
    }

    /** Convert or Encrypt when every new finding has that fix; else null. */
    private fun fixAction(fresh: List<SecretFinding>): NotificationAction? {
        val files = fresh.map { it.file }.distinct()
        return when {
            fresh.all { it.fix == SecretFix.CONVERT } ->
                NotificationAction.createSimpleExpiring(message("monitor.notification.action.convert")) { WholeFileConversions.convert(project, files) }
            fresh.all { it.fix == SecretFix.ENCRYPT } ->
                NotificationAction.createSimpleExpiring(message("monitor.notification.action.encrypt")) { VaultFileOperations.getInstance(project).encrypt(files) }
            else -> null
        }
    }

    private fun isTabInView(): Boolean = inViewForTests?.invoke() ?: SecretTab.isInView(project)

    /** The vault notification on screen, or null (tests). */
    @get:TestOnly
    val shownNotification: Notification? get() = shown

    /** Forgets the readiness and the shown notification (tests share a light project). */
    @TestOnly
    fun resetForTests() {
        shown?.expire()
        shown = null
        shownKeys = emptySet()
        ready = false
        inViewForTests = null
        nowForTests = null
    }

    companion object {
        /** The group of vault findings with errors: "Ansibility Vault" (sticky balloon by default). */
        const val GROUP_ID: String = "Ansibility.Vault"

        /** The group of vault findings that are warnings only: "Ansibility Vault warnings" (balloon). */
        const val WARNINGS_GROUP_ID: String = "Ansibility.Vault.Warnings"

        private const val DISPLAY_ID = "ansibility.vault.findings"

        fun getInstance(project: Project): SecretNotifier = project.service()

        /** The key [SecretNotificationMemory] remembers [project] by: its directory, or its location hash. */
        fun locationOf(project: Project): String = project.basePath ?: project.locationHash
    }
}
