package de.terletzkiy.ansibility.semantics.precedence

import de.terletzkiy.ansibility.semantics.inventory.Py
import de.terletzkiy.ansibility.semantics.yaml.YEntry
import de.terletzkiy.ansibility.semantics.yaml.YMap
import de.terletzkiy.ansibility.semantics.yaml.YValue

/** `DEFAULT_HASH_BEHAVIOUR` (`hash_behaviour` in `ansible.cfg`, `ANSIBLE_HASH_BEHAVIOUR`). */
enum class HashBehaviour {
    /** The default: a later definition of a variable replaces the earlier one entirely. */
    REPLACE,

    /** Dictionaries are merged recursively (`merge_hash`); lists and scalars are still replaced. */
    MERGE,
    ;

    companion object {
        /** Parses a config value; null for anything ansible-core does not accept. */
        fun parse(text: String): HashBehaviour? = when (text.trim().lowercase()) {
            "replace" -> REPLACE
            "merge" -> MERGE
            else -> null
        }
    }
}

/** Port of `ansible.utils.vars.combine_vars` and `merge_hash` over [YValue] trees. */
object CombineVars {
    /** `combine_vars(a, b)`: `a | b` under [HashBehaviour.REPLACE], `merge_hash(a, b)` under [HashBehaviour.MERGE]. */
    fun combine(a: Map<String, YValue>, b: Map<String, YValue>, behaviour: HashBehaviour): Map<String, YValue> {
        val out = LinkedHashMap(a)
        for ((key, value) in b) {
            val previous = out[key]
            out[key] = if (behaviour == HashBehaviour.MERGE) mergeValue(previous, value) else value
        }
        return out
    }

    /**
     * The value of one key after `merge_hash`: two mappings merge recursively, anything else is replaced by [y].
     * [x] null means the key was absent.
     */
    fun mergeValue(x: YValue?, y: YValue): YValue =
        if (x is YMap && y is YMap) mergeHash(x, y) else y

    /**
     * `merge_hash(x, y)` with the defaults `combine_vars` uses (recursive, `list_merge='replace'`). Keys keep the
     * position they have in [x]; new keys from [y] are appended. The result has no range of its own.
     */
    fun mergeHash(x: YMap, y: YMap): YMap {
        val xd = Py.dict(x)
        val yd = Py.dict(y)
        if (xd.isEmpty()) return YMap(yd.values.toList(), y.range)
        if (yd.isEmpty()) return YMap(xd.values.toList(), x.range)
        val out = LinkedHashMap(xd)
        for ((key, entry) in yd) {
            val existing = out[key]
            out[key] = if (existing == null) entry else YEntry(entry.key, mergeValue(existing.value, entry.value))
        }
        return YMap(out.values.toList(), null)
    }
}
