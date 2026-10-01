package de.terletzkiy.ansibility.context

import com.intellij.util.messages.Topic

/**
 * Notified on the project message bus after the Ansible structure changed and
 * `AnsibleWorkspace.structureTracker` was bumped. May be called on any thread; implementations only schedule
 * their own refresh.
 */
fun interface AnsibleStructureListener {
    fun structureChanged()

    companion object {
        @Topic.ProjectLevel
        val TOPIC: Topic<AnsibleStructureListener> =
            Topic(AnsibleStructureListener::class.java, Topic.BroadcastDirection.NONE)
    }
}
