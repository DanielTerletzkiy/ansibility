package de.terletzkiy.ansibility.api

import com.intellij.lang.documentation.DocumentationMarkup
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.HtmlChunk
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.messages.Topic
import de.terletzkiy.ansibility.toolwindow.model.AnsibleTreeNode
import org.jetbrains.annotations.Nls

// ------------------------------------------------------------------------------------------------
// Internal extension points for presentation contributions (plan amendment R7/R8, "New internal extension points";
// declared in ansibility-core.xml). They let the vault (V), host-awareness (HA) and workspace-scope (WS) tracks add
// to the variable card, the X02 status-bar widget and the Ansible tool window without editing each other's files.
// Every EP is ordered: register with an `id` and an `order` attribute.
// ------------------------------------------------------------------------------------------------

/** What a documentation card is about, as a [CardSection] sees it. Every subject belongs to exactly one root. */
sealed interface CardSubject {
    /** The root the card resolves in; sections never report anything from another root. */
    val root: AnsibleRoot

    /**
     * A variable card (F1.2, F4.3, F4.8): variable [name] and the nested keys [path] below it, either referenced
     * (Jinja, a description link) or defined at [definition] (a vars-file key, a spec option, a `register:` value …).
     */
    data class Variable(
        override val root: AnsibleRoot,
        val name: String,
        /** Keys or accessors below [name] as written, sequence indices included (`["0", "port"]`); empty for the variable itself. */
        val path: List<String>,
        /** Where the documented definition is written, for a definition card; null for a reference. */
        val definition: SourceLocation?,
        /** The card documents a Jinja local (`set`, a `for` target, a macro parameter), not a variable of the root. */
        val local: Boolean = false,
    ) : CardSubject

    /** A role (a `roles:` entry, `include_role`, a role name in a description): reach sections (F8.11). */
    data class Role(override val root: AnsibleRoot, val name: String) : CardSubject

    /** A file a task consumes (`copy`/`template` `src:`, a `vars_files` entry, F1.8 targets): the whole-file vault line (F7.7). */
    data class File(override val root: AnsibleRoot, val file: VirtualFile) : CardSubject
}

/** Where a card is shown from. */
data class CardContext(
    val project: Project,
    /** The file the card was requested in: the host file, never an injected fragment. */
    val file: VirtualFile,
    /** The offset in [file] the card was requested at, or -1 for a card reached through a link. */
    val offset: Int,
)

/** Where a [CardSection]'s chunk goes in the card. */
enum class CardPlacement {
    /** Directly below the definition line, above the description (F8.2's "Effective" section). */
    TOP,

    /** Inside the sections table after the built-in rows: the chunk is one or more rows ([CardSection.row]). */
    SECTION,

    /** After the sections table. */
    BOTTOM,
}

/**
 * Adds a section to a documentation card. The card owner calls every section in EP order for each placement and
 * inserts the non-null chunks as they are; a section returns null when it has nothing to say about the subject.
 *
 * Called in the read action (smart mode) that builds the card, on a background thread. A section must not decrypt,
 * run a process or block, must call `ProgressManager.checkCanceled()` in loops, and must never put a secret into the
 * HTML (the D13 secret-handling rules of plan amendment R7/R8): vault sections read only [VaultStatusService]. Texts
 * come from the contributor's own bundle; links use the card's `psi_element://` scheme of the owning area.
 *
 * Registration ids (order: the card's built-in content, then): `ansibilityHostEffective` (HA4a, TOP),
 * `ansibilityHostDefinition` (HA4a, SECTION), `ansibilityVault` (V3, SECTION, `order="after ansibilityHostDefinition"`).
 */
interface CardSection {
    /** Where the chunk goes; one section serves one placement. */
    val placement: CardPlacement get() = CardPlacement.SECTION

    fun section(subject: CardSubject, context: CardContext): HtmlChunk?

    companion object {
        val EP_NAME: ExtensionPointName<CardSection> = ExtensionPointName("de.terletzkiy.ansibility.cardSection")

        /** The chunks of every section registered for [placement], in EP order; a section that throws is logged and skipped. */
        fun collect(subject: CardSubject, context: CardContext, placement: CardPlacement): List<HtmlChunk> {
            val chunks = ArrayList<HtmlChunk>()
            EP_NAME.forEachExtensionSafe { section ->
                if (section.placement == placement) section.section(subject, context)?.let(chunks::add)
            }
            return chunks
        }

        /** One row of the card's sections table, rendered like the built-in rows: header [title], then [content]. */
        fun row(@Nls title: String, content: HtmlChunk): HtmlChunk = HtmlChunk.tag("tr").children(
            DocumentationMarkup.SECTION_HEADER_CELL.child(HtmlChunk.p().addText(title)),
            DocumentationMarkup.SECTION_CONTENT_CELL.child(content),
        )
    }
}

