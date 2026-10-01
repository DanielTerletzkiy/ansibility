package de.terletzkiy.ansibility.index

import de.terletzkiy.ansibility.api.ValueShape
import de.terletzkiy.ansibility.semantics.yaml.Resolved
import de.terletzkiy.ansibility.semantics.yaml.ScalarStyle
import de.terletzkiy.ansibility.semantics.yaml.YEmpty
import de.terletzkiy.ansibility.semantics.yaml.YMap
import de.terletzkiy.ansibility.semantics.yaml.YScalar
import de.terletzkiy.ansibility.semantics.yaml.YSeq
import de.terletzkiy.ansibility.semantics.yaml.YValue
import de.terletzkiy.ansibility.semantics.yaml.YVault

/** The YAML 1.1 type of a literal value, as stored in the `ansible.var.def` index (stable [code]s). */
enum class LiteralType(val code: Int) {
    /** Not a scalar literal (containers, Jinja, vault, or a definition without a written value). */
    NONE(0),
    STR(1),
    INT(2),
    FLOAT(3),
    BOOL(4),
    TIMESTAMP(5),
    NULL(6),

    /** A bare `=` scalar, which PyYAML cannot load. */
    UNLOADABLE(7),
    ;

    companion object {
        private val BY_CODE = entries.associateBy { it.code }

        fun ofCode(code: Int): LiteralType = BY_CODE[code] ?: NONE
    }
}

/**
 * Shape, literal type and preview of a written value, computed from the PSI-free [YValue] model.
 *
 * [preview] is **the** vault-safe preview rule of the plugin (plan A.7, F6.5). The `ansible.var.def` indexer and the
 * inventory model (`model.inventory.ValuePreview`, which delegates here) both use it, so a plaintext secret can reach
 * neither an index nor a table:
 * - no preview at all for `vault_*` names, for `!vault` values and for files whose name starts with `vault`
 *   (`vault.yml`, `vault`, `vault_prod.yml`, case-insensitive);
 * - inside a collection, `!vault` values print as `!vault` and the values of nested `vault_*` keys as `***`.
 */
object ValueSummary {
    /** Longest preview (plan A.7), the ellipsis included. */
    const val MAX_PREVIEW: Int = 80

    private const val ELLIPSIS = "…"
    private const val VAULT_PREFIX = "vault_"
    private const val VAULT_FILE_PREFIX = "vault"
    private const val MASK = "***"
    private const val VAULT_TAG = "!vault"

    /** The shape of [value]: vault, null, a Jinja template string, another scalar literal, or a container. */
    fun shape(value: YValue): ValueShape = when (value) {
        is YVault -> ValueShape.VAULT
        is YEmpty -> ValueShape.NULL
        is YMap, is YSeq -> ValueShape.CONTAINER
        is YScalar -> when {
            value.resolved == Resolved.Null -> ValueShape.NULL
            value.tag != "!unsafe" && value.resolved is Resolved.Str && JinjaBearing.hasTemplateMarkers(value.text) ->
                ValueShape.JINJA
            else -> ValueShape.LITERAL
        }
    }

    /** The YAML 1.1 type of a scalar literal; [LiteralType.NONE] for everything else. */
    fun literalType(value: YValue): LiteralType = when (value) {
        is YEmpty -> LiteralType.NULL
        is YScalar -> when (value.resolved) {
            is Resolved.Str -> if (shape(value) == ValueShape.JINJA) LiteralType.NONE else LiteralType.STR
            is Resolved.Int -> LiteralType.INT
            is Resolved.Float -> LiteralType.FLOAT
            is Resolved.Bool -> LiteralType.BOOL
            is Resolved.Timestamp -> LiteralType.TIMESTAMP
            Resolved.Null -> LiteralType.NULL
            Resolved.Unloadable -> LiteralType.UNLOADABLE
        }
        else -> LiteralType.NONE
    }

    /**
     * The one-line preview of variable [name] with [value], written in a file called [fileName]; null when it must stay
     * hidden ([isSecret], [isVaultFileName]). At most [MAX_PREVIEW] characters.
     */
    fun preview(name: String, value: YValue, fileName: String): String? =
        if (isSecret(name, value) || isVaultFileName(fileName)) null else render(value)

    /** Whether the value of [name] must never be shown, whatever file it is in: a `vault_*` name or a `!vault` value. */
    fun isSecret(name: String, value: YValue): Boolean = name.startsWith(VAULT_PREFIX) || value is YVault

    /** Whether a file called [fileName] holds secrets (`vault.yml`, `vault`, `vault_prod.yml` …), so none of its values is previewed. */
    fun isVaultFileName(fileName: String): Boolean = fileName.lowercase().startsWith(VAULT_FILE_PREFIX)

    /**
     * [value] on one line, at most [MAX_PREVIEW] characters. Flow scalars appear as written (quotes show the type), block
     * scalars by their first line, tags other than `!vault` in front. Nested secrets are masked; use [preview] for a
     * variable's own value.
     */
    fun render(value: YValue): String {
        val out = StringBuilder()
        append(value, out)
        return if (out.length <= MAX_PREVIEW) out.toString() else out.substring(0, MAX_PREVIEW - ELLIPSIS.length) + ELLIPSIS
    }

    /** Appends a rendering of [value], stopping once [out] is past the preview limit. */
    private fun append(value: YValue, out: StringBuilder) {
        if (out.length > MAX_PREVIEW) return
        when (value) {
            is YEmpty -> out.append("null")
            is YVault -> out.append(VAULT_TAG)
            is YScalar -> out.append(scalar(value))
            is YSeq -> {
                out.append('[')
                value.items.forEachIndexed { i, item ->
                    if (out.length > MAX_PREVIEW) return
                    if (i > 0) out.append(", ")
                    append(item, out)
                }
                out.append(']')
            }
            is YMap -> {
                out.append('{')
                value.entries.forEachIndexed { i, entry ->
                    if (out.length > MAX_PREVIEW) return
                    if (i > 0) out.append(", ")
                    out.append(oneLine(entry.key.text)).append(": ")
                    if (entry.key.text.startsWith(VAULT_PREFIX) && entry.value !is YVault) out.append(MASK) else append(entry.value, out)
                }
                out.append('}')
            }
        }
    }

    /** Flow scalars as written (quotes show the type); block scalars by their first line. */
    private fun scalar(value: YScalar): String {
        val body = when (value.style) {
            ScalarStyle.SINGLE_QUOTED, ScalarStyle.DOUBLE_QUOTED ->
                if ('\n' in value.sourceText) oneLine(value.text) else value.sourceText
            ScalarStyle.PLAIN, ScalarStyle.LITERAL, ScalarStyle.FOLDED -> oneLine(value.text)
        }
        return if (value.tag != null && value.tag != VAULT_TAG) "${value.tag} $body" else body
    }

    private fun oneLine(text: String): String {
        val trimmed = text.trimEnd('\n')
        val first = trimmed.substringBefore('\n')
        return if (first.length < trimmed.length) "$first $ELLIPSIS" else first
    }
}
