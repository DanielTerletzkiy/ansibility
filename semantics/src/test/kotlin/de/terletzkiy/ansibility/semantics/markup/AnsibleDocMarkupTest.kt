package de.terletzkiy.ansibility.semantics.markup

import de.terletzkiy.ansibility.semantics.markup.MarkupPart.Bold
import de.terletzkiy.ansibility.semantics.markup.MarkupPart.Code
import de.terletzkiy.ansibility.semantics.markup.MarkupPart.EnvVar
import de.terletzkiy.ansibility.semantics.markup.MarkupPart.HorizontalLine
import de.terletzkiy.ansibility.semantics.markup.MarkupPart.Italic
import de.terletzkiy.ansibility.semantics.markup.MarkupPart.Link
import de.terletzkiy.ansibility.semantics.markup.MarkupPart.Module
import de.terletzkiy.ansibility.semantics.markup.MarkupPart.Option
import de.terletzkiy.ansibility.semantics.markup.MarkupPart.Plugin
import de.terletzkiy.ansibility.semantics.markup.MarkupPart.Ref
import de.terletzkiy.ansibility.semantics.markup.MarkupPart.ReturnValue
import de.terletzkiy.ansibility.semantics.markup.MarkupPart.Text
import de.terletzkiy.ansibility.semantics.markup.MarkupPart.Url
import de.terletzkiy.ansibility.semantics.markup.MarkupPart.Value
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Description strings below are verbatim from ansible-core 2.21 and community.docker module docs. */
class AnsibleDocMarkupTest {
    private fun parse(text: String) = AnsibleDocMarkup.parse(text)

    @Test
    fun `plain text stays one part`() {
        assertEquals(listOf(Text("Location to render the template to on the remote machine.")), parse("Location to render the template to on the remote machine."))
        assertEquals(emptyList<MarkupPart>(), parse(""))
    }

    @Test
    fun `code and option name`() {
        assertEquals(
            listOf(
                Text("When used with a "), Code("loop:"),
                Text(" each package will be processed individually, it is much more efficient to pass the list directly to the "),
                Option("name", listOf("name")), Text(" option."),
            ),
            parse("When used with a C(loop:) each package will be processed individually, it is much more efficient to pass the list directly to the O(name) option."),
        )
    }

    @Test
    fun `module reference`() {
        assertEquals(
            listOf(Text("The "), Module("ansible.builtin.copy"), Text(" module copies a file.")),
            parse("The M(ansible.builtin.copy) module copies a file."),
        )
    }

    @Test
    fun `sphinx ref and link with label`() {
        assertEquals(
            listOf(
                Text("For an example on how to handle more complex validation than what this option provides, see "),
                Ref("handling complex validation", "complex_configuration_validation"), Text("."),
            ),
            parse("For an example on how to handle more complex validation than what this option provides, see R(handling complex validation,complex_configuration_validation)."),
        )
        assertEquals(
            listOf(Text("Templates are processed by the "), Link("Jinja2 templating language", "https://jinja.palletsprojects.com/en/stable/"), Text(".")),
            parse("Templates are processed by the L(Jinja2 templating language,https://jinja.palletsprojects.com/en/stable/)."),
        )
        assertEquals(
            listOf(Text("("), Link("Docker reference for HEALTHCHECK", "https://docs.docker.com/reference/dockerfile/#healthcheck"), Text(")")),
            parse("(L(Docker reference for HEALTHCHECK, https://docs.docker.com/reference/dockerfile/#healthcheck))"),
        )
    }

    @Test
    fun `escapes inside V()`() {
        // The docs contain V(\\r\\n): each "\\" is an escaped backslash, so the value is the four characters \r\n.
        assertEquals(
            listOf(Text("For Windows you can use "), Module("ansible.windows.win_template"), Text(" which uses "), Value("\\r\\n"), Text(" as "), Option("newline_sequence", listOf("newline_sequence")), Text(" by default.")),
            parse("For Windows you can use M(ansible.windows.win_template) which uses V(\\\\r\\\\n) as O(newline_sequence) by default."),
        )
        assertEquals(listOf(Value("a)b")), parse("V(a\\)b)"))
        assertEquals(listOf(Text("empty string ("), Value("\"\""), Text(")")), parse("empty string (V(\"\"))"))
    }

    @Test
    fun `option referring to another plugin and plugin reference`() {
        assertEquals(
            listOf(
                Text("see the "),
                Option("jinja2_native", listOf("jinja2_native"), plugin = PluginRef("ansible.builtin.template", "lookup")),
                Text(" parameter of the "), Plugin("ansible.builtin.template", "lookup"), Text(" lookup."),
            ),
            parse("see the O(ansible.builtin.template#lookup:jinja2_native) parameter of the P(ansible.builtin.template#lookup) lookup."),
        )
    }

    @Test
    fun `option paths with array stubs and values`() {
        assertEquals(listOf(Text("Use "), Option("networks[].mac_address", listOf("networks", "mac_address")), Text(" instead.")), parse("Use O(networks[].mac_address) instead."))
        assertEquals(listOf(Text("Only valid for "), Option("mounts[].type", listOf("mounts", "type"), value = "bind"), Text(".")), parse("Only valid for O(mounts[].type=bind)."))
        assertEquals(listOf(Option("a.b", listOf("a", "b"))), parse("O(a.b)"))
        assertEquals(listOf(Option("x", listOf("x"), value = "y", ignore = true)), parse("O(ignore:x=y)"))
        assertEquals(
            listOf(Option("opt", listOf("opt"), plugin = PluginRef("ns.coll.role", "role"), entryPoint = "main")),
            parse("O(ns.coll.role#role:main:opt)"),
        )
    }

