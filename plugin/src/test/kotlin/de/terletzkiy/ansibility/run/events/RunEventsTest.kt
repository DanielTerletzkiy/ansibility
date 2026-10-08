package de.terletzkiy.ansibility.run.events

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/** The event stream of the Ansibility callback: frames on stderr, decoding, and the run model built from the goldens. */
class RunEventsTest {
    private val goldens: Path = Path.of("src/test/testData/run-events")
    private val versions = listOf("2.18.8", "2.21.4")

    private fun model(case: String, version: String): RunModel = RunModel().also { model ->
        Files.readAllLines(goldens.resolve(case).resolve("$version.jsonl")).forEach { line -> RunEventDecoder.decode(line)?.let(model::apply) }
        model.finish(0)
    }

    // ------------------------------------------------------------------ frames

    @Test
    fun `frames are taken out of stderr, whatever the chunks`() {
        val events = ArrayList<RunEvent>()
        val frames = RunEventFrames("tok") { events += it }
        val stream = "[WARNING]: something\n\u001etok {\"e\":\"play\",\"id\":\"p\",\"name\":\"Web\"}\nplain\n\u001etok {\"e\":\"stats\",\"hosts\":{}}\n"
        for (size in listOf(1, 3, 7, stream.length)) {
            events.clear()
            val console = stream.chunked(size).joinToString("") { frames.filter(it) } + frames.flush()
            assertEquals("chunks of $size", "[WARNING]: something\nplain\n", console)
            assertEquals(listOf("Web"), events.filterIsInstance<RunEvent.Play>().map { it.name })
            assertEquals(1, events.filterIsInstance<RunEvent.Stats>().size)
        }
    }

    @Test
    fun `the text around the frames is read in order with their events`() {
        val seen = ArrayList<String>()
        val frames = RunEventFrames("tok", onText = { seen += "text $it" }) { seen += "event ${(it as RunEvent.Play).name}" }
        frames.filter("before\n\u001etok {\"e\":\"play\",\"id\":\"a\",\"name\":\"A\"}\nbetween\n\u001etok {\"e\":\"play\",")
        frames.filter("\"id\":\"b\",\"name\":\"B\"}\nafter")
        frames.filter("\u001etok")
        assertEquals("\u001etok", frames.flush())
        assertEquals(listOf("text before\n", "event A", "text between\n", "event B", "text after", "text \u001etok"), seen)
    }

    @Test
    fun `other tokens, broken json and text before a frame are handled`() {
        val events = ArrayList<RunEvent>()
        val frames = RunEventFrames("tok") { events += it }
        assertEquals("\u001eother {\"e\":\"play\",\"id\":\"x\"}\n", frames.filter("\u001eother {\"e\":\"play\",\"id\":\"x\"}\n"))
        assertEquals("", frames.filter("\u001etok {not json\n"))
        assertEquals("partial ", frames.filter("partial \u001etok {\"e\":\"play\",\"id\":\"p\",\"name\":\"P\"}\r\n"))
        assertEquals("tail without newline", frames.filter("tail without newline"))
        assertEquals("\u001eno", frames.filter("\u001eno"))
        assertEquals("", frames.filter("\u001etok {\"e\":\"stats\""))
        assertEquals("a frame never finished is dropped", "", frames.flush())
        assertEquals(listOf("P"), events.filterIsInstance<RunEvent.Play>().map { it.name })
        assertTrue(events.none { it is RunEvent.Stats })
    }

    @Test
    fun `events decode with defaults and unknown kinds survive`() {
        assertNull(RunEventDecoder.decode("[]"))
        assertNull(RunEventDecoder.decode("{\"x\":1}"))
        assertNull("a play needs its id", RunEventDecoder.decode("{\"e\":\"play\"}"))
        assertEquals(RunEvent.Unknown(1.5, "future"), RunEventDecoder.decode("{\"e\":\"future\",\"t\":1.5}"))
        val result = RunEventDecoder.decode(
            "{\"e\":\"item\",\"task\":\"t\",\"host\":\"h\",\"status\":\"changed\",\"item\":{\"name\":\"a\"},\"diff\":[{\"before\":\"1\",\"after\":\"2\"},3]}",
        ) as RunEvent.Result
        assertTrue(result.item)
        assertEquals(HostStatus.CHANGED, result.status)
        assertEquals("{\"name\":\"a\"}", result.itemLabel)
        assertEquals(listOf(FileDiff(null, null, "1", "2", null)), result.diff)
        assertEquals(HostStatus.RUNNING, HostStatus.of("weird"))
    }

    // ------------------------------------------------------------------ goldens

