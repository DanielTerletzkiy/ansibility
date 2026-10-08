package de.terletzkiy.ansibility.vars

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.RoleInfo
import de.terletzkiy.ansibility.api.RoleRegistry
import de.terletzkiy.ansibility.api.SourceLocation
import de.terletzkiy.ansibility.api.SpecBinding
import de.terletzkiy.ansibility.api.ValueShape
import de.terletzkiy.ansibility.api.VarDefKind
import de.terletzkiy.ansibility.api.VarDefinition
import de.terletzkiy.ansibility.api.VarService
import de.terletzkiy.ansibility.api.VarsLayer
import de.terletzkiy.ansibility.context.MoleculeView
import de.terletzkiy.ansibility.context.TargetVersionDetector
import de.terletzkiy.ansibility.index.JinjaBearing
import de.terletzkiy.ansibility.index.PathFacts
import de.terletzkiy.ansibility.index.ValueSummary
import de.terletzkiy.ansibility.inspections.spec.SpecDefaultChecks
import de.terletzkiy.ansibility.model.role.RoleDefault
import de.terletzkiy.ansibility.model.role.RoleDefaults
import de.terletzkiy.ansibility.resolve.VarViews
import de.terletzkiy.ansibility.resolve.include.IncludeBindings
import de.terletzkiy.ansibility.resolve.include.IncludeCoverage
import de.terletzkiy.ansibility.resolve.loop.LoopItemTyper
import de.terletzkiy.ansibility.semantics.CoreVersion
import de.terletzkiy.ansibility.semantics.coerce.CoreSemantics
import de.terletzkiy.ansibility.semantics.diagnostics.DiagnosticCode
import de.terletzkiy.ansibility.semantics.schema.OptionSpec
import de.terletzkiy.ansibility.semantics.schema.OptionType
import de.terletzkiy.ansibility.semantics.validate.SpecDefaults
import de.terletzkiy.ansibility.semantics.validate.SpecKind
import de.terletzkiy.ansibility.semantics.validate.SpecValidator
import de.terletzkiy.ansibility.semantics.yaml.YMap
import de.terletzkiy.ansibility.semantics.yaml.YScalar
import de.terletzkiy.ansibility.semantics.yaml.YValue
import de.terletzkiy.ansibility.vars.usages.VarOccurrences
import de.terletzkiy.ansibility.vars.usages.VarSymbolElement
import de.terletzkiy.ansibility.vars.usages.VarUsageSearch
import de.terletzkiy.ansibility.yaml.PsiYValueAdapter
import de.terletzkiy.ansibility.yaml.YamlPaths
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping
import org.jetbrains.yaml.psi.YAMLSequenceItem

/**
 * Builds the [VarCard] of a [VarSubject] from the root-scoped [VarService] symbol (plan F1.2, F4.3, F4.8):
 * - the documenting spec is the own role's, else the best-ranked declaring role's ([VarRanking]); a nested path
 *   documents the nested option;
 * - the "Default" is the role default Ansible uses ([RoleDefaults.lookup], plan amendment R23, D173/D174): the own
 *   role's when its tasks see one (its defaults, else a dependency's), else the documenting role's; as written, with a
 *   bare `{{ name }}` followed through the role defaults that role's tasks see (depth ≤ 8). The argument_specs
 *   `default:` is never the default: it is only "Documented" ([DocumentedDefault]), flagged exactly when ANS-S003/S004
 *   flag the spec;
 * - secrets never show: `vault_*` names, vault files, `!vault` values, and every value of a variable any spec of the
 *   root keeps secret (`no_log`, [NoLogVariables]) in the Default, Documented, Set in and This definition rows, the
 *   chain and the hint;
 * - ⚠ badges when the role default contradicts the documented type (the documented-type rules of `SpecValidator`, at
 *   the root's target ansible-core);
 * - "Declared by" lists the other declaring roles, "Set in" every other definition grouped by environment, and a
 *   definition subject gets "This definition";
 * - the symbol is the one the card's file sees ([MoleculeView.of] the subject's file, plan amendment R20, D153): no
 *   Molecule definition on a card shown outside Molecule while "Show Molecule in navigation and search" is off.
 *
 * Needs a read action and smart mode.
 */
