package de.terletzkiy.ansibility.lang.jinja.editor

import com.intellij.codeInsight.highlighting.BraceMatchingUtil
import com.intellij.codeInsight.highlighting.CodeBlockSupportHandler
import com.intellij.lang.folding.LanguageFolding
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import de.terletzkiy.ansibility.lang.jinja.AnsibleJinjaLanguage
import de.terletzkiy.ansibility.lang.jinja.highlighting.AnsibleJinjaColorSettingsPage
import de.terletzkiy.ansibility.lang.jinja.highlighting.AnsibleJinjaHighlighterColors
import de.terletzkiy.ansibility.lang.jinja.highlighting.AnsibleJinjaSyntaxHighlighter
import de.terletzkiy.ansibility.lang.jinja.lexer.AnsibleJinjaLexer
import de.terletzkiy.ansibility.lang.jinja.lexer.AnsibleJinjaTokenTypes
import de.terletzkiy.ansibility.lang.jinja.psi.AnsibleJinjaFile

/** F2.1 basic language support: brace matching, block keyword matching, commenter, folding and the colour page. */
class AnsibleJinjaEditorStructureTest : JinjaEditorTestCase() {
    private val text = """
        |{# Rendered by Ansible
        |   for {{ inventory_hostname }} #}
        |{% if ssl %}
        |listen {{ (port | int) + offsets[0] }};
        |{% elif plain %}
        |listen 80;
        |{% else %}
        |{%- for host in hosts %}
        |server {{ host }};
        |{%- endfor %}
        |{% endif %}
        |{% raw %}
        |{{ .Labels.host }}
        |{% endraw %}
        |""".trimMargin()

    fun testBraceMatching() {
        openTemplate("site.conf.j2", text)
        fun matched(open: String, close: String, from: Int = 0) {
            val start = text.indexOf(open, from)
            val iterator = (myFixture.editor as EditorEx).highlighter.createIterator(start)
            assertTrue(open, BraceMatchingUtil.matchBrace(text, myFixture.file.fileType, iterator, true))
            assertEquals(open, text.indexOf(close, start), iterator.start)
        }
        matched("{{ (", "}};")
        matched("(port", ") +")
        matched("[0]", "]")
        matched("{%- for", "%}\nserver")
    }

    fun testBlockKeywordsAndCommentDelimiters() {
        openTemplate("site.conf.j2", text)
        val file = myFixture.file
        fun markers(offset: Int) = CodeBlockSupportHandler.findMarkersRanges(file, AnsibleJinjaLanguage, offset).map { text.substring(it.startOffset, it.endOffset) }
        val expected = listOf("if", "elif", "else", "endif")
        assertEquals(expected, markers(text.indexOf("if ssl")))
        assertEquals(expected, markers(text.indexOf("endif")))
        assertEquals(expected, markers(text.indexOf("{% else") + 1))
        assertEquals("in the condition", emptyList<String>(), markers(text.indexOf("ssl")))
        assertEquals(listOf("for", "endfor"), markers(text.indexOf("for host")))
        assertEquals(listOf("raw", "endraw"), markers(text.indexOf("raw")))
        assertEquals(listOf("{#", "#}"), markers(text.indexOf("Rendered")))
        val forStatement = TextRange(text.indexOf("{%- for"), text.indexOf("{%- endfor") + "{%- endfor %}".length)
        val element = myFixture.file.viewProvider.findElementAt(text.indexOf("server {{"), AnsibleJinjaLanguage)!!
        assertEquals(forStatement, CodeBlockSupportHandler.EP.forLanguage(AnsibleJinjaLanguage).getCodeBlockRange(element))
    }

    fun testFolding() {
        openTemplate("site.conf.j2", text)
        val jinja = myFixture.file.viewProvider.getPsi(AnsibleJinjaLanguage) as AnsibleJinjaFile
        val document = PsiDocumentManager.getInstance(project).getDocument(jinja)!!
        val regions = LanguageFolding.buildFoldingDescriptors(LanguageFolding.INSTANCE.forLanguage(AnsibleJinjaLanguage), jinja, document, false)
            .map { text.substring(it.range.startOffset, it.range.endOffset) to it.placeholderText }
        val ifBody = text.substring(text.indexOf("{% if ssl %}") + "{% if ssl %}".length, text.indexOf("{% endif %}"))
        val forBody = text.substring(text.indexOf("{%- for host in hosts %}") + "{%- for host in hosts %}".length, text.indexOf("{%- endfor"))
        assertContainsElements(
            regions,
            ifBody to "...",
            forBody to "...",
            "\n{{ .Labels.host }}\n" to "...",
            text.substring(0, text.indexOf("#}") + 2) to "{#...#}",
        )
        assertEquals(regions.toString(), 4, regions.size)
    }

