package de.terletzkiy.ansibility.model.drift

import com.intellij.util.messages.Topic

/**
 * Notified on the project message bus when the drift of one role name changed: it was computed for the first time,
 * or a copy was edited (on disk or in an unsaved document), created or deleted, or the [DriftOptions] changed.
 *
 * Called on a background thread. Implementations invalidate only that name's rows (for example
 * `StructureTreeModel.invalidateAsync(node, false)`) and read the new result with `RoleDriftService.cached`.
 */
fun interface RoleDriftListener {
    fun driftChanged(roleName: String)

    companion object {
        @Topic.ProjectLevel
        val TOPIC: Topic<RoleDriftListener> = Topic(RoleDriftListener::class.java, Topic.BroadcastDirection.NONE)
    }
}
