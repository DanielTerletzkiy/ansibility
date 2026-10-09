package de.terletzkiy.ansibility.golden.sync

/**
 * The new content of a file written by [RoleWriter].
 *
 * Content may be secret (a key file the user opted to include): it is never logged and [toString] never prints it.
 */
sealed class OpContent {
    /**
     * Text, written through the file's document (undoable). The target keeps its own line separators, charset and
     * byte order mark; line separators in [text] are normalised. Use it when the text matters, not the bytes (a merge
     * result, X121 "Take golden's version" in place).
     */
    class Text(val text: String) : OpContent() {
        override fun toString(): String = "Text(${text.length} chars)"
    }

    /**
     * Exact bytes: the file ends up byte-identical to [bytes] (Push's mirror). A text file is still written through
     * its document when the bytes decode losslessly in its charset with one kind of line separator (undoable, and the
     * byte order mark and separator change with it); otherwise, and for binary files, through the VFS, which Undo
     * cannot restore (listed in [WriteResult.notUndoable]).
     */
    class Bytes(val bytes: ByteArray) : OpContent() {
        override fun toString(): String = "Bytes(${bytes.size} bytes)"
    }
}

/**
 * One change of a role directory (plan amendment R24), relative to the target role directory passed to [RoleWriter].
 *
 * [expected] is the target file's [FileStamp] when the plan was made ([PlanEntry.targetStamp]); when the file looks
 * different at write time the whole write aborts ([WriteResult.Status.STALE]). Null skips that check (only for a
 * caller that has just looked at the file itself).
 */
sealed class FileOp {
    /** The path relative to the target role directory, with `/` separators ("tasks/main.yml"). */
    abstract val relPath: String

    abstract val expected: FileStamp?

    /**
     * Replaces the content of an existing file. [executable]: true or false sets the owner/group/others executable
     * bits (where the matching read bit is set) after the write, null keeps the file's mode.
     */
    class Write(
        override val relPath: String,
        val content: OpContent,
        val executable: Boolean? = null,
        override val expected: FileStamp? = null,
    ) : FileOp() {
        override fun toString(): String = "Write($relPath, $content)"
    }

    /** Creates a file that does not exist yet, with its missing parent directories; [executable] as in [Write]. */
    class Create(
        override val relPath: String,
        val content: OpContent,
        val executable: Boolean? = null,
        override val expected: FileStamp? = FileStamp.ABSENT,
    ) : FileOp() {
        override fun toString(): String = "Create($relPath, $content)"
    }

    /** Deletes a file (directories left empty stay). */
    class Delete(
        override val relPath: String,
        override val expected: FileStamp? = null,
    ) : FileOp() {
        override fun toString(): String = "Delete($relPath)"
    }
}
