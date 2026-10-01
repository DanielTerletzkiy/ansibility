package de.terletzkiy.ansibility.vars

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.SourceLocation
import de.terletzkiy.ansibility.api.SpecBinding
import de.terletzkiy.ansibility.api.ValueShape
import de.terletzkiy.ansibility.api.VarDefKind
import de.terletzkiy.ansibility.api.VarDefinition
import de.terletzkiy.ansibility.api.VarService
import de.terletzkiy.ansibility.api.VarsLayer
import de.terletzkiy.ansibility.context.TargetVersionDetector
import de.terletzkiy.ansibility.index.JinjaBearing
import de.terletzkiy.ansibility.semantics.CoreVersion
import de.terletzkiy.ansibility.semantics.coerce.CoreSemantics
import de.terletzkiy.ansibility.semantics.diagnostics.DiagnosticCode
import de.terletzkiy.ansibility.semantics.schema.OptionSpec
import de.terletzkiy.ansibility.semantics.validate.SpecKind
import de.terletzkiy.ansibility.semantics.validate.SpecValidator
import de.terletzkiy.ansibility.semantics.value.PyValue
import de.terletzkiy.ansibility.semantics.value.pyEquals
import de.terletzkiy.ansibility.semantics.yaml.YScalar
import de.terletzkiy.ansibility.semantics.yaml.YValue
import de.terletzkiy.ansibility.yaml.PsiYValueAdapter
import de.terletzkiy.ansibility.yaml.YamlPaths
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLSequenceItem

/**
 * Builds the [VarCard] of a [VarSubject] from the root-scoped [VarService] symbol (plan F1.2, F4.3, F4.8):
 * - the documenting spec is the own role's, else the best-ranked declaring role's ([VarRanking]); a nested path
 *   documents the nested option;
 * - the runtime default is that role's `defaults/` value as written, with a bare `{{ name }}` followed through
 *   the same role's defaults (depth ≤ 8);
 * - ⚠ badges when the spec default differs from the runtime default, or the runtime default contradicts the
 *   documented type (the documented-type rules of `SpecValidator`, at the root's target ansible-core);
 * - "Declared by" lists the other declaring roles, "Set in" every other definition grouped by environment, and a
 *   definition subject gets "This definition".
 *
 * Needs a read action and smart mode.
 */
internal object VarCards {
    private const val MAX_CHAIN = 8
    private const val MAX_OPTION_DEPTH = 4
    private const val MAX_OPTION_ROWS = 60

    private val BARE_REFERENCE = Regex("""^\s*\{\{-?\s*([A-Za-z_][A-Za-z0-9_]*)\s*-?}}\s*$""")

    /** Documented-type findings that make a runtime default "lossy" for its documented type (X06). */
    private val LOSSY = setOf(
        DiagnosticCode.T001_VALUE_REJECTED, DiagnosticCode.T010_SHAPE_CONTRADICTION, DiagnosticCode.T011_COERCED_SCALAR_TO_STR,
        DiagnosticCode.T013_SCALAR_TYPE_MISMATCH, DiagnosticCode.T014_LEGACY_COERCION, DiagnosticCode.T016_STRING_FOR_NUMBER_OR_BOOL,
    )

