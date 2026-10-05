package de.terletzkiy.ansibility.vars.usages

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.HtmlChunk
import com.intellij.openapi.vfs.VirtualFile
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.CardContext
import de.terletzkiy.ansibility.api.CardPlacement
import de.terletzkiy.ansibility.api.CardSection
import de.terletzkiy.ansibility.api.CardSubject
import de.terletzkiy.ansibility.api.FileKind
import de.terletzkiy.ansibility.api.JinjaContainer
import de.terletzkiy.ansibility.resolve.VarUsageQuery

/**
 * The card's **Used in** row (F1.10, D-FU7; `cardSection` id `ansibilityUsages`, SECTION, `order="last"`) on every
 * variable card: `4 templates · 7 tasks · 2 conditions — Show usages`, or `no uses in falcon — Show usages` (the
 * definitions are still listed there). The link runs Find Usages ([UsagesCardLinks]).
 *
 * The counts come from one `ansible.var.use` lookup of the name in the card's root (no PSI, host-independent): template
 * files, and the uses in tasks, in conditions (bare expressions) and in the values of other variables; reads by name
 * (FU2: `hostvars[h].x`, `vars['x']`) count as the reads they are, where they are written. Jinja locals
 * and loop variables (`item` and `ansible_loop` too, wherever they are written) get no row: their uses are scoped to
 * one file or loop, and a root-wide count would add up unrelated loops.
 */
class UsagesCardSection : CardSection {
    override val placement: CardPlacement get() = CardPlacement.SECTION

    override fun section(subject: CardSubject, context: CardContext): HtmlChunk? {
        val variable = subject as? CardSubject.Variable ?: return null
        if (variable.local || variable.name in VarUsageSearch.LOOP_ONLY_NAMES) return null
        val counts = UsageCounts.of(context.project, variable.root, variable.name)
        val link = HtmlChunk.link(UsagesCardLinks.show(context.file, context.offset, variable.name), AnsibilityUsagesBundle.message("card.show.usages"))
        val content = HtmlChunk.fragment(HtmlChunk.text(counts.text(variable.root) + SEPARATOR), link)
        return CardSection.row(AnsibilityUsagesBundle.message("card.used.in"), content)
    }

    /** The uses of one name in one root, by family. */
    internal class UsageCounts(val templates: Int, val tasks: Int, val conditions: Int, val values: Int) {
        /** `4 templates · 7 tasks · 2 conditions`, or `no uses in falcon`. */
        fun text(root: AnsibleRoot): String {
            val parts = listOfNotNull(
                templates.takeIf { it > 0 }?.let { AnsibilityUsagesBundle.message("card.count.templates", it) },
                tasks.takeIf { it > 0 }?.let { AnsibilityUsagesBundle.message("card.count.tasks", it) },
                conditions.takeIf { it > 0 }?.let { AnsibilityUsagesBundle.message("card.count.conditions", it) },
                values.takeIf { it > 0 }?.let { AnsibilityUsagesBundle.message("card.count.values", it) },
            )
            return if (parts.isEmpty()) AnsibilityUsagesBundle.message("card.count.none", root.displayName) else parts.joinToString(PART_SEPARATOR)
        }

        companion object {
            /** File kinds whose templated YAML values are other variables' values rather than task arguments. */
            private val VALUE_KINDS = setOf(
                FileKind.ROLE_DEFAULTS, FileKind.ROLE_VARS, FileKind.GROUP_VARS, FileKind.HOST_VARS, FileKind.MOLECULE_VARS,
                FileKind.INVENTORY, FileKind.MOLECULE_CONFIG,
            )

            /** The counts of [name] in [root], from one index lookup. Call in a read action in smart mode. */
            fun of(project: Project, root: AnsibleRoot, name: String): UsageCounts {
                val workspace = AnsibleWorkspace.getInstance(project)
                val templateFiles = HashSet<VirtualFile>()
                var tasks = 0
                var conditions = 0
                var values = 0
                VarUsageQuery.getInstance(project).process(root, name, null) { use ->
                    ProgressManager.checkCanceled()
                    if (use.called) return@process true
                    val file = use.location.file
                    when (use.container) {
                        JinjaContainer.TEMPLATE_FILE -> templateFiles += file
                        JinjaContainer.YAML_EXPRESSION -> conditions++
                        JinjaContainer.YAML_TEMPLATE -> if (workspace.contextOf(file)?.kind in VALUE_KINDS) values++ else tasks++
                    }
                    true
                }
                return UsageCounts(templateFiles.size, tasks, conditions, values)
            }
        }
    }

    private companion object {
        const val SEPARATOR = " — "
        const val PART_SEPARATOR = " · "
    }
}
