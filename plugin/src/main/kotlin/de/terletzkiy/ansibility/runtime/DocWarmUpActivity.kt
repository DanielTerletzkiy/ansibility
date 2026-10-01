package de.terletzkiy.ansibility.runtime

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.readAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.context.TargetVersionDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Parses the bundled documentation lines the project's roots are served by, off the EDT and outside read actions,
 * so the first hover or inspection does not wait for a snapshot. Projects without Ansible roots load nothing.
 */
class DocWarmUpActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        if (ApplicationManager.getApplication().isUnitTestMode) return
        val lines = readAction {
            val roots = AnsibleWorkspace.getInstance(project).roots().filter { !it.detached }
            val detector = TargetVersionDetector.getInstance(project)
            roots.mapTo(LinkedHashSet()) { DocSnapshotStore.lineFor(detector.targetVersion(it).version) }
        }
        if (lines.isEmpty()) return
        withContext(Dispatchers.Default) { DocSnapshotStore.getInstance().warmUp(lines) }
    }
}
