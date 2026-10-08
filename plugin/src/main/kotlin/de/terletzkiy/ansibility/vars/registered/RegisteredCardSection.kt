package de.terletzkiy.ansibility.vars.registered

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.text.HtmlChunk
import de.terletzkiy.ansibility.api.CardContext
import de.terletzkiy.ansibility.api.CardPlacement
import de.terletzkiy.ansibility.api.CardSection
import de.terletzkiy.ansibility.api.CardSubject
import de.terletzkiy.ansibility.api.RoleRegistry
import de.terletzkiy.ansibility.api.VarDefKind
import de.terletzkiy.ansibility.context.MoleculeView
import de.terletzkiy.ansibility.resolve.VarViews
import de.terletzkiy.ansibility.resolve.register.AnsibilityRegisteredBundle.message
import de.terletzkiy.ansibility.resolve.register.RegisteredResult
import de.terletzkiy.ansibility.resolve.register.RegisteredResults
import de.terletzkiy.ansibility.resolve.register.RegisteringTask
import de.terletzkiy.ansibility.semantics.registered.MemberKind
import de.terletzkiy.ansibility.semantics.registered.ResultMember
import de.terletzkiy.ansibility.vars.VarLinks

/**
 * The variable card's rows for a registered variable (plan amendment FU, F1.12; `cardSection` `ansibilityRegistered`,
 * SECTION, after `ansibilityUndefined`):
 * - **Result of:** one line per registering task the card's position sees, "`ansible.builtin.command` · task “Wait
 *   for keepalived …” (roles/keepalived/tasks/main.yml:52)", the task linked to its `register:` definition;
 * - **Result keys:** the documented keys of the result (module returns first, the common keys last);
 * - **Item keys:** for a looping task, the keys of each item's result in `results`.
 *
 * The tasks are those visible at the card's position ([RegisteredResults.at]); a definition card shows its own task, a
 * card opened through a link the registers of the file's role (or of the root outside roles). Template locals, loop
 * variables and names without a `register:` get nothing. Never reads a value, so no secret can reach the card.
 */
class RegisteredCardSection : CardSection {
    override val placement: CardPlacement get() = CardPlacement.SECTION

    override fun section(subject: CardSubject, context: CardContext): HtmlChunk? {
        val variable = subject as? CardSubject.Variable ?: return null
        if (variable.local) return null
        val result = resultOf(variable, context) ?: return null
        val member = result.member(variable.path)
        // A documented member has a card of its own; only the variable (or an undocumented key) gets the rows.
        if (variable.path.isNotEmpty() && member != null) return null
        val rows = ArrayList<HtmlChunk>()
        rows += CardSection.row(message("section.result.of"), lines(result.tasks.map { task -> taskLine(result, task) }))
        val top = result.shape.root
        keys(top)?.let { rows += CardSection.row(message("section.result.keys"), it) }
        top.members?.get(RESULTS)?.takeIf { MemberKind.LOOP in it.kinds }
            ?.element()?.let(::keys)?.let { rows += CardSection.row(message("section.result.item.keys"), it) }
        return HtmlChunk.fragment(*rows.toTypedArray())
    }

    /** The result the card's position sees; a definition card's own `register:`; the role's registers from a link. */
    private fun resultOf(variable: CardSubject.Variable, context: CardContext): RegisteredResult? {
        val project = context.project
        val results = RegisteredResults.getInstance(project)
        val view = MoleculeView.of(project, context.file)
        variable.definition?.let { location ->
            val own = VarViews.symbol(project, variable.root, variable.name, view).definitions
                .filter { it.kind == VarDefKind.REGISTER && it.location == location }
            return results.of(variable.root, variable.name, own)
        }
        if (context.offset >= 0) return results.at(context.file, context.offset, variable.name, view)
        val roleDir = RoleRegistry.getInstance(project).roleOf(context.file)?.takeIf { it.ref.rootDir == variable.root.dir }?.ref?.dir
        return results.inScope(variable.root, roleDir, variable.name, view)
    }

    private fun taskLine(result: RegisteredResult, task: RegisteringTask): HtmlChunk {
        val label = RegisteredMemberDocumentationTarget.taskLabel(result, task)
        val link = HtmlChunk.link(VarLinks.definition(task.register), label)
        val module = task.moduleLabel ?: return HtmlChunk.fragment(HtmlChunk.text(message("section.result.unknown.module") + SEPARATOR), link)
        return HtmlChunk.fragment(HtmlChunk.text(module).code(), HtmlChunk.text(SEPARATOR), link)
    }

    /** The documented keys of [member] as code, at most [MAX_KEYS]; null when it has none. */
    private fun keys(member: ResultMember): HtmlChunk? {
        val keys = member.members?.keys?.toList()?.takeIf { it.isNotEmpty() } ?: return null
        val children = ArrayList<HtmlChunk>()
        keys.take(MAX_KEYS).forEachIndexed { index, key ->
            ProgressManager.checkCanceled()
            if (index > 0) children += HtmlChunk.text(", ")
            children += HtmlChunk.text(key).code()
        }
        val more = keys.size - MAX_KEYS
        if (more > 0) children += HtmlChunk.text(" " + message("card.member.list.more", more))
        return HtmlChunk.fragment(*children.toTypedArray())
    }

    private fun lines(lines: List<HtmlChunk>): HtmlChunk {
        val children = ArrayList<HtmlChunk>()
        lines.forEachIndexed { index, line ->
            if (index > 0) children += HtmlChunk.br()
            children += line
        }
        return HtmlChunk.fragment(*children.toTypedArray())
    }

    private companion object {
        const val RESULTS = "results"
        const val SEPARATOR = " · "
        const val MAX_KEYS = 40
    }
}
