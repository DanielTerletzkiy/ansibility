package de.terletzkiy.ansibility.completion.keys

import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.FileContext
import de.terletzkiy.ansibility.api.FileKind
import de.terletzkiy.ansibility.index.JinjaBearing
import de.terletzkiy.ansibility.vars.VarKeySites
import de.terletzkiy.ansibility.yaml.PsiYValueAdapter
import de.terletzkiy.ansibility.yaml.YamlPaths
import de.terletzkiy.ansibility.yaml.YamlPsi
import org.jetbrains.yaml.YAMLTokenTypes
import org.jetbrains.yaml.psi.YAMLDocument
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping
import org.jetbrains.yaml.psi.YAMLQuotedText
import org.jetbrains.yaml.psi.YAMLScalar
import org.jetbrains.yaml.psi.YAMLSequence
import org.jetbrains.yaml.psi.YAMLSequenceItem

/**
 * A mapping that holds variables, seen from a completion position: where a new key would go.
 *
 * [varPath] follows the contract of [de.terletzkiy.ansibility.api.AnsibleSite.VarKey.keyPath] for the mapping's
 * owner: empty when the keys are top-level variable names (a vars file's top level, a `vars:` section, an inventory
 * group's `vars:` or host entry), otherwise the path of the variable whose value the mapping is
 * (`["haproxy_servers", "0"]` inside the first server, `["loki_nginx_sites", "0", "floating"]`).
 */
internal class VarContainer(
    val varPath: List<String>,
    /** Roles the section is written for (role entry parameters, `include_role` vars, a play's roles); ranks first. */
    val roles: List<String> = emptyList(),
    /** In a YAML inventory or molecule inventory: the group whose `vars:` these are. */
    val group: String? = null,
    /** In a YAML inventory or molecule inventory: the host whose entry this is. */
    val host: String? = null,
)

/** A position where a variable key is being typed. */
internal class KeyPosition(
    val container: VarContainer,
    /** Keys already written in the mapping, without the key being completed. */
    val existingKeys: Set<String>,
    /** The part of the key before the caret. */
    val prefix: String,
)

/** A position where the value of a variable (or of one element of a list variable) is being typed. */
internal class ValuePosition(
    /** The key path of the key-value whose value this is; for [element], of the list variable plus the item index. */
    val varPath: List<String>,
    /** True for a sequence item: the value is one element of a list. */
    val element: Boolean,
    /** The quote the value is written in (`"` or `'`), or null for a plain scalar. */
    val quote: Char?,
    /** The part of the value before the caret (inside the quotes). */
    val prefix: String,
)

/**
 * One completion request in a vars-like YAML file (plan F4.2): the file's Ansible context and what the caret is on,
 * analysed on the completion copy, where the dummy identifier is part of the PSI.
 *
 * The YAML parser leaves a key being typed without its colon as a plain scalar, so a key position is:
 * - the key of an existing key-value (`post<caret>fix: 2`);
 * - a single-line plain scalar directly in a mapping (`postfix_` on a new line among other keys);
 * - a plain scalar on the line after `key:` and indented below it (the first key of a new mapping value);
 * - a plain scalar that is a sequence item (`- ` in a list of dicts; also a value position);
 * - a plain scalar that is the whole document (an empty vars file).
 *
 * A value position is a single-line plain or quoted scalar after `key:` on the same line, or a sequence item.
 * Whether the mapping holds variables is decided structurally: [VarKeySites] for keys that are already variables
 * (nested levels), plus the sections that own top-level variables (vars files, `vars:` sections, inventory
 * `vars:`/host entries, molecule `provisioner.inventory`). Scalars holding Jinja are never completed here; the Jinja
 * sources own them.
 */