    @Test
    fun `environment variables and return values`() {
        assertEquals(
            listOf(Text("You can define "), EnvVar("DOCKER_HOST"), Text(", "), EnvVar("DOCKER_TLS_HOSTNAME"), Text(".")),
            parse("You can define E(DOCKER_HOST), E(DOCKER_TLS_HOSTNAME)."),
        )
        assertEquals(listOf(Text("See "), ReturnValue("stat.exists", listOf("stat", "exists")), Text(".")), parse("See RV(stat.exists)."))
        assertEquals(listOf(ReturnValue("results[].rc", listOf("results", "rc"), value = "0")), parse("RV(results[].rc=0)"))
    }

    @Test
    fun `italic, bold, url and horizontal line`() {
        assertEquals(
            listOf(Text("Hostnames are "), Bold("not"), Text(" allowed; "), Italic("see"), Text(" "), Url("https://github.com/ansible-collections/community.docker/issues/70"), Text(" for details.")),
            parse("Hostnames are B(not) allowed; I(see) U(https://github.com/ansible-collections/community.docker/issues/70) for details."),
        )
        assertEquals(listOf(Text("a "), HorizontalLine, Text(" b")), parse("a HORIZONTALLINE b"))
        assertEquals(listOf(Text("HORIZONTALLINES")), parse("HORIZONTALLINES"))
    }

    @Test
    fun `macros need a word boundary and a parenthesis`() {
        assertEquals(listOf(Text("ABC(x) and C (y) and MC(z)")), parse("ABC(x) and C (y) and MC(z)"))
        assertEquals(listOf(Text("äC(x)")), parse("äC(x)"), "a Unicode letter before the macro name is part of the word")
        assertEquals(listOf(Text("("), Code("x"), Text(")")), parse("(C(x))"))
    }

    @Test
    fun `unescaped macros end at the first closing parenthesis`() {
        assertEquals(listOf(Code("a(b"), Text(")")), parse("C(a(b))"))
        assertEquals(listOf(Code("a\\"), Text("b)")), parse("C(a\\)b)"), "C() has no escapes")
        assertEquals(listOf(Text("L(foo) bar, baz)")), parse("L(foo) bar, baz)"), "the comma must come before the closing parenthesis")
    }

    @Test
    fun `broken macros are kept as text`() {
        assertEquals(listOf(Text("C(unterminated")), parse("C(unterminated"))
        assertEquals(listOf(Text("L(no comma) and "), Code("ok")), parse("L(no comma) and C(ok)"))
        assertEquals(listOf(Text("P(no.type.here) "), Module("a.b.c")), parse("P(no.type.here) M(a.b.c)"))
        assertEquals(listOf(Text("O(open")), parse("O(open"))
    }

    @Test
    fun `nested looking markup is flat`() {
        assertEquals(listOf(Bold("C(x"), Text(")")), parse("B(C(x))"))
    }

    @Test
    fun `plain text rendering`() {
        assertEquals(
            "Only valid for mounts[].type=bind. See ansible.builtin.copy and https://x.test.",
            AnsibleDocMarkup.plainText("Only valid for O(mounts[].type=bind). See M(ansible.builtin.copy) and U(https://x.test)."),
        )
        assertEquals("Jinja2 templating language", AnsibleDocMarkup.plainText("L(Jinja2 templating language,https://jinja.palletsprojects.com/)"))
    }

    @Test
    fun `RST in keyword descriptions`() {
        // keyword_desc.yml: become
        assertEquals(
            listOf(
                Text("Boolean that controls if privilege escalation is used or not on Task execution. Implemented by the become plugin. See "),
                Ref("become_plugins", "become_plugins"), Text("."),
            ),
            AnsibleDocMarkup.parseRst("Boolean that controls if privilege escalation is used or not on :term:`Task` execution. Implemented by the become plugin. See :ref:`become_plugins`."),
        )
        // keyword_desc.yml: loop (RST literal) and action (Ansible markup)
        assertEquals(
            listOf(Text("saving each list element into the "), Code("item"), Text(" variable")),
            AnsibleDocMarkup.parseRst("saving each list element into the ``item`` variable"),
        )
        assertEquals(
            listOf(Text("it normally translates into a "), Code("module"), Text(" or action plugin.")),
            AnsibleDocMarkup.parseRst("it normally translates into a C(module) or action plugin."),
        )
        assertEquals(
            listOf(Text("See "), Ref("the guide", "playbooks_intro"), Text(" and "), Code("ansible.cfg"), Text(".")),
            AnsibleDocMarkup.parseRst("See :ref:`the guide <playbooks_intro>` and :file:`ansible.cfg`."),
        )
        assertEquals(
            listOf(Text("List of collections.\n\n"), Bold("Note:"), Text("\n\n    Tasks within a role do not inherit the value of "), Code("collections"), Text(".")),
            AnsibleDocMarkup.parseRst("List of collections.\n\n.. note::\n\n    Tasks within a role do not inherit the value of ``collections``."),
        )
        assertEquals(listOf(Text("A  B")), AnsibleDocMarkup.parseRst("A .. versionadded:: B"))
    }
}
