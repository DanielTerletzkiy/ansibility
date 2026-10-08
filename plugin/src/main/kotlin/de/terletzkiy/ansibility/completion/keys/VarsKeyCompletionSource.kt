package de.terletzkiy.ansibility.completion.keys

import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.openapi.progress.ProgressManager
import de.terletzkiy.ansibility.api.AnsibleSite
import de.terletzkiy.ansibility.api.CompletionSource
import de.terletzkiy.ansibility.api.VarService
import de.terletzkiy.ansibility.context.MoleculeView
import de.terletzkiy.ansibility.semantics.schema.OptionSpec
import de.terletzkiy.ansibility.semantics.schema.OptionType
import de.terletzkiy.ansibility.vars.SpecOptions
import de.terletzkiy.ansibility.vars.VarRanking

/**
 * Key completion in vars-like places (plan F4.2, M3 WU A6): `defaults/`, `vars/`, `group_vars`/`host_vars` files,
 * molecule vars, `hosts.yml` group vars and host entries, molecule `provisioner.inventory`, play/block/task `vars:`,
 * `set_fact` arguments and role parameters ([VarsCompletionRequest] decides where).
 *
 * - **Top-level keys:** the argument-spec options and defaults-only keys of the root's roles ([KeyCatalogs]); the roles
 *   the mapping is meant for come first ([AppliedRoles]: for `group_vars`/`host_vars`, the roles of the plays that
 *   target the group's or host's hosts). Required options without a default are bold; the tail names the declaring
 *   roles, the type text is the spec type. Keys already in the mapping are left out. Names only the root's inventory
 *   sets follow last, greyed out.
 * - **Nested keys** inside `dict` and `list[dict]` values: the spec `options` of the variable at the key path
 *   ([VarService] symbol → spec bindings → [SpecOptions]), required first, siblings already written left out; inside
 *   a sequence item for lists of dicts.
 *
 * Accepting an item writes `key: `. Nothing is offered inside Jinja (the Jinja sources own it) or at structural keys
 * of tasks and plays (module names, options and keywords belong to the task sources). Needs smart mode (the variable
 * index), so it is not `DumbAware`.
 */
class VarsKeyCompletionSource : CompletionSource {
    override fun complete(site: AnsibleSite?, parameters: CompletionParameters, result: CompletionResultSet) {
        if (site != null && site !is AnsibleSite.VarKey) return
        val request = VarsCompletionRequest.of(parameters) ?: return
        val position = request.keyPosition ?: return
        val results = result.withPrefixMatcher(position.prefix)
        if (position.container.varPath.isEmpty()) topLevel(request, position, results) else nested(request, position, results)
    }

    private fun topLevel(request: VarsCompletionRequest, position: KeyPosition, result: CompletionResultSet) {
        val applied = AppliedRoles.of(request, position.container)
        val catalog = KeyCatalogs.getInstance(request.project).topLevel(request.root)
        val matcher = result.prefixMatcher
        val offered = HashSet<String>(position.existingKeys)
        for ((name, declarations) in catalog.byName) {
            ProgressManager.checkCanceled()
            // Items the typed prefix rules out are never built (a root declares hundreds of names).
            if (name in offered || !matcher.prefixMatches(name)) continue
            offered += name
            val ordered = declarations.sortedBy { if (it.role in applied) 0 else 1 }
            val isApplied = ordered.first().role in applied
            result.addElement(KeyLookups.topLevel(name, ordered, isApplied, doc(request, name, emptyList(), name)))
        }
        for (name in KeyCatalogs.getInstance(request.project).inventoryNames(request.root)) {
            ProgressManager.checkCanceled()
            if (!matcher.prefixMatches(name) || !offered.add(name)) continue
            result.addElement(KeyLookups.inventoryOnly(name, doc(request, name, emptyList(), name)))
        }
    }

    private fun nested(request: VarsCompletionRequest, position: KeyPosition, result: CompletionResultSet) {
        val path = position.container.varPath
        val name = path.first()
        val rest = path.drop(1)
        val insideItem = rest.lastOrNull()?.toIntOrNull() != null
        val symbol = VarService.getInstance(request.project).symbol(request.root, name)
        if (symbol.specBindings.isEmpty()) return
        val ranking = VarRanking(
            request.project, request.root, request.context.roleName, request.file, request.context.kind, symbol,
            MoleculeView.of(request.project, request.file),
        )
        // Sub-option name → (the option of the best-ranked role that declares it, every declaring role).
        val options = LinkedHashMap<String, Pair<OptionSpec, MutableList<String>>>()
        for (binding in ranking.bindings) {
            ProgressManager.checkCanceled()
            val container = SpecOptions.resolve(binding.option, rest)?.option ?: continue
            // A list holds keys only inside its items; `haproxy_servers:` itself takes `- ` entries.
            if (container.type == OptionType.List && !insideItem) continue
            for (option in container.options?.values.orEmpty()) {
                options.getOrPut(option.name) { option to ArrayList() }.second += binding.role.name
            }
        }
        val existing = position.existingKeys
        val candidates = options.values.filter { (option, _) -> option.name !in existing && option.aliases.none { it in existing } }
        // Required first, each group in spec order.
        candidates.sortedBy { if (it.first.required) 0 else 1 }.forEachIndexed { rank, (option, roles) ->
            result.addElement(KeyLookups.nested(option, roles.distinct(), rank, doc(request, name, rest + option.name, option.name)))
        }
    }

    private fun doc(request: VarsCompletionRequest, name: String, path: List<String>, lookupString: String): VarLookupDoc =
        VarLookupDoc(request.root.dir, name, path, request.file, request.offset, lookupString)
}
