package de.terletzkiy.ansibility.lang.jinja

import com.intellij.lang.Language
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.ex.util.LexerEditorHighlighter
import com.intellij.openapi.editor.highlighter.HighlighterClient
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.options.colors.ColorSettingsPage
import com.intellij.openapi.project.Project
import com.intellij.psi.tree.IElementType
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.terletzkiy.ansibility.lang.jinja.highlighting.AnsibleJinjaColorSettingsPage
import de.terletzkiy.ansibility.lang.jinja.highlighting.AnsibleJinjaHighlighterColors
import de.terletzkiy.ansibility.lang.jinja.highlighting.AnsibleJinjaSyntaxHighlighter
import de.terletzkiy.ansibility.lang.jinja.lexer.AnsibleJinjaTokenTypes
import de.terletzkiy.ansibility.lang.jinja.lexer.JinjaLexMode

class AnsibleJinjaHighlightingTest : BasePlatformTestCase() {
    fun testLanguageAndFileTypeAreRegisteredWithoutClaimingFiles() {
        assertSame(AnsibleJinjaLanguage, Language.findLanguageByID("AnsibleJinja"))
        assertEquals("AnsibleJinja", AnsibleJinjaLanguage.ID)
        assertEquals("Ansible Jinja2", AnsibleJinjaLanguage.displayName)
        val fileTypes = FileTypeManager.getInstance()
        assertSame(AnsibleJinjaFileType, fileTypes.findFileTypeByName(AnsibleJinjaFileType.NAME))
        assertSame(AnsibleJinjaFileType, AnsibleJinjaLanguage.associatedFileType)
        assertEmpty(fileTypes.getAssociations(AnsibleJinjaFileType))
        for (name in listOf("nginx.conf.j2", "main.yml", "docker-compose.ansible-playbook.yml", "template.jinja2")) {
            assertNotSame(name, AnsibleJinjaFileType, fileTypes.getFileTypeByFileName(name))
        }
    }

    fun testSyntaxHighlighterFactoryIsRegistered() {
        assertInstanceOf(SyntaxHighlighterFactory.getSyntaxHighlighter(AnsibleJinjaLanguage, project, null), AnsibleJinjaSyntaxHighlighter::class.java)
        assertInstanceOf(SyntaxHighlighterFactory.getSyntaxHighlighter(AnsibleJinjaFileType, project, null), AnsibleJinjaSyntaxHighlighter::class.java)
    }

    fun testTokenHighlights() {
        val text = "{# c #}{%- if x.y is defined -%}{{ y | ansible.builtin.default('a', true) ~ 1.5 + [0][0] }}" +
            "{% endif %}{% raw %}{{ r }}{% endraw %}{{ {'k': v}, \$ }}text"
        val highlighter = AnsibleJinjaSyntaxHighlighter()
        val actual = lex(text)
            .filter { it.type != AnsibleJinjaTokenTypes.WHITE_SPACE }
            .map { text.substring(it.start, it.end) to highlighter.getTokenHighlights(it.type).toList() }
        val c = AnsibleJinjaHighlighterColors
        assertEquals(
            listOf(
                "{# c #}" to c.COMMENT, "{%-" to c.DELIMITER, "if" to c.KEYWORD, "x" to c.IDENTIFIER, "." to c.DOT,
                "y" to c.IDENTIFIER, "is" to c.KEYWORD, "defined" to c.TEST, "-%}" to c.DELIMITER,
                "{{" to c.DELIMITER, "y" to c.IDENTIFIER, "|" to c.OPERATOR, "ansible" to c.FILTER, "." to c.DOT,
                "builtin" to c.FILTER, "." to c.DOT, "default" to c.FILTER, "(" to c.PARENTHESES, "'a'" to c.STRING,
                "," to c.COMMA, "true" to c.KEYWORD, ")" to c.PARENTHESES, "~" to c.OPERATOR, "1.5" to c.NUMBER,
                "+" to c.OPERATOR, "[" to c.BRACKETS, "0" to c.NUMBER, "]" to c.BRACKETS, "[" to c.BRACKETS,
                "0" to c.NUMBER, "]" to c.BRACKETS, "}}" to c.DELIMITER,
                "{%" to c.DELIMITER, "endif" to c.KEYWORD, "%}" to c.DELIMITER,
                "{%" to c.DELIMITER, "raw" to c.KEYWORD, "%}" to c.DELIMITER, "{{ r }}" to c.RAW_TEXT,
                "{%" to c.DELIMITER, "endraw" to c.KEYWORD, "%}" to c.DELIMITER,
                "{{" to c.DELIMITER, "{" to c.BRACES, "'k'" to c.STRING, ":" to c.OPERATOR, "v" to c.IDENTIFIER,
                "}" to c.BRACES, "," to c.COMMA, "\$" to c.BAD_CHARACTER, "}}" to c.DELIMITER,
            ).map { (token, key) -> token to listOf(key) } + listOf("text" to emptyList<TextAttributesKey>()),
            actual,
        )
    }