internal class VarsCompletionRequest private constructor(
    val project: Project,
    val root: AnsibleRoot,
    val context: FileContext,
    /** The original file. */
    val file: VirtualFile,
    /** The completion copy. */
    val yaml: YAMLFile,
    /** The leaf at the caret in [yaml] (it contains the dummy identifier). */
    private val leaf: PsiElement,
    val offset: Int,
) {
    /** The key position under the caret, if any. */
    val keyPosition: KeyPosition? by lazy(LazyThreadSafetyMode.NONE) { locateKey() }

    /** The value position under the caret, if any. */
    val valuePosition: ValuePosition? by lazy(LazyThreadSafetyMode.NONE) { locateValue() }

    private val taskSections: TaskVarSections by lazy(LazyThreadSafetyMode.NONE) { TaskVarSections.of(yaml, context.roleName) }

    private fun locateKey(): KeyPosition? {
        return when (leaf.node.elementType) {
            YAMLTokenTypes.SCALAR_KEY -> {
                val keyValue = leaf.parent as? YAMLKeyValue ?: return null
                if (keyValue.key != leaf) return null
                val mapping = keyValue.parent as? YAMLMapping ?: return null
                val container = inMapping(mapping) ?: return null
                KeyPosition(container, keysOf(mapping, except = keyValue), keyPrefix())
            }
            YAMLTokenTypes.TEXT -> {
                val scalar = singleLinePlain(leaf) ?: return null
                when (val parent = scalar.parent) {
                    is YAMLMapping -> inMapping(parent)?.let { KeyPosition(it, keysOf(parent, except = null), keyPrefix()) }
                    is YAMLKeyValue -> {
                        if (parent.value != scalar || !isBelowKey(parent, scalar)) return null
                        underKey(parent)?.let { KeyPosition(it, emptySet(), keyPrefix()) }
                    }
                    is YAMLSequenceItem -> underItem(parent, null)?.let { KeyPosition(it, emptySet(), keyPrefix()) }
                    is YAMLDocument -> topLevel()?.let { KeyPosition(it, emptySet(), keyPrefix()) }
                    else -> null
                }
            }
            else -> null
        }
    }

    private fun locateValue(): ValuePosition? {
        val type = leaf.node.elementType
        val scalar: YAMLScalar
        val quote: Char?
        when (type) {
            YAMLTokenTypes.TEXT -> {
                scalar = singleLinePlain(leaf) ?: return null
                quote = null
            }
            YAMLTokenTypes.SCALAR_STRING, YAMLTokenTypes.SCALAR_DSTRING -> {
                scalar = leaf.parent as? YAMLQuotedText ?: return null
                if (scalar.textContains('\n') || JinjaBearing.hasTemplateMarkers(scalar.text)) return null
                quote = leaf.text.firstOrNull()
                if (offset <= leaf.textRange.startOffset) return null
            }
            else -> return null
        }
        val prefixStart = if (quote == null) leaf.textRange.startOffset else leaf.textRange.startOffset + 1
        val prefix = yaml.viewProvider.contents.subSequence(prefixStart, offset.coerceAtLeast(prefixStart)).toString()
        return when (val parent = scalar.parent) {
            is YAMLKeyValue -> {
                if (parent.value != scalar || isBelowKey(parent, scalar)) return null
                val site = VarKeySites.of(parent, context, file) ?: return null
                ValuePosition(site.site.keyPath, element = false, quote = quote, prefix = prefix)
            }
            is YAMLSequenceItem -> {
                val container = underItem(parent, null)?.takeIf { it.varPath.isNotEmpty() } ?: return null
                ValuePosition(container.varPath, element = true, quote = quote, prefix = prefix)
            }
            else -> null
        }
    }

    /** The plain scalar [leaf] belongs to, when it is a single line without Jinja. */
    private fun singleLinePlain(leaf: PsiElement): YAMLScalar? {
        val scalar = leaf.parent as? YAMLScalar ?: return null
        if (scalar is YAMLQuotedText || scalar.textContains('\n')) return null
        if (JinjaBearing.hasTemplateMarkers(scalar.text)) return null
        return scalar
    }

    /** True when [scalar] (the value of [keyValue]) starts on a later line than the key, indented below it. */
    private fun isBelowKey(keyValue: YAMLKeyValue, scalar: PsiElement): Boolean {
        val key = keyValue.key ?: return false
        val between = TextRange(key.textRange.endOffset, scalar.textRange.startOffset)
        if (between.isEmpty || !yaml.viewProvider.contents.subSequence(between.startOffset, between.endOffset).contains('\n')) return false
        return YamlPsi.columnOf(scalar) > YamlPsi.columnOf(key)
    }

    private fun inMapping(mapping: YAMLMapping): VarContainer? = when (val parent = mapping.parent) {
        is YAMLDocument -> topLevel()
        is YAMLKeyValue -> underKey(parent)
        is YAMLSequenceItem -> underItem(parent, mapping)
        else -> null
    }

    /** The top level of a vars file holds variable names; other files' top levels are structural. */
    private fun topLevel(): VarContainer? = if (context.kind in VARS_FILE_KINDS) VarContainer(emptyList()) else null

    /** The mapping that is (or will be) the value of [owner]. */
    private fun underKey(owner: YAMLKeyValue): VarContainer? {
        VarKeySites.of(owner, context, file)?.let { return VarContainer(it.site.keyPath) }
        return when (context.kind) {
            FileKind.INVENTORY -> InventoryVarOwners.inventory(YamlPaths.keyPath(owner))
            FileKind.MOLECULE_CONFIG -> InventoryVarOwners.molecule(YamlPaths.keyPath(owner))
            in TASK_KINDS -> {
                val key = owner.key ?: return null
                taskSections.sections[key.textRange.startOffset]?.let { VarContainer(emptyList(), roles = it) }
            }
            else -> null
        }
    }

    /** The mapping that is (or will be) sequence item [item]'s value, [mapping] when it exists. */
    private fun underItem(item: YAMLSequenceItem, mapping: YAMLMapping?): VarContainer? {
        if (mapping != null && context.kind in TASK_KINDS) {
            taskSections.roleEntries[mapping.textRange]?.let { return VarContainer(emptyList(), roles = listOf(it)) }
        }
        val sequence = item.parent as? YAMLSequence ?: return null
        val owner = sequence.parent as? YAMLKeyValue ?: return null
        val site = VarKeySites.of(owner, context, file) ?: return null
        return VarContainer(site.site.keyPath + sequence.items.indexOf(item).toString())
    }

    /** The key texts of [mapping] (merge keys left out), without [except]. */
    private fun keysOf(mapping: YAMLMapping, except: YAMLKeyValue?): Set<String> =
        mapping.keyValues.filter { it != except && !YamlPsi.isMergeKey(it) }.mapTo(LinkedHashSet()) { PsiYValueAdapter.keyOf(it).text }

    /** The key characters right before the caret. */
    private fun keyPrefix(): String {
        val text = yaml.viewProvider.contents
        var start = offset
        while (start > 0 && isKeyChar(text[start - 1])) start--
        return text.subSequence(start, offset).toString()
    }

    companion object {
        /** Files whose top-level mapping holds variables. */
        val VARS_FILE_KINDS: Set<FileKind> = setOf(
            FileKind.ROLE_DEFAULTS, FileKind.ROLE_VARS, FileKind.GROUP_VARS, FileKind.HOST_VARS, FileKind.MOLECULE_VARS,
        )

        /** Task files and playbooks, whose `vars:` sections, `set_fact` arguments and role parameters hold variables. */
        val TASK_KINDS: Set<FileKind> = setOf(
            FileKind.ROLE_TASKS, FileKind.ROLE_HANDLERS, FileKind.PLAYBOOK, FileKind.MOLECULE_PLAYBOOK, FileKind.MOLECULE_TASKS,
        )

        private val SUPPORTED_KINDS: Set<FileKind> = VARS_FILE_KINDS + TASK_KINDS + setOf(FileKind.INVENTORY, FileKind.MOLECULE_CONFIG)

        /**
         * The request for [parameters], or null when completion is not ours: not basic completion, not YAML, or a
         * file outside a root or of a kind without variable keys. Cheap; the position is analysed lazily.
         */
        fun of(parameters: CompletionParameters): VarsCompletionRequest? {
            if (parameters.completionType != CompletionType.BASIC) return null
            val yaml = parameters.position.containingFile as? YAMLFile ?: return null
            val original = parameters.originalFile
            val project = original.project
            val file = original.viewProvider.virtualFile
            val context = AnsibleWorkspace.getInstance(project).contextOf(file) ?: return null
            if (context.kind !in SUPPORTED_KINDS) return null
            return VarsCompletionRequest(project, context.root, context, file, yaml, parameters.position, parameters.offset)
        }

        /** Characters of variable and option names. */
        fun isKeyChar(char: Char): Boolean = char.isLetterOrDigit() || char == '_' || char == '-'
    }
}

