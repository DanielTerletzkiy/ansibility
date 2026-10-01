package de.terletzkiy.ansibility.index

import com.intellij.openapi.vfs.VirtualFile

/**
 * What a file's own path says about its place in an Ansible tree, before any root is known (plan A.7).
 *
 * The hint is a pure function of the file name and its ancestor directory names. It never looks at other files, so
 * it is safe inside indexers; root, role, [de.terletzkiy.ansibility.api.FileKind] and layer are resolved at query
 * time by `AnsibleWorkspace`. Each hint has a stable [code] because it is stored in index values.
 */
enum class PathHint(val code: Int) {
    /** Anything else: playbooks, task files outside roles, molecule playbooks; the content decides. */
    OTHER(0),

    /** `roles/<role>/meta/argument_specs.y{a,}ml`. */
    ARGUMENT_SPECS(1),

    /** `roles/<role>/meta/main.y{a,}ml` (may hold an `argument_specs` key). */
    ROLE_META(2),

    /** A file below `roles/<role>/defaults/`. */
    DEFAULTS(3),

    /** A file below `roles/<role>/vars/`, a molecule `vars/` directory, or a playbook-level `vars/` directory. */
    VARS(4),

    /** A file below a `group_vars/` directory (inventory, playbook or molecule level). */
    GROUP_VARS(5),

    /** A file below a `host_vars/` directory. */
    HOST_VARS(6),

    /** `hosts.y{a,}ml`, a YAML inventory. */
    INVENTORY(7),

    /** `molecule/<scenario>/molecule.y{a,}ml`. */
    MOLECULE_CONFIG(8),

    /** A Jinja template: any `*.j2` file, and every file below a role's (or molecule scenario's) `templates/`. */
    TEMPLATE(9),

    /** A file below `roles/<role>/tasks/` or a playbook-level `tasks/` directory. */
    TASKS(10),

    /** A file below a `handlers/` directory. */
    HANDLERS(11),

    /** A file below `roles/<role>/files/` that is not a `.j2` template: copied verbatim, never templated. */
    FILES(12),
    ;

    companion object {
        private val BY_CODE: Array<PathHint> = entries.sortedBy { it.code }.toTypedArray()

        /** The hint stored as [code]; [OTHER] for an unknown code. */
        fun ofCode(code: Int): PathHint = BY_CODE.getOrNull(code)?.takeIf { it.code == code } ?: OTHER
    }
}

/**
 * The path-local facts of one file (plan A.7 `pathFacts`): its [hint], whether it is a vault file (whose values never
 * reach an index as previews) and whether it lies below a `molecule/` directory.
 */
