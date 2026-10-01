package de.terletzkiy.ansibility.api

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.ModificationTracker
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.GlobalSearchScope
import org.jetbrains.annotations.Nls

// R9 workspace scopes (plan amendment R9, A.15; implemented by WU WS1 in `workspace.WorkspaceScopeServiceImpl`). The workspace scope is the
// first dimension of the Ansible context: per user and project-wide, stored in `settings.WorkspaceState.scope`. It
// only narrows what is listed (tool-window roots and tabs, host pickers, the default result set of cross-repo
// features) and never changes how anything evaluates: hover, completion, Ctrl+B and every inspection stay
// root-scoped (DEV.md rule 6). `AnsibleContextService` does not delegate to it; features that need both read both.

/** What the user chose in the scope selector (F9.1). */
sealed interface ScopeChoice {
    /** Every non-detached root (the default, D39). */
    data object AllRoots : ScopeChoice

    /** The root of the selected editor's file (the same root R8's Ansible context uses). */
    data object CurrentFileRoot : ScopeChoice

    /** A named scope of either `NamedScopesHolder` (shared `.idea/scopes`, local, or predefined), by scope id. */
    data class Named(val scopeId: String) : ScopeChoice

    /** Roots chosen by hand ("Choose roots…"), by `settings.RootKeys` key. */
    data class Roots(val keys: Set<String>) : ScopeChoice
}

/** The roots a [ScopeChoice] covers right now. */
interface WorkspaceScope {
    val choice: ScopeChoice

    /** Non-detached roots that intersect the scope, in display order. */
    val roots: List<AnsibleRoot>

    /** The roots of [roots] that are only partly inside the scope. */
    val partial: Set<AnsibleRoot>

    /** Role libraries kept as drift references even when outside the scope (`golden`). */
    val references: List<AnsibleRoot>

    /** Why the scope covers less than expected ("scope 'falcon' was deleted", "no Ansible file selected"), or null. */
    @get:Nls
    val problem: String?

    /** Whether [file] lies inside the scope. */
    fun contains(file: VirtualFile): Boolean

    /** The scope as a search scope, for cross-root enumeration only (F9.8, F9.9), never for resolution. */
    val searchScope: GlobalSearchScope
}

/**
 * The project's workspace scope (VFS-only and DumbAware; membership is computed per choice, holder stamp and
 * `AnsibleWorkspace.structureTracker`).
 */
interface WorkspaceScopeService {
    /** The current scope; cheap, from any thread. */
    fun current(): WorkspaceScope

    /** Stores [choice] in the per-user workspace state. */
    fun set(choice: ScopeChoice)

    /** Bumps on choice, named-scope holder (`ScopeListener`), structure and (for Current file's root) editor-root changes. */
    val modificationTracker: ModificationTracker

    companion object {
        fun getInstance(project: Project): WorkspaceScopeService = project.service()
    }
}
