package de.terletzkiy.ansibility.golden.history

import com.intellij.openapi.Disposable
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import java.time.Instant

/**
 * The last committed change of a file or directory (plan amendment R24, D180 "Last changed", D183 diff titles): its
 * author, date, subject (the first line of the message) and short revision id.
 *
 * [kind] says how exact it is (plan amendment R25, D200/X127): the golden mirror holds only the newest commits, so on
 * its side the answer may be the fetched commit or "older than the fetched history"; then [date] is an upper bound.
 */
data class LastChange(val author: String, val date: Instant, val subject: String, val shortHash: String, val kind: Kind = Kind.COMMIT) {
    /** How a [LastChange] relates to the real last change of the file (R25, D200/X127). */
    enum class Kind {
        /** The commit that last changed the file or directory. */
        COMMIT,

        /**
         * The commit a depth-1 golden mirror holds (D200): every file's "last change" there; the real one is at or
         * before it.
         */
        FETCHED_COMMIT,

        /**
         * The file was not changed within the fetched history of a deeper golden mirror (X127): its last change is at
         * or before [date], the oldest fetched commit's date; [author] and [subject] are empty.
         */
        BEFORE_HISTORY,
    }

    /** Whether [date] is only an upper bound of the real last change ([Kind.FETCHED_COMMIT], [Kind.BEFORE_HISTORY]). */
    val isUpperBound: Boolean get() = kind != Kind.COMMIT
}

/**
 * Where the last change of a file comes from (plan amendment R24, D183). Implemented by
 * `golden.vcs.impl.VcsLastChangeLookup`, which only the optional fragment `ansibility-vcs.xml` registers (it loads
 * with `com.intellij.modules.vcs`), so no always-loaded class touches a VCS class. Callers use [LastChanges], never
 * this point directly; without an extension there is no last change.
 */
interface LastChangeLookup {
    /**
     * The last committed change of [file], or null (no VCS, an untracked or ignored file, never committed). Blocking:
     * call on a background thread, never on the EDT or under a read lock; cancellable through the current progress
     * indicator.
     */
    fun lastChange(project: Project, file: VirtualFile): LastChange?

    /** The last committed change below the directory [dir] (a whole role copy), or null. Like [lastChange]. */
    fun lastChangeUnder(project: Project, dir: VirtualFile): LastChange?

    /**
     * Calls [changed] whenever last changes may have changed (a commit, a pull, a branch switch), with the file, or
     * null when many may have, until [parent] is disposed. Any thread; [changed] may run on any thread.
     */
    fun watch(project: Project, parent: Disposable, changed: (VirtualFile?) -> Unit) {}

    companion object {
        val EP_NAME: ExtensionPointName<LastChangeLookup> = ExtensionPointName("de.terletzkiy.ansibility.lastChangeLookup")
    }
}
