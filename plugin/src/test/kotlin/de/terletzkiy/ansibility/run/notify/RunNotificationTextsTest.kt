package de.terletzkiy.ansibility.run.notify

import com.intellij.notification.NotificationType
import de.terletzkiy.ansibility.run.events.HostStats
import de.terletzkiy.ansibility.run.events.HostStatus
import de.terletzkiy.ansibility.run.events.RunEvent
import de.terletzkiy.ansibility.run.events.RunEventDecoder
import de.terletzkiy.ansibility.run.events.RunModel
import de.terletzkiy.ansibility.run.molecule.MoleculeCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * What a run's end notification says (plan amendment R19, D148): playbook runs from the run-event goldens, Molecule
 * runs, bulk runs and runs that did not start; names cut and lists capped; nothing of a result's values.
 */
class RunNotificationTextsTest {
    private val goldens: Path = Path.of("src/test/testData/run-events")

    private fun golden(case: String, exitCode: Int = 0): RunModel = RunModel().also { model ->
        Files.readAllLines(goldens.resolve(case).resolve("2.21.4.jsonl")).forEach { line -> RunEventDecoder.decode(line)?.let(model::apply) }
        model.finish(exitCode)
    }

    private fun playbook(model: RunModel?, exitCode: Int = 0, stopped: Boolean = false, seconds: Double? = 134.0) =
        RunNotificationTexts.of(RunOutcomes.playbook("site.yml [staging]", model, exitCode, stopped, seconds))

    // ------------------------------------------------------------------ a small event language for models

    private var time = 0.0
    private val model = RunModel()

    private fun event(event: (Double) -> RunEvent) = model.apply(event(++time))

    private fun start(check: Boolean = false, limit: String? = null) = event { RunEvent.Start(it, "site.yml", "2.21.4", check, false, limit, listOf("all"), emptyList()) }

    private fun play(name: String, id: String = name) = event { RunEvent.Play(it, id, name, listOf("all"), null) }

    private fun task(name: String, id: String = name, role: String? = null) =
        event { RunEvent.Task(it, id, null, if (role == null) name else "$role : $name", role, "ansible.builtin.command", null, false, false, emptyList()) }

    private fun hostStart(task: String, host: String) = event { RunEvent.HostStart(it, task, host) }

    private fun result(task: String, host: String, status: HostStatus, message: String? = null, result: Map<String, Any?>? = null, item: String? = null) =
        event { RunEvent.Result(it, item != null, task, host, status, status == HostStatus.CHANGED, item, null, 0.1, null, message, emptyList(), result, false) }

    private fun stats(vararg hosts: Pair<String, HostStats>) = event { RunEvent.Stats(it, mapOf(*hosts)) }

    private fun stage(scenario: String, action: String, result: String? = null) = event { RunEvent.Stage(it, scenario, action, result) }

    private fun ok(failures: Int = 0, unreachable: Int = 0) = HostStats(1, 0, unreachable, failures, 0, 0, 0)

    // ------------------------------------------------------------------ playbook runs

    @Test
    fun `a passed playbook run says how many hosts and results and how long`() {
        val text = playbook(golden("basic"))
        assertEquals("site.yml [staging]: passed", text.title)
        assertEquals(RunNotificationKind.PASSED, text.kind)
        assertEquals(NotificationType.INFORMATION, text.kind.type)
        assertEquals(listOf("2 hosts · 24 ok · 4 changed · 2 failed · 4 ignored · 2 skipped · 2m 14s"), text.lines)
        assertEquals("the system notification has the counts", text.lines.single(), text.summary)
    }

    @Test
    fun `a check run says what would change`() {
        val text = playbook(golden("check"), seconds = 3.0)
        assertEquals("site.yml [staging]: check passed", text.title)
        assertEquals(listOf("Check mode · 1 host · 2 would change · 3.0 s"), text.lines)
    }

