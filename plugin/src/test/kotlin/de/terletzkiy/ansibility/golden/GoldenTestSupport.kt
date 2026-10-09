package de.terletzkiy.ansibility.golden

import com.intellij.diff.chains.DiffRequestChain
import com.intellij.diff.chains.DiffRequestProducer
import com.intellij.diff.requests.DiffRequest
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.Anchor
import com.intellij.openapi.actionSystem.Constraints
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.AsyncableFileSystem
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.registerOrReplaceServiceInstance
import com.intellij.testFramework.replaceService
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.SourceLocation
import de.terletzkiy.ansibility.api.VaultDecryptResult
import de.terletzkiy.ansibility.api.VaultEncryptResult
import de.terletzkiy.ansibility.api.VaultOperations
import de.terletzkiy.ansibility.api.VaultPurpose
import de.terletzkiy.ansibility.api.VaultUnlockResult
import de.terletzkiy.ansibility.context.AnsibleWorkspaceImpl
import de.terletzkiy.ansibility.golden.compare.CompareChoice
import de.terletzkiy.ansibility.golden.compare.RoleDiffUi
import de.terletzkiy.ansibility.model.drift.DriftFixture
import de.terletzkiy.ansibility.model.role.ModelFixture
import de.terletzkiy.ansibility.model.role.RoleCatalog
import de.terletzkiy.ansibility.model.role.RoleCopy
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.isDirectory

/**
 * Shared helpers of the golden tests (plan amendment R24) on the synthetic drift tree `testData/toolwindow/drift`
 * (golden plus seven repo copies of `web`, one per tier; see its README and [DriftFixture]).
 */
object GoldenTestSupport {
    /**
     * Makes the first role library (`golden`) the golden root ("the first role library (automatic)"; the setting's
     * default is "None", D177) until [parent] is disposed.
     */
    fun useFirstLibraryAsGolden(project: Project, parent: Disposable) = DriftFixture.useGolden(project, parent)

    /** The catalog copy whose role directory is [dir]. */
    fun copy(project: Project, dir: VirtualFile): RoleCopy = RoleCatalog.getInstance(project).copyOf(dir) ?: error("no role copy at ${dir.path}")

    /** Runs [action] on a pooled thread while the EDT dispatches events (never `runBlocking` on the EDT). */
    fun <T> await(action: suspend () -> T): T = DriftFixture.await(action)

    /** Runs [action] on a pooled thread (blocking producers must not run on the EDT). */
    fun <T> pooled(action: () -> T): T {
        val future = ApplicationManager.getApplication().executeOnPooledThread<T> { action() }
        return PlatformTestUtil.waitForFuture(future, TimeUnit.MINUTES.toMillis(2))
    }

    /** [DiffRequestProducer.process] on a pooled thread, as the diff viewer calls it. */
    fun process(producer: DiffRequestProducer): DiffRequest = pooled { producer.process(UserDataHolderBase(), EmptyProgressIndicator()) }

    /** Waits (dispatching events) until [condition] holds. */
    fun waitFor(what: String, condition: () -> Boolean) = PlatformTestUtil.waitWithEventsDispatching(what, condition, 30)

    /** Flushes the asynchronous writes of saved documents (262) before a test reads files with java.nio. Tests only. */
    fun fsync() {
        (LocalFileSystem.getInstance() as? AsyncableFileSystem)?.fsync()
    }

    /** Installs [ui] as the [RoleDiffUi] until [parent] is disposed. */
    fun install(ui: RoleDiffUi, parent: Disposable) {
        ApplicationManager.getApplication().replaceService(RoleDiffUi::class.java, ui, parent)
    }

    /** The "Ansibility Golden" submenu of the editor and Project-view popups (`ansibility-golden.xml`). */
    const val SUBMENU: String = "Ansibility.Golden.Menu"

    /** The popups the golden actions are registered in (`ansibility-golden.xml`): the tool window's, and the submenu. */
    val POPUPS: List<String> = listOf("Ansibility.ToolWindow.Popup", SUBMENU)

    /**
     * Unregisters the action [id] until [parent] is disposed, then registers it again and puts it back into the popups
     * it was in, right after the action it followed: `unregisterAction` takes an action out of its groups, and
     * `registerAction` alone does not put it back, so later tests of the popup order would see it missing.
     */
    fun unregisterUntil(id: String, parent: Disposable) {
        val actions = ActionManager.getInstance()
        val action = actions.getAction(id) ?: return
        val places = POPUPS.mapNotNull { groupId ->
            val group = actions.getAction(groupId) as? DefaultActionGroup ?: return@mapNotNull null
            val children = group.getChildActionsOrStubs().map { actions.getId(it) }
            val at = children.indexOf(id)
            if (at < 0) null else group to children.getOrNull(at - 1)
        }
        actions.unregisterAction(id)
        Disposer.register(parent) {
            if (actions.getAction(id) != null) actions.unregisterAction(id)
            actions.registerAction(id, action)
            for ((group, before) in places) {
                group.add(action, if (before != null) Constraints(Anchor.AFTER, before) else Constraints.FIRST, actions)
            }
        }
    }

