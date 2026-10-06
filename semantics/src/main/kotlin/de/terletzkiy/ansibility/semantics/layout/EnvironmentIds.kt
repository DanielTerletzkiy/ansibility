package de.terletzkiy.ansibility.semantics.layout

import de.terletzkiy.ansibility.semantics.inventory.Py

/**
 * The environment ids of a root's inventories (plan amendment R10, D48–D50, F10.5). An id is `HostKey.environment`
 * and is persisted in stored selections and vault mappings, so the rules depend only on the layout:
 *
 * - a convention directory (`environments/<env>`, `inventories/<env>`) keeps its name: `prod`;
 * - anything else takes the file stem or directory name (`hosts`, `inventory`, `production`); the cfg directory as
 *   a source (an empty `inventory =`, `.`, a trailing comma) takes the directory's name;
 * - a `[defaults] inventory` list is one environment whose id joins the names with `+` (`hosts+extra`); names that
 *   repeat inside the list are replaced by their root-relative paths (`a/hosts.ini+b/hosts.yml`);
 * - D49: a cfg list that is one directory is one merged environment, or one per file with the "one environment per
 *   file" switch ([fromCfg]); D50: detected files and detected directories give one environment per file
 *   ([fromDetected]);
 * - on a collision the strongest [Origin] keeps the bare id (the earlier candidate on a tie) and every other one gets
 *   its root-relative path; settings names win, convention names never change;
 * - ids never start with [RESERVED_PREFIX], which molecule pseudo-inventories use.
 *
 * Labels are the plain names joined with ` + `; the plugin adds decorations such as "(whole root)" or "(2 files)".
 */
object EnvironmentIds {
    /** The prefix of molecule pseudo-inventories (`HostKey.MOLECULE_PREFIX`); no layout environment may use it. */
    const val RESERVED_PREFIX: String = "molecule:"

    /**
     * Where a candidate comes from, strongest first: the order in which a colliding id stays bare. It differs from
     * the precedence of layout values in one place on purpose: a convention id outranks an `ansible.cfg` one, because
     * convention ids were persisted before R10 and may never change.
     */
    enum class Origin { SETTINGS, CONVENTION, ANSIBLE_CFG, DETECTED }

    /** One inventory source as the naming rules see it. */
    data class Source(
        /** Root-relative and `/`-separated (`hosts.ini`, `inventory/prod.yml`, `../shared/hosts`); `.` is the root itself. */
        val path: String,
        /** Whether [path] names a directory inventory source instead of a single source file. */
        val isDirectory: Boolean = false,
        /** The name this source contributes: a file's stem, a directory's name. Pass the root's name for `.`. */
        val name: String = nameOf(path, isDirectory),
        /** A directory source's files that the target core reads, root-relative, in load order (for per-file splits). */
        val files: List<String> = emptyList(),
    )

    /** One environment before naming: the id it asks for ([name]) and the one it falls back to ([fallback]). */
    class Candidate private constructor(
        val origin: Origin,
        val sources: List<Source>,
        val name: String,
        val fallback: String,
        val label: String,
        val fallbackLabel: String,
    ) {
        override fun toString(): String = "Candidate($origin, $name, ${sources.map { it.path }})"

        companion object {
            /** A convention environment directory such as `environments/prod`, with the sources it holds. */
            fun convention(envDir: String, sources: List<Source> = listOf(Source(envDir, isDirectory = true))): Candidate {
                val name = nameOf(envDir, isDirectory = true)
                return Candidate(Origin.CONVENTION, sources, name, envDir, name, envDir)
            }

            /** An environment from the Layout settings: its name is used as given. */
            fun settings(name: String, sources: List<Source>): Candidate =
                Candidate(Origin.SETTINGS, sources, name, name, name, name)

            /** The environment of a `[defaults] inventory` list, in `-i` order. */
            fun cfg(sources: List<Source>): Candidate {
                require(sources.isNotEmpty()) { "a cfg inventory has at least one entry" }
                val counts = sources.groupingBy { it.name }.eachCount()
                val parts = sources.map { if (counts.getValue(it.name) > 1) it.path else it.name }
                val paths = sources.map { it.path }
                return Candidate(Origin.ANSIBLE_CFG, sources, parts.joinToString("+"), paths.joinToString("+"),
                    parts.joinToString(" + "), paths.joinToString(" + "))
            }

            /** A detected inventory file or directory. */
            fun detected(source: Source): Candidate =
                Candidate(Origin.DETECTED, listOf(source), source.name, source.path, source.name, source.path)
        }
    }

