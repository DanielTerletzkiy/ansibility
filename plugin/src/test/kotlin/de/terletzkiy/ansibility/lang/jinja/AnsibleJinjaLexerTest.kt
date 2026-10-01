package de.terletzkiy.ansibility.lang.jinja

import com.intellij.psi.TokenType
import com.intellij.testFramework.LexerTestCase
import de.terletzkiy.ansibility.lang.jinja.lexer.AnsibleJinjaLexer
import de.terletzkiy.ansibility.lang.jinja.lexer.AnsibleJinjaTokenTypes
import de.terletzkiy.ansibility.lang.jinja.lexer.JinjaLexMode
import de.terletzkiy.ansibility.lang.jinja.lexer.JinjaLexMode.EXPRESSION
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.io.path.extension
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText

/** Token dumps for the tricky cases of the research report (jinja.md, "Edge cases"), plus restartability. */
class AnsibleJinjaLexerTest {
    private fun check(input: String, expected: String, mode: JinjaLexMode = JinjaLexMode.TEMPLATE) {
        assertEquals(expected.trimIndent(), dump(input, mode))
        assertRestartable(input, mode)
    }

    @Test
    fun `raw block keeps Go templates opaque`() = check(
        "{% raw %}\n  template = \"{{ if .level }}{{ .level }}{{ end }}\"\n{% endraw %}\nx {{ y }}",
        """
        BLOCK_START '{%'
        RAW_KEYWORD 'raw'
        BLOCK_END '%}'
        RAW_TEXT '\n  template = "{{ if .level }}{{ .level }}{{ end }}"\n'
        BLOCK_START '{%'
        ENDRAW_KEYWORD 'endraw'
        BLOCK_END '%}'
        TEXT '\nx '
        VAR_START '{{'
        IDENTIFIER 'y'
        VAR_END '}}'
        """,
    )

    @Test
    fun `inline raw inside a quoted YAML value and a shell command`() {
        check(
            "summary: 'on {% raw %}{{ \$labels.host }}{% endraw %} has {{ (t / 1000000000) | round | int }}GB'",
            """
            TEXT 'summary: 'on '
            BLOCK_START '{%'
            RAW_KEYWORD 'raw'
            BLOCK_END '%}'
            RAW_TEXT '{{ ${'$'}labels.host }}'
            BLOCK_START '{%'
            ENDRAW_KEYWORD 'endraw'
            BLOCK_END '%}'
            TEXT ' has '
            VAR_START '{{'
            LPAREN '('
            IDENTIFIER 't'
            DIV '/'
            INTEGER '1000000000'
            RPAREN ')'
            PIPE '|'
            FILTER_NAME 'round'
            PIPE '|'
            FILTER_NAME 'int'
            VAR_END '}}'
            TEXT 'GB''
            """,
        )
        check(
            "docker ps --format '{% raw %}{{.Image}}{% endraw %}' | awk",
            """
            TEXT 'docker ps --format ''
            BLOCK_START '{%'
            RAW_KEYWORD 'raw'
            BLOCK_END '%}'
            RAW_TEXT '{{.Image}}'
            BLOCK_START '{%'
            ENDRAW_KEYWORD 'endraw'
            BLOCK_END '%}'
            TEXT '' | awk'
            """,
        )
    }

    @Test
    fun `raw with whitespace control, empty raw and unterminated raw`() {
        check(
            "{%- raw -%} {{ a }} {%- endraw -%}",
            """
            BLOCK_START '{%-'
            RAW_KEYWORD 'raw'
            BLOCK_END '-%}'
            RAW_TEXT ' {{ a }} '
            BLOCK_START '{%-'
            ENDRAW_KEYWORD 'endraw'
            BLOCK_END '-%}'
            """,
        )
        check(
            "{%raw%}{%endraw%}",
            """
            BLOCK_START '{%'
            RAW_KEYWORD 'raw'
            BLOCK_END '%}'
            BLOCK_START '{%'
            ENDRAW_KEYWORD 'endraw'
            BLOCK_END '%}'
            """,
        )
        check(
            "{% raw %}{% endrawx %}{{ never }}",
            """
            BLOCK_START '{%'
            RAW_KEYWORD 'raw'
            BLOCK_END '%}'
            RAW_TEXT '{% endrawx %}{{ never }}'
            """,
        )
        // `raw` followed by more than whitespace is not a raw block
        check(
            "{% raw x %}{{ y }}",
            """
            BLOCK_START '{%'
            RAW_KEYWORD 'raw'
            IDENTIFIER 'x'
            BLOCK_END '%}'
            VAR_START '{{'
            IDENTIFIER 'y'
            VAR_END '}}'
            """,
        )
    }

