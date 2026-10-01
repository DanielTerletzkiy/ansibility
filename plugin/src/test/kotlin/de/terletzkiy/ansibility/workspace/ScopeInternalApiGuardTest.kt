package de.terletzkiy.ansibility.workspace

import junit.framework.TestCase
import java.io.File

/**
 * The platform's own scope plumbing is `@ApiStatus.Internal` in 262 (plan amendment R9, "Avoided (Internal)" and
 * testing item 8), so the workspace-scope sources must never reference it: neither the Internal classes nor the
 * Internal members of the otherwise public `ScopeViewPane`. A source scan stands in for the planned ArchUnit rule.
 */
class ScopeInternalApiGuardTest : TestCase() {

    fun testTheWorkspaceSourcesUseNoInternalScopeApi() {
        val sources = sourceDir().walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue("the workspace sources are found: ${sourceDir().absolutePath}", sources.size >= 5)
        val violations = sources.flatMap { file ->
            file.readLines().withIndex().mapNotNull { (index, line) ->
                val hit = BANNED_CLASSES.firstOrNull { line.contains(it) } ?: BANNED_MEMBERS.firstOrNull { it.containsMatchIn(line) }?.pattern
                hit?.let { "${file.name}:${index + 1}: $it" }
            }
        }
        assertEquals("Internal scope API in the workspace sources", emptyList<String>(), violations)
    }

    private fun sourceDir(): File {
        val relative = "src/main/kotlin/de/terletzkiy/ansibility/workspace"
        return listOf(File(relative), File("plugin/$relative")).firstOrNull { it.isDirectory } ?: File(relative)
    }

    private companion object {
        /** Fully qualified names, so the plugin's own `workspace.actions.EditScopesAction` does not match. */
        val BANNED_CLASSES = listOf(
            "com.intellij.ide.scopeView.NamedScopeFilter",
            "com.intellij.ide.scopeView.EditScopesAction",
            "com.intellij.ide.util.scopeChooser.ScopeChooserUtils",
            "com.intellij.ide.util.scopeChooser.ScopeModelService",
            "com.intellij.ide.util.scopeChooser.ScopeService",
            "com.intellij.ide.util.scopeChooser.ScopesStateService",
            "com.intellij.ide.util.scopeChooser.ScopeOption",
            "com.intellij.ide.util.scopeChooser.ScopeChooserGroup",
            "com.intellij.ide.util.scopeChooser.AbstractScopeModel",
            "com.intellij.ide.util.scopeChooser.PackageSetChooserCombo",
            "com.intellij.psi.search.scope.packageSet.ScopeIdMapper",
            "com.intellij.ide.actions.searcheverywhere.ScopeSupporting",
            "com.intellij.ide.actions.searcheverywhere.ScopeChooserAction",
            "com.intellij.ide.actions.searcheverywhere.AbstractGotoSEContributor",
        )

        /** `ScopeViewPane.getFilters()` / `getCurrentFilter()` (also as Kotlin properties). */
        val BANNED_MEMBERS = listOf(Regex("""\.(getFilters\(|filters\b|getCurrentFilter\(|currentFilter\b)"""))
    }
}
