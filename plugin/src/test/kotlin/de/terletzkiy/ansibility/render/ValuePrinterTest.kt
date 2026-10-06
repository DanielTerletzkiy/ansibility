package de.terletzkiy.ansibility.render

import de.terletzkiy.ansibility.render.hover.RenderedValueHtml
import de.terletzkiy.ansibility.render.hover.ValuePrinter
import junit.framework.TestCase

/** Rendered values in the hover: Python literals one element per line, short ones and plain text unchanged. */
class ValuePrinterTest : TestCase() {
    fun testLongListPutsOneElementPerLine() {
        val text = "['/srv/app/roles/alloy/templates/config-base.alloy.j2', '/srv/app/roles/alloy/templates/config-file-logs.alloy.j2']"
        assertEquals(
            "[\n  '/srv/app/roles/alloy/templates/config-base.alloy.j2',\n  '/srv/app/roles/alloy/templates/config-file-logs.alloy.j2'\n]",
            ValuePrinter.pretty(text),
        )
    }

    fun testNestedDictIsIndentedAndQuotesProtectSeparators() {
        val text = "{'name': 'a, b [x]', 'ports': [80, 443], 'empty': [], 'more': {'k': 'vvvvvvvvvvvvvvvvvvvv'}}"
        assertEquals(
            "{\n  'name': 'a, b [x]',\n  'ports': [\n    80,\n    443\n  ],\n  'empty': [],\n  'more': {\n    'k': 'vvvvvvvvvvvvvvvvvvvv'\n  }\n}",
            ValuePrinter.pretty(text),
        )
    }

    fun testShortAndPlainValuesStayAsTheyAre() {
        assertEquals("[1, 2]", ValuePrinter.pretty("[1, 2]"))
        val plain = "relay.mx.example.de and a long sentence that is not a literal at all, really"
        assertEquals(plain, ValuePrinter.pretty(plain))
        val unbalanced = "[" + "x, ".repeat(30)
        assertEquals(unbalanced, ValuePrinter.pretty(unbalanced))
    }
}
