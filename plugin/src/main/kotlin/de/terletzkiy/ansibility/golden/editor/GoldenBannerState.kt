package de.terletzkiy.ansibility.golden.editor

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

/**
 * The editor banners the user hid (plan amendment R24, X120's "Hide"), per user and per file, in the workspace file
 * (never in the team-shared settings, so one person's choice hides nothing for the team).
 *
 * A dismissal holds for the file as it was on disk when it was hidden (its time stamp and length): once the file
 * changes on disk (a save, a VCS update, a push or a take), the banner shows again while the file still differs. No
 * content and no hash is stored. At most [MAX_ENTRIES] files are remembered, the oldest dismissal goes first.
 */
@Service(Service.Level.PROJECT)
@State(name = "AnsibilityGoldenBanner", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class GoldenBannerState : PersistentStateComponent<GoldenBannerState.StateBean> {
    private val lock = Any()

    /** File URL → "timeStamp:length" when hidden, oldest first. */
    private val dismissed = LinkedHashMap<String, String>()

    /** Whether the banner of [file] was hidden and the file has not changed on disk since. Any thread. */
    fun isDismissed(file: VirtualFile): Boolean = synchronized(lock) { dismissed[file.url] == stampOf(file) }

    /** Hides the banner of [file] until the file changes. Any thread. */
    fun dismiss(file: VirtualFile) {
        synchronized(lock) {
            dismissed.remove(file.url)
            dismissed[file.url] = stampOf(file)
            while (dismissed.size > MAX_ENTRIES) dismissed.remove(dismissed.keys.first())
        }
    }

    override fun getState(): StateBean = synchronized(lock) {
        StateBean().also { bean -> bean.dismissed = dismissed.map { (url, stamp) -> "$stamp $url" }.toMutableList() }
    }

    override fun loadState(state: StateBean) {
        synchronized(lock) {
            dismissed.clear()
            for (entry in state.dismissed.takeLast(MAX_ENTRIES)) {
                val stamp = entry.substringBefore(' ', "")
                val url = entry.substringAfter(' ', "")
                if (stamp.isNotEmpty() && url.isNotEmpty()) dismissed[url] = stamp
            }
        }
    }

    /** The XML form: "timeStamp:length url" per hidden banner; nothing when none is hidden. */
    class StateBean {
        var dismissed: MutableList<String> = ArrayList()
    }

    private fun stampOf(file: VirtualFile): String = "${file.timeStamp}:${file.length}"

    companion object {
        /** How many hidden banners are remembered. */
        const val MAX_ENTRIES: Int = 200

        fun getInstance(project: Project): GoldenBannerState = project.service()
    }
}