    @Test
    fun `escaping delimiters by expression`() = check(
        "--format \"{{ '{{' }}.Names{{ '}}' }}\"",
        """
        TEXT '--format "'
        VAR_START '{{'
        STRING ''{{''
        VAR_END '}}'
        TEXT '.Names'
        VAR_START '{{'
        STRING ''}}''
        VAR_END '}}'
        TEXT '"'
        """,
    )

    @Test
    fun `whitespace control markers belong to the delimiters`() = check(
        "{%- if a -%}{{- b -}}{%+ endif +%}{#- c -#}{{+ d }}",
        """
        BLOCK_START '{%-'
        IF_KEYWORD 'if'
        IDENTIFIER 'a'
        BLOCK_END '-%}'
        VAR_START '{{-'
        IDENTIFIER 'b'
        VAR_END '-}}'
        BLOCK_START '{%+'
        ENDIF_KEYWORD 'endif'
        BLOCK_END '+%}'
        COMMENT '{#- c -#}'
        VAR_START '{{+'
        IDENTIFIER 'd'
        VAR_END '}}'
        """,
    )

    @Test
    fun `minus before the end delimiter is a marker, not an operator`() = check(
        "{{ x -}}{{ 5 - 3 }}{{-5}}",
        """
        VAR_START '{{'
        IDENTIFIER 'x'
        VAR_END '-}}'
        VAR_START '{{'
        INTEGER '5'
        MINUS '-'
        INTEGER '3'
        VAR_END '}}'
        VAR_START '{{-'
        INTEGER '5'
        VAR_END '}}'
        """,
    )

    @Test
    fun `macro with trim markers`() = check(
        "{% macro env(key, value) -%}\n'{{ key }}={{ value | string | replace('\$', '\$\$') }}'\n{%- endmacro -%}\nname: x",
        """
        BLOCK_START '{%'
        MACRO_KEYWORD 'macro'
        IDENTIFIER 'env'
        LPAREN '('
        IDENTIFIER 'key'
        COMMA ','
        IDENTIFIER 'value'
        RPAREN ')'
        BLOCK_END '-%}'
        TEXT '\n''
        VAR_START '{{'
        IDENTIFIER 'key'
        VAR_END '}}'
        TEXT '='
        VAR_START '{{'
        IDENTIFIER 'value'
        PIPE '|'
        FILTER_NAME 'string'
        PIPE '|'
        FILTER_NAME 'replace'
        LPAREN '('
        STRING ''${'$'}''
        COMMA ','
        STRING ''${'$'}${'$'}''
        RPAREN ')'
        VAR_END '}}'
        TEXT ''\n'
        BLOCK_START '{%-'
        ENDMACRO_KEYWORD 'endmacro'
        BLOCK_END '-%}'
        TEXT '\nname: x'
        """,
    )

    @Test
    fun `namespace attribute set`() = check(
        "{% set ns = namespace(found=false) %}{% set ns.found = true %}",
        """
        BLOCK_START '{%'
        SET_KEYWORD 'set'
        IDENTIFIER 'ns'
        ASSIGN '='
        IDENTIFIER 'namespace'
        LPAREN '('
        IDENTIFIER 'found'
        ASSIGN '='
        FALSE_KEYWORD 'false'
        RPAREN ')'
        BLOCK_END '%}'
        BLOCK_START '{%'
        SET_KEYWORD 'set'
        IDENTIFIER 'ns'
        DOT '.'
        IDENTIFIER 'found'
        ASSIGN '='
        TRUE_KEYWORD 'true'
        BLOCK_END '%}'
        """,
    )

