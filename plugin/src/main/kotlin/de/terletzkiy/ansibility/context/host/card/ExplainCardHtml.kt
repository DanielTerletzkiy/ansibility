package de.terletzkiy.ansibility.context.host.card

import com.intellij.lang.documentation.DocumentationMarkup
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.HtmlChunk
import com.intellij.openapi.vfs.VirtualFileManager
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.ChainOutcome
import de.terletzkiy.ansibility.api.ChainStep
import de.terletzkiy.ansibility.api.CardSection
import de.terletzkiy.ansibility.api.EvalTarget
import de.terletzkiy.ansibility.api.PrecedenceChain
import de.terletzkiy.ansibility.api.SourceLocation
import de.terletzkiy.ansibility.api.VarSourceRef
import de.terletzkiy.ansibility.api.VarsLayer
import de.terletzkiy.ansibility.context.host.AnsibleContextServiceImpl
import de.terletzkiy.ansibility.context.host.Evaluation
import de.terletzkiy.ansibility.context.host.card.HostCardTexts.grayed
import de.terletzkiy.ansibility.context.host.card.HostCardTexts.message
import de.terletzkiy.ansibility.context.switching.ContextTexts
import de.terletzkiy.ansibility.model.effective.VarSourceRefs
import de.terletzkiy.ansibility.settings.RootKeys
import de.terletzkiy.ansibility.vars.VarDocumentationTarget
import de.terletzkiy.ansibility.vars.VarLinks

/**
 * Renders the Explain precedence card ([ExplainDocumentationTarget]) for one [request]:
 *
 * ```
 * postfix_relayhost    Explain precedence · prod › prod-prod1
 * Play playbook-setup-system.yml › System · as a task of role postfix · playbook dir repos/falcon/ansible
 * L2    role defaults · roles/postfix/defaults/main.yml:2 · = "" · shadowed                       (struck through)
 * L4    inventory group_vars/all · environments/prod/group_vars/all/vars.yml:471 · = … · shadowed (struck through)
 * L5    playbook group_vars/all · group_vars/all/vars.yml:156 · = relayinternal… · ✓ wins
 * Resolves to   → str 192.0.2.33 via system_ip_floating · environments/prod/group_vars/all/vars.yml:582
 * Runtime       may be replaced at runtime by set_fact at roles/x/tasks/main.yml:12
 * Other hosts   prod-prod2 · ops-ops1 · test-test1
 * ```
 *
 * The chain is the service's ([AnsibleContextServiceImpl.explain], `PrecedenceEngine.effectiveOf` on the ExecutionView of
 * the context, the running role's defaults and vars applied last), plus the block or task var in force where the card
 * was opened ([TaskVars], at level 15). Values are the engine's vault-safe previews; vault values are masked with the
 * vault area's Reveal link ([HostCardTexts.value]). Call in a read action in smart mode.
 */
