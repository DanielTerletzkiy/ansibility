package de.terletzkiy.ansibility.lang.jinja.injection

import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.FileKind
import de.terletzkiy.ansibility.index.JinjaBearing
import de.terletzkiy.ansibility.index.PathFacts
import de.terletzkiy.ansibility.index.PathHint
import de.terletzkiy.ansibility.lang.jinja.psi.AnsibleJinjaFile
import de.terletzkiy.ansibility.yaml.PsiYValueAdapter
import de.terletzkiy.ansibility.yaml.YamlPaths
import de.terletzkiy.ansibility.yaml.YamlPsi
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLScalar
import org.jetbrains.yaml.psi.YAMLSequence
import org.jetbrains.yaml.psi.YAMLSequenceItem

/**
 * Which YAML scalars carry Ansible Jinja, and how (plan A.5 "Jinja inside YAML", WU C4): the one decision shared by
 * the injector ([AnsibleJinjaYamlInjector]) and the consumers of the injected PSI (the PSI locator, the PSI entry of
 * the T020 evaluation). It mirrors the text-level rules of the `ansible.var.use` indexer and `vars.JinjaTextSites`, so
 * the injected fragments are exactly the Jinja those see.
 *
 * **Gate (cheap, path only):** the file is inside an Ansible root ([AnsibleWorkspace.contextOf], cached) and has an
 * Ansible [FileKind]: never the kinds Ansible does not template ([NEVER_TEMPLATED]), never a template file (a `*.j2`
 * or a role template, whose whole text is Jinja), and for [FileKind.OTHER] only playbook-level task, handler and vars
 * files by their path ([ANSIBLE_PATH_HINTS]). GitHub Actions `${{ }}`, Helm charts and compose files outside roots
 * are never touched.
 *
 * **Per scalar:** [JinjaBearing.isJinjaBearingScalar] must hold (no `!vault`/`!unsafe`, no argument specs, molecule
 * configuration only below `provisioner.inventory`, no compose or tool files), then
 * - a value containing `{{` or `{%` (after decoding the scalar) is injected in [JinjaInjectionMode.TEMPLATE] mode;
 * - an implicit-expression value of a task list or playbook ([JinjaBearing.implicitExpressionOffsets]: `when`,
 *   `changed_when`, `failed_when`, `until`, `assert.that` items, `debug.var`) is injected in
 *   [JinjaInjectionMode.EXPRESSION] mode, wrapped in `{{ ` and ` }}`; one that already contains `{{`/`{%` (the X30
 *   hazard `when: "{{ x }}"`) is a template instead.
 *
 * **Text.** The fragment reads the scalar's value as its own `createLiteralTextEscaper()` decodes it (quotes, escapes,
 * block indentation), so Jinja never sees YAML syntax: `"{{ x | default(\"a\") }}"` reads `default("a")`, `''` in a
 * single-quoted scalar reads `'`. The platform's injected text is the host text of each place, so the value is split
 * into [Place]s: runs of characters the host spells verbatim become places, and what decoding changes (an escape
 * sequence, a block scalar's indentation) is skipped, its decoded characters becoming the (read-only) prefix of the
 * next place. A scalar whose escapes do not decode is not injected. Call in a read action.
 */
object JinjaYamlInjections {
    /** One host range of an injection, with the text injected before and after it (not in the host). */
    class Place(val prefix: String?, val rangeInHost: TextRange, val suffix: String?) {
        override fun toString(): String = "Place(${prefix?.let { "'$it'+" } ?: ""}$rangeInHost${suffix?.let { "+'$it'" } ?: ""})"
    }

    /**
     * One injection: the [mode], the value's range inside the host scalar (the escaper's relevant range), the decoded
     * [value] and the [places] whose texts and prefixes together read `mode.prefix + value + mode.suffix`.
     */
    class Injection(val mode: JinjaInjectionMode, val rangeInHost: TextRange, val value: String, val places: List<Place>)