    @Test
    fun `a failed run names each failed host with the task where it failed`() {
        val text = playbook(golden("failure", 4), 4)
        assertEquals("site.yml [staging]: failed on 3 of 3 hosts", text.title)
        assertEquals(RunNotificationKind.FAILED, text.kind)
        assertEquals(NotificationType.ERROR, text.kind.type)
        assertEquals(
            listOf(
                "a1 failed at Fails everywhere",
                "a2 failed at Fails on a2 only",
                "down.invalid unreachable at Fails on a2 only",
                "no host ok · 2m 14s",
            ),
            text.lines,
        )
        assertEquals("counts only: no host or task name leaves the IDE", "3 hosts failed · no host ok · 2m 14s", text.summary)
    }

    @Test
    fun `more than three failures end in and N more and the role prefix stays`() {
        start()
        play("Web")
        task("Reload nginx", "t1", role = "nginx")
        val hosts = listOf("heron-1", "heron-2", "wren-1", "wren-2", "tern-1", "falcon-1")
        hosts.forEach { result("t1", it, if (it == "tern-1") HostStatus.OK else HostStatus.FAILED) }
        stats(*hosts.map { it to ok(failures = if (it == "tern-1") 0 else 1) }.toTypedArray())
        model.finish(2)
        val text = playbook(model, 2)
        assertEquals("site.yml [staging]: failed on 5 of 6 hosts", text.title)
        assertEquals(
            listOf("heron-1 failed at nginx : Reload nginx", "heron-2 failed at nginx : Reload nginx", "wren-1 failed at nginx : Reload nginx", "and 2 more", "1 host ok · 2m 14s"),
            text.lines,
        )
    }

    @Test
    fun `a play that matched no host and a run where nothing ran`() {
        val some = playbook(golden("imports"))
        assertEquals("site.yml [staging]: passed", some.title)
        assertEquals("Play \"Nothing matches\" matched no host", some.lines[1])

        start(limit = "heron-9")
        play("Web servers")
        event { RunEvent.NoHosts(it, "Web servers", false) }
        stats()
        model.finish(0)
        val none = playbook(model)
        assertEquals("site.yml [staging]: no hosts matched", none.title)
        assertEquals(listOf("Play \"Web servers\" matched no host (limit heron-9); nothing ran"), none.lines)
        assertEquals(RunNotificationKind.NOTHING_RAN, none.kind)
        assertEquals(NotificationType.WARNING, none.kind.type)
    }

    @Test
    fun `an error before any play and after a play keeps the exit code as a number`() {
        start()
        model.finish(4)
        val before = playbook(model, 4, seconds = 3.0)
        assertEquals("site.yml [staging]: failed before any play ran", before.title)
        assertEquals(listOf("ansible-playbook exited with code 4; the console shows the error · 3.0 s"), before.lines)
        assertEquals("exit code 4 · 3.0 s", before.summary)

        val after = RunModel().apply {
            apply(RunEvent.Start(1.0, "site.yml", null, false, false, null, emptyList(), emptyList()))
            apply(RunEvent.Play(2.0, "p", "Web servers", listOf("web"), null))
            finish(1)
        }
        val error = playbook(after, 1, seconds = 3.0)
        assertEquals("site.yml [staging]: ended with an error", error.title)
        assertEquals(listOf("ansible-playbook exited with code 1 after play \"Web servers\"; the console shows the error · 3.0 s"), error.lines)
    }

