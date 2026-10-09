package de.terletzkiy.ansibility.model.drift

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.CodeInsightTestFixture
import de.terletzkiy.ansibility.api.VaultHeaderInfo
import de.terletzkiy.ansibility.model.role.ModelFixture
import de.terletzkiy.ansibility.model.role.RoleCatalog
import de.terletzkiy.ansibility.model.role.RoleCopy
import de.terletzkiy.ansibility.settings.AnsibilityProjectSettings
import de.terletzkiy.ansibility.settings.GoldenRoot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.util.concurrent.TimeUnit

/**
 * Shared set-up of the drift tests: the synthetic tree `testData/toolwindow/drift` (see its README: golden plus seven
 * repo copies of `web`, one per tier), copied to the project root, and small helpers around [RoleDriftService].
 */
object DriftFixture {
    /** The synthetic tree below `src/test/testData`. */
    const val DRIFT: String = "toolwindow/drift"

    /** The repo copies of `web`, in catalog order (project roots by path). */
    val WEB_REPOS: List<String> = listOf("missing", "mol", "mol2", "same", "spec", "specmol", "tasks")

    /** Copies the synthetic tree to the project and re-scans the roots. */
    fun copy(fixture: CodeInsightTestFixture) {
        fixture.copyDirectoryToProject(DRIFT, "")
        ModelFixture.rescan(fixture.project)
    }

    /** The role directory of [role] in the repo [team] (or `golden`). */
    fun roleDir(team: String, role: String = "web"): String =
        if (team == "golden") "golden/roles/$role" else "repos/$team/ansible/roles/$role"

    fun file(fixture: CodeInsightTestFixture, path: String): VirtualFile = ModelFixture.file(fixture, path)

    /**
     * A service instance of its own, disposed with [parent], whose scope is cancelled then: tests see exact counters
     * and no state left by other tests in the shared light project.
     */
    fun freshService(project: Project, parent: Disposable): RoleDriftService {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val service = RoleDriftService(project, scope)
        Disposer.register(parent, service)
        Disposer.register(parent) { scope.cancel() }
        return service
    }

    /**
     * Runs the suspending [action] on a pooled thread while the EDT keeps dispatching events, as a caller in the IDE
     * would (never `runBlocking` on the EDT: a write action queued for the EDT would then block every read action).
     */
    fun <T> await(action: suspend () -> T): T {
        val future = ApplicationManager.getApplication().executeOnPooledThread<T> { runBlocking { action() } }
        return PlatformTestUtil.waitForFuture(future, TimeUnit.MINUTES.toMillis(5))
    }

    /** The current drift of [name] (computed now, on a background dispatcher). */
    fun drift(service: RoleDriftService, name: String): RoleDrift = await { service.drift(name) } ?: error("no role $name")

    /** The drift of [name]'s copy in [team]. */
    fun copyDrift(fixture: CodeInsightTestFixture, service: RoleDriftService, name: String, team: String): CopyDrift {
        val dir = file(fixture, roleDir(team, name))
        return drift(service, name).copyOf(dir) ?: error("no copy of $name in $team")
    }

    fun catalogCopy(fixture: CodeInsightTestFixture, name: String, team: String): RoleCopy =
        RoleCatalog.getInstance(fixture.project).copyOf(file(fixture, roleDir(team, name))) ?: error("no copy of $name in $team")

    /** Writes [text] to the project-relative [path] (creating parents) in a write action. Needs the copied tree. */
    fun write(fixture: CodeInsightTestFixture, path: String, text: String): VirtualFile = write(fixture, path, text.toByteArray())

    fun write(fixture: CodeInsightTestFixture, path: String, bytes: ByteArray): VirtualFile = WriteAction.computeAndWait<VirtualFile, Exception> {
        val base = file(fixture, "golden").parent ?: error("no project dir")
        val dir = path.substringBeforeLast('/', "")
        val parent = if (dir.isEmpty()) base else VfsUtil.createDirectoryIfMissing(base, dir)
        val file = parent.findChild(path.substringAfterLast('/')) ?: parent.createChildData(this, path.substringAfterLast('/'))
        file.setBinaryContent(bytes)
        file
    }

    /** Gives [to]'s copy of `web` the content of [from]'s file at [path] (inside the role). */
    fun copyFile(fixture: CodeInsightTestFixture, from: String, to: String, path: String) {
        write(fixture, "${roleDir(to)}/$path", VfsUtil.loadText(file(fixture, "${roleDir(from)}/$path")))
    }

    /**
     * Makes the `web` copies of specmol and tasks byte-identical to spec's (golden plus spec's `meta/argument_specs.yml`):
     * their variant, three copies, is then the largest, ahead of mol and mol2's.
     */
    fun joinSpecsVariant(fixture: CodeInsightTestFixture) {
        for (team in listOf("specmol", "tasks")) copyFile(fixture, "spec", team, "meta/argument_specs.yml")
        for (path in listOf("defaults/main.yml", "molecule/default/verify.yml")) copyFile(fixture, "golden", "specmol", path)
        copyFile(fixture, "golden", "tasks", "tasks/main.yml")
    }

    fun delete(fixture: CodeInsightTestFixture, path: String) {
        WriteAction.runAndWait<Exception> { file(fixture, path).delete(this) }
    }

    /**
     * A synthetic whole-file vault: the real header and a hex body of a made-up text. It is never decrypted (nothing
     * here could), and it is built at test time, so no `$ANSIBLE_VAULT` file is committed.
     */
    fun syntheticVault(marker: String): String =
        "${VaultHeaderInfo.MAGIC};1.1;AES256\n" + marker.toByteArray().joinToString("") { "%02x".format(it) }.chunked(80).joinToString("\n") + "\n"

    /**
     * Sets the golden root to [golden] until [parent] is disposed (plan amendment R24: the default is None, which
     * computes nothing in the background and marks no reference). R9's tests use the first role library.
     */
    fun useGolden(project: Project, parent: Disposable, golden: GoldenRoot = GoldenRoot.FirstRoleLibrary) {
        val settings = AnsibilityProjectSettings.getInstance(project)
        val before = settings.settings.drift
        settings.update { it.copy(drift = it.drift.copy(golden = golden)) }
        Disposer.register(parent) { settings.update { it.copy(drift = before) } }
    }

    /** Waits (dispatching events) until [condition] holds. */
    fun waitFor(what: String, condition: () -> Boolean) = PlatformTestUtil.waitWithEventsDispatching(what, condition, 20)
}
