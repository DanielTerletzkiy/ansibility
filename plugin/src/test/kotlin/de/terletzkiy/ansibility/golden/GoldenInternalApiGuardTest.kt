package de.terletzkiy.ansibility.golden

import junit.framework.TestCase
import java.io.File

/**
 * The golden sources (plan amendment R24) use public API only: the diff, merge, blame and Local History internals the
 * R24 investigation found in 262 are `@ApiStatus.Internal` (the verifier fails on them), and some public members must
 * not be used either (a user label, a label revert, the VCS-typed producer factory). A source scan, like
 * `ScopeInternalApiGuardTest`.
 */
class GoldenInternalApiGuardTest : TestCase() {

    fun testTheGoldenSourcesUseNoInternalOrBannedApi() {
        val sources = sourceDir().walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue("the golden sources are found: ${sourceDir().absolutePath}", sources.size >= 8)
        val violations = sources.flatMap { file ->
            val vcsImpl = "/vcs/impl/" in file.invariantSeparatorsPath
            file.readLines().withIndex().mapNotNull { (index, line) ->
                val trimmed = line.trimStart()
                if (trimmed.startsWith("*") || trimmed.startsWith("/*") || trimmed.startsWith("//")) return@mapNotNull null
                val code = line.substringBefore("//")
                val hit = BANNED.firstOrNull { (pattern, _) -> pattern.containsMatchIn(code) }
                    ?: VCS_ONLY.takeIf { !vcsImpl }?.firstOrNull { (pattern, _) -> pattern.containsMatchIn(code) }
                hit?.let { "${file.name}:${index + 1}: ${it.second}" }
            }
        }
        assertEquals("internal or banned API in the golden sources", emptyList<String>(), violations)
    }

    fun testOnlyTheVcsImplPackageImportsVcsClasses() {
        val vcs = Regex("""^import com\.intellij\.(openapi\.vcs\.|vcs\.|vcsUtil\.)""")
        val outside = sourceDir().walkTopDown().filter { it.isFile && it.extension == "kt" && "/vcs/impl/" !in it.invariantSeparatorsPath }
            .filter { file -> file.readLines().any(vcs::containsMatchIn) }.map { it.name }.toList()
        assertEquals(emptyList<String>(), outside)
    }

    /**
     * The trailing-space stripper's flag is one boolean per file: golden code turns it off only through `StripperHold`
     * (`sync/RoleWriter.kt`), which counts nested holds, so one write never turns it back on under an open Align window.
     */
    fun testTheStripperIsToggledOnlyThroughStripperHold() {
        val users = sourceDir().walkTopDown().filter { it.isFile && it.extension == "kt" }
            .filter { file -> file.readLines().any { "TrailingSpacesStripper.setEnabled" in it.substringBefore("//") && !it.trimStart().startsWith("*") } }
            .map { it.invariantSeparatorsPath.substringAfter("/golden/") }.toList()
        assertEquals(listOf("sync/RoleWriter.kt"), users)
    }

    private fun sourceDir(): File {
        val relative = "src/main/kotlin/de/terletzkiy/ansibility/golden"
        return listOf(File(relative), File("plugin/$relative")).firstOrNull { it.isDirectory } ?: File(relative)
    }

