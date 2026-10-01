package de.terletzkiy.ansibility.lang.jinja.editor

/**
 * X35 typing assistance: auto-closed `{{ }}`, `{% %}`, `{# #}`, type-over of the end delimiter, the `{%-` marker,
 * Backspace in an empty tag, quotes, and where none of it applies.
 */
class AnsibleJinjaTypingTest : JinjaEditorTestCase() {
    fun testVariableTagInPlainTextTemplate() {
        openTemplate("motd.j2", "Welcome to <caret>\n")
        myFixture.type("{{")
        myFixture.checkResult("Welcome to {{ <caret> }}\n")
    }

    fun testStatementAndCommentTags() {
        openTemplate("site.conf.j2", "<caret>\n")
        myFixture.type("{%")
        myFixture.checkResult("{% <caret> %}\n")
        openTemplate("other.conf.j2", "<caret>\n")
        myFixture.type("{#")
        myFixture.checkResult("{# <caret> #}\n")
    }

    /** In a YAML-outer template the YAML brace pairing turns `{` into `{}`; that `}` becomes the tag's end. */
    fun testYamlOuterTemplateReusesThePairedBrace() {
        openTemplate("compose.yml.j2", "services:\n  app:\n    image: <caret>\n")
        myFixture.type("{{")
        myFixture.checkResult("services:\n  app:\n    image: {{ <caret> }}\n")
        myFixture.type("app_image }}")
        myFixture.checkResult("services:\n  app:\n    image: {{ app_image }}<caret>\n")
    }

    fun testTypeOverTheEndDelimiters() {
        openTemplate("motd.j2", "<caret>\n")
        myFixture.type("{{x}}")
        myFixture.checkResult("{{ x }}<caret>\n")
        openTemplate("loop.j2", "<caret>\n")
        myFixture.type("{%if x%}")
        myFixture.checkResult("{% if x %}<caret>\n")
        openTemplate("note.j2", "<caret>\n")
        myFixture.type("{#note#}")
        myFixture.checkResult("{# note #}<caret>\n")
    }

    /** Typing the whole tag with its spaces gives the tag once: no doubled spaces or delimiters. */
    fun testTypingTheWholeTag() {
        openTemplate("motd.j2", "<caret>\n")
        myFixture.type("{{ name | default('x') }}")
        myFixture.checkResult("{{ name | default('x') }}<caret>\n")
        openTemplate("loop.j2", "<caret>\n")
        myFixture.type("{% for item in items %}")
        myFixture.checkResult("{% for item in items %}<caret>\n")
    }

    fun testWhitespaceMarker() {
        openTemplate("motd.j2", "<caret>\n")
        myFixture.type("{%- if x")
        myFixture.checkResult("{%- if x<caret> %}\n")
        myFixture.type(" -%}")
        myFixture.checkResult("{%- if x -%}<caret>\n")
    }

    fun testAlreadyClosedTagIsRetypedPlainly() {
        openTemplate("motd.j2", "{<caret> name }}\n")
        myFixture.type("{")
        myFixture.checkResult("{{<caret> name }}\n")
    }

    fun testNothingInsideCommentsAndTags() {
        openTemplate("motd.j2", "{# <caret> #}\n")
        myFixture.type("{{")
        myFixture.checkResult("{# {{<caret> #}\n")
        openTemplate("dict.j2", "{{ <caret> }}\n")
        myFixture.type("{%")
        assertFalse(myFixture.editor.document.text, "%}" in myFixture.editor.document.text)
    }

    fun testRawBodyIsTemplateTextForGoTemplates() {
        openTemplate("config.alloy.j2", "{% raw %}<caret>{% endraw %}\n")
        myFixture.type("{{")
        myFixture.checkResult("{% raw %}{{ <caret> }}{% endraw %}\n")
    }

    fun testBackspaceUndoesAnEmptyTag() {
        openTemplate("motd.j2", "<caret>\n")
        myFixture.type("{{")
        myFixture.type('\b')
        myFixture.checkResult("{<caret>\n")
        myFixture.type("%")
        myFixture.checkResult("{% <caret> %}\n")
        myFixture.type('\b')
        myFixture.checkResult("{<caret>\n")
    }

    fun testSettingSwitchesItOff() {
        withJinjaSettings({ copy(autoCloseDelimiters = false) }) {
            openTemplate("motd.j2", "<caret>\n")
            myFixture.type("{{x")
            myFixture.checkResult("{{x<caret>\n")
        }
    }

    fun testQuotesPairInsideTags() {
        openTemplate("motd.j2", "{{ name | default(<caret>) }}\n")
        myFixture.type("'")
        myFixture.checkResult("{{ name | default('<caret>') }}\n")
    }

    fun testAnsibleYamlScalars() {
        open("site/roles/web/tasks/edit.yml", "- ansible.builtin.debug:\n    msg: \"<caret>\"\n")
        myFixture.type("{{")
        myFixture.checkResult("- ansible.builtin.debug:\n    msg: \"{{ <caret> }}\"\n")
    }

    /** Bare expressions never get `{{ }}` (plan X30): `when`, `changed_when`, `failed_when`, `until`, `that`. */
    fun testNoTagsInBareExpressionKeys() {
        open("site/roles/web/tasks/edit.yml", "- ansible.builtin.debug:\n    msg: x\n  when: \"<caret>\"\n")
        myFixture.type("{{")
        myFixture.checkResult("- ansible.builtin.debug:\n    msg: x\n  when: \"{{<caret>\"\n")
    }

    fun testYamlOutsideAnsibleRootsIsLeftAlone() {
        open("elsewhere/config.yml", "msg: \"<caret>\"\n")
        myFixture.type("{{")
        myFixture.checkResult("msg: \"{{<caret>\"\n")
    }
}