    @Test
    fun `loose tag spacing`() = check(
        "{% for k in x %}{{ k }}{% endfor%}{%   if y %}{%endif%}",
        """
        BLOCK_START '{%'
        FOR_KEYWORD 'for'
        IDENTIFIER 'k'
        IN_KEYWORD 'in'
        IDENTIFIER 'x'
        BLOCK_END '%}'
        VAR_START '{{'
        IDENTIFIER 'k'
        VAR_END '}}'
        BLOCK_START '{%'
        ENDFOR_KEYWORD 'endfor'
        BLOCK_END '%}'
        BLOCK_START '{%'
        IF_KEYWORD 'if'
        IDENTIFIER 'y'
        BLOCK_END '%}'
        BLOCK_START '{%'
        ENDIF_KEYWORD 'endif'
        BLOCK_END '%}'
        """,
    )

    @Test
    fun `double-quoted YAML key with a for loop, tilde and endfor without space`() = check(
        "{% for keyfile in files %}{{ lookup('file', keyfile) ~ '\\n' }}{% endfor%}\\n",
        """
        BLOCK_START '{%'
        FOR_KEYWORD 'for'
        IDENTIFIER 'keyfile'
        IN_KEYWORD 'in'
        IDENTIFIER 'files'
        BLOCK_END '%}'
        VAR_START '{{'
        IDENTIFIER 'lookup'
        LPAREN '('
        STRING ''file''
        COMMA ','
        IDENTIFIER 'keyfile'
        RPAREN ')'
        TILDE '~'
        STRING ''\\n''
        VAR_END '}}'
        BLOCK_START '{%'
        ENDFOR_KEYWORD 'endfor'
        BLOCK_END '%}'
        TEXT '\\n'
        """,
    )

    @Test
    fun `FQCN filter in expression mode`() = check(
        "(item.path | basename | ansible.builtin.splitext | first) not in collector_parts",
        """
        LPAREN '('
        IDENTIFIER 'item'
        DOT '.'
        IDENTIFIER 'path'
        PIPE '|'
        FILTER_NAME 'basename'
        PIPE '|'
        FILTER_NAME 'ansible'
        DOT '.'
        FILTER_NAME 'builtin'
        DOT '.'
        FILTER_NAME 'splitext'
        PIPE '|'
        FILTER_NAME 'first'
        RPAREN ')'
        NOT_KEYWORD 'not'
        IN_KEYWORD 'in'
        IDENTIFIER 'collector_parts'
        """,
        EXPRESSION,
    )

    @Test
    fun `folded multi-line expression`() = check(
        "(app_settings_file is defined and app_settings_file.changed)\n" +
            "or (app_proxy_file is defined and app_proxy_file.changed)\n",
        """
        LPAREN '('
        IDENTIFIER 'app_settings_file'
        IS_KEYWORD 'is'
        TEST_NAME 'defined'
        AND_KEYWORD 'and'
        IDENTIFIER 'app_settings_file'
        DOT '.'
        IDENTIFIER 'changed'
        RPAREN ')'
        OR_KEYWORD 'or'
        LPAREN '('
        IDENTIFIER 'app_proxy_file'
        IS_KEYWORD 'is'
        TEST_NAME 'defined'
        AND_KEYWORD 'and'
        IDENTIFIER 'app_proxy_file'
        DOT '.'
        IDENTIFIER 'changed'
        RPAREN ')'
        """,
        EXPRESSION,
    )

    @Test
    fun `key value unpacking with items`() = check(
        "{% for option_key, option_value in options.items() %}{{ option_key }}: {{ option_value | to_json }}{% endfor %}",
        """
        BLOCK_START '{%'
        FOR_KEYWORD 'for'
        IDENTIFIER 'option_key'
        COMMA ','
        IDENTIFIER 'option_value'
        IN_KEYWORD 'in'
        IDENTIFIER 'options'
        DOT '.'
        IDENTIFIER 'items'
        LPAREN '('
        RPAREN ')'
        BLOCK_END '%}'
        VAR_START '{{'
        IDENTIFIER 'option_key'
        VAR_END '}}'
        TEXT ': '
        VAR_START '{{'
        IDENTIFIER 'option_value'
        PIPE '|'
        FILTER_NAME 'to_json'
        VAR_END '}}'
        BLOCK_START '{%'
        ENDFOR_KEYWORD 'endfor'
        BLOCK_END '%}'
        """,
    )

