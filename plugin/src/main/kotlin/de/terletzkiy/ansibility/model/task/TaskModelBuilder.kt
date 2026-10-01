package de.terletzkiy.ansibility.model.task

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.TextRange
import de.terletzkiy.ansibility.semantics.yaml.ScalarStyle
import de.terletzkiy.ansibility.semantics.yaml.SourceRange
import de.terletzkiy.ansibility.semantics.yaml.YEntry
import de.terletzkiy.ansibility.semantics.yaml.YMap
import de.terletzkiy.ansibility.semantics.yaml.YScalar
import de.terletzkiy.ansibility.semantics.yaml.YSeq
import de.terletzkiy.ansibility.semantics.yaml.YValue

/**
 * Builds a [TaskFileModel] from a loaded YAML document ([de.terletzkiy.ansibility.yaml.PsiYValueAdapter]),
 * following ansible-core's loaders:
 * - a playbook item with an `import_playbook` key (any FQCN spelling) is an import, every other mapping a play;
 * - a task-list item with a `block` key is a block (`load_list_of_tasks`), every other mapping a task;
 * - a task's module is its single key that is not a task (or handler) keyword; with several candidates the one
 *   the snapshot knows as a module wins and the rest are [TaskItem.unknownKeys]; `action`/`local_action` name
 *   the module in their value;
 * - `with_<lookup>` keys are loops as [TaskSyntax.isLoopKey] decides;
 * - module arguments merge like `ModuleArgsParser`: `args:` first, overridden by the module's own options.
 *
 * Pure and PSI-free: the ranges come from the [YValue] tree. Loops check for cancellation.
 */
class TaskModelBuilder(private val syntax: TaskSyntax) {

    /** Builds the model of [document] (null: an empty file), read as [kind]. */
    fun build(document: YValue?, kind: TaskFileKind): TaskFileModel {
        if (document !is YSeq) return TaskFileModel.empty(kind)
        return when (kind) {
            TaskFileKind.PLAYBOOK -> playbook(document)
            TaskFileKind.TASKS -> TaskFileModel(kind, true, emptyList(), emptyList(), items(document, handler = false))
            TaskFileKind.HANDLERS -> TaskFileModel(kind, true, emptyList(), emptyList(), items(document, handler = true))
        }
    }

    /** Whether [document] reads as a playbook: some top-level mapping has `hosts` or an `import_playbook` key. */
    fun looksLikePlaybook(document: YValue?): Boolean =
        document is YSeq && document.items.any { item -> item is YMap && item.entries.any { it.key.text == "hosts" || isImportKey(it.key.text) } }

    // ------------------------------------------------------------------------------------------------ playbooks

    private fun playbook(document: YSeq): TaskFileModel {
        val plays = ArrayList<PlayNode>()
        val imports = ArrayList<PlaybookImportNode>()
        document.items.forEachIndexed { itemIndex, item ->
            ProgressManager.checkCanceled()
            if (item !is YMap) return@forEachIndexed
            val importEntry = item.entries.lastOrNull { isImportKey(it.key.text) }
            if (importEntry != null) imports += import(item, importEntry, itemIndex) else plays += play(item, plays.size, itemIndex)
        }
        return TaskFileModel(TaskFileKind.PLAYBOOK, true, plays, imports, emptyList())
    }

    private fun isImportKey(key: String): Boolean =
        key == IMPORT_PLAYBOOK_KEY || (key.endsWith(".$IMPORT_PLAYBOOK_KEY") && syntax.canonicalModule(key) == IMPORT_PLAYBOOK)

    private fun import(map: YMap, importEntry: YEntry, itemIndex: Int): PlaybookImportNode {
        val keywords = LinkedHashMap<String, YEntry>()
        for (entry in map.entries) {
            if (entry !== importEntry && syntax.isKeyword(KeywordOwner.PLAYBOOK_INCLUDE, entry.key.text)) keywords[entry.key.text] = entry
        }
        val path = (importEntry.value as? YScalar)?.let { scalar ->
            // Legacy "file.yml k=v" parameters are an error in ansible-core; the path is the first word.
            val first = KeyValueArgs.words(scalar.text).firstOrNull()
            val text = first?.let { scalar.text.substring(it.first, it.second) } ?: scalar.text.trim()
            NameRef(text, rangeOf(scalar, map))
        }
        return PlaybookImportNode(
            itemIndex = itemIndex,
            range = rangeOf(map),
            key = importEntry.key,
            path = path,
            name = keywords["name"]?.let { name(it.value, map) },
            keywords = keywords,
            expressions = expressions("when", keywords["when"]?.value, map),
            tags = tags(keywords["tags"]?.value, map),
            vars = varsKeys(keywords["vars"]?.value),
        )
    }

