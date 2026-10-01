package de.terletzkiy.ansibility.completion.tasks

import com.intellij.codeInsight.completion.PrefixMatcher
import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.icons.AllIcons
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import de.terletzkiy.ansibility.api.AnsibleDocService
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.docs.DocsHtml
import de.terletzkiy.ansibility.docs.ModuleOptions
import de.terletzkiy.ansibility.model.task.KeywordOwner
import de.terletzkiy.ansibility.model.task.TaskFileModel
import de.terletzkiy.ansibility.model.task.TaskModelBuilder
import de.terletzkiy.ansibility.model.task.TaskSyntax
import de.terletzkiy.ansibility.semantics.schema.Choices
import de.terletzkiy.ansibility.semantics.schema.DocSnapshot
import de.terletzkiy.ansibility.semantics.schema.KeywordDoc
import de.terletzkiy.ansibility.semantics.schema.ModuleDoc
import de.terletzkiy.ansibility.semantics.schema.OptionSpec
import de.terletzkiy.ansibility.semantics.schema.OptionType
import de.terletzkiy.ansibility.semantics.yaml.Resolved
import de.terletzkiy.ansibility.semantics.yaml.ScalarStyle
import de.terletzkiy.ansibility.semantics.yaml.YScalar
import de.terletzkiy.ansibility.semantics.yaml.Yaml11Resolver

/**
 * Builds the lookup items of one [TaskSlot] (plan F5.7, X19) in [root], from the root's documentation
 * ([AnsibleDocService]) and the task syntax of its target line ([syntax]):
 * - module names ([ModuleEntry]): the short description as tail, the collection as type text, deprecated and
 *   redirected names struck out; ranked `ansible.builtin` first, then modules used in the root, then the rest;
 * - option keys: required ones first and bold, `state` next (by Ansible convention the desired-state selector),
 *   then options this file already sets on the same module, then the rest; options that are set (by name or alias)
 *   are left out; aliases only when the typed prefix starts one;
 * - playbook keywords of the owner's level with their type and the first sentence of their description;
 * - values: an option's choices (dict choices with their description as tail) or `true`/`false` for bool options
 *   and keywords.
 *
 * Every item carries a [TaskLookupObject], so Ctrl+Q in the popup shows the docs area's card.
 * [fileModel] is the model of the file being edited, used to rank options it already uses. Module items are built
 * only for names [matcher] accepts, since there are hundreds of them.
 */