    @Test
    fun `a run with the view on that failed before its first event failed before any play ran`() {
        // R19 integration fix: ansible-playbook sends its first event only after loading the playbook and matching the
        // hosts, so a syntax error (exit code 4) or a limit that matches no host (exit code 1) ends without any.
        val noView = "the console shows why (with \"Show runs as plays, tasks and hosts\" on in the Runner settings, this names the failed hosts)"
        for (exitCode in listOf(4, 1)) {
            val text = playbook(RunModel().apply { finish(exitCode) }, exitCode, seconds = 3.0)
            assertEquals("site.yml [staging]: failed before any play ran", text.title)
            assertEquals(listOf("ansible-playbook exited with code $exitCode; the console shows the error · 3.0 s"), text.lines)
            assertEquals("exit code $exitCode · 3.0 s", text.summary)
            assertEquals(RunNotificationKind.FAILED, text.kind)
            assertFalse("the view is on: no hint to turn it on", text.lines.any { noView in it })
        }
        // The view off: only the exit code is known, and the hint stays.
        val off = playbook(null, 4, seconds = 3.0)
        assertEquals("site.yml [staging]: failed (exit code 4)", off.title)
        assertEquals(listOf("3.0 s · $noView"), off.lines)
        // No event and exit code 0, with the view on or off: passed.
        assertEquals("site.yml [staging]: passed", playbook(RunModel().apply { finish(0) }, 0, seconds = 3.0).title)
        assertEquals("site.yml [staging]: passed", playbook(null, 0, seconds = 3.0).title)
        // Stopped before the first event: stopped, view on or off.
        assertEquals("site.yml [staging]: stopped", playbook(RunModel().apply { finish(-1) }, -1, stopped = true, seconds = 3.0).title)
    }

    @Test
    fun `a stopped run says where it stopped and what ran so far`() {
        start()
        play("Web")
        task("Install nginx", "t1", role = "nginx")
        result("t1", "heron-1", HostStatus.OK)
        result("t1", "heron-2", HostStatus.CHANGED)
        task("Reload nginx", "t2", role = "nginx")
        hostStart("t2", "heron-1")
        model.finish(-1)
        val text = playbook(model, -1, stopped = true, seconds = 62.0)
        assertEquals("site.yml [staging]: stopped", text.title)
        assertEquals(listOf("Stopped after 1m 2s in \"nginx : Reload nginx\"", "1 ok · 1 changed · 1 running so far"), text.lines)
        assertEquals(RunNotificationKind.STOPPED, text.kind)
        assertEquals(NotificationType.WARNING, text.kind.type)
        assertEquals("1 ok · 1 changed · 1 running so far · 1m 2s", text.summary)
    }

    @Test
    fun `a run without the run view knows only its exit code`() {
        val failed = playbook(null, 2)
        assertEquals("site.yml [staging]: failed (exit code 2)", failed.title)
        assertTrue(failed.lines.single(), failed.lines.single().startsWith("2m 14s · the console shows why"))
        val passed = playbook(RunModel().apply { finish(0) }, 0)
        assertEquals("site.yml [staging]: passed", passed.title)
        assertEquals(listOf("2m 14s"), passed.lines)
    }

    // ------------------------------------------------------------------ Molecule runs

    private fun molecule(
        command: MoleculeCommand,
        exitCode: Int,
        subject: String = "web › default",
        stopped: Boolean = false,
        countdown: Int? = null,
        destroyFollows: Boolean = false,
        automatic: Boolean = false,
    ) = RunNotificationTexts.of(RunOutcomes.molecule(subject, command, model, exitCode, stopped, 250.0, countdown, destroyFollows, automatic))

    /** A stage of [scenario] that runs [tasks] (name to status per host) and ends with [result]. */
    private fun stageRun(scenario: String, action: String, result: String?, vararg tasks: Pair<String, Map<String, HostStatus>>, message: String? = null) {
        stage(scenario, action)
        start()
        play(action, "$scenario-$action")
        for ((name, hosts) in tasks) {
            task(name, "$scenario-$action-$name")
            hosts.forEach { (host, status) -> result("$scenario-$action-$name", host, status, message = message?.takeIf { status.isFailure }) }
        }
        if (result != null) stage(scenario, action, result)
    }

