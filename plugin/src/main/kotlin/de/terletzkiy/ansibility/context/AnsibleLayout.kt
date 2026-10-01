package de.terletzkiy.ansibility.context

/**
 * Pure naming rules of an Ansible tree (plan A.5): which directory and file names mean what.
 *
 * Nothing here touches the VFS, so the rules are shared by root detection, the file classifier and the
 * structure-change filter, and are unit-tested on plain strings.
 */
object AnsibleLayout {
    const val ANSIBLE_CFG = "ansible.cfg"
    const val ROLES = "roles"
    const val ENVIRONMENTS = "environments"
    const val GROUP_VARS = "group_vars"
    const val HOST_VARS = "host_vars"
    const val MOLECULE = "molecule"
    const val PLAYBOOKS = "playbooks"
    const val DOCKER = "docker"
    const val PATCHES = "patches"
    const val DOT_ANSIBLE = ".ansible"
    const val DOT_GIT = ".git"

    /** Directory names never descended into while looking for roots (heavy or tool-owned trees). */
    val SKIPPED_DIRS: Set<String> = setOf(
        ".git", "node_modules", ".gradle", "build", ".idea", DOT_ANSIBLE, "__pycache__",
        ".venv", "venv", ".tox", ".mypy_cache", ".pytest_cache", ".ruff_cache", ".terraform",
        ".intellijPlatform", ".kotlin", ".next", ".nuxt", ".yarn", ".pnpm-store", "vendor",
    )

    /**
     * Directories of a root (or of any directory inside one) that never contain another root, so the root
     * walk does not descend into them. Classification still covers every file below them.
     */
    val ROOT_CONTENT_DIRS: Set<String> = setOf(
        ROLES, ENVIRONMENTS, "inventories", GROUP_VARS, HOST_VARS, "files", "templates", DOCKER, MOLECULE,
        "library", "module_utils", "filter_plugins", "lookup_plugins", "callback_plugins", "action_plugins",
        "collections", "tasks", "handlers", "defaults", "vars", "meta",
    )

    /** Inventory directory names whose children are environments (`environments/<env>/hosts.yml`). */
    val ENVIRONMENT_PARENTS: Set<String> = setOf(ENVIRONMENTS, "inventories")

    /** File names of ansible-lint configuration. */
    val LINT_CONFIG_NAMES: Set<String> = setOf(
        "ansible-lint.yml", "ansible-lint.yaml", ".ansible-lint", ".ansible-lint.yml", ".ansible-lint.yaml",
    )

    /** Molecule playbooks by stem (`converge.yml`, `verify.yml`, …). */
    val MOLECULE_PLAYBOOK_STEMS: Set<String> =
        setOf("converge", "verify", "prepare", "cleanup", "side_effect", "create", "destroy")

    /** File extensions ansible-core accepts for vars files, besides "no extension". */
    val VARS_EXTENSIONS: Set<String> = setOf("yml", "yaml", "json")

    /** Extensions that never name a host even though host names may contain dots. */
    private val NON_HOST_EXTENSIONS: Set<String> = setOf(
        "md", "txt", "rst", "bak", "orig", "rej", "swp", "tmp", "log", "j2", "sh", "py", "pyc", "ini", "cfg",
    )

    fun isYamlName(name: String): Boolean = name.endsWith(".yml") || name.endsWith(".yaml")

    fun isPlaybookName(name: String): Boolean = name.startsWith("playbook-") && isYamlName(name)

    fun isHostsFileName(name: String): Boolean = name == "hosts.yml" || name == "hosts.yaml"

    fun isRequirementsName(name: String): Boolean = name == "requirements.yml" || name == "requirements.yaml"

    fun isArgumentSpecsName(name: String): Boolean = name == "argument_specs.yml" || name == "argument_specs.yaml"

    fun isMoleculeConfigName(name: String): Boolean = name == "molecule.yml" || name == "molecule.yaml"

    /** `**`/molecule/`**`/`*_tasks.y{a,}ml`, the repo's ansible-lint `kinds` rule for molecule task lists. */
    fun isMoleculeTasksName(name: String): Boolean = name.endsWith("_tasks.yml") || name.endsWith("_tasks.yaml")

    fun isDockerfileName(name: String): Boolean =
        name == "Dockerfile" || (name.startsWith("Dockerfile.") && !name.endsWith(".j2"))

    /** The name without its last extension (`converge.yml` → `converge`). */
    fun stem(name: String): String = name.substringBeforeLast('.', name)

    /** Hidden files and editor backups, which ansible-core skips when it loads vars directories. */
    fun isIgnoredVarsEntry(name: String): Boolean = name.startsWith(".") || name.endsWith("~")

    /**
     * Whether ansible-core loads [name] as a file inside a vars *directory* (`group_vars/all/…`): not hidden,
     * not a backup, and with no extension or a vars extension (Python's `os.path.splitext`).
     */
    fun isVarsFileInDirectory(name: String): Boolean {
        if (isIgnoredVarsEntry(name)) return false
        val ext = pythonExtension(name) ?: return true
        return ext in VARS_EXTENSIONS
    }

    /** Whether a directory below a vars directory is recursed into (not hidden, no extension). */
    fun isVarsSubdirectory(name: String): Boolean = !isIgnoredVarsEntry(name) && pythonExtension(name) == null

    /**
     * The group named by a file directly in `group_vars/` (`all.yml` → `all`, `all` → `all`), or null when
     * ansible-core would never load it for a group (hidden, backup, foreign extension).
     */
    fun groupFromFileName(name: String): String? {
        if (isIgnoredVarsEntry(name)) return null
        val ext = pythonExtension(name) ?: return name
        return if (ext in VARS_EXTENSIONS) name.substringBeforeLast('.') else null
    }

    /**
     * The host named by a file directly in `host_vars/`. Host names may contain dots
     * (`preview-dev1.bike.example.de`), so a name without a vars extension is the host itself, unless its
     * extension is one that never names a host (`README.md`).
     */
    fun hostFromFileName(name: String): String? {
        if (isIgnoredVarsEntry(name)) return null
        val ext = pythonExtension(name) ?: return name
        return when (ext) {
            in VARS_EXTENSIONS -> name.substringBeforeLast('.')
            in NON_HOST_EXTENSIONS -> null
            else -> name
        }
    }

    /** `os.path.splitext` semantics: leading dots do not start an extension; null when there is none. */
    private fun pythonExtension(name: String): String? {
        val trimmed = name.trimStart('.')
        val dot = trimmed.lastIndexOf('.')
        if (dot <= 0 || dot == trimmed.length - 1) return null
        return trimmed.substring(dot + 1).lowercase()
    }
}
