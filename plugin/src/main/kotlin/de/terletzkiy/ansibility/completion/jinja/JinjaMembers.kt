package de.terletzkiy.ansibility.completion.jinja

import com.intellij.icons.AllIcons
import com.intellij.openapi.progress.ProgressManager
import de.terletzkiy.ansibility.api.InventoryService
import de.terletzkiy.ansibility.completion.jinja.AnsibilityJinjaCompletionBundle.message
import de.terletzkiy.ansibility.completion.jinja.JinjaNames.Companion.part
import de.terletzkiy.ansibility.completion.jinja.JinjaNames.Companion.typeText
import de.terletzkiy.ansibility.facts.FactSpec
import de.terletzkiy.ansibility.facts.FactsCatalog
import de.terletzkiy.ansibility.index.ValueSummary
import de.terletzkiy.ansibility.lang.jinja.refs.JinjaLocal
import de.terletzkiy.ansibility.lang.jinja.refs.JinjaLocalKind
import de.terletzkiy.ansibility.resolve.loop.LoopItemTyper
import de.terletzkiy.ansibility.semantics.schema.OptionSpec
import de.terletzkiy.ansibility.semantics.schema.OptionType

/** A typed chain: its option tree and where the card of its members lives (null: no variable card). */
internal class TypedChain(val option: OptionSpec, val doc: CandidateDoc.Variable?)

/**
 * Member completion after `.` and `['` (plan F1.3 member completion, F1.7, X10), for the chain in front of the caret:
 * - Jinja locals: `loop.` members; `for` targets typed by their iterable (so `server.` in
 *   `{% for server in haproxy_servers %}` offers the element options); namespace attributes;
 * - the loop variable (and `ansible_loop`) through [LoopItemTyper], in templates the union over rendering tasks;
 * - `ansible_facts` and the injected `ansible_*` facts from [FactsCatalog], dict-valued special variables;
 * - `groups` (group names), `hostvars` (host names, then per host the inventory names and connection variables),
 *   from every inventory of the root and its molecule scenarios;
 * - any other variable through the root's spec bindings (own role first), nested `options` down to [MAX_DEPTH],
 *   else the shape of a literal value.
 *
 * A `list` only has members after an index (`servers[0].name`). Members of spec'd variables link to the M2 card of
 * the nested option.
 */
internal class JinjaMembers(private val scope: JinjaCompletionScope) {

    /** Member candidates of [chain]; [keys] for a quoted subscript (any key, not only identifiers). */
    fun members(chain: JinjaCompletionPosition.Chain, keys: Boolean, accept: (String) -> Boolean): List<JinjaCandidate> {
        val root = chain.root
        val path = chain.path
        special(root, path, keys, accept)?.let { return it }
        val constPath = chain.constPath ?: return emptyList()
        if (constPath.size >= MAX_DEPTH) return emptyList()
        localMembers(root, constPath)?.let { return it.filter { c -> accept(c.lookupString) } }
        val typed = typeOf(root, constPath) ?: return emptyList()
        return optionMembers(typed, root, keys, accept)
    }

    /**
     * The type of `root.path…` at the caret, or null when unknown. Locals shadow every other name; a local that is
     * not a `for` target has no type.
     */
    fun typeOf(root: String, path: List<String>, depth: Int = 0): TypedChain? {
        if (depth > MAX_DEPTH) return null
        val local = scope.locals.firstOrNull { it.name == root }
        if (local != null) return localType(local, path, depth)
        scope.loopOf(root)?.let { entry ->
            val option = LoopItemTyper.typeOfPath(entry.loop, root, path) ?: return null
            val source = entry.loop.sourceVariable
            val doc = if (root == entry.loop.loopVar && source != null) CandidateDoc.Variable(source, entry.loop.sourcePath + "0" + path) else null
            return TypedChain(option, doc)
        }
        val option = LoopItemTyper.variableType(scope.project, scope.fileContext, scope.chain, root, path) ?: return null
        return TypedChain(option, CandidateDoc.Variable(root, path))
    }