    fun testEveryJinjaTokenTypeIsHighlightedExceptOuterTextAndWhitespace() {
        val highlighter = AnsibleJinjaSyntaxHighlighter()
        val unhighlighted = setOf(AnsibleJinjaTokenTypes.TEXT, AnsibleJinjaTokenTypes.WHITE_SPACE)
        val tokenTypes = AnsibleJinjaTokenTypes::class.java.declaredFields
            .filter { IElementType::class.java.isAssignableFrom(it.type) }
            .map { it.get(null) as IElementType }
        assertTrue(tokenTypes.size > 60)
        for (type in tokenTypes) {
            assertEquals("$type", if (type in unhighlighted) 0 else 1, highlighter.getTokenHighlights(type).size)
        }
    }

    fun testColorSettingsPage() {
        val page = ColorSettingsPage.EP_NAME.extensionList.filterIsInstance<AnsibleJinjaColorSettingsPage>().single()
        assertEquals("Ansible Jinja2", page.displayName)
        val described = page.attributeDescriptors.map { it.key }.toSet()
        assertEquals(page.attributeDescriptors.size, described.size)
        assertTrue(page.attributeDescriptors.all { it.displayName.isNotBlank() })
        val demo = page.demoText
        val tokens = lex(demo)
        assertTrue("the demo has no bad characters", tokens.none { it.type == AnsibleJinjaTokenTypes.BAD_CHARACTER })
        val highlighter = page.highlighter
        val used = tokens.flatMap { highlighter.getTokenHighlights(it.type).toList() }.toSet()
        assertTrue("every key the demo uses is configurable: ${used - described}", described.containsAll(used))
        assertEquals("the demo shows every key but bad characters", described - AnsibleJinjaHighlighterColors.BAD_CHARACTER, used)
    }

    fun testEditorHighlighterRelexesIncrementally() {
        val initial = "# {{ ansible_managed }}\n{% for k, v in d.items() %}\n{{ k }}={{ v | to_json }}\n{% endfor %}\n" +
            "{% raw %}{{ .x }}{% endraw %}\n{{ {'a': {'b': 1}} }}\n{% raw %}{% endraw %}{{ 'q' }}\n"
        val edits = listOf(
            // (text to find, replacement): typing inside tags, breaking and repairing delimiters, raw and dict nesting
            "| to_json" to "| to_json | trim",
            "{{ k }}" to "{{ k ",
            "{{ k =" to "{{ k }}=",
            "{% raw %}" to "{% raw -%}",
            "{% endraw %}" to "{% endra %}",
            "{% endra %}" to "{% endraw %}",
            "{% raw %}{% endraw %}" to "{% raw %}{% endra %}",
            "{% endra %}" to "{% endraw %}",
            "{'b': 1}} }}" to "{'b': 1} }}",
            "{'b': 1} }}" to "{'b': 1}} }}",
            "{{ 'q' }}" to "{{ 'q }}",
            "{{ 'q }}" to "{{ 'q' }}",
            "# {{" to "# {",
            "# {" to "# {#",
            "# {#" to "# {{",
        )
        withIncrementalHighlighter(initial, JinjaLexMode.TEMPLATE) { document, check ->
            for ((find, replacement) in edits) {
                val offset = document.text.indexOf(find)
                assertTrue("'$find' in ${document.text}", offset >= 0)
                WriteCommandAction.runWriteCommandAction(project) { document.replaceString(offset, offset + find.length, replacement) }
                check("after replacing '$find' with '$replacement'")
            }
        }
    }