internal object VarCards {
    private const val MAX_CHAIN = 8
    private const val MAX_OPTION_DEPTH = 4
    private const val MAX_OPTION_ROWS = 60
    private const val VAULT_PREFIX = "vault_"
    private const val SPEC_DEFAULT = "default"

    /** The spec findings that flag a documented default, in the order the card asks for them. */
    private val FINDING_STATES = listOf(
        DiagnosticCode.S003_SPEC_DEFAULT_MISMATCH to DocumentedDefault.State.DIFFERS,
        DiagnosticCode.S004_SPEC_DEFAULT_NOT_APPLIED to DocumentedDefault.State.NOT_APPLIED,
    )

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
        val view = MoleculeView.of(project, subject.file)
        val symbol = VarViews.symbol(project, root, subject.name, view)
        val ranking = VarRanking(project, root, ownRole, subject.file, context?.kind, symbol, view)
        if (subject.origin == VarSubject.Origin.LOCAL) localCard(project, root, subject)?.let { return it }
        loopCard(project, root, subject)?.let { return it }

        val binding = primaryBinding(ranking, subject.path)
        val nested = binding?.let { SpecOptions.resolve(it.option, subject.path) }
        val option = nested?.option ?: binding?.option
        val optionNames = nested?.names.orEmpty()
        val valuePath = if (nested != null) subject.path else emptyList()
        val registry = RoleRegistry.getInstance(project)
        val bindingRole = binding?.let { registry.roleOf(it.location.file)?.takeIf { info -> info.ref.dir == it.role.dir } ?: registry.role(root, it.role.name) }
        val defaults = defaultsOf(project, root, subject, ownRole, bindingRole, ranking)
        val role = binding?.role?.name ?: defaults?.role?.ref?.name
        val found = defaults?.lookup as? RoleDefaults.Lookup.Found
        val defaultDefinition = found?.let { definitionOf(symbol.definitions, it.default) }
        val thisDefinition = thisDefinitionOf(subject, symbol.definitions)
        // Every spec that documents the name counts (one role's `no_log` hides the value on another role's card too), for
        // the value the card shows: the nested one at [valuePath], else the whole variable's.
        val secrecy = if (binding != null) secrecy(ranking.bindings, valuePath) else Secrecy.NONE
        val runtime = found?.let { runtimeDefault(project, root, subject.name, it, defaults.role, defaultDefinition, valuePath, secrecy, view) }
        // Set in, This definition: values of the whole variable, secret when any spec keeps any part of it secret.
        val noLogVariable = secrecy == Secrecy.NO_LOG || NoLogVariables.isNoLog(project, root, subject.name, view)
        // The default is what another role's tasks see, not the documenting role's (D174): the hint names that role.
        val defaultRole = defaults?.role?.takeIf { bindingRole != null && it.ref.dir != bindingRole.ref.dir && found?.default?.roleDir != bindingRole.ref.dir }

        val specDescription = option?.description.orEmpty().filter { it.isNotBlank() }
        val comment = defaultDefinition?.docComment?.takeIf { valuePath.isEmpty() }
        val commentDescription = comment?.takeIf { specDescription.isEmpty() }
        val fromComment = commentDescription != null
        val description = commentDescription?.let(::listOf) ?: specDescription
        val shownRuntime = if (fromComment && runtime != null) runtime.withoutComment() else runtime