    fun testUnclosedBlockDoesNotFold() {
        openTemplate("site.conf.j2", "{% if a %}\nx\ny\n")
        val jinja = myFixture.file.viewProvider.getPsi(AnsibleJinjaLanguage)!!
        val document = PsiDocumentManager.getInstance(project).getDocument(jinja)!!
        assertEmpty(AnsibleJinjaFoldingBuilder().buildFoldRegions(jinja, document, false))
    }

    /** Ctrl+/ in a template writes a Jinja comment, also on outer-language (YAML) lines: it is removed when rendering. */
    fun testCommentLineWritesJinjaComments() {
        openTemplate("compose.yml.j2", "services:\n  app: <caret>{{ app }}\n{% if debug %}\n")
        myFixture.performEditorAction(IdeActions.ACTION_COMMENT_LINE)
        myFixture.performEditorAction(IdeActions.ACTION_COMMENT_LINE)
        assertEquals("services:\n{#   app: {{ app }} #}\n{# {% if debug %} #}\n", myFixture.editor.document.text)
        myFixture.editor.caretModel.moveToOffset(myFixture.editor.document.text.indexOf("app"))
        myFixture.performEditorAction(IdeActions.ACTION_COMMENT_LINE)
        myFixture.performEditorAction(IdeActions.ACTION_COMMENT_LINE)
        assertEquals("services:\n  app: {{ app }}\n{% if debug %}\n", myFixture.editor.document.text)
    }

    /** Uncommenting a whitespace-controlled comment removes its markers too. */
    fun testUncommentMarkerComment() {
        openTemplate("site.conf.j2", "{#- listen 80; -#}\n")
        myFixture.performEditorAction(IdeActions.ACTION_COMMENT_LINE)
        assertEquals("listen 80;\n", myFixture.editor.document.text)
    }

    fun testCommentSelectionAsBlock() {
        openTemplate("site.conf.j2", "listen <selection>80</selection>;\n")
        myFixture.performEditorAction(IdeActions.ACTION_COMMENT_BLOCK)
        assertEquals("listen {# 80 #};\n", myFixture.editor.document.text)
        myFixture.editor.caretModel.moveToOffset(myFixture.editor.document.text.indexOf("80"))
        myFixture.performEditorAction(IdeActions.ACTION_COMMENT_BLOCK)
        assertEquals("listen 80;\n", myFixture.editor.document.text)
    }

    /** A block comment never starts or ends inside a tag: `{{ a | b }}` is commented whole. */
    fun testBlockCommentCoversWholeTags() {
        openTemplate("site.conf.j2", "x {{ <selection>a | b</selection> }} y\n")
        myFixture.performEditorAction(IdeActions.ACTION_COMMENT_BLOCK)
        assertEquals("x {# {{ a | b }} #} y\n", myFixture.editor.document.text)
    }

    /** Every attribute key of the Jinja layer is on the colour page, and the demo text shows all but bad characters. */
    fun testColorSettingsPageIsComplete() {
        val keys = AnsibleJinjaHighlighterColors::class.java.declaredFields
            .filter { TextAttributesKey::class.java.isAssignableFrom(it.type) }
            .map { it.get(AnsibleJinjaHighlighterColors) as TextAttributesKey }
            .toSet()
        val page = AnsibleJinjaColorSettingsPage()
        val described = page.attributeDescriptors.map { it.key }.toSet()
        assertEquals(keys, described)
        val highlighter = AnsibleJinjaSyntaxHighlighter()
        val lexer = AnsibleJinjaLexer()
        lexer.start(page.demoText)
        val shown = HashSet<TextAttributesKey>()
        while (lexer.tokenType != null) {
            assertFalse("bad character in the demo text at ${lexer.tokenStart}", lexer.tokenType == AnsibleJinjaTokenTypes.BAD_CHARACTER)
            shown += highlighter.getTokenHighlights(lexer.tokenType)
            lexer.advance()
        }
        assertEquals(keys - AnsibleJinjaHighlighterColors.BAD_CHARACTER, shown)
    }
}
