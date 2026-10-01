package de.terletzkiy.ansibility.model.task

import de.terletzkiy.ansibility.semantics.schema.DocSnapshot
import de.terletzkiy.ansibility.semantics.schema.PluginKind
import de.terletzkiy.ansibility.semantics.schema.Routing

/** The object a playbook keyword belongs to (the `applies_to` classes of ansible-core's keyword docs). */
enum class KeywordOwner(val docName: String) {
    PLAY("Play"),
    ROLE("Role"),
    BLOCK("Block"),
    TASK("Task"),
    HANDLER("Handler"),
    PLAYBOOK_INCLUDE("PlaybookInclude"),
    LOOP_CONTROL("LoopControl"),
}

/**
 * The facts of one ansible-core line that the task model needs to tell keywords from module keys (plan A.5
 * "Task model"): the keyword set per owner, module option names, free-form modules, lookup plugin names and
 * plugin routing. Extracted from a bundled [DocSnapshot] so the full documentation does not stay in memory.
 *
 * Keyword sets come from `Play/Block/Task/Handler/RoleInclude/PlaybookInclude/LoopControl.fattributes` (the
 * snapshot adds `listen`, `local_action` and `with_<lookup>` by hand). A `with_<name>` key is never in a set;
 * [isLoopKey] decides it.
 */
