package de.terletzkiy.ansibility.lang.jinja.parser

import com.intellij.lang.ASTNode
import com.intellij.lang.PsiBuilder
import com.intellij.lang.PsiParser
import com.intellij.psi.tree.IElementType

/**
 * The hand-written parser of Ansible Jinja (plan A.5, WU C2): a port of `jinja2/parser.py` 3.1 over the tokens of
 * `AnsibleJinjaLexer` in template mode. Bare expressions (`when:` values) are parsed as templates too: the YAML
 * injector wraps them in `{{ ` and ` }}`, so one grammar serves both.
 */
class AnsibleJinjaParser : PsiParser {
    override fun parse(root: IElementType, builder: PsiBuilder): ASTNode {
        val file = builder.mark()
        JinjaTemplateParsing(builder).parseTemplate()
        file.done(root)
        return builder.treeBuilt
    }
}
