package de.terletzkiy.ansibility.inspections.spec

import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.modcommand.ModCommand
import com.intellij.modcommand.ModCommandQuickFix
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import de.terletzkiy.ansibility.inspections.types.SpecEdits
import de.terletzkiy.ansibility.yaml.YamlPaths
import org.jetbrains.yaml.YAMLElementGenerator
import org.jetbrains.yaml.YAMLFileType
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLMapping

/** Adds [options] (name → `type`/`elements` fields) to the `options:` of [entryPoint] in the role's existing spec. */
class AddToArgumentSpecFix(
    private val specFile: VirtualFile,
    private val roleName: String,
    private val entryPoint: String,
    private val options: Map<String, List<Pair<String, String>>>,
) : ModCommandQuickFix() {
    override fun getName(): String =
        if (options.size == 1) {
            AnsibilitySpecBundle.message("fix.add.to.spec", options.keys.single(), options.values.single().typeText(), roleName)
        } else {
            AnsibilitySpecBundle.message("fix.add.all.to.spec", options.size, roleName)
        }

    override fun getFamilyName(): String = AnsibilitySpecBundle.message("fix.add.to.spec.family")

    override fun perform(project: Project, descriptor: ProblemDescriptor): ModCommand {
        val spec = PsiManager.getInstance(project).findFile(specFile) as? YAMLFile ?: return ModCommand.nop()
        val entry = SpecEdits.optionMapping(spec, entryPoint, emptyList())
        if (entry != null) return ModCommand.psiUpdate(entry) { writable, _ -> SpecEdits.addSubOptions(writable, options) }
        // No such entry point yet (an empty or comment-only spec): write a fresh `argument_specs:` document.
        return ModCommand.psiUpdate(spec) { writable, _ ->
            val fresh = YAMLElementGenerator.getInstance(project).createDummyYamlWithText(newSpecText(roleName, options))
            val top = YamlPaths.topLevelValue(writable) as? YAMLMapping
            val freshTop = YamlPaths.topLevelValue(fresh) as? YAMLMapping
            val freshSpecs = freshTop?.getKeyValueByKey("argument_specs")
            if (top != null && freshSpecs != null && top.getKeyValueByKey("argument_specs") == null) {
                top.putKeyValue(freshSpecs)
            } else if (freshTop != null) {
                writable.documents.firstOrNull()?.replace(fresh.documents.first()) ?: writable.add(fresh.documents.first())
            }
        }
    }
}

/** Creates the role's `meta/argument_specs.yml` with an entry point `main` declaring [options]. */
class CreateArgumentSpecFix(
    private val roleDir: VirtualFile,
    private val roleName: String,
    private val options: Map<String, List<Pair<String, String>>>,
) : LocalQuickFix {
    override fun getName(): String =
        if (options.size == 1) {
            AnsibilitySpecBundle.message("fix.create.spec.one", options.keys.single(), options.values.single().typeText())
        } else {
            AnsibilitySpecBundle.message("fix.create.spec.all", options.size)
        }

    override fun getFamilyName(): String = AnsibilitySpecBundle.message("fix.create.spec.family")

    override fun startInWriteAction(): Boolean = true

    override fun generatePreview(project: Project, previewDescriptor: ProblemDescriptor): IntentionPreviewInfo =
        IntentionPreviewInfo.CustomDiff(YAMLFileType.YML, "meta/argument_specs.yml", "", newSpecText(roleName, options))

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        if (!roleDir.isValid) return
        val meta = VfsUtil.createDirectoryIfMissing(roleDir, "meta")
        if (meta.findChild(SPEC_NAME) != null || meta.findChild(SPEC_NAME_YAML) != null) return
        val spec = meta.createChildData(this, SPEC_NAME)
        VfsUtil.saveText(spec, newSpecText(roleName, options))
        OpenFileDescriptor(project, spec).navigate(false)
    }

    private companion object {
        const val SPEC_NAME = "argument_specs.yml"
        const val SPEC_NAME_YAML = "argument_specs.yaml"
    }
}

/** `str`, `list of str`, `dict` … for a fix name. */
private fun List<Pair<String, String>>.typeText(): String {
    val type = firstOrNull { it.first == "type" }?.second ?: "raw"
    val elements = firstOrNull { it.first == "elements" }?.second
    return if (elements != null) "$type of $elements" else type
}
