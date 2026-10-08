package de.terletzkiy.ansibility.vars

import com.intellij.openapi.project.Project
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.context.MoleculeView
import de.terletzkiy.ansibility.resolve.VarViews
import de.terletzkiy.ansibility.semantics.validate.SpecDefaults

/**
 * Variables whose values are never shown because an argument spec keeps them secret (plan amendment R23): a spec of the
 * root documents the name with `no_log` on the option or on a sub-option below it ([SpecDefaults.hasNoLog]), so every
 * value of the variable, wherever it is set, is a secret as a whole. The name- and file-based rules (`vault_*` names,
 * vault files, `!vault` values) are [VaultInfo]'s and the preview rule's. Every declaring spec counts, as in completion:
 * one role's `no_log` keeps the value secret on another role's card too.
 *
 * Call in a read action in smart mode (the root's cached symbol, [VarViews.symbol]).
 */
internal object NoLogVariables {
    fun isNoLog(project: Project, root: AnsibleRoot, name: String, view: MoleculeView): Boolean =
        VarViews.symbol(project, root, name, view).specBindings.any { SpecDefaults.hasNoLog(it.option) }
}
