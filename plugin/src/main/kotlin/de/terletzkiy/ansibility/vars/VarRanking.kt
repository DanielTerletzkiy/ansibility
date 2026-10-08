package de.terletzkiy.ansibility.vars

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.FileKind
import de.terletzkiy.ansibility.api.PlayGraph
import de.terletzkiy.ansibility.api.SpecBinding
import de.terletzkiy.ansibility.api.VarDefKind
import de.terletzkiy.ansibility.api.VarDefinition
import de.terletzkiy.ansibility.api.VarSymbol
import de.terletzkiy.ansibility.context.MoleculeView
import de.terletzkiy.ansibility.context.MoleculeVisibility

/**
 * How the roles that declare one variable are ranked (plan F1.5), for the card's primary spec and the Ctrl+B chooser:
 * the file's own role → roles applied by the same plays → roles whose spec *and* defaults both define the name →
 * the rest; ties by name. Every role here belongs to [root]; the symbol is already root-scoped (and already in the
 * request's [view]). With [MoleculeView.EXCLUDE] the plays of Molecule files (converge, verify) do not count as the
 * same plays (plan amendment R20, D153).
 */
internal class VarRanking(
    private val project: Project,
    private val root: AnsibleRoot,
    /** The role of the file the question comes from, if any. */
    val ownRole: String?,
    /** The file the question comes from. */
    private val file: VirtualFile,
    private val fileKind: FileKind?,
    val symbol: VarSymbol,
    /** What the request sees of Molecule content. */
    private val view: MoleculeView = MoleculeView.INCLUDE,
) {
    private val playRoles: Set<String> by lazy(LazyThreadSafetyMode.NONE) { rolesInSamePlays() }

    private val rolesWithDefaults: Set<String> by lazy(LazyThreadSafetyMode.NONE) {
        symbol.definitions.filter { it.kind == VarDefKind.ROLE_DEFAULT }.mapNotNullTo(HashSet()) { it.roleName }
    }

    /** Lower ranks first. */
    fun rank(role: String): Int = when {
        role == ownRole -> 0
        role in playRoles -> 1
        role in rolesWithDefaults && symbol.specBindings.any { it.role.name == role } -> 2
        else -> 3
    }

    val roleOrder: Comparator<String> = compareBy<String>({ rank(it) }, { it })

    /** Spec bindings, best first. */
    val bindings: List<SpecBinding> by lazy(LazyThreadSafetyMode.NONE) {
        symbol.specBindings.sortedWith(compareBy<SpecBinding, String>(roleOrder) { it.role.name }.thenBy { it.location.file.path })
    }

    /** Every role with a spec option, a default or a role var for the name, best first. */
    val declaringRoles: List<String> by lazy(LazyThreadSafetyMode.NONE) {
        (symbol.specBindings.map { it.role.name } + roleDefinitions().mapNotNull { it.roleName }).distinct().sortedWith(roleOrder)
    }

    /** True when some role of the root declares the name (spec, defaults or role vars). */
    val anyRoleDeclares: Boolean get() = declaringRoles.isNotEmpty()

    /** The role-level definitions (defaults, then role vars) of [role], `main` files first. */
    fun roleDefinitions(role: String, kind: VarDefKind): List<VarDefinition> =
        symbol.definitions.filter { it.kind == kind && it.roleName == role }
            .sortedWith(compareBy<VarDefinition>({ if (it.location.file.nameWithoutExtension == "main") 0 else 1 }, { it.location.file.path }, { it.location.offset }))

    private fun roleDefinitions(): List<VarDefinition> =
        symbol.definitions.filter { it.kind == VarDefKind.ROLE_DEFAULT || it.kind == VarDefKind.ROLE_VAR }

    /** The spec binding of [role], if its spec declares the name. */
    fun bindingOf(role: String): SpecBinding? = bindings.firstOrNull { it.role.name == role }

    /** True when [definition] is written in the own role (or, outside roles, in the same file). */
    fun isLocal(definition: VarDefinition): Boolean {
        val own = ownRole
        return if (own != null) definition.roleName == own else definition.location.file == file
    }

    private fun rolesInSamePlays(): Set<String> {
        val graph = PlayGraph.getInstance(project)
        val result = HashSet<String>()
        val own = ownRole
        if (own != null) {
            for (play in MoleculeVisibility.playsInView(project, view, graph.playsApplying(root, own))) {
                ProgressManager.checkCanceled()
                graph.rolesOfPlay(play).mapTo(result) { it.name }
            }
        } else if (fileKind == FileKind.PLAYBOOK || fileKind == FileKind.MOLECULE_PLAYBOOK) {
            graph.playsOf(file).forEach { play -> play.roles.mapTo(result) { it.name } }
        }
        return result
    }

    companion object {
        /** Kinds set while the play runs, nearest to a reference in the same role or file (plan F1.6). */
        val RUNTIME_KINDS: Set<VarDefKind> = setOf(
            VarDefKind.SET_FACT, VarDefKind.REGISTER, VarDefKind.LOOP_VAR, VarDefKind.INDEX_VAR, VarDefKind.TASK_VARS,
            VarDefKind.BLOCK_VARS, VarDefKind.INCLUDE_PARAMS, VarDefKind.PLAY_VARS, VarDefKind.VARS_FILES,
            VarDefKind.ROLE_PARAMS, VarDefKind.VARS_PROMPT,
        )

        /** Orders [definitions] for a caret at [offset] in [file]: same file before the caret (nearest first), same file after it, then other files. */
        fun nearestFirst(definitions: List<VarDefinition>, file: VirtualFile, offset: Int): List<VarDefinition> =
            definitions.sortedWith(
                compareBy<VarDefinition>(
                    {
                        when {
                            it.location.file != file -> 2
                            it.location.offset <= offset -> 0
                            else -> 1
                        }
                    },
                    { if (it.location.file == file && it.location.offset <= offset) offset - it.location.offset else 0 },
                    { it.location.file.path },
                    { it.location.offset },
                ),
            )

        /** Orders other definitions around [file]: same directory first, then by environment and path. */
        fun byProximity(definitions: List<VarDefinition>, file: VirtualFile): List<VarDefinition> {
            val dir = file.parent
            return definitions.sortedWith(
                compareBy<VarDefinition>(
                    { if (dir != null && it.location.file.parent == dir) 0 else if (dir != null && VfsUtilCore.isAncestor(dir.parent ?: dir, it.location.file, true)) 1 else 2 },
                    { it.environment ?: "" },
                    { it.location.file.path },
                    { it.location.offset },
                ),
            )
        }
    }
}
