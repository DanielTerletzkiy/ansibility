package de.terletzkiy.ansibility.run.view

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputType
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.EditorTextField
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.UIUtil
import de.terletzkiy.ansibility.run.events.HostStatus
import de.terletzkiy.ansibility.run.events.ItemRun
import de.terletzkiy.ansibility.run.events.LineSplitter
import de.terletzkiy.ansibility.run.events.MoleculeLog
import de.terletzkiy.ansibility.run.events.PlayRun
import de.terletzkiy.ansibility.run.events.RunCallback
import de.terletzkiy.ansibility.run.events.RunEvent
import de.terletzkiy.ansibility.run.events.RunEventDecoder
import de.terletzkiy.ansibility.run.events.RunEventFrames
import de.terletzkiy.ansibility.run.events.StageRun
import de.terletzkiy.ansibility.run.events.TaskRun
import de.terletzkiy.ansibility.run.events.UnitRun
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import javax.swing.AbstractButton
import javax.swing.JComponent
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.TreePath

/** The "Plays" tab: the tree and its filters, the details, the strip, the fallback, and the events of a real process. */
class RunViewTest : BasePlatformTestCase() {
    private class Recorded : RunViewActions {
        val reruns = ArrayList<List<String>>()
        val starts = ArrayList<String>()
        val parts = ArrayList<Pair<String, String?>>()
        override fun rerunHosts(hosts: List<String>) { reruns += hosts }
        override fun startAt(task: String) { starts += task }
        override fun runPart(play: String, role: String?) { parts += play to role }
    }

    private val goldens = Path.of("src/test/testData/run-events")
    private lateinit var collector: RunEventCollector
    private lateinit var actions: Recorded

    override fun setUp() {
        super.setUp()
        collector = RunEventCollector(testRootDisposable)
        actions = Recorded()
    }

    private fun view(case: String?, finish: Int? = 0, hostPath: (String) -> Path? = { null }): AnsibleRunView {
        if (case != null) Files.readAllLines(goldens.resolve(case).resolve("2.21.4.jsonl")).forEach { RunEventDecoder.decode(it)?.let(collector::accept) }
        if (finish != null) collector.finish(finish)
        val view = AnsibleRunView(project, collector, actions, hostPath)
        collector.drainForTests()
        return view
    }

    private fun AnsibleRunView.children(node: DefaultMutableTreeNode = treeModel.root): List<Any> =
        (0 until node.childCount).map { (node.getChildAt(it) as DefaultMutableTreeNode).userObject }

    private fun AnsibleRunView.select(value: Any) {
        val node = treeModel.node(value)!!
        tree.selectionPath = TreePath(node.path)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
    }

    private fun AnsibleRunView.rendered(value: Any): String {
        val node = treeModel.node(value)!!
        val renderer = tree.cellRenderer as RunTreeRenderer
        renderer.getTreeCellRendererComponent(tree, node, false, false, true, 0, false)
        return renderer.getCharSequence(false).toString()
    }

    private fun <T : JComponent> JComponent.find(type: Class<T>): List<T> = UIUtil.findComponentsOfType(this, type)

    fun testTheTreeShowsPlaysTasksHostsItemsAndTheRecap() {
        val view = view("basic")
        val top = view.children()
        assertEquals(2, top.size)
        val play = top[0] as PlayRun
        assertSame(RecapNode, top[1])
        val tasks = view.children(view.treeModel.node(play)!!).map { (it as TaskRun).shortName }
        assertTrue(tasks.containsAll(listOf("Write the site file", "Loop over packages", "Reload site")))
        val loop = play.tasks.first { it.shortName == "Loop over packages" }
        val hostNode = view.treeModel.node(loop.hosts.getValue("h1"))!!
        assertEquals(listOf("nginx", "certbot"), view.children(hostNode).map { (it as ItemRun).label })
        assertTrue("the play is expanded", view.tree.isExpanded(TreePath(view.treeModel.node(play)!!.path)))

        val playText = view.rendered(play)
        assertTrue(playText, playText.startsWith("Web") && "OK" in playText && "%" in playText)
        assertEquals("web › Write the site file   2 changed · 0.0 s", view.rendered(play.tasks.first { it.shortName == "Write the site file" }).replace(Regex("\\d+\\.\\d s"), "0.0 s"))
        val retried = view.rendered(play.tasks.first { it.shortName.startsWith("Retries") }.hosts.getValue("h1"))
        assertTrue(retried, "retried 1×" in retried)
        val delegated = view.rendered(play.tasks.first { it.shortName.startsWith("Runs on the first host") }.hosts.getValue("h2"))
        assertTrue(delegated, "→ h1" in delegated)
    }