    fun build(project: Project, subject: VarSubject): VarCard? {
        val root = subject.root(project) ?: return null
        val context = AnsibleWorkspace.getInstance(project).contextOf(subject.file)
        val ownRole = context?.takeIf { it.root.dir == root.dir }?.roleName
        val symbol = VarService.getInstance(project).symbol(root, subject.name)
        val ranking = VarRanking(project, root, ownRole, subject.file, context?.kind, symbol)
        if (subject.origin == VarSubject.Origin.LOCAL) localCard(project, root, subject)?.let { return it }

        val binding = primaryBinding(ranking, subject.path)
        val nested = binding?.let { SpecOptions.resolve(it.option, subject.path) }
        val option = nested?.option ?: binding?.option
        val optionNames = nested?.names.orEmpty()
        val valuePath = if (nested != null) subject.path else emptyList()
        val role = binding?.role?.name
            ?: ownRole?.takeIf { ranking.roleDefinitions(it, VarDefKind.ROLE_DEFAULT).isNotEmpty() }
            ?: ranking.declaringRoles.firstOrNull()
        val defaultDefinition = role?.let { ranking.roleDefinitions(it, VarDefKind.ROLE_DEFAULT).firstOrNull() }
        val thisDefinition = thisDefinitionOf(subject, symbol.definitions)
        val runtime = defaultDefinition?.let { runtimeDefault(project, root, subject.name, role, it, valuePath) }

        val specDescription = option?.description.orEmpty().filter { it.isNotBlank() }
        val comment = defaultDefinition?.docComment?.takeIf { valuePath.isEmpty() }
        val commentDescription = comment?.takeIf { specDescription.isEmpty() }
        val fromComment = commentDescription != null
        val description = commentDescription?.let(::listOf) ?: specDescription
        val shownRuntime = if (fromComment && runtime != null) runtime.withoutComment() else runtime

        val badges = if (option != null && runtime != null) badges(project, root, option, runtime) else emptyList()
        val missing = when {
            option == null || valuePath.isNotEmpty() || runtime != null -> null
            option.default != null -> MissingDefault.DOCUMENTED
            option.required -> MissingDefault.REQUIRED
            else -> MissingDefault.NONE
        }
        val (rows, truncated) = option?.let(::subOptionRows) ?: (emptyList<SubOptionRow>() to 0)
        val title = (listOf(subject.name) + optionNames).joinToString(".")
        val shownThis = thisDefinition?.takeIf { it.kind != VarDefKind.SPEC_OPTION }
        return VarCard(
            root = root,
            subject = subject,
            title = if (nested == null && binding == null) subject.displayName else title,
            role = role,
            binding = binding,
            option = option,
            optionNames = optionNames,
            description = description,
            descriptionFromComment = fromComment,
            runtimeDefault = shownRuntime,
            missingDefault = missing,
            badges = badges,
            subOptions = rows,
            subOptionsTruncated = truncated,
            declaredBy = declaredBy(project, root, ranking, binding, option, subject.path),
            setIn = setIn(project, root, subject, symbol.definitions, setOfNotNull(defaultDefinition, shownThis)),
            setInOf = subject.name.takeIf { optionNames.isNotEmpty() },
            thisDefinition = shownThis?.let { thisDefinitionCard(project, root, subject.name, it, defaultDefinition, runtime) },
            note = note(project, root, subject, symbol.definitions.isEmpty() && symbol.specBindings.isEmpty()),
            loop = subject.loop,
        )
    }

    /** The binding that documents the card: the own role's, else the best-ranked one whose options resolve [path]. */
    private fun primaryBinding(ranking: VarRanking, path: List<String>): SpecBinding? {
        val bindings = ranking.bindings
        if (path.isEmpty()) return bindings.firstOrNull()
        return bindings.firstOrNull { SpecOptions.resolve(it.option, path) != null } ?: bindings.firstOrNull()
    }

    private fun thisDefinitionOf(subject: VarSubject, definitions: List<VarDefinition>): VarDefinition? {
        if (subject.origin != VarSubject.Origin.DEFINITION) return null
        val offset = subject.offset
        return definitions.firstOrNull { it.location.file == subject.file && it.location.offset == offset }
    }

    // ------------------------------------------------------------------------------------------------ runtime default

    private fun runtimeDefault(
        project: Project,
        root: AnsibleRoot,
        name: String,
        role: String,
        definition: VarDefinition,
        valuePath: List<String>,
    ): RuntimeDefault? {
        val label = VarLocations.label(root, definition.location)
        val keyValue = VarLocations.keyValueAt(project, definition.location)
        if (isSecret(name, definition, keyValue)) {
            return RuntimeDefault(definition, label, null, null, false, secretText(root, definition, keyValue, long = true), definition.docComment, null)
        }
        keyValue ?: return null
        val element: PsiElement = if (valuePath.isEmpty()) keyValue else keyValue.value?.let { YamlPaths.find(it, valuePath) } ?: return null
        val value: YValue = when (element) {
            is YAMLKeyValue -> PsiYValueAdapter.valueOf(element)
            is YAMLSequenceItem -> PsiYValueAdapter.valueOf(element)
            else -> return null
        }
        val text = when (element) {
            is YAMLKeyValue -> element.value?.text
            is YAMLSequenceItem -> element.value?.text
            else -> null
        }.orEmpty().trim().ifEmpty { "null" }
        val jinja = JinjaBearing.hasTemplateMarkers(text)
        val chain = if (jinja) chain(project, root, role, value) else null
        return RuntimeDefault(definition, label, text, value, jinja, null, definition.docComment?.takeIf { valuePath.isEmpty() }, chain)
    }