data class PathFacts(
    val hint: PathHint,
    val fileName: String,
    /** A file of secrets by its name (`vault`, `vault.yml`, `vault_prod.yml` …; [ValueSummary.isVaultFileName]). */
    val isVaultFile: Boolean,
    /** The path went through a role's (or a playbook-level) `molecule/` directory. */
    val inMolecule: Boolean,
) {
    /** A `.yml`/`.yaml` name. */
    val isYamlName: Boolean get() = isYamlName(fileName)

    companion object {
        private const val ROLES = "roles"
        private const val MOLECULE = "molecule"
        private const val GROUP_VARS = "group_vars"
        private const val HOST_VARS = "host_vars"
        private const val TEMPLATES = "templates"

        /** How many ancestor directories are read; deep enough for every layout of the target repo. */
        private const val MAX_DEPTH = 40

        /** Facts of [file], from its name and ancestor directory names. */
        fun of(file: VirtualFile): PathFacts {
            val dirs = ArrayList<String>()
            var current = file.parent
            while (current != null && dirs.size < MAX_DEPTH) {
                dirs += current.name
                current = current.parent
            }
            dirs.reverse()
            return of(dirs, file.name)
        }

        /** Facts of a `/`-separated [path]; the last segment is the file name. */
        fun of(path: String): PathFacts {
            val segments = path.split('/').filter { it.isNotEmpty() }
            return of(segments.dropLast(1), segments.lastOrNull().orEmpty())
        }

        /** Facts of a file called [name] whose ancestor directory names are [dirs], outermost first. */
        fun of(dirs: List<String>, name: String): PathFacts {
            val roleAt = nearestRoleDir(dirs)
            var molecule = false
            val hint = if (roleAt >= 0) {
                val inRole = dirs.subList(roleAt + 2, dirs.size)
                when (inRole[0]) {
                    "tasks" -> if (isJ2(name)) PathHint.TEMPLATE else PathHint.TASKS
                    "handlers" -> PathHint.HANDLERS
                    "defaults" -> PathHint.DEFAULTS
                    "vars" -> PathHint.VARS
                    "meta" -> when {
                        inRole.size == 1 && isArgumentSpecsName(name) -> PathHint.ARGUMENT_SPECS
                        inRole.size == 1 && isYamlName(name) && name.substringBeforeLast('.') == "main" -> PathHint.ROLE_META
                        else -> PathHint.OTHER
                    }
                    TEMPLATES -> PathHint.TEMPLATE
                    "files" -> if (isJ2(name)) PathHint.TEMPLATE else PathHint.FILES
                    MOLECULE -> {
                        molecule = true
                        moleculeHint(inRole.subList(1, inRole.size), name)
                    }
                    else -> generic(inRole, name)
                }
            } else {
                val moleculeAt = dirs.lastIndexOf(MOLECULE)
                if (moleculeAt >= 0) {
                    molecule = true
                    moleculeHint(dirs.subList(moleculeAt + 1, dirs.size), name)
                } else {
                    generic(dirs, name)
                }
            }
            return PathFacts(hint, name, ValueSummary.isVaultFileName(name), molecule)
        }

        fun isYamlName(name: String): Boolean = name.endsWith(".yml") || name.endsWith(".yaml")

        fun isJ2(name: String): Boolean = name.endsWith(".j2")

        private fun isArgumentSpecsName(name: String) = name == "argument_specs.yml" || name == "argument_specs.yaml"

        /**
         * The index of the nearest `roles` directory that has a role directory and a sub-directory below it, so the file
         * is at least `roles/<role>/<dir>/<file>`; -1 when there is none. A file directly in `roles/<x>/` is not a role
         * file (`roles/requirements.yml`, or a project that happens to live in a directory called `roles`).
         */
        private fun nearestRoleDir(dirs: List<String>): Int {
            for (i in dirs.size - 3 downTo 0) {
                if (dirs[i] == ROLES) return i
            }
            return -1
        }

        /** Rules below a `molecule/` directory; [below] starts with the scenario directory (or `vars`). */
        private fun moleculeHint(below: List<String>, name: String): PathHint = when {
            isJ2(name) -> PathHint.TEMPLATE
            GROUP_VARS in below -> PathHint.GROUP_VARS
            HOST_VARS in below -> PathHint.HOST_VARS
            below.size == 1 && (name == "molecule.yml" || name == "molecule.yaml") -> PathHint.MOLECULE_CONFIG
            below.firstOrNull() == "vars" || below.getOrNull(1) == "vars" -> PathHint.VARS
            below.getOrNull(1) == TEMPLATES -> PathHint.TEMPLATE
            else -> PathHint.OTHER
        }

        /** Rules outside a role directory; only the two nearest directories count for `vars`, `tasks` and friends. */
        private fun generic(dirs: List<String>, name: String): PathHint {
            if (isJ2(name)) return PathHint.TEMPLATE
            val varsAt = dirs.indexOfLast { it == GROUP_VARS || it == HOST_VARS }
            if (varsAt >= 0) return if (dirs[varsAt] == GROUP_VARS) PathHint.GROUP_VARS else PathHint.HOST_VARS
            if (name == "hosts.yml" || name == "hosts.yaml") return PathHint.INVENTORY
            val near = dirs.takeLast(2).asReversed()
            return when {
                near.firstOrNull() == "handlers" -> PathHint.HANDLERS
                "vars" in near -> PathHint.VARS
                "tasks" in near -> PathHint.TASKS
                TEMPLATES in near -> PathHint.TEMPLATE
                else -> PathHint.OTHER
            }
        }
    }
}