    @Test
    fun `inline if else`() = check(
        "verbose {{ 'on' if cache_verbose | bool else 'off' }}",
        """
        TEXT 'verbose '
        VAR_START '{{'
        STRING ''on''
        IF_KEYWORD 'if'
        IDENTIFIER 'cache_verbose'
        PIPE '|'
        FILTER_NAME 'bool'
        ELSE_KEYWORD 'else'
        STRING ''off''
        VAR_END '}}'
        """,
    )

    @Test
    fun `slices and negative indexes`() = check(
        "{{ q[:-1] | int(default=5) }}{{ q[-1] }}{{ l[1:3] }}{{ l[::2] }}",
        """
        VAR_START '{{'
        IDENTIFIER 'q'
        LBRACKET '['
        COLON ':'
        MINUS '-'
        INTEGER '1'
        RBRACKET ']'
        PIPE '|'
        FILTER_NAME 'int'
        LPAREN '('
        IDENTIFIER 'default'
        ASSIGN '='
        INTEGER '5'
        RPAREN ')'
        VAR_END '}}'
        VAR_START '{{'
        IDENTIFIER 'q'
        LBRACKET '['
        MINUS '-'
        INTEGER '1'
        RBRACKET ']'
        VAR_END '}}'
        VAR_START '{{'
        IDENTIFIER 'l'
        LBRACKET '['
        INTEGER '1'
        COLON ':'
        INTEGER '3'
        RBRACKET ']'
        VAR_END '}}'
        VAR_START '{{'
        IDENTIFIER 'l'
        LBRACKET '['
        COLON ':'
        COLON ':'
        INTEGER '2'
        RBRACKET ']'
        VAR_END '}}'
        """,
    )

    @Test
    fun `method calls`() = check(
        "{{ x.split(',')[0].startswith('a') }}{{ groups.get('sandbox_hosts', []) }}",
        """
        VAR_START '{{'
        IDENTIFIER 'x'
        DOT '.'
        IDENTIFIER 'split'
        LPAREN '('
        STRING '',''
        RPAREN ')'
        LBRACKET '['
        INTEGER '0'
        RBRACKET ']'
        DOT '.'
        IDENTIFIER 'startswith'
        LPAREN '('
        STRING ''a''
        RPAREN ')'
        VAR_END '}}'
        VAR_START '{{'
        IDENTIFIER 'groups'
        DOT '.'
        IDENTIFIER 'get'
        LPAREN '('
        STRING ''sandbox_hosts''
        COMMA ','
        LBRACKET '['
        RBRACKET ']'
        RPAREN ')'
        VAR_END '}}'
        """,
    )

    @Test
    fun `tests after is and is not, including FQCN tests`() = check(
        "{{ x is not none and y is defined }}{{ 'a' not in b }}{{ v is ansible.builtin.version('2.0', '>=') }}",
        """
        VAR_START '{{'
        IDENTIFIER 'x'
        IS_KEYWORD 'is'
        NOT_KEYWORD 'not'
        TEST_NAME 'none'
        AND_KEYWORD 'and'
        IDENTIFIER 'y'
        IS_KEYWORD 'is'
        TEST_NAME 'defined'
        VAR_END '}}'
        VAR_START '{{'
        STRING ''a''
        NOT_KEYWORD 'not'
        IN_KEYWORD 'in'
        IDENTIFIER 'b'
        VAR_END '}}'
        VAR_START '{{'
        IDENTIFIER 'v'
        IS_KEYWORD 'is'
        TEST_NAME 'ansible'
        DOT '.'
        TEST_NAME 'builtin'
        DOT '.'
        TEST_NAME 'version'
        LPAREN '('
        STRING ''2.0''
        COMMA ','
        STRING ''>=''
        RPAREN ')'
        VAR_END '}}'
        """,
    )

    @Test
    fun `nested dict literals do not close the output tag`() = check(
        "{{ {'a': {'b': 1}} }}{{ {'a':1}}}x",
        """
        VAR_START '{{'
        LBRACE '{'
        STRING ''a''
        COLON ':'
        LBRACE '{'
        STRING ''b''
        COLON ':'
        INTEGER '1'
        RBRACE '}'
        RBRACE '}'
        VAR_END '}}'
        VAR_START '{{'
        LBRACE '{'
        STRING ''a''
        COLON ':'
        INTEGER '1'
        RBRACE '}'
        VAR_END '}}'
        TEXT 'x'
        """,
    )

