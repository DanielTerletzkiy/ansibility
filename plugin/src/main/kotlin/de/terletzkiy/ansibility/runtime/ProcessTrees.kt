package de.terletzkiy.ansibility.runtime

/**
 * Ends a process together with every process it started. A CLI often runs its real work in children: a wrapper
 * (`bwbio`, a shell shim) runs the password manager's CLI, `git fetch` runs `ssh` or `git-remote-https` and credential
 * helpers. Those would otherwise keep running (with an approval sheet they show, holding the output pipes open) after
 * the process itself is gone. Used when a caller is cancelled or a wait times out (vault password managers, the git
 * checks before a run). Any thread.
 */
object ProcessTrees {
    /**
     * Ends [process] and its descendants at once. The descendants are listed first: once [process] is gone they are
     * no longer its children.
     */
    fun end(process: Process) {
        val descendants = runCatching { process.descendants().toList() }.getOrDefault(emptyList())
        process.destroyForcibly()
        descendants.forEach { it.destroyForcibly() }
    }
}
