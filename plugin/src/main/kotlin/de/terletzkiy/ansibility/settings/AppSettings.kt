package de.terletzkiy.ansibility.settings

import de.terletzkiy.ansibility.semantics.CoreVersion

/**
 * Application-wide Ansibility settings (plan "Coexistence & settings", application level). Immutable; read it
 * from any thread through [AnsibilityAppSettings.settings] and change it with [AnsibilityAppSettings.update].
 */
data class AppSettings(
    val executables: ExecutableSettings = ExecutableSettings(),
    val docs: DocsSettings = DocsSettings(),
    val jinja: JinjaSettings = JinjaSettings(),
    val coexistence: CoexistenceSettings = CoexistenceSettings(),
) {
    companion object {
        val DEFAULT = AppSettings()
    }
}

/**
 * Explicit paths of the Ansible tools. Null means "find it": the project SDK's `bin/`, then the `PATH` from
 * `EnvironmentUtil` (GUI launches do not inherit the shell's `PATH`). A tool without a path of its own is looked up
 * next to one of the other configured tools first ([pathFor]), since the tools of one install share a directory.
 */
data class ExecutableSettings(
    val ansibleDoc: String? = null,
    val ansibleInventory: String? = null,
    val ansible: String? = null,
    /**
     * D10 step 4: when no root pins an ansible-core version, guess the target from the local install
     * (`ansible --version`, run in the background), shown as "target guessed from local install".
     */
    val localTargetGuess: Boolean = true,
) {
    /**
     * The configured path for a tool: [own] when set, else the first other configured path ([ansible], [ansibleDoc],
     * [ansibleInventory] in that order), which the toolchain resolves to the sibling tool in the same directory.
     */
    fun pathFor(own: String?): String? = own ?: listOfNotNull(ansible, ansibleDoc, ansibleInventory).firstOrNull()
}

/** Which docs.ansible.com tree Ctrl+B on a module opens (D11). */
enum class DocsWebBase {
    /** The Ansible package that ships the root's target ansible-core (core 2.18 → package 11). */
    TARGET_VERSIONED,

    /** `latest`, whatever the target. */
    LATEST,

    /** [DocsSettings.customWebBaseUrl], e.g. an internal mirror. */
    CUSTOM,
}

/** What Ctrl+B on a module name does (🟣 CLAUDE X22 adds the module source). */
enum class ModuleNavigationTarget { WEB_DOCS, QUICK_DOC, MODULE_SOURCE }

/** Documentation settings (D11, D14). */
data class DocsSettings(
    val webBase: DocsWebBase = DocsWebBase.TARGET_VERSIONED,
    /** Used when [webBase] is [DocsWebBase.CUSTOM]; the base under which `collections/<ns>/<coll>/…` pages live. */
    val customWebBaseUrl: String = "",
    val moduleNavigation: ModuleNavigationTarget = ModuleNavigationTarget.WEB_DOCS,
    /** Refresh module docs from the local `ansible-doc` in the background (D14). */
    val backgroundRefresh: Boolean = true,
) {
    /**
     * The docs base URL for a root whose target ansible-core is [target], always ending in `/`.
     * [DocsWebBase.TARGET_VERSIONED] falls back to `latest` when the target is unknown or older than core 2.10
     * (before the package/core split). A blank custom URL also falls back to `latest`.
     */
    fun webBaseUrl(target: CoreVersion?): String = when (webBase) {
        DocsWebBase.LATEST -> LATEST_URL
        DocsWebBase.CUSTOM -> customWebBaseUrl.trim().takeIf { it.isNotEmpty() }?.let { if (it.endsWith("/")) it else "$it/" } ?: LATEST_URL
        DocsWebBase.TARGET_VERSIONED -> ansiblePackageFor(target)?.let { "$DOCS_ROOT$it/" } ?: LATEST_URL
    }

    companion object {
        const val DOCS_ROOT: String = "https://docs.ansible.com/ansible/"
        const val LATEST_URL: String = "${DOCS_ROOT}latest/"

        /** The Ansible community package major that ships [core] (`2.N` → `N − 7` since ansible 3 / core 2.10). */
        fun ansiblePackageFor(core: CoreVersion?): Int? =
            core?.takeIf { it.major == 2 && it.minor >= 10 }?.let { it.minor - 7 }
    }
}

/**
 * One outer-language rule for Ansible Jinja templates: templates whose name or path matches [pattern] get
 * [languageId] as their outer language (plan A.5 `OuterLanguageRules`). The first matching rule wins; no match
 * means plain text, never HTML.
 *
 * Matching is done on the template's *inner* name, i.e. without a trailing `.j2`, so `nginx.conf` matches
 * `nginx.conf.j2` and `Dockerfile` matches `Dockerfile.j2`:
 * - a pattern without `/` is matched against the inner file name;
 * - a pattern with `/` is matched against the inner path relative to the Ansible root ([PathGlob] syntax).
 *
 * [languageId] is an IntelliJ language ID (`yaml`, `Shell Script`, `Dockerfile`, `TEXT` …). A language no installed
 * plugin provides falls back to plain text; [findLanguageId] only matches, the Jinja area resolves the language.
 */
data class OuterLanguageRule(val pattern: String, val languageId: String) {
    private val glob: PathGlob? by lazy(LazyThreadSafetyMode.PUBLICATION) { PathGlob.compile(pattern) }

