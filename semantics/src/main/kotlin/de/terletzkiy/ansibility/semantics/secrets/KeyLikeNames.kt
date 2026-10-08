package de.terletzkiy.ansibility.semantics.secrets

/** A file name that says "secret" by convention (plan amendment R21, D161; the R7 rule of ANS-V108). */
enum class KeyLikeName {
    /** A `*.key` file below `files/ssl` (a TLS key Ansible copies to hosts). */
    SSL_KEY,

    /** A `*.key` file below `files/ssh` (an SSH key Ansible copies to hosts). */
    SSH_KEY,

    /** A `*.password` file below `files` (a password Ansible copies or reads with a lookup). */
    PASSWORD,
}

/**
 * The key-like names of ANS-V108 (plan amendment R21, D161): `files/ssl/` and `files/ssh/` `*.key` files at any depth
 * below those directories, and `*.password` files at any depth below a `files` directory. By convention such files
 * are vaulted; a file of that name that is neither a vault nor holds a key [PrivateKeySignatures] can read is a weaker
 * signal (WARNING), unless it holds only password hashes ([PasswordHashes.isHashOnly]: no plaintext secret). Pure:
 * the path decides, never the content.
 */
object KeyLikeNames {
    private const val FILES = "files"
    private const val KEY_SUFFIX = ".key"
    private const val PASSWORD_SUFFIX = ".password"

    /**
     * The key-like name of a file named [name] in the directories [dirs] (outermost first, e.g. the path below the
     * project or content root), or null.
     */
    fun of(dirs: List<String>, name: String): KeyLikeName? {
        val files = dirs.indices.filter { dirs[it] == FILES }
        if (files.isEmpty()) return null
        if (name.length > KEY_SUFFIX.length && name.endsWith(KEY_SUFFIX)) {
            for (at in files) {
                when (dirs.getOrNull(at + 1)) {
                    "ssl" -> return KeyLikeName.SSL_KEY
                    "ssh" -> return KeyLikeName.SSH_KEY
                }
            }
            return null
        }
        if (name.length > PASSWORD_SUFFIX.length && name.endsWith(PASSWORD_SUFFIX)) return KeyLikeName.PASSWORD
        return null
    }

    /** The key-like name of a `/`-separated relative [path], or null. */
    fun of(path: String): KeyLikeName? {
        val segments = path.split('/').filter { it.isNotEmpty() }
        val name = segments.lastOrNull() ?: return null
        return of(segments.dropLast(1), name)
    }
}
