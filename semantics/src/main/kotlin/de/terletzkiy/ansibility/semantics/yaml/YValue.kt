package de.terletzkiy.ansibility.semantics.yaml

/**
 * A PSI-free YAML value tree, as Ansible's loader (PyYAML, YAML 1.1) would see it.
 *
 * Built from IntelliJ YAML PSI by `PsiYValueAdapter` in :plugin, or from text in tests.
 * Every node carries its source range so diagnostics can point at it.
 */
sealed interface YValue {
    val range: SourceRange?
}

/** Offsets into the containing file; `end` is exclusive. */
data class SourceRange(val start: Int, val end: Int)

enum class ScalarStyle { PLAIN, SINGLE_QUOTED, DOUBLE_QUOTED, LITERAL, FOLDED }

/**
 * A scalar. [text] is the value after YAML unescaping/folding (what Python would receive before implicit typing);
 * [sourceText] is the literal spelling in the file (needed for hazards such as `3.10` → 3.1).
 * [tag] is an explicit tag such as `!!str`, `!vault`, `!unsafe` (null when absent).
 */
data class YScalar(
    val text: String,
    val style: ScalarStyle,
    val tag: String? = null,
    val sourceText: String = text,
    override val range: SourceRange? = null,
) : YValue {
    /** The implicitly resolved Python value (YAML 1.1 rules). Quoted, block and explicitly tagged scalars are strings. */
    val resolved: Resolved by lazy { Yaml11Resolver.resolve(this) }
}

data class YSeq(val items: List<YValue>, override val range: SourceRange? = null) : YValue

/** A mapping. Keys are scalars (complex keys do not occur in Ansible content); merge keys (`<<`) are already applied. */
data class YMap(val entries: List<YEntry>, override val range: SourceRange? = null) : YValue {
    operator fun get(key: String): YValue? = entries.lastOrNull { it.key.text == key }?.value
    val keys: List<String> get() = entries.map { it.key.text }
}

data class YEntry(val key: YScalar, val value: YValue)

/** `!vault |` encrypted scalar: always decrypts to a string, never shown. */
data class YVault(override val range: SourceRange? = null) : YValue

/** An empty value (`key:` with nothing after it), which YAML loads as None. */
data class YEmpty(override val range: SourceRange? = null) : YValue

/**
 * The Python value a scalar resolves to under YAML 1.1 (PyYAML resolver as used by Ansible).
 */
sealed interface Resolved {
    data object Null : Resolved
    data class Bool(val value: Boolean) : Resolved
    data class Int(val value: java.math.BigInteger) : Resolved
    data class Float(val value: Double) : Resolved
    data class Str(val value: String) : Resolved
    /** `datetime.date` / `datetime.datetime` from the timestamp resolver. */
    data class Timestamp(val text: String) : Resolved
    /** A bare `=` scalar: PyYAML has no constructor for `tag:yaml.org,2002:value`, so the file fails to load. */
    data object Unloadable : Resolved
}
