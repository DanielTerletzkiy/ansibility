package de.terletzkiy.ansibility.inspections.vault

import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.FileContext
import de.terletzkiy.ansibility.api.ValueShape
import de.terletzkiy.ansibility.api.VarDefKind
import de.terletzkiy.ansibility.api.VarDefinition
import de.terletzkiy.ansibility.api.VarService
import de.terletzkiy.ansibility.context.TargetVersionDetector
import de.terletzkiy.ansibility.index.JinjaBearing
import de.terletzkiy.ansibility.index.PathFacts
import de.terletzkiy.ansibility.inspections.modules.TaskCheckEnvironment
import de.terletzkiy.ansibility.lang.jinja.refs.JinjaRefs
import de.terletzkiy.ansibility.model.task.BlockNode
import de.terletzkiy.ansibility.model.task.ModuleArgs
import de.terletzkiy.ansibility.model.task.ModuleCall
import de.terletzkiy.ansibility.model.task.SrcRef
import de.terletzkiy.ansibility.model.task.TaskFileModels
import de.terletzkiy.ansibility.model.task.TaskItem
import de.terletzkiy.ansibility.model.task.TaskModelBuilder
import de.terletzkiy.ansibility.model.task.TaskNode
import de.terletzkiy.ansibility.navigation.RefKind
import de.terletzkiy.ansibility.navigation.RefOccurrence
import de.terletzkiy.ansibility.navigation.RefResolver
import de.terletzkiy.ansibility.navigation.RefSites
import de.terletzkiy.ansibility.navigation.ResolutionStatus
import de.terletzkiy.ansibility.semantics.coerce.Booleans
import de.terletzkiy.ansibility.semantics.yaml.Resolved
import de.terletzkiy.ansibility.semantics.yaml.YEmpty
import de.terletzkiy.ansibility.semantics.yaml.YEntry
import de.terletzkiy.ansibility.semantics.yaml.YMap
import de.terletzkiy.ansibility.semantics.yaml.YScalar
import de.terletzkiy.ansibility.semantics.yaml.YSeq
import de.terletzkiy.ansibility.semantics.yaml.YValue
import de.terletzkiy.ansibility.semantics.yaml.YVault
import de.terletzkiy.ansibility.vars.VarLocations
import de.terletzkiy.ansibility.vault.envelope.VaultEnvelopes
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLScalar
import java.math.BigInteger

/** Where a [SecretModeFinding] sits, which decides how "Set mode to …" writes the mode. */
enum class ModeSite {
    /** On the `mode` value of the module's (or `args:`') mapping: the value is replaced, its anchor and tag kept. */
    VALUE,

    /** On the key of a `mode:` without a value: the value is set (after its anchor, `mode: &m`). */
    EMPTY_VALUE,

    /** On the value of a `mode=…` word of free-form arguments: the word's value is replaced. */
    WORD,

    /** On the key of a `mode: *m` whose value is an alias: the alias is replaced, so the anchored value stays. */
    ALIAS,

    /**
     * On the module name: the mode is written outside the task (merged in with `<<`, or part of an aliased mapping)
     * and shared with whatever else uses it, so a `mode` line is added to the module's own arguments, where it wins.
     */
    SHARED,

    /** On the module name: there is no mode, so a `mode` line is added to the module's arguments. */
    ABSENT,
}

/** One ANS-V113 finding: a task writes a secret to a file whose mode lets others read it. PSI-free. */
class SecretModeFinding(
    /**
     * The highlighted range in the file, always inside the task: the mode value (or key), or the module name when
     * there is no mode or it is written elsewhere.
     */
    val range: TextRange,
    val message: String,
    val site: ModeSite,
    /** The mode as written, for [ModeSite.VALUE] and [ModeSite.WORD]; null otherwise. */
    val written: String?,
) {
    override fun toString(): String = "SecretModeFinding($site @ ${range.startOffset})"
}