    private fun play(map: YMap, index: Int, itemIndex: Int): PlayNode {
        val keywords = LinkedHashMap<String, YEntry>()
        val unknown = ArrayList<YEntry>()
        for (entry in map.entries) {
            if (syntax.isKeyword(KeywordOwner.PLAY, entry.key.text)) keywords[entry.key.text] = entry else unknown += entry
        }
        return PlayNode(
            index = index,
            itemIndex = itemIndex,
            range = rangeOf(map),
            name = keywords["name"]?.let { name(it.value, map) },
            hosts = keywords["hosts"]?.let { hosts(it, map) },
            roles = (keywords["roles"]?.value as? YSeq)?.items.orEmpty().mapNotNull { roleEntry(it, map) },
            preTasks = items(keywords["pre_tasks"]?.value, handler = false),
            tasks = items(keywords["tasks"]?.value, handler = false),
            postTasks = items(keywords["post_tasks"]?.value, handler = false),
            handlers = items(keywords["handlers"]?.value, handler = true),
            vars = varsKeys(keywords["vars"]?.value),
            varsFiles = varsFiles(keywords["vars_files"]?.value, map),
            tags = tags(keywords["tags"]?.value, map),
            keywords = keywords,
            unknownKeys = unknown,
        )
    }

    private fun hosts(entry: YEntry, owner: YValue): HostsRef? {
        val pattern = when (val value = entry.value) {
            is YScalar -> value.text
            is YSeq -> value.items.mapNotNull { (it as? YScalar)?.text }.joinToString(",")
            else -> return null
        }
        return HostsRef(pattern, entry.value, rangeOf(entry.value, owner))
    }

    private fun roleEntry(item: YValue, owner: YValue): RoleEntryNode? = when (item) {
        is YScalar -> RoleEntryNode(NameRef(item.text, rangeOf(item, owner)), rangeOf(item, owner), emptyMap(), emptyList(), emptyList(), emptyList(), emptyList())
        is YMap -> {
            val keywords = LinkedHashMap<String, YEntry>()
            val params = ArrayList<YEntry>()
            for (entry in item.entries) {
                if (syntax.isKeyword(KeywordOwner.ROLE, entry.key.text)) keywords[entry.key.text] = entry else params += entry
            }
            val nameValue = (keywords["role"] ?: keywords["name"])?.value
            RoleEntryNode(
                name = nameValue?.let { name(it, item) },
                range = rangeOf(item, owner),
                keywords = keywords,
                params = params,
                tags = tags(keywords["tags"]?.value, item),
                expressions = expressions("when", keywords["when"]?.value, item),
                vars = varsKeys(keywords["vars"]?.value),
            )
        }
        else -> null
    }

    private fun varsFiles(value: YValue?, owner: YValue): List<NameRef> = when (value) {
        is YScalar -> listOf(NameRef(value.text, rangeOf(value, owner)))
        // An item may itself be a list: the first file of it that exists is loaded.
        is YSeq -> value.items.flatMap { varsFiles(it, owner) }
        else -> emptyList()
    }

    // ------------------------------------------------------------------------------------------------ task lists

    private fun items(value: YValue?, handler: Boolean): List<TaskItem> {
        if (value !is YSeq) return emptyList()
        return value.items.mapNotNull { item ->
            ProgressManager.checkCanceled()
            if (item !is YMap) null
            else if (item.entries.any { it.key.text == "block" }) block(item, handler)
            else task(item, handler)
        }
    }

    private fun block(map: YMap, handler: Boolean): BlockNode {
        val keywords = LinkedHashMap<String, YEntry>()
        val unknown = ArrayList<YEntry>()
        for (entry in map.entries) {
            if (syntax.isKeyword(KeywordOwner.BLOCK, entry.key.text)) keywords[entry.key.text] = entry else unknown += entry
        }
        return BlockNode(
            range = rangeOf(map),
            name = keywords["name"]?.let { name(it.value, map) },
            block = items(keywords["block"]?.value, handler),
            rescue = items(keywords["rescue"]?.value, handler),
            always = items(keywords["always"]?.value, handler),
            keywords = keywords,
            unknownKeys = unknown,
            expressions = expressions("when", keywords["when"]?.value, map),
            tags = tags(keywords["tags"]?.value, map),
            vars = varsKeys(keywords["vars"]?.value),
            notify = names(keywords["notify"]?.value, map),
            isHandler = handler,
        )
    }

