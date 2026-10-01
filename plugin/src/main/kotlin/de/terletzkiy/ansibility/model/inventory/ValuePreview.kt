package de.terletzkiy.ansibility.model.inventory

import com.intellij.openapi.vfs.VirtualFile
import de.terletzkiy.ansibility.api.ValueShape
import de.terletzkiy.ansibility.index.ValueSummary
import de.terletzkiy.ansibility.semantics.yaml.Resolved
import de.terletzkiy.ansibility.semantics.yaml.YEmpty
import de.terletzkiy.ansibility.semantics.yaml.YMap
import de.terletzkiy.ansibility.semantics.yaml.YScalar
import de.terletzkiy.ansibility.semantics.yaml.YSeq
import de.terletzkiy.ansibility.semantics.yaml.YValue
import de.terletzkiy.ansibility.semantics.yaml.YVault

/**
 * Short, single-line previews of variable values for tables, hover and completion tails (plan A.7, F6.5).
 *
 * The rule itself lives in [ValueSummary.preview], which the `ansible.var.def` index uses too, so an indexed
 * preview and a preview computed from the loaded file are always the same text. Secrets never appear: there is no
 * preview for `vault_*` keys, for values in vault files and for `!vault` values; inside a collection, `!vault`
 * values print as `!vault` and values of nested `vault_*` keys as `***`.
 */
object ValuePreview {
    /** Maximum preview length, the ellipsis included. */
    const val MAX_LENGTH: Int = ValueSummary.MAX_PREVIEW

    /** The preview of variable [name] with [value] defined in [file], or null when it must stay hidden. */
    fun of(name: String, value: YValue, file: VirtualFile): String? = ValueSummary.preview(name, value, file.name)

    /** Whether the value of [name] must never be shown, whatever file it is in. */
    fun isSecret(name: String, value: YValue): Boolean = ValueSummary.isSecret(name, value)

    /** [value] on one line, at most [MAX_LENGTH] characters. */
    fun render(value: YValue): String = ValueSummary.render(value)

    /** The [ValueShape] of a loaded value. */
    fun shape(value: YValue): ValueShape = when (value) {
        is YVault -> ValueShape.VAULT
        is YEmpty -> ValueShape.NULL
        is YMap, is YSeq -> ValueShape.CONTAINER
        is YScalar -> when {
            value.resolved == Resolved.Null -> ValueShape.NULL
            "{{" in value.text || "{%" in value.text -> ValueShape.JINJA
            else -> ValueShape.LITERAL
        }
    }
}