/**
 * The owners of top-level variables in YAML inventories (`hosts.yml`) and in molecule's `provisioner.inventory`,
 * decided by the structural key path of the mapping's owner: a group's `vars:` (`children` nest to any depth), a host
 * entry under a group's `hosts:`, and molecule's `group_vars`/`host_vars` owners.
 */
internal object InventoryVarOwners {
    private const val VARS = "vars"
    private const val HOSTS = "hosts"
    private const val CHILDREN = "children"

    /** In a YAML inventory: the container whose keys are variables when [path] is its owner's key path, or null. */
    fun inventory(path: List<String>, from: Int = 0): VarContainer? {
        var group = from
        while (group + 1 < path.size) {
            when (path[group + 1]) {
                VARS -> return if (path.size == group + 2) VarContainer(emptyList(), group = path[group]) else null
                HOSTS -> return if (path.size == group + 3) VarContainer(emptyList(), host = path[group + 2]) else null
                CHILDREN -> group += 2
                else -> return null
            }
        }
        return null
    }

    /** In `molecule.yml`: `provisioner.inventory.{group_vars,host_vars}.<owner>`, or an inline inventory under `hosts`. */
    fun molecule(path: List<String>): VarContainer? {
        if (path.size < 3 || path[0] != "provisioner" || path[1] != "inventory") return null
        return when (path[2]) {
            "group_vars" -> if (path.size == 4) VarContainer(emptyList(), group = path[3]) else null
            "host_vars" -> if (path.size == 4) VarContainer(emptyList(), host = path[3]) else null
            HOSTS -> inventory(path, 3)
            else -> null
        }
    }
}