    private fun task(map: YMap, handler: Boolean): TaskNode {
        val owner = if (handler) KeywordOwner.HANDLER else KeywordOwner.TASK
        val keywords = LinkedHashMap<String, YEntry>()
        val candidates = ArrayList<YEntry>()
        val withKeys = ArrayList<YEntry>()
        for (entry in map.entries) {
            val key = entry.key.text
            when {
                syntax.isKeyword(owner, key) -> keywords[key] = entry
                key.startsWith(TaskSyntax.WITH_PREFIX) && key.length > TaskSyntax.WITH_PREFIX.length -> withKeys += entry
                else -> candidates += entry
            }
        }
        val unknown = ArrayList<YEntry>()
        val actionEntry = keywords["action"] ?: keywords["local_action"]
        val module = if (actionEntry != null) {
            unknown += candidates
            val form = if (actionEntry.key.text == "local_action") ModuleForm.LOCAL_ACTION else ModuleForm.ACTION
            actionModule(actionEntry, form, keywords["args"], map)
        } else {
            val chosen = candidates.firstOrNull { syntax.isKnownModule(it.key.text) } ?: candidates.firstOrNull()
            candidates.filterTo(unknown) { it !== chosen }
            chosen?.let { keyModule(it, keywords["args"], map) }
        }
        val (loopKeys, notLoops) = withKeys.partition { syntax.isLoopKey(it.key.text, module?.canonical) }
        unknown += notLoops
        // A task has one loop; with several (an error in ansible-core) the first in source order is kept.
        val loopEntry = map.entries.firstOrNull { entry -> entry.key.text == "loop" || loopKeys.any { it === entry } }
        val loop = loopEntry?.let { entry ->
            val lookup = entry.key.text.takeIf { it != "loop" }?.removePrefix(TaskSyntax.WITH_PREFIX)
            LoopInfo(entry.key, entry.value, lookup)
        }
        unknown.sortBy { it.key.range?.start ?: 0 }
        val canonical = module?.canonical
        return TaskNode(
            range = rangeOf(map),
            name = keywords["name"]?.let { name(it.value, map) },
            module = module,
            keywords = keywords,
            unknownKeys = unknown,
            loop = loop,
            loopControl = keywords["loop_control"]?.let { loopControl(it, map) },
            register = keywords["register"]?.let { name(it.value, map) },
            notify = names(keywords["notify"]?.value, map),
            listen = if (handler) names(keywords["listen"]?.value, map) else emptyList(),
            expressions = taskExpressions(keywords, module, map),
            tags = tags(keywords["tags"]?.value, map),
            vars = varsKeys(keywords["vars"]?.value),
            delegateTo = keywords["delegate_to"],
            become = keywords["become"],
            taskInclude = module?.takeIf { canonical == INCLUDE_TASKS || canonical == IMPORT_TASKS }?.let { taskInclude(it, map) },
            roleInclude = module?.takeIf { canonical == INCLUDE_ROLE || canonical == IMPORT_ROLE }?.let { roleInclude(it, keywords["vars"], map) },
            src = module?.takeIf { canonical == TEMPLATE || canonical == COPY }?.let(::src),
            isHandler = handler,
        )
    }

    private fun keyModule(entry: YEntry, argsKeyword: YEntry?, owner: YValue): ModuleCall {
        val name = entry.key.text
        return ModuleCall(
            name = name,
            nameRange = rangeOf(entry.key, owner),
            canonical = syntax.canonicalModule(name),
            form = ModuleForm.KEY,
            args = args(entry.value, name, argsKeyword, owner, skipKey = null),
        )
    }

    /** `action: copy src=a dest=b` or `action: {module: copy, src: a}`. */
    private fun actionModule(entry: YEntry, form: ModuleForm, argsKeyword: YEntry?, owner: YValue): ModuleCall? {
        return when (val value = entry.value) {
            is YScalar -> {
                val words = KeyValueArgs.words(value.text)
                val first = words.firstOrNull() ?: return null
                val name = value.text.substring(first.first, first.second)
                val rest = value.text.substring(first.second)
                val base = textStart(value)
                val nameRange = base?.let { TextRange(it + first.first, it + first.second) } ?: rangeOf(value, owner)
                val restScalar = YScalar(
                    text = rest,
                    style = ScalarStyle.PLAIN,
                    sourceText = rest,
                    range = base?.let { SourceRange(it + first.second, it + value.text.length) },
                )
                ModuleCall(name, nameRange, syntax.canonicalModule(name), form, args(restScalar, name, argsKeyword, owner, skipKey = null, original = value))
            }
            is YMap -> {
                val moduleValue = value["module"] as? YScalar ?: return null
                val name = moduleValue.text
                ModuleCall(name, rangeOf(moduleValue, owner), syntax.canonicalModule(name), form, args(value, name, argsKeyword, owner, skipKey = "module"))
            }
            else -> null
        }
    }