    fun testFiltersHideOkAndSkippedResults() {
        val view = view("basic")
        val play = collector.model.plays.single()
        view.treeModel.showOk = false
        view.treeModel.showSkipped = false
        view.refresh()
        val tasks = view.children(view.treeModel.node(play)!!).map { it as TaskRun }
        assertTrue(tasks.none { it.shortName == "Loop over packages" || it.shortName == "Skipped on purpose" })
        assertTrue(tasks.any { it.shortName == "Write the site file" } && tasks.any { it.shortName == "Breaks" })
        val mixed = play.tasks.first { it.shortName.startsWith("Loop with") }
        assertEquals(listOf(HostStatus.FAILED), view.children(view.treeModel.node(mixed.hosts.getValue("h1"))!!).map { (it as ItemRun).status })
        view.treeModel.showOk = true
        view.treeModel.showSkipped = true
        view.refresh()
        assertNotNull(view.treeModel.node(play.tasks.first { it.shortName == "Loop over packages" }))
    }

    fun testDetailsShowResultsDiffsTheRecapAndTheRun() {
        val view = view("basic")
        val play = collector.model.plays.single()
        val site = play.tasks.first { it.shortName == "Write the site file" }
        view.select(site.hosts.getValue("h1"))
        val details = view.detailsComponentForTests()
        val json = details.find(EditorTextField::class.java).single().text
        assertTrue(json, "\"dest\"" in json && "site.txt" in json)
        assertTrue(details.find(AbstractButton::class.java).any { it.text == "Show Diff: site.txt" })

        view.select(play.tasks.first { it.shortName == "Secret output" }.hosts.getValue("h2"))
        val secret = view.detailsComponentForTests().find(EditorTextField::class.java).single().text
        assertTrue(secret, "censored" in secret && "s3cret" !in secret)

        view.select(RecapNode)
        assertEquals(2, view.detailsComponentForTests().find(JBTable::class.java).single().rowCount)

        view.select(site)
        val buttons = view.detailsComponentForTests().find(AbstractButton::class.java)
        buttons.first { it.text == "Start at This Task" }.doClick()
        buttons.first { it.text == "Run Role 'web'…" }.doClick()
        assertEquals(listOf("web : Write the site file"), actions.starts)
        assertEquals(listOf("Web" to "web"), actions.parts)
    }

    fun testFailedHostsCanBeRunAgain() {
        val view = view("failure", finish = 2)
        view.tree.clearSelection()
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        view.detailsComponentForTests().find(AbstractButton::class.java).first { it.text.startsWith("Rerun Failed Hosts") }.doClick()
        assertEquals(listOf(listOf("a1", "a2", "down.invalid")), actions.reruns.map { it.sorted() })
        val first = collector.model.plays.first().tasks.first()
        assertTrue("a failed task is expanded", view.tree.isExpanded(TreePath(view.treeModel.node(first)!!.path)))
        assertEquals(3, view.strip.model.size)
        view.strip.selectedIndex = 1
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        assertSame(collector.model.plays[1], (view.tree.selectionPath!!.lastPathComponent as DefaultMutableTreeNode).userObject)
    }

    fun testATaskOpensItsSource() {
        val dir = FileUtil.createTempDirectory("ansibility-view", null, true).toPath().toRealPath()
        VfsRootAccess.allowRootAccess(testRootDisposable, dir.toString())
        val file = Files.writeString(dir.resolve("site.yml"), "- hosts: all\n  tasks:\n    - name: One\n      debug: {}\n")
        val view = view(null, finish = null) { path -> Path.of(path.replace("/container", dir.toString())) }
        assertTrue(view.details.navigate("/container/site.yml:3"))
        val editor = FileEditorManager.getInstance(project).selectedTextEditor!!
        assertEquals(file.fileName.toString(), FileEditorManager.getInstance(project).selectedFiles.single().name)
        assertEquals(2, editor.caretModel.logicalPosition.line)
        assertFalse(view.details.navigate("/elsewhere/missing.yml:1"))
        assertEquals("a.yml" to 1, RunDetails.split("a.yml"))
        FileEditorManager.getInstance(project).closeFile(FileEditorManager.getInstance(project).selectedFiles.single())
    }