/** One segment of the X02 status-bar widget, after `Ansibility: <root> · <env> › <host> · core <v>` (`AnsibilityCoreBundle` `status.text`). */
data class WidgetSegment(
    /** The segment text without separator, e.g. `file: keepalived → 2 hosts`, `🔓 default`, `scope heron⚠`. */
    @Nls val text: String,
    /** A tooltip line for the segment; when the status bar is narrow, the widget moves the segment text here too. */
    @Nls val tooltip: String? = null,
    /** Actions the widget's popup lists for this segment (Unlock…, Lock all, Switch Context…). */
    val popupActions: List<AnAction> = emptyList(),
)

/**
 * Contributes a segment to the X02 Ansible context widget (context switching, lock state, workspace scope). The
 * fixed order is the built-in `Ansibility: <root> · <env> › <host> · core <v>`, then `ansibilityFileScope` (HA3),
 * `ansibilityVault` (V8, `order="after ansibilityFileScope"`), `ansibilityWorkspaceScope` (WS9,
 * `order="after ansibilityVault"`).
 *
 * Called in a background read action whenever the widget refreshes (editor selection, structure changes, and
 * [ContextWidgetSegmentListener] events); must be cheap: no decryption, no process, no index-wide scan.
 */
fun interface ContextWidgetSegment {
    /** The segment for the selected editor's [file] (always inside an Ansible root), or null to show nothing. */
    fun segment(project: Project, file: VirtualFile): WidgetSegment?

    companion object {
        val EP_NAME: ExtensionPointName<ContextWidgetSegment> = ExtensionPointName("de.terletzkiy.ansibility.contextWidgetSegment")

        /** The segments of every contributor in EP order; a contributor that throws is logged and skipped. */
        fun segments(project: Project, file: VirtualFile): List<WidgetSegment> {
            val segments = ArrayList<WidgetSegment>()
            EP_NAME.forEachExtensionSafe { contributor -> contributor.segment(project, file)?.let(segments::add) }
            return segments
        }
    }
}

/** Notified on the project message bus when a [ContextWidgetSegment]'s text may have changed; the widget refreshes. */
fun interface ContextWidgetSegmentListener {
    /** Called on any thread; the widget only schedules its own refresh. */
    fun segmentsChanged()

    companion object {
        @Topic.ProjectLevel
        val TOPIC: Topic<ContextWidgetSegmentListener> =
            Topic(ContextWidgetSegmentListener::class.java, Topic.BroadcastDirection.NONE)
    }
}

/**
 * Adds children to a node of the Ansible tool window: the Vault node per root (V8), Effective vars and Targeted by
 * under host nodes (HA7a). The tree calls it for root, environment, group and host nodes (R9's Environments tab
 * reuses those nodes and gets the same children) and appends the result after the node's built-in children.
 *
 * Every returned node must have [AnsibleTreeNode.parent] == the given parent and a segment of its own (prefix it with
 * the contributor, e.g. `vault`), because nodes are equal by key. Called on the tree's background read-action thread.
 */
fun interface ToolWindowNodeContributor {
    fun children(parent: AnsibleTreeNode): List<AnsibleTreeNode>

    companion object {
        val EP_NAME: ExtensionPointName<ToolWindowNodeContributor> =
            ExtensionPointName("de.terletzkiy.ansibility.toolWindowNodeContributor")

        /**
         * The contributed children of [parent], in EP order. A contributor that throws, or returns a node of another
         * parent, is logged and contributes nothing.
         */
        fun childrenOf(parent: AnsibleTreeNode): List<AnsibleTreeNode> {
            val children = ArrayList<AnsibleTreeNode>()
            EP_NAME.forEachExtensionSafe { contributor ->
                val contributed = contributor.children(parent)
                val foreign = contributed.firstOrNull { it.parent != parent }
                check(foreign == null) { "${contributor.javaClass.name} returned ${foreign?.key}, which is not a child of ${parent.key}" }
                children += contributed
            }
            return children
        }
    }
}