internal class TaskLookupItems(
    private val project: Project,
    private val root: AnsibleRoot,
    private val syntax: TaskSyntax,
    private val fileModel: TaskFileModel?,
    private val matcher: PrefixMatcher,
) {
    private val docs: AnsibleDocService = AnsibleDocService.getInstance(project)
    private val moduleDocs = HashMap<String, ModuleDoc?>()

    /** The items for [slot]. */
    fun items(slot: TaskSlot): List<LookupElement> = when (slot) {
        is TaskSlot.Key -> keyItems(slot)
        is TaskSlot.Value -> valueItems(slot)
        is TaskSlot.ListItem -> listItems(slot)
    }

    /** Every alias of the options offered at [slot] (for restarting completion once an alias is typed). */
    fun aliases(slot: TaskSlot): Set<String> {
        val options = when (slot) {
            is TaskSlot.Key -> slot.owner as? KeyOwner.Options
            is TaskSlot.ListItem -> slot.owner
            is TaskSlot.Value -> null
        } ?: return emptySet()
        return optionsAt(options.module, options.path)?.values?.flatMapTo(HashSet()) { it.aliases }.orEmpty()
    }

    // ------------------------------------------------------------------------------------------------ keys

    private fun keyItems(slot: TaskSlot.Key): List<LookupElement> = when (val owner = slot.owner) {
        KeyOwner.Play -> playKeys(slot)
        is KeyOwner.Task -> {
            val keywordOwner = if (owner.handler) KeywordOwner.HANDLER else KeywordOwner.TASK
            val keywords = keywordItems(keywordOwner, slot)
            if (owner.module != null) {
                keywords
            } else {
                // A new item: a module, a task keyword, or `block:` to make it a block.
                keywords + keywordItems(KeywordOwner.BLOCK, slot, only = setOf(BLOCK)) + moduleItems(slot.flow)
            }
        }
        is KeyOwner.Options -> optionItems(owner, slot)
        else -> keywordItems(owner.keywordOwner() ?: return emptyList(), slot)
    }

    /** A playbook item is a play, or an `import_playbook` entry once it has the import key. */
    private fun playKeys(slot: TaskSlot.Key): List<LookupElement> {
        if (slot.present.any(::isImportKey)) return keywordItems(KeywordOwner.PLAYBOOK_INCLUDE, slot)
        val play = keywordItems(KeywordOwner.PLAY, slot)
        val includeKeywords = syntax.keywords(KeywordOwner.PLAYBOOK_INCLUDE)
        if (!includeKeywords.containsAll(slot.present)) return play
        return play + keywordItems(KeywordOwner.PLAYBOOK_INCLUDE, slot, only = setOf(TaskModelBuilder.IMPORT_PLAYBOOK_KEY))
    }

    private fun isImportKey(key: String): Boolean =
        key == TaskModelBuilder.IMPORT_PLAYBOOK_KEY ||
            key.endsWith("." + TaskModelBuilder.IMPORT_PLAYBOOK_KEY) && syntax.canonicalModule(key) == TaskModelBuilder.IMPORT_PLAYBOOK

    private fun keywordItems(owner: KeywordOwner, slot: TaskSlot.Key, only: Set<String>? = null): List<LookupElement> {
        val level = owner.level()
        return docs.keywords(root).mapNotNull { doc ->
            ProgressManager.checkCanceled()
            val name = doc.name
            if (owner.docName !in doc.appliesTo || name == DocSnapshot.WITH_LOOKUP || name in slot.present) return@mapNotNull null
            if (only != null && name !in only) return@mapNotNull null
            val element = LookupElementBuilder.create(TaskLookupObject.Keyword(root, name, level), name)
                .withIcon(AllIcons.Nodes.Property)
                .withTypeText(keywordType(doc), true)
                .withTailText(tail(DocsHtml.firstSentence(doc.description, rst = true)), true)
                .withInsertHandler(KeyInsertHandler(keywordShape(doc), slot.flow))
            PrioritizedLookupElement.withPriority(element, KEYWORD_PRIORITY)
        }
    }

    private fun keywordType(doc: KeywordDoc): String =
        doc.type?.name ?: doc.isa ?: AnsibilityTaskCompletionBundle.message("keyword.type")

    private fun keywordShape(doc: KeywordDoc): ValueShape = when {
        doc.name in SEQUENCE_KEYWORDS -> ValueShape.SEQUENCE
        doc.isa == "dict" || doc.isa == "class" -> ValueShape.MAPPING
        else -> ValueShape.SCALAR
    }

    private fun moduleItems(flow: Boolean): List<LookupElement> {
        val catalog = TaskCompletionCatalog.getInstance(project)
        val used = catalog.usedModules(root)
        val usedCanonical = used.mapTo(HashSet()) { syntax.canonicalModule(it) }
        return catalog.modules(root).filter { matcher.prefixMatches(it.name) }.map { entry ->
            ProgressManager.checkCanceled()
            val struck = entry.deprecated || entry.redirect != null
            val isUsed = entry.name in used || entry.canonical in usedCanonical
            val priority = when {
                struck -> DEPRECATED_PRIORITY
                entry.isBuiltin -> BUILTIN_PRIORITY + if (isUsed) USED_BONUS else 0.0
                isUsed -> USED_PRIORITY
                else -> MODULE_PRIORITY
            }
            val tail = when {
                entry.redirect != null -> AnsibilityTaskCompletionBundle.message("module.redirect.tail", entry.redirect)
                else -> tail(entry.description) + if (entry.deprecated) AnsibilityTaskCompletionBundle.message("module.deprecated.tail") else ""
            }
            val shape = when {
                entry.freeForm -> ValueShape.SCALAR
                entry.hasOptions -> ValueShape.MAPPING
                else -> ValueShape.NONE
            }
            val element = LookupElementBuilder.create(TaskLookupObject.Module(root, entry.name), entry.name)
                .withIcon(AllIcons.Nodes.Plugin)
                .withTypeText(entry.collection.orEmpty(), true)
                .withTailText(tail, true)
                .withStrikeoutness(struck)
                .withInsertHandler(KeyInsertHandler(shape, flow))
            PrioritizedLookupElement.withPriority(element, priority)
        }
    }

    private fun optionItems(owner: KeyOwner.Options, slot: TaskSlot.Key): List<LookupElement> {
        val options = optionsAt(owner.module, owner.path) ?: return emptyList()
        val freeForm = if (owner.path.isEmpty()) moduleFreeForm(owner.module) else null
        val set = slot.present.mapNotNullTo(HashSet()) { ModuleOptions.find(options, it)?.name }
        val usage = if (owner.path.isEmpty()) usageInFile(owner.module, options) else emptyMap()
        val prefix = slot.prefix
        val result = ArrayList<LookupElement>()
        for (option in options.values) {
            ProgressManager.checkCanceled()
            if (option.name == freeForm || option.name in set) continue
            val path = owner.path + option.name
            val priority = optionPriority(option, usage[option.name] ?: 0)
            result += optionItem(option.name, option, path, owner.module, slot.flow, priority, alias = false)
            if (prefix.isEmpty()) continue
            for (alias in option.aliases) {
                if (alias.startsWith(prefix, ignoreCase = true) && !option.name.startsWith(prefix, ignoreCase = true)) {
                    result += optionItem(alias, option, path, owner.module, slot.flow, priority - ALIAS_PENALTY, alias = true)
                }
            }
        }
        return result
    }

    private fun optionItem(
        key: String,
        option: OptionSpec,
        path: List<String>,
        module: String,
        flow: Boolean,
        priority: Double,
        alias: Boolean,
    ): LookupElement {
        val tail = if (alias) {
            AnsibilityTaskCompletionBundle.message("option.alias.tail", option.name)
        } else {
            tail(DocsHtml.firstSentence(option.description))
        }
        val element = LookupElementBuilder.create(TaskLookupObject.Option(root, module, path), key)
            .withIcon(AllIcons.Nodes.Parameter)
            .withTypeText(DocsHtml.typeAndRequired(option), true)
            .withTailText(tail, true)
            .withBoldness(option.required)
            .withStrikeoutness(option.deprecated != null)
            .withInsertHandler(KeyInsertHandler(optionShape(option), flow))
        return PrioritizedLookupElement.withPriority(element, priority)
    }

    private fun optionPriority(option: OptionSpec, uses: Int): Double = when {
        option.deprecated != null -> DEPRECATED_PRIORITY
        option.required -> REQUIRED_PRIORITY
        option.name == STATE -> STATE_PRIORITY
        uses > 0 -> USED_OPTION_PRIORITY + uses.coerceAtMost(MAX_USE_BONUS)
        else -> OPTION_PRIORITY
    }

    private fun optionShape(option: OptionSpec): ValueShape = when {
        option.type == OptionType.Dict -> ValueShape.MAPPING
        option.type == OptionType.List && option.elements == OptionType.Dict -> ValueShape.SEQUENCE
        else -> ValueShape.SCALAR
    }

    /** How often each top-level option of [module] is set by the other tasks of this file (aliases counted as their option). */
    private fun usageInFile(module: String, options: Map<String, OptionSpec>): Map<String, Int> {
        val model = fileModel ?: return emptyMap()
        val canonical = syntax.canonicalModule(module)
        val counts = HashMap<String, Int>()
        for (task in model.tasks()) {
            ProgressManager.checkCanceled()
            val call = task.module ?: continue
            if (call.canonical != canonical) continue
            for (key in call.args.options.keys) {
                val name = ModuleOptions.find(options, key)?.name ?: continue
                counts.merge(name, 1, Int::plus)
            }
        }
        return counts
    }

    // ------------------------------------------------------------------------------------------------ values

    private fun valueItems(slot: TaskSlot.Value): List<LookupElement> {
        if (JINJA_START.containsMatchIn(slot.prefix)) return emptyList()
        return when (val owner = slot.owner) {
            is KeyOwner.Options -> optionValues(owner.module, owner.path + slot.key, slot.quoted, elements = false)
            else -> keywordValues(owner.keywordOwner() ?: return emptyList(), slot.key)
        }
    }

    private fun listItems(slot: TaskSlot.ListItem): List<LookupElement> {
        val owner = slot.owner
        val option = optionChain(owner.module, owner.path)?.last() ?: return emptyList()
        if (option.type != OptionType.List) return emptyList()
        if (option.elements == OptionType.Dict) {
            return if (slot.quoted) emptyList() else keyItems(TaskSlot.Key(owner, emptySet(), slot.prefix, flow = false))
        }
        if (JINJA_START.containsMatchIn(slot.prefix)) return emptyList()
        return optionValues(owner.module, owner.path, slot.quoted, elements = true)
    }

    /** Choices or bools of the option at [path]; with [elements], of the elements of a list option. */
    private fun optionValues(module: String, path: List<String>, quoted: Boolean, elements: Boolean): List<LookupElement> {
        val option = optionChain(module, path)?.last() ?: return emptyList()
        val type = if (elements || option.type == OptionType.List) option.elements else option.type
        val default = option.default?.let(DocsHtml::valueText)
        fun item(value: YScalar, description: List<String>): LookupElement {
            val text = value.text
            val defaultTail = if (DocsHtml.valueText(value) == default) AnsibilityTaskCompletionBundle.message("value.default.tail") else ""
            val needsQuotes = !quoted && value.style != ScalarStyle.PLAIN && Yaml11Resolver.resolvePlain(text) !is Resolved.Str
            val element = LookupElementBuilder.create(TaskLookupObject.OptionValue(root, module, path, text), text)
                .withIcon(AllIcons.Nodes.Enum)
                .withTypeText(option.name, true)
                .withTailText(tail(DocsHtml.firstSentence(description)) + defaultTail, true)
                .let { if (needsQuotes) it.withInsertHandler(QuotingInsertHandler) else it }
            return PrioritizedLookupElement.withPriority(element, VALUE_PRIORITY)
        }
        return when (val choices = option.choices) {
            is Choices.Values -> choices.values.filterIsInstance<YScalar>().map { item(it, emptyList()) }
            is Choices.Described -> choices.described.mapNotNull { (value, description) -> (value as? YScalar)?.let { item(it, description) } }
            null -> if (type == OptionType.Bool) boolItems { TaskLookupObject.OptionValue(root, module, path, it) } else emptyList()
        }
    }

    private fun keywordValues(owner: KeywordOwner, key: String): List<LookupElement> {
        val level = owner.level()
        val doc = docs.keywordDoc(root, key, level) ?: return emptyList()
        if (doc.type != OptionType.Bool) return emptyList()
        return boolItems { TaskLookupObject.KeywordValue(root, key, level, it) }
    }

    private fun boolItems(objectOf: (String) -> TaskLookupObject): List<LookupElement> = BOOLS.map { value ->
        val element = LookupElementBuilder.create(objectOf(value), value)
            .withIcon(AllIcons.Nodes.Constant)
            .withTypeText(AnsibilityTaskCompletionBundle.message("value.bool.type"), true)
        PrioritizedLookupElement.withPriority(element, VALUE_PRIORITY)
    }

    // ------------------------------------------------------------------------------------------------ docs

    /** The documented options at [path] of [module] (its own options for an empty path); null when undocumented. */
    private fun optionsAt(module: String, path: List<String>): Map<String, OptionSpec>? {
        val options = moduleOptions(module) ?: return null
        if (path.isEmpty()) return options
        return ModuleOptions.chain(options, path)?.last()?.options?.takeIf { it.isNotEmpty() }
    }

    private fun optionChain(module: String, path: List<String>): List<OptionSpec>? {
        val options = moduleOptions(module) ?: return null
        return ModuleOptions.chain(options, path)
    }

    private fun moduleOptions(module: String): Map<String, OptionSpec>? = moduleDoc(module)?.options

    private fun moduleFreeForm(module: String): String? = moduleDoc(module)?.freeForm

    /** The documentation of [module], looked up once per completion. */
    private fun moduleDoc(module: String): ModuleDoc? {
        if (module in moduleDocs) return moduleDocs[module]
        return docs.moduleDoc(root, module)?.doc.also { moduleDocs[module] = it }
    }

    /** A tail text: a space and [text], shortened to [MAX_TAIL] characters; empty without text. */
    private fun tail(text: String?): String {
        val plain = text?.let(StringUtil::collapseWhiteSpace)?.takeIf { it.isNotBlank() } ?: return ""
        return " " + StringUtil.shortenTextWithEllipsis(plain, MAX_TAIL, 0)
    }

    private companion object {
        const val BLOCK = "block"
        const val STATE = "state"
        const val MAX_TAIL = 80
        const val MAX_USE_BONUS = 50

        const val BUILTIN_PRIORITY = 300.0
        const val USED_BONUS = 10.0
        const val USED_PRIORITY = 200.0
        const val KEYWORD_PRIORITY = 150.0
        const val MODULE_PRIORITY = 100.0
        const val DEPRECATED_PRIORITY = 10.0

        const val REQUIRED_PRIORITY = 400.0
        const val STATE_PRIORITY = 300.0
        const val USED_OPTION_PRIORITY = 200.0
        const val OPTION_PRIORITY = 100.0
        const val ALIAS_PENALTY = 1.0
        const val VALUE_PRIORITY = 100.0

        val BOOLS = listOf("true", "false")

        /** Keywords whose value is a list of plays' tasks, blocks' tasks or role entries. */
        val SEQUENCE_KEYWORDS = setOf("tasks", "pre_tasks", "post_tasks", "handlers", "roles", "block", "rescue", "always")

        val JINJA_START = Regex("""\{[{%#]""")
    }
}
