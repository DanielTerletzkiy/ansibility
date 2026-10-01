package de.terletzkiy.ansibility.completion.jinja

import de.terletzkiy.ansibility.completion.jinja.JinjaCompletionPosition.Chain
import de.terletzkiy.ansibility.completion.jinja.JinjaCompletionPosition.Key
import de.terletzkiy.ansibility.completion.jinja.JinjaCompletionPosition.Member
import de.terletzkiy.ansibility.completion.jinja.JinjaCompletionPosition.Name
import de.terletzkiy.ansibility.lang.jinja.lexer.JinjaLexMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What the caret completes in Jinja text ([JinjaCompletionPosition]); `|` marks the caret. */
class JinjaCompletionPositionTest {
    private fun at(marked: String, mode: JinjaLexMode = JinjaLexMode.TEMPLATE): JinjaCompletionPosition? {
        val caret = marked.indexOf('|').also { check(it >= 0) { "no caret in $marked" } }
        // a filter pipe written as '¦' in the samples keeps '|' free for the caret
        return JinjaCompletionPosition.at(marked.removeRange(caret, caret + 1).replace('¦', '|'), caret, mode)
    }

    private fun chain(root: String, vararg path: String) = Chain(root, path.map { Accessor.Const(it) })

    @Test
    fun namesWhereAnExpressionStarts() {
        assertEquals(Name("haproxy_"), at("{{ haproxy_| }}"))
        assertEquals(Name(""), at("{{ | }}"))
        assertEquals(Name(""), at("{{|}}"))
        assertEquals(Name("po"), at("relayhost = {{ po| }}\n"))
        assertEquals(Name("x"), at("{% if a and x| %}"))
        assertEquals(Name(""), at("{% for s in | %}"))
        assertEquals(Name("de"), at("{{ a ~ de| }}"))
        assertEquals(Name(""), at("{{ lookup('file', | ) }}"))
        assertEquals(Name(""), at("{{ x ¦ default(|) }}"))
        assertEquals(Name(""), at("{% set y = | %}"))
        assertEquals(Name("in"), at("{{ in| }}"), )
        assertEquals(Name("_chronod_"), at("not _chronod_|", JinjaLexMode.EXPRESSION))
        assertEquals(Name(""), at("|", JinjaLexMode.EXPRESSION))
        assertEquals(Name("hap"), at("hap|", JinjaLexMode.EXPRESSION))
    }

    @Test
    fun membersAfterADot() {
        assertEquals(Member(chain("item", "floating", "ssl"), ""), at("{{ item.floating.ssl.| }}"))
        assertEquals(Member(chain("item", "floating", "ssl"), "ce"), at("{{ item.floating.ssl.ce| }}"))
        assertEquals(Member(chain("server"), ""), at("{{ 'server ' ~ server.|name ~ ' ' }}"))
        assertEquals(Member(chain("ansible_facts", "default_ipv4"), ""), at("{{ ansible_facts['default_ipv4'].| }}"))
        assertEquals(Member(chain("servers", "0"), "na"), at("{{ servers[0].na| }}"))
        assertEquals(Member(chain("item", "0"), ""), at("{{ item.0.| }}"))
        assertEquals(Member(Chain("hostvars", listOf(Accessor.Dynamic)), "ansible_"), at("{{ hostvars[groups['x'][0]].ansible_| }}"))
        assertEquals(Member(chain("x"), ""), at("x.|", JinjaLexMode.EXPRESSION))
    }

    @Test
    fun quotedKeysAfterABracket() {
        assertEquals(Key(chain("ansible_facts"), "", '\'', closed = false), at("{{ ansible_facts['| }}"))
        assertEquals(Key(chain("ansible_facts"), "os", '"', closed = true), at("{{ ansible_facts[\"os|\"] }}"))
        assertEquals(Key(chain("groups"), "", '\'', closed = true), at("{{ groups['|'] }}"))
        assertEquals(Key(chain("ansible_facts", "default_ipv4"), "add", '\'', closed = false), at("{{ ansible_facts['default_ipv4']['add| }}"))
        assertEquals(Key(chain("ansible_facts"), "service", '"', closed = false), at("ansible_facts[\"service|", JinjaLexMode.EXPRESSION))
    }

    @Test
    fun namesRightAfterABracketKnowTheOwner() {
        assertEquals(Name("", chain("hostvars")), at("{{ hostvars[|] }}"))
        assertEquals(Name("inv", chain("hostvars")), at("{{ hostvars[inv| }}"))
        assertEquals(Name("", null), at("{{ [| }}"))
    }

    @Test
    fun noPositionOutsideVariableSites() {
        assertNull("outer text", at("server {| {{ x }}"))
        assertNull("outer text after a tag", at("{{ x }} li|sten"))
        assertNull("inside the delimiter", at("{|{ x }}"))
        assertNull("after the closing delimiter", at("{{ x }}|"))
        assertNull("comment", at("{# a| #}"))
        assertNull("raw body", at("{% raw %}{{ a| }}{% endraw %}"))
        assertNull("filter name", at("{{ x ¦ defa| }}"))
        assertNull("test name", at("{{ x is defi| }}"))
        assertNull("test name after not", at("{{ x is not | }}"))
        assertNull("string literal that is not a key", at("{{ lookup('fi|') }}"))
        assertNull("statement tag name", at("{% i| %}"))
        assertNull("for target", at("{% for s| in x %}"))
        assertNull("tuple for target", at("{% for k, | in x %}"))
        assertNull("set target", at("{% set y| = 1 %}"))
        assertNull("macro parameter", at("{% macro m(a| %}"))
        assertNull("import alias", at("{% import 'm.j2' as | %}"))
        assertNull("after a complete operand", at("{{ x i| }}"))
        assertNull("number", at("{{ 12| }}"))
        assertNull("member of a call result", at("{{ x.items().| }}"))
        assertNull("after a closing key quote", at("{{ groups['a'|] }}"))
    }

    @Test
    fun implicitExpressionsHaveNoDelimiters() {
        assertEquals(Name("a"), at("a|", JinjaLexMode.EXPRESSION))
        assertEquals(Member(chain("result", "stat"), "ex"), at("not result.stat.ex|", JinjaLexMode.EXPRESSION))
        assertNull(at("x ¦ bo|", JinjaLexMode.EXPRESSION))
    }
}
