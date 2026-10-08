package de.terletzkiy.ansibility.context.host.card

import com.intellij.lang.documentation.DocumentationMarkup
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.HtmlChunk
import com.intellij.openapi.vfs.VirtualFile
import de.terletzkiy.ansibility.api.AnsibleContextService
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.CardContext
import de.terletzkiy.ansibility.api.CardPlacement
import de.terletzkiy.ansibility.api.CardSection
import de.terletzkiy.ansibility.api.CardSubject
import de.terletzkiy.ansibility.api.HostKey
import de.terletzkiy.ansibility.api.HostScopeOrigin
import de.terletzkiy.ansibility.api.SourceLocation
import de.terletzkiy.ansibility.api.VarDefKind
import de.terletzkiy.ansibility.api.VarSourceRef
import de.terletzkiy.ansibility.context.MoleculeView
import de.terletzkiy.ansibility.context.host.AnsibleContextServiceImpl
import de.terletzkiy.ansibility.context.host.Location
import de.terletzkiy.ansibility.context.host.card.HostCardTexts.grayed
import de.terletzkiy.ansibility.context.host.card.HostCardTexts.message
import de.terletzkiy.ansibility.context.switching.ContextTexts
import de.terletzkiy.ansibility.lang.jinja.refs.JinjaRefs
import de.terletzkiy.ansibility.model.inventory.InventoryModels
import de.terletzkiy.ansibility.render.service.TemplatePreviewService
import de.terletzkiy.ansibility.resolve.VarViews
import de.terletzkiy.ansibility.settings.EnvironmentChoice
import de.terletzkiy.ansibility.vars.NoLogVariables
import de.terletzkiy.ansibility.vars.VarLocations
import org.jetbrains.yaml.psi.YAMLScalar

/**
 * The **Effective** section at the top of the variable card (plan amendment R7/R8, F8.2, ex-X13; `cardSection` id
 * `ansibilityHostEffective`, TOP): the value of the variable per host of the card file's host scope, grouped by
 * identical outcome (the winning definition's location, never the value, so vault values group like any other), largest
 * group first. Environments are never merged: hosts of several environments are listed per environment.
 *
 * ```
 * Effective on 4 hosts (play System) — 1 value
 *   ops-ops1 · prod: prod-prod1, prod-prod2 · test: test-test1
 *     = relayinternal.mx.example.de   group_vars/all/vars.yml:156 · L5 playbook group_vars/all · Explain for ops-ops1 ›
 *     shadowed: environments/prod/group_vars/all/vars.yml:471 (L4, prod ×2) · … · roles/postfix/defaults/main.yml:2 (L2)
 * molecule default: = relay.mx.example-test.de   roles/postfix/molecule/default/molecule.yml:56 · molecule inventory
 * ```
 *
 * - **Selection-aware** (D32: presentation follows the selection, inspections do not): the scope is
 *   [AnsibleContextService.hostScope] of the card's file and offset, so an environment narrows the hosts and a host gives
 *   one line plus **Explain precedence ›** ([ExplainLinks]); a file scope that overrides the selection says so.
 * - At most [MAX_OUTCOMES] outcomes, then "+140 more values on 140 hosts" (the "Set in" rows list every definition).
 * - **Not set for** the scope's hosts that no layer defines the name on; **not applied in** the environments a role
 *   does not run in (All mode); **n names share an address** when hosts on one address get different values.
 * - **Runtime-default chain:** a winner that is a bare `{{ other }}` reference is followed through the same contexts
 *   ([ValueChains]): "→ str 192.0.2.33 via system_ip_floating (prod-prod1, prod-prod2)".
 * - **Block and task vars** (level 15) that apply at the card's position ([TaskVars]): inside a task whose `vars:` set
 *   the name, that definition wins over every lower layer, so the outcomes say so; a template rendered with the name in
 *   some of its rendering tasks' `vars:` only names those tasks. In a file that include tasks run, their `vars:` and
 *   loop variables count too: hosts are not "not set" when every include path sets the name, and the definitions are
 *   named ("set by the include tasks that run this file").
 * - **Vault:** previews follow the one vault-safe rule; a vault winner reads "🔒 vault-encrypted (AES256, 1.1)" with the
 *   vault area's Reveal link ([HostCardTexts.value]). Nothing is decrypted.
 * - **no_log** (plan amendment R23): a variable a spec keeps secret ([NoLogVariables]) shows no value, no rendered value
 *   and no chain; a chain through such a variable ends hidden, and a value that reads one (or leads to one) is not
 *   rendered.
 *
 * No section for template locals and loop variables, for nested option paths (the card documents an option there), and
 * for names that no static layer of the root defines (magic variables, facts, `register`/`set_fact` results).
 * Built from [HostCardViews] (single-name evaluation on cached views), in the card's read action.
 */