    // ------------------------------------------------------------------------------------------------ locals

    private fun localType(local: JinjaLocal, path: List<String>, depth: Int): TypedChain? {
        if (local.kind != JinjaLocalKind.FOR_TARGET) return null
        val expression = iterableOf(local) ?: return null
        var base: CandidateDoc.Variable? = null
        val iteration = LoopItemTyper.iterate(expression, { name, accessors ->
            typeOf(name, accessors, depth + 1)?.also { base = it.doc }?.option
        })
        val element = iteration.element?.copy(name = local.name) ?: return null
        val option = LoopItemTyper.walk(element, path) ?: return null
        val doc = base?.let { CandidateDoc.Variable(it.name, it.path + "0" + path) }
        return TypedChain(option, doc)
    }

    /** The iterable expression of the `{% for %}` tag binding [local]; null for tuple targets. */
    private fun iterableOf(local: JinjaLocal): String? {
        val text = scope.analysis.text
        val from = local.definitionRange.endOffset
        val end = text.indexOf("%}", from).let { if (it < 0) text.length else it }
        val rest = text.subSequence(from, end).toString().removeSuffix("-").removeSuffix("+")
        val match = FOR_ITERABLE.matchEntire(rest) ?: return null
        return cutCondition(match.groupValues[1]).removeSuffix(RECURSIVE).trim().takeIf { it.isNotEmpty() }
    }

    /** The expression without a trailing top-level ` if <condition>` loop filter. */
    private fun cutCondition(expression: String): String {
        var depth = 0
        var quote: Char? = null
        for (i in expression.indices) {
            val c = expression[i]
            when {
                quote != null -> if (c == quote) quote = null
                c == '\'' || c == '"' -> quote = c
                c == '(' || c == '[' || c == '{' -> depth++
                c == ')' || c == ']' || c == '}' -> depth--
                depth == 0 && expression.startsWith(" if ", i) -> return expression.substring(0, i)
            }
        }
        return expression
    }

    /** Members of Jinja's `loop` and of namespace objects. */
    private fun localMembers(root: String, path: List<String>): List<JinjaCandidate>? {
        val local = scope.locals.firstOrNull { it.name == root } ?: return null
        if (path.isNotEmpty()) return null
        return when (local.kind) {
            JinjaLocalKind.LOOP -> JINJA_LOOP.map { (name, type) ->
                JinjaCandidate(name, Tier.MEMBER, 0, type, part(message("part.jinja.loop")), AllIcons.Nodes.Property,
                    CandidateDoc.Member("loop", name, type, message("loop.member.$name")))
            }
            JinjaLocalKind.SET -> scope.analysis.result.locals
                .filter { it.kind == JinjaLocalKind.NAMESPACE_ATTRIBUTE && it.owner == root }
                .distinctBy { it.name }
                .map { attribute ->
                    val kind = message("type.local.NAMESPACE_ATTRIBUTE")
                    JinjaCandidate(attribute.name, Tier.MEMBER, 0, kind, part(message("part.local")), AllIcons.Nodes.Property,
                        CandidateDoc.Local(attribute.name, kind, scope.locationOf(attribute)))
                }
            else -> null
        }
    }

    // ------------------------------------------------------------------------------------------------ special roots

