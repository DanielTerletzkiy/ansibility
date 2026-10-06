package de.terletzkiy.ansibility.semantics.inventory

import de.terletzkiy.ansibility.semantics.CoreVersion
import de.terletzkiy.ansibility.semantics.yaml.SourceRange
import de.terletzkiy.ansibility.semantics.yaml.YMap
import de.terletzkiy.ansibility.semantics.yaml.YValue

/** File access for [InventorySources]: the [InventoryTree] plus the content of files. Nothing is ever executed. */
interface InventoryFileSystem<F> : InventoryTree<F> {
    /** True when [file] exists (a file or a directory) and can be read. */
    fun exists(file: F): Boolean

    /** The text of the file [file], or null when it cannot be read as text. */
    fun text(file: F): String?

    /** [file] loaded as YAML (asked only for files the `auto` and `yaml` plugins try). */
    fun yaml(file: F): YamlLoad

    /** True when the file has an execute permission (`os.access(path, os.X_OK)`); false when unknown. */
    fun isExecutable(file: F): Boolean = false
}

/** The result of loading a file as YAML. */
sealed interface YamlLoad {
    /** The loaded document; null (or `YEmpty`) for an empty file. */
    data class Loaded(val document: YValue?) : YamlLoad

    /** The file is not YAML; [offset] is where the loader stopped, when known. */
    data class Failed(val message: String, val offset: Int? = null) : YamlLoad
}

/** One entry of the inventory source list (`-i` order, or the entries of `[defaults] inventory`). */
sealed interface SourceSpec<out F> {
    /** A file or directory, possibly missing (its directory's `group_vars` still load). */
    data class Path<F>(val file: F) : SourceSpec<F>

    /** A comma-separated host list (`-i web1,web2`; only the command line takes one). */
    data class HostList(val text: String) : SourceSpec<Nothing>
}

/** Which inventory plugin a file is for. */
enum class InventoryFileFormat {
    YAML,
    INI,

    /** A `-i` host list (the `host_list` plugin). */
    HOST_LIST,

    /** A YAML file with a root `plugin:` key (the `auto` plugin runs that plugin). */
    PLUGIN_CONFIG,

    /** An executable file with a `#!` line (the `script` plugin runs it). */
    SCRIPT,

    /** A `.toml` file (the `toml` plugin); not modelled. */
    TOML,
}

/** What became of one file. */
enum class FileStatus {
    /** A static plugin parsed it into the graph. */
    PARSED,

    /** Every plugin that tried it failed; what it added before the error stays in the graph (as in ansible-core). */
    FAILED,

    /** A script or a plugin configuration: never run, so its hosts are not evaluated (assumed to parse). */
    DYNAMIC,

    /** A vault-encrypted file, which ansible-core decrypts before parsing: not evaluated (assumed to parse). */
    ENCRYPTED,

    /** A TOML inventory: not modelled (assumed to parse). */
    UNSUPPORTED,

    /** No plugin accepted it (unreadable). */
    UNPARSED,
}

/** One plugin's failure on a file, as ansible-core reports it; [line] is 1-based. */
data class PluginFailure(val plugin: String, val message: String, val range: SourceRange?, val line: Int?)

/**
 * One file the sources read, or one host list. [index] is the [InventoryLocation.sourceIndex] of everything it
 * defines; [sourceIndex] is the position of the [SourceSpec] it came from (a directory source reads many files).
 * [file] is null for a host list. [format] is the plugin that parsed it, or for a failed file the plugin its
 * extension (or its YAML shape) suggests.
 */
data class ParsedFile<F>(
    val index: Int,
    val file: F?,
    val sourceIndex: Int,
    val format: InventoryFileFormat?,
    val status: FileStatus,
    val failures: List<PluginFailure>,
)

/** How a [SourceSpec] was read. */
enum class SourceKind { FILE, DIRECTORY, MISSING, HOST_LIST }

/**
 * One source: [parsed] is `parse_source`'s result (a directory parsed when any of its files did); [files] are the
 * file itself or every file of a directory in read order; [skipped] lists the directory entries the walk skipped.
 */
data class SourceOutcome<F>(
    val index: Int,
    val spec: SourceSpec<F>,
    val kind: SourceKind,
    val parsed: Boolean,
    val files: List<ParsedFile<F>>,
    val skipped: List<InventoryDirectoryWalk.Skipped<F>>,
)

/**
 * The inventory several sources make. [parsed] is false when no source parsed: ansible-core then skips
 * `reconcile_inventory()` and the graph shows only what `all` reaches. [files] are indexed by
 * [InventoryLocation.sourceIndex].
 */