class EffectiveCardSection : CardSection {
    override val placement: CardPlacement get() = CardPlacement.TOP

    override fun section(subject: CardSubject, context: CardContext): HtmlChunk? {
        val variable = subject as? CardSubject.Variable ?: return null
        if (variable.local || variable.path.isNotEmpty()) return null
        val project = context.project
        // Seen from the same position as the ranked "Set in" rows ([HostCardViews.scopeOffset]).
        val view = HostCardViews.getInstance(project).view(variable.definition, context, variable.name) ?: return null
        return Renderer(project, variable.root, view, context.file).render()
    }

    private class Renderer(private val project: Project, private val root: AnsibleRoot, private val view: CardView, private val file: VirtualFile) {
        private val scope = view.scope
        private val single: HostKey? = view.singleHost
        private val moleculeView = MoleculeView.of(project, file)
        private val noLog: (String) -> Boolean = { NoLogVariables.isNoLog(project, root, it, moleculeView) }

        /** The card's variable is a secret by its spec: no value of it is shown. */
        private val hidden = noLog(view.name)

        fun render(): HtmlChunk? {
            if (scope.targets.isEmpty()) {
                val reason = scope.emptyReason ?: return null
                return block(listOf(HtmlChunk.p().children(HtmlChunk.text(message("card.effective.empty")).bold(), HtmlChunk.text(" "), grayed(reason))))
            }
            if (view.outcomes.isEmpty() && view.moleculeCompanions.isEmpty() && view.taskVars.partial.isEmpty() && view.taskVars.included.isEmpty() &&
                !hasStaticDefinitions()
            ) {
                return null
            }
            val paragraphs = ArrayList<HtmlChunk>()
            paragraphs += header()
            // A value per host on a large inventory: the largest outcomes, then one line for the rest ("Set in" lists them all).
            val shown = view.outcomes.take(MAX_OUTCOMES)
            for (outcome in shown) {
                ProgressManager.checkCanceled()
                // All mode: each outcome under its hosts. Host mode: one line, or one per play when the host's plays disagree.
                val heading = when {
                    single == null -> HostCardTexts.hostList(outcome.group.hosts)
                    view.outcomes.size > 1 -> HostCardTexts.plays(outcome.group.plays)?.let { "$it:" }
                    else -> null
                }
                paragraphs += HtmlChunk.p().child(outcomeLines(outcome, heading))
            }
            if (view.outcomes.size > shown.size) {
                val rest = view.outcomes.drop(shown.size)
                val hosts = message("card.effective.hosts", rest.flatMap { it.group.hosts }.distinct().size)
                paragraphs += HtmlChunk.p().child(grayed(message("card.effective.more", rest.size, hosts)))
            }
            notes()?.let { paragraphs += HtmlChunk.p().child(it) }
            if (view.moleculeCompanions.isNotEmpty()) paragraphs += HtmlChunk.p().child(moleculeLines())
            return block(paragraphs)
        }

        private fun block(paragraphs: List<HtmlChunk>): HtmlChunk = DocumentationMarkup.CONTENT_ELEMENT.children(paragraphs)

        /** A name without any static definition has nothing the Effective section could say (magic variables, facts, register). */
        private fun hasStaticDefinitions(): Boolean =
            VarViews.symbol(project, root, view.name, MoleculeView.of(project, file)).definitions.any { it.kind in STATIC_KINDS }

        // -------------------------------------------------------------------------------------------- header

