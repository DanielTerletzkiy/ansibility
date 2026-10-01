package de.terletzkiy.ansibility.lang.jinja.psi

import com.intellij.extapi.psi.PsiFileBase
import com.intellij.openapi.fileTypes.FileType
import com.intellij.psi.FileViewProvider
import com.intellij.psi.util.PsiTreeUtil
import de.terletzkiy.ansibility.lang.jinja.AnsibleJinjaFileType
import de.terletzkiy.ansibility.lang.jinja.AnsibleJinjaLanguage

/**
 * The Jinja tree of a template file (the base language root of its multi-root view provider) or of a Jinja fragment
 * injected into a YAML scalar.
 */
class AnsibleJinjaFile(viewProvider: FileViewProvider) : PsiFileBase(viewProvider, AnsibleJinjaLanguage) {
    override fun getFileType(): FileType = AnsibleJinjaFileType

    /** The top-level statements, in source order. */
    val statements: List<JinjaStatement>
        get() = PsiTreeUtil.getChildrenOfTypeAsList(this, JinjaStatement::class.java)

    /** The top-level output tags, in source order. */
    val outputTags: List<JinjaOutputTag>
        get() = PsiTreeUtil.getChildrenOfTypeAsList(this, JinjaOutputTag::class.java)

    override fun toString(): String = "AnsibleJinjaFile:$name"
}