    /** Whether this rule applies to a template named [fileName] at [relativePath] (both may end in `.j2`). */
    fun matches(fileName: String, relativePath: String): Boolean {
        val glob = glob ?: return false
        return if (glob.isPathPattern) glob.matches(stripJ2(relativePath)) else glob.matches(stripJ2(fileName))
    }

    companion object {
        /** IntelliJ's plain-text language ID. */
        const val PLAIN_TEXT: String = "TEXT"

        private fun stripJ2(name: String): String = if (name.endsWith(".j2") && name.length > 3) name.dropLast(3) else name

        /** The language ID of the first rule in [rules] matching the template, or null (plain text). */
        fun findLanguageId(rules: List<OuterLanguageRule>, fileName: String, relativePath: String): String? =
            rules.firstOrNull { it.matches(fileName, relativePath) }?.languageId
    }
}

/** Jinja settings (plan R2, D8, 🟣 CLAUDE X35). */
data class JinjaSettings(
    /** Claim `.j2` files inside Ansible roots for Ansible Jinja (D8). */
    val claimJ2InsideRoots: Boolean = true,
    /** "Keep YAML for .j2": leave `.j2` files as YAML (the user's own mapping); overrides [claimJ2InsideRoots]. */
    val keepYamlForJ2: Boolean = false,
    /** Treat Jinja-bearing non-`.j2` files under `roles/<role>/templates` as templates. */
    val treatJinjaTemplatesUnderTemplatesDir: Boolean = true,
    /** Leave `.j2` to PyCharm's own Jinja2 plugin where it is installed. */
    val deferToPyCharmJinja: Boolean = false,
    val outerLanguageRules: List<OuterLanguageRule> = DEFAULT_OUTER_LANGUAGE_RULES,
    /** 🟣 CLAUDE X35: auto-close `{{ }}` and `{% %}`. */
    val autoCloseDelimiters: Boolean = true,
    /** 🟣 CLAUDE X35: auto-insert `{% endif %}`, `{% endfor %}` … */
    val autoInsertEndTags: Boolean = true,
) {
    /** Whether `.j2` files inside roots become Ansible Jinja: claimed, not kept as YAML and not deferred. */
    val claimsJ2: Boolean
        get() = claimJ2InsideRoots && !keepYamlForJ2 && !deferToPyCharmJinja

    /** The outer language ID for a template, per [outerLanguageRules]; null means plain text. */
    fun outerLanguageId(fileName: String, relativePath: String): String? =
        OuterLanguageRule.findLanguageId(outerLanguageRules, fileName, relativePath)

    companion object {
        private const val NGINX = "Nginx"
        private const val SYSTEMD = "Unit File (systemd)"

        /**
         * The default rules (plan A.5, research jinja.md), first match wins:
         * 1. plain-text configs that an nginx plugin would claim (`logrotate`, systemd drop-ins, `rsyslog`,
         *    keepalived). They come first because the repo keeps `templates/nginx/logrotate.conf.j2` next to the
         *    nginx sites (measured in 6 roles);
         * 2. nginx: files in a `templates/nginx` directory, `*site*.conf`, `nginx.conf`, `gateway.*.conf`;
         * 3. `Dockerfile`; 4. `haproxy.cfg` as plain text; 5. the inner-extension whitelist.
         *
         * Language IDs were read from the 262 plugins (`Ini`, `DotEnv`, `Shell Script`, `Dockerfile`); Nginx and
         * systemd come from third-party plugins and fall back to plain text without them.
         */
        val DEFAULT_OUTER_LANGUAGE_RULES: List<OuterLanguageRule> = listOf(
            OuterLanguageRule("logrotate*", OuterLanguageRule.PLAIN_TEXT),
            OuterLanguageRule("*.override.conf", OuterLanguageRule.PLAIN_TEXT),
            OuterLanguageRule("rsyslog*", OuterLanguageRule.PLAIN_TEXT),
            OuterLanguageRule("keepalived.conf", OuterLanguageRule.PLAIN_TEXT),
            OuterLanguageRule("**/templates/nginx/**", NGINX),
            OuterLanguageRule("*site*.conf", NGINX),
            OuterLanguageRule("nginx.conf", NGINX),
            OuterLanguageRule("gateway.*.conf", NGINX),
            OuterLanguageRule("Dockerfile", "Dockerfile"),
            OuterLanguageRule("haproxy.cfg", OuterLanguageRule.PLAIN_TEXT),
            OuterLanguageRule("*.yml", "yaml"),
            OuterLanguageRule("*.yaml", "yaml"),
            OuterLanguageRule("*.sh", "Shell Script"),
            OuterLanguageRule("*.json", "JSON"),
            OuterLanguageRule("*.ini", "Ini"),
            OuterLanguageRule("*.cnf", "Ini"),
            OuterLanguageRule("*.env", "DotEnv"),
            OuterLanguageRule("*.py", "Python"),
            OuterLanguageRule("*.service", SYSTEMD),
            OuterLanguageRule("*.timer", SYSTEMD),
        )
    }
}

/** Coexistence with other Ansible plugins (🟣 CLAUDE X03, X85). */
data class CoexistenceSettings(
    /** One-time notifications about conflicting Ansible plugins (X03). */
    val conflictNotifications: Boolean = true,
    /** Hide other Ansible plugins' completions instead of only removing duplicates (X85). */
    val hideOtherAnsibleCompletions: Boolean = false,
)
