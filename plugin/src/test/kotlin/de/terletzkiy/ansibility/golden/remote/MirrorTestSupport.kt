package de.terletzkiy.ansibility.golden.remote

import com.intellij.notification.Notification
import com.intellij.notification.Notifications
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import de.terletzkiy.ansibility.settings.AnsibilityProjectSettings
import de.terletzkiy.ansibility.settings.GoldenRoot
import de.terletzkiy.ansibility.settings.RemoteGolden
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A transport that records every request and answers with [answer] when it returns a result, else runs [delegate]
 * (the system git by default): the tests count git calls and script failures without a network.
 */
class RecordingTransport(@Volatile var delegate: GoldenGitTransport = SystemGitTransport) : GoldenGitTransport {
    val requests = CopyOnWriteArrayList<GitRequest>()

    @Volatile
    var answer: ((GitRequest) -> GitResult?)? = null

    override fun run(request: GitRequest): GitResult {
        requests += request
        return answer?.invoke(request) ?: delegate.run(request)
    }

    fun ops(): List<GitOp> = requests.map { it.op }

    fun clear() = requests.clear()

    companion object {
        fun failure(vararg lines: String): GitResult = GitResult(128, emptyList(), lines.toList())
    }
}

/** Records the notifications of [project] (in tests the platform publishes them at once on the project's bus). */
class RecordedBalloons(project: Project, disposable: Disposable) {
    val all = CopyOnWriteArrayList<Notification>()

    init {
        project.messageBus.connect(disposable).subscribe(Notifications.TOPIC, object : Notifications {
            override fun notify(notification: Notification) {
                all += notification
            }
        })
    }

    /** The golden-root notifications (group "Ansibility", title "Golden root"). */
    val golden: List<Notification>
        get() = all.filter { it.groupId == "Ansibility" && it.title == GoldenMirrorTexts.consentTitle() }
}

object MirrorSettings {
    /** Chooses the git repository [url] as the golden root (consent is the caller's business). */
    fun useGit(project: Project, url: String, ref: String = "", rolesPath: String = "", refreshMinutes: Int = 30, depth: Int = 1) {
        AnsibilityProjectSettings.getInstance(project).update {
            it.copy(drift = it.drift.copy(golden = GoldenRoot.Git, remote = RemoteGolden(url, ref, rolesPath, refreshMinutes, depth)))
        }
    }

    fun useFolder(project: Project, folder: String) {
        AnsibilityProjectSettings.getInstance(project).update { it.copy(drift = it.drift.copy(golden = GoldenRoot.Folder, folder = folder)) }
    }

    fun useNone(project: Project) {
        AnsibilityProjectSettings.getInstance(project).update { it.copy(drift = it.drift.copy(golden = GoldenRoot.None)) }
    }
}