    @Test
    fun `a Molecule test that passed counts its stages`() {
        stageRun("default", "converge", "Successful", "Install packages" to mapOf("instance-1" to HostStatus.CHANGED))
        stageRun("default", "idempotence", "Successful", "Install packages" to mapOf("instance-1" to HostStatus.OK))
        stageRun("default", "verify", "Successful", "Check the site answers" to mapOf("instance-1" to HostStatus.OK))
        model.finish(0)
        val text = molecule(MoleculeCommand.TEST, 0)
        assertEquals("Molecule test passed: web › default", text.title)
        assertEquals(listOf("3 stages passed · 2 ok · 1 changed · 4m 10s"), text.lines)
        assertEquals(RunNotificationKind.PASSED, text.kind)
    }

    @Test
    fun `a Molecule test that failed at verify names the task and the instance`() {
        stageRun("default", "converge", "Successful", "Install packages" to mapOf("instance-1" to HostStatus.CHANGED))
        stageRun("default", "idempotence", "Successful", "Install packages" to mapOf("instance-1" to HostStatus.OK))
        stageRun("default", "verify", "Failed", "Check the site answers" to mapOf("instance-1" to HostStatus.FAILED, "instance-2" to HostStatus.OK))
        model.finish(1)
        val text = molecule(MoleculeCommand.TEST, 1)
        assertEquals("Molecule test failed: web › default", text.title)
        assertEquals(listOf("verify failed: \"Check the site answers\" on instance-1", "2 stages passed · 4m 10s"), text.lines)
        assertEquals("counts only", "1 stage failed · 2 stages passed · 4m 10s", text.summary)
    }

    @Test
    fun `idempotence names the tasks that changed on the second run`() {
        stageRun("default", "converge", "Successful", "Write config" to mapOf("instance-1" to HostStatus.CHANGED))
        stageRun(
            "default", "idempotence", "Failed",
            "Write config" to mapOf("instance-1" to HostStatus.CHANGED),
            "Restart service" to mapOf("instance-1" to HostStatus.CHANGED),
            "Stays" to mapOf("instance-1" to HostStatus.OK),
        )
        model.finish(1)
        val text = molecule(MoleculeCommand.TEST, 1)
        assertEquals("idempotence failed: 2 tasks changed on the second run: \"Write config\", \"Restart service\"", text.lines.first())
    }

    @Test
    fun `a run of all scenarios says which scenario failed where`() {
        stageRun("default", "converge", "Successful", "Install packages" to mapOf("instance-1" to HostStatus.OK))
        stageRun("volatile", "converge", "Successful", "Install packages" to mapOf("instance-1" to HostStatus.OK))
        stageRun("volatile", "idempotence", "Failed", "Install packages" to mapOf("instance-1" to HostStatus.CHANGED))
        model.finish(1)
        val text = molecule(MoleculeCommand.TEST, 1, subject = "web (all scenarios)")
        assertEquals("Molecule test failed: web (all scenarios)", text.title)
        assertEquals(listOf("volatile › idempotence failed: 1 task changed on the second run: \"Install packages\"", "default passed · 4m 10s"), text.lines)
        assertFalse("no scenario name in the system notification", "default" in text.summary || "volatile" in text.summary)
    }

    @Test
    fun `a converge says when its instances are destroyed`() {
        stageRun("default", "converge", "Successful", "Install packages" to mapOf("instance-1" to HostStatus.CHANGED, "instance-2" to HostStatus.OK))
        model.finish(0)
        val passed = molecule(MoleculeCommand.CONVERGE, 0, countdown = 2)
        assertEquals("Molecule converge passed: web › default", passed.title)
        assertEquals(listOf("1 ok · 1 changed · 4m 10s", "The instances are destroyed in 2 min unless you keep them."), passed.lines)
    }