        /** `Effective on 4 hosts (play System) — 1 value`, or `Effective on prod › prod-prod1 (play System)`. */
        private fun header(): HtmlChunk {
            val molecule = view.hosts.isNotEmpty() && view.hosts.all { it.isMolecule }
            val values = view.outcomes.count { it.group.winner != null }
            val plays = HostCardTexts.plays(scope.targets.mapNotNull { it.play }.distinct())
            val title = if (single != null) {
                message("card.effective.on", HostCardTexts.hostLabel(single))
            } else {
                val hosts = message("card.effective.hosts", (scope.hosts + view.breakdown.undefinedOn).distinct().size)
                message(if (molecule) "card.effective.on.molecule" else "card.effective.on", hosts)
            }
            val details = when {
                single != null -> plays?.let { "($it)" }
                // Only include tasks set it: the values are theirs (named below), not a count of static outcomes.
                values == 0 && view.taskVars.included.isNotEmpty() -> plays?.let { "($it)" }
                plays != null -> message("card.effective.header.details", plays, message("card.effective.values", values))
                else -> message("card.effective.header.value", message("card.effective.values", values))
            }
            val children = mutableListOf<HtmlChunk>(HtmlChunk.text(title).bold())
            details?.let {
                children.add(HtmlChunk.text(" "))
                children.add(grayed(it))
            }
            if (scope.overriddenSelection) {
                children.add(HtmlChunk.br())
                children.add(grayed(message("card.effective.overridden", ContextTexts.environmentAndHost(scope.selection))))
            }
            return HtmlChunk.p().children(children)
        }

        // -------------------------------------------------------------------------------------------- outcomes

        /**
         * The [heading] (the outcome's hosts in All mode, its plays when one host's plays disagree), then `= value  source ·
         * layer · Explain ›`, the chain lines and the shadowed definitions.
         */
        private fun outcomeLines(outcome: CardOutcome, heading: String?): HtmlChunk {
            val lines = ArrayList<HtmlChunk>()
            val indent = if (heading != null) INDENT else 0
            heading?.let { lines += HtmlChunk.text(it) }
            val winner = outcome.group.winner ?: return HostCardTexts.lines(lines)
            val parts = mutableListOf<HtmlChunk>(
                HtmlChunk.fragment(HtmlChunk.text("= "), HostCardTexts.value(project, winner, hidden)),
                HostCardTexts.definitionLink(project, root, winner),
                HtmlChunk.text(HostCardTexts.levelAndLayer(winner)),
            )
            outcome.explainTarget?.let { target ->
                val text = if (single != null) message("card.effective.explain") else message("card.effective.explain.host", target.host.host)
                parts += HtmlChunk.link(ExplainLinks.of(view.name, target, view.runningRole, view.taskVars.applied), text)
            }
            lines += indented(indent, HostCardTexts.joined(parts))
            if (!hidden) {
                // A hidden value has no rendering and no chain: both would tell what it says.
                renderedLine(outcome, winner)?.let { lines += indented(indent + INDENT, it) }
                for ((chain, hosts) in outcome.chains) lines += indented(indent + INDENT, chainLine(chain, hosts.takeIf { single == null }))
            }
            shadowedLine(outcome)?.let { lines += indented(indent + INDENT, it) }
            return HostCardTexts.lines(lines)
        }

