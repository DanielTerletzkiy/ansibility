package de.terletzkiy.ansibility.index

import de.terletzkiy.ansibility.semantics.yaml.Resolved
import de.terletzkiy.ansibility.semantics.yaml.YMap
import de.terletzkiy.ansibility.semantics.yaml.YScalar
import de.terletzkiy.ansibility.semantics.yaml.YSeq
import de.terletzkiy.ansibility.semantics.yaml.YValue

/**
 * The one shared answer to "does Ansible template this YAML scalar?" (plan A.7), used by the `ansible.var.use`
 * indexer, the Jinja-in-YAML injector and the text-level Jinja locator alike.
 *
 * It depends only on facts local to the file's path, the scalar's key path inside the document and its explicit tag,
 * so it is safe inside indexers. Exclusions that need the query-time file kind (for example files outside any Ansible
 * root) are applied by the consumers.
 */
object JinjaBearing {
    /** Keys whose whole value is a bare Jinja expression in task, block, role-entry and playbook-import context. */
    val IMPLICIT_EXPRESSION_KEYS: Set<String> = setOf("when", "changed_when", "failed_when", "until")

    /** Tags whose scalars are never templated: encrypted vault payloads and `!unsafe` strings. */
    val NON_TEMPLATED_TAGS: Set<String> = setOf("!vault", "!vault-encrypted", "!unsafe")

    /** Top-level keys of a role's `meta/main.yml` that ansible-core never templates. */
    private val STATIC_META_KEYS: Set<String> = setOf("argument_specs", "galaxy_info", "allow_duplicates")

    /** File names that are tool configuration, not Ansible content, wherever they are. */
    private val TOOL_CONFIG_NAMES: Set<String> = setOf(
        "requirements.yml", "requirements.yaml", "ansible-lint.yml", "ansible-lint.yaml", ".ansible-lint",
        ".ansible-lint.yml", ".ansible-lint.yaml", ".yamllint", ".yamllint.yml", ".yamllint.yaml",
    )

    /**
     * True when Ansible would template the scalar at [keyPath] (mapping keys and sequence indices from the document
     * root, see `YamlPaths.keyPath`) carrying the explicit [tag] (normalised, e.g. `!vault`) in a file with
     * [pathFacts]. Never Jinja:
     * - `!vault` and `!unsafe` scalars;
     * - anything in `meta/argument_specs.yml`, and the `argument_specs`/`galaxy_info` parts of `meta/main.yml`: the
     *   spec is documentation and validation data (descriptions quoting `{{ x }}` literally, spec defaults, which are
     *   never applied);
     * - `molecule.yml` outside `provisioner.inventory` (molecule itself only expands `${MOLECULE_RUN_ID:-local}`-style
     *   environment variables there; only the inventory it writes is templated by Ansible);
     * - files below `roles/<role>/files/` (copied verbatim) and tool configuration (`requirements.yml`,
     *   ansible-lint and yamllint files);
     * - template files, whose whole text is Jinja rather than individual scalars.
     */
    fun isJinjaBearingScalar(pathFacts: PathFacts, keyPath: List<String>, tag: String?): Boolean {
        if (tag != null && tag in NON_TEMPLATED_TAGS) return false
        if (pathFacts.fileName in TOOL_CONFIG_NAMES || isComposeFile(pathFacts)) return false
        return when (pathFacts.hint) {
            PathHint.ARGUMENT_SPECS, PathHint.FILES, PathHint.TEMPLATE -> false
            PathHint.ROLE_META -> keyPath.firstOrNull() !in STATIC_META_KEYS
            PathHint.MOLECULE_CONFIG -> keyPath.size > 2 && keyPath[0] == "provisioner" && keyPath[1] == "inventory"
            else -> true
        }
    }

    /** True when [text] contains a Jinja expression or statement opener (`{{` or `{%`). */
    fun hasTemplateMarkers(text: CharSequence): Boolean = text.contains("{{") || text.contains("{%")

    /** `assert` under all its names; its `that` option holds bare expressions. */
    private val ASSERT: Set<String> = setOf("assert", "ansible.builtin.assert", "ansible.legacy.assert")

    /** `debug` under all its names; its `var` option holds a bare expression. */
    private val DEBUG: Set<String> = setOf("debug", "ansible.builtin.debug", "ansible.legacy.debug")

    /**
     * The start offsets of the scalars Ansible evaluates as bare Jinja expressions in a task list or playbook
     * [document] (the value loaded by `PsiYValueAdapter.documentValue`, so offsets are file offsets of the scalars):
     * `when`, `changed_when`, `failed_when` and `until` of tasks and handlers (scalar or list items), `when` of blocks,
     * role entries and playbook imports, `assert`'s `that` and `debug`'s `var`. Only string scalars count; booleans
     * such as `changed_when: false` are not templated. Empty for vars files and every other mapping document.
     */
    fun implicitExpressionOffsets(document: YValue?, pathFacts: PathFacts): Set<Int> {
        if (document !is YSeq || !isJinjaBearingScalar(pathFacts, emptyList(), null)) return emptySet()
        val starts = HashSet<Int>()
        fun add(value: YValue?) {
            when (value) {
                is YScalar -> if (value.resolved is Resolved.Str) value.range?.let { starts += it.start }
                is YSeq -> value.items.forEach { if (it is YScalar) add(it) }
                else -> Unit
            }
        }
        TaskWalker(
            object : TaskVisitor {
                override fun playbookImport(entry: YMap, key: YScalar, target: YValue) = add(entry["when"])

                override fun roleEntry(entry: YValue, play: YMap) {
                    (entry as? YMap)?.let { add(it["when"]) }
                }

                override fun block(block: YMap, handlers: Boolean, play: YMap?) = add(block["when"])

                override fun task(task: YMap, handlers: Boolean, play: YMap?) {
                    IMPLICIT_EXPRESSION_KEYS.forEach { add(task[it]) }
                    val action = TaskKeywords.action(task) ?: return
                    val args = action.argsMap(task) ?: return
                    when (action.name) {
                        in ASSERT -> add(args["that"])
                        in DEBUG -> add(args["var"])
                    }
                }
            },
            handlerFile = pathFacts.hint == PathHint.HANDLERS,
        ).walk(document)
        return starts
    }

    /** Docker compose files use `${VAR}` interpolation, never Jinja (unless they are `.j2` templates). */
    private fun isComposeFile(pathFacts: PathFacts): Boolean {
        val name = pathFacts.fileName
        return pathFacts.hint != PathHint.TEMPLATE &&
            (name.startsWith("docker-compose") || name.startsWith("compose.")) && pathFacts.isYamlName
    }
}
