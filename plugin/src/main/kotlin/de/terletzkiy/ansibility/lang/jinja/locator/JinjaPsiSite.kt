package de.terletzkiy.ansibility.lang.jinja.locator

import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.JinjaContainer
import de.terletzkiy.ansibility.lang.jinja.AnsibleJinjaLanguage
import de.terletzkiy.ansibility.lang.jinja.injection.JinjaInjectionMode
import de.terletzkiy.ansibility.lang.jinja.injection.JinjaYamlInjections
import de.terletzkiy.ansibility.lang.jinja.psi.AnsibleJinjaFile
import org.jetbrains.yaml.YAMLLanguage
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLScalar

/**
 * A caret position inside Ansible Jinja PSI, found from a host file and a host offset (plan A.4: sites are in host
 * coordinates): the Jinja tree of a template file, or the fragment the YAML injector put into the scalar at the offset.
 *
 * [offset] is the caret in [file]'s coordinates; [toHost] maps Jinja offsets back to the host file. For an injected
 * fragment the mapping is the host scalar's own escaper (the injected text is its decoded value, after the
 * [JinjaInjectionMode.prefix] of expression mode), exactly as `vars.JinjaTextSites` maps its decoded text, so both
 * locators report the same host ranges.
 */
class JinjaPsiSite private constructor(
    /** The Jinja tree: a template file's base tree, or an injected fragment. */
    val file: AnsibleJinjaFile,
    /** The caret in [file]'s coordinates. */
    val offset: Int,
    val container: JinjaContainer,
    /** Host offsets of the decoded value's offsets (null for template files, whose offsets are host offsets). */
    private val hostOffsets: IntArray?,
    /** The host offset of the scalar the fragment is injected into. */
    private val hostBase: Int,
    /** The fragment offset of the value's first character (the expression-mode prefix). */
    private val prefixLength: Int,
) {
    /** The host offset of the Jinja offset [jinjaOffset] (offsets in the prefix or suffix map to the value's ends). */
    fun toHost(jinjaOffset: Int): Int {
        val offsets = hostOffsets ?: return jinjaOffset
        return hostBase + offsets[(jinjaOffset - prefixLength).coerceIn(0, offsets.lastIndex)]
    }

    /** The host range of the Jinja range [range]. */
    fun toHost(range: TextRange): TextRange = TextRange(toHost(range.startOffset), toHost(range.endOffset))

    override fun toString(): String = "JinjaPsiSite($container, ${file.name}@$offset)"

    companion object {
        /**
         * The Jinja PSI at [offset] of [file] (a host file; an injected fragment is mapped to its host first), or null
         * outside Ansible roots, outside Jinja, and when the Jinja text has no PSI (a `.j2` file of another file type,
         * a scalar the injector skips): callers then fall back to the text level.
         */
        fun at(file: PsiFile, offset: Int): JinjaPsiSite? {
            val manager = InjectedLanguageManager.getInstance(file.project)
            if (manager.isInjectedFragment(file)) {
                val host = manager.getTopLevelFile(file) ?: return null
                return at(host, manager.injectedToHost(file, offset))
            }
            val virtualFile = file.originalFile.viewProvider.virtualFile
            AnsibleWorkspace.getInstance(file.project).contextOf(virtualFile) ?: return null
            val viewProvider = file.viewProvider
            if (viewProvider.baseLanguage == AnsibleJinjaLanguage) {
                val template = viewProvider.getPsi(AnsibleJinjaLanguage) as? AnsibleJinjaFile ?: return null
                if (offset < 0 || offset > template.textLength) return null
                return JinjaPsiSite(template, offset, JinjaContainer.TEMPLATE_FILE, null, 0, 0)
            }
            val yaml = file as? YAMLFile ?: viewProvider.getPsi(YAMLLanguage.INSTANCE) as? YAMLFile ?: return null
            val scalar = scalarAt(yaml, offset) ?: return null
            return injected(scalar, offset)
        }

        private fun injected(scalar: YAMLScalar, hostOffset: Int): JinjaPsiSite? {
            val fragment = JinjaYamlInjections.injectedFile(scalar) ?: return null
            val mode = JinjaYamlInjections.modeOf(fragment) ?: return null
            val escaper = scalar.createLiteralTextEscaper()
            val range = escaper.relevantTextRange
            val decoded = StringBuilder()
            if (!escaper.decode(range, decoded)) return null
            // the fragment must be the value as decoded now (stale injections are skipped, the text level answers)
            if (fragment.textLength != mode.prefixLength + decoded.length + mode.suffixLength) return null
            val hostOffsets = IntArray(decoded.length + 1) { escaper.getOffsetInHost(it, range) }
            val start = scalar.textRange.startOffset
            val caret = decodedOffsetOf(hostOffsets, hostOffset - start) ?: return null
            return JinjaPsiSite(fragment, caret + mode.prefixLength, mode.container, fillGaps(hostOffsets), start, mode.prefixLength)
        }

        /** The scalar at [offset], also when the caret sits right after its last character. */
        private fun scalarAt(file: YAMLFile, offset: Int): YAMLScalar? {
            for (candidate in intArrayOf(offset, offset - 1)) {
                if (candidate < 0) continue
                val scalar = PsiTreeUtil.getParentOfType(file.findElementAt(candidate), YAMLScalar::class.java, false) ?: continue
                if (scalar.textRange.containsOffset(offset)) return scalar
            }
            return null
        }

        /** The largest decoded offset whose (mapped) host offset is at most [inHost] (relative to the scalar), or null. */
        private fun decodedOffsetOf(hostOffsets: IntArray, inHost: Int): Int? {
            var best: Int? = null
            for (i in hostOffsets.indices) {
                if (i and 0xFF == 0) ProgressManager.checkCanceled()
                val host = hostOffsets[i]
                if (host < 0) continue
                if (host > inHost) break
                best = i
            }
            return best
        }

        /** Replaces unmapped (-1) entries with the previous mapped offset. */
        private fun fillGaps(offsets: IntArray): IntArray {
            var last = offsets.firstOrNull { it >= 0 } ?: 0
            for (i in offsets.indices) {
                if (offsets[i] < 0) offsets[i] = last else last = offsets[i]
            }
            return offsets
        }
    }
}