    @Test
    fun `a full run builds plays, tasks, hosts and items alike for both versions`() {
        for (version in versions) {
            val model = model("basic", version)
            assertEquals(version, model.start?.ansible)
            assertTrue(model.start!!.diff)
            val play = model.plays.single()
            assertEquals("Web", play.name)
            val tasks = play.tasks.associateBy { it.shortName }
            assertEquals("web", tasks.getValue("Write the site file").role)
            assertTrue(tasks.getValue("Write the site file").path!!.endsWith("roles/web/tasks/main.yml:3"))
            assertEquals(HostStatus.CHANGED, tasks.getValue("Write the site file").status)
            assertEquals(listOf("h1", "h2"), tasks.getValue("Write the site file").hosts.keys.sorted())
            assertTrue(tasks.getValue("Write the site file").hosts.getValue("h1").diff.any { it.after?.contains("hello h1") == true })

            val loop = tasks.getValue("Loop over packages").hosts.getValue("h1")
            assertEquals(listOf("nginx", "certbot"), loop.items.map { it.label })
            val mixed = tasks.getValue("Loop with skipped and failed items").hosts.getValue("h1").items
            assertEquals(listOf(HostStatus.OK, HostStatus.SKIPPED, HostStatus.FAILED), mixed.map { it.status })

            val secret = tasks.getValue("Secret output").hosts.getValue("h1").result!!
            assertTrue("no_log is censored", "censored" in secret && "msg" !in secret)
            @Suppress("UNCHECKED_CAST")
            val account = ((tasks.getValue("Credentials in a result").hosts.getValue("h1").result!!["ansible_facts"] as Map<String, Any?>)["service_account"]) as Map<String, Any?>
            assertEquals(mapOf("user" to "deploy", "password" to "********", "api_token" to "********"), account)

            assertEquals(HostStatus.SKIPPED, tasks.getValue("Skipped on purpose").status)
            assertEquals(HostStatus.IGNORED, tasks.getValue("Fails but is ignored").status)
            assertEquals(1, tasks.getValue("Retries until the second attempt").hosts.getValue("h1").retries)
            assertTrue(tasks.getValue("Runs a little while").hosts.values.all { it.polls > 0 })
            assertEquals("h1", tasks.getValue("Runs on the first host for everyone").hosts.getValue("h2").delegatedTo)
            assertEquals(HostStatus.FAILED, tasks.getValue("Breaks").status)
            assertEquals(HostStatus.OK, tasks.getValue("Recovers").status)
            assertTrue(tasks.getValue("A very large result").hosts.getValue("h1").truncated)
            val long = tasks.getValue("Prints a long output").hosts.getValue("h1").result!!["stdout"] as String
            assertTrue("long output is capped", long.length < 70_000 && long.endsWith("more characters]"))
            assertFalse("stdout_lines is dropped next to stdout", "stdout_lines" in tasks.getValue("Prints a long output").hosts.getValue("h1").result!!)
            assertEquals(listOf("h1", "h2"), tasks.getValue("Included task").hosts.keys.sorted())
            assertTrue(play.tasks.any { it.handler && it.shortName == "Reload site" })
            assertEquals(listOf("web : Reload site" to "h1", "web : Reload site" to "h2"), play.notified.sortedBy { it.second })
            assertEquals(1, model.includes.size)

            assertEquals(setOf("h1", "h2"), model.stats.keys)
            assertEquals(1, model.stats.getValue("h1").rescued)
            assertEquals("the work directory and the site file", 2, model.stats.getValue("h1").changed)
            assertEquals(HostStatus.FAILED, play.status)
            assertTrue(model.failedHosts().isEmpty())
            assertTrue(play.counts[HostStatus.OK] > 0 && play.counts.percent(HostStatus.OK) in 1..100)
        }
    }

    @Test
    fun `failures, unreachable hosts and the plays that follow`() {
        for (version in versions) {
            val model = model("failure", version)
            assertEquals(listOf("Breaks one host", "Second play", "Breaks the rest"), model.plays.map { it.name })
            val first = model.plays[0].tasks[0]
            assertEquals(HostStatus.UNREACHABLE, first.hosts.getValue("down.invalid").status)
            assertEquals(HostStatus.FAILED, first.hosts.getValue("a2").status)
            assertEquals(HostStatus.OK, first.hosts.getValue("a1").status)
            assertEquals(HostStatus.FAILED, first.status)
            assertEquals(listOf("a1"), model.plays[1].tasks.single().hosts.keys.toList())
            assertEquals("the run stopped before the last task", 1, model.plays[2].tasks.size)
            assertEquals(listOf("a1", "a2", "down.invalid"), model.failedHosts().sorted())
        }
    }