    @Test
    fun numbers() = check(
        "{{ 1_000 + 0x1F + 0o17 + 0b101 + 1.5e-3 + 2e10 + 3.25 + item.0.name + 1.x }}",
        """
        VAR_START '{{'
        INTEGER '1_000'
        PLUS '+'
        INTEGER '0x1F'
        PLUS '+'
        INTEGER '0o17'
        PLUS '+'
        INTEGER '0b101'
        PLUS '+'
        FLOAT '1.5e-3'
        PLUS '+'
        FLOAT '2e10'
        PLUS '+'
        FLOAT '3.25'
        PLUS '+'
        IDENTIFIER 'item'
        DOT '.'
        INTEGER '0'
        DOT '.'
        IDENTIFIER 'name'
        PLUS '+'
        INTEGER '1'
        DOT '.'
        IDENTIFIER 'x'
        VAR_END '}}'
        """,
    )

    @Test
    fun `strings with escapes and delimiters inside`() = check(
        "{{ 'a\\'b' ~ \"c\\\"d\" ~ '%}' }}{% set x = \"}}\" %}",
        """
        VAR_START '{{'
        STRING ''a\\'b''
        TILDE '~'
        STRING '"c\\"d"'
        TILDE '~'
        STRING ''%}''
        VAR_END '}}'
        BLOCK_START '{%'
        SET_KEYWORD 'set'
        IDENTIFIER 'x'
        ASSIGN '='
        STRING '"}}"'
        BLOCK_END '%}'
        """,
    )

    @Test
    fun `unterminated strings end at the line end`() {
        check(
            "{{ 'abc }} tail",
            """
            VAR_START '{{'
            STRING ''abc }} tail'
            """,
        )
        check(
            "{% set x = 'abc -%}\n{{ y }}",
            """
            BLOCK_START '{%'
            SET_KEYWORD 'set'
            IDENTIFIER 'x'
            ASSIGN '='
            STRING ''abc -%}'
            LBRACE '{'
            LBRACE '{'
            IDENTIFIER 'y'
            RBRACE '}'
            RBRACE '}'
            """,
        )
        // the closing quote comes before the tag end: a multi-line string, as in Jinja
        check(
            "{{ f('abc\n, 'x') }}",
            """
            VAR_START '{{'
            IDENTIFIER 'f'
            LPAREN '('
            STRING ''abc\n, ''
            IDENTIFIER 'x'
            STRING '') }}'
            """,
        )
        // the tag ends first: unterminated
        check(
            "{{ 'abc\ndef }}x",
            """
            VAR_START '{{'
            STRING ''abc'
            IDENTIFIER 'def'
            VAR_END '}}'
            TEXT 'x'
            """,
        )
        check("x: 'abc", "IDENTIFIER 'x'\nCOLON ':'\nSTRING ''abc'", EXPRESSION)
        check("x == 'a\nb' and y", "IDENTIFIER 'x'\nEQEQ '=='\nSTRING ''a\\nb''\nAND_KEYWORD 'and'\nIDENTIFIER 'y'", EXPRESSION)
    }

    @Test
    fun comments() {
        check("{# a {{ b }} #}c{##}{#-#}", "COMMENT '{# a {{ b }} #}'\nTEXT 'c'\nCOMMENT '{##}'\nCOMMENT '{#-#}'")
        check("{# unterminated {{ x }}", "COMMENT '{# unterminated {{ x }}'")
        check(
            "{# Watcher on every host\n   over two lines. #}\n{% if x %}",
            """
            COMMENT '{# Watcher on every host\n   over two lines. #}'
            TEXT '\n'
            BLOCK_START '{%'
            IF_KEYWORD 'if'
            IDENTIFIER 'x'
            BLOCK_END '%}'
            """,
        )
    }

