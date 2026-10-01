package de.terletzkiy.ansibility.resolve

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiTreeUtil
import de.terletzkiy.ansibility.api.FileContext
import de.terletzkiy.ansibility.api.FileKind
import de.terletzkiy.ansibility.api.RoleRef
import de.terletzkiy.ansibility.api.SourceLocation
import de.terletzkiy.ansibility.api.SpecBinding
import de.terletzkiy.ansibility.api.VarDefKind
import de.terletzkiy.ansibility.api.VarDefinition
import de.terletzkiy.ansibility.api.VarsLayer
import de.terletzkiy.ansibility.index.DefEntry
import de.terletzkiy.ansibility.index.DefSite
import de.terletzkiy.ansibility.index.LiteralType
import de.terletzkiy.ansibility.semantics.schema.ArgSpecParseResult
import de.terletzkiy.ansibility.semantics.schema.ArgSpecParser
import de.terletzkiy.ansibility.yaml.PsiYValueAdapter
import de.terletzkiy.ansibility.yaml.YamlPaths
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLKeyValue

/**
 * Query-time interpretation of `ansible.var.def` entries (plan A.7): the index knows only where a key is written;
 * the [FileContext] of the file decides what kind of definition it is, its layer, environment, group, host and role.
 * An entry whose file kind contradicts its site (a `defaults/` directory outside any role, a skipped file) is dropped.
 */
internal object VarDefinitions {
    /** File kinds whose content is a task list or a playbook. */
    private val TASK_CONTAINERS = setOf(
        FileKind.ROLE_TASKS, FileKind.ROLE_HANDLERS, FileKind.PLAYBOOK, FileKind.MOLECULE_PLAYBOOK,
        FileKind.MOLECULE_TASKS, FileKind.OTHER,
    )

    /** The kind and layer of [entry] in a file classified as [context], or null when the entry does not count there. */
    fun classify(entry: DefEntry, context: FileContext): Pair<VarDefKind, VarsLayer?>? {
        val kind = context.kind
        return when (entry.site) {
            DefSite.SPEC_OPTION -> when {
                kind == FileKind.ROLE_ARGSPEC -> VarDefKind.SPEC_OPTION to null
                // ansible-core reads meta/main.yml argument_specs only when there is no meta/argument_specs.yml.
                kind == FileKind.ROLE_META && !hasArgumentSpecsFile(context) -> VarDefKind.SPEC_OPTION to null
                else -> null
            }
            DefSite.DEFAULTS -> if (kind == FileKind.ROLE_DEFAULTS) VarDefKind.ROLE_DEFAULT to VarsLayer.ROLE_DEFAULTS else null
            DefSite.VARS -> when {
                kind == FileKind.ROLE_VARS -> VarDefKind.ROLE_VAR to VarsLayer.ROLE_VARS
                kind == FileKind.MOLECULE_VARS -> VarDefKind.MOLECULE_INVENTORY to VarsLayer.MOLECULE_INVENTORY
                // A playbook-level vars directory: the target of vars_files or include_vars.
                kind == FileKind.OTHER && context.roleDir == null -> VarDefKind.VARS_FILES to VarsLayer.VARS_FILES
                else -> null
            }
            DefSite.INVENTORY_KEY -> when (kind) {
                FileKind.GROUP_VARS -> VarDefKind.GROUP_VARS to context.layer
                FileKind.HOST_VARS -> VarDefKind.HOST_VARS to context.layer
                FileKind.MOLECULE_VARS -> VarDefKind.MOLECULE_INVENTORY to VarsLayer.MOLECULE_INVENTORY
                else -> null
            }
            DefSite.INVENTORY_INLINE -> if (kind == FileKind.INVENTORY) {
                VarDefKind.INVENTORY_INLINE to if (entry.ownerIsHost) VarsLayer.INVENTORY_FILE_HOST else VarsLayer.INVENTORY_FILE_GROUP
            } else {
                null
            }
            DefSite.MOLECULE_INVENTORY ->
                if (kind == FileKind.MOLECULE_CONFIG) VarDefKind.MOLECULE_INVENTORY to VarsLayer.MOLECULE_INVENTORY else null
            else -> if (kind in TASK_CONTAINERS) taskKind(entry.site) else null
        }
    }

