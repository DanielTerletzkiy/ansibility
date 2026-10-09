package de.terletzkiy.ansibility.golden.push

import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import de.terletzkiy.ansibility.api.RoleRegistry
import de.terletzkiy.ansibility.golden.AnsibilityGoldenBundle.message
import de.terletzkiy.ansibility.inspections.spec.SpecDefaultChecks
import de.terletzkiy.ansibility.semantics.diagnostics.DiagnosticCode
import de.terletzkiy.ansibility.semantics.diagnostics.Level
import de.terletzkiy.ansibility.settings.SeverityPolicy
import de.terletzkiy.ansibility.settings.atMost
import kotlinx.coroutines.CancellationException
import org.jetbrains.annotations.Nls

/**
 * X124 (plan amendment R24): after a push that changed a copy's `defaults/` or its argument_specs
 * (`meta/argument_specs.y(a)ml`, or `meta/main.y(a)ml`, which may hold them and names the dependencies), R23's checks
 * of that copy ([SpecDefaultChecks]: ANS-S003, S004, S005) run in the background and their findings become one line of
 * Push's notification: "argument_specs checks: heron web: 1 spec default differs from the role default (ANS-S003)".
 *
 * Only findings the editor highlights count: the level of [SeverityPolicy] for the copy's root, lowered to the
 * finding's cap, must be at least a weak warning (INFO and OFF findings are left out). Values are never shown, only
 * counts, so no-log and vault values stay out of the notification.
 */
internal object PushSpecChecks {
    private val LOG = logger<PushSpecChecks>()

    /** A pushed copy to check: the root's display name, the role name and the role directory. */
    class Target(val rootName: String, val roleName: String, val roleDir: VirtualFile) {
        override fun toString(): String = "Target($rootName $roleName)"
    }

    /** The highlighted findings of one copy, by code (only codes with findings). */
    class Findings(val target: Target, val counts: Map<DiagnosticCode, Int>) {
        override fun toString(): String = "Findings($target, $counts)"
    }

    /** The codes of R23's checks, in the order the line names them. */
    val CODES: List<DiagnosticCode> = listOf(
        DiagnosticCode.S003_SPEC_DEFAULT_MISMATCH,
        DiagnosticCode.S004_SPEC_DEFAULT_NOT_APPLIED,
        DiagnosticCode.S005_SPEC_DEFAULT_UNDOCUMENTED,
    )

    private val TRIGGER_FILES = setOf("meta/argument_specs.yml", "meta/argument_specs.yaml", "meta/main.yml", "meta/main.yaml")

    /** Whether a change of [relPath] (relative to the role directory) re-runs the checks. */
    fun triggers(relPath: String): Boolean = relPath.startsWith("defaults/") || relPath in TRIGGER_FILES

    /**
     * The findings of [targets] with at least one highlighted finding, in [targets] order. One read action in smart
     * mode (the checks need indexes), on a background thread; the analyses are cached per spec file (R23). A check
     * that fails is logged (its error class only) and counts as clean: the push's notification never waits on it.
     */
    suspend fun run(project: Project, targets: List<Target>): List<Findings> = try {
        smartReadAction(project) {
            targets.mapNotNull { target ->
                ProgressManager.checkCanceled()
                findings(project, target)
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        LOG.warn("The R23 checks after a push failed (${e.javaClass.name})")
        emptyList()
    }

    /** The highlighted findings of [target], or null when there are none (or the copy has no spec). Read action. */
    fun findings(project: Project, target: Target): Findings? {
        if (!target.roleDir.isValid) return null
        val role = RoleRegistry.getInstance(project).roleOf(target.roleDir)?.takeIf { it.ref.dir == target.roleDir } ?: return null
        val analysis = SpecDefaultChecks.of(project, role) ?: return null
        val policy = SeverityPolicy.getInstance(project)
        val counts = analysis.findings
            .filter { it.code in CODES && highlighted(policy.level(it.code, analysis.root).atMost(it.cap)) }
            .groupingBy { it.code }
            .eachCount()
        return if (counts.isEmpty()) null else Findings(target, counts)
    }

    /**
     * "argument_specs checks: heron web: 1 spec default differs from the role default (ANS-S003); falcon web: …", or null when
     * [findings] is empty (a clean push adds no line).
     */
    @Nls
    fun line(findings: List<Findings>): String? {
        if (findings.isEmpty()) return null
        val copies = findings.joinToString("; ") { copy ->
            val parts = CODES.mapNotNull { code -> copy.counts[code]?.let { count -> message("push.specChecks.${code.id}", count, code.id) } }
            message("push.specChecks.copy", copy.target.rootName, copy.target.roleName, parts.joinToString(", "))
        }
        return message("push.specChecks", copies)
    }

    private fun highlighted(level: Level): Boolean = level == Level.ERROR || level == Level.WARNING || level == Level.WEAK_WARNING
}
