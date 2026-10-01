package de.terletzkiy.ansibility.runtime

import com.intellij.openapi.util.io.FileUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.context.AnsibleWorkspaceImpl
import de.terletzkiy.ansibility.context.TargetVersionDetector
import de.terletzkiy.ansibility.semantics.CoreVersion
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

/**
 * Shared setup of the runtime platform tests: minimal roots in the light fixture, per-root target versions
 * through the settings hook of [TargetVersionDetector], and the application-wide runtime state (options, probes)
 * restored after every test, because the light project and the application outlive a test.
 */
abstract class RuntimeTestCase : BasePlatformTestCase() {
    protected val options: AnsibleRuntimeOptions get() = AnsibleRuntimeOptions.getInstance()
    private var savedRefresh = false
    private var savedProbe = false
    private var savedExplicit: (AnsibleTool) -> String? = { null }
    private var savedBase: DocsBase? = null
    private val targets = HashMap<String, CoreVersion?>()
    private val tempDirs = ArrayList<Path>()

    override fun setUp() {
        super.setUp()
        savedRefresh = options.localDocRefresh
        savedProbe = options.localTargetProbe
        savedExplicit = options.explicitExecutable
        savedBase = options.docsBaseOverride
        LocalDocRefresher.getInstance(project).resetForTests()
        AnsibleToolchain.getInstance().resetForTests()
        TargetVersionDetector.getInstance(project).overrideFor = { root -> targets[root.dir.path] }
    }

    override fun tearDown() {
        try {
            options.localDocRefresh = savedRefresh
            options.localTargetProbe = savedProbe
            options.explicitExecutable = savedExplicit
            options.docsBaseOverride = savedBase
            System.clearProperty(DocsBase.PROPERTY)
            TargetVersionDetector.getInstance(project).overrideFor = { null }
            LocalDocRefresher.getInstance(project).resetForTests()
            AnsibleToolchain.getInstance().resetForTests()
            tempDirs.forEach { FileUtil.delete(it) }
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    /** Creates a PROJECT root (`<path>/ansible.cfg`) with [files] below it and returns it. */
    protected fun createRoot(path: String = "site", files: Map<String, String> = emptyMap()): AnsibleRoot {
        myFixture.tempDirFixture.createFile("$path/ansible.cfg", "[defaults]\n")
        for ((relative, text) in files) myFixture.tempDirFixture.createFile("$path/$relative", text)
        AnsibleWorkspaceImpl.getInstance(project)!!.structureChanged()
        val dir = myFixture.findFileInTempDir(path)!!
        return AnsibleWorkspace.getInstance(project).roots().single { it.dir == dir }
    }

    /** Sets the target ansible-core of [root] (null: nothing pinned, so the target is unknown). */
    protected fun target(root: AnsibleRoot, version: CoreVersion?) {
        targets[root.dir.path] = version
        TargetVersionDetector.getInstance(project).overrideFor = { r -> targets[r.dir.path] }
    }

    /** A temporary directory on the local disk, deleted after the test. */
    protected fun tempDir(prefix: String = "ansibility-runtime"): Path =
        FileUtil.createTempDirectory(prefix, null, true).toPath().toRealPath().also(tempDirs::add)

    /** Writes an executable `/bin/sh` script. */
    protected fun script(dir: Path, name: String, body: String): Path {
        val file = dir.resolve(name)
        Files.writeString(file, "#!/bin/sh\n$body\n")
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"))
        return file
    }
}
