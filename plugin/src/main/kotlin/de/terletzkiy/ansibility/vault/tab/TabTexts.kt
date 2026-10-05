package de.terletzkiy.ansibility.vault.tab

import de.terletzkiy.ansibility.vault.AnsibilityVaultBundle
import de.terletzkiy.ansibility.vault.ui.AnsibilityVaultUiBundle
import org.jetbrains.annotations.Nls

/** The `# v6a` messages of [AnsibilityVaultUiBundle]: the whole-file banner, the decrypted tab and its questions. */
internal object TabTexts {
    @Nls
    fun message(key: String, vararg params: Any): String = AnsibilityVaultUiBundle.message(key, *params)

    /** Why the edits of the tab of [fileName] are not saved. */
    @Nls
    fun problem(problem: TabProblem, fileName: String): String = when (problem) {
        is TabProblem.Failed -> message("tab.problem.failed", AnsibilityVaultBundle.failure(problem.failure))
        TabProblem.ChangedOnDisk -> message("tab.problem.changed", fileName)
        TabProblem.Gone -> message("tab.problem.gone", fileName)
    }
}
