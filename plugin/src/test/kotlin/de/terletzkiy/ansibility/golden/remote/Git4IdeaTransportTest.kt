package de.terletzkiy.ansibility.golden.remote

import com.intellij.openapi.util.io.NioFiles
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.terletzkiy.ansibility.golden.GoldenTestSupport
import de.terletzkiy.ansibility.golden.remote.GitTestRepo.Companion.files
import de.terletzkiy.ansibility.golden.remote.GitTestRepo.Companion.revCount
import de.terletzkiy.ansibility.settings.SettingsTestSupport
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant

/**
 * The golden mirror through the bundled Git plugin (plan amendment R25, D202): the test sandbox loads Git4Idea (a
 * bundled plugin of the test IDE), so `ansibility-git.xml` registers its transport, which [GoldenGitTransport.current]
 * prefers. The same sparse, shallow clone and refresh as with the system git, against `file://` repositories; the Git
 * plugin adds `protocol.file.allow=always` in tests, our `protocol.allow=never` keeps every other protocol but the four
 * allowed ones refused.
 */
class Git4IdeaTransportTest : BasePlatformTestCase() {
    private lateinit var temp: Path
    private lateinit var service: GoldenMirrorService
    private lateinit var transport: RecordingTransport
    private var now: Instant = Instant.parse("2026-10-09T10:00:00Z")

    override fun setUp() {
        super.setUp()
        temp = Files.createTempDirectory("ansibility-git4idea").toRealPath()
        service = GoldenMirrorService.getInstance(project)!!
        service.resetForTests()
        transport = RecordingTransport(delegate = GoldenGitTransport.current())
        service.configureForTests(base = temp.resolve("cache"), transport = transport, now = { now }, powerSave = false, externalAgent = false)
        GoldenMirrorConsents.getInstance().resetForTests()
    }

    override fun tearDown() {
        try {
            SettingsTestSupport.resetAll(project)
            GoldenTestSupport.await { service.reconcileForTests() }
            service.resetForTests()
            GoldenMirrorConsents.getInstance().resetForTests()
            NioFiles.deleteRecursively(temp)
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    fun testTheGitPluginsTransportIsRegisteredAndPreferred() {
        val registered = GoldenGitTransport.EP_NAME.extensionList.map { it.javaClass.name }
        assertEquals(listOf("de.terletzkiy.ansibility.golden.remote.git4idea.impl.Git4IdeaGoldenGitTransport"), registered)
        assertEquals(registered.single(), GoldenGitTransport.current().javaClass.name)
    }

    fun testASparseShallowCloneAndRefreshThroughTheGitPlugin() {
        val golden = GitTestRepo.standard(temp.resolve("golden"))
        repeat(2) { golden.write("roles/web/tasks/main.yml", "- debug: msg=$it\n").commit("golden: $it", author = "bob") }
        GoldenMirrorConsents.getInstance().set(golden.url, true)
        MirrorSettings.useGit(project, golden.url, depth = 2)
        GoldenTestSupport.await { service.reconcileForTests() }
        assertTrue(GoldenTestSupport.await { service.tick() })
        val state = service.state()!!
        assertNull(state.error, state.error)
        assertEquals(golden.head(), state.commit)
        assertEquals(2, revCount(state.baseDir!!))
        val mirrored = files(state.baseDir!!)
        assertTrue(mirrored.toString(), "roles/web/tasks/main.yml" in mirrored && mirrored.none { it.startsWith("playbooks/") })

        val moved = golden.write("roles/db/tasks/main.yml", "- name: db v2\n").commit("golden: db v2")
        transport.clear()
        now = now.plus(Duration.ofMinutes(31))
        assertTrue(GoldenTestSupport.await { service.tick() })
        assertTrue(transport.ops().toString(), transport.ops().containsAll(listOf(GitOp.LS_REMOTE, GitOp.FETCH, GitOp.CHECKOUT)))
        assertEquals(moved, service.state()!!.commit)
        assertNull(service.state()!!.error)

        val connection = GoldenTestSupport.await { service.testConnection(golden.url, "") }
        assertEquals(ConnectionResult(true, GoldenMirrorTexts.connectedDefault("main", moved.take(7))), connection)
    }

    fun testExtIsRefusedThroughTheGitPlugin() {
        val marker = temp.resolve("pwned")
        val request = GitRequest(
            project = project,
            workDir = temp,
            op = GitOp.LS_REMOTE,
            args = listOf("--", "ext::sh -c touch% $marker", "HEAD"),
            remoteUrl = "ext::sh",
            interaction = Interaction.BACKGROUND,
            config = GoldenMirrorCommands.safetyConfig(temp.resolve(".no-hooks")),
            env = GoldenMirrorCommands.environment(Interaction.BACKGROUND, network = true),
        )
        val result = GoldenTestSupport.await { GoldenGitTransport.current().run(request) }
        assertFalse(result.success)
        assertFalse(Files.exists(marker))
    }
}
