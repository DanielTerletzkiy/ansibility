package de.terletzkiy.ansibility.vars

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.AnsibleSite
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.FileContext
import de.terletzkiy.ansibility.api.FileKind
import de.terletzkiy.ansibility.api.RootKind
import de.terletzkiy.ansibility.api.SiteNavigation
import de.terletzkiy.ansibility.api.SourceLocation
import de.terletzkiy.ansibility.api.VarDefKind
import de.terletzkiy.ansibility.api.VarDefinition
import de.terletzkiy.ansibility.api.VarService
import de.terletzkiy.ansibility.resolve.VarUsageQuery
import de.terletzkiy.ansibility.semantics.yaml.YMap
import de.terletzkiy.ansibility.semantics.yaml.YValue
import de.terletzkiy.ansibility.yaml.PsiYValueAdapter
import org.jetbrains.yaml.psi.YAMLKeyValue

/**
 * The variables area's `siteNavigation` (plan F1.4, F1.5, F1.6, F4.5, F4.8; X87): Ctrl+B / Ctrl+click targets for
 * variable references and variable keys, nearest first, all inside the root of the file ([VarService] scoping, so
 * never another root or a detached worktree).
 *
 * From a reference (the first rule with targets wins):
 * 1. a template local goes to its binding;
 * 2. a loop variable ([LoopItems]: the task's loop, or in a template the loops of its rendering tasks): a member
 *    (`item.floating.ssl.cert_file`) goes to the nested spec option of an element of the iterated variable
 *    (`grafana_nginx_sites[].floating.ssl.cert_file`), the bare variable (and any member no spec documents) to the
 *    loop keyword of each task running the loop (X78);
 * 3. inside a file that defines variables (inventory `group_vars`/`host_vars`/`hosts.yml`, molecule inventories, role
 *    `defaults/` and `vars/`, play `vars_files`): the definition the reference sees there ([SeenDefinitions]), an
 *    accessor path to the nested key written there (merge keys and aliases followed);
 * 4. a nested accessor (`haproxy_check.uri`) goes to the nested spec option of every declaring role, if any;
 * 5. the own role's spec option → `defaults/` → `vars/`;
 * 6. `set_fact`/`register`/`loop_var`/task, block and play `vars` in the same role (outside roles: the same file),
 *    before the caret and nearest first, then after it;
 * 7. other roles of the root that declare the name (spec, defaults, vars), ranked by [VarRanking];
 * 8. only when no role declares it: every other definition (inventory, playbook vars, molecule, vault files), the
 *    caret's directory first; `{{ vault_x }}` so reaches the `vault_x` keys of the root's vault files.
 *
 * The key whose value holds the reference (`x: "{{ x }}"`) is never a target of a reference.
 *
 * From a variable key: a nested key goes to the nested spec option (a nested argument_specs option to the same
 * option of the other declaring roles, if any); a top-level key to the declaring roles' spec options, `defaults/` and
 * `vars/` keys (own role first); an inventory-only key to its sibling definitions in other files and environments
 * (X87). The key itself is never a target.
 *
 * Two kinds of top-level keys get no targets, so Ctrl+B there falls through to the platform's "show usages" outcome
 * (plan amendment FU, `vars.usages`): a role's own declaration, i.e. an argument_specs option or a key of the role's
 * `defaults/` or `vars/` (D-FU1), and a `set_fact`, task, block or `include_role` vars key of a name no role declares
 * (D-FU2). Override keys (`group_vars`, `host_vars`, `hosts.yml`, play vars, molecule) keep their navigation.
 */
class VarNavigation : SiteNavigation {
    override fun targets(site: AnsibleSite, file: PsiFile): List<PsiElement> = when (site) {
        is AnsibleSite.VarRef -> Collector.forReference(file, site)
        is AnsibleSite.VarKey -> Collector.forKey(file, site)
        else -> emptyList()
    }

    private class Collector(private val project: Project, private val root: AnsibleRoot, private val ranking: VarRanking) {
        private val targets = LinkedHashMap<SourceLocation, VarTargetElement>()

        /** Locations that are never targets: the key under the caret, the key whose value holds the reference. */
        private val excluded = HashSet<SourceLocation>()
        private val name: String get() = ranking.symbol.name

