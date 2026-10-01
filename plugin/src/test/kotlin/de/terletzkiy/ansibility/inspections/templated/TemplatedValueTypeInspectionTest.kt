package de.terletzkiy.ansibility.inspections.templated

import com.intellij.lang.annotation.HighlightSeverity
import de.terletzkiy.ansibility.semantics.CoreVersion
import de.terletzkiy.ansibility.semantics.diagnostics.Preset
import de.terletzkiy.ansibility.typeflow.TemplatedTestCase

/**
 * ANS-T020 through the highlighting pass (M4 acceptance 4): the haproxy chain at
 * `golden/roles/haproxy/defaults/main.yml:15`, the severity rules (presets, certain rejections), the quick fixes and
 * the registration.
 */
class TemplatedValueTypeInspectionTest : TemplatedTestCase() {

    private fun problems(path: String): List<String> = highlights(path).map { "${line(it)}: ${it.description}" }

    /** Applies the fix called [name] at the caret. */
    private fun applyFix(name: String) {
        myFixture.launchAction(myFixture.findSingleIntention(name))
    }

    // ------------------------------------------------------------------------------------------------ M4 acceptance 4

    fun testHaproxySomaxconnIsRedAndNamesTheChain() {
        copyInfra("golden/roles/haproxy", "golden/playbooks", "golden/docker")
        val infos = highlights(HAPROXY_DEFAULTS)
        assertEquals(
            listOf(
                "15: documented `str` for role `haproxy` (entry point `main`), but this is int `65535` via " +
                    "`haproxy_settings_maximum_connections: 65535` (defaults/main.yml:12); ansible-core 2.18.8 would coerce it " +
                    "to `'65535'` (the role itself still receives `65535`)",
            ),
            infos.map { "${line(it)}: ${it.description}" },
        )
        assertEquals(HighlightSeverity.ERROR, infos.single().severity)
        assertEquals(
            "the whole templated scalar is highlighted",
            "'{{ haproxy_settings_maximum_connections }}'",
            myFixture.editor.document.charsSequence.subSequence(infos.single().startOffset, infos.single().endOffset).toString(),
        )
    }

    fun testAppendStringFixClearsTheFinding() {
        copyInfra("golden/roles/haproxy", "golden/playbooks", "golden/docker")
        val info = highlights(HAPROXY_DEFAULTS).single()
        myFixture.editor.caretModel.moveToOffset(info.startOffset + 3)
        applyFix("Append '| string'")
        assertEquals("haproxy_settings_kernel_somaxconn: '{{ haproxy_settings_maximum_connections | string }}'", lineText(15))
        assertEmpty(problems(HAPROXY_DEFAULTS))
    }

    fun testUnreachableRoleIsNotedButStaysRed() {
        copyInfra("golden/roles/haproxy", "golden/docker")
        createFile("golden/roles/typedemo/meta/argument_specs.yml", DEMO_SPEC)
        createFile("golden/roles/typedemo/defaults/main.yml", DEMO_DEFAULTS.trimIndent() + "\ntd_str: '{{ td_port }}'")
        val path = "golden/roles/typedemo/defaults/main.yml"
        val info = highlights(path).single()
        assertEquals(HighlightSeverity.ERROR, info.severity)
        assertTrue(info.description, info.description.endsWith("; role `typedemo` is not applied by any play in this root, so its spec may be stale"))
        rootSettings("golden") { it.copy(requireReachablePlayForRed = true) }
        assertEquals("D6 toggle demotes findings without a reachable play", HighlightSeverity.WARNING, highlights(path).single().severity)
        createFile("golden/playbooks/typedemo.yml", "- hosts: all\n  roles: [typedemo]")
        assertEquals(HighlightSeverity.ERROR, highlights(path).single().severity)
    }

    // ------------------------------------------------------------------------------------------------ severity

    private fun demo() {
        copyInfra("golden/roles/haproxy", "golden/docker")
        createFile("golden/roles/typedemo/meta/argument_specs.yml", DEMO_SPEC)
        createFile(
            "golden/roles/typedemo/defaults/main.yml",
            DEMO_DEFAULTS.trimIndent() + "\n" +
                """
                td_str: '{{ td_port }}'
                td_int: '{{ td_items }}'
                td_bool: '{{ td_port | int }}'
                td_float: '{{ td_name ~ "x" }}'
                td_path: '{{ td_items if td_port else td_items }}'
                td_list: '{{ td_port }}'
                """.trimIndent(),
        )
        createFile("golden/playbooks/typedemo.yml", "- hosts: all\n  roles: [typedemo]")
    }

    private fun severities(path: String): Map<Int, HighlightSeverity> = highlights(path).associate { line(it) to it.severity }

