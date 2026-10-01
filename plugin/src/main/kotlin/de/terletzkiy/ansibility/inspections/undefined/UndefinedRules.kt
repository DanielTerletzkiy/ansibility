package de.terletzkiy.ansibility.inspections.undefined

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.HostKey
import de.terletzkiy.ansibility.api.RoleInfo
import de.terletzkiy.ansibility.api.RoleRegistry
import de.terletzkiy.ansibility.api.RuntimeMarkerKind
import de.terletzkiy.ansibility.api.VarDefKind
import de.terletzkiy.ansibility.api.VarService
import de.terletzkiy.ansibility.api.VarSymbol
import de.terletzkiy.ansibility.facts.FactsCatalog
import de.terletzkiy.ansibility.model.effective.ExecutionSources
import de.terletzkiy.ansibility.semantics.schema.OptionSpec
import org.jetbrains.annotations.Nls

/**
 * The name and role rules of ANS-V003 (plan amendment R7/R8, F8.12 (a) and (b)), shared by the inspection and the card
 * section.
 *
 * **Always defined** ([isAlwaysDefined]): Jinja globals and constants, ansible-core's special variables and the facts
 * of the bundled catalog (`FactsCatalog`), every `ansible_*` name (facts and connection variables the catalog does not
 * list, `ansible_loop`), and `item` (the loop variable of a task whose loop a render context may not resolve).
 *
 * **Runtime default** ([hasRuntimeDefault]): a key in `defaults/` or `vars/` of the role or of one of its `meta/main.yml`
 * dependencies (transitively). The argument spec's `default:` does **not** count: ansible-core only documents and
 * validates with it and never sets the variable from it (the runtime default always comes from `defaults/`), which is
 * why the card shows "Runtime default" separately.
 */
internal class UndefinedRules(private val project: Project, private val root: AnsibleRoot) {
    private val symbols = HashMap<String, VarSymbol>()
    private val closures = HashMap<String, List<RoleInfo>>()
    private val included = HashMap<Pair<String, String>, Boolean>()

    fun symbol(name: String): VarSymbol = symbols.getOrPut(name) { VarService.getInstance(project).symbol(root, name) }

    /** A loop or index variable of some task of the root (`loop_control.loop_var`, `index_var`): never reported. */
    fun isLoopVariable(name: String): Boolean = symbol(name).definitions.any { it.kind == VarDefKind.LOOP_VAR || it.kind == VarDefKind.INDEX_VAR }

    /** [role] and its `meta/main.yml` dependencies, transitively, each once. */
    fun withDependencies(role: RoleInfo): List<RoleInfo> = closures.getOrPut(role.ref.dir.url) {
        val registry = RoleRegistry.getInstance(project)
        val out = LinkedHashMap<VirtualFile, RoleInfo>()
        fun visit(info: RoleInfo, depth: Int) {
            if (depth > MAX_DEPENDENCY_DEPTH || out.putIfAbsent(info.ref.dir, info) != null) return
            for (dependency in info.metaDependencies) {
                ProgressManager.checkCanceled()
                registry.role(root, dependency)?.let { visit(it, depth + 1) }
            }
        }
        visit(role, 0)
        out.values.toList()
    }

    /** A `defaults/` or `vars/` key of [name] in [role] or its dependencies. */
    fun hasRuntimeDefault(role: RoleInfo, name: String): Boolean =
        definedIn(role, name, setOf(VarDefKind.ROLE_DEFAULT, VarDefKind.ROLE_VAR))

    /**
     * Whether a task of [role] (or of a dependency) sets [name] itself: `set_fact`, `register`, the `vars:` of an
     * `include_tasks`/`include_role` it runs, `template_vars` of a template lookup, or an `include_vars` task whose
     * literal file defines it (or whose `name:` is [name]).
     */
    fun setByRoleTasks(role: RoleInfo, name: String): Boolean =
        definedIn(role, name, setOf(VarDefKind.SET_FACT, VarDefKind.REGISTER, VarDefKind.INCLUDE_PARAMS, VarDefKind.TEMPLATE_VARS)) ||
            includedVars(role, name)

    /** An `include_vars` marker of [name] in the task files of [role] and its dependencies (the role run on its own). */
    private fun includedVars(role: RoleInfo, name: String): Boolean = included.getOrPut(role.ref.dir.url to name) {
        val sources = ExecutionSources.getInstance(project)
        val inputs = sources.inputs(root, null, role.ref.name)
        sources.runtimeMarkers(root, inputs, name).any { it.kind == RuntimeMarkerKind.INCLUDE_VARS }
    }

    /** The spec options of [name] that [role]'s argument specs declare (one per entry point that declares it). */
    fun specOptions(role: RoleInfo, name: String): List<OptionSpec> = role.argumentSpecs.values.mapNotNull { it.options[name] }

    private fun definedIn(role: RoleInfo, name: String, kinds: Set<VarDefKind>): Boolean {
        val dirs = withDependencies(role).map { it.ref.dir }
        return symbol(name).definitions.any { definition ->
            definition.kind in kinds && dirs.any { VfsUtilCore.isAncestor(it, definition.location.file, true) }
        }
    }

    companion object {
        private const val MAX_DEPENDENCY_DEPTH = 16

        /** How many hosts a message lists before "and n more". */
        const val MAX_LISTED_HOSTS: Int = 3

        /** Whether [name] is defined for every task by Jinja or ansible-core itself. */
        fun isAlwaysDefined(name: String): Boolean =
            name in JINJA_GLOBALS || name == "item" || name == "ansible_facts" || name.startsWith(FactsCatalog.INJECTED_PREFIX) ||
                name in FactsCatalog.magicVars || name in FactsCatalog.injected

        /** "prod (prod-prod1, prod-prod2), test (test-test1) and 2 more": at most [MAX_LISTED_HOSTS] hosts, grouped by environment. */
        @Nls
        fun hostsText(hosts: List<HostKey>): String {
            val shown = hosts.take(MAX_LISTED_HOSTS)
            val text = shown.groupBy({ it.environment }, { it.host }).entries.joinToString(", ") { (environment, names) ->
                AnsibilityUndefinedBundle.message("hosts.environment", environment, names.joinToString(", "))
            }
            val more = hosts.size - shown.size
            return if (more > 0) "$text ${AnsibilityUndefinedBundle.message("hosts.more", more)}" else text
        }

        /** "role alloy" or "roles a, b". */
        @Nls
        fun rolesText(roles: List<String>): String =
            if (roles.size == 1) AnsibilityUndefinedBundle.message("v003.role", roles.single())
            else AnsibilityUndefinedBundle.message("v003.roles", roles.joinToString(", "))
    }
}
