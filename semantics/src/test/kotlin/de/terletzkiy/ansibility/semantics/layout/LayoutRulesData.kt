package de.terletzkiy.ansibility.semantics.layout

import de.terletzkiy.ansibility.semantics.CoreVersion
import de.terletzkiy.ansibility.semantics.coerce.PathEnvironment
import de.terletzkiy.ansibility.semantics.json.Json
import java.io.File

/**
 * The measured records under `src/test/resources/layout-rules/` (see its README): `ansible-config dump` and
 * `ansible-inventory --list` runs of ansible-core 2.18.8 and 2.21.4 on synthetic cfg files.
 *
 * Paths in the records are normalised to `{project}`, `{cwd}` (a neutral empty directory) and `{home}` (an empty
 * home). The tests put the project at [PROJECT] and the home at [HOME]; `{cwd}` stands for the cfg's directory,
 * which is where the plugin resolves `{{CWD}}` (D53).
 */
internal object LayoutRulesData {
    val VERSIONS: List<String> = listOf("2.18.8", "2.21.4")

    const val PROJECT = "/work/project"
    const val HOME = "/work/home"

    val root: File = File(requireNotNull(LayoutRulesData::class.java.getResource("/layout-rules")) { "layout-rules missing" }.toURI())

    fun case(name: String): Case = Case(File(root, name))

    class Case(val dir: File) {
        val name: String get() = dir.name

        @Suppress("UNCHECKED_CAST")
        val runs: List<Map<String, Any?>> = (Json.parseObject(File(dir, "oracle.json").readText())["runs"] as List<Map<String, Any?>>)

        /** The records of [version] for the runs of [tool], in oracle order. */
        fun records(version: String, tool: String = "ansible-config"): List<Record> = runs
            .filter { (it["tool"] ?: "ansible-inventory") == tool }
            .map { record(it["name"] as String, version) }

        fun record(run: String, version: String): Record =
            Record(this, run, version, Json.parseObject(File(dir, "expected/$version/$run.json").readText()))
    }

    /** One run's record, in the research format (`stdout`) or this unit's reduced format (`settings`). */
    class Record(val case: Case, val run: String, val version: String, private val json: Map<String, Any?>) {
        val core: CoreVersion = requireNotNull(CoreVersion.parse(version))
        val rc: Int get() = (json["rc"] as Number).toInt()
        val stderr: String get() = json["stderr"] as String

        /** The cfg file relative to the case directory (`project/cfg/list.cfg`), or null for a run without one. */
        val cfg: String? get() = json["cfg"] as String?

        @Suppress("UNCHECKED_CAST")
        val env: Map<String, String> get() = (json["env"] as Map<String, String>?).orEmpty()

        val stdout: Any? get() = json["stdout"]

        /** The cfg file's path as the records name it (`{project}/cfg/list.cfg`). */
        val cfgPlaceholder: String? get() = cfg?.let { "{project}" + it.removePrefix("project") }

        /** The absolute directory of the cfg file under [PROJECT]. */
        val cfgDir: String get() = (PROJECT + requireNotNull(cfg).removePrefix("project")).substringBeforeLast('/')

        fun cfgFile(): File = File(case.dir, requireNotNull(cfg))

        /** The cfg text exactly as ansible-core decodes it (UTF-8, a byte order mark kept). */
        fun cfgText(): String = String(cfgFile().readBytes(), Charsets.UTF_8)

        /** A setting's value and origin from the dump, or null when the dump does not list it. */
        @Suppress("UNCHECKED_CAST")
        fun setting(name: String): Setting? {
            (json["settings"] as Map<String, Map<String, Any?>>?)?.let { settings ->
                return settings[name]?.let { Setting(it["value"], it["origin"] as String) }
            }
            val entries = json["stdout"] as? List<Map<String, Any?>> ?: return null
            return entries.firstOrNull { it["name"] == name }?.let { Setting(it["value"], it["origin"] as String) }
        }

        /** True when [setting]'s origin is this run's cfg file. */
        fun fromCfg(setting: Setting): Boolean = setting.origin == cfgPlaceholder

        /** [text] with the record placeholders replaced: `{cwd}` is the cfg's directory. */
        fun absolute(text: String): String =
            text.replace("{project}", PROJECT).replace("{home}", HOME).replace("{cwd}", cfgDir)

        /** The environment of the run (placeholders replaced) plus `HOME`, for the follow switch. */
        fun followEnvironment(): PathEnvironment =
            PathEnvironment(env.mapValues { absolute(it.value) } + ("HOME" to HOME), home = HOME)

        override fun toString(): String = "${case.name}/$version/$run"
    }

    private val LINE = Regex("""\[line\s+(\d+)]|line: (\d+)""")

    /** The [CfgErrorKind] behind an rc-5 message of ansible-core. */
    fun reportedKind(stderr: String): CfgErrorKind = when {
        "Unsupported configuration file extension" in stderr -> CfgErrorKind.UNSUPPORTED_EXTENSION
        "Unsupported configuration file type" in stderr -> CfgErrorKind.UNSUPPORTED_TYPE
        "already exists" in stderr && "option '" in stderr -> CfgErrorKind.DUPLICATE_OPTION
        "already exists" in stderr && "section '" in stderr -> CfgErrorKind.DUPLICATE_SECTION
        "File contains no section headers" in stderr -> CfgErrorKind.MISSING_SECTION_HEADER
        "Source contains parsing errors" in stderr -> CfgErrorKind.PARSING_ERROR
        else -> error("not a configuration error: ${stderr.lineSequence().firstOrNull()}")
    }

    /** The line numbers an rc-5 message names (`[line  3]`, `line: 1`), in message order. */
    fun reportedLines(stderr: String): List<Int> =
        LINE.findAll(stderr).map { m -> (m.groupValues[1].ifEmpty { m.groupValues[2] }).toInt() }.toList()

    data class Setting(val value: Any?, val origin: String) {
        @Suppress("UNCHECKED_CAST")
        val strings: List<String> get() = value as List<String>
    }
}
