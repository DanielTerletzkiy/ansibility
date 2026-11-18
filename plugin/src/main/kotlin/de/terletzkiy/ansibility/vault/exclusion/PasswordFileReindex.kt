package de.terletzkiy.ansibility.vault.exclusion

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.vfs.VFileProperty
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.NewVirtualFile
import com.intellij.util.indexing.FileBasedIndex
import de.terletzkiy.ansibility.vault.VaultLog
import org.jetbrains.annotations.TestOnly

/**
 * Index data from before (plan amendment R30, D226): a password file the IDE indexed before it was excluded keeps its
 * word and trigram index data until it is indexed again, so once per project and list version
 * ([IndexExclusionSettings.fingerprint]) Ansibility asks the platform to re-index the excluded password files of the
 * project (`FileBasedIndex.requestReindex`). For a file outside the project content that drops its data and adds none
 * (`PasswordFileExclusionTest` proves it on the word and trigram indexes). A changed list applies when the project
 * opens again (D225), so this runs at the next start after a change, too. Local History keeps earlier revisions of a
 * file it tracked before; only File › Invalidate Caches with "Clear Local History" removes them (the settings page says
 * so).
 *
 * Reads no file: the excluded files are found by name among the files the VFS knows below the content roots.
 */
object PasswordFileReindex {
    /** How many VFS entries one walk looks at, at most (a huge project is walked up to here; the rest waits for later). */
    @Volatile
    var maxVisits: Int = MAX_VISITS
        @TestOnly set

    /** The key a project's last re-index is remembered by: its directory, or its location hash. */
    fun projectKey(project: Project): String = project.basePath ?: project.locationHash

    /**
     * Re-indexes the excluded password files of [project] unless it was done for the current list already; returns how
     * many files were handed to the platform, or null when nothing was due. Waits for smart mode; background.
     */
    suspend fun runIfDue(project: Project): Int? {
        val settings = IndexExclusionSettings.getInstance()
        val key = projectKey(project)
        val fingerprint = settings.fingerprint()
        if (settings.reindexedFingerprint(key) == fingerprint) return null
        val patterns = settings.patterns()
        val files = smartReadAction(project) { excludedFiles(project, patterns) }
        requestReindex(files)
        settings.markReindexed(key, fingerprint)
        VaultLog.event(VaultLog.Operation.STATUS, VaultLog.Event.PASSWORD_FILES_REINDEXED, key = files.size.toString())
        return files.size
    }

    /** Asks the platform to index [files] again (for an excluded file: its old data goes, nothing new comes). */
    fun requestReindex(files: Collection<VirtualFile>) {
        val index = FileBasedIndex.getInstance()
        for (file in files) if (file.isValid) index.requestReindex(file)
    }

    /**
     * The files below [project]'s content roots that are excluded because their name, or the name of a folder above
     * them, matches one of [patterns]: from the files the VFS knows (`NewVirtualFile.iterInDbChildren`, as the
     * platform's indexing walks), skipping ignored files, symbolic links to folders and folders excluded otherwise.
     * Read action.
     */
    fun excludedFiles(project: Project, patterns: List<String>): List<VirtualFile> {
        val fileIndex = ProjectFileIndex.getInstance(project)
        val types = FileTypeManager.getInstance()
        val found = LinkedHashSet<VirtualFile>()
        val seen = HashSet<VirtualFile>()
        val stack = ArrayDeque<Pair<VirtualFile, Boolean>>()
        ProjectRootManager.getInstance(project).contentRoots.forEach { stack.addLast(it to false) }
        var visits = 0
        while (stack.isNotEmpty() && visits < maxVisits) {
            ProgressManager.checkCanceled()
            val (dir, keptOut) = stack.removeLast()
            if (!dir.isValid || !seen.add(dir)) continue
            for (child in childrenOf(dir)) {
                if (++visits >= maxVisits) break
                if (types.isFileIgnored(child)) continue
                val matches = keptOut || PasswordFileNames.matchesAny(patterns, child.name)
                if (child.isDirectory) {
                    if (child.`is`(VFileProperty.SYMLINK)) continue
                    // A folder excluded for another reason (build output, a marked folder) holds nothing of ours.
                    if (matches || !fileIndex.isExcluded(child)) stack.addLast(child to matches)
                } else if (matches && fileIndex.isExcluded(child)) {
                    found += child
                }
            }
        }
        return found.toList()
    }

    private fun childrenOf(dir: VirtualFile): Iterable<VirtualFile> =
        if (dir is NewVirtualFile) dir.iterInDbChildren() else dir.children.orEmpty().asList()

    private const val MAX_VISITS = 1_000_000
}

/** Re-indexes the excluded password files when a project opens, once per list version ([PasswordFileReindex]). */
class PasswordFileReindexActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        // Tests run it themselves: a light project outlives a test class.
        if (ApplicationManager.getApplication().isUnitTestMode) return
        PasswordFileReindex.runIfDue(project)
    }
}