class InventoryParse<F>(
    val graph: InventoryGraph,
    val sources: List<SourceOutcome<F>>,
    val files: List<ParsedFile<F>>,
    val parsed: Boolean,
) {
    /** The file a location of [graph] is in. */
    fun fileOf(location: InventoryLocation): ParsedFile<F>? = files.getOrNull(location.sourceIndex)

    /** True when some file is a script or a plugin configuration whose hosts are missing from [graph]. */
    val hasDynamicSources: Boolean get() = files.any { it.status == FileStatus.DYNAMIC }
}

/**
 * The settings that steer [InventorySources]. [enabledPlugins] is `[inventory] enable_plugins` (FQCNs allowed);
 * [yamlExtensions] is `yaml_valid_extensions`; [walk] holds the directory ignore lists. [core] is the target
 * ansible-core (null: the newest measured behaviour).
 */
class InventoryParseOptions(
    val core: CoreVersion? = null,
    val enabledPlugins: List<String> = DEFAULT_ENABLED_PLUGINS,
    val yamlExtensions: List<String> = DEFAULT_YAML_EXTENSIONS,
    val walk: InventoryDirectoryWalk.Options = InventoryDirectoryWalk.Options(core),
) {
    companion object {
        /** `INVENTORY_ENABLED`'s default, in the order the plugins are tried. */
        val DEFAULT_ENABLED_PLUGINS = listOf("host_list", "script", "auto", "yaml", "ini", "toml")

        /** `YAML_FILENAME_EXTENSIONS`, the `yaml` plugin's default `yaml_extensions`. */
        val DEFAULT_YAML_EXTENSIONS = listOf(".yaml", ".yml", ".json")
    }
}

/**
 * Port of `InventoryManager.parse_sources`/`parse_source`: every source in order into one inventory.
 *
 * - A directory is walked as [InventoryDirectoryWalk] says; every file is a source of its own.
 * - Each file tries the enabled plugins in order; the first whose `verify_file` accepts it and whose `parse`
 *   succeeds wins, a failed attempt is silent when a later plugin succeeds, and a plugin that fails half-way
 *   keeps what it added (once any source parses, `reconcile_inventory()` adopts those groups too).
 * - Static formats are parsed ([YamlInventoryParser], [IniInventoryParser], host lists). Scripts and plugin
 *   configurations are recognised and listed as [FileStatus.DYNAMIC], never executed; vault-encrypted and TOML
 *   files are not evaluated either.
 * - Later sources add to earlier ones: hosts keep the order of their first appearance (and the port of their first
 *   definition), variables set later come later in the same group or host, so they win.
 */
object InventorySources {
    /** Parses [sources] in order. */
    fun <F> parseAll(
        sources: List<SourceSpec<F>>,
        fs: InventoryFileSystem<F>,
        options: InventoryParseOptions = InventoryParseOptions(),
    ): InventoryParse<F> = Run(fs, options).run(sources)

    /** Parses the files or directories [sources] in order (no host lists). */
    fun <F> parseFiles(
        sources: List<F>,
        fs: InventoryFileSystem<F>,
        options: InventoryParseOptions = InventoryParseOptions(),
    ): InventoryParse<F> = parseAll(sources.map { SourceSpec.Path(it) }, fs, options)

    private const val VAULT_HEADER = "\$ANSIBLE_VAULT"
    private val AUTO_EXTENSIONS = listOf(".yml", ".yaml")

    /** A plugin name without the `ansible.builtin.`/`ansible.legacy.` prefix. */
    private fun shortName(plugin: String): String =
        if (plugin.startsWith("ansible.builtin.") || plugin.startsWith("ansible.legacy.")) plugin.substringAfterLast('.') else plugin

    private class Run<F>(private val fs: InventoryFileSystem<F>, private val options: InventoryParseOptions) {
        private val builder = InventoryBuilder(failFast = true)
        private val files = ArrayList<ParsedFile<F>>()
        private val plugins = options.enabledPlugins.map(::shortName)
        private val modernPlugins = options.core == null || options.core >= InventoryDirectoryWalk.INI_READ_SINCE

        fun run(sources: List<SourceSpec<F>>): InventoryParse<F> {
            val outcomes = sources.mapIndexed { index, spec -> source(index, spec) }
            val parsed = outcomes.any { it.parsed }
            val graph = if (parsed) {
                builder.reconcile()
                builder.build()
            } else {
                builder.buildReachable()
            }
            return InventoryParse(graph, outcomes, files.toList(), parsed)
        }