    private fun RuntimeDefault.withoutComment() = RuntimeDefault(definition, label, text, value, isJinja, secret, null, chain)

    /** Follows a bare `{{ name }}` default through [role]'s defaults until a literal (cycles and depth > 8 give null). */
    private fun chain(project: Project, root: AnsibleRoot, role: String, start: YValue): DefaultChain? {
        var current = (start as? YScalar)?.let { BARE_REFERENCE.matchEntire(it.text)?.groupValues?.get(1) } ?: return null
        val seen = HashSet<String>()
        val service = VarService.getInstance(project)
        repeat(MAX_CHAIN) {
            ProgressManager.checkCanceled()
            if (!seen.add(current)) return null
            val definition = service.symbol(root, current).definitions
                .filter { it.kind == VarDefKind.ROLE_DEFAULT && it.roleName == role }
                .minByOrNull { if (it.location.file.nameWithoutExtension == "main") 0 else 1 } ?: return null
            if (definition.valueShape == ValueShape.VAULT || VaultInfo.isSecret(current, definition.location.file)) return null
            val keyValue = VarLocations.keyValueAt(project, definition.location) ?: return null
            val value = PsiYValueAdapter.valueOf(keyValue)
            val next = (value as? YScalar)?.let { BARE_REFERENCE.matchEntire(it.text)?.groupValues?.get(1) }
            if (next == null) {
                if (value is YScalar && JinjaBearing.hasTemplateMarkers(value.text)) return null
                return DefaultChain(
                    ValueDisplay.typeName(value), keyValue.value?.text?.trim() ?: ValueDisplay.dump(value), value, current,
                    definition.location, VarLocations.label(root, definition.location),
                )
            }
            current = next
        }
        return null
    }

    private fun badges(project: Project, root: AnsibleRoot, option: OptionSpec, runtime: RuntimeDefault): List<String> {
        val value = runtime.value ?: return emptyList()
        val result = ArrayList<String>(2)
        val specDefault = option.default
        if (specDefault != null && !sameValue(specDefault, value)) {
            result += AnsibilityVarsBundle.message("card.badge.differs", ValueDisplay.dump(specDefault))
        }
        val validator = SpecValidator(semantics(project, root), SpecKind.ROLE)
        if (!runtime.isJinja) {
            lossy(validator, option, value)?.let {
                result += AnsibilityVarsBundle.message("card.badge.lossy", ValueDisplay.typeName(value), VarCard.typeText(option), it)
            }
        } else {
            val chain = runtime.chain
            if (chain != null && lossy(validator, option, chain.value) != null) {
                result += AnsibilityVarsBundle.message("card.badge.lossy.chain", chain.typeName, VarCard.typeText(option), chain.via)
            }
        }
        return result
    }

    private fun lossy(validator: SpecValidator, option: OptionSpec, value: YValue): String? =
        validator.validateValue(option, value).findings.firstOrNull { it.code in LOSSY && it.path.size <= 1 }?.message

    private fun sameValue(a: YValue, b: YValue): Boolean =
        PyValue.fromYValue(a).pyEquals(PyValue.fromYValue(b))

    private fun semantics(project: Project, root: AnsibleRoot): CoreSemantics =
        CoreSemantics(TargetVersionDetector.getInstance(project).targetVersion(root).version ?: CoreVersion.PINNED)

    // ------------------------------------------------------------------------------------------------ sections

    private fun subOptionRows(option: OptionSpec): Pair<List<SubOptionRow>, Int> {
        val rows = ArrayList<SubOptionRow>()
        var skipped = 0
        fun visit(current: OptionSpec, names: List<String>, depth: Int) {
            for ((name, sub) in current.options.orEmpty()) {
                ProgressManager.checkCanceled()
                if (rows.size >= MAX_OPTION_ROWS) {
                    skipped++
                    continue
                }
                val path = names + name
                rows += SubOptionRow(depth, path, sub)
                if (depth + 1 < MAX_OPTION_DEPTH) visit(sub, path, depth + 1)
            }
        }
        visit(option, emptyList(), 0)
        return rows to skipped
    }

