package de.terletzkiy.ansibility.lang.jinja.editor

/** X35 Enter: end tags after an unclosed opening tag, and the split between an opening tag and its end tag. */
class AnsibleJinjaEnterTest : JinjaEditorTestCase() {
    fun testEnterAfterIfInsertsEndif() {
        openTemplate("site.conf.j2", "server {\n{% if ssl %}<caret>\n}\n")
        myFixture.type('\n')
        myFixture.checkResult("server {\n{% if ssl %}\n<caret>\n{% endif %}\n}\n")
    }

    fun testEveryBlockKind() {
        val openers = mapOf(
            "{% for x in xs %}" to "{% endfor %}",
            "{% macro m(a) %}" to "{% endmacro %}",
            "{% call m(1) %}" to "{% endcall %}",
            "{% filter upper %}" to "{% endfilter %}",
            "{% with a = 1 %}" to "{% endwith %}",
            "{% block body %}" to "{% endblock %}",
            "{% raw %}" to "{% endraw %}",
            "{% set lines %}" to "{% endset %}",
        )
        for ((index, entry) in openers.entries.withIndex()) {
            openTemplate("t$index.j2", "${entry.key}<caret>\n")
            myFixture.type('\n')
            myFixture.checkResult("${entry.key}\n<caret>\n${entry.value}\n")
        }
    }

    fun testIndentationAndSpellingAreMirrored() {
        openTemplate("site.conf.j2", "    {%-   if ssl -%}<caret>\n")
        myFixture.type('\n')
        myFixture.checkResult("    {%-   if ssl -%}\n    <caret>\n    {%-   endif -%}\n")
    }

    fun testNoEndTagWhenTheBlockIsClosed() {
        openTemplate("site.conf.j2", "{% if ssl %}<caret>\nlisten 443;\n{% endif %}\n")
        myFixture.type('\n')
        myFixture.checkResult("{% if ssl %}\n<caret>\nlisten 443;\n{% endif %}\n")
    }

    /** A new `if` inside an `if` would take over the outer `{% endif %}`: it gets its own. */
    fun testNestedOpenerGetsItsOwnEndTag() {
        openTemplate("site.conf.j2", "{% if a %}\n{% if b %}<caret>\n{% endif %}\n")
        myFixture.type('\n')
        myFixture.checkResult("{% if a %}\n{% if b %}\n<caret>\n{% endif %}\n{% endif %}\n")
    }

    fun testSingleTagsAndInlineSetGetNoEndTag() {
        openTemplate("site.conf.j2", "{% set x = 1 %}<caret>\n")
        myFixture.type('\n')
        myFixture.checkResult("{% set x = 1 %}\n<caret>\n")
        openTemplate("include.j2", "{% include 'a.j2' %}<caret>\n")
        myFixture.type('\n')
        myFixture.checkResult("{% include 'a.j2' %}\n<caret>\n")
    }

    fun testTextAfterTheCaretKeepsThePlainEnter() {
        openTemplate("site.conf.j2", "{% if ssl %}<caret>listen 443;\n")
        myFixture.type('\n')
        myFixture.checkResult("{% if ssl %}\n<caret>listen 443;\n")
    }

    fun testSplitBetweenOpeningAndEndTag() {
        openTemplate("site.conf.j2", "  {% if ssl %}<caret>{% endif %}\n")
        myFixture.type('\n')
        myFixture.checkResult("  {% if ssl %}\n  <caret>\n  {% endif %}\n")
    }

    fun testSettingSwitchesEndTagsOff() {
        withJinjaSettings({ copy(autoInsertEndTags = false) }) {
            openTemplate("site.conf.j2", "{% if ssl %}<caret>\n")
            myFixture.type('\n')
            myFixture.checkResult("{% if ssl %}\n<caret>\n")
        }
    }

    fun testBlockScalarOfAnAnsibleTaskFile() {
        open(
            "site/roles/web/tasks/edit.yml",
            "- ansible.builtin.copy:\n    dest: /etc/hosts\n    content: |\n      {% for host in hosts %}<caret>\n",
        )
        myFixture.type('\n')
        myFixture.checkResult(
            "- ansible.builtin.copy:\n    dest: /etc/hosts\n    content: |\n      {% for host in hosts %}\n      <caret>\n      {% endfor %}\n",
        )
    }

    fun testQuotedScalarsGetNoEndTags() {
        open("site/roles/web/tasks/edit.yml", "- ansible.builtin.debug:\n    msg: \"{% if a %}<caret>\"\n")
        myFixture.type('\n')
        assertFalse(myFixture.editor.document.text, "endif" in myFixture.editor.document.text)
    }
}
