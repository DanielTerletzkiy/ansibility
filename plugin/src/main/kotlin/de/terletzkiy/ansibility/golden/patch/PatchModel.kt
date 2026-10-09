package de.terletzkiy.ansibility.golden.patch

import org.jetbrains.annotations.Nls

/** How a file of a patch for golden changes golden (plan amendment R25, X126). */
enum class PatchChange {
    /** In both copies, with different content: golden's file gets this copy's content. */
    CHANGED,

    /** Only in this copy: the patch creates it in golden. */
    ADDED,

    /** Only in golden: the patch deletes it there. */
    DELETED,
}

/** One differing file offered for a patch: [relPath] inside the role, facts only (no content). */
class PatchRow internal constructor(
    val relPath: String,
    val change: PatchChange,
    /** Binary on either side: a "Binary files … differ" note, never content. */
    val binary: Boolean,
    /** Key material, a secret by name or a whole-file vault: left out unless included explicitly; never decrypted. */
    val sensitive: Boolean,
) {
    /** Whether the patch holds this file's diff with key and vault files [includeSensitive] or not. */
    fun inPatch(includeSensitive: Boolean): Boolean = !binary && (!sensitive || includeSensitive)

    override fun toString(): String = "PatchRow($relPath, $change${if (binary) ", binary" else ""}${if (sensitive) ", sensitive" else ""})"
}

/**
 * What "Copy as Patch for Golden…" offers (plan amendment R25, X126): the differing files of a role copy (all of them
 * for a copy row, or those of the selected file or folder), how golden is named, where the patch applies and the
 * default file name ("web-falcon-to-golden.patch"). The dialog shows it; the user picks the output and whether key
 * and vault files go in.
 */
class PatchModel internal constructor(
    /** The role name ("web"). */
    val roleName: String,
    /** The name of the root of the copy the patch comes from ("falcon"). */
    val copyName: String,
    /** The name of the golden root ("golden"). */
    val goldenName: String,
    /** Where the patch applies, and the golden role directory's path there. */
    val base: GoldenPatchBase,
    /** The differing files, sorted by path. */
    val rows: List<PatchRow>,
    /** The default name of a saved patch. */
    val defaultFileName: String,
) {
    /** The key and vault files among [rows]. */
    val sensitiveCount: Int get() = rows.count { it.sensitive }

    /** The files whose diff the patch holds, with key and vault files [includeSensitive] or not. */
    fun diffCount(includeSensitive: Boolean): Int = rows.count { it.inPatch(includeSensitive) }

    companion object {
        /**
         * "web-falcon-to-golden.patch": the role, the copy's root and golden's root, each reduced to letters, digits,
         * dots, underscores and dashes ("pelican › danger_zone/database" becomes "pelican-danger_zone-database").
         */
        fun fileName(roleName: String, copyName: String, goldenName: String): String =
            "${safe(roleName)}-${safe(copyName)}-to-${safe(goldenName)}.patch"

        private fun safe(name: String): String =
            name.replace(Regex("[^A-Za-z0-9._-]+"), "-").trim('-', '.').ifEmpty { "role" }
    }
}

/** Where the patch goes. */
enum class PatchOutput {
    /** Copy to Clipboard. */
    CLIPBOARD,

    /** Save as .patch… */
    FILE,
}

/** The user's choice in the patch dialog: the output, and whether key and vault files go in (off by default). */
data class PatchChoice(val output: PatchOutput, val includeSensitive: Boolean = false)

/**
 * A patch for golden (plan amendment R25, X126): [bytes] are the patch as `git apply` reads it (a short note first,
 * then one `diff --git` per file), [text] the same as text for the clipboard. Lists name the role paths it holds and
 * leaves out.
 */
class GoldenPatch internal constructor(
    val bytes: ByteArray,
    /** The role paths whose diff the patch holds, sorted. */
    val files: List<String>,
    /** Binary files: noted ("Binary files … differ"), no content. */
    val binaries: List<String>,
    /** Key and vault files left out (not included). */
    val leftOut: List<String>,
    /** Files that could not be read (nothing of them in the patch). */
    val unreadable: List<String>,
) {
    /** The patch as text (UTF-8). */
    val text: String get() = String(bytes, Charsets.UTF_8)

    val fileCount: Int get() = files.size

    override fun toString(): String = "GoldenPatch(${files.size} files, binaries=$binaries, leftOut=$leftOut, unreadable=$unreadable)"
}

/** What "Copy as Patch for Golden…" did (plan amendment R25, X126); for the caller and tests. */
class PatchOutcome internal constructor(
    val status: Status,
    /** The patch when one was made. */
    val patch: GoldenPatch?,
    /** What the user was told (the notification or a notice), or null. */
    @get:Nls val message: String?,
) {
    enum class Status {
        /** Copied to the clipboard. */
        COPIED,

        /** Saved to a file. */
        SAVED,

        /** The dialog or the save dialog was cancelled. */
        CANCELLED,

        /** Nothing differs from golden (in the selection). */
        SAME,

        /** Only files a patch cannot hold differ (binary files, key and vault files left out). */
        NOTHING_TO_PATCH,

        /** No golden copy, the golden copy itself, nothing selected. */
        NOT_APPLICABLE,

        /** The file could not be written. */
        FAILED,
    }

    override fun toString(): String = "PatchOutcome($status, $patch, $message)"
}
