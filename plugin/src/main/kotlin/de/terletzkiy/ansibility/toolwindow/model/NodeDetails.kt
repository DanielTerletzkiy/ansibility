package de.terletzkiy.ansibility.toolwindow.model

import org.jetbrains.annotations.Nls

/**
 * What the details pane shows for the selected node (plan F6.2): a title, an optional subtitle and titled sections
 * of items, plus an optional [content] the pane renders below them (HA7a: the Effective vars table with its play
 * selector). Pure data, so the wording is tested without Swing.
 */
data class NodeDetails(
    @Nls val title: String,
    @Nls val subtitle: String?,
    val sections: List<DetailSection>,
    val content: DetailsContent? = null,
) {
    fun section(title: String): DetailSection? = sections.firstOrNull { it.title == title }
}

/**
 * Content of a details pane beyond titled sections, rendered by the pane's view (the tool window's details view knows
 * every implementation). Pure data, computed in the background like the rest of the details.
 */
interface DetailsContent

/** A titled list of items; empty sections are left out by the builders. */
data class DetailSection(@Nls val title: String, val items: List<DetailItem>)

/**
 * One line of a section: [text], a grey [note] after it, and a [target] that makes it a link. [emphasized] marks the
 * selected node inside a list (the group itself in the `sort_groups` order).
 */
data class DetailItem(
    @Nls val text: String,
    @Nls val note: String? = null,
    val target: NavigationTarget? = null,
    val emphasized: Boolean = false,
) {
    /** `text — note`, the plain form used by tests and accessibility. */
    override fun toString(): String = if (note.isNullOrEmpty()) text else "$text — $note"
}

/** Collects sections, dropping empty ones. */
internal class DetailsBuilder(@Nls private val title: String, @Nls private val subtitle: String? = null) {
    private val sections = ArrayList<DetailSection>()

    fun section(@Nls title: String, items: List<DetailItem>) {
        if (items.isNotEmpty()) sections += DetailSection(title, items)
    }

    fun section(@Nls title: String, vararg items: DetailItem?) = section(title, items.filterNotNull())

    fun build(content: DetailsContent? = null): NodeDetails = NodeDetails(title, subtitle, sections, content)
}