    /** Kinds whose YAML scalars Ansible never templates, or whose whole text is Jinja. */
    val NEVER_TEMPLATED: Set<FileKind> = setOf(
        FileKind.ROLE_ARGSPEC, FileKind.ANSIBLE_CFG, FileKind.LINT_CONFIG, FileKind.REQUIREMENTS, FileKind.ROLE_FILE,
        FileKind.ROLE_TEMPLATE,
    )

    /** Path hints of [FileKind.OTHER] files that are Ansible content: playbook-level `tasks/`, `handlers/`, `vars/` … */
    val ANSIBLE_PATH_HINTS: Set<PathHint> = setOf(
        PathHint.TASKS, PathHint.HANDLERS, PathHint.VARS, PathHint.DEFAULTS, PathHint.GROUP_VARS, PathHint.HOST_VARS,
        PathHint.INVENTORY,
    )

    /** Keys whose value (or list items) may be an implicit expression; the task model decides ([JinjaBearing]). */
    private val EXPRESSION_KEYS: Set<String> = JinjaBearing.IMPLICIT_EXPRESSION_KEYS + setOf("that", "var")

    private val EXPRESSION_STARTS = Key.create<CachedValue<Set<Int>>>("ansibility.jinja.injection.implicitExpressions")

    /** How [scalar] is injected, or null when it carries no Jinja (or its file is outside the gate). */
    fun injectionFor(scalar: YAMLScalar): Injection? {
        val raw = scalar.text
        val markers = JinjaBearing.hasTemplateMarkers(raw)
        if (!markers && !mayBeImplicitExpression(scalar)) return null
        val file = scalar.containingFile as? YAMLFile ?: return null
        val virtualFile = file.originalFile.viewProvider.virtualFile
        if (!isInjectableFile(file.project, virtualFile)) return null
        val facts = PathFacts.of(virtualFile)
        val expression = !markers && isImplicitExpression(file, scalar, facts)
        if (!markers && !expression) return null
        if (!JinjaBearing.isJinjaBearingScalar(facts, YamlPaths.keyPath(scalar), YamlPsi.tagOf(scalar))) return null
        val escaper = scalar.createLiteralTextEscaper()
        val range = escaper.relevantTextRange
        val decoded = StringBuilder()
        if (!escaper.decode(range, decoded)) return null
        val mode = when {
            JinjaBearing.hasTemplateMarkers(decoded) -> JinjaInjectionMode.TEMPLATE
            expression || isImplicitExpression(file, scalar, facts) -> JinjaInjectionMode.EXPRESSION
            else -> return null
        }
        val hostOffsets = IntArray(decoded.length + 1) { escaper.getOffsetInHost(it, range) }
        return Injection(mode, range, decoded.toString(), places(raw, decoded, hostOffsets, range, mode))
    }

    /**
     * The places reading [decoded] (the value of the scalar with host text [hostText]; [hostOffsets] maps each decoded
     * offset to a host offset, -1 when unmapped): maximal runs of decoded characters that are the very host character
     * at their offset, each run one place; every other decoded character is carried as text before the next run (or
     * after the last one). The mode's prefix opens the first place, its suffix closes the last.
     */
    internal fun places(
        hostText: CharSequence,
        decoded: CharSequence,
        hostOffsets: IntArray,
        range: TextRange,
        mode: JinjaInjectionMode,
    ): List<Place> {
        val places = ArrayList<Place>()
        val pending = StringBuilder(mode.prefix.orEmpty())
        var runStart = -1
        var runEnd = -1
        var runPrefix: String? = null
        fun closeRun() {
            if (runStart < 0) return
            places += Place(runPrefix, TextRange(runStart, runEnd), null)
            runStart = -1
        }
        for (i in decoded.indices) {
            val host = hostOffsets[i]
            val next = hostOffsets[i + 1]
            val verbatim = host >= 0 && host < hostText.length && next > host && hostText[host] == decoded[i]
            if (!verbatim) {
                closeRun()
                pending.append(decoded[i])
                continue
            }
            if (runStart >= 0 && host != runEnd) closeRun()
            if (runStart < 0) {
                runStart = host
                runPrefix = pending.toString().ifEmpty { null }
                pending.setLength(0)
            }
            runEnd = host + 1
        }
        closeRun()
        val tail = pending.append(mode.suffix.orEmpty()).toString().ifEmpty { null }
        if (places.isEmpty()) return listOf(Place(tail, TextRange(range.startOffset, range.startOffset), null))
        val last = places.removeAt(places.lastIndex)
        places += Place(last.prefix, last.rangeInHost, tail)
        return places
    }

