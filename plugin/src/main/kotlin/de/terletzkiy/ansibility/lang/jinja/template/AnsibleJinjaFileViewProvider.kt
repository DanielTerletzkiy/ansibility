package de.terletzkiy.ansibility.lang.jinja.template

import com.intellij.lang.Language
import com.intellij.lang.LanguageParserDefinitions
import com.intellij.lexer.DelegateLexer
import com.intellij.lexer.Lexer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.FileViewProvider
import com.intellij.psi.FileViewProviderFactory
import com.intellij.psi.MultiplePsiFilesPerDocumentFileViewProvider
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.impl.source.PsiFileImpl
import com.intellij.psi.templateLanguages.ConfigurableTemplateLanguageFileViewProvider
import com.intellij.psi.templateLanguages.TemplateDataElementType
import com.intellij.psi.templateLanguages.TemplateLanguageFileViewProvider
import com.intellij.psi.tree.IElementType
import de.terletzkiy.ansibility.lang.jinja.AnsibleJinjaLanguage
import de.terletzkiy.ansibility.lang.jinja.lexer.AnsibleJinjaLexer
import de.terletzkiy.ansibility.lang.jinja.lexer.AnsibleJinjaTokenTypes
import de.terletzkiy.ansibility.lang.jinja.lexer.JinjaLexMode
import de.terletzkiy.ansibility.lang.jinja.psi.AnsibleJinjaElementTypes
import java.util.concurrent.ConcurrentHashMap

/**
 * The view provider of an Ansible Jinja template file (plan A.5, F2.1): two PSI trees over one document, the Jinja
 * tree ([getBaseLanguage]) and the outer-language tree ([getTemplateDataLanguage], chosen by
 * [AnsibleJinjaOuterLanguages]). The outer tree is parsed from the text outside Jinja tags (raw-block bodies included,
 * since they are output verbatim), with the Jinja ranges as outer-language elements.
 *
 * It is a [ConfigurableTemplateLanguageFileViewProvider], so the platform's "Template Data Language" choice (Settings
 * › Languages & Frameworks › Template Data Languages, or the status bar) applies to these files.
 */
class AnsibleJinjaFileViewProvider(
    manager: PsiManager,
    file: VirtualFile,
    eventSystemEnabled: Boolean,
    private val dataLanguage: Language,
) : MultiplePsiFilesPerDocumentFileViewProvider(manager, file, eventSystemEnabled), ConfigurableTemplateLanguageFileViewProvider {

    private val languages: Set<Language> = setOf(AnsibleJinjaLanguage, dataLanguage)

    override fun getBaseLanguage(): Language = AnsibleJinjaLanguage

    override fun getTemplateDataLanguage(): Language = dataLanguage

    override fun getLanguages(): Set<Language> = languages

    /** The outer tree is rebuilt from the whole text on each change; no incremental reparse for either tree. */
    override fun supportsIncrementalReparse(rootLanguage: Language): Boolean = false

    override fun getContentElementType(language: Language): IElementType? =
        if (language == dataLanguage) templateDataElementType(dataLanguage) else null

    override fun cloneInner(fileCopy: VirtualFile): MultiplePsiFilesPerDocumentFileViewProvider =
        AnsibleJinjaFileViewProvider(manager, fileCopy, false, dataLanguage)

    override fun createFile(lang: Language): PsiFile? = when (lang) {
        AnsibleJinjaLanguage -> LanguageParserDefinitions.INSTANCE.forLanguage(AnsibleJinjaLanguage)?.createFile(this)
        dataLanguage -> (LanguageParserDefinitions.INSTANCE.forLanguage(lang)?.createFile(this) as? PsiFileImpl)?.also {
            it.setContentElementType(templateDataElementType(lang))
        }
        else -> null
    }

    companion object {
        private val DATA_TYPES = ConcurrentHashMap<String, TemplateDataElementType>()

        /** The (shared) content element type of the outer tree for [language]. */
        fun templateDataElementType(language: Language): TemplateDataElementType =
            DATA_TYPES.computeIfAbsent(language.id) { AnsibleJinjaTemplateDataElementType(language) }
    }
}

/**
 * Builds the outer-language tree of a template: everything except Jinja tags, comments and raw-tag delimiters is
 * outer text; each Jinja run becomes one [AnsibleJinjaElementTypes.OUTER_FRAGMENT] leaf in the outer tree.
 */
internal class AnsibleJinjaTemplateDataElementType(language: Language) : TemplateDataElementType(
    "ANSIBLE_JINJA_TEMPLATE_DATA",
    language,
    AnsibleJinjaTokenTypes.TEXT,
    AnsibleJinjaElementTypes.OUTER_FRAGMENT,
) {
    override fun createBaseLexer(viewProvider: TemplateLanguageFileViewProvider): Lexer = OuterTextLexer()

    /** The template lexer with raw-block bodies reported as outer text. */
    private class OuterTextLexer : DelegateLexer(AnsibleJinjaLexer(JinjaLexMode.TEMPLATE)) {
        override fun getTokenType(): IElementType? {
            val type = super.getTokenType()
            return if (type == AnsibleJinjaTokenTypes.RAW_TEXT) AnsibleJinjaTokenTypes.TEXT else type
        }
    }
}

/** `lang.fileViewProviderFactory` for `AnsibleJinja`: every Ansible Jinja file gets an [AnsibleJinjaFileViewProvider]. */
class AnsibleJinjaFileViewProviderFactory : FileViewProviderFactory {
    override fun createFileViewProvider(
        file: VirtualFile,
        language: Language?,
        manager: PsiManager,
        eventSystemEnabled: Boolean,
    ): FileViewProvider = AnsibleJinjaFileViewProvider(
        manager,
        file,
        eventSystemEnabled,
        AnsibleJinjaOuterLanguages.templateDataLanguage(manager.project, file),
    )
}