    fun testEditorHighlighterRelexesExpressionModeIncrementally() {
        withIncrementalHighlighter("x == 'a\n b' and y is defined\n or 'c", JinjaLexMode.EXPRESSION) { document, check ->
            for ((find, replacement) in listOf("'a" to "a", "b'" to "b", "a\n" to "'a\n", "'c" to "'c'", "c'" to "c")) {
                val offset = document.text.indexOf(find)
                WriteCommandAction.runWriteCommandAction(project) { document.replaceString(offset, offset + find.length, replacement) }
                check("after replacing '$find' with '$replacement'")
            }
        }
    }

    /** Random single-character edits on the synthetic templates, each checked against a fresh lex. */
    fun testEditorHighlighterSurvivesRandomEdits() {
        val random = java.util.Random(20260930)
        val alphabet = "{}%#-+'\" \n x|.()[]raw endraw"
        val templates = jinjaTestData.resolve("templates").toFile().listFiles { f -> f.name.endsWith(".j2") }!!.sortedBy { it.name }
        for (template in templates) {
            withIncrementalHighlighter(template.readText(), JinjaLexMode.TEMPLATE) { document, check ->
                repeat(150) { step ->
                    val length = document.textLength
                    val offset = random.nextInt(length + 1)
                    WriteCommandAction.runWriteCommandAction(project) {
                        if (length > 0 && random.nextBoolean()) {
                            document.deleteString(offset.coerceAtMost(length - 1), offset.coerceAtMost(length - 1) + 1)
                        } else {
                            document.insertString(offset, alphabet[random.nextInt(alphabet.length)].toString())
                        }
                    }
                    check("${template.name}, edit #$step")
                }
            }
        }
    }

    private fun withIncrementalHighlighter(
        initial: String,
        mode: JinjaLexMode,
        body: (Document, check: (String) -> Unit) -> Unit,
    ) {
        val document = EditorFactory.getInstance().createDocument(initial)
        val highlighter = LexerEditorHighlighter(AnsibleJinjaSyntaxHighlighter(mode), EditorColorsManager.getInstance().globalScheme)
        highlighter.setEditor(object : HighlighterClient {
            override fun getProject(): Project = this@AnsibleJinjaHighlightingTest.project

            override fun repaint(start: Int, end: Int) = Unit

            override fun getDocument(): Document = document
        })
        highlighter.setText(document.immutableCharSequence)
        document.addDocumentListener(highlighter)
        body(document) { message ->
            val iterator = highlighter.createIterator(0)
            val incremental = ArrayList<Triple<IElementType, Int, Int>>()
            while (!iterator.atEnd()) {
                incremental += Triple(iterator.tokenType, iterator.start, iterator.end)
                iterator.advance()
            }
            val fresh = lex(document.text, mode).map { Triple(it.type, it.start, it.end) }
            if (fresh != incremental) {
                val first = fresh.indices.firstOrNull { it >= incremental.size || fresh[it] != incremental[it] } ?: fresh.size
                val at = (fresh.getOrNull(first) ?: incremental.getOrNull(first))?.second ?: 0
                fail(
                    "$message: first difference at token #$first: fresh ${fresh.subList(maxOf(0, first - 2), minOf(fresh.size, first + 3))}, " +
                        "incremental ${incremental.subList(maxOf(0, first - 2), minOf(incremental.size, first + 3))}, " +
                        "text around: <${document.text.substring(maxOf(0, at - 60), minOf(document.textLength, at + 60))}>",
                )
            }
        }
    }
}
