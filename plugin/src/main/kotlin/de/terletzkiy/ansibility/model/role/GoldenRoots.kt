package de.terletzkiy.ansibility.model.role

import com.intellij.openapi.project.Project
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.RootKind
import de.terletzkiy.ansibility.settings.GoldenRoot
import de.terletzkiy.ansibility.settings.ProjectSettings
import de.terletzkiy.ansibility.settings.RootKeys

/**
 * The golden root the setting names, resolved against the current roots (plan amendment R24, D177).
 *
 * [root] is null for [GoldenRoot.None], for [GoldenRoot.FirstRoleLibrary] without a role library, and for a
 * [GoldenRoot.Root] whose key no root has ([missing]; the settings page shows it as "<key> (not found)" and drift has
 * no reference). Never a detached root.
 */
data class GoldenResolution(val setting: GoldenRoot, val root: AnsibleRoot?, val missing: Boolean) {
    companion object {
        val NONE: GoldenResolution = GoldenResolution(GoldenRoot.None, null, missing = false)
    }
}

/** Resolves the golden-root setting (plan amendment R24, D177). Pure apart from [RootKeys]; call where the roots are known. */
object GoldenRoots {
    /** The golden root of [settings] among [roots] (detached roots are skipped). */
    fun resolve(project: Project, settings: ProjectSettings, roots: List<AnsibleRoot>): GoldenResolution =
        resolve(project, settings.drift.golden, roots)

    /** The golden root [golden] names among [roots] (detached roots are skipped). */
    fun resolve(project: Project, golden: GoldenRoot, roots: List<AnsibleRoot>): GoldenResolution {
        val candidates = roots.filter { !it.detached }
        return when (golden) {
            GoldenRoot.None -> GoldenResolution.NONE
            GoldenRoot.FirstRoleLibrary -> GoldenResolution(golden, firstRoleLibrary(candidates), missing = false)
            is GoldenRoot.Root -> {
                val root = candidates.firstOrNull { RootKeys.keyOf(project, it.dir) == golden.key }
                GoldenResolution(golden, root, missing = root == null)
            }
        }
    }

    /** The first role library by path (the R9 reference order), or null; detached roots are skipped. */
    fun firstRoleLibrary(roots: List<AnsibleRoot>): AnsibleRoot? =
        roots.filter { !it.detached && it.kind == RootKind.ROLE_LIBRARY }.minWithOrNull(RoleCatalogSnapshot.ROOT_ORDER)
}
