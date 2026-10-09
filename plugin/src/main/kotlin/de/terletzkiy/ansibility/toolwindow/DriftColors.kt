package de.terletzkiy.ansibility.toolwindow

import com.intellij.openapi.editor.colors.ColorKey
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.ui.JBColor
import de.terletzkiy.ansibility.toolwindow.model.NodeColor
import java.awt.Color

/**
 * The VCS file-status colours of drift rows (plan amendment R24, D179): the colour scheme's `FILESTATUS_MODIFIED`,
 * `FILESTATUS_ADDED` and `FILESTATUS_DELETED` keys (the keys `FileStatusFactory` creates as `FILESTATUS_<id>` for
 * `FileStatus.MODIFIED`/`ADDED`/`DELETED` in 262), so rows look like the Project view's VCS colours and follow the
 * user's scheme. Read through [ColorKey.find], so no VCS class is needed (the plugin loads without VCS support);
 * a scheme without the key falls back to the platform's default colours.
 */
object DriftColors {
    private val KEYS: Map<NodeColor, String> = mapOf(
        NodeColor.MODIFIED to "FILESTATUS_MODIFIED",
        NodeColor.ADDED to "FILESTATUS_ADDED",
        NodeColor.DELETED to "FILESTATUS_DELETED",
    )

    private val FALLBACK: Map<NodeColor, Color> = mapOf(
        NodeColor.MODIFIED to JBColor(0x0032A0, 0x6897BB),
        NodeColor.ADDED to JBColor(0x0A7700, 0x629755),
        NodeColor.DELETED to JBColor(0x6C6C6C, 0x6C6C6C),
    )

    /** The colour scheme's key of [color]. */
    fun keyOf(color: NodeColor): ColorKey = ColorKey.find(KEYS.getValue(color))

    /** The colour of [color] in the current scheme, or the platform default. Any thread. */
    fun of(color: NodeColor): Color =
        EditorColorsManager.getInstance().schemeForCurrentUITheme.getColor(keyOf(color)) ?: FALLBACK.getValue(color)
}