    private fun args(
        value: YValue,
        module: String,
        argsKeyword: YEntry?,
        owner: YValue,
        skipKey: String?,
        original: YValue = value,
    ): ModuleArgs {
        val options = LinkedHashMap<String, YEntry>()
        (argsKeyword?.value as? YMap)?.entries?.forEach { options[it.key.text] = it }
        var rawParams: String? = null
        var rawValue: YScalar? = null
        when (value) {
            is YMap -> value.entries.forEach { if (it.key.text != skipKey) options[it.key.text] = it }
            is YScalar -> {
                val parsed = KeyValueArgs.parse(value.text, checkRaw = syntax.isFreeFormModule(module) || module in FREE_FORM_FALLBACK)
                val base = textStart(value)
                for (option in parsed.options) {
                    val keyRange = base?.let { SourceRange(it + option.keyStart, it + option.keyEnd) } ?: original.range
                    val valueRange = base?.let { SourceRange(it + option.valueStart, it + option.valueEnd) } ?: original.range
                    // parse_kv hands modules strings; the tag keeps YAML 1.1 typing (0644 → 420) off these values.
                    options[option.key] = YEntry(
                        YScalar(option.key, ScalarStyle.PLAIN, range = keyRange),
                        YScalar(option.value, ScalarStyle.PLAIN, tag = STR_TAG, sourceText = option.value, range = valueRange),
                    )
                }
                rawParams = parsed.rawParams
                if (rawParams != null) rawValue = original as? YScalar ?: value
            }
            else -> Unit
        }
        return ModuleArgs(options, rawParams, rawValue, original, argsKeyword)
    }

    private fun loopControl(entry: YEntry, owner: YValue): LoopControlInfo {
        val map = entry.value as? YMap
        return LoopControlInfo(
            entry = entry,
            loopVar = map?.get("loop_var")?.let { name(it, owner) },
            indexVar = map?.get("index_var")?.let { name(it, owner) },
            label = map?.get("label"),
            extended = map?.get("extended"),
            extendedAllItems = map?.get("extended_allitems"),
            pause = map?.get("pause"),
        )
    }

    private fun taskExpressions(keywords: Map<String, YEntry>, module: ModuleCall?, owner: YValue): List<ImplicitExpression> {
        val result = ArrayList<ImplicitExpression>()
        for (key in IMPLICIT_TASK_KEYS) result += expressions(key, keywords[key]?.value, owner)
        (keywords["loop_control"]?.value as? YMap)?.get("break_when")?.let { result += expressions("break_when", it, owner) }
        when (module?.canonical) {
            ASSERT -> result += expressions("that", module.args.option("that"), owner)
            DEBUG -> result += expressions("var", module.args.option("var"), owner)
        }
        return result.sortedBy { it.range.startOffset }
    }

    private fun taskInclude(module: ModuleCall, owner: YValue): TaskFileInclude {
        val kind = if (module.canonical == IMPORT_TASKS) IncludeKind.IMPORT else IncludeKind.INCLUDE
        val file = module.args.rawParamsValue?.let { NameRef(module.args.rawParams!!.trim(), rangeOf(it, owner)) }
            ?: (module.args.option("file") ?: module.args.option("_raw_params"))?.let { name(it, owner) }
        return TaskFileInclude(kind, file, module.args.options["apply"])
    }

    private fun roleInclude(module: ModuleCall, varsKeyword: YEntry?, owner: YValue): RoleIncludeCall {
        val args = module.args
        fun ref(option: String) = args.option(option)?.let { name(it, owner) }
        return RoleIncludeCall(
            kind = if (module.canonical == IMPORT_ROLE) IncludeKind.IMPORT else IncludeKind.INCLUDE,
            name = ref("name"),
            tasksFrom = ref("tasks_from"),
            handlersFrom = ref("handlers_from"),
            varsFrom = ref("vars_from"),
            defaultsFrom = ref("defaults_from"),
            apply = args.options["apply"],
            public = args.option("public"),
            vars = varsKeyword,
        )
    }