    private fun declaredBy(
        project: Project,
        root: AnsibleRoot,
        ranking: VarRanking,
        primary: SpecBinding?,
        option: OptionSpec?,
        path: List<String>,
    ): List<Declaration> {
        primary ?: return emptyList()
        return ranking.bindings.filter { it.role.name != primary.role.name }.mapNotNull { binding ->
            val nested = SpecOptions.resolve(binding.option, path) ?: return@mapNotNull null
            val location = SpecOptions.locationOf(project, binding, nested.names)
            Declaration(
                role = binding.role.name,
                required = nested.option.required,
                differs = option != null && nested.option.required != option.required,
                location = location,
                label = VarLocations.label(root, location),
            )
        }
    }

    private fun setIn(
        project: Project,
        root: AnsibleRoot,
        subject: VarSubject,
        definitions: List<VarDefinition>,
        excluded: Set<VarDefinition>,
    ): List<SetInGroup> {
        val groups = LinkedHashMap<GroupKey, MutableList<SetInEntry>>()
        for (definition in definitions) {
            ProgressManager.checkCanceled()
            if (definition.kind == VarDefKind.SPEC_OPTION || definition in excluded) continue
            val keyValue = if (definition.valueShape == ValueShape.VAULT) VarLocations.keyValueAt(project, definition.location) else null
            val secret = isSecret(subject.name, definition, keyValue)
            groups.getOrPut(groupOf(definition)) { ArrayList() } += SetInEntry(
                location = definition.location,
                label = VarLocations.label(root, definition.location),
                layer = VarLabels.layerWithLevel(definition),
                preview = definition.preview.takeUnless { secret },
                secret = if (secret) secretText(root, definition, keyValue, long = false) else null,
            )
        }
        return groups.entries.sortedWith(compareBy({ it.key.order }, { it.key.environment })).map { (key, entries) ->
            SetInGroup(key.title(), entries.sortedWith(compareBy({ it.location.file.path }, { it.location.offset })))
        }
    }

    /** How "Set in" groups definitions: environments first (by name), then playbook vars, roles and plays, molecule. */
    private data class GroupKey(val order: Int, val environment: String) {
        fun title(): String = when (order) {
            ENVIRONMENT -> AnsibilityVarsBundle.message("card.set.in.group.environment", environment)
            PLAYBOOK -> AnsibilityVarsBundle.message("card.set.in.group.playbook")
            ROLES -> AnsibilityVarsBundle.message("card.set.in.group.roles")
            MOLECULE -> AnsibilityVarsBundle.message("card.set.in.group.molecule")
            else -> AnsibilityVarsBundle.message("card.set.in.group.other")
        }

        companion object {
            const val ENVIRONMENT = 0
            const val PLAYBOOK = 1
            const val ROLES = 2
            const val MOLECULE = 3
            const val OTHER = 4
        }
    }

    private fun groupOf(definition: VarDefinition): GroupKey = when (definition.kind) {
        VarDefKind.MOLECULE_INVENTORY -> GroupKey(GroupKey.MOLECULE, "")
        VarDefKind.GROUP_VARS, VarDefKind.HOST_VARS, VarDefKind.INVENTORY_INLINE ->
            definition.environment?.let { GroupKey(GroupKey.ENVIRONMENT, it) } ?: GroupKey(GroupKey.PLAYBOOK, "")
        VarDefKind.SPEC_OPTION, VarDefKind.TEMPLATE_VARS, VarDefKind.JINJA_LOCAL -> GroupKey(GroupKey.OTHER, "")
        else -> GroupKey(GroupKey.ROLES, "")
    }

    private fun thisDefinitionCard(
        project: Project,
        root: AnsibleRoot,
        name: String,
        definition: VarDefinition,
        defaultDefinition: VarDefinition?,
        runtime: RuntimeDefault?,
    ): ThisDefinition {
        val parts = ArrayList<String>()
        parts += VarLabels.layer(definition) ?: VarLabels.kind(definition.kind)
        definition.environment?.let { parts += AnsibilityVarsBundle.message("card.this.environment", it) }
        if (definition.kind == VarDefKind.MOLECULE_INVENTORY) {
            definition.group?.let { parts += AnsibilityVarsBundle.message("card.this.group", it) }
            definition.host?.let { parts += AnsibilityVarsBundle.message("card.this.host", it) }
        }
        VarLabels.level(definition)?.let { parts += it }
        if (definition.layer == null || definition.layer == VarsLayer.ROLE_DEFAULTS || definition.layer == VarsLayer.ROLE_VARS) {
            definition.roleName?.let { parts += AnsibilityVarsBundle.message("card.this.role", it) }
        }
        val overrides = if (defaultDefinition != null && defaultDefinition != definition && runtime != null && overridesDefaults(definition)) {
            val role = defaultDefinition.roleName ?: ""
            val value = runtime.secret ?: runtime.text.orEmpty().let { text ->
                val first = text.lineSequence().first()
                if (first.length < text.length) "$first \u2026" else first
            }
            AnsibilityVarsBundle.message("card.this.overrides", role, value, runtime.label)
        } else {
            null
        }
        val keyValue = VarLocations.keyValueAt(project, definition.location)
        val secret = if (isSecret(name, definition, keyValue)) secretText(root, definition, keyValue, long = true) else null
        return ThisDefinition(definition, parts, overrides, defaultDefinition?.location?.takeIf { overrides != null }, secret)
    }

