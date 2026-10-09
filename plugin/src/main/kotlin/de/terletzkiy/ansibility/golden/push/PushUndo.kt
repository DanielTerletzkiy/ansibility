package de.terletzkiy.ansibility.golden.push

import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.annotations.RequiresEdt
import org.jetbrains.annotations.Nls

/**
 * The Undo of one push (plan amendment R24, D190: "Undo asks once and reverts the last push"). `RoleWriter.multi`
 * wrote one global-undo command per root, in [commands] order, each with `UndoConfirmationPolicy.DO_NOT_REQUEST_CONFIRMATION`,
 * so the platform does not ask per root: [undo] asks ONE question through [PushUi.confirmUndo] ("Undo the push of web
 * to falcon, heron?") and then reverts the commands, last first, each only while it is the top of the global undo stack.
 *
 * Public API only: every push names its commands uniquely ("Push web to falcon (#3)", S2), and the top is recognised
 * by `UndoManager.getUndoActionNameAndDescription(null)`, whose description ("Undo Push web to falcon (#3)") is
 * matched against the platform's own wording, captured right after the push from the last command. An undo took
 * place when the command left the top of the undo stack and is now the top of the redo stack (by its effect, never by
 * comparing texts that another push could share). A later command on top (another edit, another push) makes this
 * Undo unavailable; Local History keeps a label per root for that case.
 *
 * Edit › Undo (Ctrl+Z / Cmd+Z) still works on the commands one by one: without a confirmation it reverts the most
 * recent root's command only (from the Project view, or from an editor of a file that root's command changed), with
 * no question. `PushService` watches for that ([observe], U5): it says which repos are still pushed and offers
 * "Undo the Rest", which is this Undo over the [remaining] commands.
 */
class PushUndo private constructor(
    private val project: Project,
    /** The command names of the push, in write order ("Push web to falcon (#3)", "Push web to heron (#3)"). */
    val commands: List<String>,
    /** The repo of each command ("falcon", "heron"). */
    val names: List<String>,
    /** The Local History labels of the push, one per repo, for the notice when Undo is no longer possible. */
    val labels: List<String>,
    private val questionOf: (List<String>) -> String,
    private val prefix: String,
    private val suffix: String,
) {
    enum class Result {
        /** Every command of the push was undone. */
        UNDONE,

        /** Nothing was undone: another command is on top (or this is not the last push). */
        NOT_ON_TOP,

        /** Nothing was undone: the user declined the question, or the platform refused. */
        DECLINED,

        /** Some roots were undone, then the next command was not on top or was not undone. */
        INCOMPLETE,
    }

    /** False once this push was superseded by a later one, or undone as a whole. */
    @Volatile
    internal var current: Boolean = true

    /** The commands an Edit › Undo reverted one by one (U5); a redo brings one back. EDT. */
    private val undone = LinkedHashSet<String>()

    /** True while [undo] itself undoes, so [observe] ignores those commands. EDT. */
    private var undoing = false

    /** The commands still pushed, in write order. EDT. */
    val remaining: List<String> get() = commands.filter { it !in undone }

    /** The repos still pushed ("falcon", "heron"). EDT. */
    val remainingNames: List<String> get() = commands.indices.filter { commands[it] !in undone }.map { names[it] }

    /** The repos an Edit › Undo reverted, in write order ("tern"). EDT. */
    val undoneNames: List<String> get() = commands.indices.filter { commands[it] in undone }.map { names[it] }

    /** The one question asked before undoing what is still pushed ("Undo the push of web to falcon, heron?"). */
    @get:Nls
    val question: String get() = questionOf(remainingNames)

    /** Whether the most recent command still pushed is the top of the global undo stack. EDT. */
    @RequiresEdt
    fun canUndo(): Boolean {
        val last = remaining.lastOrNull() ?: return false
        return current && isOnTop(last)
    }

    /**
     * Asks once ([PushUi.confirmUndo]), then undoes the push's commands still pushed, last first, while each is on top.
     * The top-of-stack check runs before the question and again before each command. EDT.
     */
    @RequiresEdt
    fun undo(): Result {
        if (!canUndo()) return Result.NOT_ON_TOP
        if (!PushUi.getInstance().confirmUndo(project, question)) return Result.DECLINED
        // The modal question lets other events run: check the top again.
        if (!canUndo()) return Result.NOT_ON_TOP
        val manager = UndoManager.getInstance(project)
        val todo = remaining
        undoing = true
        try {
            for ((index, name) in todo.withIndex().reversed()) {
                val first = index == todo.lastIndex
                if (!isOnTop(name)) return if (first) Result.NOT_ON_TOP else Result.INCOMPLETE
                manager.undo(null)
                // By effect: the command left the undo stack's top and is the redo stack's top now.
                if (isOnTop(name) || !isRedoTop(name)) return if (first) Result.DECLINED else Result.INCOMPLETE
                undone += name
            }
        } finally {
            undoing = false
        }
        current = false
        return Result.UNDONE
    }

    /**
     * Notes that the platform finished the command [commandName], an undo ([isUndo]) or a redo (U5: an Edit › Undo of
     * one repo's command). True when it is one of this push's commands and changes what is still pushed. EDT.
     */
    @RequiresEdt
    fun observe(commandName: String, isUndo: Boolean): Boolean {
        if (undoing) return false
        val hit = commands.firstOrNull { commandName.contains(it) } ?: return false
        return if (isUndo) undone.add(hit) else undone.remove(hit)
    }

    private fun isOnTop(name: String): Boolean {
        val manager = UndoManager.getInstance(project)
        if (project.isDisposed || !manager.isUndoAvailable(null)) return false
        return manager.getUndoActionNameAndDescription(null).second == prefix + name + suffix
    }

    private fun isRedoTop(name: String): Boolean {
        val manager = UndoManager.getInstance(project)
        if (project.isDisposed || !manager.isRedoAvailable(null)) return false
        return manager.getRedoActionNameAndDescription(null).second.contains(name)
    }

    override fun toString(): String = "PushUndo($commands)"

    companion object {
        /**
         * The Undo of a push whose [commands] (one per repo of [names], with the Local History [labels]) were just
         * written (the last one is expected on top of the global undo stack), asking [questionOf] the repos still
         * pushed before it undoes; null when there is none or it is not on top. EDT.
         */
        @RequiresEdt
        fun capture(project: Project, commands: List<String>, names: List<String>, labels: List<String>, questionOf: (List<String>) -> String): PushUndo? {
            val last = commands.lastOrNull() ?: return null
            val manager = UndoManager.getInstance(project)
            if (!manager.isUndoAvailable(null)) return null
            val description = manager.getUndoActionNameAndDescription(null).second
            val at = description.lastIndexOf(last)
            if (at < 0) return null
            return PushUndo(project, commands, names, labels, questionOf, description.substring(0, at), description.substring(at + last.length))
        }
    }
}
