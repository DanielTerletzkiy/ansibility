package de.terletzkiy.ansibility.vault.regenerate

import com.intellij.openapi.vfs.VirtualFile
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.semantics.vault.GenerationRule
import de.terletzkiy.ansibility.semantics.vault.GeneratorKind
import de.terletzkiy.ansibility.semantics.vault.NotPasswordReason
import de.terletzkiy.ansibility.semantics.vault.SecretGenerator
import de.terletzkiy.ansibility.vault.ui.AnsibilityVaultUiBundle.message
import org.jetbrains.annotations.Nls

/** What a row of Regenerate Secrets… stands for (plan amendment R29, D216). */
enum class RowKind {
    /** An inline `!vault` value: regenerated with the id and header it has now (D220). */
    VAULT_VALUE,

    /** A plaintext `vault_*` value (a placeholder): gets its first envelope, with the id Ansible's rule picks (D220). */
    PLAINTEXT,

    /** A whole-file vault: listed, never regenerated (D216: vault files hold keys and certificates). */
    VAULT_FILE,
}

/** Where a row's generator comes from (D218: a rule of the root, then "same as now", then the default). */
enum class GeneratorSource { RULE, SAME_AS_NOW, DEFAULT, CHOSEN }

/**
 * One row of the selection dialog (plan amendment R29, D217): a value of the selection with its key path, id, how the
 * new value will be made, its marks and whether it is checked. Never holds a value, old or new; [toString] names the
 * row only.
 */
class RegenerateRow(
    val id: Int,
    val file: VirtualFile,
    val root: AnsibleRoot,
    /** The repository the file belongs to, as the tree shows it. */
    @Nls val repository: String,
    /** The keys from the document root (`db.vault_password`, `users.0.password`), or the file name for a vault file. */
    val keyPath: String,
    /** The last mapping key, which the rules match (null for a sequence item or a vault file). */
    val keyName: String?,
    val kind: RowKind,
    /** The id that decrypts the value now (and will encrypt the new one), or the id a placeholder gets; null when it is asked later. */
    val identity: String?,
    /** Why the current value does not look like a password, or null. */
    val notPassword: NotPasswordReason?,
    /** Why the value cannot be regenerated at all (not decrypted, a vault file), or null. */
    @Nls val unavailable: String?,
    /** Shorter than 16 characters: marked, length kept (D217). */
    val short: Boolean,
    /** The matching rule of the root, if any (D218 rule 1). */
    val rule: GenerationRule?,
    generator: SecretGenerator,
    source: GeneratorSource,
) {
    /** How the new value is made; editable for selected rows (D217). */
    var generator: SecretGenerator = generator
        internal set

    var source: GeneratorSource = source
        internal set

    /** Whether the row is regenerated: all checked except values that are no password and unavailable rows (D217). */
    var checked: Boolean = unavailable == null && notPassword == null
        internal set

    /** The equal-value group (D219), or null when the value is alone (or its group was split). */
    var group: Int? = null
        internal set

    /** False for unavailable rows: their checkbox is disabled. */
    val enabled: Boolean get() = unavailable == null

    override fun toString(): String = "RegenerateRow($id, ${file.name}, $keyPath, $kind)"
}

/**
 * The selection of Regenerate Secrets… (plan amendment R29, D217–D219): every row of the scanned files, with the
 * equal-value groups. The dialog edits it through [setChecked], [setGenerator] and [split], which keep a group together:
 * its rows are checked together and share one generator, so equal values stay equal (D219).
 */
class RegenerateRequest(val rows: List<RegenerateRow>) {
    /** The rows of [group]. */
    fun members(group: Int): List<RegenerateRow> = rows.filter { it.group == group }

    /** [row] and the other rows of its group. */
    fun withGroup(row: RegenerateRow): List<RegenerateRow> = row.group?.let(::members) ?: listOf(row)

    /** Checks or unchecks [row] (and its group); unavailable rows stay unchecked. */
    fun setChecked(row: RegenerateRow, checked: Boolean) {
        for (member in withGroup(row)) if (member.enabled) member.checked = checked
    }

    /** Gives [selected] (and their groups) [generator]. */
    fun setGenerator(selected: Collection<RegenerateRow>, generator: SecretGenerator) {
        for (row in selected.flatMap(::withGroup).distinct()) {
            if (!row.enabled) continue
            row.generator = generator
            row.source = GeneratorSource.CHOSEN
        }
    }

    /** Splits [group]: each of its values gets a value of its own (D219). */
    fun split(group: Int) {
        for (row in members(group)) row.group = null
    }

    /** The rows that will be regenerated. */
    val selected: List<RegenerateRow> get() = rows.filter { it.enabled && it.checked }

    /** The groups with more than one row, by id. */
    val groups: Map<Int, List<RegenerateRow>> get() = rows.filter { it.group != null }.groupBy { it.group!! }.filterValues { it.size > 1 }

    override fun toString(): String = "RegenerateRequest(${rows.size} rows)"
}

/** The small chooser of the intentions (plan amendment R29, X97): generator and length for one value. */
class GeneratorRequest(
    /** The key of the value (`vault_db_password`). */
    val keyName: String,
    /** The generator offered first: the root's rule, else the current value's shape, else 32 alphanumeric. */
    val preset: SecretGenerator,
    /** Why [preset] was chosen, as shown ("same as now: 32 hex"). */
    @Nls val presetText: String,
    /** The current value is shorter than 16 characters. */
    val short: Boolean,
    /** Why the current value does not look like a password ("a PEM block"), or null. */
    @Nls val notPassword: String?,
) {
    override fun toString(): String = "GeneratorRequest($keyName, $preset)"
}

/** The texts of generators, sources, marks and reasons (bundle texts, never a value). */
object RegenerateTexts {
    /** "32 alphanumeric", "32 hex", "base64 of 32 bytes" … */
    @Nls
    fun generator(generator: SecretGenerator): String = when (generator.kind) {
        GeneratorKind.ALPHANUMERIC -> message("regenerate.generator.alphanumeric", generator.length)
        GeneratorKind.HEX -> message(if (generator.upperHex) "regenerate.generator.hex.upper" else "regenerate.generator.hex", generator.length)
        GeneratorKind.BASE64 -> message("regenerate.generator.base64", generator.length)
        GeneratorKind.BASE64_URL -> message(if (generator.padded) "regenerate.generator.base64.url.padded" else "regenerate.generator.base64.url", generator.length)
        GeneratorKind.ALPHANUMERIC_SYMBOLS -> message("regenerate.generator.symbols", generator.length)
    }

    /** The name of a generator kind (the settings table and the choosers). */
    @Nls
    fun kind(kind: GeneratorKind): String = message("regenerate.kind.${kind.name.lowercase()}")

    /** How [row]'s value will be made: "rule `*_cookie_secret`: 32 hex", "same as now: 32 hex", "32 alphanumeric". */
    @Nls
    fun source(row: RegenerateRow): String = source(row.source, row.generator, row.rule)

    @Nls
    fun source(source: GeneratorSource, generator: SecretGenerator, rule: GenerationRule?): String = when (source) {
        GeneratorSource.RULE -> message("regenerate.source.rule", rule?.pattern ?: "*", generator(generator))
        GeneratorSource.SAME_AS_NOW -> message("regenerate.source.same", generator(generator))
        GeneratorSource.DEFAULT, GeneratorSource.CHOSEN -> generator(generator)
    }

    /** Why a value is no password ("several lines", "a PEM block" …). */
    @Nls
    fun reason(reason: NotPasswordReason): String = message("regenerate.reason.${reason.name.lowercase()}")
}
