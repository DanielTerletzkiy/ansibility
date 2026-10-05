package de.terletzkiy.ansibility.typeflow

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.util.PsiTreeUtil
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.ValueShape
import de.terletzkiy.ansibility.api.VarDefKind
import de.terletzkiy.ansibility.api.VarDefinition
import de.terletzkiy.ansibility.api.VarService
import de.terletzkiy.ansibility.facts.FactsCatalog
import de.terletzkiy.ansibility.index.ValueSummary
import de.terletzkiy.ansibility.model.task.YamlFiles
import de.terletzkiy.ansibility.resolve.register.RegisteredResult
import de.terletzkiy.ansibility.resolve.register.RegisteredResults
import de.terletzkiy.ansibility.semantics.registered.ResultShapes
import de.terletzkiy.ansibility.semantics.typeflow.AValue
import de.terletzkiy.ansibility.semantics.typeflow.VariableDefinition
import de.terletzkiy.ansibility.semantics.typeflow.VariableResolver
import de.terletzkiy.ansibility.semantics.yaml.YVault
import de.terletzkiy.ansibility.semantics.yaml.YValue
import de.terletzkiy.ansibility.yaml.PsiYValueAdapter
import org.jetbrains.yaml.psi.YAMLKeyValue
import java.util.Optional

/**
 * The [VariableResolver] of the T020 chains (plan A.5, F3.4): every definition [VarService] knows for a name in
 * [root] (the same root family as everything else, so no other root or detached worktree), read from the YAML PSI.
 *
 * - **Reachable** means "defined anywhere in the root": role defaults and vars of every role, inventory and playbook
 *   `group_vars`/`host_vars`, `hosts.yml` inline vars, molecule inventories, play/block/task vars, include and role
 *   parameters, `set_fact`. Taking all of them can only make a finding rarer (the must-rule needs every one to be
 *   outside the documented type), so no precedence guess is involved. Spec declarations are not values (ansible-core
 *   never injects spec defaults) and are skipped.
 * - **Runtime-only values** have no readable value: `register`, loop and index variables, `vars_prompt` answers,
 *   free-form `set_fact`, and names that facts or special variables provide at runtime (injected `ansible_*` facts,
 *   `ansible_facts`, `hostvars`, `inventory_hostname` …; connection variables such as `ansible_port` are ordinary
 *   inventory variables). Such a name is not followed, so its chain stays unknown.
 * - **Registered members** (plan amendment FU, F1.12): a member of a registered result has its documented type as
 *   logical type ([memberType]: `{{ x.rc }}` is an `int`), from the union of the name's `register:` tasks in the checked
 *   file's role (the root outside roles); undocumented members, types the tasks disagree on and names with other
 *   definitions stay unknown.
 * - **Secrets**: values in vault files, of `vault_*` names and `!vault` values are typed but never shown
 *   ([ValueSummary.isSecret], [ValueSummary.isVaultFileName]).
 * - **Labels** are `path:line`, relative to [roleDir] for files of the checked file's role, else relative to the root.
 *
 * Results are memoised per name for the lifetime of the resolver (one inspection pass). Call inside a read action.
 */
internal class ChainResolver(
    private val project: Project,
    private val root: AnsibleRoot,
    private val roleDir: VirtualFile?,
) : VariableResolver {
    private val service = VarService.getInstance(project)
    private val memo = HashMap<String, List<VariableDefinition>?>()

    override fun definitions(name: String): List<VariableDefinition>? = memo.getOrPut(name) { compute(name) }

    private val registered = HashMap<String, Optional<RegisteredResult>>()

    /**
     * The logical type of the member `name.path…` of a registered result: its documented type ([ResultShapes.logicalType]),
     * or null when [name] is no registered variable (some definition of it in the root is not a `register:`), the result
     * does not document [path], or its type says nothing checkable. Only members have a type: the whole result stays
     * unknown.
     */
    override fun memberType(name: String, path: List<String>): AValue? {
        if (path.isEmpty()) return null
        val result = registered.getOrPut(name) { Optional.ofNullable(registeredResult(name)) }.orElse(null) ?: return null
        return result.member(path)?.let(ResultShapes::logicalType)
    }

    private fun registeredResult(name: String): RegisteredResult? {
        ProgressManager.checkCanceled()
        if (isRuntimeProvided(name)) return null
        val definitions = service.symbol(root, name).definitions.filter { it.kind != VarDefKind.SPEC_OPTION }
        if (definitions.isEmpty() || definitions.any { it.kind != VarDefKind.REGISTER }) return null
        return RegisteredResults.getInstance(project).inScope(root, roleDir, name)
    }

    private fun compute(name: String): List<VariableDefinition>? {
        ProgressManager.checkCanceled()
        if (isRuntimeProvided(name)) return null
        val definitions = service.symbol(root, name).definitions.filter { it.kind != VarDefKind.SPEC_OPTION }
        if (definitions.isEmpty()) return null
        return definitions.map { definition ->
            val value = valueOf(definition)
            val secret = ValueSummary.isVaultFileName(definition.location.file.name) || name.startsWith(VAULT_PREFIX) || value is YVault
            VariableDefinition(label(definition.location.file, definition.location.offset), value, secret)
        }
    }

    /** The value written at [definition], or null when it is assigned at runtime or cannot be read. */
    private fun valueOf(definition: VarDefinition): YValue? {
        if (definition.kind in RUNTIME_KINDS) return null
        if (definition.valueShape == ValueShape.VAULT) return YVault()
        val location = definition.location
        val yaml = YamlFiles.yamlFile(project, location.file) ?: return null
        val keyValue = PsiTreeUtil.getParentOfType(yaml.findElementAt(location.offset), YAMLKeyValue::class.java, false) ?: return null
        // A free-form `set_fact` names the variable inside a scalar: no key starts there.
        if (keyValue.key?.textRange?.startOffset != location.offset) return null
        if (PsiYValueAdapter.keyOf(keyValue).text != definition.name) return null
        return PsiYValueAdapter.valueOf(keyValue)
    }

    /** `defaults/main.yml:12` (inside the checked role) or `environments/prod/group_vars/all.yml:4` (from the root). */
    fun label(file: VirtualFile, offset: Int): String {
        val base = roleDir?.takeIf { VfsUtilCore.isAncestor(it, file, true) }
            ?: root.dir.takeIf { VfsUtilCore.isAncestor(it, file, true) }
            ?: root.parentDir?.takeIf { VfsUtilCore.isAncestor(it, file, true) }
        val path = base?.let { VfsUtilCore.getRelativePath(file, it) } ?: file.name
        val text = YamlFiles.yamlFile(project, file)?.viewProvider?.contents
        val line = text?.let { StringUtil.offsetToLineNumber(it, offset.coerceIn(0, it.length)) + 1 }
        return if (line == null) path else "$path:$line"
    }

    companion object {
        private const val VAULT_PREFIX = "vault_"

        /** Definitions whose value only exists at runtime. */
        private val RUNTIME_KINDS = setOf(
            VarDefKind.REGISTER, VarDefKind.LOOP_VAR, VarDefKind.INDEX_VAR, VarDefKind.VARS_PROMPT,
            VarDefKind.JINJA_LOCAL, VarDefKind.TEMPLATE_VARS,
        )

        /** Names whose value facts or ansible-core itself provide at runtime, whatever the root defines. */
        fun isRuntimeProvided(name: String): Boolean {
            if (name == "ansible_facts" || name in FactsCatalog.injected) return true
            val magic = FactsCatalog.magicVars[name] ?: return false
            return !magic.connection
        }
    }
}