    private fun special(root: String, path: List<Accessor>, keys: Boolean, accept: (String) -> Boolean): List<JinjaCandidate>? {
        if (scope.locals.any { it.name == root } || scope.loopOf(root) != null) return null
        val constPath = path.map { (it as? Accessor.Const)?.name }
        return when {
            root == ANSIBLE_FACTS -> {
                val names = constPath.takeIf { null !in it }?.filterNotNull() ?: return emptyList()
                facts(names, null, keys, accept)
            }
            root in FactsCatalog.injected && null !in constPath -> {
                val fact = FactsCatalog.injected.getValue(root)
                facts(listOf(fact.name) + constPath.filterNotNull(), root, keys, accept)
            }
            root == GROUPS && path.isEmpty() -> groups(keys, accept)
            root == HOSTVARS && path.isEmpty() -> hosts(keys, accept)
            root == HOSTVARS && path.size == 1 -> hostVariables(keys, accept)
            root == HOSTVARS && path.size > 1 -> {
                val variable = (path[1] as? Accessor.Const)?.name ?: return emptyList()
                val rest = path.drop(2).map { (it as? Accessor.Const)?.name ?: return emptyList() }
                val typed = typeOf(variable, rest) ?: return emptyList()
                optionMembers(typed, variable, keys, accept)
            }
            root in FactsCatalog.magicVars -> {
                val variable = FactsCatalog.magicVars.getValue(root)
                if (variable.keys.isEmpty() || null in constPath) return emptyList()
                var keysOf: Map<String, FactSpec> = variable.keys
                for (segment in constPath.filterNotNull()) keysOf = keysOf[segment]?.keys ?: return emptyList()
                keysOf.values.filter { accept(it.name) && (keys || isIdentifier(it.name)) }.map { key ->
                    JinjaCandidate(key.name, Tier.MEMBER, 0, key.typeText, part(root), AllIcons.Nodes.Property,
                        CandidateDoc.Magic(variable, constPath.filterNotNull() + key.name, key))
                }
            }
            else -> null
        }
    }

    /** Keys of the fact at [path] below `ansible_facts` (top-level facts for an empty path). */
    private fun facts(path: List<String>, injectedAs: String?, keys: Boolean, accept: (String) -> Boolean): List<JinjaCandidate> {
        val children: Collection<FactSpec> = if (path.isEmpty()) FactsCatalog.facts.values else {
            val fact = FactsCatalog.fact(path) ?: return emptyList()
            if (fact.type == "list" && path.last().toIntOrNull() == null) return emptyList()
            fact.keys.values
        }
        return children.filter { accept(it.name) && (keys || isIdentifier(it.name)) }.map { fact ->
            val label = fact.setBy?.let { message("part.fact.set.by", it.substringAfterLast('.')) } ?: message("part.fact")
            JinjaCandidate(fact.name, Tier.MEMBER, 0, fact.typeText, part(label), AllIcons.Nodes.Property,
                CandidateDoc.Fact(path + fact.name, fact, injectedAs))
        }
    }

    private fun groups(keys: Boolean, accept: (String) -> Boolean): List<JinjaCandidate> {
        val byName = LinkedHashMap<String, Pair<MutableSet<String>, MutableSet<String>>>()
        val service = InventoryService.getInstance(scope.project)
        for (inventory in service.inventories(scope.root)) {
            for ((name, group) in inventory.groups) {
                val (hosts, environments) = byName.getOrPut(name) { LinkedHashSet<String>() to LinkedHashSet() }
                hosts += group.hosts
                environments += inventory.environment
            }
        }
        for (molecule in service.moleculeInventories(scope.root)) {
            for ((name, group) in molecule.inventory.groups) {
                val (hosts, environments) = byName.getOrPut(name) { LinkedHashSet<String>() to LinkedHashSet() }
                hosts += group.hosts
                environments += message("part.molecule")
            }
        }
        return byName.entries.filter { accept(it.key) && (keys || isIdentifier(it.key)) }.map { (name, data) ->
            val (hosts, environments) = data
            JinjaCandidate(name, Tier.MEMBER, 0, "list[str]", part(message("part.group")) + part(environments.joinToString(", ")),
                AllIcons.Nodes.Folder, CandidateDoc.Group(name, hosts.toList(), environments.toList()))
        }
    }

    private fun hosts(keys: Boolean, accept: (String) -> Boolean): List<JinjaCandidate> {
        val byName = LinkedHashMap<String, MutableSet<String>>()
        for (inventory in InventoryService.getInstance(scope.project).inventories(scope.root)) {
            for (host in inventory.hosts.keys) byName.getOrPut(host) { LinkedHashSet() } += inventory.environment
        }
        return byName.entries.filter { accept(it.key) && (keys || isIdentifier(it.key)) }.map { (name, environments) ->
            JinjaCandidate(name, Tier.MEMBER, 0, "dict", part(message("part.host")) + part(environments.joinToString(", ")),
                AllIcons.Nodes.Plugin, CandidateDoc.Host(name, environments.toList()))
        }
    }

