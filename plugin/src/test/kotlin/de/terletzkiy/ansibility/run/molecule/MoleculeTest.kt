package de.terletzkiy.ansibility.run.molecule

import de.terletzkiy.ansibility.run.DockerTarget
import de.terletzkiy.ansibility.run.PlaybookExecutor
import de.terletzkiy.ansibility.run.RunAdditions
import de.terletzkiy.ansibility.run.RunSecrets
import de.terletzkiy.ansibility.run.events.HostStatus
import de.terletzkiy.ansibility.run.events.LineSplitter
import de.terletzkiy.ansibility.run.events.MoleculeLog
import de.terletzkiy.ansibility.run.events.RunEvent
import de.terletzkiy.ansibility.run.events.RunEventFrames
import de.terletzkiy.ansibility.run.events.RunModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/** Molecule runs: the command line, Molecule's stage lines, and a recorded `molecule test --all` as stages and plays. */
class MoleculeTest {
    /** Replays a recorded Molecule output the way the process handler reads a stream: frames out, stage lines observed. */
    private fun replay(text: String): RunModel {
        val model = RunModel()
        val lines = LineSplitter { line -> MoleculeLog.stage(line, 0.0)?.let(model::apply) }
        val frames = RunEventFrames("golden", onText = lines::feed, onEvent = model::apply)
        text.chunked(97).forEach { frames.filter(it) }
        frames.flush()
        lines.flush()
        model.finish(1)
        return model
    }

    /** What a model shows of its stages. */
    private fun stages(model: RunModel): List<String> = model.stages.map { stage ->
        "${stage.scenario} ${stage.action}: ${stage.result} ${stage.status} plays=${stage.plays.map { it.name }} counts=${stage.counts} recap=${stage.stats}"
    }

    private fun transcripts(): List<Path> = Files.list(Path.of("src/test/testData/run-events/molecule")).use { files ->
        files.filter { it.fileName.toString().endsWith(".log") }.toList()
    }

    @Test
    fun `molecule stage lines are read in both formats`() {
        assertEquals(RunEvent.Stage(1.0, "default", "converge", null), MoleculeLog.stage("INFO     [default > converge] Executing", 1.0))
        assertEquals(RunEvent.Stage(1.0, "default", "verify", "Successful"), MoleculeLog.stage("INFO     [default > verify] Executed: Successful", 1.0))
        assertEquals("Failed", MoleculeLog.stage("ERROR    [broken > idempotence] \u001b[31mExecuted: Failed\u001b[0m", 1.0)?.result)
        assertEquals(RunEvent.Stage(1.0, "default", "create", null), MoleculeLog.stage("INFO     Running default > create", 1.0))
        assertNull(MoleculeLog.stage("TASK [Gathering Facts] *****", 1.0))
        assertNull(MoleculeLog.stage("default > converge: Executed: Successful", 1.0))
    }

    @Test
    fun `a recorded molecule test shows its scenarios' stages with their plays`() {
        val transcripts = transcripts()
        assertTrue("regenerate with tools/run-events/generate.py --molecule-image", transcripts.isNotEmpty())
        for (transcript in transcripts) {
            val model = replay(Files.readString(transcript))
            val stages = model.stages.associateBy { "${it.scenario} ${it.action}" }
            assertTrue(stages.keys.toString(), stages.keys.containsAll(listOf("default converge", "default idempotence", "default verify", "volatile converge", "volatile idempotence")))
            assertEquals(HostStatus.CHANGED, stages.getValue("default converge").status)
            assertEquals("Successful", stages.getValue("default idempotence").result)
            assertEquals(HostStatus.OK, stages.getValue("default verify").status)
            assertEquals(listOf("The marker exists"), stages.getValue("default verify").plays.single().tasks.filter { it.name == "The marker exists" }.map { it.name })
            assertEquals(HostStatus.FAILED, stages.getValue("volatile idempotence").status)
            assertEquals(listOf("Always changes"), stages.getValue("volatile idempotence").changedTasks.map { it.name })
            assertTrue("the playbooks of every stage are plays of the run", model.plays.size >= 5)
            assertTrue(stages.getValue("default converge").stats.getValue("instance").changed > 0)
        }
    }

    @Test
    fun `stage lines that overtake the last events of their playbook leave the plays with their stage`() {
        // Molecule logs its stage lines on stderr and Ansible's events come on stdout: each stream is read in order, the
        // two only roughly in step. Here every stage line arrives three events early: an `Executed` line (and the next
        // stage's `Executing`) before the recap and the last results of the playbook it reports on.
        for (transcript in transcripts()) {
            val arrivals = ArrayList<Pair<Double, RunEvent>>()
            var events = 0
            val lines = LineSplitter { line -> MoleculeLog.stage(line, 0.0)?.let { arrivals += (events - 3.5) to it } }
            val frames = RunEventFrames("golden", onText = lines::feed) { arrivals += (events++).toDouble() to it }
            frames.filter(Files.readString(transcript))
            frames.flush()
            lines.flush()
            val early = RunModel()
            arrivals.sortedBy { it.first }.forEach { early.apply(it.second) }
            early.finish(1)
            val ordered = replay(Files.readString(transcript))
            assertTrue(stages(ordered).joinToString("\n"), stages(ordered).any { it.startsWith("volatile idempotence: Failed FAILED plays=[") && "plays=[]" !in it })
            assertEquals(stages(ordered), stages(early))
        }
    }