    @Test
    fun `stopped runs say where they stopped and what cleanup follows`() {
        stageRun("default", "converge", null, "Install packages" to mapOf("instance-1" to HostStatus.RUNNING))
        model.finish(-1)
        val test = molecule(MoleculeCommand.TEST, -1, stopped = true, destroyFollows = true)
        assertEquals("Molecule test stopped: web › default", test.title)
        assertEquals(listOf("Stopped during converge after 4m 10s; its instances are destroyed in a run of their own."), test.lines)
        assertEquals(RunNotificationKind.STOPPED, test.kind)
        val converge = molecule(MoleculeCommand.CONVERGE, -1, stopped = true, countdown = 5)
        assertEquals(listOf("Stopped during converge after 4m 10s", "The instances are destroyed in 5 min unless you keep them."), converge.lines)
    }

    @Test
    fun `a destroy says whether the instances are gone`() {
        model.finish(0)
        val gone = molecule(MoleculeCommand.DESTROY, 0)
        assertEquals("Molecule destroy finished: web › default", gone.title)
        assertEquals(listOf("The instances are gone · 4m 10s"), gone.lines)
        val failed = molecule(MoleculeCommand.DESTROY, 1, automatic = true)
        assertEquals("Molecule destroy failed: web › default", failed.title)
        assertEquals(listOf("molecule destroy exited with code 1; the instances may still be running (see the console)"), failed.lines)
        assertEquals(RunNotificationKind.FAILED, failed.kind)
    }

    @Test
    fun `a failure for want of vault secrets says that Molecule runs pass none, without the message`() {
        val message = "Attempting to decrypt but no vault secrets found"
        stageRun("default", "converge", "Failed", "Read the secret" to mapOf("instance-1" to HostStatus.FAILED), message = message)
        model.finish(1)
        val text = molecule(MoleculeCommand.CONVERGE, 1)
        assertEquals(listOf("converge failed: \"Read the secret\" on instance-1", "4m 10s", "Molecule runs pass no vault secrets."), text.lines)
        assertFalse(text.htmlContent.contains("decrypt"))

        // A passed run says nothing of it.
        val passed = RunModel()
        passed.apply(RunEvent.Stage(1.0, "default", "converge", null))
        passed.apply(RunEvent.Stage(2.0, "default", "converge", "Successful"))
        passed.finish(0)
        val ok = RunNotificationTexts.of(RunOutcomes.molecule("web", MoleculeCommand.CONVERGE, passed, 0, false, 1.0))
        assertFalse(ok.lines.toString(), ok.lines.any { "vault" in it })
    }

    @Test
    fun `a failure outside any stage gives the exit code`() {
        model.finish(1)
        val text = molecule(MoleculeCommand.VERIFY, 1)
        assertEquals("Molecule verify failed: web › default", text.title)
        assertEquals(listOf("molecule exited with code 1; the console shows why", "4m 10s"), text.lines)
        assertEquals("exit code 1", RunNotificationTexts.of(RunOutcomes.molecule("web", MoleculeCommand.VERIFY, model, 1, false, null)).summary)
    }

    // ------------------------------------------------------------------ bulk runs

    private fun units(vararg roles: String) = event { RunEvent.Units(it, roles.map { role -> RunEvent.UnitInfo("/roles/$role", role, "falcon") }) }

    private fun unit(role: String, exitCode: Int, problem: String? = null, block: () -> Unit = {}) {
        event { RunEvent.UnitStart(it, "/roles/$role") }
        block()
        event { RunEvent.UnitEnd(it, "/roles/$role", exitCode, problem) }
    }

    @Test
    fun `a bulk run that passed names its roles`() {
        units("web", "db", "cache", "lb", "dns")
        listOf("web", "db", "cache", "lb", "dns").forEach { unit(it, 0) }
        model.finish(0)
        val text = RunNotificationTexts.of(RunOutcomes.batch(model, stopped = false, destroyed = null))
        assertEquals("Molecule tests passed: 5 roles", text.title)
        assertEquals(listOf("web, db, cache and 2 more · 9.0 s"), text.lines)
        assertEquals("5 passed · 0 failed · 0 not run · 9.0 s", text.summary)
    }