/**
 * ANS-V113: a task of `template`, `copy`, `lineinfile`, `blockinfile` or `ini_file` (short names, `ansible.builtin.*`,
 * `ansible.legacy.*` and `community.general.ini_file`) that writes a secret into a file others can read
 * ([FileModes]: an octal or symbolic mode that lets others read, or no mode at all on a file the module creates).
 * Tasks with `state: absent` write nothing. A missing mode is not reported when the arguments are templated
 * (`args: "{{ x }}"`), nor when the module only changes an existing file, which keeps its mode: `lineinfile` and
 * `blockinfile` without `create: true`, `ini_file` with `create: false` (they fail on a missing file).
 *
 * What counts as a secret:
 * - `template`: the template file, resolved as navigation resolves `src` (role `templates/`, the task's directory,
 *   the playbook directories), uses a vaulted variable, or is a whole-file vault itself;
 * - `copy`, `lineinfile`, `blockinfile`, `ini_file`: `content`, `line`, `block` or `value(s)` (and their aliases) is a
 *   `!vault` value or uses a vaulted variable; `copy` of a whole-file vault `src` too, which Ansible decrypts unless
 *   `decrypt` is false;
 * - a task with `no_log: true` (its own, or inherited from a block or the play) writes a secret by its author's word.
 *
 * A vaulted variable is a `vault_*` name, a variable with a `!vault` value, one defined in a vault file (`vault.yml`
 * naming, [PathFacts.isVaultFile]) or a whole-file vault, or one whose Jinja value uses such a variable
 * (`db_password: "{{ vault_db_password }}"`), followed up to [MAX_STEPS] definitions deep with a cycle guard. The
 * variables come from [VarService] for the file's root, every definition counting.
 *
 * Nothing is decrypted and no value of a secret is read: only names, value shapes, envelope headers (the first 14
 * bytes of a candidate file) and the Jinja text of non-secret definitions and templates. Messages carry variable names
 * and paths only. The findings are cached on the file until it, the Ansible structure, a root's target version or any
 * PSI changes (templates and variable definitions live in other files). Call inside a read action in smart mode.
 */
object SecretFileModeChecks {
    /** How many definitions deep a variable's Jinja value is followed to a vaulted variable. */
    const val MAX_STEPS: Int = 3

    /** The most variables one file's analysis looks up while following values; beyond it a name counts as not vaulted. */
    private const val LOOKUP_BUDGET = 2_000

    /** Templates larger than this are not read (a rendered template of that size is no configuration file). */
    private const val MAX_TEMPLATE_BYTES = 1L shl 20

    private const val VAULT_PREFIX = "vault_"
    private const val UNSAFE_TAG = "!unsafe"
    private const val MODE = "mode"
    private const val CREATE = "create"
    private const val STATE_ABSENT = "absent"

    private val ANALYSIS = Key.create<CachedValue<List<SecretModeFinding>>>("ansibility.inspections.secretFileModes")
    private val TEMPLATE_NAMES = Key.create<CachedValue<List<String>>>("ansibility.inspections.secretFileModes.templateNames")

    /** The modules that write a file, by their short name. */
    private enum class Writer(
        /** The options whose value ends up in the file (aliases included). */
        val contentOptions: List<String>,
        /** `state: absent` removes content instead of writing it. */
        val hasState: Boolean,
        /**
         * The default of the module's `create` option, or null when the module always writes a file of its own. Without
         * `create` a missing file fails the module, and an existing one keeps its mode.
         */
        val createDefault: Boolean?,
    ) {
        TEMPLATE(emptyList(), false, null),
        COPY(listOf("content"), false, null),
        LINEINFILE(listOf("line", "value"), true, false),
        BLOCKINFILE(listOf("block", "content"), true, false),
        INI_FILE(listOf("value", "values"), true, true),
        ;

        companion object {
            private val BY_SHORT_NAME = mapOf(
                "template" to TEMPLATE, "copy" to COPY, "lineinfile" to LINEINFILE, "blockinfile" to BLOCKINFILE, "ini_file" to INI_FILE,
            )
            private val PREFIXES = listOf("ansible.builtin.", "ansible.legacy.")
            private const val COMMUNITY_INI_FILE = "community.general.ini_file"

            /** The writer [module] calls, by the name as written or (for redirected short names) its canonical name. */
            fun of(module: ModuleCall): Writer? = byName(module.name) ?: byName(module.canonical)

            private fun byName(name: String): Writer? {
                if (name == COMMUNITY_INI_FILE) return INI_FILE
                val prefix = PREFIXES.firstOrNull { name.startsWith(it) }
                val short = prefix?.let { name.removePrefix(it) } ?: name.takeIf { '.' !in it } ?: return null
                return BY_SHORT_NAME[short]
            }
        }
    }