    /** An assigned id. [renamed] is true when the candidate did not get its bare name. */
    data class EnvironmentId(val id: String, val label: String, val renamed: Boolean)

    /** Why a settings name is refused (F10.6 validation). */
    enum class NameProblem { EMPTY, RESERVED, DUPLICATE }

    /**
     * D49: the environments of a `[defaults] inventory` list. A list that is a single directory with files gives one
     * environment per file when [onePerFile] is on; everything else is one merged environment, which is what a run
     * without `-i` loads.
     */
    fun fromCfg(sources: List<Source>, onePerFile: Boolean): List<Candidate> {
        val single = sources.singleOrNull()
        if (onePerFile && single != null && single.isDirectory && single.files.isNotEmpty()) {
            return single.files.map { Candidate.cfg(listOf(Source(it))) }
        }
        return listOf(Candidate.cfg(sources))
    }

    /**
     * D50: detected inventories, one environment per file (that is how such layouts are run: `-i inventory/prod.yml`).
     * A detected directory gives one per file of [Source.files]; one without files stays one (empty) environment.
     */
    fun fromDetected(sources: List<Source>): List<Candidate> = sources.flatMap { source ->
        if (source.isDirectory && source.files.isNotEmpty()) source.files.map { Candidate.detected(Source(it)) }
        else listOf(Candidate.detected(source))
    }

    /** The ids of [candidates], in their order and unique among them. */
    fun assign(candidates: List<Candidate>): List<EnvironmentId> {
        val ids = arrayOfNulls<EnvironmentId>(candidates.size)
        val taken = HashSet<String>()
        for ((name, members) in candidates.indices.groupBy { candidates[it].name }) {
            if (name.isEmpty() || isReserved(name)) continue
            val winner = members.minWith(compareBy({ candidates[it].origin }, { it }))
            ids[winner] = EnvironmentId(name, candidates[winner].label, renamed = false)
            taken += name
        }
        for (i in candidates.indices) {
            if (ids[i] != null) continue
            val candidate = candidates[i]
            val base = candidate.fallback.ifEmpty { "." }.let { if (isReserved(it)) "_$it" else it }
            var id = base
            var suffix = ""
            var n = 2
            while (id in taken) {
                suffix = " (${n++})"
                id = base + suffix
            }
            taken += id
            ids[i] = EnvironmentId(id, candidate.fallbackLabel + suffix, renamed = true)
        }
        return ids.map { checkNotNull(it) }
    }

    /** True for an id that only a molecule pseudo-inventory may have. */
    fun isReserved(id: String): Boolean = id.startsWith(RESERVED_PREFIX)

    /** The problems of the settings names [names], by index: blank, reserved, or a repeat of an earlier name. */
    fun nameProblems(names: List<String>): Map<Int, NameProblem> {
        val seen = HashSet<String>()
        val problems = LinkedHashMap<Int, NameProblem>()
        names.forEachIndexed { i, name ->
            when {
                name.isBlank() -> problems[i] = NameProblem.EMPTY
                isReserved(name) -> problems[i] = NameProblem.RESERVED
                !seen.add(name) -> problems[i] = NameProblem.DUPLICATE
            }
        }
        return problems
    }

    /** The stem of a file name, as `os.path.splitext` splits it: `hosts.ini` → `hosts`, `.hosts` → `.hosts`. */
    fun stem(fileName: String): String = fileName.removeSuffix(Py.extension(fileName))

    /** The last segment of [path]; for a file its [stem]. */
    fun nameOf(path: String, isDirectory: Boolean): String {
        val segment = path.trimEnd('/').substringAfterLast('/')
        return if (isDirectory) segment else stem(segment)
    }
}
