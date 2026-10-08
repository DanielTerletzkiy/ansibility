package de.terletzkiy.ansibility.context.host.card

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.CardContext
import de.terletzkiy.ansibility.api.HostKey
import de.terletzkiy.ansibility.api.SourceLocation
import de.terletzkiy.ansibility.context.host.Location
import de.terletzkiy.ansibility.context.host.card.HostCardTexts.message
import de.terletzkiy.ansibility.settings.EnvironmentChoice
import org.jetbrains.annotations.Nls

/**
 * How each definition of the card's "Set in" rows takes effect for the hosts of the card (plan amendment R7/R8, F8.2
 * "Set in is ranked"), for `vars.VarCardHtml`:
 * - the winners come first, the one that wins on most hosts first, then the definitions that load and lose, then the rest;
 * - in All mode a winner says "wins on 3 of 4 hosts" and a loser "shadowed on 2 of 4 hosts";
 * - in host mode (one host: a selected host, a `host_vars` file) the winner says "✓", a loser is struck through and every
 *   other definition says "not for prod-prod1";
 * - with an environment selected, the definitions of other environments collapse into one "Other environments" line;
 * - a definition an include task gives the card's file (its `vars:` key, its `loop_var`) says "applies through the
 *   include" instead ([TaskVars.included]), after the winners.
 *
 * Built on the card's [CardView] (the same selection-aware scope and single-name evaluation as the Effective section, so
 * the card computes it once). One instance serves one rendering of "Set in" and is not thread-safe; call in the card's
 * read action.
 */
class SetInEffects private constructor(private val view: CardView, private val project: Project) {
    /** What one definition does for the card's hosts. */
    enum class Kind { WINS, SHADOWED, NOT_FOR_HOST, OUT_OF_SCOPE, INCLUDED }

    /** The effect of one "Set in" definition: a short [text] after its value, and whether it is shown [struck] through. */
    class Effect(val kind: Kind, @Nls val text: String?, val struck: Boolean, internal val rank: Int)

    private val single: HostKey? = view.singleHost

    /** The definitions the include tasks that run the card's file give the name with ([TaskVars.included]). */
    private val included: Set<Location> = view.taskVars.included.mapTo(HashSet()) { Location.of(it) }
    private val inventoryHosts = view.hosts.filter { !it.isMolecule }
    private val moleculeHosts = view.hosts.filter { it.isMolecule }

    /**
     * The environment whose definitions stay expanded: the selected one (or, when the file scope overrides the selection,
     * the file's single environment); null in All mode, where nothing collapses.
     */
    val focusEnvironment: String? = run {
        val scope = view.scope
        val named = (scope.selection.environment as? EnvironmentChoice.Named)?.name
        when {
            scope.overriddenSelection -> inventoryHosts.map { it.environment }.distinct().singleOrNull()
            else -> named
        }
    }

    /** The title of the collapsed line of the other environments' definitions. */
    @get:Nls
    val otherEnvironmentsTitle: String get() = message("card.set.in.other.environments")

    /** The effects computed so far: "Set in" sorts by [rank] and renders [effect], so each definition is evaluated once. */
    private val effects = HashMap<Location, Effect>()

    /** The effect of the definition written at [location]. */
    fun effect(location: SourceLocation): Effect {
        val key = Location(location.file, location.offset)
        return effects.getOrPut(key) { compute(key) }
    }

    private fun compute(key: Location): Effect {
        val wins = view.winsOn[key].orEmpty()
        val shadowed = view.shadowedOn[key].orEmpty()
        // An includer's `vars:` key or loop variable applies wherever that include runs the file: never "not for" a host.
        if (key in included && wins.isEmpty() && shadowed.isEmpty()) {
            return Effect(Kind.INCLUDED, message("card.set.in.included"), struck = false, rank = INCLUDED_RANK)
        }
        if (single != null) {
            return when (single) {
                in wins -> Effect(Kind.WINS, message("card.set.in.winner.host"), struck = false, rank = 0)
                in shadowed -> Effect(Kind.SHADOWED, null, struck = true, rank = SHADOWED_RANK)
                else -> Effect(Kind.NOT_FOR_HOST, message("card.set.in.not.for", single.host), struck = false, rank = OTHER_RANK)
            }
        }
        val inventoryWins = inventoryHosts.count { it in wins }
        val moleculeWins = moleculeHosts.count { it in wins }
        val inventoryShadowed = inventoryHosts.count { it in shadowed }
        return when {
            inventoryWins > 0 -> Effect(
                Kind.WINS,
                message("card.set.in.wins", inventoryWins, message("card.effective.hosts", inventoryHosts.size)),
                struck = false,
                rank = inventoryHosts.size - inventoryWins,
            )
            moleculeWins > 0 -> Effect(
                Kind.WINS,
                message("card.set.in.wins.molecule", moleculeWins, moleculeHosts.size),
                struck = false,
                rank = inventoryHosts.size + moleculeHosts.size - moleculeWins,
            )
            inventoryShadowed > 0 || shadowed.isNotEmpty() -> Effect(
                Kind.SHADOWED,
                if (inventoryShadowed > 0) message("card.set.in.shadowed", inventoryShadowed, message("card.effective.hosts", inventoryHosts.size)) else null,
                struck = false,
                rank = SHADOWED_RANK,
            )
            else -> Effect(Kind.OUT_OF_SCOPE, null, struck = false, rank = OTHER_RANK)
        }
    }

    /** The ordering key of [location]: lower first (winners on most hosts, then losers, then the rest). */
    fun rank(location: SourceLocation): Int = effect(location).rank

    /** Whether [location] is a definition of an environment other than [focusEnvironment] (it collapses). */
    fun isOtherEnvironment(location: SourceLocation): Boolean {
        val focus = focusEnvironment ?: return false
        val environment = environmentOf(location.file) ?: return false
        return environment != focus
    }

    private fun environmentOf(file: VirtualFile): String? = AnsibleWorkspace.getInstance(project).contextOf(file)?.environment

    companion object {
        private const val INCLUDED_RANK = 50_000
        private const val SHADOWED_RANK = 100_000
        private const val OTHER_RANK = 200_000

        /**
         * The effects for the card of variable [name] shown from [context] ([definition]: the documented definition of a
         * definition card, else null), seen from the same position as the card's Effective section
         * ([HostCardViews.scopeOffset]: a reference card reached through a link covers its whole file); null when the
         * card has no host scope (dumb mode, no inventory reaches the file, the model cannot answer): "Set in" then stays
         * unranked.
         */
        fun of(project: Project, definition: SourceLocation?, context: CardContext, name: String): SetInEffects? {
            val view = HostCardViews.getInstance(project).view(definition, context, name) ?: return null
            if (view.scope.targets.isEmpty()) return null
            return SetInEffects(view, project)
        }
    }
}