        fun result(): List<PsiElement> = targets.values.toList()

        fun add(location: SourceLocation, path: List<String>, kindLabel: String, owner: String?) {
            ProgressManager.checkCanceled()
            if (location in excluded || location in targets) return
            val anchor = VarLocations.elementAt(project, location) ?: return
            val display = (listOf(name) + path).joinToString(".")
            val where = VarLocations.label(root, location)
            val text = if (owner != null) {
                AnsibilityVarsBundle.message("target.location.role", owner, kindLabel, where)
            } else {
                AnsibilityVarsBundle.message("target.location", kindLabel, where)
            }
            targets[location] = VarTargetElement(anchor, location, display, text, VarSubject.definition(project, root, name, path, location))
        }

        fun add(definition: VarDefinition) {
            add(definition.location, emptyList(), VarLabels.layer(definition) ?: VarLabels.kind(definition.kind), ownerOf(definition))
        }

        /** The spec option, `defaults/` and `vars/` keys of [role]. */
        fun addRole(role: String) {
            ranking.bindingOf(role)?.let { add(it.location, emptyList(), VarLabels.kind(VarDefKind.SPEC_OPTION), role) }
            ranking.roleDefinitions(role, VarDefKind.ROLE_DEFAULT).forEach { add(it.location, emptyList(), VarLabels.kind(it.kind), role) }
            ranking.roleDefinitions(role, VarDefKind.ROLE_VAR).forEach { add(it.location, emptyList(), VarLabels.kind(it.kind), role) }
        }

        /** The nested spec option [path] (accessors or keys as written) of every declaring role; true when any. */
        fun addNested(path: List<String>): Boolean {
            val before = targets.size
            for (binding in ranking.bindings) {
                val nested = SpecOptions.resolve(binding.option, path) ?: continue
                if (nested.names.isEmpty()) continue
                val key = SpecOptions.keyOf(project, binding, nested.names)?.key ?: continue
                add(SourceLocation(binding.location.file, key.textRange.startOffset), nested.names, VarLabels.kind(VarDefKind.SPEC_OPTION), binding.role.name)
            }
            return targets.size > before
        }

        /**
         * Rule 0 ([SeenDefinitions]): adds the definitions the reference's own file sees; for an accessor path
         * (`haproxy_check.uri`) the nested key of a mapping value when it is written there. True when any was added.
         */
        fun addSeen(seen: List<SeenDefinitions.Seen>, path: List<String>): Boolean {
            val before = targets.size
            for (entry in seen) {
                val nested = if (path.isEmpty()) null else nestedKey(entry.location, path)
                when {
                    nested != null -> {
                        val kindLabel = entry.definition?.let { VarLabels.layer(it) ?: VarLabels.kind(it.kind) } ?: entry.layerLabel.orEmpty()
                        add(nested.first, nested.second, kindLabel, entry.definition?.let(::ownerOf) ?: entry.owner)
                    }
                    entry.definition != null -> add(entry.definition)
                    else -> add(entry.location, emptyList(), entry.layerLabel.orEmpty(), entry.owner)
                }
            }
            return targets.size > before
        }

        /**
         * The deepest key along [path] below the mapping value of the key at [location] and the steps it covers, merge
         * keys (`<<: *base`) and aliases followed (a merged key points into its anchor); null when not even the first
         * step is written there.
         */
        private fun nestedKey(location: SourceLocation, path: List<String>): Pair<SourceLocation, List<String>>? {
            val keyValue = VarLocations.keyValueAt(project, location) ?: return null
            var value: YValue = PsiYValueAdapter.valueOf(keyValue)
            var found: Pair<SourceLocation, List<String>>? = null
            for ((index, step) in path.withIndex()) {
                val entry = (value as? YMap)?.entries?.lastOrNull { it.key.text == step } ?: break
                val start = entry.key.range?.start ?: break
                found = SourceLocation(location.file, start) to path.subList(0, index + 1)
                value = entry.value
            }
            return found
        }

        private fun ownerOf(definition: VarDefinition): String? =
            definition.roleName ?: definition.environment?.let { AnsibilityVarsBundle.message("card.this.environment", it) }

