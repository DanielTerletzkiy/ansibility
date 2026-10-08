package de.terletzkiy.ansibility.completion.jinja

import com.intellij.icons.AllIcons
import com.intellij.openapi.progress.ProgressManager
import de.terletzkiy.ansibility.api.InventoryService
import de.terletzkiy.ansibility.completion.jinja.AnsibilityJinjaCompletionBundle.message
import de.terletzkiy.ansibility.completion.jinja.JinjaNames.Companion.part
import de.terletzkiy.ansibility.completion.jinja.JinjaNames.Companion.typeText
import de.terletzkiy.ansibility.facts.FactSpec
import de.terletzkiy.ansibility.facts.FactsCatalog
import de.terletzkiy.ansibility.index.JinjaBearing
import de.terletzkiy.ansibility.index.ValueSummary
import de.terletzkiy.ansibility.lang.jinja.refs.JinjaLocal
import de.terletzkiy.ansibility.lang.jinja.refs.JinjaLocalKind
import de.terletzkiy.ansibility.model.role.RoleDefaults
import de.terletzkiy.ansibility.model.task.TaskNode
import de.terletzkiy.ansibility.resolve.VarViews
import de.terletzkiy.ansibility.resolve.loop.LoopItemTyper
import de.terletzkiy.ansibility.resolve.register.RegisteredDocs
import de.terletzkiy.ansibility.resolve.register.RegisteredResult
import de.terletzkiy.ansibility.resolve.register.RegisteredResults
import de.terletzkiy.ansibility.semantics.schema.OptionSpec
import de.terletzkiy.ansibility.semantics.schema.OptionType
import de.terletzkiy.ansibility.semantics.yaml.YMap
import de.terletzkiy.ansibility.semantics.yaml.YScalar
import de.terletzkiy.ansibility.semantics.yaml.YValue
import de.terletzkiy.ansibility.semantics.yaml.YVault
import de.terletzkiy.ansibility.vars.registered.RegisteredSites

/**
 * A typed chain: its option tree, where the card of its members lives (null: no variable card) and, for a chain into a
 * registered result, the result and the path below it (its members get the return values' docs).
 */
internal class TypedChain(val option: OptionSpec, val doc: CandidateDoc.Variable?, val registered: RegisteredChain? = null)

/** `result.path…` of a registered result ([path] below the registered variable, list elements as `"0"`). */
internal class RegisteredChain(val result: RegisteredResult, val path: List<String>) {
    /** The same result one element below this list chain, then [rest]. */
    fun element(rest: List<String>): RegisteredChain = RegisteredChain(result, path + LIST_ELEMENT + rest)

    private companion object {
        const val LIST_ELEMENT = "0"
    }
}

