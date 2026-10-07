package de.terletzkiy.ansibility.api

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.messages.Topic

/** What a role's tests look like from outside the run area: none, never run, running, the last run passed or failed. */
enum class RoleTestState { NONE, NOT_RUN, RUNNING, PASSED, FAILED }

/**
 * The Molecule tests of roles as the tool window shows them (plan amendment R16): whether a role directory has
 * scenarios and how its last run in this session went. Implemented by the run area; [TOPIC] fires when a state changed.
 */
interface RoleTests {
    /** The state of the role at [roleDir]. Cheap, any thread (reads the VFS). */
    fun stateOf(roleDir: VirtualFile): RoleTestState

    fun interface Listener {
        fun changed()
    }

    companion object {
        @Topic.ProjectLevel
        @JvmField
        val TOPIC: Topic<Listener> = Topic(Listener::class.java, Topic.BroadcastDirection.NONE)

        fun getInstance(project: Project): RoleTests = project.service()
    }
}
