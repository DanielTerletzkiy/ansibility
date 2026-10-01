package de.terletzkiy.ansibility.toolwindow.model

import de.terletzkiy.ansibility.api.VarsLayer
import de.terletzkiy.ansibility.context.TargetVersion
import de.terletzkiy.ansibility.context.TargetVersionSource
import de.terletzkiy.ansibility.toolwindow.AnsibilityToolWindowBundle.message
import org.jetbrains.annotations.Nls

/**
 * Precedence-level wording (plan F6.2): every [VarsLayer] has a short label that names its level and whom it beats
 * (`L5 playbook group_vars/all — beats env group_vars/all (L4)`) and a plain-words sentence for tooltips.
 */
object LayerTexts {
    /** The short label of [layer] for the group or host [owner]. */
    @Nls
    fun label(layer: VarsLayer, owner: String?): String = message("layer.label.${layer.name}", owner.orEmpty())

    /** The plain-words explanation of [layer] for the group or host [owner]. */
    @Nls
    fun tooltip(layer: VarsLayer, owner: String?): String = message("layer.tooltip.${layer.name}", owner.orEmpty())

    /** The label of a [LayerSource]: its level, its layer and whom it beats. */
    @Nls
    fun label(source: LayerSource): String = when (source) {
        is LayerSource.File -> label(source.layer, source.owner)
        is LayerSource.Inline -> label(VarsLayer.INVENTORY_FILE_GROUP, source.group.name)
        is LayerSource.HostInline -> label(VarsLayer.INVENTORY_FILE_HOST, source.host.name)
        is LayerSource.Connection -> message("connection.extra")
    }

    /** The plain-words explanation of a [LayerSource]. */
    @Nls
    fun tooltip(source: LayerSource): String = when (source) {
        is LayerSource.File -> tooltip(source.layer, source.owner)
        is LayerSource.Inline -> tooltip(VarsLayer.INVENTORY_FILE_GROUP, source.group.name)
        is LayerSource.HostInline -> tooltip(VarsLayer.INVENTORY_FILE_HOST, source.host.name)
        is LayerSource.Connection -> message("connection.tooltip")
    }
}

/** Small text helpers shared by the node labels and the details pane. */
object ToolWindowTexts {
    /** Names joined with `, `; beyond [max] the rest is counted (`a, b, c +4`). */
    fun joinCapped(names: List<String>, max: Int): String {
        if (names.size <= max) return names.joinToString(", ")
        return names.take(max).joinToString(", ") + " " + message("count.more", names.size - max)
    }

    /**
     * Paths grouped by directory and extension, the plan's `group_vars/all/{vars,vault}.yml` notation: a single
     * file stays as written, several files of one directory with one extension share a brace list.
     */
    fun compactPaths(paths: List<String>): String {
        val groups = LinkedHashMap<Pair<String, String>, MutableList<String>>()
        for (path in paths) {
            val dir = path.substringBeforeLast('/', "")
            val name = path.substringAfterLast('/')
            val ext = name.substringAfterLast('.', "")
            val stem = if (ext.isEmpty()) name else name.dropLast(ext.length + 1)
            groups.getOrPut(dir to ext) { ArrayList() } += stem
        }
        return groups.entries.joinToString(", ") { (key, stems) ->
            val (dir, ext) = key
            val prefix = if (dir.isEmpty()) "" else "$dir/"
            val suffix = if (ext.isEmpty()) "" else ".$ext"
            if (stems.size == 1) "$prefix${stems.single()}$suffix" else "$prefix{${stems.joinToString(",")}}$suffix"
        }
    }

    /** `core 2.18.8 (docker pin)`, `core 2.21.4 (local install, guessed)` or `core unknown`. */
    @Nls
    fun coreText(target: TargetVersion): String {
        val version = target.version ?: return message("root.core.unknown")
        if (target.source == TargetVersionSource.NONE) return message("root.core.unknown")
        return message("root.core", version.toString(), message("root.core.source.${target.source.name}"))
    }
}