    fun testARunWithoutEventsLeavesItToTheConsole() {
        var reported = 0
        val view = view(null, finish = null)
        view.onNoEvents = { reported++ }
        assertEquals("Waiting for the first play… (the Console tab shows the output)", view.tree.emptyText.text)
        collector.finish(4)
        collector.drainForTests()
        assertEquals("No plays were reported: see the Console tab", view.tree.emptyText.text)
        assertEquals(1, reported)
        collector.drainForTests()
        assertEquals(1, reported)
    }

    fun testARealProcessHasItsFramesTakenOutOfStderr() {
        val script = "printf 'normal out\\n'; printf 'warning one\\n\\036tok {\"e\":\"play\",\"id\":\"p\",\"name\":\"Web\",\"hosts\":[\"all\"]}\\n' >&2; " +
            "printf '\\036tok {\"e\":\"stats\",\"hosts\":{\"h1\":{\"ok\":1}}}\\nwarning two' >&2"
        val handler = RunEventsProcessHandler(GeneralCommandLine("sh", "-c", script), "tok", collector)
        val stderr = StringBuilder()
        val stdout = StringBuilder()
        handler.addProcessListener(object : ProcessListener {
            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                if (outputType is ProcessOutputType && outputType.isStderr) stderr.append(event.text)
                else if (outputType is ProcessOutputType && outputType.isStdout) stdout.append(event.text)
            }
        })
        handler.startNotify()
        assertTrue(handler.waitFor(TimeUnit.SECONDS.toMillis(20)))
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        collector.drainForTests()
        assertEquals("normal out\n", stdout.toString())
        assertEquals("warning one\nwarning two", stderr.toString())
        assertEquals(listOf("Web"), collector.model.plays.map { it.name })
        assertEquals(setOf("h1"), collector.model.stats.keys)
        assertTrue(collector.model.finished)
        assertEquals(0, collector.model.exitCode)
    }

    fun testARealAnsibleRunWhenAnsibleIsInstalled() {
        val playbook = listOf("/opt/homebrew/bin", "/usr/local/bin", "/usr/bin").map { Path.of(it, "ansible-playbook") }.firstOrNull { Files.isExecutable(it) }
            ?: return // ansible-core is not installed (CI): the goldens cover the callback
        val dir = FileUtil.createTempDirectory("ansibility-e2e", null, true).toPath()
        Files.writeString(dir.resolve("site.yml"), "- name: Local\n  hosts: all\n  gather_facts: false\n  tasks:\n    - name: Say hi\n      ansible.builtin.debug: {msg: hi}\n")
        val callbacks = Path.of("src/main/resources/ansible/callback").toAbsolutePath()
        val command = GeneralCommandLine(playbook.toString(), "-i", "localhost,", "-c", "local", "site.yml")
            .withWorkingDirectory(dir)
            .withEnvironment(
                mapOf(
                    RunCallback.TOKEN_VARIABLE to "e2e",
                    RunCallback.PLUGINS_VARIABLE to RunCallback.pluginPath(null, null, callbacks.toString()),
                    "ANSIBLE_PYTHON_INTERPRETER" to "auto_silent",
                ),
            )
        val handler = RunEventsProcessHandler(command, "e2e", collector)
        handler.startNotify()
        assertTrue(handler.waitFor(TimeUnit.MINUTES.toMillis(2)))
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        collector.drainForTests()
        val task = collector.model.plays.single().tasks.single()
        assertEquals("Say hi", task.name)
        assertEquals(HostStatus.OK, task.hosts.getValue("localhost").status)
        assertEquals("hi", task.hosts.getValue("localhost").message)
        assertEquals(1, collector.model.stats.getValue("localhost").ok)
    }

    fun testARealMoleculeRunWhenAnImageIsGiven() {
        // Molecule logs its stage lines on stderr and passes Ansible's output, frames and all, on through stdout: two
        // streams on two reader threads. Opt-in: ANSIBILITY_MOLECULE_IMAGE names an image with Molecule (and Docker runs).
        val image = System.getenv("ANSIBILITY_MOLECULE_IMAGE")?.takeIf { it.isNotBlank() } ?: return
        val docker = listOf("/usr/local/bin", "/opt/homebrew/bin", "/usr/bin").map { Path.of(it, "docker") }.firstOrNull { Files.isExecutable(it) } ?: return
        val roles = FileUtil.createTempDirectory("ansibility-molecule", null, true).toPath().resolve("roles")
        FileUtil.copyDir(Path.of("../tools/run-events/molecule/roles").toFile(), roles.toFile())
        val callbacks = Path.of("src/main/resources/ansible/callback").toAbsolutePath()
        val command = GeneralCommandLine(
            docker.toString(), "run", "--rm", "--network", "none", "-v", "$roles:/ansible/roles", "-v", "$callbacks:/ansibility/callbacks:ro",
            "-w", "/ansible/roles/demo", "-e", "${RunCallback.TOKEN_VARIABLE}=e2e", "-e", "${RunCallback.PLUGINS_VARIABLE}=/ansibility/callbacks",
            "-e", "PYTHONUNBUFFERED=1", "-e", "ANSIBLE_PYTHON_INTERPRETER=auto_silent", "-e", "ANSIBLE_ROLES_PATH=/ansible/roles",
            "--entrypoint", "molecule", image, "test", "--all",
        )
        val handler = RunEventsProcessHandler(command, "e2e", collector, observeStages = true)
        handler.startNotify()
        assertTrue(handler.waitFor(TimeUnit.MINUTES.toMillis(5)))
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        collector.drainForTests()
        val stages = collector.model.stages.associateBy { "${it.scenario} ${it.action}" }
        for (stage in listOf("default converge", "default idempotence", "default verify", "volatile converge", "volatile idempotence")) {
            assertEquals(stage, listOf(if (stage.endsWith("verify")) "Verify" else "Converge"), stages.getValue(stage).plays.map { it.name })
            assertTrue(stage, stages.getValue(stage).stats.containsKey("instance"))
        }
        assertEquals(HostStatus.CHANGED, stages.getValue("default converge").status)
        assertEquals(HostStatus.OK, stages.getValue("default idempotence").status)
        assertEquals(HostStatus.FAILED, stages.getValue("volatile idempotence").status)
        assertEquals(listOf("Always changes"), stages.getValue("volatile idempotence").changedTasks.map { it.name })
        assertEquals(1, stages.getValue("volatile idempotence").stats.getValue("instance").changed)
        assertTrue(collector.model.finished)
        assertFalse(collector.model.exitCode == 0)
    }

    fun testScrollToTheEndIsRememberedAndFollowsOnlyFromTheEnd() {
        val properties = com.intellij.ide.util.PropertiesComponent.getInstance()
        properties.unsetValue(AnsibleRunView.SCROLL_TO_END_KEY)
        try {
            val view = view(null)
            assertTrue("on by default", view.scrollToEnd)
            view.scrollToEnd = false
            assertFalse("every run view", AnsibleRunView(project, collector, actions) { null }.scrollToEnd)
            assertFalse("the strip's scroll bar has a row of its own", view.stripScrollForTests().isOverlappingScrollBar)
        } finally {
            properties.unsetValue(AnsibleRunView.SCROLL_TO_END_KEY)
        }
        assertTrue("all rows fit", AnsibleRunView.atEnd(javax.swing.DefaultBoundedRangeModel(0, 300, 0, 300)))
        assertTrue("at the end", AnsibleRunView.atEnd(javax.swing.DefaultBoundedRangeModel(700, 300, 0, 1000)))
        assertFalse("scrolled up", AnsibleRunView.atEnd(javax.swing.DefaultBoundedRangeModel(200, 300, 0, 1000)))
    }

    fun testTheStripScrollBarDoesNotCoverTheChips() {
        for (i in 1..30) collector.accept(RunEvent.Play(i.toDouble(), "p$i", "A play with a long name $i", listOf("all"), null))
        collector.finish(0)
        val view = AnsibleRunView(project, collector, actions) { null }
        collector.drainForTests()
        view.component.setSize(400, 300)
        fun layout(container: java.awt.Container) {
            container.doLayout()
            container.components.filterIsInstance<java.awt.Container>().forEach(::layout)
        }
        layout(view.component)
        layout(view.component)
        val scroll = view.stripScrollForTests()
        val chips = view.strip.preferredSize.height
        val bar = scroll.horizontalScrollBar
        val numbers = "chips $chips, bar ${bar.y}+${bar.height} (preferred ${bar.preferredSize.height}), viewport ${scroll.viewport.y}+${scroll.viewport.height}, strip ${scroll.height}"
        assertTrue("30 chips overflow 400 px: $numbers", bar.isVisible)
        assertTrue("room for the scroll bar below the chips: $numbers", scroll.preferredSize.height >= chips + bar.preferredSize.height)
        assertTrue("the chips keep their height: $numbers", scroll.viewport.height >= chips)
        // Below the chips' row: in a row of its own, or (where the platform draws scroll bars over the content, as on
        // Linux or macOS with automatic scroll bars) over the room the strip keeps for it.
        assertTrue("the scroll bar is below the chips: $numbers", bar.y >= scroll.viewport.y + chips)

        view.component.setSize(100_000, 300)
        layout(view.component)
        layout(view.component)
        assertFalse("all chips fit", scroll.horizontalScrollBar.isVisible)
        assertEquals("no empty row for a scroll bar", view.strip.preferredSize.height, scroll.viewport.height)
    }

    fun testABatchShowsItsRolesWithTheirStages() {
        collector.accept(RunEvent.Units(0.0, listOf(RunEvent.UnitInfo("/r/web", "web", "falcon"), RunEvent.UnitInfo("/r/db", "db", "tern"), RunEvent.UnitInfo("/r/lb", "lb", "tern"))))
        collector.accept(RunEvent.UnitStart(1.0, "/r/web"))
        collector.accept(RunEvent.Stage(1.1, "default", "converge", null))
        collector.accept(RunEvent.Stage(1.5, "default", "converge", "Successful"))
        collector.accept(RunEvent.UnitEnd(2.0, "/r/web", 0))
        collector.accept(RunEvent.UnitStart(2.0, "/r/db"))
        val units = ArrayList<List<String>>()
        val stages = ArrayList<Triple<String, String, String?>>()
        val batch = object : RunViewActions by RunViewActions.NONE {
            override val playbookActions: Boolean get() = false
            override val stageActions: Boolean get() = true
            override val unitActions: Boolean get() = true
            override fun runUnits(keys: List<String>) { units += keys }
            override fun runStage(scenario: String, action: String, unit: String?) { stages += Triple(scenario, action, unit) }
        }
        val view = AnsibleRunView(project, collector, batch) { null }
        collector.drainForTests()
        val (web, db, lb) = view.children().map { it as UnitRun }
        assertTrue(view.rendered(web), view.rendered(web).startsWith("web  falcon   "))
        assertTrue(view.rendered(lb), view.rendered(lb).startsWith("lb  tern   Waiting"))
        assertFalse("a role that passed closes", view.tree.isExpanded(TreePath(view.treeModel.node(web)!!.path)))
        assertEquals("the strip shows the roles", listOf(web, db, lb), (0 until view.strip.model.size).map { view.strip.model.getElementAt(it) })

        collector.accept(RunEvent.UnitEnd(3.0, "/r/db", 2))
        collector.finish(1)
        collector.drainForTests()
        assertTrue(view.rendered(db), view.rendered(db).startsWith("db  tern   Exit code 2"))
        assertTrue(view.rendered(lb), view.rendered(lb).startsWith("lb  tern   Not run"))
        view.select(web)
        view.detailsComponentForTests().find(AbstractButton::class.java).first { it.text == "Run 'web' Again" }.doClick()
        view.select(web.stages.single())
        view.detailsComponentForTests().find(AbstractButton::class.java).first { it.text == "Run 'converge' Again" }.doClick()
        view.tree.clearSelection()
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        val summary = view.detailsComponentForTests()
        assertTrue(summary.find(javax.swing.JLabel::class.java).any { it.text.orEmpty() == "db (tern)" })
        summary.find(AbstractButton::class.java).first { it.text == "Rerun Failed Roles" }.doClick()
        assertEquals(listOf(listOf("/r/web"), listOf("/r/db")), units)
        assertEquals(listOf(Triple("default", "converge", "/r/web")), stages)
    }

    fun testAMoleculeRunGroupsItsPlaysByStage() {
        val transcript = Files.list(goldens.resolve("molecule")).use { files -> files.filter { it.toString().endsWith(".log") }.findFirst().get() }
        val lines = LineSplitter { line -> MoleculeLog.stage(line, 0.0)?.let(collector::accept) }
        val frames = RunEventFrames("golden", onText = lines::feed, onEvent = collector::accept)
        frames.filter(Files.readString(transcript))
        frames.flush()
        lines.flush()
        collector.finish(1)
        val reruns = ArrayList<Pair<String, String>>()
        val stageActions = object : RunViewActions by RunViewActions.NONE {
            override val playbookActions: Boolean get() = false
            override val stageActions: Boolean get() = true
            override fun runStage(scenario: String, action: String, unit: String?) { reruns += scenario to action }
        }
        val view = AnsibleRunView(project, collector, stageActions) { null }
        collector.drainForTests()
        val stages = view.children().filterIsInstance<StageRun>()
        assertTrue(stages.map { "${it.scenario} ${it.action}" }.containsAll(listOf("default converge", "volatile idempotence")))
        val idempotence = stages.first { it.scenario == "volatile" && it.action == "idempotence" }
        assertTrue(view.rendered(idempotence).startsWith("volatile \u203a idempotence   Failed"))
        assertFalse("plays sit under their stage", view.children().any { it is PlayRun })
        assertFalse("each stage's playbook has its own recap", RecapNode in view.children())
        assertEquals("the strip shows the stages", collector.model.stages, (0 until view.strip.model.size).map { view.strip.model.getElementAt(it) })
        val chip = view.strip.cellRenderer.getListCellRendererComponent(view.strip, idempotence, 0, false, false) as SimpleColoredComponent
        assertEquals("volatile \u203a idempotence  Failed", chip.getCharSequence(false).toString())
        view.strip.setSelectedValue(idempotence, false)
        assertSame(idempotence, (view.tree.selectionPath?.lastPathComponent as DefaultMutableTreeNode).userObject)
        view.select(idempotence)
        val details = view.detailsComponentForTests()
        val labels = details.find(javax.swing.JLabel::class.java).map { it.text.orEmpty() }
        assertTrue(labels.toString(), labels.any { "Always changes" in it })
        details.find(AbstractButton::class.java).first { it.text == "Run 'idempotence' Again" }.doClick()
        assertEquals(listOf("volatile" to "idempotence"), reruns)
        view.select(collector.model.plays.first().tasks.first())
        assertTrue("no playbook actions in a Molecule run", view.detailsComponentForTests().find(AbstractButton::class.java).none { it.text == "Start at This Task" })
    }

    fun testThePlaysTabSaysWhatTheRunPreparesAndWhyItDidNotStart() {
        var reported = 0
        val view = view(null, finish = null)
        view.onNoEvents = { reported++ }
        collector.accept(RunEvent.Preparing(1.0, "unlocking the vault ids of falcon"))
        collector.drainForTests()
        assertEquals("Preparing: unlocking the vault ids of falcon…", view.tree.emptyText.text)
        collector.accept(RunEvent.NotStarted(2.0, "The Compose service ansible-playbook mounts LOCAL_VAULT_FILE, which is not set.\nSet it under Compose variables"))
        collector.finish(-2)
        collector.drainForTests()
        assertEquals("the first line", "Not started: The Compose service ansible-playbook mounts LOCAL_VAULT_FILE, which is not set.", view.tree.emptyText.text)
        assertEquals("the Plays tab says it: no switch to the console", 0, reported)

        val started = RunEventCollector(testRootDisposable)
        val other = AnsibleRunView(project, started, actions) { null }
        started.accept(RunEvent.Preparing(1.0, "reading the playbook"))
        started.accept(RunEvent.Preparing(2.0, null))
        started.drainForTests()
        assertEquals("Waiting for the first play… (the Console tab shows the output)", other.tree.emptyText.text)
    }

    fun testABannerShowsInfoWarningsAndErrors() {
        val banner = RunBanner()
        val component = banner.component()
        assertFalse("hidden without a message", component.isVisible)
        banner.show("Preparing: reading the playbook…", emptyList(), RunBanner.Status.INFO)
        assertEquals(RunBanner.Status.INFO, banner.statusForTests())
        banner.show("Preparing: unlocking the vault ids of falcon…", emptyList(), RunBanner.Status.INFO)
        val info = component.find(com.intellij.ui.EditorNotificationPanel::class.java).single()
        assertEquals("the same status only changes the text", "Preparing: unlocking the vault ids of falcon…", info.text)
        banner.show("Not started: cancelled", emptyList(), RunBanner.Status.ERROR)
        assertEquals(RunBanner.Status.ERROR, banner.statusForTests())
        val error = component.find(com.intellij.ui.EditorNotificationPanel::class.java).single()
        assertNotSame("another status builds another panel", info, error)
        assertEquals("Not started: cancelled", error.text)
        banner.show("web \u203a default: the instances are destroyed in 2:00", listOf(BannerAction("Keep Instances") {}))
        assertEquals("a countdown warns, as before", RunBanner.Status.WARNING, banner.statusForTests())
        banner.hide()
        assertNull(banner.statusForTests())
        assertFalse(component.isVisible)
    }

    fun testABatchRowSaysWhatItsRolePrepares() {
        collector.accept(RunEvent.Units(0.0, listOf(RunEvent.UnitInfo("/r/web", "web", "falcon"), RunEvent.UnitInfo("/r/db", "db", "tern"))))
        collector.accept(RunEvent.UnitStart(1.0, "/r/web"))
        collector.accept(RunEvent.Preparing(1.1, "reading the role, its scenarios and its Molecule service"))
        val view = AnsibleRunView(project, collector, actions) { null }
        collector.drainForTests()
        val web = view.children().first() as UnitRun
        assertTrue(view.rendered(web), view.rendered(web).startsWith("web  falcon   Preparing: reading the role, its scenarios and its Molecule service…"))
        val chip = view.strip.cellRenderer.getListCellRendererComponent(view.strip, web, 0, false, false) as SimpleColoredComponent
        assertTrue(chip.getCharSequence(false).toString(), "Preparing: reading the role" in chip.getCharSequence(false).toString())
        collector.accept(RunEvent.Preparing(2.0, null))
        collector.drainForTests()
        assertFalse(view.rendered(web), "Preparing" in view.rendered(web))
    }

    fun testAMoleculeTaskThatFailedForWantOfVaultSecretsSaysWhy() {
        val error = "Attempting to decrypt but no vault secrets found"
        fun events(stage: Boolean) {
            if (stage) collector.accept(RunEvent.Stage(0.5, "default", "converge", null))
            collector.accept(RunEvent.Play(1.0, "p", "Converge", listOf("instance"), null))
            collector.accept(RunEvent.Task(2.0, "t", "p", "web : Read the password", "web", "debug", null, handler = false, loop = false, tags = emptyList()))
            collector.accept(RunEvent.Result(3.0, false, "t", "instance", HostStatus.FAILED, false, null, null, null, null, error, emptyList(), null, false))
            collector.finish(2)
        }
        events(stage = true)
        val view = AnsibleRunView(project, collector, actions) { null }
        collector.drainForTests()
        val task = collector.model.plays.single().tasks.single()
        fun labels() = view.detailsComponentForTests().find(javax.swing.JLabel::class.java).map { it.text.orEmpty() }
        view.select(task)
        assertTrue(labels().toString(), "Molecule runs pass no vault secrets." in labels())
        view.select(task.hosts.getValue("instance"))
        assertTrue("the host's details too", "Molecule runs pass no vault secrets." in labels())
        assertTrue(collector.model.missingVaultSecrets)

        // A playbook run passes the unlocked ids: the same error needs no Molecule hint.
        collector = RunEventCollector(testRootDisposable)
        events(stage = false)
        val playbook = AnsibleRunView(project, collector, actions) { null }
        collector.drainForTests()
        playbook.select(collector.model.plays.single().tasks.single())
        assertFalse(playbook.detailsComponentForTests().find(javax.swing.JLabel::class.java).any { it.text == "Molecule runs pass no vault secrets." })
    }
}
