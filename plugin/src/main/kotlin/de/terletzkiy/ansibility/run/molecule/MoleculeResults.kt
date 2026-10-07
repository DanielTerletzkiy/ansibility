package de.terletzkiy.ansibility.run.molecule

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import de.terletzkiy.ansibility.api.RoleTestState
import de.terletzkiy.ansibility.api.RoleTests
import de.terletzkiy.ansibility.run.events.StageRun
import org.jetbrains.annotations.TestOnly
import java.util.concurrent.ConcurrentHashMap

/**
 * The last Molecule result of each role and scenario in this session, and the roles that run now (plan amendment
 * R16). A scenario's gutter and the Roles tab show them like test results; [RoleTests.TOPIC] fires on every change.
 */
class MoleculeResults(private val project: Project) : RoleTests {
    /** Role directory path → scenario → its last result. */
    private val results = ConcurrentHashMap<String, Map<String, RoleTestState>>()
    private val running = ConcurrentHashMap<String, Int>()

    override fun stateOf(roleDir: VirtualFile): RoleTestState {
        if (MoleculeRunContext.scenariosOf(roleDir).isEmpty()) return RoleTestState.NONE
        if ((running[roleDir.path] ?: 0) > 0) return RoleTestState.RUNNING
        val states = results[roleDir.path]?.values.orEmpty()
        return when {
            RoleTestState.FAILED in states -> RoleTestState.FAILED
            RoleTestState.PASSED in states -> RoleTestState.PASSED
            else -> RoleTestState.NOT_RUN
        }
    }

    /** The last result of [scenario] of the role at [roleDir], or null before it ran. */
    fun scenarioState(roleDir: String, scenario: String): RoleTestState? = results[roleDir]?.get(scenario)

    /** A run of [spec] started. */
    fun started(spec: MoleculeSpec) {
        running.merge(spec.roleDir, 1, Int::plus)
        changed()
    }

    /**
     * A run of [spec] ended with [exitCode], having reported [stages] (none without the run view). A scenario failed
     * when one of its stages did; without stages the exit code decides for the scenarios the run covered. A destroy is
     * no test, and a run the user [stopped] says nothing: both leave the results alone.
     */
    fun finished(spec: MoleculeSpec, stages: List<StageRun>, exitCode: Int?, stopped: Boolean = false) {
        running.computeIfPresent(spec.roleDir) { _, count -> (count - 1).takeIf { it > 0 } }
        if (spec.command != MoleculeCommand.DESTROY && !stopped) {
            val states = LinkedHashMap<String, RoleTestState>()
            for ((scenario, of) in stages.groupBy { it.scenario }) {
                states[scenario] = if (of.any { it.status.isFailure }) RoleTestState.FAILED else RoleTestState.PASSED
            }
            if (states.isEmpty()) {
                val covered = spec.scenario.ifBlank { null }?.let(::listOf) ?: scenariosOf(spec.roleDir)
                covered.forEach { states[it] = if (exitCode == 0) RoleTestState.PASSED else RoleTestState.FAILED }
            } else if (exitCode != 0 && RoleTestState.FAILED !in states.values) {
                // Molecule failed after its last stage line (or was stopped): the scenario it was in failed.
                states[stages.last().scenario] = RoleTestState.FAILED
            }
            if (states.isNotEmpty()) results.merge(spec.roleDir, states) { old, new -> old + new }
        }
        changed()
    }

    /** Forgets every result (tests: the project outlives a test). */
    @TestOnly
    fun resetForTests() {
        results.clear()
        running.clear()
    }

    private fun scenariosOf(roleDir: String): List<String> =
        LocalFileSystem.getInstance().findFileByPath(roleDir)?.let(MoleculeRunContext::scenariosOf).orEmpty()

    private fun changed() {
        ApplicationManager.getApplication().invokeLater({
            if (project.isDisposed) return@invokeLater
            project.messageBus.syncPublisher(RoleTests.TOPIC).changed()
            // The gutter of scenario files shows the last result.
            DaemonCodeAnalyzer.getInstance(project).restart("Molecule results changed")
        }, ModalityState.any())
    }

    companion object {
        fun getInstance(project: Project): MoleculeResults = RoleTests.getInstance(project) as MoleculeResults
    }
}
