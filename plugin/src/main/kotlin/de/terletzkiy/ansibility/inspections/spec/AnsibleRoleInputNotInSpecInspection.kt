package de.terletzkiy.ansibility.inspections.spec

import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.psi.PsiFile
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.RoleInfo
import de.terletzkiy.ansibility.api.RoleRegistry
import de.terletzkiy.ansibility.api.VarDefKind
import de.terletzkiy.ansibility.context.TargetVersionDetector
import de.terletzkiy.ansibility.inspections.types.SpecEdits
import de.terletzkiy.ansibility.inspections.undefined.GuardRules
import de.terletzkiy.ansibility.inspections.undefined.UndefinedRules
import de.terletzkiy.ansibility.inspections.undefined.UseSites
import de.terletzkiy.ansibility.semantics.diagnostics.DiagnosticCode
import de.terletzkiy.ansibility.semantics.diagnostics.Level
import de.terletzkiy.ansibility.semantics.yaml.YValue
import de.terletzkiy.ansibility.settings.SeverityPolicy
import de.terletzkiy.ansibility.settings.atMost
import de.terletzkiy.ansibility.settings.toProblemHighlightType
import de.terletzkiy.ansibility.vars.VarLocations
import de.terletzkiy.ansibility.yaml.PsiYValueAdapter
import org.jetbrains.yaml.YAMLUtil
import org.jetbrains.yaml.psi.YAMLFile

/**
 * ANS-S002 "role input not in the argument spec": a variable a role takes from outside that its
 * `meta/argument_specs.yml` does not declare, so ansible-core never validates it and the card has no type for it.
 *
 * Role inputs are the top-level keys of the role's `defaults/` files and the free Jinja uses in its tasks, handlers,
 * defaults and templates of names nothing inside the role sets: not a Jinja local, loop variable, magic variable or fact,
 * not a `vars/` key, not set by the role's own tasks (`set_fact`, `register`, `include_vars`, task or block `vars:`).
 * What is left comes from the inventory, the play or the caller, exactly what an argument spec documents.
 *
 * A role without an argument spec gets the same findings one level lower (a suggestion while the role is new), with a
 * fix that creates `meta/argument_specs.yml`. The fixes write every input of the file at once, typed from its values
 * ([SpecEdits.fieldsFor]): the `defaults/` value, else the literal definitions elsewhere in the root.
 *
 * Severity only through [SeverityPolicy] (WARNING in every preset). Call in a read action in smart mode.
 */
class AnsibleRoleInputNotInSpecInspection : LocalInspectionTool() {

    override fun checkFile(file: PsiFile, manager: InspectionManager, isOnTheFly: Boolean): Array<ProblemDescriptor>? {
        val project = file.project
        if (DumbService.isDumb(project) || InjectedLanguageManager.getInstance(project).isInjectedFragment(file)) return null
        val viewProvider = file.viewProvider
        if (viewProvider.getPsi(viewProvider.baseLanguage) != file) return null
        val virtualFile = viewProvider.virtualFile
        val context = AnsibleWorkspace.getInstance(project).contextOf(virtualFile) ?: return null
        val role = RoleRegistry.getInstance(project).roleOf(virtualFile) ?: return null
        val inputs = RoleInputs(project, context.root, role).of(file) ?: return null
        if (inputs.isEmpty()) return null

        val level = SeverityPolicy.getInstance(project).level(DiagnosticCode.S002_SPEC_DEFAULTS_SYNC, context.root)
        val highlight = (if (role.specFile == null) level.atMost(Level.WEAK_WARNING) else level).toProblemHighlightType() ?: return null
        val all = inputs.associate { it.name to it.fields }
        return inputs.map { input ->
            ProgressManager.checkCanceled()
            val message = if (role.specFile == null) {
                AnsibilitySpecBundle.message("inspection.s002.no.spec", input.name, role.ref.name)
            } else {
                AnsibilitySpecBundle.message("inspection.s002.message", input.name, role.ref.name)
            }
            val fixes = fixes(role, input, all)
            manager.createProblemDescriptor(file, input.range, message, highlight, isOnTheFly, *fixes)
        }.toTypedArray()
    }

    private fun fixes(role: RoleInfo, input: RoleInput, all: Map<String, List<Pair<String, String>>>): Array<LocalQuickFix> {
        val one = mapOf(input.name to input.fields)
        val spec = role.specFile
        if (spec == null) {
            val create = CreateArgumentSpecFix(role.ref.dir, role.ref.name, all)
            return if (all.size > 1) arrayOf(create, CreateArgumentSpecFix(role.ref.dir, role.ref.name, one)) else arrayOf(create)
        }
        val entry = entryPoint(role)
        val add = AddToArgumentSpecFix(spec, role.ref.name, entry, one)
        return if (all.size > 1) arrayOf(add, AddToArgumentSpecFix(spec, role.ref.name, entry, all)) else arrayOf(add)
    }

    /** `main` when the spec has it (the role's default entry point), else its first entry point. */
    private fun entryPoint(role: RoleInfo): String = if ("main" in role.argumentSpecs || role.argumentSpecs.isEmpty()) "main" else role.argumentSpecs.keys.first()

    companion object {
        const val SHORT_NAME: String = "AnsibleRoleInputNotInSpec"
    }
}