    /** Fails on any vault entry point: compare, plans and writes must never unlock, decrypt or reveal. */
    fun guardVault(project: Project, parent: Disposable): DecryptGuard =
        DecryptGuard().also { project.registerOrReplaceServiceInstance(VaultOperations::class.java, it, parent) }
}

/** A [RoleDiffUi] that records instead of showing; [pick] chooses in "Compare with…" (null: nothing). */
class RecordingDiffUi : RoleDiffUi {
    val chains = CopyOnWriteArrayList<DiffRequestChain>()
    val notices = CopyOnWriteArrayList<String>()
    val choiceLists = CopyOnWriteArrayList<List<CompareChoice>>()

    @Volatile
    var pick: ((List<CompareChoice>) -> CompareChoice?)? = null

    override fun show(project: Project, chain: DiffRequestChain) {
        chains += chain
    }

    override fun chooseCopy(project: Project, title: String, choices: List<CompareChoice>, context: DataContext?, chosen: (CompareChoice) -> Unit) {
        choiceLists += choices
        pick?.invoke(choices)?.let(chosen)
    }

    override fun inform(project: Project, message: String) {
        notices += message
    }
}

/** Fails the test on any vault entry point; [calls] counts the attempts. */
class DecryptGuard : VaultOperations {
    val calls = AtomicInteger()

    private fun refuse(): Nothing {
        calls.incrementAndGet()
        throw AssertionError("golden features must never touch vault operations")
    }

    override suspend fun unlock(root: AnsibleRoot, identity: String?): VaultUnlockResult = refuse()
    override fun lockAll() = refuse()
    override suspend fun decrypt(location: SourceLocation, purpose: VaultPurpose): VaultDecryptResult = refuse()
    override suspend fun encrypt(root: AnsibleRoot, plaintext: ByteArray, identity: String?, target: VirtualFile?): VaultEncryptResult = refuse()
    override fun reveal(location: SourceLocation, editor: Editor?) = refuse()
}

/**
 * Base of the golden tests that need real files (Local History, Undo of creations and deletions, executable bits,
 * symbolic links): the synthetic drift tree copied to a temporary directory on the local file system, added as a
 * content root of the light project. Everything is removed afterwards (the light project outlives a test).
 */
abstract class GoldenLocalTestCase : BasePlatformTestCase() {
    protected lateinit var base: Path
    private var contentRoot: VirtualFile? = null

    override fun setUp() {
        super.setUp()
        base = FileUtil.createTempDirectory("ansibility-golden", null, true).toPath().toRealPath()
        VfsRootAccess.allowRootAccess(testRootDisposable, base.toString())
        copyTree(Paths.get(ModelFixture.testDataPath, DriftFixture.DRIFT), base)
        refresh()
        GoldenTestSupport.useFirstLibraryAsGolden(project, testRootDisposable)
    }

    override fun tearDown() {
        try {
            FileDocumentManager.getInstance().saveAllDocuments()
            contentRoot?.let { PsiTestUtil.removeContentEntry(module, it) }
            AnsibleWorkspaceImpl.getInstance(project)?.structureChanged()
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    /** Refreshes the VFS below the tree, registers it as a content root once and rescans the roots. */
    protected fun refresh() {
        GoldenTestSupport.fsync()
        val dir = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(base) ?: error("no VFS dir for $base")
        VfsUtil.markDirtyAndRefresh(false, true, true, dir)
        if (contentRoot == null) {
            PsiTestUtil.addContentRoot(module, dir)
            contentRoot = dir
        }
        AnsibleWorkspaceImpl.getInstance(project)!!.structureChanged()
    }

    /** The VFS file at [relative] below the tree. */
    protected fun vf(relative: String): VirtualFile =
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(base.resolve(relative)) ?: error("no file $relative")

    /** The role directory of [role] in the repo [team] (or `golden`). */
    protected fun roleDir(team: String, role: String = "web"): VirtualFile = vf(DriftFixture.roleDir(team, role))

    protected fun roleCopy(team: String, role: String = "web"): RoleCopy = GoldenTestSupport.copy(project, roleDir(team, role))

    /** The path of [relative] inside [team]'s copy of web. */
    protected fun path(team: String, relative: String): Path = base.resolve("${DriftFixture.roleDir(team)}/$relative")

    /** The bytes on disk (after flushing the asynchronous writes). */
    protected fun bytes(team: String, relative: String): ByteArray {
        GoldenTestSupport.fsync()
        return Files.readAllBytes(path(team, relative))
    }

    /** Writes [bytes] on disk below [team]'s copy and refreshes the VFS (an external change). */
    protected fun writeOnDisk(team: String, relative: String, bytes: ByteArray) {
        val path = path(team, relative)
        Files.createDirectories(path.parent)
        Files.write(path, bytes)
        refresh()
    }

    private fun copyTree(from: Path, to: Path) {
        Files.walk(from).use { paths ->
            paths.forEach { source ->
                val target = to.resolve(from.relativize(source).toString())
                if (source.isDirectory()) Files.createDirectories(target) else Files.copy(source, target)
            }
        }
    }
}