        /**
         * `renders: https://mimir.example.de/api/v1/push (prod-prod1)` for a winner that carries Jinja: the value fully
         * templated on the outcome's first host, with placeholders where only the run knows (facts, vault, runtime results).
         * A block, task or include var winner ([TaskVars.applied], which the model does not evaluate) renders its own
         * value, a mapping or list as a whole.
         */
        private fun renderedLine(outcome: CardOutcome, winner: VarSourceRef): HtmlChunk? {
            if (winner.isVault) return null
            val preview = winner.preview ?: return null
            if ("{{" !in preview && "{%" !in preview) return null
            val target = outcome.explainTarget ?: return null
            // The rendering would print the value of a variable a spec keeps secret that it reads (or leads to).
            if (outcome.chains.any { (chain, _) -> chain.via.any(noLog) }) return null
            val rendered = try {
                val scalar = VarLocations.keyValueAt(project, SourceLocation(winner.file, winner.offset))?.value as? YAMLScalar
                if (JinjaRefs.analyze(scalar?.textValue ?: preview).references.any { noLog(it.name) }) return null
                val local = if (scalar == null && winner == view.taskVars.applied) TaskVars.valueOf(project, winner) else null
                TemplatePreviewService.getInstance(project).renderVariable(target, view.scope, view.name, view.runningRole, file, scalar?.textValue, local)
            } catch (e: ProcessCanceledException) {
                throw e
            } catch (_: RuntimeException) {
                return null
            }
            val text = rendered.text.takeUnless { it == preview } ?: return null
            val shown = if (text.length > MAX_RENDERED) text.take(MAX_RENDERED) + "\u2026" else text
            val parts = mutableListOf<HtmlChunk>(
                grayed(message("card.effective.renders") + " "),
                HtmlChunk.tag("code").addText(shown.replace('\n', '\u21B5')),
            )
            val note = when {
                rendered.errors.isNotEmpty() -> message("card.effective.renders.error", rendered.errors.first().message)
                single == null -> message("card.effective.renders.on", target.host.host)
                else -> null
            }
            note?.let { parts += HtmlChunk.text(" "); parts += grayed(it) }
            return HtmlChunk.fragment(*parts.toTypedArray())
        }

        /** `→ str 192.0.2.33 via system_ip_floating · <link> (prod-prod1, prod-prod2)`. */
        private fun chainLine(chain: ResolvedChain, hosts: List<HostKey>?): HtmlChunk {
            val line = HostCardTexts.chain(project, root, chain, noLog)
            if (hosts.isNullOrEmpty()) return line
            return HtmlChunk.fragment(line, HtmlChunk.text(" "), grayed(message("card.effective.chain.hosts", HostCardTexts.hostList(hosts))))
        }

        /** `shadowed: environments/prod/group_vars/all/vars.yml:471 (L4, prod ×2) · roles/postfix/defaults/main.yml:2 (L2)`. */
        private fun shadowedLine(outcome: CardOutcome): HtmlChunk? {
            if (outcome.shadowed.isEmpty()) return null
            val entries = outcome.shadowed.map { (ref, hosts) ->
                val label = HostCardTexts.label(project, root, ref)
                val text = when {
                    !HostCardTexts.isInventory(ref.layer) -> message("card.effective.shadowed.level", label, ref.layer.level)
                    hosts.isEmpty() -> message("card.effective.shadowed.level", label, ref.layer.level)
                    else -> {
                        val counts = hosts.groupingBy { it.environment }.eachCount().entries.joinToString(", ") { (environment, count) ->
                            message("card.effective.shadowed.count", HostCardTexts.environmentLabel(environment), count)
                        }
                        message("card.effective.shadowed.inventory", label, ref.layer.level, counts)
                    }
                }
                HostCardTexts.definitionLink(ref, text)
            }
            return HtmlChunk.fragment(grayed(message("card.effective.shadowed.prefix") + " "), HostCardTexts.joined(entries))
        }

        private fun indented(width: Int, chunk: HtmlChunk): HtmlChunk = if (width == 0) chunk else HtmlChunk.fragment(HtmlChunk.nbsp(width), chunk)

        // -------------------------------------------------------------------------------------------- notes

        /** `not set for …`, `not applied in build, ops, test`, `7 names share 192.0.2.43`, task vars of some renders. */
        private fun notes(): HtmlChunk? {
            val lines = ArrayList<HtmlChunk>()
            val undefined = view.breakdown.undefinedOn
            if (undefined.isNotEmpty()) {
                val hosts = if (single != null) HostCardTexts.hostLabel(undefined.single()) else HostCardTexts.hostList(undefined)
                lines += HtmlChunk.text(message("card.effective.not.set", hosts))
            }
            notAppliedIn()?.let { lines += grayed(message("card.effective.not.applied", it)) }
            lines += sharedAddresses().map { grayed(it) }
            partialTaskVars()?.let { lines += it }
            includedTaskVars()?.let { lines += it }
            return if (lines.isEmpty()) null else HostCardTexts.lines(lines)
        }