        private fun source(index: Int, spec: SourceSpec<F>): SourceOutcome<F> = when (spec) {
            is SourceSpec.HostList -> {
                val file = hostList(spec.text, index)
                SourceOutcome(index, spec, SourceKind.HOST_LIST, file.status == FileStatus.PARSED, listOf(file), emptyList())
            }
            is SourceSpec.Path -> when {
                !fs.exists(spec.file) -> SourceOutcome(index, spec, SourceKind.MISSING, false, emptyList(), emptyList())
                fs.isDirectory(spec.file) -> {
                    val read = ArrayList<ParsedFile<F>>()
                    val skipped = ArrayList<InventoryDirectoryWalk.Skipped<F>>()
                    val parsed = directory(spec.file, index, read, skipped, 0)
                    SourceOutcome(index, spec, SourceKind.DIRECTORY, parsed, read, skipped)
                }
                else -> {
                    val file = file(spec.file, index)
                    SourceOutcome(index, spec, SourceKind.FILE, file.status.counts, listOf(file), emptyList())
                }
            }
        }

        /** `parse_source` on a directory: its entries in sorted order, subdirectories recursed in place. */
        private fun directory(
            dir: F,
            sourceIndex: Int,
            read: MutableList<ParsedFile<F>>,
            skipped: MutableList<InventoryDirectoryWalk.Skipped<F>>,
            depth: Int,
        ): Boolean {
            var parsed = false
            for (entry in InventoryDirectoryWalk.entries(dir, fs, options.walk, skipped)) {
                val thisOne = if (fs.isDirectory(entry)) {
                    depth < InventoryDirectoryWalk.MAX_DEPTH && directory(entry, sourceIndex, read, skipped, depth + 1)
                } else {
                    file(entry, sourceIndex).also { read += it }.status.counts
                }
                parsed = parsed || thisOne
            }
            return parsed
        }

        /** The host_list plugin: `host[:port]` entries separated by commas, added to `ungrouped`. */
        private fun hostList(text: String, sourceIndex: Int): ParsedFile<F> {
            val index = files.size
            builder.source = index
            if (!plugins.contains("host_list") || ',' !in text) {
                return ParsedFile<F>(index, null, sourceIndex, InventoryFileFormat.HOST_LIST, FileStatus.UNPARSED, emptyList())
                    .also { files += it }
            }
            var start = 0
            for (part in text.split(',')) {
                val end = start + part.length
                val h = part.trim()
                if (h.isNotEmpty()) {
                    val parsed = HostAddress.parse(h, allowRanges = false)
                    val host = parsed?.host ?: h
                    val from = start + part.indexOf(h)
                    if (host !in builder.hosts) {
                        builder.addHost(host, InventoryGraph.UNGROUPED, parsed?.port, SourceRange(from, from + h.length))
                    }
                }
                start = end + 1
            }
            return ParsedFile<F>(index, null, sourceIndex, InventoryFileFormat.HOST_LIST, FileStatus.PARSED, emptyList())
                .also { files += it }
        }

        /** `parse_source` on a file: the enabled plugins in order. */
        private fun file(file: F, sourceIndex: Int): ParsedFile<F> {
            val index = files.size
            files += ParsedFile(index, file, sourceIndex, null, FileStatus.UNPARSED, emptyList()) // reserves the index
            builder.source = index
            val result = Attempts(file, index).run()
            files[index] = ParsedFile(index, file, sourceIndex, result.format, result.status, result.failures)
            return files[index]
        }

        private class Result(val format: InventoryFileFormat?, val status: FileStatus, val failures: List<PluginFailure>)

