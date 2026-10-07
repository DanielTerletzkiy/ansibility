package de.terletzkiy.ansibility.run

/** Where a playbook runs: the local `ansible-playbook`, a Compose service, or whichever [PlaybookRunContext] finds. */
enum class PlaybookExecutor { AUTO, NATIVE, DOCKER }

/** What a run executes: the whole playbook, one of its plays, or one role entry of one play. */
enum class TargetKind { PLAYBOOK, PLAY, ROLE }

/**
 * The part of a playbook a run executes. A play is found by [playName] (its `name:`, else its `import_playbook`
 * path; empty for a play with neither) and, when names repeat or are empty, by [playIndex] (0-based among the
 * playbook's top-level items). A role is found the same way among the play's role entries ([roleName],
 * [roleIndex]): the items of `roles:`, then the `import_role`/`include_role` tasks.
 */
data class PlaybookTarget(
    val kind: TargetKind = TargetKind.PLAYBOOK,
    val playIndex: Int = -1,
    val playName: String = "",
    val roleIndex: Int = -1,
    val roleName: String = "",
) {
    /** Whether [other] names the same part: equal names, or equal indices where the names are empty. */
    fun sameAs(other: PlaybookTarget): Boolean {
        if (kind != other.kind) return false
        if (kind == TargetKind.PLAYBOOK) return true
        if (!same(playName, playIndex, other.playName, other.playIndex)) return false
        return kind == TargetKind.PLAY || same(roleName, roleIndex, other.roleName, other.roleIndex)
    }

    private fun same(name: String, index: Int, otherName: String, otherIndex: Int): Boolean =
        if (name.isNotEmpty() || otherName.isNotEmpty()) name == otherName else index == otherIndex

    companion object {
        val PLAYBOOK = PlaybookTarget()

        fun play(index: Int, name: String) = PlaybookTarget(TargetKind.PLAY, index, name)

        fun role(playIndex: Int, playName: String, roleIndex: Int, roleName: String) =
            PlaybookTarget(TargetKind.ROLE, playIndex, playName, roleIndex, roleName)
    }
}

/**
 * What one playbook run asks for, as the user chose it. [environment] is an inventory id of the playbook's root (null
 * when the root has none); [extraVars] holds one `-e` value per line; blank [composeFile]/[composeService] pick the
 * discovered service and a blank [envFile] means `<root>/.env.local` when it exists. [become] passes the become
 * password from the root's runner settings; null is automatic: on when the root has a source for the environment or
 * the target uses `become:` ([PlaybookRunContext.becomeByDefault]). The connection, the become password source and
 * the checks before a run are settings of the root, not of the run ([de.terletzkiy.ansibility.run.settings.RunnerSettings]).
 */
data class PlaybookRunSpec(
    val playbook: String,
    val target: PlaybookTarget = PlaybookTarget.PLAYBOOK,
    val environment: String? = null,
    val limit: String = "",
    val tags: String = "",
    val skipTags: String = "",
    val extraVars: String = "",
    val check: Boolean = false,
    val diff: Boolean = false,
    val verbosity: Int = 0,
    val become: Boolean? = null,
    val skipFreshnessCheck: Boolean = false,
    val executor: PlaybookExecutor = PlaybookExecutor.AUTO,
    val composeFile: String = "",
    val composeService: String = "",
    val envFile: String = "",
    val additionalArgs: String = "",
) {
    companion object {
        const val MAX_VERBOSITY = 4
    }
}