        /**
         * `set by the include tasks that run this file: roles/x/tasks/rules.yml:12, …`: the definitions an include task
         * gives the file ([TaskVars.included]) when the outcomes above do not already show one of them as the winner.
         */
        private fun includedTaskVars(): HtmlChunk? {
            val taskVars = view.taskVars
            val winners = (view.outcomes.mapNotNull { it.group.winner } + listOfNotNull(taskVars.applied)).mapTo(HashSet()) { Location.of(it) }
            val included = taskVars.included.filter { Location.of(it) !in winners }.takeIf { it.isNotEmpty() } ?: return null
            val links = included.map { HostCardTexts.definitionLink(project, root, it) }
            val key = if (taskVars.includedEverywhere) "card.effective.included" else "card.effective.included.partial"
            return HtmlChunk.fragment(grayed(message(key) + " "), HostCardTexts.joined(links))
        }

        /**
         * `some renders set it in their task vars (L15): roles/x/tasks/main.yml:12`: the rendering tasks whose `vars:` set
         * the name when not every render does (those renders get that value; the outcomes above are the others').
         */
        private fun partialTaskVars(): HtmlChunk? {
            val partial = view.taskVars.partial.takeIf { it.isNotEmpty() } ?: return null
            val links = partial.map { HostCardTexts.definitionLink(project, root, it) }
            return HtmlChunk.fragment(grayed(message("card.effective.task.vars.partial") + " "), HostCardTexts.joined(links))
        }

        /** The environments a role file's role does not run in, while the selection names no environment. */
        private fun notAppliedIn(): String? {
            if (scope.origin !is HostScopeOrigin.RoleReach || scope.selection.environment is EnvironmentChoice.Named) return null
            val impl = AnsibleContextServiceImpl.getInstance(project) ?: return null
            val reached = scope.fileHosts.mapTo(HashSet()) { it.environment }
            val missing = InventoryModels.getInstance(project).environments(impl.inventoryRoot(scope.root)).map { it.name }.filter { it !in reached }
            return missing.takeIf { it.isNotEmpty() }?.joinToString(", ")
        }

        /**
         * `7 names share 192.0.2.43` for each address that several hosts of the scope share while they get different
         * values ([CardView.sharedAddresses]): it explains why one machine shows several outcomes (ports per preview host).
         */
        private fun sharedAddresses(): List<String> =
            view.sharedAddresses.map { message("card.effective.shared.address", it.names, it.address) }

        /** `molecule default: = value  roles/postfix/molecule/default/molecule.yml:56 · molecule inventory`. */
        private fun moleculeLines(): HtmlChunk {
            val lines = view.moleculeCompanions.mapNotNull { outcome ->
                val winner = outcome.group.winner ?: return@mapNotNull null
                val scenarios = outcome.group.hosts.map { it.environment.removePrefix(HostKey.MOLECULE_PREFIX).substringAfterLast('/') }.distinct()
                HostCardTexts.joined(
                    listOf(
                        HtmlChunk.fragment(
                            grayed(message("card.effective.molecule", scenarios.joinToString(", "))),
                            HtmlChunk.text(" = "),
                            HostCardTexts.value(project, winner, hidden),
                        ),
                        HostCardTexts.definitionLink(project, root, winner),
                        HtmlChunk.text(HostCardTexts.levelAndLayer(winner)),
                    ),
                )
            }
            return HostCardTexts.lines(lines)
        }
    }

    private companion object {
        const val INDENT = 2

        /** Characters of a rendered value the card shows before "…". */
        const val MAX_RENDERED = 200

        /** Outcomes listed before "+n more values on m hosts" (hover cards stay short and cheap on large inventories). */
        const val MAX_OUTCOMES = 10

        /** Kinds a static layer evaluates (the Effective section has something to say about them). */
        val STATIC_KINDS = setOf(
            VarDefKind.ROLE_DEFAULT, VarDefKind.ROLE_VAR, VarDefKind.INVENTORY_INLINE, VarDefKind.GROUP_VARS, VarDefKind.HOST_VARS,
            VarDefKind.MOLECULE_INVENTORY, VarDefKind.PLAY_VARS, VarDefKind.VARS_FILES, VarDefKind.ROLE_PARAMS,
        )
    }
}