    /**
     * The findings of [file], or an empty list when it is no task-like file of a root (role tasks and handlers,
     * playbooks, molecule playbooks and task files) or an injected fragment. Call inside a read action.
     */
    fun of(file: PsiFile): List<SecretModeFinding> {
        val yaml = file as? YAMLFile ?: return emptyList()
        val project = file.project
        if (InjectedLanguageManager.getInstance(project).isInjectedFragment(file)) return emptyList()
        val virtualFile = file.viewProvider.virtualFile
        val workspace = AnsibleWorkspace.getInstance(project)
        val context = workspace.contextOf(virtualFile) ?: return emptyList()
        if (context.kind !in TaskCheckEnvironment.CHECKED_KINDS) return emptyList()
        return CachedValuesManager.getCachedValue(yaml, ANALYSIS) {
            val current = workspace.contextOf(virtualFile)?.takeIf { it.kind in TaskCheckEnvironment.CHECKED_KINDS }
            CachedValueProvider.Result.create(
                current?.let { FileAnalysis(yaml, it).findings() }.orEmpty(),
                yaml,
                workspace.structureTracker,
                TargetVersionDetector.getInstance(project).modificationTracker,
                PsiModificationTracker.getInstance(project),
            )
        }
    }

    /** One file's analysis: walks its tasks with the `no_log` they inherit and checks the file writers among them. */
    private class FileAnalysis(private val file: YAMLFile, private val context: FileContext) {
        private val project: Project = file.project
        private val vaulted = VaultedNames(project, context.root)
        private val findings = ArrayList<SecretModeFinding>()
        private val sources: List<RefOccurrence> by lazy {
            RefSites.occurrences(file).filter { occurrence ->
                (occurrence.kind == RefKind.TEMPLATE_SRC || occurrence.kind == RefKind.COPY_SRC) && !occurrence.isDynamic && !occurrence.isTemplated
            }
        }
        private val resolver: RefResolver by lazy { RefResolver(file, context) }

        fun findings(): List<SecretModeFinding> {
            val model = TaskFileModels.of(file)
            if (!model.isSequence) return emptyList()
            walk(model.items, inheritedNoLog = false)
            for (play in model.plays) {
                val noLog = noLogOf(play.keywords["no_log"]) ?: false
                play.sections().forEach { walk(it, noLog) }
            }
            return findings
        }

        /** Tasks inherit `no_log` from their blocks and play unless they set it themselves (a templated value is unknown: false). */
        private fun walk(items: List<TaskItem>, inheritedNoLog: Boolean) {
            for (item in items) {
                ProgressManager.checkCanceled()
                val noLog = noLogOf(item.keywords["no_log"]) ?: inheritedNoLog
                when (item) {
                    is BlockNode -> {
                        walk(item.block, noLog)
                        walk(item.rescue, noLog)
                        walk(item.always, noLog)
                    }
                    is TaskNode -> check(item, noLog)
                }
            }
        }

        private fun noLogOf(entry: YEntry?): Boolean? = entry?.let { literalBoolean(it.value) ?: false }