        /** Every definition not yet listed, except spec options, around [file]. */
        fun addRemaining(file: VirtualFile) {
            val rest = ranking.symbol.definitions.filter { it.kind != VarDefKind.SPEC_OPTION && it.location !in targets }
            VarRanking.byProximity(rest, file).forEach(::add)
        }

        companion object {
            /** File kinds whose top-level variable keys are a role's own declarations (D-FU1: Ctrl+B shows their usages). */
            private val DECLARATION_KINDS = setOf(FileKind.ROLE_ARGSPEC, FileKind.ROLE_META, FileKind.ROLE_DEFAULTS, FileKind.ROLE_VARS)

            /** File kinds that hold argument_specs options. */
            private val SPEC_KINDS = setOf(FileKind.ROLE_ARGSPEC, FileKind.ROLE_META)

            /**
             * True when a direct read of [name] has constant accessors starting with [path] (`host_ips['ops-pxe1']`
             * for the key `ops-pxe1` under `host_ips`), in [root] or a nested playbook root inside it.
             */
            fun hasMemberUses(project: Project, root: AnsibleRoot, name: String, path: List<String>): Boolean {
                val roots = listOf(root) + AnsibleWorkspace.getInstance(project).roots()
                    .filter { it.kind == RootKind.NESTED_PLAYBOOK && it.parentDir == root.dir }
                val query = VarUsageQuery.getInstance(project)
                var found = false
                for (member in roots) {
                    query.process(member, name, null) { use ->
                        found = use.indirect == null && !use.called && use.attrPath.size >= path.size && use.attrPath.subList(0, path.size) == path
                        !found
                    }
                    if (found) return true
                }
                return false
            }

            fun forReference(file: PsiFile, site: AnsibleSite.VarRef): List<PsiElement> {
                val project = file.project
                val virtualFile = file.originalFile.viewProvider.virtualFile
                val context = AnsibleWorkspace.getInstance(project).contextOf(virtualFile) ?: return emptyList()
                val root = context.root
                if (site.name in site.localNames) return listOfNotNull(localTarget(file, site, root))
                LoopItems.bindingAt(project, virtualFile, site.range.startOffset, site.name)?.let { binding ->
                    return loopTargets(file, site, context, binding)
                }
                val symbol = VarService.getInstance(project).symbol(root, site.name)
                val ranking = VarRanking(project, root, context.roleName, virtualFile, context.kind, symbol)
                val collector = Collector(project, root, ranking)
                val enclosing = enclosingKey(file, virtualFile, site)
                enclosing?.let(collector.excluded::add)
                val seen = SeenDefinitions.of(project, virtualFile, site.range.startOffset, context, symbol, enclosing)
                if (collector.addSeen(seen, site.attrPath)) return collector.result()
                if (site.attrPath.isNotEmpty() && collector.addNested(site.attrPath)) return collector.result()
                ranking.ownRole?.takeIf { it in ranking.declaringRoles }?.let(collector::addRole)
                val runtime = symbol.definitions.filter { it.kind in VarRanking.RUNTIME_KINDS && ranking.isLocal(it) }
                VarRanking.nearestFirst(runtime, virtualFile, site.range.startOffset).forEach(collector::add)
                ranking.declaringRoles.filter { it != ranking.ownRole }.forEach(collector::addRole)
                if (!ranking.anyRoleDeclares) collector.addRemaining(virtualFile)
                return collector.result()
            }

            fun forKey(file: PsiFile, site: AnsibleSite.VarKey): List<PsiElement> {
                val project = file.project
                val virtualFile = file.originalFile.viewProvider.virtualFile
                val context = AnsibleWorkspace.getInstance(project).contextOf(virtualFile) ?: return emptyList()
                val keySite = VarKeySites.at(file, site.range.startOffset, context) ?: return emptyList()
                val topLevel = site.keyPath.size == 1
                if (topLevel && site.kind in DECLARATION_KINDS) return emptyList()
                val root = context.root
                val symbol = VarService.getInstance(project).symbol(root, keySite.name)
                val ranking = VarRanking(project, root, context.roleName, virtualFile, context.kind, symbol)
                val keyLocation = keySite.variable.key?.let { SourceLocation(virtualFile, it.textRange.startOffset) }
                // A key that is itself a definition (an inventory override, set_fact, task vars …) is a declaration:
                // Ctrl+B shows its usages; Go to Super and the override gutter lead to what it overrides.
                if (topLevel && symbol.definitions.any { it.location == keyLocation }) return emptyList()
                val collector = Collector(project, root, ranking)
                keyLocation?.let(collector.excluded::add)
                // A nested key is never its own target either: a nested argument_specs option resolves to itself.
                collector.excluded += SourceLocation(virtualFile, site.range.startOffset)
                val nested = site.keyPath.drop(1)
                if (nested.isNotEmpty()) {
                    if (collector.addNested(nested)) return collector.result()
                    // A nested option no other role declares is the declaration itself (D-FU1); its parent is just above.
                    if (site.kind in SPEC_KINDS) return emptyList()
                    // A member with reads is a declaration like a role's own key: Ctrl+B shows its usages instead.
                    if (Collector.hasMemberUses(project, root, keySite.name, nested)) return emptyList()
                }
                ranking.declaringRoles.forEach(collector::addRole)
                if (collector.targets.isEmpty()) collector.addRemaining(virtualFile)
                return collector.result()
            }

            /** The key named like the reference whose value holds it (`x: "{{ x }}"`), which is no useful target. */
            private fun enclosingKey(file: PsiFile, virtualFile: VirtualFile, site: AnsibleSite.VarRef): SourceLocation? {
                var element = file.findElementAt(site.range.startOffset)
                while (element != null && element !is PsiFile) {
                    if (element is YAMLKeyValue && element.keyText == site.name) {
                        return element.key?.let { SourceLocation(virtualFile, it.textRange.startOffset) }
                    }
                    element = element.parent
                }
                return null
            }

            /**
             * The targets of a loop variable reference: for a member, the nested option of the iterated variable's
             * element; for the bare variable (or a member no spec documents), the loop keywords of the looping tasks.
             */
            private fun loopTargets(file: PsiFile, site: AnsibleSite.VarRef, context: FileContext, binding: LoopItems.Binding): List<PsiElement> {
                val project = file.project
                val root = context.root
                val virtualFile = file.originalFile.viewProvider.virtualFile
                val documented = binding.documented(site.name, site.attrPath)
                if (documented != null && site.attrPath.isNotEmpty()) {
                    val (variable, path) = documented
                    val symbol = VarService.getInstance(project).symbol(root, variable)
                    val ranking = VarRanking(project, root, context.roleName, virtualFile, context.kind, symbol)
                    val collector = Collector(project, root, ranking)
                    if (collector.addNested(path)) return collector.result()
                }
                val reference = VarSubject.reference(file, site) ?: return emptyList()
                val subject = LoopItems.documentedSubject(project, root, reference) ?: reference
                val kind = AnsibilityVarsBundle.message("target.loop", site.name)
                return binding.loopKeys.mapNotNull { location ->
                    ProgressManager.checkCanceled()
                    val anchor = VarLocations.elementAt(project, location) ?: return@mapNotNull null
                    val text = AnsibilityVarsBundle.message("target.location", kind, VarLocations.label(root, location))
                    VarTargetElement(anchor, location, site.name, text, subject)
                }
            }

            /** The binding of the template local [site] refers to. */
            private fun localTarget(file: PsiFile, site: AnsibleSite.VarRef, root: AnsibleRoot): VarTargetElement? {
                val analysis = JinjaTextSites.analysisAt(file, site.range.startOffset) ?: return null
                val local = analysis.localOf(analysis.reference, site.name) ?: return null
                val virtualFile = file.originalFile.viewProvider.virtualFile
                val location = SourceLocation(virtualFile, analysis.toHost(local.definitionRange.startOffset))
                val anchor = file.findElementAt(location.offset) ?: file
                val subject = VarSubject.reference(file, site) ?: return null
                val text = AnsibilityVarsBundle.message(
                    "target.location", AnsibilityVarsBundle.message("target.local"), VarLocations.label(root, location),
                )
                return VarTargetElement(anchor, location, site.name, text, subject)
            }
        }
    }
}