    @Test
    fun `a stage without a result ends with the next one, and the stage a stopped run was in failed`() {
        val model = RunModel()
        model.apply(RunEvent.Stage(1.0, "default", "create", null))
        model.apply(RunEvent.Stage(2.0, "default", "converge", null))
        assertEquals("an older Molecule reports no results", HostStatus.OK, model.stages[0].status)
        assertEquals(HostStatus.RUNNING, model.stages[1].status)
        model.finish(143)
        assertTrue(model.stages[1].interrupted)
        assertEquals(HostStatus.FAILED, model.stages[1].status)

        val done = RunModel()
        done.apply(RunEvent.Stage(1.0, "default", "verify", null))
        done.finish(0)
        assertEquals(HostStatus.OK, done.stages.single().status)
    }

    @Test
    fun `a batch groups each role's stages and plays, and knows the roles it did not run`() {
        val model = RunModel()
        val units = listOf(RunEvent.UnitInfo("/r/web", "web", "falcon"), RunEvent.UnitInfo("/r/db", "db", "tern"), RunEvent.UnitInfo("/r/lb", "lb", "tern"))
        model.apply(RunEvent.Units(0.0, units))
        model.apply(RunEvent.UnitStart(1.0, "/r/web"))
        model.apply(RunEvent.Stage(1.1, "default", "converge", null))
        model.apply(RunEvent.Start(1.2, "converge.yml", null, false, false, null, emptyList(), emptyList()))
        model.apply(RunEvent.Play(1.3, "p1", "Converge", listOf("all"), null))
        model.apply(RunEvent.Stage(1.8, "default", "converge", "Successful"))
        model.apply(RunEvent.UnitEnd(2.0, "/r/web", 0))
        model.apply(RunEvent.UnitStart(3.0, "/r/db"))
        // The next role's first frames overtake its first stage line: they still belong to it, not to web's stage.
        model.apply(RunEvent.Start(3.1, "converge.yml", null, false, false, null, emptyList(), emptyList()))
        model.apply(RunEvent.Play(3.2, "p2", "Converge", listOf("all"), null))
        model.apply(RunEvent.Stage(3.3, "default", "converge", null))
        model.apply(RunEvent.Stage(3.9, "default", "converge", "Failed"))
        model.apply(RunEvent.UnitEnd(4.0, "/r/db", 2))
        model.finish(1)
        val (web, db, lb) = model.units
        assertEquals(listOf("Converge"), web.stages.single().plays.map { it.name })
        assertEquals(HostStatus.OK, web.status)
        assertEquals(listOf("Converge"), db.plays.map { it.name })
        assertTrue("web's stage keeps only its own play", web.stages.single().plays.size == 1)
        assertEquals(HostStatus.FAILED, db.status)
        assertEquals(2, db.exitCode)
        assertNull("not run", lb.started)
        assertEquals(HostStatus.SKIPPED, lb.status)

        val stopped = RunModel()
        stopped.apply(RunEvent.Units(0.0, units.take(1)))
        stopped.apply(RunEvent.UnitStart(1.0, "/r/web"))
        stopped.finish(130)
        assertEquals(130, stopped.units.single().exitCode)
        assertEquals(HostStatus.FAILED, stopped.units.single().status)
    }

    @Test
    fun `the molecule command runs in the role, locally or in its compose service`() {
        val role = Path.of("/work/repos/falcon/ansible/roles/web")
        assertEquals(listOf("test", "--scenario-name", "default"), MoleculeCommandLine.arguments(MoleculeSpec(role.toString(), "default")))
        assertEquals(listOf("test", "--destroy=never", "--all", "--parallel"), MoleculeCommandLine.arguments(MoleculeSpec(role.toString(), "", MoleculeCommand.TEST_KEEP, additionalArgs = "--parallel")))
        assertEquals(MoleculeCommand.CONVERGE, MoleculeCommand.forAction("converge"))
        assertNull(MoleculeCommand.forAction("prepare"))
        assertEquals("Molecule web › default (verify)", MoleculeSpec(role.toString(), "default", MoleculeCommand.VERIFY).name())

        val native = MoleculeCommandLine.native(Path.of("/venv/bin/molecule"), MoleculeSpec(role.toString(), "default"), role, mapOf("MOLECULE_RUN_ID" to "x"), RunSecrets.NONE, "/usr/bin")
        assertEquals(listOf("/venv/bin/molecule", "test", "--scenario-name", "default"), native.command)
        assertEquals(role, native.workDir)
        assertEquals("/venv/bin:/usr/bin", native.environment["PATH"])

        val target = DockerTarget(
            Path.of("/work/repos/falcon/docker-compose.ansible-molecule.yaml"), "ansible-molecule",
            listOf(DockerTarget.Mount(Path.of("/work/repos/falcon/ansible/roles"), "/ansible/roles")),
        )
        val docker = MoleculeCommandLine.docker(
            Path.of("docker"), target, MoleculeSpec(role.toString(), "default", executor = PlaybookExecutor.DOCKER), role, RunSecrets.NONE,
            RunAdditions(null, mapOf("MOLECULE_RUN_ID" to "x"), mapOf("KNOWN" to "y")),
        )!!
        assertEquals(
            listOf("docker", "compose", "-f", target.composeFile.toString(), "run", "--rm", "-T", "--entrypoint", "molecule", "-w", "/ansible/roles/web",
                "-e", "MOLECULE_RUN_ID", "ansible-molecule", "test", "--scenario-name", "default"),
            docker.command,
        )
        assertEquals(mapOf("KNOWN" to "y", "MOLECULE_RUN_ID" to "x"), docker.environment)
        assertNull(MoleculeCommandLine.docker(Path.of("docker"), target, MoleculeSpec("/elsewhere"), Path.of("/elsewhere"), RunSecrets.NONE, RunAdditions()))
    }
}
