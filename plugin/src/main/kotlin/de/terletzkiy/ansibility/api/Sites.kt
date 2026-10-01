package de.terletzkiy.ansibility.api

import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.util.TextRange
import com.intellij.platform.backend.documentation.DocumentationTarget
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

/** Where a Jinja variable reference sits. */
enum class JinjaContainer {
    /** A `.j2` (or Jinja-bearing) template file. */
    TEMPLATE_FILE,

    /** `{{ }}`/`{% %}` inside a YAML scalar. */
    YAML_TEMPLATE,

    /** The whole value of an implicit-expression key (`when`, `changed_when`, `failed_when`, `until`, `assert.that`, `debug.var`). */
    YAML_EXPRESSION,
}

enum class KeywordLevel { PLAY, BLOCK, TASK, HANDLER, ROLE_ENTRY, LOOP_CONTROL, PLAYBOOK_INCLUDE }

/**
 * What sits under the caret, classified once by the `siteClassifier` extensions (plan A.4).
 * [range] is in the coordinates of the file passed to the classifier (the host file, not an injected fragment).
 */
sealed interface AnsibleSite {
    val range: TextRange

    /** A variable reference in Jinja: `haproxy_servers`, `item.floating.ssl.port`, `ansible_facts['os_family']`. */
    data class VarRef(
        val name: String,
        val attrPath: List<String>,
        val container: JinjaContainer,
        override val range: TextRange,
        /** Names defined locally before the caret (`set`, `for` targets, macro params), when known. */
        val localNames: Set<String> = emptySet(),
    ) : AnsibleSite

    /**
     * A key in a vars-like YAML file (defaults, vars, group_vars, host_vars, play/task vars, argument_specs options).
     *
     * [keyPath] always starts at the variable name, never at the file's structural keys:
     * - vars files (defaults, vars, group_vars, host_vars, molecule vars): the top-level key, then nested keys and
     *   sequence indices (`["haproxy_servers", "0", "port"]`);
     * - `meta/argument_specs.yml`: the option name, then nested option names (`argument_specs.main.options` and
     *   intermediate `options` keys are dropped): `["haproxy_servers", "port"]`;
     * - play/block/task `vars:` and `hosts.yml` inline vars: the key under `vars:`, then nested keys.
     */
    data class VarKey(val keyPath: List<String>, val kind: FileKind, override val range: TextRange) : AnsibleSite

    data class ModuleKey(val fqcn: String, override val range: TextRange) : AnsibleSite

    data class ModuleOptionKey(val fqcn: String, val path: List<String>, override val range: TextRange) : AnsibleSite

    data class KeywordKey(val keyword: String, val level: KeywordLevel, override val range: TextRange) : AnsibleSite

    data class RoleRef(val name: String, override val range: TextRange) : AnsibleSite

    data class TaskFileRef(val roleName: String?, val path: String, override val range: TextRange) : AnsibleSite

    data class HandlerRef(val name: String, override val range: TextRange) : AnsibleSite

    data class TemplateRef(val path: String, val isCopySource: Boolean, override val range: TextRange) : AnsibleSite

    /**
     * A file a playbook names relative to its own directory: a play's `vars_files` entry or an `import_playbook`
     * target ([path] as written, unquoted).
     */
    data class PlaybookFileRef(val path: String, override val range: TextRange) : AnsibleSite

    data class JinjaFilter(val name: String, override val range: TextRange) : AnsibleSite

    data class JinjaTest(val name: String, override val range: TextRange) : AnsibleSite
}

// ------------------------------------------------------------------------------------------------
// Internal extension points. Global-precedence platform EPs (documentation target provider,
// goto-declaration handler, completion contributor) are registered once by `dispatch`;
// features implement only these.
// ------------------------------------------------------------------------------------------------

/**
 * Classifies the caret position. Areas contribute one each; the first non-null result wins (order attribute).
 * Implementations that need no indexes should also implement `DumbAware` so they keep working while indexing.
 */
interface SiteClassifier {
    fun classify(file: PsiFile, offset: Int): AnsibleSite?

    companion object {
        val EP_NAME = ExtensionPointName<SiteClassifier>("de.terletzkiy.ansibility.siteClassifier")
    }
}

interface SiteDocumentation {
    fun documentation(site: AnsibleSite, file: PsiFile): DocumentationTarget?

    companion object {
        val EP_NAME = ExtensionPointName<SiteDocumentation>("de.terletzkiy.ansibility.siteDocumentation")
    }
}

interface SiteNavigation {
    /** Declaration targets, nearest first. More than one target opens the platform chooser. */
    fun targets(site: AnsibleSite, file: PsiFile): List<PsiElement>

    companion object {
        val EP_NAME = ExtensionPointName<SiteNavigation>("de.terletzkiy.ansibility.siteNavigation")
    }
}

/**
 * Contributes completion items. The dispatcher calls every source, then runs the remaining (foreign) contributors
 * exactly once for all sources, so implementations must never call `runRemainingContributors` or `stopHere` themselves.
 */
interface CompletionSource {
    fun complete(site: AnsibleSite?, parameters: CompletionParameters, result: CompletionResultSet)

    companion object {
        val EP_NAME = ExtensionPointName<CompletionSource>("de.terletzkiy.ansibility.completionSource")
    }
}

/** Finds the Jinja variable reference at an offset: text-based until M5, PSI-based from M5 (order="first"). */
interface JinjaLocator {
    fun locate(file: PsiFile, offset: Int): AnsibleSite.VarRef?

    companion object {
        val EP_NAME = ExtensionPointName<JinjaLocator>("de.terletzkiy.ansibility.jinjaLocator")
    }
}
