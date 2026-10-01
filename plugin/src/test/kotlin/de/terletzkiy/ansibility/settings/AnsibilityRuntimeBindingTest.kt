package de.terletzkiy.ansibility.settings

import com.intellij.testFramework.PlatformTestUtil
import de.terletzkiy.ansibility.api.AnsibleDocService
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.DocKind
import de.terletzkiy.ansibility.runtime.AnsibleTool
import de.terletzkiy.ansibility.runtime.AnsibleToolchain
import de.terletzkiy.ansibility.runtime.DocsBase
import de.terletzkiy.ansibility.runtime.LocalDocPrefetcher
import de.terletzkiy.ansibility.runtime.RuntimeTestCase
import de.terletzkiy.ansibility.semantics.CoreVersion
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import java.util.Collections
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** Settings → `runtime.AnsibleRuntimeOptions` (task a): each application setting changes what the runtime does. */
class AnsibilityRuntimeBindingTest : RuntimeTestCase() {
    private val binding: AnsibilityRuntimeBinding get() = AnsibilityRuntimeBinding.getInstance()
    private val app: AnsibilityAppSettings get() = AnsibilityAppSettings.getInstance()
    private lateinit var root: AnsibleRoot
    private lateinit var savedSink: (AnsibleRoot, Set<String>) -> Unit
    private var savedDebounce: Duration = Duration.ZERO

    override fun setUp() {
        super.setUp()
        root = createRoot("site", mapOf("roles/web/tasks/main.yml" to "- name: Copy\n  ansible.builtin.copy:\n    src: a\n    dest: /b\n"))
        target(root, CoreVersion.PINNED)
        val prefetcher = LocalDocPrefetcher.getInstance(project)
        savedSink = prefetcher.sink
        savedDebounce = prefetcher.debounce
    }

