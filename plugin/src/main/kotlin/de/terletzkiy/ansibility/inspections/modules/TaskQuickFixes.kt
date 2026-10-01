package de.terletzkiy.ansibility.inspections.modules

import com.intellij.modcommand.ModPsiUpdater
import com.intellij.modcommand.PsiUpdateModCommandQuickFix
import com.intellij.openapi.project.Project
import com.intellij.psi.ElementManipulators
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import de.terletzkiy.ansibility.semantics.yaml.Resolved
import de.terletzkiy.ansibility.semantics.yaml.Yaml11Resolver
import org.jetbrains.yaml.YAMLElementGenerator
import org.jetbrains.yaml.YAMLUtil
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping
import org.jetbrains.yaml.psi.YAMLScalar

/**
 * 🟣 CLAUDE X80 "Move '<key>' to task level" (ANS-M001): a task keyword written inside the module arguments
 * (`vars:` inside `ansible.builtin.assert:`) moves out to the task, re-indented to the task's keys, after the task's
 * other keys. Offered for block mappings when the task does not already set that keyword.
 */
class MoveToTaskLevelFix(private val key: String) : PsiUpdateModCommandQuickFix() {
    override fun getName(): String = AnsibilityModuleChecksBundle.message("fix.move.to.task", key)

    override fun getFamilyName(): String = AnsibilityModuleChecksBundle.message("fix.move.to.task.family")

    override fun applyFix(project: Project, element: PsiElement, updater: ModPsiUpdater) {
        val (option, arguments, task) = locate(element, key) ?: return
        val fromColumn = YAMLUtil.getIndentToThisElement(option)
        val toColumn = YAMLUtil.getIndentToThisElement(arguments.parent as YAMLKeyValue)
        val text = reindent(option.text, fromColumn, toColumn)
        val moved = PsiTreeUtil.findChildOfType(YAMLElementGenerator.getInstance(project).createDummyYamlWithText(text), YAMLKeyValue::class.java) ?: return
        arguments.deleteKeyValue(option)
        task.putKeyValue(moved)
        task.getKeyValueByKey(key)?.let { updater.moveCaretTo(it.textRange.startOffset) }
    }

    companion object {
        /** Whether the fix applies to the option key [element]. */
        fun isApplicable(element: PsiElement, key: String): Boolean = locate(element, key) != null

        /** The option's key-value, the module's argument mapping and the task mapping. */
        private fun locate(element: PsiElement, key: String): Triple<YAMLKeyValue, YAMLMapping, YAMLMapping>? {
            val option = TaskProblemInspection.keyValueOf(element)?.takeIf { it.keyText == key } ?: return null
            val arguments = option.parentMapping ?: return null
            val argumentsKey = arguments.parent as? YAMLKeyValue ?: return null
            val task = argumentsKey.parentMapping ?: return null
            if (!isBlock(arguments) || !isBlock(task) || task.getKeyValueByKey(key) != null) return null
            return Triple(option, arguments, task)
        }

        private fun isBlock(mapping: YAMLMapping): Boolean = !mapping.text.startsWith("{")

        /** [text] (starting at a key in column [from]) with its continuation lines moved to column [to]. */
        internal fun reindent(text: String, from: Int, to: Int): String {
            val lines = text.split('\n')
            return buildString {
                append(lines.first())
                for (line in lines.drop(1)) {
                    append('\n')
                    if (line.isBlank()) continue
                    val leading = line.length - line.trimStart(' ').length
                    append(" ".repeat((leading - from + to).coerceAtLeast(to + 1)))
                    append(line.substring(leading))
                }
            }
        }
    }
}

/** "Rename to '<name>'": the misspelt key of an option or keyword (ANS-M001, ANS-K002, ANS-T002) gets the suggested name. */
class RenameKeyFix(private val newName: String) : PsiUpdateModCommandQuickFix() {
    override fun getName(): String = AnsibilityModuleChecksBundle.message("fix.rename.key", newName)

    override fun getFamilyName(): String = AnsibilityModuleChecksBundle.message("fix.rename.key.family")

    override fun applyFix(project: Project, element: PsiElement, updater: ModPsiUpdater) {
        TaskProblemInspection.keyValueOf(element)?.setName(newName)
    }
}

/**
 * "Replace with '<choice>'" (ANS-T004): the value becomes the nearest documented choice. A quoted value keeps its
 * quotes; a plain value is quoted when the choice would not load as a string in plain YAML 1.1 (`yes`, `1.0`).
 */
class ReplaceValueFix(private val newValue: String) : PsiUpdateModCommandQuickFix() {
    override fun getName(): String = AnsibilityModuleChecksBundle.message("fix.replace.value", newValue)

    override fun getFamilyName(): String = AnsibilityModuleChecksBundle.message("fix.replace.value.family")

    override fun applyFix(project: Project, element: PsiElement, updater: ModPsiUpdater) {
        val scalar = element as? YAMLScalar ?: return
        val plain = scalar.text.firstOrNull()?.let { it != '"' && it != '\'' && it != '|' && it != '>' } ?: true
        if (plain && Yaml11Resolver.resolvePlain(newValue) !is Resolved.Str) {
            val escaped = newValue.replace("\\", "\\\\").replace("\"", "\\\"")
            val quoted = PsiTreeUtil.findChildOfType(
                YAMLElementGenerator.getInstance(project).createDummyYamlWithText("value: \"$escaped\""),
                YAMLScalar::class.java,
            ) ?: return
            scalar.replace(quoted)
            return
        }
        ElementManipulators.handleContentChange(scalar, newValue)
    }
}

/** "Add option '<name>'" (ANS-M002): an empty required option is added to the module's argument mapping. */
class AddOptionFix(private val option: String) : PsiUpdateModCommandQuickFix() {
    override fun getName(): String = AnsibilityModuleChecksBundle.message("fix.add.option", option)

    override fun getFamilyName(): String = AnsibilityModuleChecksBundle.message("fix.add.option.family")

    override fun applyFix(project: Project, element: PsiElement, updater: ModPsiUpdater) {
        val mapping = TaskProblemInspection.keyValueOf(element)?.value as? YAMLMapping ?: return
        mapping.putKeyValue(YAMLElementGenerator.getInstance(project).createYamlKeyValue(option, "\"\""))
        mapping.getKeyValueByKey(option)?.value?.let { value -> updater.moveCaretTo(value.textRange.startOffset + 1) }
    }

    companion object {
        /** Whether [element] (the module key) has a block mapping of arguments to add to. */
        fun isApplicable(element: PsiElement): Boolean {
            val mapping = TaskProblemInspection.keyValueOf(element)?.value as? YAMLMapping ?: return false
            return !mapping.text.startsWith("{")
        }
    }
}