/** One undeclared input of a role in one file: the first place the file names it, and the spec fields to add. */
internal class RoleInput(val name: String, val range: TextRange, val fields: List<Pair<String, String>>)

/** Finds the undeclared inputs of [role] (see [AnsibleRoleInputNotInSpecInspection]). */
internal class RoleInputs(private val project: Project, private val root: AnsibleRoot, private val role: RoleInfo) {
    private val rules = UndefinedRules(project, root)

    /** The undeclared inputs named in [file], in source order, each once; null when [file] is no input-bearing role file. */
    fun of(file: PsiFile): List<RoleInput>? {
        val virtualFile = file.viewProvider.virtualFile
        val isDefaults = virtualFile in role.defaultsFiles
        val isTemplate = role.templatesDir?.let { VfsUtilCore.isAncestor(it, virtualFile, true) } == true
        val isTasks = virtualFile in role.taskFiles || virtualFile in role.handlerFiles
        if (!isDefaults && !isTemplate && !isTasks) return null

        val found = LinkedHashMap<String, RoleInput>()
        if (isDefaults) {
            val yaml = file as? YAMLFile ?: return null
            for (keyValue in YAMLUtil.getTopLevelKeys(yaml)) {
                val name = keyValue.keyText
                val key = keyValue.key ?: continue
                if (name.isEmpty() || isDeclared(name) || name in found) continue
                found[name] = RoleInput(name, key.textRange, SpecEdits.fieldsFor(PsiYValueAdapter.valueOf(keyValue)))
            }
        }
        val guardRules = GuardRules.of(TargetVersionDetector.getInstance(project).targetVersion(root).version)
        val uses = when {
            isTemplate -> UseSites.template(file, guardRules)
            file is YAMLFile -> UseSites.yaml(file, guardRules)
            else -> emptyList()
        }
        for (use in uses) {
            ProgressManager.checkCanceled()
            val name = use.name
            if (name in found || !isExternalInput(name)) continue
            found[name] = RoleInput(name, use.nameRange, fieldsFromDefinitions(name))
        }
        return found.values.sortedBy { it.range.startOffset }
    }

    /** A spec of the role declares [name] (or an alias of it) for some entry point. */
    private fun isDeclared(name: String): Boolean =
        role.argumentSpecs.values.any { spec -> name in spec.options || spec.options.values.any { name in it.aliases } }

    /** Whether [name] reaches the role from outside and no spec declares it. */
    private fun isExternalInput(name: String): Boolean {
        if (isDeclared(name) || UndefinedRules.isAlwaysDefined(name) || rules.isLoopVariable(name)) return false
        if (rules.hasRuntimeDefault(role, name) || rules.setByRoleTasks(role, name)) {
            // A default of the role itself is reported on its defaults key, a `vars/` key is internal.
            return false
        }
        val dirs = rules.withDependencies(role).map { it.ref.dir }
        return rules.symbol(name).definitions.none { definition ->
            definition.kind in INTERNAL_KINDS && dirs.any { VfsUtilCore.isAncestor(it, definition.location.file, true) }
        }
    }

    /** The spec fields for [name] from its literal definitions in the root (inventory, play vars, role params). */
    private fun fieldsFromDefinitions(name: String): List<Pair<String, String>> {
        val values = ArrayList<YValue>()
        for (definition in rules.symbol(name).definitions) {
            ProgressManager.checkCanceled()
            if (definition.kind == VarDefKind.SPEC_OPTION || definition.kind in INTERNAL_KINDS) continue
            val keyValue = VarLocations.keyValueAt(project, definition.location) ?: continue
            values += PsiYValueAdapter.valueOf(keyValue)
            if (values.size == MAX_SAMPLED_DEFINITIONS) break
        }
        if (values.isEmpty()) return listOf("type" to "raw")
        val fields = values.map(SpecEdits::fieldsFor)
        return fields.distinct().singleOrNull() ?: listOf("type" to SpecEdits.join(fields.map { it.first().second }))
    }

    private companion object {
        const val MAX_SAMPLED_DEFINITIONS = 16

        /** Names a role sets for itself: never inputs. */
        val INTERNAL_KINDS: Set<VarDefKind> = setOf(
            VarDefKind.ROLE_VAR, VarDefKind.TASK_VARS, VarDefKind.BLOCK_VARS, VarDefKind.TEMPLATE_VARS, VarDefKind.REGISTER,
            VarDefKind.SET_FACT, VarDefKind.LOOP_VAR, VarDefKind.INDEX_VAR, VarDefKind.JINJA_LOCAL, VarDefKind.INCLUDE_PARAMS,
        )
    }
}

/** The role directory's `meta/argument_specs.yml` text for a new spec with [options] (name → fields). */
internal fun newSpecText(roleName: String, options: Map<String, List<Pair<String, String>>>): String = buildString {
    append("---\nargument_specs:\n  main:\n    short_description: ").append(SpecEdits.keyText(roleName)).append('\n')
    append("    options:\n")
    for ((name, fields) in options) {
        append("      ").append(SpecEdits.keyText(name)).append(":\n")
        for ((key, value) in fields) append("        ").append(key).append(": ").append(value).append('\n')
    }
}
