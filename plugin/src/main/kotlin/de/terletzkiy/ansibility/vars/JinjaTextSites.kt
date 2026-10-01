package de.terletzkiy.ansibility.vars

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiTreeUtil
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.FileContext
import de.terletzkiy.ansibility.api.FileKind
import de.terletzkiy.ansibility.api.JinjaContainer
import de.terletzkiy.ansibility.index.JinjaBearing
import de.terletzkiy.ansibility.index.PathFacts
import de.terletzkiy.ansibility.lang.jinja.lexer.AnsibleJinjaLexer
import de.terletzkiy.ansibility.lang.jinja.lexer.AnsibleJinjaTokenTypes
import de.terletzkiy.ansibility.lang.jinja.lexer.JinjaLexMode
import de.terletzkiy.ansibility.lang.jinja.refs.JinjaLocal
import de.terletzkiy.ansibility.lang.jinja.refs.JinjaLocalKind
import de.terletzkiy.ansibility.lang.jinja.refs.JinjaRefs
import de.terletzkiy.ansibility.lang.jinja.refs.JinjaRefsResult
import de.terletzkiy.ansibility.lang.jinja.refs.JinjaVarRef
import de.terletzkiy.ansibility.model.task.YamlFiles
import de.terletzkiy.ansibility.yaml.PsiYValueAdapter
import de.terletzkiy.ansibility.yaml.YamlPaths
import de.terletzkiy.ansibility.yaml.YamlPsi
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLScalar

/**
 * Text-level Jinja in the host file (plan A.4 `TextJinjaLocator`): the Jinja text around an offset, analysed with
 * [JinjaRefs], with the mapping between analysed-text offsets and host-file offsets.
 *
 * Two shapes are recognised, the same way the `ansible.var.use` indexer reads them:
 * - **template files**: `FileKind.ROLE_TEMPLATE`, or any `*.j2` file inside a root, whatever file type the IDE gives
 *   it (YAML through a `*.j2 → YAML` mapping, plain text, PyCharm's Jinja2, AnsibleJinja): the whole text is one
 *   template, and offsets are file offsets;
 * - **YAML scalars** of Ansible files that pass [JinjaBearing.isJinjaBearingScalar]: a scalar containing `{{`/`{%` is a
 *   template; an implicit-expression value (`when`, `changed_when`, `failed_when`, `until`, `assert.that` items,
 *   `debug.var`) is one bare expression. The scalar is decoded through its own escaper (quotes, escapes, folding), and
 *   offsets map back through it.
 *
 * `{% raw %}` bodies, comments and string literals never yield references ([JinjaRefs] skips them). Call in a read
 * action.
 */
internal object JinjaTextSites {
    private val TEMPLATE_ANALYSIS = Key.create<CachedValue<JinjaRefsResult>>("ansibility.vars.templateRefs")
    private val EXPRESSION_STARTS = Key.create<CachedValue<Set<Int>>>("ansibility.vars.implicitExpressions")

    /** Kinds whose YAML scalars Ansible never templates (their `{{ }}` is documentation or tool data). */
    private val NOT_TEMPLATED = setOf(
        FileKind.ROLE_ARGSPEC, FileKind.ANSIBLE_CFG, FileKind.LINT_CONFIG, FileKind.REQUIREMENTS, FileKind.ROLE_FILE,
    )