        private fun check(task: TaskNode, noLog: Boolean) {
            val module = task.module ?: return
            val writer = Writer.of(module) ?: return
            val args = module.args
            if (writer.hasState && (args.option("state") as? YScalar)?.text?.trim() == STATE_ABSENT) return
            val modeEntry = args.options[MODE]
            // Templated arguments (`args: "{{ x }}"`, Jinja in free-form words) may hold a mode nobody can see.
            if (modeEntry == null && isDynamic(args)) return
            val verdict = FileModes.verdict(modeEntry?.value)
            if (verdict == ModeVerdict.NOT_BROAD) return
            // Without a mode only a file the module creates gets the default; an existing one keeps its mode.
            if (verdict == ModeVerdict.MISSING && !createsFile(writer, args)) return
            val secret = secretOf(task, writer, args) ?: if (noLog) AnsibilityVaultChecksBundle.message("secret.no.log") else return
            finding(task, module, modeEntry, verdict, secret)?.let { findings += it }
        }

        /**
         * Whether the module creates the file when it is missing: `template` and `copy` always do, the others by their
         * `create` option or its default. A templated `create` is not known to be true.
         */
        private fun createsFile(writer: Writer, args: ModuleArgs): Boolean {
            val default = writer.createDefault ?: return true
            val create = args.option(CREATE) ?: return default
            return literalBoolean(create) == true
        }

        /**
         * The finding for [task], reported inside the task: on its own mode value or key, else (a mode merged in or an
         * aliased module mapping, written in another place that other tasks may share) on the module name; null when
         * even that is written elsewhere.
         */
        private fun finding(task: TaskNode, module: ModuleCall, modeEntry: YEntry?, verdict: ModeVerdict, secret: String): SecretModeFinding? {
            val written = (modeEntry?.value as? YScalar)?.text
            val message = if (verdict == ModeVerdict.MISSING) {
                AnsibilityVaultChecksBundle.message("inspection.v113.message.no.mode", secret)
            } else {
                AnsibilityVaultChecksBundle.message("inspection.v113.message", secret, written.orEmpty())
            }
            val finding = if (modeEntry == null) {
                SecretModeFinding(module.nameRange, message, ModeSite.ABSENT, null)
            } else {
                val value = modeEntry.value
                val keyRange = TaskModelBuilder.rangeOf(modeEntry.key)
                val valueRange = TaskModelBuilder.rangeOf(value, modeEntry.key)
                when {
                    // A value reached through an alias keeps the range of its anchor, which comes before the key.
                    task.range.contains(valueRange) && valueRange.startOffset >= keyRange.startOffset -> when {
                        value is YEmpty -> SecretModeFinding(keyRange, message, ModeSite.EMPTY_VALUE, null)
                        fromMapping(module.args, modeEntry) -> SecretModeFinding(valueRange, message, ModeSite.VALUE, written)
                        else -> SecretModeFinding(valueRange, message, ModeSite.WORD, written)
                    }
                    task.range.contains(keyRange) -> SecretModeFinding(keyRange, message, ModeSite.ALIAS, null)
                    else -> SecretModeFinding(module.nameRange, message, ModeSite.SHARED, null)
                }
            }
            return finding.takeIf { task.range.contains(it.range) }
        }

        /** The arguments come from a template: a templated `args:` string, or free-form words with Jinja that are no `k=v`. */
        private fun isDynamic(args: ModuleArgs): Boolean {
            val argsValue = args.argsKeyword?.value
            return (argsValue is YScalar && JinjaBearing.hasTemplateMarkers(argsValue.text)) ||
                args.rawParams?.let(JinjaBearing::hasTemplateMarkers) == true
        }

        /** Whether [entry] is a key of the module's own mapping or of `args:` (not a `k=v` word of a string). */
        private fun fromMapping(args: ModuleArgs, entry: YEntry): Boolean =
            (args.value as? YMap)?.entries?.any { it === entry } == true ||
                (args.argsKeyword?.value as? YMap)?.entries?.any { it === entry } == true

        // -------------------------------------------------------------------------------------------- secrets

        /** The secret [task] writes, as messages name it, or null when none is seen. */
        private fun secretOf(task: TaskNode, writer: Writer, args: ModuleArgs): String? {
            writer.contentOptions.firstNotNullOfOrNull { option -> args.option(option)?.let(::valueSecret) }?.let { return it }
            return when (writer) {
                Writer.TEMPLATE -> task.src?.let(::templateSecret)
                Writer.COPY -> task.src?.let { copySourceSecret(it, args) }
                else -> null
            }
        }