    fun testPresetsAndCertainRejections() {
        demo()
        val path = "golden/roles/typedemo/defaults/main.yml"
        val documented = severities(path)
        assertEquals(
            "Documented types: everything red",
            mapOf(9 to HighlightSeverity.ERROR, 10 to HighlightSeverity.ERROR, 11 to HighlightSeverity.ERROR, 12 to HighlightSeverity.ERROR, 13 to HighlightSeverity.ERROR, 14 to HighlightSeverity.ERROR),
            documented,
        )
        rootSettings("golden") { it.copy(preset = Preset.RUNTIME_FAITHFUL) }
        assertEquals(
            "Runtime-faithful: coercions yellow, the list into int (a certain rejection) stays red",
            mapOf(9 to HighlightSeverity.WARNING, 10 to HighlightSeverity.ERROR, 11 to HighlightSeverity.WARNING, 12 to HighlightSeverity.WARNING, 13 to HighlightSeverity.WARNING, 14 to HighlightSeverity.WARNING),
            severities(path),
        )
        rootSettings("golden") { it.copy(preset = Preset.STRICT) }
        assertTrue(severities(path).values.all { it == HighlightSeverity.ERROR })
    }

    fun testTargetVersionChangesTheVerdict() {
        demo()
        val path = "golden/roles/typedemo/defaults/main.yml"
        assertTrue("2.18.8: '{{ td_port | int }}' arrives as the string '8080', which is outside bool as well", 11 in severities(path))
        createFile("golden/roles/typedemo/defaults/more.yml", "td_names: ['{{ td_port | int }}']")
        assertEmpty("2.18.8 renders '| int' as a string: fine for elements: str", problems("golden/roles/typedemo/defaults/more.yml"))
        target(CoreVersion(2, 21, 4))
        assertEquals(
            listOf("1: documented `elements: str` for role `typedemo` (entry point `main`) at `td_names[0]`, but this is int from the trailing `| int` filter; ansible-core 2.21.4 would convert it to str (the role itself still receives the unconverted value)"),
            problems("golden/roles/typedemo/defaults/more.yml"),
        )
    }

    // ------------------------------------------------------------------------------------------------ fixes

    fun testFixesPerDocumentedType() {
        demo()
        val path = "golden/roles/typedemo/defaults/main.yml"
        highlights(path)
        fun fixesOn(line: Int): List<String> {
            val document = myFixture.editor.document
            myFixture.editor.caretModel.moveToOffset(document.getLineStartOffset(line - 1) + lineText(line).indexOf("'") + 2)
            return myFixture.availableIntentions.map { it.text }.filter { it.startsWith("Append") }
        }
        assertEquals(listOf("Append '| string'"), fixesOn(9))
        assertEquals("no '| int' for a list", emptyList<String>(), fixesOn(10))
        assertEquals(listOf("Append '| bool'"), fixesOn(11))
        assertEquals(listOf("Append '| float'"), fixesOn(12))
        assertEquals(listOf("Append '| string'"), fixesOn(13))
        assertEquals("nothing sensible for a list", emptyList<String>(), fixesOn(14))

        fixesOn(12)
        applyFix("Append '| float'")
        assertEquals("concatenation is parenthesised first", "td_float: '{{ (td_name ~ \"x\") | float }}'", lineText(12))
        fixesOn(13)
        applyFix("Append '| string'")
        assertEquals("td_path: '{{ (td_items if td_port else td_items) | string }}'", lineText(13))
        assertEquals(listOf(9, 10, 11, 14), highlights(path).map { line(it) })
    }

    fun testDoubleQuotedAndBlockScalarsKeepTheirStyle() {
        demo()
        target(CoreVersion(2, 21, 4))
        val path = "golden/roles/typedemo/defaults/more.yml"
        createFile(path, "td_str: \"{{ td_port -}}\"\ntd_path: |\n  {{ td_port }}\n")
        highlights(path)
        myFixture.editor.caretModel.moveToOffset(3 + myFixture.editor.document.text.indexOf("\"{{"))
        applyFix("Append '| string'")
        assertEquals("td_str: \"{{ td_port | string -}}\"", lineText(1))
        myFixture.editor.caretModel.moveToOffset(myFixture.editor.document.text.indexOf("  {{") + 4)
        applyFix("Append '| string'")
        assertEquals("  {{ td_port | string }}", lineText(3))
        assertEmpty(problems(path))
    }

    fun testNoFindingsOutsideAnsibleRootsOrForUndeclaredNames() {
        createFile("somewhere/vars.yml", "td_str: '{{ td_port }}'\ntd_port: 1")
        assertEmpty(problems("somewhere/vars.yml"))
    }
}