    /** True when YAML scalars of [file] may carry injected Jinja: the cheap path gate of the injector. */
    fun isInjectableFile(project: Project, file: VirtualFile): Boolean {
        if (PathFacts.isJ2(file.name)) return false
        val context = AnsibleWorkspace.getInstance(project).contextOf(file) ?: return false
        if (context.kind in NEVER_TEMPLATED) return false
        return context.kind != FileKind.OTHER || PathFacts.of(file).hint in ANSIBLE_PATH_HINTS
    }

    /** The Ansible Jinja fragment injected into [scalar], computing the injection if needed; null when there is none. */
    fun injectedFile(scalar: YAMLScalar): AnsibleJinjaFile? =
        InjectedLanguageManager.getInstance(scalar.project).getInjectedPsiFiles(scalar)
            ?.firstNotNullOfOrNull { it.first as? AnsibleJinjaFile }

    /**
     * The Ansible Jinja fragment injected into [scalar] if the platform has already built it (for highlighting, an
     * editor action, a locator query), without ever computing an injection; null otherwise. For callers that walk many
     * scalars of a file and have a text fallback: injecting every scalar of a large vars file only to read it once costs
     * more than lexing its text.
     */
    fun cachedInjectedFile(scalar: YAMLScalar): AnsibleJinjaFile? {
        val host = scalar.containingFile ?: return null
        val project = scalar.project
        val manager = InjectedLanguageManager.getInstance(project)
        val documents = PsiDocumentManager.getInstance(project)
        for (window in manager.getCachedInjectedDocumentsInRange(host, scalar.textRange)) {
            if (!window.isValid) continue
            val fragment = documents.getCachedPsiFile(window) as? AnsibleJinjaFile ?: continue
            // a window of an earlier version of the host has another (invalid) host element
            if (fragment.isValid && manager.getInjectionHost(fragment) === scalar) return fragment
        }
        return null
    }

    /** The mode [file] was injected in, or null when it is no YAML fragment (a template file, for example). */
    fun modeOf(file: AnsibleJinjaFile): JinjaInjectionMode? {
        file.getUserData(JinjaInjectionMode.KEY)?.let { return it }
        val host = hostOf(file) ?: return null
        return injectionFor(host)?.mode
    }

    /** The YAML scalar [file] is injected into, or null for a fragment of another host (or no fragment). */
    fun hostOf(file: AnsibleJinjaFile): YAMLScalar? =
        InjectedLanguageManager.getInstance(file.project).getInjectionHost(file) as? YAMLScalar

    /** A cheap structural pre-check: the scalar is the value (or a list item) of a key that may hold an expression. */
    private fun mayBeImplicitExpression(scalar: YAMLScalar): Boolean {
        val keyValue = when (val parent = scalar.parent) {
            is YAMLKeyValue -> parent
            is YAMLSequenceItem -> (parent.parent as? YAMLSequence)?.parent as? YAMLKeyValue
            else -> null
        } ?: return false
        return keyValue.keyText in EXPRESSION_KEYS
    }

    /** Whether the task model of [file] evaluates [scalar] as a bare expression (cached per file). */
    private fun isImplicitExpression(file: YAMLFile, scalar: YAMLScalar, facts: PathFacts): Boolean {
        if (!mayBeImplicitExpression(scalar) || !YamlPaths.isTopLevelSequence(file)) return false
        val starts = CachedValuesManager.getCachedValue(file, EXPRESSION_STARTS) {
            CachedValueProvider.Result.create(JinjaBearing.implicitExpressionOffsets(PsiYValueAdapter.documentValue(file), facts), file)
        }
        return scalar.textRange.startOffset in starts
    }
}