        /** A `!vault` value, or a vaulted variable used in a scalar of [value] (lists and mappings are searched). */
        private fun valueSecret(value: YValue): String? = when (value) {
            is YVault -> AnsibilityVaultChecksBundle.message("secret.vault.value")
            is YScalar -> if (value.tag == UNSAFE_TAG) null else jinjaSecret(value.text)
            is YSeq -> value.items.firstNotNullOfOrNull(::valueSecret)
            is YMap -> value.entries.firstNotNullOfOrNull { valueSecret(it.value) }
            is YEmpty -> null
        }

        /** The first vaulted variable [jinja] (a template or a YAML string) uses, with the variables it went through. */
        private fun jinjaSecret(jinja: CharSequence): String? {
            if (!JinjaBearing.hasTemplateMarkers(jinja)) return null
            return VaultedNames.namesUsedBy(jinja).firstNotNullOfOrNull(vaulted::chainOf)?.let(::describe)
        }

        /** `vault_db_password`, or `vault_db_password (through db_password)` for a chain from db_password to it. */
        private fun describe(chain: List<String>): String =
            if (chain.size == 1) {
                AnsibilityVaultChecksBundle.message("secret.variable", chain.single())
            } else {
                AnsibilityVaultChecksBundle.message("secret.variable.through", chain.last(), chain.dropLast(1).joinToString(" → "))
            }

        private fun templateSecret(src: SrcRef): String? {
            val template = resolvedSource(src) ?: return null
            if (VaultEnvelopes.isWholeFileVault(template)) return AnsibilityVaultChecksBundle.message("secret.vault.file", src.text)
            return templateNames(template)?.firstNotNullOfOrNull(vaulted::chainOf)?.let(::describe)
        }

        /**
         * A whole-file vault `src`, which the copy action decrypts unless `decrypt` is false; an empty or templated
         * `decrypt` is not known to be true (the action reads it with `boolean(…, strict=False)`), so it counts as false.
         */
        private fun copySourceSecret(src: SrcRef, args: ModuleArgs): String? {
            val decrypt = args.option("decrypt")
            if (decrypt != null && literalBoolean(decrypt) != true) return null
            val source = resolvedSource(src) ?: return null
            return if (VaultEnvelopes.isWholeFileVault(source)) AnsibilityVaultChecksBundle.message("secret.vault.file", src.text) else null
        }

        /** The file a static `src` resolves to, as navigation resolves it; null for a dynamic, remote or missing source. */
        private fun resolvedSource(src: SrcRef): VirtualFile? {
            val range = src.value.range ?: return null
            val occurrence = sources.firstOrNull { it.range.startOffset >= range.start && it.range.endOffset <= range.end } ?: return null
            val resolution = resolver.resolve(occurrence)
            if (resolution.status != ResolutionStatus.RESOLVED) return null
            return resolution.targets.firstOrNull()?.file?.takeIf { it.isValid && !it.isDirectory }
        }

        /**
         * The variables the template reads, from its current text (unsaved changes included), cached on the template
         * until it changes; null for binary or large files.
         */
        private fun templateNames(template: VirtualFile): List<String>? {
            if (template.length > MAX_TEMPLATE_BYTES || template.fileType.isBinary) return null
            val psi = PsiManager.getInstance(project).findFile(template) ?: return null
            return CachedValuesManager.getCachedValue(psi, TEMPLATE_NAMES) {
                CachedValueProvider.Result.create(VaultedNames.namesUsedBy(psi.viewProvider.contents), psi)
            }
        }
    }

