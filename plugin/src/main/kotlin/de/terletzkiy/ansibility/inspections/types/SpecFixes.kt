package de.terletzkiy.ansibility.inspections.types

import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.modcommand.ModCommand
import com.intellij.modcommand.ModCommandQuickFix
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.ValueShape
import de.terletzkiy.ansibility.api.VarDefKind
import de.terletzkiy.ansibility.api.VarService
import de.terletzkiy.ansibility.semantics.yaml.YMap
import de.terletzkiy.ansibility.semantics.yaml.YSeq
import de.terletzkiy.ansibility.types.AnsibilityTypesBundle
import de.terletzkiy.ansibility.yaml.PsiYValueAdapter
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLKeyValue

/**
 * "Add sub-option 'k' to <role> argument_specs" (🟣 CLAUDE X80, ANS-T002): declares the unsupported key in the
 * role's `meta/argument_specs.yml` (same root only), with the type its value suggests ([SpecEdits.fieldsFor]). The edit
 * is to another file, so it goes through [ModCommand.psiUpdate] and the preview shows the spec diff.
 *
 * @property optionPath the option that lacks the key: the top-level name, then sub-option names
 */
class AddSubOptionFix(
    private val roleName: String,
    private val specFile: VirtualFile,
    private val entryPoint: String,
    private val optionPath: List<String>,
    private val key: String,
    private val fields: List<Pair<String, String>>,
) : ModCommandQuickFix() {
    override fun getName(): String = AnsibilityTypesBundle.message("fix.add.sub.option", key, roleName)

    override fun getFamilyName(): String = AnsibilityTypesBundle.message("fix.add.sub.option.family")

    override fun perform(project: Project, descriptor: ProblemDescriptor): ModCommand {
        val spec = PsiManager.getInstance(project).findFile(specFile) as? YAMLFile ?: return ModCommand.nop()
        val option = SpecEdits.optionMapping(spec, entryPoint, optionPath) ?: return ModCommand.nop()
        return ModCommand.psiUpdate(option) { writable, _ -> SpecEdits.addSubOption(writable, key, fields) }
    }
}

/**
 * 🟣 CLAUDE X79 "Update spec from usage" (ANS-T010): the spec documents `elements: str` but the values are mappings.
 * The option gets `elements: dict` and one sub-option per item key: the keys of every literal definition of the variable
 * in the root (inventory, playbooks, molecule …), typed by their values ([SpecEdits.join]), then the attributes the
 * looping tasks read ([usageAttributes]) that no value sets, typed `raw`. That clears the ANS-T010 findings of every
 * file of the root at once; definitions whose items are not mappings would then be rejected, which the role's spec
 * owner decides.
 */
class UpdateSpecFromUsageFix(
    private val roleName: String,
    private val rootDir: VirtualFile,
    private val specFile: VirtualFile,
    private val entryPoint: String,
    private val variable: String,
    private val usageAttributes: List<String>,
) : ModCommandQuickFix() {
    override fun getName(): String = AnsibilityTypesBundle.message("fix.update.spec.role", roleName)

    override fun getFamilyName(): String = AnsibilityTypesBundle.message("fix.update.spec")

    override fun perform(project: Project, descriptor: ProblemDescriptor): ModCommand {
        val spec = PsiManager.getInstance(project).findFile(specFile) as? YAMLFile ?: return ModCommand.nop()
        val option = SpecEdits.optionMapping(spec, entryPoint, listOf(variable)) ?: return ModCommand.nop()
        val subOptions = inferSubOptions(project)
        if (subOptions.isEmpty()) return ModCommand.nop()
        return ModCommand.psiUpdate(option) { writable, _ -> SpecEdits.makeElementsDict(writable, subOptions) }
    }

    /** Item key → `type` field, from the literal definitions of [variable] in the root, then the read attributes. */
    private fun inferSubOptions(project: Project): Map<String, List<Pair<String, String>>> {
        val root = AnsibleWorkspace.getInstance(project).roots().firstOrNull { it.dir == rootDir } ?: return emptyMap()
        val types = LinkedHashMap<String, MutableList<String?>>()
        val psiManager = PsiManager.getInstance(project)
        for (definition in VarService.getInstance(project).symbol(root, variable).definitions) {
            ProgressManager.checkCanceled()
            if (definition.kind == VarDefKind.SPEC_OPTION || definition.valueShape != ValueShape.CONTAINER) continue
            val file = psiManager.findFile(definition.location.file) as? YAMLFile ?: continue
            val keyValue = PsiTreeUtil.getParentOfType(file.findElementAt(definition.location.offset), YAMLKeyValue::class.java, false) ?: continue
            val items = (PsiYValueAdapter.valueOf(keyValue) as? YSeq)?.items ?: continue
            for (item in items.filterIsInstance<YMap>()) {
                for (entry in item.entries) types.getOrPut(entry.key.text) { ArrayList() } += SpecEdits.typeOf(entry.value)
            }
        }
        for (attribute in usageAttributes) types.getOrPut(attribute) { ArrayList() }
        return types.mapValues { (_, seen) -> listOf("type" to SpecEdits.join(seen)) }
    }
}
