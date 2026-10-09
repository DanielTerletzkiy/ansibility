package de.terletzkiy.ansibility.golden.align

import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.annotations.RequiresEdt

/**
 * The merge workspace of Align (plan amendment R24, D185): shows an [AlignSession]'s rows and lets the user accept a
 * side or merge, through the session only.
 *
 * Implemented by `golden.vcs.impl.AlignVcsMergeDialog` (the platform's Conflicts dialog, the one of a Git merge), which
 * only the optional fragment `ansibility-vcs.xml` registers, so no always-loaded class touches a VCS class (like
 * `trackedStatusLookup` and `lastChangeLookup`). Without an extension, [AlignFallbackDialog] shows a plain list.
 * Tests register an implementation of their own to drive Accept and Merge….
 */
interface RoleMergeDialog {
    /** Shows the workspace for [session], modal, and returns once it is closed. EDT, outside any read or write action. */
    @RequiresEdt
    fun show(project: Project, session: AlignSession)

    companion object {
        val EP_NAME: ExtensionPointName<RoleMergeDialog> = ExtensionPointName("de.terletzkiy.ansibility.roleMergeDialog")
    }
}