    override fun tearDown() {
        try {
            val prefetcher = LocalDocPrefetcher.getInstance(project)
            prefetcher.sink = savedSink
            prefetcher.debounce = savedDebounce
            binding.bindsProcessSwitches = false
            SettingsTestSupport.resetAll(project)
            binding.resetForTests()
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    private fun docs(transform: (DocsSettings) -> DocsSettings) {
        app.update { it.copy(docs = transform(it.docs)) }
    }

    private fun copyUrl(): String = AnsibleDocService.getInstance(project).docsUrl(root, DocKind.MODULE, "copy")

    fun testDocsWebBaseSettingChangesTheUrls() {
        docs { it.copy(webBase = DocsWebBase.TARGET_VERSIONED) }
        binding.apply(app.settings)
        assertEquals("https://docs.ansible.com/ansible/11/collections/ansible/builtin/copy_module.html", copyUrl())

        docs { it.copy(webBase = DocsWebBase.LATEST) }
        assertEquals("https://docs.ansible.com/ansible/latest/collections/ansible/builtin/copy_module.html", copyUrl())

        docs { it.copy(webBase = DocsWebBase.CUSTOM, customWebBaseUrl = "https://mirror.example/ansible/11") }
        assertEquals("https://mirror.example/ansible/11/collections/ansible/builtin/copy_module.html", copyUrl())

        docs { it.copy(customWebBaseUrl = "  ") }
        assertEquals("a custom base without a URL opens latest", "https://docs.ansible.com/ansible/latest/collections/ansible/builtin/copy_module.html", copyUrl())

        docs { it.copy(webBase = DocsWebBase.TARGET_VERSIONED) }
        assertEquals(DocsBase.TargetVersioned, options.docsBaseOverride)
        assertEquals("https://docs.ansible.com/ansible/11/collections/ansible/builtin/copy_module.html", copyUrl())
    }

    fun testDocsBaseMapping() {
        assertEquals(DocsBase.TargetVersioned, AnsibilityRuntimeBinding.docsBase(DocsSettings()))
        assertEquals(DocsBase.Latest, AnsibilityRuntimeBinding.docsBase(DocsSettings(webBase = DocsWebBase.LATEST)))
        assertEquals(DocsBase.Custom("https://m.example/x/"), AnsibilityRuntimeBinding.docsBase(DocsSettings(DocsWebBase.CUSTOM, " https://m.example/x ")))
        assertEquals(DocsBase.Custom("https://m.example/x/"), AnsibilityRuntimeBinding.docsBase(DocsSettings(DocsWebBase.CUSTOM, "https://m.example/x/")))
        assertEquals(DocsBase.Latest, AnsibilityRuntimeBinding.docsBase(DocsSettings(DocsWebBase.CUSTOM, "")))
    }

    fun testExecutablesBindTheToolchainHook() {
        app.update { it.copy(executables = it.executables.copy(ansibleDoc = "/opt/tools/bin/ansible-doc")) }
        assertEquals("/opt/tools/bin/ansible-doc", options.explicitExecutable(AnsibleTool.ANSIBLE_DOC))
        assertEquals("tools of one install share a directory", "/opt/tools/bin/ansible-doc", options.explicitExecutable(AnsibleTool.ANSIBLE))
        val toolchain = AnsibleToolchain.getInstance()
        assertEquals(Path.of("/opt/tools/bin/ansible"), toolchain.candidates(AnsibleTool.ANSIBLE, null).first())
        assertEquals(Path.of("/opt/tools/bin/ansible-doc"), toolchain.candidates(AnsibleTool.ANSIBLE_DOC, null).first())

        app.update { it.copy(executables = it.executables.copy(ansible = "/usr/local/bin/ansible")) }
        assertEquals("a tool's own path wins", "/usr/local/bin/ansible", options.explicitExecutable(AnsibleTool.ANSIBLE))
        assertEquals("/opt/tools/bin/ansible-doc", options.explicitExecutable(AnsibleTool.ANSIBLE_DOC))
        assertEquals("/usr/local/bin/ansible", options.explicitExecutable(AnsibleTool.ANSIBLE_INVENTORY))

        app.update { it.copy(executables = ExecutableSettings()) }
        AnsibleTool.entries.forEach { assertNull(options.explicitExecutable(it)) }
    }

    fun testProcessSwitchesStayOffInUnitTestsUnlessAllowed() {
        options.localDocRefresh = false
        options.localTargetProbe = false
        assertFalse("unit-test default", binding.bindsProcessSwitches)
        binding.apply(AppSettings.DEFAULT)
        assertFalse("settings default on, but no processes in tests", options.localDocRefresh)
        assertFalse(options.localTargetProbe)
    }

    fun testProcessSwitchesFollowTheSettings() {
        val prefetcher = LocalDocPrefetcher.getInstance(project)
        val requested = Collections.synchronizedList(ArrayList<Pair<AnsibleRoot, Set<String>>>())
        prefetcher.sink = { r, names -> requested += r to names }
        prefetcher.debounce = 0.milliseconds
        options.localDocRefresh = false
        options.localTargetProbe = true
        binding.bindsProcessSwitches = true

        app.update { it.copy(executables = it.executables.copy(localTargetGuess = false)) }
        assertFalse("D10 off", options.localTargetProbe)
        app.update { it.copy(executables = it.executables.copy(localTargetGuess = true)) }
        assertTrue(options.localTargetProbe)

        assertTrue("the default is on", app.settings.docs.backgroundRefresh)
        binding.apply(app.settings)
        assertTrue("D14 on", options.localDocRefresh)
        PlatformTestUtil.waitWithEventsDispatching("turning D14 on prefetches the modules in use", { requested.isNotEmpty() }, 20)
        assertEquals(root, requested.first().first)
        assertTrue("ansible.builtin.copy" in requested.first().second)

        docs { it.copy(backgroundRefresh = false) }
        assertFalse("D14 off", options.localDocRefresh)
    }

    fun testApplyAssignsOnlyChangedHooks() {
        binding.apply(app.settings)
        val tracker = options.tracker.modificationCount
        binding.apply(app.settings)
        assertEquals("nothing changed, so no cache is dropped", tracker, options.tracker.modificationCount)
        binding.apply(app.settings.copy(docs = DocsSettings(webBase = DocsWebBase.LATEST)))
        assertTrue(options.tracker.modificationCount > tracker)
    }

    fun testSettingsActivityAppliesTheBindingOnce() {
        binding.resetForTests()
        options.docsBaseOverride = null
        runBlocking { AnsibilitySettingsActivity().execute(project) }
        assertEquals(DocsBase.TargetVersioned, options.docsBaseOverride)
        options.docsBaseOverride = DocsBase.Latest
        binding.ensureApplied()
        assertEquals("applied once per application", DocsBase.Latest, options.docsBaseOverride)
    }

    fun testExecutablePathFallback() {
        val executables = ExecutableSettings(ansibleInventory = "/a/ansible-inventory")
        assertEquals("/a/ansible-inventory", executables.pathFor(executables.ansible))
        assertEquals("/a/ansible-inventory", executables.pathFor(executables.ansibleInventory))
        assertNull(ExecutableSettings().pathFor(null))
    }
}
