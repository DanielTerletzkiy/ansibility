package de.terletzkiy.ansibility.inspections.undefined

import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import de.terletzkiy.ansibility.lang.jinja.AnsibleJinjaLanguage
import de.terletzkiy.ansibility.lang.jinja.injection.JinjaInjectionMode
import de.terletzkiy.ansibility.lang.jinja.injection.JinjaYamlInjections
import de.terletzkiy.ansibility.lang.jinja.lexer.JinjaLexMode
import de.terletzkiy.ansibility.lang.jinja.psi.AnsibleJinjaFile
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLScalar

/**
 * One free variable use in its host file (a template, or a task, vars or play file whose scalar carries Jinja), with
 * every range in host-file offsets.
 */
class SiteUse internal constructor(
    /** The analysis in the coordinates of the analysed text. */
    val raw: RawUse,
    /** The root name's host range. */
    val nameRange: TextRange,
    /** The host range of the name and its constant accessors. */
    val pathRange: TextRange,
    /** The host offset after which `| default(…)` can be appended, or -1 when a dynamic accessor or call follows. */
    val appendOffset: Int,
    /** The host range of the template statement a definedness guard would wrap (template files only), or null. */
    val statementRange: TextRange?,
    /** The YAML scalar that holds the use, or null in a template file. */
    val scalar: YAMLScalar?,
    /** Whether the answer comes from the PSI (true) or from the text-level fallback. */
    val fromPsi: Boolean,
) {
    val name: String get() = raw.name

    override fun toString(): String = "SiteUse($name@$nameRange${if (raw.guarded) " guarded" else ""})"
}

/**
 * Collects the [SiteUse]s of a host file (plan amendment R7/R8, F8.12 (a)). The PSI path reads the Ansible Jinja tree:
 * the template file's own tree, or the fragment the YAML injector put into each Jinja-bearing scalar
 * ([JinjaYamlInjections.injectedFile]). Where no such tree exists, the text-level [TextJinjaUses] reads the same text:
 * the whole template, or the scalar's decoded value through its own escaper (the mapping `vars.JinjaTextSites` uses).
 * Call in a read action.
 */
internal object UseSites {
    /**
     * The uses of a template file [file] (any file type), in source order; [usePsi] false forces the text level (the
     * parity tests compare both).
     */
    fun template(file: PsiFile, rules: GuardRules, usePsi: Boolean = true): List<SiteUse> {
        val jinja = if (usePsi) file.viewProvider.getPsi(AnsibleJinjaLanguage) as? AnsibleJinjaFile else null
        if (jinja != null) {
            return PsiJinjaUses.collect(jinja, rules).map { raw ->
                SiteUse(raw, raw.nameRange, raw.pathRange, appendOffset(raw), raw.statement, null, fromPsi = true)
            }
        }
        return TextJinjaUses.collect(file.viewProvider.contents, JinjaLexMode.TEMPLATE, rules).map { raw ->
            SiteUse(raw, raw.nameRange, raw.pathRange, appendOffset(raw), raw.statement, null, fromPsi = false)
        }
    }

    /** The uses in the Jinja-bearing scalars of [file], in source order; [usePsi] false forces the text level. */
    fun yaml(file: YAMLFile, rules: GuardRules, usePsi: Boolean = true): List<SiteUse> {
        val uses = ArrayList<SiteUse>()
        for (scalar in PsiTreeUtil.findChildrenOfType(file, YAMLScalar::class.java)) {
            ProgressManager.checkCanceled()
            val injection = JinjaYamlInjections.injectionFor(scalar) ?: continue
            val fragment = if (usePsi) JinjaYamlInjections.injectedFile(scalar) else null
            if (fragment != null) uses += fromFragment(scalar, fragment, rules) else uses += fromText(scalar, injection, rules)
        }
        return uses
    }

    private fun fromFragment(scalar: YAMLScalar, fragment: AnsibleJinjaFile, rules: GuardRules): List<SiteUse> {
        val manager = InjectedLanguageManager.getInstance(scalar.project)
        return PsiJinjaUses.collect(fragment, rules).map { raw ->
            val path = manager.injectedToHost(fragment, raw.pathRange)
            SiteUse(
                raw = raw,
                nameRange = manager.injectedToHost(fragment, raw.nameRange),
                pathRange = path,
                appendOffset = if (raw.appendable) path.endOffset else -1,
                statementRange = null,
                scalar = scalar,
                fromPsi = true,
            )
        }
    }

    private fun fromText(scalar: YAMLScalar, injection: JinjaYamlInjections.Injection, rules: GuardRules): List<SiteUse> {
        val escaper = scalar.createLiteralTextEscaper()
        val range = escaper.relevantTextRange
        val decoded = StringBuilder()
        escaper.decode(range, decoded)
        val offsets = IntArray(decoded.length + 1) { escaper.getOffsetInHost(it, range) }
        fillGaps(offsets)
        val base = scalar.textRange.startOffset
        fun host(offset: Int): Int = base + offsets[offset.coerceIn(0, offsets.lastIndex)]
        fun host(range: TextRange) = TextRange(host(range.startOffset), host(range.endOffset))
        val mode = if (injection.mode == JinjaInjectionMode.EXPRESSION) JinjaLexMode.EXPRESSION else JinjaLexMode.TEMPLATE
        return TextJinjaUses.collect(decoded, mode, rules).map { raw ->
            val path = host(raw.pathRange)
            SiteUse(raw, host(raw.nameRange), path, if (raw.appendable) path.endOffset else -1, null, scalar, fromPsi = false)
        }
    }

    private fun appendOffset(raw: RawUse): Int = if (raw.appendable) raw.pathRange.endOffset else -1

    /** Replaces unmapped (-1) entries with the previous mapped offset. */
    private fun fillGaps(offsets: IntArray) {
        var last = offsets.firstOrNull { it >= 0 } ?: 0
        for (i in offsets.indices) {
            if (offsets[i] < 0) offsets[i] = last else last = offsets[i]
        }
    }
}