    /** Every layer above role defaults overrides them (inventory, play, task and runtime definitions alike). */
    private fun overridesDefaults(definition: VarDefinition): Boolean {
        val layer = definition.layer ?: return definition.kind != VarDefKind.ROLE_DEFAULT && definition.kind != VarDefKind.SPEC_OPTION
        return layer.level > VarsLayer.ROLE_DEFAULTS.level
    }

    // ------------------------------------------------------------------------------------------------ secrets

    private fun isSecret(name: String, definition: VarDefinition, keyValue: YAMLKeyValue?): Boolean =
        definition.valueShape == ValueShape.VAULT ||
            VaultInfo.isSecret(name, definition.location.file) ||
            (keyValue != null && VaultInfo.isVaultValue(keyValue))

    /** `🔒 vault-encrypted (AES256, 1.1) in <file>:<line>` ([long]) or without the location; never the value. */
    private fun secretText(root: AnsibleRoot, definition: VarDefinition, keyValue: YAMLKeyValue?, long: Boolean): String {
        val label = VarLocations.label(root, definition.location)
        val vault = definition.valueShape == ValueShape.VAULT || (keyValue != null && VaultInfo.isVaultValue(keyValue))
        val header = keyValue?.let(VaultInfo::headerOf)
        return when {
            vault && header != null ->
                if (long) AnsibilityVarsBundle.message("card.vault.encrypted", header.summary, label)
                else AnsibilityVarsBundle.message("card.vault.encrypted.short", header.summary)
            vault ->
                if (long) AnsibilityVarsBundle.message("card.vault.encrypted.plain", label)
                else AnsibilityVarsBundle.message("card.vault.encrypted.plain.short")
            else ->
                if (long) AnsibilityVarsBundle.message("card.vault.hidden", label)
                else AnsibilityVarsBundle.message("card.vault.hidden.short")
        }
    }

    // ------------------------------------------------------------------------------------------------ notes

    private fun localCard(project: Project, root: AnsibleRoot, subject: VarSubject): VarCard? {
        val psi = PsiManager.getInstance(project).findFile(subject.file) ?: return null
        val analysis = JinjaTextSites.analysisAt(psi, subject.offset) ?: return null
        val local = analysis.localOf(analysis.reference, subject.name) ?: return null
        val definition = analysis.toHost(local.definitionRange.startOffset)
        val label = VarLocations.label(root, SourceLocation(subject.file, definition))
        val kind = AnsibilityVarsBundle.message("card.local.kind.${local.kind.name}")
        return emptyCard(root, subject, Note.Local(kind, label))
    }

    /**
     * Why an undefined reference has no card: it is a loop variable (of the task around it, or of the tasks rendering
     * the template), or nothing in the root knows the name.
     */
    private fun note(project: Project, root: AnsibleRoot, subject: VarSubject, undefined: Boolean): Note? {
        if (!undefined || subject.origin != VarSubject.Origin.REFERENCE || subject.loop != null) return null
        LoopItems.bindingAt(project, subject.file, subject.offset, subject.name)?.let { return Note.Loop(LoopItems.tasksLabel(root, it.tasks)) }
        return Note.Undefined(root.displayName)
    }

    private fun emptyCard(root: AnsibleRoot, subject: VarSubject, note: Note) = VarCard(
        root, subject, subject.displayName, null, null, null, emptyList(), emptyList(), false, null, null, emptyList(),
        emptyList(), 0, emptyList(), emptyList(), null, null, note,
    )
}