        /** The plugin attempts on one file, with the problems of each failed attempt held back. */
        private inner class Attempts(private val file: F, private val index: Int) {
            private val name = fs.name(file)
            private val extension = Py.extension(name)
            private val text: String? = fs.text(file)
            private var loaded: YamlLoad? = null
            private val failures = ArrayList<PluginFailure>()
            private val heldBack = LinkedHashMap<String, List<InventoryProblem>>()

            private fun yaml(): YamlLoad = loaded ?: fs.yaml(file).also { loaded = it }

            private fun lineOf(offset: Int?): Int? = offset?.let { o -> text?.let { IniInventoryParser.lineOf(it, o) } }

            private fun fail(plugin: String, message: String, range: SourceRange?, problems: List<InventoryProblem>) {
                failures += PluginFailure(plugin, message, range, lineOf(range?.start))
                heldBack[plugin] = problems
            }

            fun run(): Result {
                if (text == null) return Result(null, FileStatus.UNPARSED, emptyList())
                for (plugin in plugins) {
                    // ansible-core's loader decrypts a vault-encrypted file before any plugin reads its content.
                    if (plugin != "host_list" && plugin != "script" && text.startsWith(VAULT_HEADER)) {
                        return Result(naturalFormat(), FileStatus.ENCRYPTED, failures)
                    }
                    val outcome = when (plugin) {
                        "host_list" -> null // a path that exists is never a host list
                        "script" -> script()
                        "auto" -> auto()
                        "yaml" -> static(plugin, InventoryFileFormat.YAML)
                        "ini" -> static(plugin, InventoryFileFormat.INI)
                        "toml" -> if (extension == ".toml") Result(InventoryFileFormat.TOML, FileStatus.UNSUPPORTED, failures) else null
                        else -> pluginConfig()
                    }
                    if (outcome != null) return outcome
                }
                if (failures.isEmpty()) return Result(null, FileStatus.UNPARSED, failures)
                val format = naturalFormat()
                // ansible-core prints every attempt's failure; the IDE keeps the one of the file's own format.
                val shown = heldBack[format?.name?.lowercase()] ?: heldBack.values.last()
                builder.problems += shown
                return Result(format, FileStatus.FAILED, failures)
            }

            /** The script plugin accepts executable files (before 2.19 also files starting with `#!`) and runs them. */
            private fun script(): Result? {
                val shebang = text!!.startsWith("#!")
                val executable = fs.isExecutable(file)
                if (!executable && !(shebang && !modernPlugins)) return null
                if (executable && shebang) return Result(InventoryFileFormat.SCRIPT, FileStatus.DYNAMIC, failures)
                val reason = if (executable) "[Errno 8] Exec format error" else "[Errno 13] Permission denied"
                fail("script", "problem running $name ($reason)", null, emptyList())
                return null
            }

            /** The auto plugin accepts `.yml`/`.yaml` files and runs the plugin their root `plugin:` key names. */
            private fun auto(): Result? {
                if (AUTO_EXTENSIONS.none { name.endsWith(it) }) return null
                if (hasPluginKey()) return Result(InventoryFileFormat.PLUGIN_CONFIG, FileStatus.DYNAMIC, failures)
                when (val load = yaml()) {
                    is YamlLoad.Failed -> fail("auto", load.message, load.offset?.let { SourceRange(it, it) }, emptyList())
                    is YamlLoad.Loaded -> fail(
                        "auto",
                        "no root 'plugin' key found, '$name' is not a valid YAML inventory plugin config file",
                        null,
                        emptyList(),
                    )
                }
                return null
            }

            /** Another enabled plugin (a collection's or a builtin dynamic one): configured by a YAML `plugin:` file. */
            private fun pluginConfig(): Result? =
                if (AUTO_EXTENSIONS.any { name.endsWith(it) } && hasPluginKey()) {
                    Result(InventoryFileFormat.PLUGIN_CONFIG, FileStatus.DYNAMIC, failures)
                } else {
                    null
                }

            private fun hasPluginKey(): Boolean {
                val document = (yaml() as? YamlLoad.Loaded)?.document as? YMap ?: return false
                return Py.isTruthy(Py.dict(document)["plugin"]?.value)
            }

            /** The yaml and ini plugins: `verify_file`, then a fail-fast parse into the shared builder. */
            private fun static(plugin: String, format: InventoryFileFormat): Result? {
                val accepts = when (format) {
                    InventoryFileFormat.YAML -> extension.isEmpty() || extension in options.yamlExtensions
                    else -> !(modernPlugins && extension == ".toml") // 2.19 added the TOML guard to ini.verify_file
                }
                if (!accepts) return null
                if (format == InventoryFileFormat.YAML) {
                    val load = yaml()
                    if (load is YamlLoad.Failed) {
                        val range = load.offset?.let { SourceRange(it, it) }
                        val problem = InventoryProblem(ProblemSeverity.ERROR, load.message, InventoryLocation(index, range))
                        fail(plugin, load.message, range, listOf(problem))
                        return null
                    }
                }
                val mark = builder.problems.size
                return try {
                    when (format) {
                        InventoryFileFormat.YAML -> YamlInventoryParser.parseInto(builder, (yaml() as YamlLoad.Loaded).document, index)
                        else -> IniInventoryParser.parseInto(builder, text!!, index, options.core)
                    }
                    Result(format, FileStatus.PARSED, failures)
                } catch (e: InventoryBuilder.SourceFailure) {
                    val added = builder.problems.subList(mark, builder.problems.size)
                    val problems = added.toList()
                    added.clear()
                    fail(plugin, e.problem.message, e.problem.location.range, problems)
                    null
                }
            }

            /** The format a failed or encrypted file is meant to have: by extension, or by its YAML shape. */
            private fun naturalFormat(): InventoryFileFormat? = when {
                extension == ".toml" -> InventoryFileFormat.TOML
                extension in options.yamlExtensions -> InventoryFileFormat.YAML
                extension.isNotEmpty() -> InventoryFileFormat.INI
                text?.startsWith(VAULT_HEADER) == true -> null
                (loaded as? YamlLoad.Loaded)?.document is YMap -> InventoryFileFormat.YAML
                else -> InventoryFileFormat.INI
            }
        }
    }

    /** Whether a file in this state counts as parsed for `parse_sources` (dynamic and unmodelled ones are assumed to). */
    private val FileStatus.counts: Boolean
        get() = this != FileStatus.FAILED && this != FileStatus.UNPARSED
}