    @Test
    fun `a bulk run with failures lists the failed roles and why one did not start`() {
        units("web", "db", "lb", "dns")
        unit("web", 2) { stageRun("default", "verify", "Failed", "Check" to mapOf("instance-1" to HostStatus.FAILED)) }
        unit("db", RunModel.NOT_STARTED, "No Docker Compose service with Molecule mounts the role db\nsecond line")
        unit("lb", 0)
        unit("dns", 3)
        model.finish(1)
        val text = RunNotificationTexts.of(RunOutcomes.batch(model, stopped = false, destroyed = null))
        assertEquals("Molecule tests: 3 of 4 roles failed", text.title)
        assertEquals(
            listOf(
                "web (falcon): verify failed",
                "db (falcon): not started: No Docker Compose service with Molecule mounts the role db",
                "dns (falcon): exit code 3",
                "1 passed · ${text.lines.last().substringAfter(" · ")}",
            ),
            text.lines,
        )
        assertEquals(RunNotificationKind.FAILED, text.kind)
        assertFalse("the reason stays in the IDE", "Docker" in text.summary)
        assertFalse(text.lines.any { "vault" in it })
    }

    @Test
    fun `a bulk run whose role failed for want of vault secrets says that Molecule runs pass none`() {
        units("web", "db")
        unit("web", 2) {
            stageRun("default", "converge", "Failed", "Read the secret" to mapOf("instance-1" to HostStatus.FAILED), message = "Attempting to decrypt but no vault secrets found")
        }
        unit("db", 0)
        model.finish(1)
        val text = RunNotificationTexts.of(RunOutcomes.batch(model, stopped = false, destroyed = null))
        assertEquals(listOf("web (falcon): converge failed", "Molecule runs pass no vault secrets."), text.lines.take(2))
        assertFalse(text.htmlContent.contains("decrypt"))
    }

    @Test
    fun `a stopped bulk run says where it stopped and how many roles started`() {
        units("web", "dns", "db", "lb")
        unit("web", 0)
        unit("dns", RunModel.NOT_STARTED, "No Docker Compose service with Molecule mounts the role dns")
        unit("db", RunModel.STOPPED)
        model.finish(1)
        val stopped = RunNotificationTexts.of(RunOutcomes.batch(model, stopped = true, destroyed = "/roles/db"))
        // As the console counts them ("Stopped: 3 of 4 roles started, 1 failed."): the stopped role and the one that did not start too.
        assertEquals("Molecule tests stopped: 3 of 4 roles started", stopped.title)
        assertEquals("Stopped during db (falcon); its instances are destroyed in a run of their own", stopped.lines.first())
        assertEquals("dns (falcon): not started: No Docker Compose service with Molecule mounts the role dns", stopped.lines[1])
        assertTrue("the stopped role is no role that did not run: ${stopped.lines.last()}", stopped.lines.last().startsWith("1 passed · 1 failed · 1 stopped · 1 not run"))
        assertTrue(stopped.summary, stopped.summary.startsWith("1 passed · 1 failed · 1 stopped · 1 not run"))
        assertEquals(RunNotificationKind.STOPPED, stopped.kind)
        val preparing = RunNotificationTexts.of(RunOutcomes.batch(model, stopped = true, destroyed = null))
        assertEquals("Stopped during db (falcon)", preparing.lines.first())
    }

    @Test
    fun `a bulk role that ran several scenarios names the failed stage, each name cut on its own`() {
        val scenario = "s".repeat(RunNotificationTexts.NAME_LIMIT + 10)
        units("web")
        unit("web", 2) {
            stageRun("default", "converge", "Successful", "Install packages" to mapOf("instance-1" to HostStatus.OK))
            stageRun(scenario, "idempotence", "Failed", "Install packages" to mapOf("instance-1" to HostStatus.CHANGED))
        }
        model.finish(1)
        val text = RunNotificationTexts.of(RunOutcomes.batch(model, stopped = false, destroyed = null))
        assertEquals("web (falcon): ${"s".repeat(RunNotificationTexts.NAME_LIMIT)}… › idempotence failed", text.lines.first())
    }