/**
 * Member completion after `.` and `['` (plan F1.3 member completion, F1.7, X10), for the chain in front of the caret:
 * - Jinja locals: `loop.` members; `for` targets typed by their iterable (so `server.` in
 *   `{% for server in haproxy_servers %}` offers the element options); namespace attributes;
 * - the loop variable (and `ansible_loop`) through [LoopItemTyper], in templates the union over rendering tasks;
 * - registered variables (plan amendment FU, F1.12): the documented keys of the task's result ([RegisteredResults]),
 *   nested return values via `contains`, `results` items of loops, with type, origin tail and the return value's
 *   card; a loop or `for` over `x.results` types its items the same way;
 * - `ansible_facts` and the injected `ansible_*` facts from [FactsCatalog], dict-valued special variables;
 * - `groups` (group names), `hostvars` (host names, then per host the inventory names and connection variables),
 *   from every inventory of the root and, when the caret sees Molecule ([JinjaCompletionScope.moleculeView]), its
 *   molecule scenarios;
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
            val registered = if (root == entry.loop.loopVar && source != null) registeredChain(source, entry.loop.sourcePath + "0" + path) else null
            return TypedChain(option, doc, registered)
        }
        // A visible `register:` decides the name: registered results rank above task, block, play, role and inventory
        // vars; of the names in this scope only include parameters (the `vars:` of an including task, or of the caret's
        // task when it is an include itself) and `template_vars` rank above them.
        val registered = scope.registered(root)?.takeIf { scope.taskVars.none { it.name == root && ranksAboveRegistered(it) } }
        if (registered != null) {
            val chain = RegisteredChain(registered, path).takeIf { registered.member(path) != null } ?: return null
            return registered.option(path)?.let { TypedChain(it, null, chain) }
        }
        val option = LoopItemTyper.variableType(scope.project, scope.fileContext, scope.chain, root, path, scope.moleculeView) ?: return null
        return TypedChain(option, CandidateDoc.Variable(root, path))
    }

    /** Whether the tier-T2 name [variable] ranks above a registered result of the same name (an include parameter). */
    private fun ranksAboveRegistered(variable: ScopeTaskVar): Boolean = when (variable.source) {
        ScopeTaskVar.Source.INCLUDE, ScopeTaskVar.Source.TEMPLATE -> true
        ScopeTaskVar.Source.TASK -> (scope.chain.lastOrNull() as? TaskNode)?.let { it.taskInclude != null || it.roleInclude != null } == true
        ScopeTaskVar.Source.BLOCK -> false
    }

    /** `name.path…` in the result registered as [name] at the caret, when the result documents that path. */
    private fun registeredChain(name: String, path: List<String>): RegisteredChain? {
        val result = scope.registered(name) ?: return null
        return RegisteredChain(result, path).takeIf { result.member(path) != null }
    }

    // ------------------------------------------------------------------------------------------------ locals

    private fun localType(local: JinjaLocal, path: List<String>, depth: Int): TypedChain? {
        if (local.kind != JinjaLocalKind.FOR_TARGET) return null
        val expression = iterableOf(local) ?: return null
        var base: CandidateDoc.Variable? = null
        var baseRegistered: RegisteredChain? = null
        val iteration = LoopItemTyper.iterate(expression, { name, accessors ->
            typeOf(name, accessors, depth + 1)?.also { base = it.doc; baseRegistered = it.registered }?.option
        })
        val element = iteration.element?.copy(name = local.name) ?: return null
        val option = LoopItemTyper.walk(element, path) ?: return null
        val doc = base?.let { CandidateDoc.Variable(it.name, it.path + "0" + path) }
        // `{% for r in x.results %}`: `r` is an element of the registered list unless a filter changed the element.
        val registered = baseRegistered?.let { list ->
            val listElement = list.element(emptyList()).let { it.result.option(it.path) }
            val unchanged = listElement != null && listElement.type == element.type && listElement.options?.keys == element.options?.keys
            list.element(path).takeIf { unchanged && it.result.member(it.path) != null }
        }
        return TypedChain(option, doc, registered)
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
        // Molecule groups only where the caret sees Molecule (plan amendment R20, D153).
        val molecules = if (scope.moleculeView.includesMolecule) service.moleculeInventories(scope.root) else emptyList()
        for (molecule in molecules) {
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
        typed.registered?.let { return registeredMembers(it, keys, accept) }
        val option = typed.option
        if (option.type == OptionType.List) return emptyList()
        val options = option.options ?: return emptyList()
        val roleDefault = typed.doc?.let { roleDefaultOf(it) }
        return options.values.withIndex().filter { (_, it) -> accept(it.name) && (keys || isIdentifier(it.name)) }.map { (index, sub) ->
            // Only the role default's value at the path: the spec's sub-option `default:` is never applied (R23, D174).
            // A `no_log` sub-option's value is never shown.
            val default = roleDefault?.takeUnless { sub.noLog }?.let { member(it, sub.name) }?.let(ValueSummary::render)
            val tail = buildString {
                default?.let { append(message("tail.default", JinjaNames.shorten(it))) }
                if (sub.required) append(part(message("part.required")))
            }.ifEmpty { null }
            val doc = typed.doc?.let { CandidateDoc.Variable(it.name, it.path + sub.name) }
                ?: CandidateDoc.Member(owner, sub.name, typeText(sub), sub.description.firstOrNull())
            JinjaCandidate(
                sub.name, Tier.MEMBER, (if (sub.required) 500 else 0) + (options.size - index).coerceAtMost(499),
                typeText(sub), tail, AllIcons.Nodes.Property, doc,
                bold = sub.required && default == null, deprecated = sub.deprecated != null,
            )
        }
    }

    /**
     * The role default's value at the chain [doc] names (`web_db` in `web_db.`): the own role's default first, then that of
     * a role whose spec declares the variable; null through a list element, a secret (also a `no_log` option on the
     * path in any spec that declares the variable), a template or a missing key.
     */
    private fun roleDefaultOf(doc: CandidateDoc.Variable): YValue? {
        if (doc.path.any { it.toIntOrNull() != null } || doc.name.startsWith(VAULT_PREFIX)) return null
        val bindings = VarViews.symbol(scope.project, scope.root, doc.name, scope.moleculeView).specBindings
        val options = scope.roles.flatMap { role -> role.info.argumentSpecs.values.mapNotNull { it.options[doc.name] } } + bindings.map { it.option }
        if (options.any { noLogAlong(it, doc.path) }) return null
        val roleDirs = scope.roles.map { it.info.ref.dir } + bindings.map { it.role.dir }
        val default = roleDirs.distinct().firstNotNullOfOrNull { RoleDefaults.of(scope.project, it, doc.name) } ?: return null
        if (default.isSecret || default.merged) return null
        var value: YValue = default.value
        for (segment in doc.path) value = member(value, segment) ?: return null
        return value
    }

    /** Whether [option] or an option along [path] below it is `no_log`. */
    private fun noLogAlong(option: OptionSpec, path: List<String>): Boolean {
        var current = option
        if (current.noLog) return true
        for (segment in path) {
            current = current.options?.get(segment) ?: return false
            if (current.noLog) return true
        }
        return false
    }

    /** The value of key [name] in [value] when it is a literal mapping; never a `vault_*` key's or a template. */
    private fun member(value: YValue, name: String): YValue? {
        if (name.startsWith(VAULT_PREFIX)) return null
        val entry = (value as? YMap)?.entries?.lastOrNull { it.key.text == name } ?: return null
        return entry.value.takeUnless { it is YVault || it is YScalar && JinjaBearing.hasTemplateMarkers(it.text) }
    }

    /**
     * The documented keys below a registered chain (module returns first, the common keys last), typed, with where they
     * come from as the tail (`command`, `common`, `until` …) and the return value's card; nothing for a list.
     */
    private fun registeredMembers(chain: RegisteredChain, keys: Boolean, accept: (String) -> Boolean): List<JinjaCandidate> {
        val member = chain.result.member(chain.path) ?: return emptyList()
        if (member.type == OptionType.List) return emptyList()
        val members = member.members ?: return emptyList()
        return members.values.withIndex().filter { (_, it) -> accept(it.name) && (keys || isIdentifier(it.name)) }.map { (index, sub) ->
            ProgressManager.checkCanceled()
            val path = chain.path + sub.name
            JinjaCandidate(
                sub.name, Tier.MEMBER, (members.size - index).coerceAtMost(999), typeText(sub.option),
                part(RegisteredDocs.tail(sub)), AllIcons.Nodes.Property,
                CandidateDoc.Registered(chain.result, path, RegisteredSites.display(chain.result.name, path)),
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
        private const val VAULT_PREFIX = "vault_"
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