    /**
     * One analysed piece of Jinja: [result] is the analysis of the text, [textOffset] the caret in that text,
     * [container] where the text sits, and [toHost] maps a text offset to a host-file offset.
     */
    class Analysis(
        /** The analysed text: the whole template, or the decoded scalar. */
        val text: CharSequence,
        val result: JinjaRefsResult,
        val textOffset: Int,
        val container: JinjaContainer,
        private val hostOffsets: IntArray?,
        private val hostBase: Int,
    ) {
        /** The host-file offset of [textOffset] in the analysed text. */
        fun toHost(textOffset: Int): Int {
            val offsets = hostOffsets ?: return hostBase + textOffset
            return hostBase + offsets[textOffset.coerceIn(0, offsets.lastIndex)]
        }

        /** The host-file range of [range] in the analysed text. */
        fun toHost(range: TextRange): TextRange = TextRange(toHost(range.startOffset), toHost(range.endOffset))

        /** The reference whose range contains the caret (end inclusive), free or local. */
        val reference: JinjaVarRef? get() = result.referenceAt(textOffset)

        /** Bare names bound locally where the caret is (namespace attributes are not bare names). */
        val localNames: Set<String>
            get() = result.localsVisibleAt(textOffset).filter { it.kind != JinjaLocalKind.NAMESPACE_ATTRIBUTE }.mapTo(HashSet()) { it.name }

        /** The local [ref] resolves to, or the innermost visible local called [name]. */
        fun localOf(ref: JinjaVarRef?, name: String): JinjaLocal? =
            ref?.local ?: result.localsVisibleAt(textOffset).firstOrNull { it.name == name && it.kind != JinjaLocalKind.NAMESPACE_ATTRIBUTE }
    }

    /** The Jinja around [offset] of [file] (a host file), or null when the offset is in no Jinja text. */
    fun analysisAt(file: PsiFile, offset: Int): Analysis? {
        val virtualFile = file.originalFile.viewProvider.virtualFile
        val context = AnsibleWorkspace.getInstance(file.project).contextOf(virtualFile) ?: return null
        if (isTemplateFile(virtualFile, context)) return template(file, offset)
        if (context.kind in NOT_TEMPLATED) return null
        // Vars files Ansible loads without a YAML name (or typed otherwise by the IDE) are read through their YAML view.
        val yaml = file as? YAMLFile ?: YamlFiles.yamlFile(file.project, virtualFile) ?: return null
        return scalar(yaml, virtualFile, offset)
    }

    /** True for template files: role templates and any `*.j2` file inside a root. */
    fun isTemplateFile(file: VirtualFile, context: FileContext): Boolean =
        context.kind == FileKind.ROLE_TEMPLATE || PathFacts.isJ2(file.name)

    private fun template(file: PsiFile, offset: Int): Analysis? {
        val text = file.viewProvider.contents
        if (offset < 0 || offset > text.length) return null
        val result = CachedValuesManager.getCachedValue(file, TEMPLATE_ANALYSIS) {
            CachedValueProvider.Result.create(JinjaRefs.analyze(file.viewProvider.contents, JinjaLexMode.TEMPLATE), file)
        }
        return Analysis(text, result, offset, JinjaContainer.TEMPLATE_FILE, null, 0)
    }

    private fun scalar(file: YAMLFile, virtualFile: VirtualFile, offset: Int): Analysis? {
        val scalar = scalarAt(file, offset) ?: return null
        val facts = PathFacts.of(virtualFile)
        val start = scalar.textRange.startOffset
        // Only task lists and playbooks (top-level sequences) have implicit-expression keys.
        val expression = YamlPaths.isTopLevelSequence(file) && start in implicitExpressionStarts(file, facts)
        if (!expression && !JinjaBearing.hasTemplateMarkers(scalar.text)) return null
        if (!JinjaBearing.isJinjaBearingScalar(facts, YamlPaths.keyPath(scalar), YamlPsi.tagOf(scalar))) return null
        val escaper = scalar.createLiteralTextEscaper()
        val range = escaper.relevantTextRange
        val decoded = StringBuilder()
        escaper.decode(range, decoded) // like the `ansible.var.use` indexer, a bad escape keeps the text decoded so far
        val template = JinjaBearing.hasTemplateMarkers(decoded)
        if (!expression && !template) return null
        val hostOffsets = IntArray(decoded.length + 1) { escaper.getOffsetInHost(it, range) }
        val caret = textOffsetOf(hostOffsets, offset - start) ?: return null
        val mode = if (template) JinjaLexMode.TEMPLATE else JinjaLexMode.EXPRESSION
        val container = if (template) JinjaContainer.YAML_TEMPLATE else JinjaContainer.YAML_EXPRESSION
        return Analysis(decoded, JinjaRefs.analyze(decoded, mode), caret, container, fillGaps(hostOffsets), start)
    }