    // ------------------------------------------------------------------ not started, names, secrecy

    @Test
    fun `a run that did not start gives the first line of the reason`() {
        val playbook = RunNotificationTexts.of(RunOutcome.NotStarted("site.yml [staging]", null, "No docker executable found on PATH\nmore"))
        assertEquals("site.yml [staging]: did not start", playbook.title)
        assertEquals(listOf("No docker executable found on PATH"), playbook.lines)
        assertEquals(RunNotificationKind.NOT_STARTED, playbook.kind)
        assertEquals(NotificationType.ERROR, playbook.kind.type)
        assertFalse("the reason stays in the IDE", "docker" in playbook.summary)
        val converge = RunNotificationTexts.of(RunOutcome.NotStarted("web › default", MoleculeCommand.CONVERGE, "The role web has no Molecule scenario nope"))
        assertEquals("Molecule converge did not start: web › default", converge.title)
        assertNull(RunOutcome.NotStarted("x", null, "y").seconds)
    }

    @Test
    fun `names are cut and escaped`() {
        start()
        play("Web")
        val long = "Configure ".repeat(12).trim()
        task(long, "t1")
        task("Breaks <b>bold</b> & more", "t2")
        result("t1", "heron-1", HostStatus.FAILED)
        result("t2", "heron-2", HostStatus.FAILED)
        stats("heron-1" to ok(failures = 1), "heron-2" to ok(failures = 1))
        model.finish(2)
        val text = playbook(model, 2)
        assertEquals("heron-1 failed at " + long.take(RunNotificationTexts.NAME_LIMIT) + "…", text.lines[0])
        assertTrue(text.htmlContent, "heron-2 failed at Breaks &lt;b&gt;bold&lt;/b&gt; &amp; more" in text.htmlContent)
        assertFalse(text.htmlContent, "<b>" in text.htmlContent)
        val title = RunNotificationTexts.of(RunOutcomes.playbook("<site>.yml", model, 2, false, 1.0))
        assertEquals("&lt;site&gt;.yml: failed on 2 of 2 hosts", title.htmlTitle)
        assertEquals(listOf("a", "b", "c", "and 1 more"), RunNotificationTexts.capped(listOf("a", "b", "c", "d")))
        assertEquals("a, b, c", RunNotificationTexts.inline(listOf("a", "b", "c")))
    }

    @Test
    fun `no message, result or item label ever reaches a notification`() {
        val sentinel = "SENTINEL-4d1f"
        start()
        play("Web")
        task("Uses a secret", "t1")
        result("t1", "heron-1", HostStatus.FAILED, message = "password=$sentinel", result = mapOf("stdout" to sentinel, "msg" to sentinel))
        result("t1", "heron-2", HostStatus.FAILED, message = sentinel, item = "item-$sentinel")
        result("t1", "heron-2", HostStatus.FAILED, message = sentinel, result = mapOf("x" to sentinel))
        stats("heron-1" to ok(failures = 1), "heron-2" to ok(failures = 1))
        model.finish(2)
        val texts = listOf(
            playbook(model, 2),
            playbook(model, 2, stopped = true),
            RunNotificationTexts.of(RunOutcomes.molecule("web", MoleculeCommand.CONVERGE, model, 2, false, 1.0)),
        )
        for (text in texts) {
            for (part in listOf(text.title, text.htmlTitle, text.htmlContent, text.summary) + text.lines) assertFalse(part, sentinel in part)
        }
        assertTrue("the failed task is named", "Uses a secret" in texts[0].htmlContent)
    }
}
