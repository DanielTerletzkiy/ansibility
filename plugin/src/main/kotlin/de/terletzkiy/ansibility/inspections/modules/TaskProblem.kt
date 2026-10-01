package de.terletzkiy.ansibility.inspections.modules

import com.intellij.openapi.util.TextRange
import de.terletzkiy.ansibility.semantics.diagnostics.DiagnosticCode
import de.terletzkiy.ansibility.semantics.diagnostics.Level
import de.terletzkiy.ansibility.settings.FindingContext

/**
 * One finding of the module option or keyword checks, PSI-free: what a task-like file's model says ansible-core
 * does with a value or key. The inspections map [code] and [context] through `SeverityPolicy` and anchor [range] in
 * the file the problem was computed for.
 *
 * @property range the offending node in the file (a key, a value or a list item)
 * @property maxLevel a ceiling for the policy's level, for findings ansible-core only warns about
 *   (`invalid_task_attribute_failed = False`)
 */
data class TaskProblem(
    val code: DiagnosticCode,
    val message: String,
    val range: TextRange,
    val context: FindingContext,
    val fixes: List<ProblemFix> = emptyList(),
    val maxLevel: Level? = null,
)

/** A quick fix a [TaskProblem] offers; the inspection turns it into a `ModCommand` fix anchored in PSI. */
sealed interface ProblemFix {
    /** Moves the module option whose key spans [keyRange] out of the module arguments to the task (🟣 X80). */
    data class MoveToTaskLevel(val key: String, val keyRange: TextRange) : ProblemFix

    /** Renames the mapping key spanning [keyRange] to [newName]. */
    data class RenameKey(val keyRange: TextRange, val newName: String) : ProblemFix

    /** Replaces the scalar spanning [valueRange] with [newValue] (a choice), keeping its quoting style. */
    data class ReplaceValue(val valueRange: TextRange, val newValue: String) : ProblemFix

    /** Adds the required option [name] (with an empty value) to the module mapping whose key spans [moduleKeyRange]. */
    data class AddOption(val moduleKeyRange: TextRange, val name: String) : ProblemFix
}