    @Test
    fun `imports, plays without hosts and serial batches`() {
        for (version in versions) {
            val model = model("imports", version)
            assertEquals(listOf("Imported play", "Nothing matches", "One host at a time"), model.plays.map { it.name })
            assertTrue(model.plays[1].noHostsMatched)
            assertEquals(HostStatus.SKIPPED, model.plays[1].status)
            val serial = model.plays[2]
            assertEquals(2, serial.batches)
            assertEquals("one task for both batches", listOf("d1", "d2"), serial.tasks.single().hosts.keys.toList())
        }
    }

    @Test
    fun `check mode reports changes with their diffs`() {
        for (version in versions) {
            val model = model("check", version)
            assertTrue(model.start!!.check)
            val tasks = model.plays.single().tasks
            assertEquals(listOf(HostStatus.CHANGED, HostStatus.CHANGED), tasks.map { it.status })
            val line = tasks[1].hosts.getValue("c1").diff.first()
            assertEquals("port=80\nhost=example.test\n", line.before)
            assertNotNull(line.after)
        }
    }

    @Test
    fun `a stopped run leaves running hosts and a failed status`() {
        val model = RunModel()
        model.apply(RunEvent.Play(1.0, "p", "Web", listOf("all"), null))
        model.apply(RunEvent.Task(2.0, "t", "p", "Long", null, "command", null, handler = false, loop = false, tags = emptyList()))
        model.apply(RunEvent.HostStart(3.0, "t", "h1"))
        assertEquals(HostStatus.RUNNING, model.status)
        model.finish(130)
        assertEquals(HostStatus.RUNNING, model.plays.single().tasks.single().status)
        assertEquals(130, model.exitCode)
        assertEquals(3.0, model.plays.single().ended!!, 0.0)
        assertEquals(listOf<String>(), model.failedHosts())
        val orphan = RunModel().apply { apply(RunEvent.Task(1.0, "t", null, "Alone", null, null, null, false, false, emptyList())) }
        assertEquals("a task without a play gets an implicit one", 1, orphan.plays.size)
    }

    @Test
    fun `the callback is added behind the configured callback paths`() {
        assertEquals("./team/cb:/ansibility-run/callbacks", RunCallback.pluginPath(null, "./team/cb", "/ansibility-run/callbacks"))
        assertEquals("the environment wins over ansible.cfg", "/env/cb:/r", RunCallback.pluginPath("/env/cb", "./team/cb", "/r"))
        assertEquals("${RunCallback.DEFAULT_PLUGINS}:/r", RunCallback.pluginPath(null, null, "/r"))
        assertEquals("a:/r", RunCallback.pluginPath(" a : /r ", null, "/r"))
        assertEquals(32, RunCallback.newToken().length)
        assertTrue(RunCallback.source().contains("CALLBACK_NAME = \"ansibility_events\""))
    }

    // ------------------------------------------------------------------ R19: preparation, not started, no vault secrets

    @Test
    fun `the preparation's phases come and go, and a run that did not start says why`() {
        val model = RunModel()
        model.apply(RunEvent.Preparing(1.0, "reading the playbook"))
        assertEquals("reading the playbook", model.preparing)
        model.apply(RunEvent.Preparing(2.0, "unlocking the vault ids of falcon"))
        assertEquals("unlocking the vault ids of falcon", model.preparing)
        assertFalse("a preparation is no event of the process", model.hasEvents)
        model.apply(RunEvent.Preparing(3.0, null))
        assertNull("the process started", model.preparing)

        val failed = RunModel()
        failed.apply(RunEvent.Preparing(1.0, "reading the role"))
        failed.apply(RunEvent.NotStarted(2.0, "No docker executable found on PATH"))
        failed.finish(RunModel.NOT_STARTED)
        assertNull(failed.preparing)
        assertEquals("No docker executable found on PATH", failed.notStarted)
        assertEquals(RunModel.NOT_STARTED, failed.exitCode)
        assertFalse(failed.hasEvents)

        val stopped = RunModel().apply {
            apply(RunEvent.Preparing(1.0, "checking that the checkout is up to date with main"))
            finish(RunModel.NOT_STARTED)
        }
        assertNull("the end clears the phase", stopped.preparing)
    }

