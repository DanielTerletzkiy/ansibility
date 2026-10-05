package de.terletzkiy.ansibility.context.host.values

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import de.terletzkiy.ansibility.api.AnsibleContextService
import de.terletzkiy.ansibility.api.EvalTarget
import de.terletzkiy.ansibility.api.HostValue
import de.terletzkiy.ansibility.api.HostValueService
import de.terletzkiy.ansibility.api.HostValues
import de.terletzkiy.ansibility.context.host.AnsibleContextServiceImpl
import de.terletzkiy.ansibility.context.host.Evaluation
import de.terletzkiy.ansibility.model.effective.ExecutionSources
import de.terletzkiy.ansibility.semantics.precedence.VarLayer
import de.terletzkiy.ansibility.semantics.precedence.VarOwner
import de.terletzkiy.ansibility.semantics.precedence.VarSource
import de.terletzkiy.ansibility.semantics.yaml.YValue
import de.terletzkiy.ansibility.semantics.yaml.YVault

/** [HostValueService] over the host area's evaluator; it reads HA2's cached views and keeps no cache of its own. */
internal class HostValueServiceImpl(private val project: Project) : HostValueService {
    override fun values(target: EvalTarget, runningRole: String?, siteVars: Map<String, YValue>): HostValues? {
        val context = AnsibleContextService.getInstance(project) as? AnsibleContextServiceImpl ?: return null
        val evaluation = context.evaluator.evaluation(target, runningRole) ?: return null
        val sources = if (siteVars.isEmpty()) {
            evaluation.inputs.sources
        } else {
            evaluation.inputs.sources + VarSource(VarLayer.BLOCK_TASK_VARS, VarOwner.All, SITE_ORIGIN, Int.MAX_VALUE, siteVars)
        }
        return Values(project, evaluation, sources, withMarkers = target.play != null || runningRole != null)
    }

    private class Values(
        private val project: Project,
        private val evaluation: Evaluation,
        private val sources: List<VarSource>,
        private val withMarkers: Boolean,
    ) : HostValues {
        private val memo = HashMap<String, HostValue?>()

        override val target: EvalTarget get() = evaluation.target

        override fun valueOf(name: String): HostValue? = memo.getOrPut(name) {
            ProgressManager.checkCanceled()
            val effective = evaluation.host.engine.effectiveOf(name, evaluation.host.view, sources)
            val markers = if (withMarkers) ExecutionSources.getInstance(project).runtimeMarkers(evaluation.root, evaluation.inputs, name) else emptyList()
            when {
                effective != null -> {
                    val ref = if (effective.winner.source.originId == SITE_ORIGIN) null else evaluation.ref(effective)
                    HostValue(effective.value, ref, effective.value is YVault || ref?.isVault == true, markers)
                }
                markers.isNotEmpty() -> HostValue(null, null, false, markers)
                else -> null
            }
        }
    }

    private companion object {
        const val SITE_ORIGIN = "ansibility:render-site"
    }
}