internal class ExplainCardHtml(
    private val project: Project,
    private val root: AnsibleRoot,
    private val request: ExplainLinks.Request,
    private val origin: VarDocumentationTarget?,
) {
    fun render(): String {
        val out = StringBuilder()
        definition().appendTo(out)
        val impl = AnsibleContextServiceImpl.getInstance(project)
        val target = request.target(project)
        // The context's evaluation (single-name, on the cached views) tells whether its host still exists.
        val evaluation = if (impl == null || target == null) null else impl.evaluator.evaluation(target, request.runningRole)
        if (impl == null || target == null || evaluation == null) {
            DocumentationMarkup.CONTENT_ELEMENT.child(HtmlChunk.p().addText(message("explain.gone"))).appendTo(out)
            return out.toString()
        }
        val chain = impl.explain(target, request.name, request.runningRole)
        // The block or task var in force where the card was opened (the model's chain has no level 15).
        val taskVars = TaskVars(taskVar(), emptyList())
        val steps = taskVars.applyTo(chain.steps)
        DocumentationMarkup.CONTENT_ELEMENT.children(context(target), summary(steps)).appendTo(out)
        val rows = ArrayList<HtmlChunk>()
        steps.forEach { rows += step(it) }
        resolvedTo(evaluation, taskVars)?.let { rows += CardSection.row(message("explain.section.chain.value"), it) }
        runtime(chain)?.let { rows += CardSection.row(message("explain.section.runtime"), it) }
        otherHosts(impl, target)?.let { rows += CardSection.row(message("explain.section.other.hosts"), it) }
        if (rows.isNotEmpty()) DocumentationMarkup.SECTIONS_TABLE.children(rows).appendTo(out)
        return out.toString()
    }

    /** `postfix_relayhost    Explain precedence · prod › prod-prod1`. */
    private fun definition(): HtmlChunk = DocumentationMarkup.DEFINITION_ELEMENT.children(
        HtmlChunk.text(request.name).bold(),
        HtmlChunk.nbsp(4),
        grayed(message("explain.title") + HostCardTexts.SEPARATOR + HostCardTexts.hostLabel(request.host)),
    )

    /** `Play playbook-setup-system.yml › System · as a task of role postfix · playbook dir repos/falcon/ansible`. */
    private fun context(target: EvalTarget): HtmlChunk {
        val parts = ArrayList<String>()
        val play = target.play
        parts += if (play == null) message("explain.inventory.only") else message("explain.play", ContextTexts.playLabel(root, play))
        request.runningRole?.let { parts += message("explain.running.role", it) }
        target.playbookDir?.let { dir -> parts += message("explain.playbook.dir", RootKeys.relativePath(project, dir)?.ifEmpty { dir.name } ?: dir.presentableUrl) }
        return HtmlChunk.p().addText(parts.joinToString(HostCardTexts.SEPARATOR))
    }

    /** The block or task var the request names, while it is still one ([TaskVars.definitionAt]). */
    private fun taskVar(): VarSourceRef? {
        val url = request.taskVarUrl ?: return null
        val file = VirtualFileManager.getInstance().findFileByUrl(url)?.takeIf { it.isValid && !it.isDirectory } ?: return null
        return TaskVars.definitionAt(project, root, request.name, SourceLocation(file, request.taskVarOffset))
    }

    /** Nothing loads the name in this context: the chain is empty. */
    private fun summary(steps: List<ChainStep>): HtmlChunk =
        if (steps.isEmpty()) HtmlChunk.p().addText(message("explain.none", request.name, HostCardTexts.hostLabel(request.host))) else HtmlChunk.empty()

    /** One definition of the chain: level, layer, source, value and outcome; shadowed definitions are struck through. */
    private fun step(step: ChainStep): HtmlChunk {
        ProgressManager.checkCanceled()
        val source = step.source
        val body = HostCardTexts.joined(
            listOf(
                HtmlChunk.text(HostCardTexts.layerName(source)),
                HostCardTexts.definitionLink(project, root, source),
                HtmlChunk.fragment(HtmlChunk.text("= "), HostCardTexts.value(project, source)),
            ),
        )
        val outcome = when (step.outcome) {
            ChainOutcome.WINNER -> HtmlChunk.text(message("explain.outcome.winner")).bold()
            ChainOutcome.SHADOWED -> grayed(message("explain.outcome.shadowed"))
            ChainOutcome.MERGED -> grayed(message("explain.outcome.merged"))
        }
        val shown = if (step.outcome == ChainOutcome.SHADOWED) body.strikethrough() else body
        val level = if (source.layer == VarsLayer.MOLECULE_INVENTORY) "" else message("card.effect.level", source.layer.level)
        return CardSection.row(level, HtmlChunk.fragment(shown, HtmlChunk.text(HostCardTexts.SEPARATOR), outcome))
    }

    /** Where a bare `{{ other }}` winner resolves in this context (nothing for a masked winner, [ValueChains.follow]). */
    private fun resolvedTo(evaluation: Evaluation, taskVars: TaskVars): HtmlChunk? {
        val effective = evaluation.effectiveOf(request.name)
        val shadowed = effective?.shadowed?.mapNotNull { VarSourceRefs.ref(it, evaluation.origins) }.orEmpty()
        val (winner, _) = taskVars.outcome(effective?.let(evaluation::ref), shadowed) ?: return null
        val value = if (winner === taskVars.applied) TaskVars.valueOf(project, winner) else effective?.value
        return value?.let { ValueChains.follow(evaluation, request.name, winner, it) }?.let { HostCardTexts.chain(project, root, it) }
    }

    /** `may be replaced at runtime by set_fact at …` and the templated `vars_files` entries that may define the name. */
    private fun runtime(chain: PrecedenceChain): HtmlChunk? {
        val lines = ArrayList<HtmlChunk>()
        for (marker in chain.runtimeMarkers) {
            val location = marker.location
            lines += HtmlChunk.fragment(
                HtmlChunk.text(message("explain.runtime.marker", marker.kind.name.lowercase()) + " "),
                HtmlChunk.link(VarLinks.definition(location), HostCardTexts.label(project, root, location.file, location.offset)),
            )
        }
        for (location in chain.unknownSources) lines += unknownSource(location)
        return if (lines.isEmpty()) null else HostCardTexts.lines(lines)
    }

    private fun unknownSource(location: SourceLocation): HtmlChunk =
        HtmlChunk.text(message("explain.runtime.unknown", HostCardTexts.label(project, root, location.file, location.offset)))

    /**
     * The same explanation for the other hosts of the card the link came from (first context of each host), seen from
     * the position its Effective section shows ([HostCardViews.scopeOffset]).
     */
    private fun otherHosts(impl: AnsibleContextServiceImpl, target: EvalTarget): HtmlChunk? {
        val origin = origin ?: return null
        val context = origin.cardContext()
        if (AnsibleWorkspace.getInstance(project).rootFor(context.file) == null) return null
        val scope = impl.hostScope(context.file, HostCardViews.scopeOffset(origin.definitionLocation, context))
        val role = impl.runningRole(scope)
        val others = scope.targets.distinctBy { it.host }.filter { it.host != target.host }.take(MAX_OTHER_HOSTS)
        if (others.isEmpty()) return null
        val qualified = scope.targets.map { it.host.environment }.distinct().size > 1
        val links = others.map { other ->
            val label = if (qualified) HostCardTexts.hostLabel(other.host) else other.host.host
            val link = ExplainLinks.of(ExplainLinks.request(request.name, other, role).copy(taskVarUrl = request.taskVarUrl, taskVarOffset = request.taskVarOffset))
            HtmlChunk.link(link, label)
        }
        return HostCardTexts.joined(links)
    }

    private companion object {
        const val MAX_OTHER_HOSTS = 16
    }
}
