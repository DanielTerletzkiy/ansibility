package de.terletzkiy.ansibility.inspections.modules

import com.intellij.openapi.project.Project
import de.terletzkiy.ansibility.api.AnsibleDocService
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.FileKind
import de.terletzkiy.ansibility.context.AnsibleWorkspaceImpl
import de.terletzkiy.ansibility.context.TargetVersionDetector
import de.terletzkiy.ansibility.model.task.TaskSyntax
import de.terletzkiy.ansibility.model.task.TaskSyntaxService
import de.terletzkiy.ansibility.runtime.DocSnapshotStore
import de.terletzkiy.ansibility.semantics.CoreVersion
import de.terletzkiy.ansibility.semantics.coerce.Booleans
import de.terletzkiy.ansibility.semantics.coerce.CoreSemantics

/**
 * What the module option and keyword checks of one root need: the target ansible-core (its semantics and the label
 * messages name it by), the keyword facts of its documentation line, whether those docs match the target, and the
 * root's `invalid_task_attribute_failed` setting. Built per file inside a read action.
 *
 * @property version the target ansible-core, or the documentation line's version when the target is unknown
 * @property keywordDocsMatchTarget the bundled keyword docs are on the target's minor line (findings from other docs
 *   are capped at WARNING, plan A.6); false for an unknown target
 * @property invalidTaskAttributeFailed `[defaults] invalid_task_attribute_failed` (default true): when false,
 *   ansible-core only warns about unknown task keys and ignores them
 */
class TaskCheckEnvironment(
    val project: Project,
    val root: AnsibleRoot,
    val version: CoreVersion,
    /** False when the root's target is unknown and [version] is the documentation line's. */
    val targetKnown: Boolean,
    val syntax: TaskSyntax,
    val keywordDocsMatchTarget: Boolean,
    val invalidTaskAttributeFailed: Boolean,
) {
    val semantics: CoreSemantics = CoreSemantics(version)

    /** "ansible-core 2.18.8", as messages name the target. */
    val coreLabel: String = "ansible-core $version"

    val docs: AnsibleDocService get() = AnsibleDocService.getInstance(project)

    /** " (documentation from …; the target is …)" for findings whose documentation is not for the target's line. */
    fun docsMismatchNote(source: String): String = " " + if (targetKnown) {
        AnsibilityModuleChecksBundle.message("docs.mismatch", source, coreLabel)
    } else {
        AnsibilityModuleChecksBundle.message("docs.mismatch.unknown.target", source)
    }

    companion object {
        /** The file kinds whose tasks, blocks and plays are checked. */
        val CHECKED_KINDS: Set<FileKind> = setOf(
            FileKind.ROLE_TASKS, FileKind.ROLE_HANDLERS, FileKind.PLAYBOOK, FileKind.MOLECULE_PLAYBOOK, FileKind.MOLECULE_TASKS,
        )

        /** The environment of [root]. Call inside a read action. */
        fun of(project: Project, root: AnsibleRoot): TaskCheckEnvironment {
            val target = TargetVersionDetector.getInstance(project).targetVersion(root).version
            val line = DocSnapshotStore.lineFor(target)
            val syntax = TaskSyntaxService.getInstance().forLine(line)
            val lineVersion = CoreVersion.parse(syntax.coreVersion)
            val matches = target != null && lineVersion != null && target.major == lineVersion.major && target.minor == lineVersion.minor
            val cfg = AnsibleWorkspaceImpl.getInstance(project)?.configOf(root)
            val failed = cfg?.value("defaults", "invalid_task_attribute_failed")?.trim()?.lowercase()?.let { it !in Booleans.FALSE_STRINGS } ?: true
            return TaskCheckEnvironment(project, root, target ?: lineVersion ?: CoreVersion.PINNED, target != null, syntax, matches, failed)
        }
    }
}