    /**
     * Which variable names of one root are vaulted, and through which variables a name reaches one. Results are kept
     * for the analysis of one file; lookups beyond [LOOKUP_BUDGET] count as not vaulted, so a pathological tree of
     * Jinja definitions stays cheap on the highlighting path.
     */
    private class VaultedNames(private val project: Project, private val root: AnsibleRoot) {
        private val service = VarService.getInstance(project)
        private val definitions = HashMap<String, List<VarDefinition>>()
        private val itself = HashMap<String, Boolean>()
        private val chains = HashMap<String, List<String>?>()
        private val vaultFiles = HashMap<VirtualFile, Boolean>()
        private var lookups = 0

        /** The variables from [name] to a vaulted one ([name] first, the vaulted variable last), or null. */
        fun chainOf(name: String): List<String>? {
            if (name in chains) return chains[name]
            var cut = false
            val visiting = HashSet<String>()

            fun follow(current: String, steps: Int): List<String>? {
                if (isVaultedItself(current)) return listOf(current)
                if (steps >= MAX_STEPS) return null
                if (!visiting.add(current)) {
                    cut = true
                    return null
                }
                try {
                    for (definition in definitionsOf(current)) {
                        ProgressManager.checkCanceled()
                        if (definition.valueShape != ValueShape.JINJA || definition.kind == VarDefKind.JINJA_LOCAL) continue
                        val value = valueText(definition) ?: continue
                        for (used in namesUsedBy(value)) {
                            follow(used, steps + 1)?.let { return listOf(current) + it }
                        }
                    }
                    return null
                } finally {
                    visiting.remove(current)
                }
            }

            val chain = follow(name, 0)
            // A miss that met a cycle depends on the path it was asked from; only complete answers are kept.
            if (chain != null || !cut) chains[name] = chain
            return chain
        }

        /** A `vault_*` name, or a variable with a `!vault` value or a definition in a vault file or whole-file vault. */
        private fun isVaultedItself(name: String): Boolean = itself.getOrPut(name) {
            name.startsWith(VAULT_PREFIX) || definitionsOf(name).any { it.valueShape == ValueShape.VAULT || isVaultFile(it.location.file) }
        }

        /** A vault file by its name, else by its first 14 bytes (read once per file and analysis). */
        private fun isVaultFile(file: VirtualFile): Boolean = vaultFiles.getOrPut(file) {
            PathFacts.of(file).isVaultFile || VaultEnvelopes.isWholeFileVault(file)
        }

        private fun definitionsOf(name: String): List<VarDefinition> = definitions.getOrPut(name) {
            if (++lookups > LOOKUP_BUDGET) emptyList() else service.symbol(root, name).definitions
        }

        /** The Jinja text of a definition's scalar value (never called for vaulted definitions). */
        private fun valueText(definition: VarDefinition): String? =
            (VarLocations.keyValueAt(project, definition.location)?.value as? YAMLScalar)?.textValue

        companion object {
            /** The variables [jinja] reads: free references that are not calls (`lookup(…)`) and `vars`/`hostvars` reads. */
            fun namesUsedBy(jinja: CharSequence): List<String> {
                if (!JinjaBearing.hasTemplateMarkers(jinja)) return emptyList()
                val refs = JinjaRefs.analyze(jinja)
                return (refs.references.filter { !it.called }.map { it.name to it.nameRange.startOffset } +
                    refs.indirectReferences.map { it.name to it.nameRange.startOffset })
                    .sortedBy { it.second }.map { it.first }.distinct()
            }
        }
    }

    /** Ansible's `boolean()` of a literal scalar; null for a templated, missing or non-boolean value. */
    private fun literalBoolean(value: YValue?): Boolean? {
        val scalar = value as? YScalar ?: return null
        if (JinjaBearing.hasTemplateMarkers(scalar.text)) return null
        return when (val resolved = scalar.resolved) {
            is Resolved.Bool -> resolved.value
            is Resolved.Int -> when (resolved.value) {
                BigInteger.ONE -> true
                BigInteger.ZERO -> false
                else -> null
            }
            is Resolved.Str -> resolved.value.trim().lowercase().let { text ->
                when (text) {
                    in Booleans.TRUE_STRINGS -> true
                    in Booleans.FALSE_STRINGS -> false
                    else -> null
                }
            }
            else -> null
        }
    }
}
