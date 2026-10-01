package de.terletzkiy.ansibility.lang.jinja.editor

import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.impl.source.tree.injected.InjectedLanguageEditorUtil
import com.intellij.psi.util.PsiTreeUtil
import de.terletzkiy.ansibility.dispatch.SiteDispatch
import de.terletzkiy.ansibility.index.JinjaBearing
import de.terletzkiy.ansibility.lang.jinja.lexer.AnsibleJinjaTokenTypes
import de.terletzkiy.ansibility.lang.jinja.template.AnsibleJinjaFileViewProvider
import de.terletzkiy.ansibility.settings.AnsibilityAppSettings
import de.terletzkiy.ansibility.settings.JinjaSettings
import org.jetbrains.yaml.YAMLLanguage
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLScalar
import org.jetbrains.yaml.psi.YAMLScalarList
import org.jetbrains.yaml.psi.YAMLScalarText
import org.jetbrains.yaml.psi.YAMLSequenceItem

/**
 * Where the Jinja typing assistance of plan X35 applies, in the host document: the whole text of an Ansible Jinja
 * template file, or one scalar of an Ansible YAML file (a file inside an Ansible root with an Ansible file kind, the
 * gate of the YAML injector). Inside a scalar only template mode applies, so the bare expressions of `when`,
 * `changed_when`, `failed_when`, `until` and `assert`'s `that` get no `{{ }}` (plan X30), and vault and `!unsafe`
 * scalars are left alone.
 *
 * Editors and files of injected fragments are mapped to their host, so the assistance works the same with and
 * without the Jinja injection.
 *
 * @property editor the top-level (host) editor.
 * @property region the template text: the whole document, or the scalar's range (quotes included).
 */
class JinjaTypingContext private constructor(
    val editor: Editor,
    val region: TextRange,
    val kind: Kind,
) {
    /** What the [region] is. */
    enum class Kind {
        /** An Ansible Jinja template file. */
        TEMPLATE_FILE,

        /** A plain or quoted scalar of an Ansible YAML file (lines are folded: no end tags on Enter). */
        YAML_SCALAR,

        /** A literal (`|`) or folded (`>`) block scalar of an Ansible YAML file. */
        YAML_BLOCK_SCALAR,
    }

    val text: CharSequence
        get() = editor.document.charsSequence

    /** Whether template lines are kept: a template file or a block scalar (where Enter may insert end tags). */
    val keepsLines: Boolean
        get() = kind != Kind.YAML_SCALAR

    /** The template token covering [offset], or null outside the region. */
    fun tokenAt(offset: Int): JinjaScannedToken? = JinjaTagScanner.tokenAt(text, region.startOffset, region.endOffset, offset)

    /** Whether the character at [offset] is outer text: text outside tags, or the body of a raw block. */
    fun isOuterText(offset: Int): Boolean = tokenAt(offset)?.type in AnsibleJinjaTokenTypes.OUTER_TEXT

    /** The `{% … %}` tags of the region. */
    fun tags(): List<JinjaScannedTag> = JinjaTagScanner.tags(text, region.startOffset, region.endOffset)

    companion object {
        /** The application's Jinja settings (the X35 switches). */
        fun settings(): JinjaSettings = AnsibilityAppSettings.getInstance().settings.jinja

        /**
         * The context of the character at [probeOffset] (a host offset) for [editor] showing [file], or null when the
         * assistance does not apply there. [editor] and [file] may be an injected editor and fragment.
         *
         * Template files need no PSI. For YAML the document is committed first, so this must run where a commit is
         * allowed (typing and Enter handlers on the EDT).
         */
        fun find(project: Project, editor: Editor, file: PsiFile, probeOffset: Int): JinjaTypingContext? {
            if (project.isDisposed) return null
            val hostEditor = InjectedLanguageEditorUtil.getTopLevelEditor(editor)
            val hostFile = InjectedLanguageManager.getInstance(project).getTopLevelFile(file) ?: file
            val document = hostEditor.document
            if (probeOffset < 0 || probeOffset > document.textLength) return null
            if (hostFile.viewProvider is AnsibleJinjaFileViewProvider) {
                return JinjaTypingContext(hostEditor, TextRange(0, document.textLength), Kind.TEMPLATE_FILE)
            }
            if (hostFile.language != YAMLLanguage.INSTANCE || !SiteDispatch.isCompletionTarget(SiteDispatch.contextOf(hostFile))) return null
            val documents = PsiDocumentManager.getInstance(project)
            if (documents.getPsiFile(document) != hostFile) return null
            documents.commitDocument(document)
            val scalar = PsiTreeUtil.getParentOfType(hostFile.findElementAt(probeOffset), YAMLScalar::class.java, false) ?: return null
            if (!isTemplateScalar(scalar)) return null
            val kind = if (scalar is YAMLScalarList || scalar is YAMLScalarText) Kind.YAML_BLOCK_SCALAR else Kind.YAML_SCALAR
            return JinjaTypingContext(hostEditor, scalar.textRange, kind)
        }

        /** Not a vault or `!unsafe` scalar, and not the bare expression of an implicit-expression key. */
        private fun isTemplateScalar(scalar: YAMLScalar): Boolean {
            if (scalar.tag?.text in JinjaBearing.NON_TEMPLATED_TAGS) return false
            val keyValue = when (val parent = scalar.parent) {
                is YAMLKeyValue -> parent
                is YAMLSequenceItem -> parent.parent?.parent as? YAMLKeyValue
                else -> null
            }
            return keyValue?.keyText !in BARE_EXPRESSION_KEYS
        }

        /** Keys whose values are bare expressions: the implicit-expression keys and `assert`'s `that`. */
        private val BARE_EXPRESSION_KEYS: Set<String> = JinjaBearing.IMPLICIT_EXPRESSION_KEYS + "that"
    }
}