    @Test
    fun `keywords are contextual`() = check(
        "{{ raw }}{{ x.if }}{% set block = 1 %}{{ d | combine(e, recursive=True) }}{{ for }}{% if with %}",
        """
        VAR_START '{{'
        IDENTIFIER 'raw'
        VAR_END '}}'
        VAR_START '{{'
        IDENTIFIER 'x'
        DOT '.'
        IDENTIFIER 'if'
        VAR_END '}}'
        BLOCK_START '{%'
        SET_KEYWORD 'set'
        IDENTIFIER 'block'
        ASSIGN '='
        INTEGER '1'
        BLOCK_END '%}'
        VAR_START '{{'
        IDENTIFIER 'd'
        PIPE '|'
        FILTER_NAME 'combine'
        LPAREN '('
        IDENTIFIER 'e'
        COMMA ','
        IDENTIFIER 'recursive'
        ASSIGN '='
        TRUE_KEYWORD 'True'
        RPAREN ')'
        VAR_END '}}'
        VAR_START '{{'
        IDENTIFIER 'for'
        VAR_END '}}'
        BLOCK_START '{%'
        IF_KEYWORD 'if'
        WITH_KEYWORD 'with'
        BLOCK_END '%}'
        """,
    )

    @Test
    fun `statement clauses`() {
        check(
            "{% for x in y if x.a recursive %}{% endfor %}",
            """
            BLOCK_START '{%'
            FOR_KEYWORD 'for'
            IDENTIFIER 'x'
            IN_KEYWORD 'in'
            IDENTIFIER 'y'
            IF_KEYWORD 'if'
            IDENTIFIER 'x'
            DOT '.'
            IDENTIFIER 'a'
            RECURSIVE_KEYWORD 'recursive'
            BLOCK_END '%}'
            BLOCK_START '{%'
            ENDFOR_KEYWORD 'endfor'
            BLOCK_END '%}'
            """,
        )
        check(
            "{% from 'forms' import input as field with context %}{% include 'x' ignore missing %}{% filter upper %}",
            """
            BLOCK_START '{%'
            FROM_KEYWORD 'from'
            STRING ''forms''
            IMPORT_KEYWORD 'import'
            IDENTIFIER 'input'
            AS_KEYWORD 'as'
            IDENTIFIER 'field'
            WITH_KEYWORD 'with'
            IDENTIFIER 'context'
            BLOCK_END '%}'
            BLOCK_START '{%'
            INCLUDE_KEYWORD 'include'
            STRING ''x''
            IDENTIFIER 'ignore'
            IDENTIFIER 'missing'
            BLOCK_END '%}'
            BLOCK_START '{%'
            FILTER_KEYWORD 'filter'
            FILTER_NAME 'upper'
            BLOCK_END '%}'
            """,
        )
    }

    @Test
    fun `operators and punctuation`() = check(
        "a + b - c * d / e // f % g ** h ~ i == j != k < l > m <= n >= o = p | q . r , s : t ; u ( ) [ ] { }",
        """
        IDENTIFIER 'a'
        PLUS '+'
        IDENTIFIER 'b'
        MINUS '-'
        IDENTIFIER 'c'
        MUL '*'
        IDENTIFIER 'd'
        DIV '/'
        IDENTIFIER 'e'
        FLOORDIV '//'
        IDENTIFIER 'f'
        MOD '%'
        IDENTIFIER 'g'
        POW '**'
        IDENTIFIER 'h'
        TILDE '~'
        IDENTIFIER 'i'
        EQEQ '=='
        IDENTIFIER 'j'
        NE '!='
        IDENTIFIER 'k'
        LT '<'
        IDENTIFIER 'l'
        GT '>'
        IDENTIFIER 'm'
        LE '<='
        IDENTIFIER 'n'
        GE '>='
        IDENTIFIER 'o'
        ASSIGN '='
        IDENTIFIER 'p'
        PIPE '|'
        FILTER_NAME 'q'
        DOT '.'
        FILTER_NAME 'r'
        COMMA ','
        IDENTIFIER 's'
        COLON ':'
        IDENTIFIER 't'
        SEMICOLON ';'
        IDENTIFIER 'u'
        LPAREN '('
        RPAREN ')'
        LBRACKET '['
        RBRACKET ']'
        LBRACE '{'
        RBRACE '}'
        """,
        EXPRESSION,
    )

    @Test
    fun `bad characters only for input Jinja rejects`() = check(
        "{{ \$x ? #y ! z != w \\ }}",
        """
        VAR_START '{{'
        BAD_CHARACTER '${'$'}'
        IDENTIFIER 'x'
        BAD_CHARACTER '?'
        BAD_CHARACTER '#'
        IDENTIFIER 'y'
        BAD_CHARACTER '!'
        IDENTIFIER 'z'
        NE '!='
        IDENTIFIER 'w'
        BAD_CHARACTER '\\'
        VAR_END '}}'
        """,
    )