    @Test
    fun `a unit of a batch prepares in its own row`() {
        val model = RunModel()
        model.apply(RunEvent.Units(0.0, listOf(RunEvent.UnitInfo("/r/web", "web", "falcon"), RunEvent.UnitInfo("/r/db", "db", "falcon"))))
        model.apply(RunEvent.UnitStart(1.0, "/r/web"))
        model.apply(RunEvent.Preparing(1.1, "reading the role"))
        val (web, db) = model.units
        assertEquals("reading the role", web.preparing)
        assertNull("the run's own phase stays empty", model.preparing)
        model.apply(RunEvent.Preparing(1.2, null))
        assertNull(web.preparing)
        model.apply(RunEvent.UnitEnd(2.0, "/r/web", 0))
        model.apply(RunEvent.UnitStart(2.0, "/r/db"))
        model.apply(RunEvent.Preparing(2.1, "reading the role"))
        model.apply(RunEvent.UnitEnd(3.0, "/r/db", RunModel.NOT_STARTED, "No Docker Compose service with Molecule mounts the role db"))
        assertNull("its end clears it", db.preparing)
        model.apply(RunEvent.UnitStart(3.0, "/r/web"))
        model.apply(RunEvent.Preparing(3.1, "reading the role"))
        model.finish(1)
        assertNull("so does the batch's", web.preparing)
    }

    @Test
    fun `a failure for want of vault secrets is told apart from others`() {
        assertTrue(RunModel.isMissingVaultSecrets("Attempting to decrypt but no vault secrets found"))
        assertTrue(RunModel.isMissingVaultSecrets("ERROR! attempting to decrypt but NO VAULT SECRETS found"))
        assertFalse(RunModel.isMissingVaultSecrets("The task includes an option with an undefined variable"))
        assertFalse(RunModel.isMissingVaultSecrets(null))

        val model = RunModel()
        model.apply(RunEvent.Play(1.0, "p", "Converge", listOf("instance"), null))
        fun task(id: String, name: String) = model.apply(RunEvent.Task(2.0, id, "p", name, null, "debug", null, handler = false, loop = false, tags = emptyList()))
        fun result(task: String, host: String, status: HostStatus, message: String?, item: Boolean = false) = model.apply(
            RunEvent.Result(3.0, item, task, host, status, false, if (item) "x" else null, null, null, null, message, emptyList(), null, false),
        )
        task("t1", "Read the secret")
        result("t1", "instance", HostStatus.OK, "Attempting to decrypt but no vault secrets found")
        assertFalse("only a failure counts", model.missingVaultSecrets)
        task("t2", "Other failure")
        result("t2", "instance", HostStatus.FAILED, "Connection refused")
        assertFalse(model.missingVaultSecrets)
        task("t3", "Read the vaulted password")
        result("t3", "instance", HostStatus.FAILED, "Attempting to decrypt but no vault secrets found")
        val (ok, other, vaulted) = model.plays.single().tasks
        assertFalse(ok.missingVaultSecrets)
        assertFalse(other.missingVaultSecrets)
        assertTrue(vaulted.missingVaultSecrets)
        assertTrue(vaulted.hosts.getValue("instance").missingVaultSecrets)
        assertTrue(model.missingVaultSecrets)
        assertTrue(model.plays.single().missingVaultSecrets)

        // A Molecule stage and a batch's unit say so too.
        val batch = RunModel()
        batch.apply(RunEvent.Units(0.0, listOf(RunEvent.UnitInfo("/r/web", "web", "falcon"))))
        batch.apply(RunEvent.UnitStart(0.5, "/r/web"))
        batch.apply(RunEvent.Stage(0.6, "default", "converge", null))
        batch.apply(RunEvent.Start(0.7, "converge.yml", "2.18.8", false, false, null, emptyList(), emptyList()))
        batch.apply(RunEvent.Play(1.0, "p", "Converge", listOf("instance"), null))
        batch.apply(RunEvent.Task(2.0, "t", "p", "Read", null, "debug", null, handler = false, loop = false, tags = emptyList()))
        batch.apply(RunEvent.Result(3.0, false, "t", "instance", HostStatus.FAILED, false, null, null, null, null, "Attempting to decrypt but no vault secrets found", emptyList(), null, false))
        assertTrue(batch.units.single().stages.single().missingVaultSecrets)
        assertTrue(batch.units.single().missingVaultSecrets)

        val items = RunModel()
        items.apply(RunEvent.Play(1.0, "p", "Converge", listOf("instance"), null))
        items.apply(RunEvent.Task(2.0, "t", "p", "Loop", null, "debug", null, handler = false, loop = true, tags = emptyList()))
        items.apply(RunEvent.Result(3.0, true, "t", "instance", HostStatus.FAILED, false, "a", null, null, null, "Attempting to decrypt but no vault secrets found", emptyList(), null, false))
        items.apply(RunEvent.Result(4.0, false, "t", "instance", HostStatus.FAILED, false, null, 1, null, null, "One or more items failed", emptyList(), null, false))
        assertTrue("an item's failure counts", items.plays.single().tasks.single().hosts.getValue("instance").items.single().missingVaultSecrets)
        assertTrue(items.missingVaultSecrets)
    }
}