    private fun taskKind(site: DefSite): Pair<VarDefKind, VarsLayer?> = when (site) {
        DefSite.PLAY_VARS -> VarDefKind.PLAY_VARS to VarsLayer.PLAY_VARS
        // ansible-core loads vars_prompt answers right after play vars, which the api numbers as level 13.
        DefSite.VARS_PROMPT -> VarDefKind.VARS_PROMPT to VarsLayer.VARS_FILES
        DefSite.BLOCK_VARS -> VarDefKind.BLOCK_VARS to VarsLayer.BLOCK_TASK_VARS
        DefSite.TASK_VARS -> VarDefKind.TASK_VARS to VarsLayer.BLOCK_TASK_VARS
        DefSite.INCLUDE_PARAMS -> VarDefKind.INCLUDE_PARAMS to VarsLayer.BLOCK_TASK_VARS
        DefSite.ROLE_PARAMS -> VarDefKind.ROLE_PARAMS to VarsLayer.ROLE_PARAMS
        DefSite.SET_FACT -> VarDefKind.SET_FACT to VarsLayer.SET_FACT_REGISTER
        DefSite.REGISTER -> VarDefKind.REGISTER to VarsLayer.SET_FACT_REGISTER
        DefSite.LOOP_VAR -> VarDefKind.LOOP_VAR to null
        DefSite.INDEX_VAR -> VarDefKind.INDEX_VAR to null
        else -> error("not a task site: $site")
    }

    /** The api definition of [entry], or null when it does not count in a file classified as [context]. */
    fun definition(project: Project, name: String, file: VirtualFile, context: FileContext, entry: DefEntry): VarDefinition? {
        val (kind, layer) = classify(entry, context) ?: return null
        val inlineOwner = entry.site == DefSite.INVENTORY_INLINE || entry.site == DefSite.MOLECULE_INVENTORY
        return VarDefinition(
            name = name,
            kind = kind,
            location = SourceLocation(file, entry.offset),
            layer = layer,
            roleName = context.roleName,
            environment = context.environment,
            group = if (inlineOwner && !entry.ownerIsHost) entry.owner else context.group,
            host = if (inlineOwner && entry.ownerIsHost) entry.owner else context.host,
            valueShape = entry.shape,
            preview = entry.preview,
            docComment = if (entry.hasDocComment) docComment(project, file, entry.offset) else null,
            literalType = literalTypeName(entry.literalType),
        )
    }

    /**
     * The api name of an indexed literal type (`str`, `int`, `float`, `bool`, `timestamp`, `null`). Null for
     * everything that is not a loadable scalar literal: containers, Jinja, vault values and bare `=` scalars.
     */
    fun literalTypeName(type: LiteralType): String? = when (type) {
        LiteralType.STR -> "str"
        LiteralType.INT -> "int"
        LiteralType.FLOAT -> "float"
        LiteralType.BOOL -> "bool"
        LiteralType.TIMESTAMP -> "timestamp"
        LiteralType.NULL -> "null"
        LiteralType.NONE, LiteralType.UNLOADABLE -> null
    }

    /** The spec binding of a [DefSite.SPEC_OPTION] entry, or null when the spec cannot be read or has no such option. */
    fun specBinding(project: Project, name: String, file: VirtualFile, context: FileContext, entry: DefEntry): SpecBinding? {
        if (entry.site != DefSite.SPEC_OPTION) return null
        val roleName = context.roleName ?: return null
        val roleDir = context.roleDir ?: return null
        val entryPoint = entry.entryPoint ?: return null
        val yaml = PsiManager.getInstance(project).findFile(file) as? YAMLFile ?: return null
        val option = SpecFiles.parse(yaml).entryPoints[entryPoint]?.options?.get(name) ?: return null
        return SpecBinding(RoleRef(context.root.dir, roleName, roleDir), entryPoint, option, SourceLocation(file, entry.offset))
    }

    private fun hasArgumentSpecsFile(context: FileContext): Boolean {
        val meta = context.roleDir?.findChild("meta") ?: return false
        return listOf("argument_specs.yml", "argument_specs.yaml").any { meta.findChild(it)?.isDirectory == false }
    }

    /** The comment block above the key written at [offset]. */
    private fun docComment(project: Project, file: VirtualFile, offset: Int): String? {
        val yaml = PsiManager.getInstance(project).findFile(file) as? YAMLFile ?: return null
        val keyValue = PsiTreeUtil.getParentOfType(yaml.findElementAt(offset), YAMLKeyValue::class.java, false) ?: return null
        if (keyValue.key?.textRange?.startOffset != offset) return null
        return YamlPaths.docCommentAbove(keyValue)
    }
}

/** Argument specs parsed once per file version (plan A.9 per-file models). */
internal object SpecFiles {
    private val KEY = Key.create<CachedValue<ArgSpecParseResult>>("ansibility.resolve.argumentSpecs")

    /**
     * The entry points of the spec document in [file]. The options' origin names the role directory that holds the
     * file (`roles/<role>/meta/…`), which is what the query-time file context reports as the role.
     */
    fun parse(file: YAMLFile): ArgSpecParseResult =
        CachedValuesManager.getCachedValue(file, KEY) {
            val roleName = file.virtualFile?.parent?.parent?.name
            CachedValueProvider.Result.create(ArgSpecParser.parse(PsiYValueAdapter.documentValue(file), roleName), file)
        }
}