class TaskSyntax(
    /** The ansible-core version the facts come from, e.g. `2.18.8`. */
    val coreVersion: String,
    private val keywords: Map<KeywordOwner, Set<String>>,
    /** Canonical module FQCN → option names and aliases. */
    private val moduleOptions: Map<String, Set<String>>,
    /** Canonical FQCNs of modules that take a free-form string (`command`, `shell`, `raw`, `script`, `meta`). */
    private val freeFormModules: Set<String>,
    /** Canonical FQCNs of lookup plugins. */
    private val lookups: Set<String>,
    private val routing: Routing,
) {
    /** The keywords valid on [owner], without the `with_<lookup>` placeholder. */
    fun keywords(owner: KeywordOwner): Set<String> = keywords[owner].orEmpty()

    fun isKeyword(owner: KeywordOwner, key: String): Boolean = key in keywords(owner)

    /**
     * The canonical FQCN [name] routes to (`ansible.builtin.systemd` → `ansible.builtin.systemd_service`,
     * `community.mysql.mysql_user` → `ansible.mysql.mysql_user`, `copy` → `ansible.builtin.copy`), whether or
     * not the snapshot documents that module.
     */
    fun canonicalModule(name: String): String = routing.resolve(name, PluginKind.MODULE).canonical

    /** Whether [name] is a module or action the snapshot knows (documented, or routed through `plugin_routing`). */
    fun isKnownModule(name: String): Boolean {
        val resolution = routing.resolve(name, PluginKind.MODULE)
        return resolution.canonical in moduleOptions || resolution.isRedirected || resolution.tombstone != null
    }

    /** Whether module [name] documents option [option] (or an alias of that name); null when the module is unknown. */
    fun moduleHasOption(name: String, option: String): Boolean? = moduleOptions[canonicalModule(name)]?.let { option in it }

    /** Whether [name] (canonical or not) takes a free-form string, like `command` or `shell`. */
    fun isFreeFormModule(name: String): Boolean = canonicalModule(name) in freeFormModules

    /** Whether [name] (`items`, `ansible.builtin.fileglob`, `community.general.dig`) is a lookup plugin. */
    fun isLookup(name: String): Boolean = routing.resolve(name, PluginKind.LOOKUP).canonical in lookups

    /**
     * Whether the task-level key [key] is a `with_<lookup>` loop. ansible-core treats `with_X` as a loop when X
     * is a lookup plugin; a `with_X` that is no known lookup is still read as a loop (custom lookup plugins are
     * not in the snapshot) unless the task's [module] documents `with_X` as an option, as
     * `community.general.jenkins_plugin` does with `with_dependencies`.
     */
    fun isLoopKey(key: String, module: String?): Boolean {
        if (!key.startsWith(WITH_PREFIX) || key.length <= WITH_PREFIX.length) return false
        if (isLookup(key.removePrefix(WITH_PREFIX))) return true
        return module == null || moduleHasOption(module, key) != true
    }

    companion object {
        const val WITH_PREFIX: String = "with_"

        /** Builds the facts from a loaded snapshot. */
        fun fromSnapshot(snapshot: DocSnapshot): TaskSyntax {
            val byOwner = KeywordOwner.entries.associateWith { owner ->
                snapshot.keywords.values
                    .filter { owner.docName in it.appliesTo && it.name != DocSnapshot.WITH_LOOKUP }
                    .mapTo(LinkedHashSet()) { it.name }
            }
            val options = snapshot.modules.mapValues { (_, doc) ->
                doc.options.values.flatMapTo(HashSet()) { listOf(it.name) + it.aliases }
            }
            val freeForm = snapshot.modules.values.filter { it.freeForm != null }.mapTo(HashSet()) { it.fqcn }
            return TaskSyntax(
                coreVersion = snapshot.core,
                keywords = byOwner,
                moduleOptions = options,
                freeFormModules = freeForm,
                lookups = snapshot.lookups.keys.toHashSet(),
                routing = snapshot.routing,
            )
        }

        /**
         * The ansible-core 2.18 keyword table without module, lookup or routing facts. Only used when the bundled
         * snapshot cannot be read (a broken build), so that tasks still split into keywords and a module key.
         */
        fun fallback(): TaskSyntax {
            val all = KeywordOwner.entries.toSet() - KeywordOwner.LOOP_CONTROL
            val keywords = HashMap<KeywordOwner, MutableSet<String>>()
            fun add(owners: Set<KeywordOwner>, vararg names: String) = owners.forEach { keywords.getOrPut(it, ::LinkedHashSet) += names }
            val common = setOf(KeywordOwner.PLAY, KeywordOwner.ROLE, KeywordOwner.BLOCK, KeywordOwner.TASK, KeywordOwner.HANDLER)
            add(
                all, "any_errors_fatal", "become", "become_exe", "become_flags", "become_method", "become_user",
                "check_mode", "connection", "debugger", "diff", "environment", "ignore_errors", "ignore_unreachable",
                "module_defaults", "name", "no_log", "port", "remote_user", "run_once", "tags", "throttle", "timeout", "vars",
            )
            add(common, "collections")
            add(common - KeywordOwner.PLAY, "delegate_facts", "delegate_to")
            add(common - KeywordOwner.PLAY + KeywordOwner.PLAYBOOK_INCLUDE, "when")
            add(setOf(KeywordOwner.BLOCK, KeywordOwner.TASK, KeywordOwner.HANDLER), "notify")
            add(
                setOf(KeywordOwner.TASK, KeywordOwner.HANDLER), "action", "args", "async", "changed_when", "delay",
                "failed_when", "local_action", "loop", "loop_control", "poll", "register", "retries", "until",
            )
            add(setOf(KeywordOwner.BLOCK), "always", "block", "rescue")
            add(setOf(KeywordOwner.HANDLER), "listen")
            add(setOf(KeywordOwner.ROLE), "role")
            add(setOf(KeywordOwner.PLAYBOOK_INCLUDE), "import_playbook")
            add(
                setOf(KeywordOwner.PLAY), "fact_path", "force_handlers", "gather_facts", "gather_subset", "gather_timeout",
                "handlers", "hosts", "max_fail_percentage", "order", "post_tasks", "pre_tasks", "roles", "serial",
                "strategy", "tasks", "vars_files", "vars_prompt",
            )
            add(
                setOf(KeywordOwner.LOOP_CONTROL), "break_when", "extended", "extended_allitems", "index_var", "label",
                "loop_var", "pause",
            )
            return TaskSyntax("2.18.8", keywords, emptyMap(), emptySet(), emptySet(), Routing(emptyMap()))
        }
    }
}