    /** The variables of one host: inventory names, connection variables and facts. */
    private fun hostVariables(keys: Boolean, accept: (String) -> Boolean): List<JinjaCandidate> {
        val result = ArrayList<JinjaCandidate>()
        FactsCatalog.magicVars.values.filter { it.connection || it.name in HOST_MAGIC }.forEach { variable ->
            if (accept(variable.name)) {
                result += JinjaCandidate(variable.name, Tier.MEMBER, 100, variable.typeText, part(JinjaNames.magicLabel(variable)),
                    AllIcons.Nodes.Constant, CandidateDoc.Magic(variable))
            }
        }
        for ((name, entries) in scope.catalog.inventory) {
            ProgressManager.checkCanceled()
            if (!accept(name) || (!keys && !isIdentifier(name)) || name in FactsCatalog.magicVars) continue
            result += JinjaCandidate(name, Tier.MEMBER, 0, entries.firstNotNullOfOrNull(JinjaNames::literalTypeText), part(message("part.inventory")),
                AllIcons.Nodes.Variable, CandidateDoc.Variable(name, emptyList()))
        }
        return result
    }

    // ------------------------------------------------------------------------------------------------ options

    /** The `options` of a typed chain; nothing for lists (they need an index) and untyped values. */
    private fun optionMembers(typed: TypedChain, owner: String, keys: Boolean, accept: (String) -> Boolean): List<JinjaCandidate> {
        val option = typed.option
        if (option.type == OptionType.List) return emptyList()
        val options = option.options ?: return emptyList()
        return options.values.withIndex().filter { (_, it) -> accept(it.name) && (keys || isIdentifier(it.name)) }.map { (index, sub) ->
            val default = sub.default?.takeUnless { ValueSummary.isSecret(sub.name, it) }?.let(ValueSummary::render)
            val tail = buildString {
                default?.let { append(message("tail.default", JinjaNames.shorten(it))) }
                if (sub.required) append(part(message("part.required")))
            }.ifEmpty { null }
            val doc = typed.doc?.let { CandidateDoc.Variable(it.name, it.path + sub.name) }
                ?: CandidateDoc.Member(owner, sub.name, typeText(sub), sub.description.firstOrNull())
            JinjaCandidate(
                sub.name, Tier.MEMBER, (if (sub.required) 500 else 0) + (options.size - index).coerceAtMost(499),
                typeText(sub), tail, AllIcons.Nodes.Property, doc,
                bold = sub.required && sub.default == null, deprecated = sub.deprecated != null,
            )
        }
    }

    companion object {
        /** Members deeper than this are not followed (plan F1.3: depth ≤ 4 below the variable). */
        const val MAX_DEPTH: Int = 4

        const val ANSIBLE_FACTS: String = "ansible_facts"
        const val GROUPS: String = "groups"
        const val HOSTVARS: String = "hostvars"

        private const val RECURSIVE = "recursive"
        private val FOR_ITERABLE = Regex("""^\s*in\s+(.+?)\s*$""", RegexOption.DOT_MATCHES_ALL)
        private val IDENTIFIER = Regex("[A-Za-z_][A-Za-z0-9_]*")
        private val HOST_MAGIC = setOf("ansible_facts", "group_names", "inventory_hostname", "inventory_hostname_short", "inventory_dir", "inventory_file")

        /** Jinja's `loop` variable inside `{% for %}` and each member's type. */
        private val JINJA_LOOP = listOf(
            "index" to "int", "index0" to "int", "revindex" to "int", "revindex0" to "int", "first" to "bool",
            "last" to "bool", "length" to "int", "cycle" to "function", "depth" to "int", "depth0" to "int",
            "previtem" to "raw", "nextitem" to "raw", "changed" to "function",
        )

        fun isIdentifier(name: String): Boolean = IDENTIFIER.matches(name)
    }
}