    private companion object {
        val BANNED: List<Pair<Regex, String>> = listOf(
            Regex("""\bDiffRequestFactoryImpl\b""") to "DiffRequestFactoryImpl (internal)",
            Regex("""\bTextMergeRequestImpl\b""") to "TextMergeRequestImpl (internal)",
            Regex("""\bMergeUtil\b""") to "MergeUtil (internal)",
            Regex("""\bMergeWindow\b""") to "MergeWindow (internal)",
            Regex("""\bAnnotateDiffViewerAction\b""") to "AnnotateDiffViewerAction (internal)",
            Regex("""\bAnnotateLocalFileAction\b""") to "AnnotateLocalFileAction (internal)",
            Regex("""\bputUserLabel\b""") to "LocalHistory.putUserLabel (internal)",
            Regex("""\bputEventLabel\b""") to "LocalHistory.putEventLabel (use putSystemLabel)",
            Regex("""\bstartAction\([^,()]*,""") to "LocalHistory.startAction(name, ActivityId) (use startAction(name))",
            Regex("""\.revert\(""") to "Label.revert (obsolete; Undo and revertToLabel are the revert paths)",
            Regex("""\bCharsetToolkit\b""") to "CharsetToolkit (internal)",
            Regex("""\bAsyncableFileSystem\b""") to "AsyncableFileSystem (internal; tests only)",
            Regex("""\bReadAction\.compute\b""") to "ReadAction.compute (deprecated)",
            Regex("""\bblockingContextToIndicator\b|\brunBlockingCancellable\(\s*true""") to "internal coroutine bridges",
            // Align (D185): the Conflicts dialog is reached through AbstractVcsHelper only; its internals are not API.
            Regex("""\bMultipleFileMergeDialog\b""") to "MultipleFileMergeDialog (vcs.impl; use AbstractVcsHelper.showMergeDialogWithResult)",
            Regex("""\bMergeConflictIterativeDataHolder\b|\bMergeConflictModel\b""") to "the iterative merge model (internal)",
            Regex("""\bMergeThreesideViewer\b|\bTextMergeViewer\b""") to "the merge viewer classes (internal)",
            Regex("""\bUiWithModelAccess\b""") to "Dispatchers.UiWithModelAccess (internal)",
            Regex("""\.getHandler\(""") to "DiffManagerEx.getHandler (internal)",
            Regex("""\bRefreshVFsSynchronously\b|\bVcsDirtyScopeManagerImpl\b""") to "VCS refresh internals",
            Regex("""\brunBlockingMaybeCancellable\(\s*(true|false)""") to "runBlockingMaybeCancellable(Boolean, …) (internal)",
            // X120/X121/X124: the banner, the takes and Push's R23 line.
            Regex("""\bNotification\.fire\b""") to "Notification.fire (internal; tests only)",
            Regex("""\b(readActionUndispatched|constrainedReadActionUndispatched)\b""") to "undispatched read actions (internal)",
            Regex("""\bgetOpenFilesWithRemotes\b|\bopenFilesWithRemotes\b""") to "FileEditorManager.getOpenFilesWithRemotes (experimental)",
            Regex("""updateNotifications\(\s*(this|provider)\b""") to "EditorNotifications.updateNotifications(provider) (deprecated; per file or all)",
            // Review fixes of the write paths (S3, S6, S9, U5).
            Regex("""TrailingSpacesStripper\.(isEnabled|strip|getOptions|clearLineModificationFlags)\b""") to "TrailingSpacesStripper internals (use StripperHold)",
            Regex("""\bUndoRedoListener\b|\bUndoManagerImpl\b|\bUndoClientState\b""") to "undo internals (CommandListener + UndoManager.isUndoInProgress are public)",
            Regex("""(?<!fileSystem)\.isCaseSensitive\b""") to "VirtualFile.isCaseSensitive (experimental; use fileSystem.isCaseSensitive)",
            Regex("""\bMergeUtils\b|\bDiffVcsDataKeys\b""") to "MergeUtils / DiffVcsDataKeys (vcs.impl; set MergeData's *_FILE_PATH and *_REVISION_NUMBER)",
        )

        /** VCS types are for `vcs/impl` only (VcsIsolationTest); Compare uses `SimpleDiffRequestProducer.create(String, …)`. */
        val VCS_ONLY: List<Pair<Regex, String>> = listOf(
            Regex("""\bVcsContextFactory\b|\bFilePath\b""") to "FilePath outside vcs/impl (SimpleDiffRequestProducer.create(String, …) only)",
        )
    }
}
