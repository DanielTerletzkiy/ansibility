package de.terletzkiy.ansibility.vault.rotate

import de.terletzkiy.ansibility.vault.VaultLog
import de.terletzkiy.ansibility.vault.identity.SecretPlan
import de.terletzkiy.ansibility.vault.identity.VaultDiscovery
import de.terletzkiy.ansibility.vault.reencrypt.AtomicFiles
import java.nio.file.Path

/**
 * Plan amendment R29 review, decision 17: a crash between writing a temporary file and moving it over a password file
 * or a vault file leaves `.ansibility-rotate-*.tmp` behind, often inside the repository, and a password file's holds
 * the new password in plain text. They are removed at the next unlock of the root (the directories of its password
 * files and the root itself) and by the next rotation's scan of its roots: never while a rotation runs (in any
 * project), and only when older than a minute (review round 2, decision 9), so a running write keeps its file.
 */
object RotationTempFiles {
    /** Removes leftovers next to [discovery]'s password files and in its root directory. Blocking: on `Dispatchers.IO`. */
    fun removeLeftovers(discovery: VaultDiscovery) {
        if (RotationService.isRunning) return
        val directories = LinkedHashSet<Path>()
        discovery.rootPath?.let(directories::add)
        for (identity in discovery.identities) {
            val targets = when (val plan = identity.plan) {
                is SecretPlan.PasswordFile -> listOf(plan.target)
                is SecretPlan.EnvLocal -> listOfNotNull(plan.expected)
                else -> emptyList()
            }
            for (target in targets) {
                target.path?.parent?.let(directories::add)
                target.declared?.parent?.let(directories::add)
            }
        }
        for (directory in directories) {
            val removed = AtomicFiles.removeLeftovers(directory)
            if (removed > 0) VaultLog.event(VaultLog.Operation.LOAD_SOURCE, VaultLog.Event.SOURCE_SKIPPED, directory.toString(), AtomicFiles.TEMP_PREFIX + AtomicFiles.TEMP_SUFFIX)
        }
    }
}