    /** The scalar value at [offset], also when the caret sits right after its last character. */
    private fun scalarAt(file: YAMLFile, offset: Int): YAMLScalar? {
        for (candidate in listOf(offset, offset - 1)) {
            if (candidate < 0) continue
            val scalar = PsiTreeUtil.getParentOfType(file.findElementAt(candidate), YAMLScalar::class.java, false) ?: continue
            if (scalar.textRange.containsOffset(offset)) return scalar
        }
        return null
    }

    /** The implicit-expression scalar starts of [file], cached until the file changes (the shared predicate of plan A.7). */
    private fun implicitExpressionStarts(file: YAMLFile, facts: PathFacts): Set<Int> =
        CachedValuesManager.getCachedValue(file, EXPRESSION_STARTS) {
            val starts = JinjaBearing.implicitExpressionOffsets(PsiYValueAdapter.documentValue(file), facts)
            CachedValueProvider.Result.create(starts, file)
        }

    /** The decoded-text offset of the host offset [inHost] (relative to the scalar), or null when it is outside the text. */
    private fun textOffsetOf(hostOffsets: IntArray, inHost: Int): Int? {
        var best: Int? = null
        for (i in hostOffsets.indices) {
            ProgressManager.checkCanceled()
            val host = hostOffsets[i]
            if (host < 0) continue
            if (host > inHost) break
            best = i
        }
        return best
    }

    /** Replaces unmapped (-1) entries with the previous mapped offset, so every text offset maps somewhere sensible. */
    private fun fillGaps(offsets: IntArray): IntArray {
        var last = offsets.firstOrNull { it >= 0 } ?: 0
        for (i in offsets.indices) {
            if (offsets[i] < 0) offsets[i] = last else last = offsets[i]
        }
        return offsets
    }

    /**
     * The accessors of [ref] the caret covers, as the end offset (in [text]) of each accessor up to and including
     * the one under [caret]: empty on the root name, one entry on the first `.attr`/`['key']`/`[0]`, and so on.
     */
    fun coveredSegmentEnds(text: CharSequence, ref: JinjaVarRef, caret: Int): List<Int> {
        if (ref.attrPath.isEmpty() || caret <= ref.nameRange.endOffset) return emptyList()
        val ends = segmentEnds(text, ref).take(ref.attrPath.size)
        val index = ends.indexOfFirst { caret <= it }
        return if (index < 0) ends else ends.subList(0, index + 1)
    }

    /** The end offsets (in [text]) of the accessor segments of [ref], in order. */
    private fun segmentEnds(text: CharSequence, ref: JinjaVarRef): List<Int> {
        val lexer = AnsibleJinjaLexer(JinjaLexMode.EXPRESSION)
        lexer.start(text, ref.nameRange.endOffset, ref.range.endOffset, 0)
        val ends = ArrayList<Int>(ref.attrPath.size)
        var pendingDot = false
        var inBracket = false
        while (lexer.tokenType != null) {
            val type = lexer.tokenType
            when {
                type == AnsibleJinjaTokenTypes.WHITE_SPACE -> Unit
                type == AnsibleJinjaTokenTypes.DOT -> pendingDot = true
                type == AnsibleJinjaTokenTypes.LBRACKET -> inBracket = true
                type == AnsibleJinjaTokenTypes.RBRACKET && inBracket -> {
                    inBracket = false
                    ends += lexer.tokenEnd
                }
                pendingDot -> {
                    pendingDot = false
                    ends += lexer.tokenEnd
                }
                else -> Unit
            }
            lexer.advance()
        }
        return ends
    }
}
