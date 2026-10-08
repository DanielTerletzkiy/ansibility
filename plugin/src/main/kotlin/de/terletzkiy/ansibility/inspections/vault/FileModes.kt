package de.terletzkiy.ansibility.inspections.vault

import de.terletzkiy.ansibility.index.JinjaBearing
import de.terletzkiy.ansibility.semantics.yaml.Resolved
import de.terletzkiy.ansibility.semantics.yaml.YEmpty
import de.terletzkiy.ansibility.semantics.yaml.YScalar
import de.terletzkiy.ansibility.semantics.yaml.YValue

/** What a file module's `mode` gives users outside the owner and the group ("others"). */
enum class ModeVerdict {
    /** Others can read the file for sure: `0644`, `644`, `"0o644"`, `o+r`, `a=r`, `u=rw,g=r,o=r`. */
    OTHERS_CAN_READ,

    /**
     * Others cannot read the file, or nothing can be told: group-only modes (`0640`, `u=rw,g=r,o=`), modes that give
     * others write or execute only (`0711`, `0602`, `o+x`), a templated mode, `preserve`, relative symbolic modes that do
     * not touch others (`u+rw`, `go-rwx`), and values ansible-core rejects (`ua+r`).
     */
    NOT_BROAD,

    /** No mode (absent, empty or `null`): a file the module creates gets the default, usually `0644`. */
    MISSING,
}

/**
 * Reads a `mode` value the way ansible-core's `AnsibleModule.set_mode_if_different` does (2.18.8 and 2.21.4): an int
 * (a YAML 1.1 int, so unquoted `0644` is octal 420 and unquoted `644` decimal 644, `0o1204`) is the permission bits;
 * a string is `int(mode, 8)` (`"644"`, `"0644"`, `"0o644"`) or else a symbolic mode (`_symbolic_mode_to_octal`:
 * comma-separated clauses of users, `a`, none or `[ugo]+`, followed by `[-+=]` and `[rwxXstugo]*` groups). Only the
 * read bit of others matters: writing or running a file shows nobody its content. PSI-free and side-effect free.
 */
object FileModes {
    private const val OTHERS_BITS = 7
    private const val READ = 4
    private const val WRITE = 2
    private const val EXECUTE = 1

    /** `preserve` (copy, template): the source's mode, which the project cannot see. */
    private const val PRESERVE = "preserve"

    /** `int(text, 8)` after Python's strip: sign, optional `0o` prefix, digits with single underscores between them. */
    private val OCTAL = Regex("""([+-]?)(?:0[oO]_?)?([0-7](?:_?[0-7])*)""")

    /** The users of a clause: `a` (all) or none alone, else `[ugo]+`; ansible-core rejects `ua` or `aa`. */
    private const val ALL = "a"
    private val USERS = Regex("[ugo]+")
    private val PERMS = Regex("[rwxXstugo]*")

    /** The verdict for [value], the `mode` option as loaded (null when the task sets no mode). */
    fun verdict(value: YValue?): ModeVerdict = when (value) {
        null, is YEmpty -> ModeVerdict.MISSING
        is YScalar -> scalarVerdict(value)
        // A vaulted, list or mapping mode: ansible-core decrypts or rejects it; nothing to tell.
        else -> ModeVerdict.NOT_BROAD
    }

    private fun scalarVerdict(scalar: YScalar): ModeVerdict = when (val resolved = scalar.resolved) {
        Resolved.Null -> ModeVerdict.MISSING
        // Only the low bits matter, so the truncating toInt is exact for them.
        is Resolved.Int ->
            if (resolved.value.signum() >= 0 && othersCanRead(resolved.value.toInt())) ModeVerdict.OTHERS_CAN_READ else ModeVerdict.NOT_BROAD
        is Resolved.Str -> stringVerdict(resolved.value)
        // Floats, booleans and timestamps fail the `int(mode, 8)` and the symbolic parse alike.
        else -> ModeVerdict.NOT_BROAD
    }

    /** The verdict for a string mode: templated and `preserve` modes say nothing, else octal, else symbolic. */
    fun stringVerdict(text: String): ModeVerdict {
        if (JinjaBearing.hasTemplateMarkers(text) || text.trim() == PRESERVE) return ModeVerdict.NOT_BROAD
        val others = octalOthers(text) ?: symbolicOthers(text) ?: return ModeVerdict.NOT_BROAD
        return if (othersCanRead(others)) ModeVerdict.OTHERS_CAN_READ else ModeVerdict.NOT_BROAD
    }

    /** Others' bits of `int(text, 8)`, or null when Python's `int` rejects the text or it is negative. */
    internal fun octalOthers(text: String): Int? {
        val match = OCTAL.matchEntire(text.trim()) ?: return null
        if (match.groupValues[1] == "-") return null
        val digits = match.groupValues[2].replace("_", "")
        // Only the last digit holds the bits of others; the leading ones may be arbitrarily many.
        return othersBitsOf(digits.last() - '0')
    }

    /**
     * The permission bits for others that the symbolic [mode] sets for sure, or null when it is no valid symbolic mode.
     * The file's current bits are unknown, so a clause that does not name others (`u+rw`) leaves them unknown and
     * counts as nothing; `-` removes bits, `=` replaces them. Without a user class (`+r`, `=rw`) ansible-core applies the
     * umask, assumed to be the usual `022`: read and execute pass, write does not. Copying another class (`o=u`) counts
     * as read, which the owner of a written file has. `X` (execute when some execute bit is set) and `s`/`t` count as
     * nothing.
     */
    internal fun symbolicOthers(mode: String): Int? {
        var others = 0
        for (clause in mode.split(',')) {
            // Python's re.split on [+=-]: the users first, then one permission group per operator.
            val parts = clause.split('+', '=', '-')
            val operators = clause.filter { it == '+' || it == '=' || it == '-' }
            val users = parts.first()
            if (users.isNotEmpty() && users != ALL && !USERS.matches(users)) return null
            val withUmask = users.isEmpty()
            val touchesOthers = withUmask || users == ALL || 'o' in users
            for ((index, perms) in parts.drop(1).withIndex()) {
                if (!PERMS.matches(perms)) return null
                if (!touchesOthers) continue
                var literal = 0
                if ('r' in perms) literal = literal or READ
                if ('w' in perms && !withUmask) literal = literal or WRITE
                if ('x' in perms) literal = literal or EXECUTE
                val copied = if ('u' in perms || 'g' in perms) READ else 0
                others = when (operators[index]) {
                    '=' -> literal or copied or (if ('o' in perms) others else 0)
                    '+' -> others or literal or copied
                    else -> others and literal.inv()
                }
            }
        }
        return others
    }

    private fun othersBitsOf(mode: Int): Int = mode and OTHERS_BITS

    /** Whether [mode] (a whole mode, or the bits of others alone) has the read bit of others. */
    private fun othersCanRead(mode: Int): Boolean = (mode and READ) != 0
}