        val badges = if (option != null && runtime != null) badges(project, root, option, runtime) else emptyList()
        val documented = if (binding != null && option != null) {
            documented(project, root, binding, bindingRole, option, optionNames, defaults, runtime, secrecy, view)
        } else {
            null
        }
        val missing = when {
            option == null || runtime != null -> null
            // A nested option: only where the role default is a literal dict without the key (else its parent's card says
            // it); a merged dictionary may get the key from an earlier defaults file.
            valuePath.isNotEmpty() && (found == null || found.default.merged || !keyMissing(found.default.value, valuePath)) -> null
            option.required -> MissingDefault.REQUIRED
            // The spec's ANS-S004 finding, never the documented default alone (the role's vars/ or tasks may set it).
            documented?.state == DocumentedDefault.State.NOT_APPLIED -> MissingDefault.DOCUMENTED
            (defaults?.lookup as? RoleDefaults.Lookup.None)?.opaque == true -> MissingDefault.OPAQUE
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
            setIn = setIn(project, root, subject, symbol.definitions, setOfNotNull(defaultDefinition, shownThis), noLogVariable),
            setInOf = subject.name.takeIf { optionNames.isNotEmpty() },
            thisDefinition = shownThis?.let { thisDefinitionCard(project, root, subject.name, it, runtime, defaults?.role?.ref?.name, noLogVariable) },
            note = note(project, root, subject, symbol.definitions.isEmpty() && symbol.specBindings.isEmpty(), view),
            loop = subject.loop,
            provision = provision(project, subject),
            documented = documented,
            defaultRole = defaultRole?.ref?.name,
        )
    }

    /** The role whose defaults the card shows, and what they give the name ([RoleDefaults.lookup]). */
    private class Defaults(val role: RoleInfo, val lookup: RoleDefaults.Lookup)

    /**
     * Whose role default the card shows (plan amendment R23, D174): the own role's when its tasks see one (its own
     * defaults or a dependency's), even when another role's spec documents the name; else the documenting role's, else
     * the best-ranked declaring role's; null when no role declares the name. When the own role loads a defaults file
     * that cannot be read (a whole-file vault), the other role's default is uncertain: the own role's defaults apply
     * after it and may set the name.
     */
    private fun defaultsOf(
        project: Project,
        root: AnsibleRoot,
        subject: VarSubject,
        ownRole: String?,
        bindingRole: RoleInfo?,
        ranking: VarRanking,
    ): Defaults? {
        val registry = RoleRegistry.getInstance(project)
        val own = ownRole?.let { name -> registry.roleOf(subject.file)?.takeIf { it.ref.name == name && it.ref.rootDir == root.dir } ?: registry.role(root, name) }
        val ownLookup = own?.let { RoleDefaults.lookup(project, root, it, subject.name) }
        if (own != null && ownLookup is RoleDefaults.Lookup.Found) return Defaults(own, ownLookup)
        val other = bindingRole ?: ranking.declaringRoles.firstOrNull()?.let { registry.role(root, it) } ?: return null
        if (own != null && ownLookup != null && other.ref.dir == own.ref.dir) return Defaults(own, ownLookup)
        val lookup = RoleDefaults.lookup(project, root, other, subject.name)
        if ((ownLookup as? RoleDefaults.Lookup.None)?.opaque != true) return Defaults(other, lookup)
        return Defaults(
            other,
            when (lookup) {
                is RoleDefaults.Lookup.Own -> RoleDefaults.Lookup.Own(lookup.default, uncertain = true)
                is RoleDefaults.Lookup.Dependency -> RoleDefaults.Lookup.Dependency(lookup.default, uncertain = true)
                is RoleDefaults.Lookup.None -> RoleDefaults.Lookup.None(opaque = true)
            },
        )
    }

    /** The index definition of [default]'s key (the same key offset), else the last one of its name in its file. */
    private fun definitionOf(definitions: List<VarDefinition>, default: RoleDefault): VarDefinition? {
        val inFile = definitions.filter { it.kind == VarDefKind.ROLE_DEFAULT && it.location.file == default.file }
        return inFile.firstOrNull { it.location.offset == default.keyOffset } ?: inFile.maxByOrNull { it.location.offset }
    }

    /** Whether [value] is a literal dict along [path] whose last dict lacks the key (sequence steps prove nothing). */
    private fun keyMissing(value: YValue, path: List<String>): Boolean {
        var current = value
        for (segment in path) {
            val map = current as? YMap ?: return false
            current = map.entries.lastOrNull { it.key.text == segment }?.value ?: return true
        }
        return false
    }

    /** Why the card's option is secret (plan amendment R23: never shown, never compared). */
    private enum class Secrecy {
        NONE,

        /** A `vault_*` option name on the path, or a `vault_*` sub-option below the card's option. */
        VAULT_NAME,

        /** A `no_log` option on the path, or a `no_log` sub-option below the card's option ([SpecDefaults.hasNoLog]). */
        NO_LOG,
    }

    /**
     * The card's option is secret when any spec that documents the name says so ([bindings], every declaring role's, as
     * completion asks them): each walks [path] (an integer below a `list` is an element, aliases count) as far as its
     * options go; `no_log` wins over a `vault_*` name.
     */
    private fun secrecy(bindings: List<SpecBinding>, path: List<String>): Secrecy {
        var result = Secrecy.NONE
        for (binding in bindings) {
            ProgressManager.checkCanceled()
            var current = binding.option
            val names = arrayListOf(current.name)
            var noLog = current.noLog
            var resolved = true
            for (segment in path) {
                if (current.type == OptionType.List && segment.toIntOrNull() != null) continue
                val options = current.options
                val next = options?.get(segment) ?: options?.values?.firstOrNull { segment in it.aliases }
                if (next == null) {
                    resolved = false
                    break
                }
                current = next
                names += next.name
                noLog = noLog || next.noLog
            }
            if (noLog || resolved && SpecDefaults.hasNoLog(current)) return Secrecy.NO_LOG
            if (names.any { it.startsWith(VAULT_PREFIX) } || resolved && hasVaultSubOption(current, 0)) result = Secrecy.VAULT_NAME
        }
        return result
    }

    private fun hasVaultSubOption(option: OptionSpec, depth: Int): Boolean = depth < MAX_OPTION_DEPTH * 2 &&
        option.options.orEmpty().any { (name, sub) -> name.startsWith(VAULT_PREFIX) || hasVaultSubOption(sub, depth + 1) }

    // ------------------------------------------------------------------------------------------------ documented default

    /**
     * The "Documented (argument_specs)" row (plan amendment R23, D173): [DocumentedDefault.State.DIFFERS] and
     * [DocumentedDefault.State.NOT_APPLIED] exactly when the spec's ANS-S003/S004 finding for the same entry point and
     * option path exists (so the card and the underline agree), where the card shows the default those findings judged:
     * the documenting role's own default (also when a depending role's tasks see it through the dependency), or, without
     * one, the documenting role's absence of one. Else [DocumentedDefault.State.DOCUMENTED] unless the documented value is
     * what Ansible uses after the option type's conversion ([SpecDefaults], the check's comparator; a merged dictionary is
     * never known to be equal). Secrets keep their state but never their value; without a finding they get no row.
     */
    private fun documented(
        project: Project,
        root: AnsibleRoot,
        binding: SpecBinding,
        bindingRole: RoleInfo?,
        option: OptionSpec,
        optionNames: List<String>,
        defaults: Defaults?,
        runtime: RuntimeDefault?,
        secrecy: Secrecy,
        view: MoleculeView,
    ): DocumentedDefault? {
        val value = SpecDefaults.documentedDefault(option) ?: return null
        val hidden = secrecy != Secrecy.NONE || runtime?.secret != null || SpecDefaults.containsVault(value)
        val text = if (hidden) null else SpecDefaultChecks.display(value)
        val path = listOf(binding.option.name) + optionNames
        // The role whose defaults the shown value (or its absence) is: the findings judge only the binding role's own.
        val judged = when (val lookup = defaults?.lookup) {
            is RoleDefaults.Lookup.Found -> lookup.default.roleDir
            is RoleDefaults.Lookup.None -> defaults.role.ref.dir.takeUnless { lookup.opaque }
            null -> null
        }
        val analysis = bindingRole?.takeIf { judged != null && it.ref.dir == judged }?.let { SpecDefaultChecks.of(project, it) }
        if (analysis != null) {
            for ((code, state) in FINDING_STATES) {
                val finding = analysis.findings(code).firstOrNull { it.target.entryPoint == binding.entryPoint && it.target.path == path } ?: continue
                val location = SourceLocation(analysis.specFile, finding.range.startOffset)
                return DocumentedDefault(state, text, location, VarLocations.label(root, location))
            }
        }
        if (hidden || option.required) return null
        if (runtime != null && defaults != null && !runtime.merged) {
            val shown = runtime.value ?: return null
            when (val verdict = comparator(project, root, defaults.role, view).compare(option, shown)) {
                SpecDefaults.Verdict.Equal, SpecDefaults.Verdict.NoClaim -> return null
                is SpecDefaults.Verdict.Unknown -> if (verdict.reason == SpecDefaults.UnknownReason.SECRET) return null
                is SpecDefaults.Verdict.Mismatch -> Unit
            }
        }
        val location = specDefaultLocation(project, binding, optionNames)
        return DocumentedDefault(DocumentedDefault.State.DOCUMENTED, text, location, VarLocations.label(root, location))
    }

    /**
     * The check's comparator with a `{{ name }}` chain through the role defaults [role]'s tasks see ([chainStep]: its own,
     * then its dependencies'), comparable and non-secret only (as ANS-S003 does).
     */
    private fun comparator(project: Project, root: AnsibleRoot, role: RoleInfo, view: MoleculeView): SpecDefaults =
        SpecDefaults(semantics(project, root), SpecValidator::containsJinja) { name -> chainStep(project, root, role, name, view)?.value }

    /**
     * One step of a `{{ name }}` chain: the role default of [name] the tasks of [role] see ([RoleDefaults.lookup]: the
     * role's own defaults are applied after its dependencies', and templates are evaluated lazily, so the own role's value
     * of a name a dependency's default references wins), or null when there is none or it is not known or not to be shown:
     * possibly overridden by an unreadable file, a merged dictionary, a secret by name, file or value, or a `no_log`
     * variable of any spec ([NoLogVariables]).
     */
    private fun chainStep(project: Project, root: AnsibleRoot, role: RoleInfo, name: String, view: MoleculeView): RoleDefault? {
        val found = RoleDefaults.lookup(project, root, role, name) as? RoleDefaults.Lookup.Found ?: return null
        val default = found.default
        if (found.uncertain || default.merged || default.isSecret || VaultInfo.isSecret(name, default.file)) return null
        return default.takeUnless { NoLogVariables.isNoLog(project, root, name, view) }
    }

    /** The spec's `default:` value of the option [names] below [binding]'s option (the option's key when not found). */
    private fun specDefaultLocation(project: Project, binding: SpecBinding, names: List<String>): SourceLocation {
        val key = SpecOptions.keyOf(project, binding, names) ?: return SpecOptions.locationOf(project, binding, names)
        val default = (key.value as? YAMLMapping)?.getKeyValueByKey(SPEC_DEFAULT)
        val element = default?.value ?: default ?: key.key ?: key
        return SourceLocation(binding.location.file, element.textRange.startOffset)
    }

    /**
     * How the include tasks that run a reference's file (a role task file, or a template's rendering tasks) give it the
     * name ([IncludeBindings.coverageAt]; Molecule includers never for a production file, [MoleculeView.forAnalysis]),
     * or, for a definition that is a key of an include task's own `vars:`, how the include tasks give it to the files
     * that include runs; null when none does.
     */
    private fun provision(project: Project, subject: VarSubject): IncludeProvision? {
        val coverage = when (subject.origin) {
            VarSubject.Origin.REFERENCE -> {
                if (subject.loop != null) return null
                IncludeBindings.coverageAt(project, subject.file, subject.name, MoleculeView.forAnalysis(project, subject.file))
            }
            VarSubject.Origin.DEFINITION -> {
                val reached = IncludeBindings.filesRunByVarsKey(project, SourceLocation(subject.file, subject.offset)).ifEmpty { return null }
                IncludeCoverage.union(reached.map { IncludeBindings.coverageAt(project, it, subject.name, MoleculeView.forAnalysis(project, it)) })
            }
            VarSubject.Origin.LOCAL -> return null
        }
        if (!coverage.provided) return null
        return IncludeProvision(coverage.everywhere, coverage.byLoop(subject.name))
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

    /**
     * The "Default" row of [found] (plan amendment R23, D173): its value as written (at [valuePath] for a nested option),
     * the file and line of the winning key, the chain of a bare `{{ name }}` through the role defaults [role]'s tasks
     * see, and whether a dependency sets it or a later unreadable file may override it. A secret ([secrecy], a `vault_*`
     * name, a vault file, a `!vault` value anywhere in it) keeps only its location.
     */
    private fun runtimeDefault(
        project: Project,
        root: AnsibleRoot,
        name: String,
        found: RoleDefaults.Lookup.Found,
        role: RoleInfo,
        definition: VarDefinition?,
        valuePath: List<String>,
        secrecy: Secrecy,
        view: MoleculeView,
    ): RuntimeDefault? {
        val default = found.default
        val location = default.location
        val label = VarLocations.label(root, location)
        val keyValue = VarLocations.keyValueAt(project, location)
        val dependency = if (found is RoleDefaults.Lookup.Dependency) default.roleDir.name else null
        val comment = definition?.docComment?.takeIf { valuePath.isEmpty() }
        val vault = SpecDefaults.containsVault(default.value) || (keyValue != null && VaultInfo.isVaultValue(keyValue))
        if (vault || secrecy != Secrecy.NONE || default.isSecret || VaultInfo.isSecret(name, default.file)) {
            val hidden = when {
                isVaultFile(default.file) -> Hidden.VAULT_FILE
                secrecy == Secrecy.NO_LOG -> Hidden.NO_LOG
                else -> Hidden.VAULT_NAME
            }
            val secret = secretText(root, location, vault, keyValue, long = true, hidden = hidden)
            return RuntimeDefault(location, definition, label, null, null, false, secret, comment, null, dependency, found.uncertain, default.merged)
        }
        val (value, written) = valueAt(default, keyValue, valuePath) ?: return null
        val text = written.trim().ifEmpty { "null" }
        val jinja = JinjaBearing.hasTemplateMarkers(text)
        val chain = if (jinja) chain(project, root, role, value, view) else null
        return RuntimeDefault(location, definition, label, text, value, jinja, null, comment, chain, dependency, found.uncertain, default.merged)
    }

    /**
     * The value of [default] at [valuePath] and its text as written: from the key's PSI ([keyValue]), else (a JSON
     * defaults file) from the loaded value along literal dicts, dumped.
     */
    private fun valueAt(default: RoleDefault, keyValue: YAMLKeyValue?, valuePath: List<String>): Pair<YValue, String>? {
        if (keyValue == null) {
            var current = default.value
            for (segment in valuePath) current = (current as? YMap)?.entries?.lastOrNull { it.key.text == segment }?.value ?: return null
            return current to ValueDisplay.dump(current)
        }
        val element: PsiElement = if (valuePath.isEmpty()) keyValue else keyValue.value?.let { YamlPaths.find(it, valuePath) } ?: return null
        return when (element) {
            is YAMLKeyValue -> PsiYValueAdapter.valueOf(element) to element.value?.text.orEmpty()
            is YAMLSequenceItem -> PsiYValueAdapter.valueOf(element) to element.value?.text.orEmpty()
            else -> null
        }
    }

    private fun RuntimeDefault.withoutComment() =
        RuntimeDefault(location, definition, label, text, value, isJinja, secret, null, chain, dependency, uncertain, merged)

    /**
     * Follows a bare `{{ name }}` default through the role defaults [role]'s tasks see ([chainStep]: the files Ansible
     * loads, the last wins, the role's own over its dependencies') until a literal; cycles, depth > 8, unknown values
     * and secrets (`no_log` ones too) give null.
     */
    private fun chain(project: Project, root: AnsibleRoot, role: RoleInfo, start: YValue, view: MoleculeView): DefaultChain? {
        var current = (start as? YScalar)?.let { BARE_REFERENCE.matchEntire(it.text)?.groupValues?.get(1) } ?: return null
        val seen = HashSet<String>()
        repeat(MAX_CHAIN) {
            ProgressManager.checkCanceled()
            if (!seen.add(current)) return null
            val default = chainStep(project, root, role, current, view) ?: return null
            val keyValue = VarLocations.keyValueAt(project, default.location)
            if (keyValue != null && VaultInfo.isVaultValue(keyValue)) return null
            val value = default.value
            val next = (value as? YScalar)?.let { BARE_REFERENCE.matchEntire(it.text)?.groupValues?.get(1) }
            if (next == null) {
                if (value is YScalar && JinjaBearing.hasTemplateMarkers(value.text)) return null
                return DefaultChain(
                    ValueDisplay.typeName(value), keyValue?.value?.text?.trim() ?: ValueDisplay.dump(value), value, current,
                    default.location, VarLocations.label(root, default.location),
                )
            }
            current = next
        }
        return null
    }

    /** The lossy badges (X06); whether the documented default differs is the "Documented" row's, from ANS-S003. */
    private fun badges(project: Project, root: AnsibleRoot, option: OptionSpec, runtime: RuntimeDefault): List<String> {
        val value = runtime.value ?: return emptyList()
        val result = ArrayList<String>(1)
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

    /** "Set in": every other definition, grouped; values of a [noLog] variable (any spec's `no_log`) never show. */
    private fun setIn(
        project: Project,
        root: AnsibleRoot,
        subject: VarSubject,
        definitions: List<VarDefinition>,
        excluded: Set<VarDefinition>,
        noLog: Boolean,
    ): List<SetInGroup> {
        val groups = LinkedHashMap<GroupKey, MutableList<SetInEntry>>()
        for (definition in definitions) {
            ProgressManager.checkCanceled()
            if (definition.kind == VarDefKind.SPEC_OPTION || definition in excluded) continue
            val keyValue = if (definition.valueShape == ValueShape.VAULT) VarLocations.keyValueAt(project, definition.location) else null
            val secret = isSecret(subject.name, definition, keyValue) || noLog
            groups.getOrPut(groupOf(definition)) { ArrayList() } += SetInEntry(
                location = definition.location,
                label = VarLocations.label(root, definition.location),
                layer = VarLabels.layerWithLevel(definition),
                preview = definition.preview.takeUnless { secret },
                secret = if (secret) secretText(root, definition, keyValue, long = false, noLog = noLog) else null,
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
        runtime: RuntimeDefault?,
        defaultsRole: String?,
        noLog: Boolean,
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
        val overrides = if (runtime != null && runtime.location != definition.location && overridesDefaults(definition)) {
            val role = runtime.dependency ?: runtime.definition?.roleName ?: defaultsRole.orEmpty()
            val value = runtime.secret ?: runtime.text.orEmpty().let { text ->
                val first = text.lineSequence().first()
                if (first.length < text.length) "$first \u2026" else first
            }
            AnsibilityVarsBundle.message("card.this.overrides", role, value, runtime.label)
        } else {
            null
        }
        val keyValue = VarLocations.keyValueAt(project, definition.location)
        val secret = if (isSecret(name, definition, keyValue) || noLog) secretText(root, definition, keyValue, long = true, noLog = noLog) else null
        return ThisDefinition(definition, parts, overrides, runtime?.location?.takeIf { overrides != null }, secret)
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

    /**
     * `🔒 vault-encrypted (AES256, 1.1) in <file>:<line>` ([long]) or without the location; never the value. A plain
     * value says why it is hidden: a vault file, a `no_log` variable ([noLog]) or a `vault_*` name.
     */
    private fun secretText(root: AnsibleRoot, definition: VarDefinition, keyValue: YAMLKeyValue?, long: Boolean, noLog: Boolean): String {
        val vault = definition.valueShape == ValueShape.VAULT || (keyValue != null && VaultInfo.isVaultValue(keyValue))
        val hidden = when {
            isVaultFile(definition.location.file) -> Hidden.VAULT_FILE
            noLog -> Hidden.NO_LOG
            else -> Hidden.VAULT_NAME
        }
        return secretText(root, definition.location, vault, keyValue, long, hidden)
    }

    /** Why a plain (not `!vault`) value is hidden. */
    private enum class Hidden { VAULT_FILE, VAULT_NAME, NO_LOG }

    private fun isVaultFile(file: VirtualFile): Boolean = PathFacts.of(file).isVaultFile || ValueSummary.isVaultFileName(file.name)

    /** The same for a value at [location]: a `!vault` value ([vault]) by its envelope, a plain one by [hidden]. */
    private fun secretText(root: AnsibleRoot, location: SourceLocation, vault: Boolean, keyValue: YAMLKeyValue?, long: Boolean, hidden: Hidden): String {
        val label = VarLocations.label(root, location)
        val header = keyValue?.let(VaultInfo::headerOf)
        return when {
            vault && header != null ->
                if (long) AnsibilityVarsBundle.message("card.vault.encrypted", header.summary, label)
                else AnsibilityVarsBundle.message("card.vault.encrypted.short", header.summary)
            vault ->
                if (long) AnsibilityVarsBundle.message("card.vault.encrypted.plain", label)
                else AnsibilityVarsBundle.message("card.vault.encrypted.plain.short")
            hidden == Hidden.NO_LOG ->
                if (long) AnsibilityVarsBundle.message("card.vault.hidden.nolog", label)
                else AnsibilityVarsBundle.message("card.vault.hidden.nolog.short")
            hidden == Hidden.VAULT_NAME ->
                if (long) AnsibilityVarsBundle.message("card.vault.hidden.name", label)
                else AnsibilityVarsBundle.message("card.vault.hidden.name.short")
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
     * Why an undefined reference has no card: only hidden Molecule files set it, or nothing in the root knows the name
     * (a loop variable gets the loop card instead, [loopCard]).
     */
    private fun note(project: Project, root: AnsibleRoot, subject: VarSubject, undefined: Boolean, view: MoleculeView): Note? {
        if (!undefined || subject.origin != VarSubject.Origin.REFERENCE || subject.loop != null) return null
        // Hidden is not undefined: say that Molecule files set it instead of "defined nowhere" (plan amendment R20, D153).
        if (!view.includesMolecule && VarViews.symbol(project, root, subject.name, MoleculeView.INCLUDE).definitions.isNotEmpty()) {
            return Note.MoleculeOnly(root.displayName)
        }
        return Note.Undefined(root.displayName)
    }

    /**
     * The loop card: a reference to a loop variable that no documented iterated variable explains (a literal list, a
     * lookup, `index_var`, `ansible_loop`, the loop of an include task that runs the file), or the
     * `loop_control.loop_var`/`index_var` value itself ([LoopItems]). It says which tasks bind the name and what they
     * iterate, the type of the variable (or member) with the item's keys as options, and where the loop's scope reads
     * it; no "Set in" and no host sections, since no inventory or play sets a loop variable.
     */
    private fun loopCard(project: Project, root: AnsibleRoot, subject: VarSubject): VarCard? {
        val binding = when (subject.origin) {
            VarSubject.Origin.REFERENCE -> if (subject.loop != null) null else LoopItems.bindingAt(project, subject.file, subject.offset, subject.name)
            VarSubject.Origin.DEFINITION -> LoopItems.bindingOfValue(project, subject.file, subject.offset)?.takeIf { it.first == subject.name }?.second
            VarSubject.Origin.LOCAL -> null
        } ?: return null
        val item = LoopItemTyper.typeOfPath(binding.loop, subject.name, subject.path)
        val (rows, truncated) = item?.let(::subOptionRows) ?: (emptyList<SubOptionRow>() to 0)
        return VarCard(
            root = root,
            subject = subject,
            title = subject.displayName,
            role = null,
            binding = null,
            option = null,
            optionNames = emptyList(),
            description = item?.description.orEmpty().filter { it.isNotBlank() },
            descriptionFromComment = false,
            runtimeDefault = null,
            missingDefault = null,
            badges = emptyList(),
            subOptions = rows,
            subOptionsTruncated = truncated,
            declaredBy = emptyList(),
            setIn = emptyList(),
            setInOf = null,
            thisDefinition = null,
            note = Note.Loop(LoopItems.describe(project, root, binding, subject.name)),
            loopItem = item,
            // Computed by the full card only (the "Used in" row is no part of the Ctrl-hover hint); `item` and
            // `ansible_loop` are never counted across the root (their row keeps the link only).
            loopUses = lazy(LazyThreadSafetyMode.NONE) {
                if (subject.name in VarUsageSearch.LOOP_ONLY_NAMES) null else loopUses(project, root, subject, binding)
            },
        )
    }

    /**
     * The reads of the loop variable in the loop's scope per file ([VarOccurrences] of [VarUsageSearch.loopScope] of
     * [binding], the same occurrences Find Usages lists from the card's position): the binding tasks, the files they
     * include, the templates rendered there. One root-wide lookup of the name: call it from the full card only.
     */
    private fun loopUses(project: Project, root: AnsibleRoot, subject: VarSubject, binding: LoopItems.Binding): List<LoopUse> {
        val anchor = PsiManager.getInstance(project).findFile(subject.file) ?: return emptyList()
        val context = AnsibleWorkspace.getInstance(project).contextOf(subject.file)
        val template = subject.file.takeIf { subject.origin == VarSubject.Origin.REFERENCE && context != null && JinjaTextSites.isTemplateFile(it, context) }
        val symbol = VarSymbolElement(anchor, root, subject.name, VarUsageSearch.loopScope(project, binding, template))
        val counts = LinkedHashMap<VirtualFile, Int>()
        for (occurrence in VarOccurrences.of(project, symbol, null)) {
            ProgressManager.checkCanceled()
            if (!occurrence.write) counts.merge(occurrence.file, 1, Int::plus)
        }
        return counts.map { (file, count) -> LoopUse(file.name, count) }
    }

    private fun emptyCard(root: AnsibleRoot, subject: VarSubject, note: Note) = VarCard(
        root, subject, subject.displayName, null, null, null, emptyList(), emptyList(), false, null, null, emptyList(),
        emptyList(), 0, emptyList(), emptyList(), null, null, note,
    )
}
