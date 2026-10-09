package de.terletzkiy.ansibility.golden.patch

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.testFramework.replaceService
import de.terletzkiy.ansibility.golden.remote.GitTestRepo
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.relativeTo

/**
 * A [PatchUi] that records instead of showing: [choice] answers the dialog (null: Cancel), [saveTo] the save dialog
 * (null: Cancel).
 */
class RecordingPatchUi : PatchUi {
    val models = CopyOnWriteArrayList<PatchModel>()
    val saveNames = CopyOnWriteArrayList<String>()
    val notices = CopyOnWriteArrayList<String>()
    val notifications = CopyOnWriteArrayList<String>()

    @Volatile
    var choice: (PatchModel) -> PatchChoice? = { PatchChoice(PatchOutput.CLIPBOARD) }

    @Volatile
    var saveTo: Path? = null

    override fun choose(project: Project, model: PatchModel): PatchChoice? {
        models += model
        return choice(model)
    }

    override fun saveTarget(project: Project, defaultFileName: String): Path? {
        saveNames += defaultFileName
        return saveTo
    }

    override fun inform(project: Project, message: String) {
        notices += message
    }

    override fun notify(project: Project, message: String) {
        notifications += message
    }

    companion object {
        /** Installs a new recording UI until [parent] is disposed. */
        fun install(parent: Disposable): RecordingPatchUi =
            RecordingPatchUi().also { ApplicationManager.getApplication().replaceService(PatchUi::class.java, it, parent) }
    }
}

/** Real git repositories for the patch tests: the golden tree in a temp directory, `git apply`, byte comparisons. */
object PatchGit {
    /** A git repository at [dir] holding a copy of [tree] (one commit), like a checkout of the golden repository. */
    fun repoOf(tree: Path, dir: Path): GitTestRepo {
        copyTree(tree, dir)
        val repo = GitTestRepo.init(dir)
        // Keep the bytes as they are: no line-ending conversion, whatever the system says.
        repo.git("config", "core.autocrlf", "false")
        repo.commit("golden")
        return repo
    }

    /** `git apply --check` of [patch] in [repo], then (unless [checkOnly]) `git apply`; fails the test on an error. */
    fun apply(repo: GitTestRepo, patch: ByteArray, checkOnly: Boolean = false) {
        val file = Files.createTempFile("ansibility-x126", ".patch")
        try {
            Files.write(file, patch)
            repo.git("apply", "--check", file.toString())
            if (checkOnly) return
            repo.git("apply", file.toString())
        } finally {
            Files.deleteIfExists(file)
        }
    }

    /** Every file below [dir] (relative path → bytes as a list, so maps compare by content), without `.git`. */
    fun files(dir: Path): Map<String, List<Byte>> = Files.walk(dir).use { paths ->
        paths.filter { it.isRegularFile() && ".git" !in it.relativeTo(dir).map(Path::toString) }
            .toList()
            .associate { it.relativeTo(dir).toString().replace('\\', '/') to Files.readAllBytes(it).toList() }
            .toSortedMap()
    }

    /** Whether [file]'s owner-executable bit is set. */
    fun executable(file: Path): Boolean = Files.isExecutable(file)

    fun copyTree(from: Path, to: Path) {
        Files.walk(from).use { paths ->
            paths.forEach { source ->
                val target = to.resolve(from.relativize(source).toString())
                if (source.isDirectory()) Files.createDirectories(target) else Files.copy(source, target, java.nio.file.StandardCopyOption.COPY_ATTRIBUTES)
            }
        }
    }
}
