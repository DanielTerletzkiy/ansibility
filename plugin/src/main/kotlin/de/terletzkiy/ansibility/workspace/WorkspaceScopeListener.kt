package de.terletzkiy.ansibility.workspace

import com.intellij.util.messages.Topic

/**
 * Notified on the project message bus after `WorkspaceScopeService.modificationTracker` moved: the choice, a named
 * scope (either holder), the Ansible structure, the project roots, the project settings, a refresh or, under
 * "Current file's root", the editor's root changed.
 *
 * Called on any thread (the EDT for holder edits and choices made in the UI, a background thread for editor-root
 * and structure changes); implementations re-read `WorkspaceScopeService.current()` and only schedule their own
 * refresh (the tool window refilters, it never rebuilds its snapshot).
 */
fun interface WorkspaceScopeListener {
    fun scopeChanged()

    companion object {
        @Topic.ProjectLevel
        val TOPIC: Topic<WorkspaceScopeListener> = Topic(WorkspaceScopeListener::class.java, Topic.BroadcastDirection.NONE)
    }
}