    @Test
    fun `expression mode has no delimiters`() = check(
        "x }} y %} {{ z",
        """
        IDENTIFIER 'x'
        RBRACE '}'
        RBRACE '}'
        IDENTIFIER 'y'
        MOD '%'
        RBRACE '}'
        LBRACE '{'
        LBRACE '{'
        IDENTIFIER 'z'
        """,
        EXPRESSION,
    )

    @Test
    fun `outer text is never mistaken for a tag`() {
        check("a { b } c }} d %} e #}", "TEXT 'a { b } c }} d %} e #}'")
        check("\${#arr[@]} {", "TEXT '\$'\nCOMMENT '{#arr[@]} {'")
        check("{", "TEXT '{'")
        check("x{", "TEXT 'x{'")
        check("{{", "VAR_START '{{'")
        check("{%", "BLOCK_START '{%'")
        check("{{ x", "VAR_START '{{'\nIDENTIFIER 'x'")
        check("", "")
    }

    @Test
    fun `whitespace inside tags is one token and outer text keeps state zero`() {
        val text = "a {{  x\n\t}} b {%   if y %}\n{% endif %}"
        assertEquals(
            "TEXT 'a '\nVAR_START '{{'\nWHITE_SPACE '  '\nIDENTIFIER 'x'\nWHITE_SPACE '\\n\\t'\nVAR_END '}}'\n" +
                "TEXT ' b '\nBLOCK_START '{%'\nWHITE_SPACE '   '\nIF_KEYWORD 'if'\nWHITE_SPACE ' '\nIDENTIFIER 'y'\n" +
                "WHITE_SPACE ' '\nBLOCK_END '%}'\nTEXT '\\n'\nBLOCK_START '{%'\nWHITE_SPACE ' '\nENDIF_KEYWORD 'endif'\n" +
                "WHITE_SPACE ' '\nBLOCK_END '%}'",
            dump(text, withWhitespace = true),
        )
        val tokens = lex(text)
        assertTrue(tokens.filter { it.type == AnsibleJinjaTokenTypes.TEXT }.all { it.state == 0 })
        assertTrue(tokens.filter { it.type == AnsibleJinjaTokenTypes.VAR_START || it.type == AnsibleJinjaTokenTypes.BLOCK_START }.all { it.state == 0 })
        assertEquals(TokenType.WHITE_SPACE, AnsibleJinjaTokenTypes.WHITE_SPACE)
    }

    @Test
    fun `lexing a sub-range stops at its end`() {
        val text = "xx{{ a }}yy"
        val lexer = AnsibleJinjaLexer()
        lexer.start(text, 2, 9, 0)
        val types = generateSequence { lexer.tokenType?.also { lexer.advance() } }.toList()
        assertEquals(
            listOf(AnsibleJinjaTokenTypes.VAR_START, AnsibleJinjaTokenTypes.WHITE_SPACE, AnsibleJinjaTokenTypes.IDENTIFIER,
                AnsibleJinjaTokenTypes.WHITE_SPACE, AnsibleJinjaTokenTypes.VAR_END),
            types,
        )
        assertEquals(9, lexer.tokenStart)
    }

    /** Every synthetic template: no bad characters, restartable, and a reviewed golden token dump. */
    @Test
    fun `synthetic templates lex cleanly and match their golden dumps`() {
        val templates = jinjaTestData.resolve("templates").listDirectoryEntries().filter { it.extension == "j2" }.sortedBy { it.name }
        assertTrue("expected the synthetic templates, found ${templates.size}", templates.size >= 15)
        val golden = LinkedHashMap<java.nio.file.Path, String>()
        for (template in templates) {
            val text = template.readText()
            val bad = lex(text).filter { it.type == AnsibleJinjaTokenTypes.BAD_CHARACTER }
            assertTrue("${template.name}: bad characters at ${bad.map { it.start }}", bad.isEmpty())
            assertRestartable(text)
            golden[jinjaTestData.resolve("lexer").resolve(template.name.removeSuffix(".j2") + ".txt")] =
                LexerTestCase.printTokens(text, 0, AnsibleJinjaLexer())
        }
        assertSameAsGolden(golden)
    }
}
