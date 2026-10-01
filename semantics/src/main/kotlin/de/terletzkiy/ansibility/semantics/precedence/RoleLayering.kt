package de.terletzkiy.ansibility.semantics.precedence

/**
 * One role a play applies, as variable precedence sees it (plan amendment R7/R8, A.14 ExecutionView inputs).
 *
 * Applications are given in the play's execution order with `meta/main.yml` dependencies expanded depth first,
 * each dependency right before the role that declares it (the order `PlayGraph.rolesOfPlay` reports).
 */
data class RoleApplication(
    /** The role's identity (its directory); two applications with the same id are the same role. */
    val id: String,
    /** The role name, which [requiredBy] refers to. */
    val name: String,
    val kind: Kind,
    /** For [Kind.DEPENDENCY]: the name of the role whose `meta/main.yml` declares this one. */
    val requiredBy: String? = null,
) {
    /** How the role takes part in the play. */
    enum class Kind {
        /** An entry of the play's `roles:` list: public (exported to the play) unless `private_role_vars` is on. */
        PLAY_ROLE,

        /** A static `import_role` task: public like a `roles:` entry. */
        IMPORT_ROLE,

        /**
         * A dynamic `include_role` task: `public: false` by default, so its defaults and vars only reach the role's
         * own tasks, never the rest of the play.
         */
        INCLUDE_ROLE,

        /** A `meta/main.yml` dependency, public exactly when the application that pulls it in is. */
        DEPENDENCY,
    }
}

/**
 * The order in which role defaults (level 2) and role vars (level 14) of a play's roles apply for one task, ported
 * from ansible-core's `VariableManager.get_vars`:
 *
 * - every **public** role of the play contributes its defaults first (`role.get_default_vars()` per `play.roles`, a
 *   role's dependencies before it) and its exported vars after `vars_files`;
 * - the **running role** (the task's own role) is re-applied last with its dependency chain
 *   (`task._role.get_default_vars(dep_chain)` and `task._role.get_vars(dep_chain)`), so it wins over the other roles
 *   of the play even when it is not public (an `include_role`).
 *
 * Re-applying a role replaces the values it gave before, so only the **last** application of each role is kept: the
 * winners are exactly ansible-core's, and every definition appears once in a shadow chain.
 */
object RoleLayering {
    /**
     * The result of [order]: [order] lists indices into the applications in the order their defaults (and their role
     * vars) apply, lowest precedence first. [running] is the index of the running role's own application (whose role
     * params and `vars:` keyword apply on top), or null when no application of the play is the running role.
     */
    data class Layering(val order: List<Int>, val running: Int?)

    /**
     * Orders [applications] for a task of [runningRole] (a role name; null for a play-level task). With
     * [privateRoleVars] (`private_role_vars = true` in `ansible.cfg`) no role is public, so only the running role's
     * chain applies.
     */
    fun order(applications: List<RoleApplication>, runningRole: String?, privateRoleVars: Boolean = false): Layering {
        val owner = IntArray(applications.size) { it }
        // A dependency belongs to the next application that is not itself a dependency (dependencies precede it).
        var next = -1
        for (i in applications.indices.reversed()) {
            if (applications[i].kind != RoleApplication.Kind.DEPENDENCY) next = i
            owner[i] = if (next >= 0) next else i
        }
        val running = runningRole?.let { name ->
            applications.indices.firstOrNull { applications[it].name == name && applications[it].kind != RoleApplication.Kind.DEPENDENCY }
                ?: applications.indices.firstOrNull { applications[it].name == name }
        }
        val chain = running?.let { chainOf(applications, it) }.orEmpty()

        val sequence = ArrayList<Int>()
        if (!privateRoleVars) {
            for (i in applications.indices) {
                if (i in chain) continue
                if (applications[owner[i]].kind == RoleApplication.Kind.INCLUDE_ROLE) continue
                sequence += i
            }
        }
        sequence += chain.sorted()
        return Layering(keepLast(applications, sequence), running)
    }

    /**
     * [running] and the dependencies expanded for it: the contiguous run of dependency applications right before it
     * whose `requiredBy` leads to it.
     */
    private fun chainOf(applications: List<RoleApplication>, running: Int): Set<Int> {
        val chain = linkedSetOf(running)
        val names = hashSetOf(applications[running].name)
        var j = running - 1
        while (j >= 0) {
            val application = applications[j]
            if (application.kind != RoleApplication.Kind.DEPENDENCY || application.requiredBy !in names) break
            chain += j
            names += application.name
            j--
        }
        return chain
    }

    /** [sequence] with only the last occurrence of each role id. */
    private fun keepLast(applications: List<RoleApplication>, sequence: List<Int>): List<Int> {
        val seen = HashSet<String>()
        val kept = ArrayList<Int>()
        for (i in sequence.asReversed()) if (seen.add(applications[i].id)) kept += i
        return kept.asReversed()
    }
}