    private fun src(module: ModuleCall): SrcRef? {
        val value = module.args.option("src") as? YScalar ?: return null
        val text = value.text
        val jinja = JINJA_START.find(text)
        val kind = when {
            jinja == null -> SrcKind.STATIC
            jinja.range.first > 0 -> SrcKind.DYNAMIC_PREFIX
            WHOLE_EXPRESSION.matches(text) -> SrcKind.WHOLE_VAR
            else -> SrcKind.TEMPLATED
        }
        val prefix = if (jinja == null) text else text.substring(0, jinja.range.first)
        val remote = module.canonical == COPY && isTrue(module.args.option("remote_src"))
        return SrcRef(module.canonical, value, kind, prefix, remote)
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private fun name(value: YValue, owner: YValue): NameRef? = (value as? YScalar)?.let { NameRef(it.text, rangeOf(it, owner)) }

    /** A string or a list of strings (`notify`, `listen`). */
    private fun names(value: YValue?, owner: YValue): List<NameRef> = when (value) {
        is YScalar -> listOf(NameRef(value.text, rangeOf(value, owner)))
        is YSeq -> value.items.mapNotNull { name(it, owner) }
        else -> emptyList()
    }

    /** `tags`: a list, or a comma-separated string (each tag keeps the string's range). */
    private fun tags(value: YValue?, owner: YValue): List<NameRef> = when (value) {
        is YScalar -> value.text.split(',').map { it.trim() }.filter { it.isNotEmpty() }.map { NameRef(it, rangeOf(value, owner)) }
        is YSeq -> value.items.mapNotNull { name(it, owner) }
        else -> emptyList()
    }

    private fun varsKeys(value: YValue?): List<NameRef> {
        if (value !is YMap) return emptyList()
        return value.entries.map { NameRef(it.key.text, rangeOf(it.key, value)) }
    }

    /** The expressions of an implicit-expression key: one per string, list items included. */
    private fun expressions(key: String, value: YValue?, owner: YValue): List<ImplicitExpression> = when (value) {
        is YScalar -> listOf(ImplicitExpression(key, value, rangeOf(value, owner)))
        is YSeq -> value.items.filterIsInstance<YScalar>().map { ImplicitExpression(key, it, rangeOf(it, owner)) }
        else -> emptyList()
    }

    companion object {
        const val IMPORT_PLAYBOOK_KEY: String = "import_playbook"
        const val IMPORT_PLAYBOOK: String = "ansible.builtin.import_playbook"
        const val INCLUDE_TASKS: String = "ansible.builtin.include_tasks"
        const val IMPORT_TASKS: String = "ansible.builtin.import_tasks"
        const val INCLUDE_ROLE: String = "ansible.builtin.include_role"
        const val IMPORT_ROLE: String = "ansible.builtin.import_role"
        const val TEMPLATE: String = "ansible.builtin.template"
        const val COPY: String = "ansible.builtin.copy"
        const val ASSERT: String = "ansible.builtin.assert"
        const val DEBUG: String = "ansible.builtin.debug"

        /** The tag given to `k=v` option values, which ansible-core passes to modules as strings. */
        const val STR_TAG: String = "!!str"

        /** The task keys whose values are bare Jinja expressions (keyword docs: `template: implicit`). */
        val IMPLICIT_TASK_KEYS: List<String> = listOf("when", "changed_when", "failed_when", "until")

        /** Free-form modules when the snapshot is unavailable ([TaskSyntax.fallback]). */
        private val FREE_FORM_FALLBACK = setOf(
            "command", "shell", "raw", "script",
            "ansible.builtin.command", "ansible.builtin.shell", "ansible.builtin.raw", "ansible.builtin.script",
        )

        private val JINJA_START = Regex("""\{\{|\{%""")
        private val WHOLE_EXPRESSION = Regex("""^\{\{(?:(?!\}\}|\{\{).)*\}\}$""", RegexOption.DOT_MATCHES_ALL)

        /** Python truthiness of a boolean-ish option value as Ansible's `boolean()` reads it. */
        private fun isTrue(value: YValue?): Boolean =
            (value as? YScalar)?.text?.trim()?.lowercase() in setOf("yes", "on", "1", "true", "y", "t")

        /**
         * The file offset of [scalar]'s loaded text, when the text appears verbatim in its source spelling (plain
         * and simply quoted scalars); null otherwise (escapes or folding).
         */
        fun textStart(scalar: YScalar): Int? {
            val range = scalar.range ?: return null
            if (scalar.style == ScalarStyle.LITERAL || scalar.style == ScalarStyle.FOLDED) return null
            val contentStart = range.end - scalar.sourceText.length
            val at = scalar.sourceText.indexOf(scalar.text)
            return if (at < 0 || scalar.text.isEmpty()) null else contentStart + at
        }

        /** [value]'s range, or [owner]'s when it has none (an empty value without position). */
        fun rangeOf(value: YValue, owner: YValue? = null): TextRange {
            val range = value.range ?: owner?.range ?: return TextRange.EMPTY_RANGE
            return TextRange(range.start, range.end)
        }
    }
}
