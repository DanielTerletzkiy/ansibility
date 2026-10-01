package de.terletzkiy.ansibility.yaml

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.terletzkiy.ansibility.semantics.yaml.Resolved
import de.terletzkiy.ansibility.semantics.yaml.ScalarStyle
import de.terletzkiy.ansibility.semantics.yaml.SourceRange
import de.terletzkiy.ansibility.semantics.yaml.YEmpty
import de.terletzkiy.ansibility.semantics.yaml.YMap
import de.terletzkiy.ansibility.semantics.yaml.YScalar
import de.terletzkiy.ansibility.semantics.yaml.YSeq
import de.terletzkiy.ansibility.semantics.yaml.YValue
import de.terletzkiy.ansibility.semantics.yaml.YVault
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLKeyValue
import java.math.BigInteger

class PsiYValueAdapterTest : BasePlatformTestCase() {
    private lateinit var text: String

    private fun load(yaml: String): YValue? {
        text = yaml.trimIndent() + "\n"
        val file = myFixture.configureByText("vars.yml", text) as YAMLFile
        return PsiYValueAdapter.documentValue(file)
    }

    private fun map(yaml: String): YMap = load(yaml) as YMap

    private fun YMap.scalar(key: String): YScalar = this[key] as YScalar

    private fun YMap.map(key: String): YMap = this[key] as YMap

    private fun textAt(range: SourceRange?): String = text.substring(range!!.start, range.end)

    private fun int(value: Long) = Resolved.Int(BigInteger.valueOf(value))

    fun testPlainScalarKeepsSpellingAndRange() {
        val value = map("version: 3.10").scalar("version")
        assertEquals("3.10", value.text)
        assertEquals(ScalarStyle.PLAIN, value.style)
        assertEquals("3.10", value.sourceText)
        assertEquals("3.10", textAt(value.range))
        assertEquals(Resolved.Float(3.1), value.resolved)
    }

    fun testYaml11ResolutionOnAdapterOutput() {
        val vars = map(
            """
            float: 3.2
            quoted_float: "3.2"
            single_quoted_float: '3.2'
            mode: 0644
            quoted_mode: "0644"
            enabled: yes
            off_word: off
            tilde: ~
            date: 2024-01-01
            exponent: 1e3
            bare_equals: =
            """,
        )
        assertEquals(Resolved.Float(3.2), vars.scalar("float").resolved)
        assertEquals(Resolved.Str("3.2"), vars.scalar("quoted_float").resolved)
        assertEquals(Resolved.Str("3.2"), vars.scalar("single_quoted_float").resolved)
        assertEquals(int(420), vars.scalar("mode").resolved)
        assertEquals(Resolved.Str("0644"), vars.scalar("quoted_mode").resolved)
        assertEquals(Resolved.Bool(true), vars.scalar("enabled").resolved)
        assertEquals(Resolved.Bool(false), vars.scalar("off_word").resolved)
        assertEquals(Resolved.Null, vars.scalar("tilde").resolved)
        assertEquals(Resolved.Timestamp("2024-01-01"), vars.scalar("date").resolved)
        assertEquals(Resolved.Str("1e3"), vars.scalar("exponent").resolved)
        assertEquals(Resolved.Unloadable, vars.scalar("bare_equals").resolved)
    }

    fun testQuotedScalarsAreUnescapedAndKeepTheirQuotesInSourceText() {
        val vars = map(
            """
            double: "tab\there \u00e4"
            single: 'it''s'
            folded: "multi
              line"
            """,
        )
        assertEquals(ScalarStyle.DOUBLE_QUOTED, vars.scalar("double").style)
        assertEquals("tab\there ä", vars.scalar("double").text)
        assertEquals("\"tab\\there \\u00e4\"", vars.scalar("double").sourceText)
        assertEquals(ScalarStyle.SINGLE_QUOTED, vars.scalar("single").style)
        assertEquals("it's", vars.scalar("single").text)
        assertEquals("'it''s'", vars.scalar("single").sourceText)
        assertEquals("multi line", vars.scalar("folded").text)
    }

    fun testBlockScalarStylesAndText() {
        val vars = map(
            """
            literal: |
              line one
                indented
            folded: >-
              folded
              text
            nested:
              - |2
                   explicit indent
            """,
        )
        assertEquals(ScalarStyle.LITERAL, vars.scalar("literal").style)
        assertEquals("line one\n  indented\n", vars.scalar("literal").text)
        assertEquals(ScalarStyle.FOLDED, vars.scalar("folded").style)
        assertEquals("folded text", vars.scalar("folded").text)
        val explicit = (vars["nested"] as YSeq).items.single() as YScalar
        assertEquals("   explicit indent\n", explicit.text)
        assertTrue(explicit.sourceText.startsWith("|2"))
    }

    fun testMultiLinePlainScalarsFoldLikeYaml() {
        val value = map(
            """
            description: Maximum connections
              at kernel level

              second paragraph
            """,
        ).scalar("description")
        assertEquals("Maximum connections at kernel level\nsecond paragraph", value.text)
        assertEquals(Resolved.Str(value.text), value.resolved)
    }

    fun testExplicitTagsAreKept() {
        val vars = map(
            """
            str_tag: !!str 3.2
            unsafe: !unsafe '{{ not_templated }}'
            verbatim: !<tag:yaml.org,2002:str> 3.2
            custom: !Ref other
            """,
        )
        assertEquals("!!str", vars.scalar("str_tag").tag)
        assertEquals(Resolved.Str("3.2"), vars.scalar("str_tag").resolved)
        assertEquals("!unsafe", vars.scalar("unsafe").tag)
        assertEquals("{{ not_templated }}", vars.scalar("unsafe").text)
        assertEquals("!!str", vars.scalar("verbatim").tag)
        assertEquals(Resolved.Unloadable, vars.scalar("custom").resolved)
    }

    fun testTagAfterAnAnchorIsFound() {
        // YAMLValue.getTag() returns null here, and YAMLScalar.getTextValue() gives `a !!str 'x`.
        val vars = map(
            """
            plain: &p !!str 3.2
            quoted: &q !!str 'x'
            """,
        )
        assertEquals("!!str", vars.scalar("plain").tag)
        assertEquals(Resolved.Str("3.2"), vars.scalar("plain").resolved)
        assertEquals("x", vars.scalar("quoted").text)
        assertEquals(ScalarStyle.SINGLE_QUOTED, vars.scalar("quoted").style)
        assertEquals("'x'", vars.scalar("quoted").sourceText)
        assertEquals("&q !!str 'x'", textAt(vars.scalar("quoted").range))
    }

    fun testVaultValuesAreOpaque() {
        val vars = map(
            """
            vault_password: !vault |
              ${'$'}ANSIBLE_VAULT;1.1;AES256
              64756d6d79
            list:
              - !vault |
                ${'$'}ANSIBLE_VAULT;1.1;AES256
                64756d6d79
            """,
        )
        val vault = vars["vault_password"]
        assertInstanceOf(vault, YVault::class.java)
        assertTrue(textAt(vault!!.range).startsWith("!vault |"))
        assertInstanceOf((vars["list"] as YSeq).items.single(), YVault::class.java)
    }

    fun testEmptyValuesHaveAZeroLengthRangeAfterTheirMarker() {
        val vars = map(
            """
            no_value:
            list:
              -
              - x
            tagged_empty: !!str
            """,
        )
        val empty = vars["no_value"] as YEmpty
        assertEquals(text.indexOf("no_value:") + "no_value:".length, empty.range!!.start)
        assertEquals(empty.range!!.start, empty.range!!.end)
        val item = (vars["list"] as YSeq).items.first() as YEmpty
        assertEquals(text.indexOf("-") + 1, item.range!!.start)
        val tagged = vars.scalar("tagged_empty")
        assertEquals("", tagged.text)
        assertEquals("!!str", tagged.tag)
        assertEquals(Resolved.Str(""), tagged.resolved)
    }

    fun testAliasesResolveToTheAnchoredValueAndKeepItsRange() {
        val vars = map(
            """
            version: &version 3.2
            copy: *version
            hosts: &hosts [a, b]
            hosts_copy: *hosts
            anchor_only: &nothing
            nothing_copy: *nothing
            """,
        )
        val copy = vars.scalar("copy")
        assertEquals(Resolved.Float(3.2), copy.resolved)
        assertEquals(vars.scalar("version").range, copy.range)
        assertEquals(listOf("a", "b"), (vars["hosts_copy"] as YSeq).items.map { (it as YScalar).text })
        assertInstanceOf(vars["nothing_copy"], YEmpty::class.java)
    }

    fun testAnAliasUsesTheLastAnchorBeforeIt() {
        val vars = map(
            """
            first: &x 1
            sees_first: *x
            second: &x 2
            sees_second: *x
            """,
        )
        assertEquals("1", vars.scalar("sees_first").text)
        assertEquals("2", vars.scalar("sees_second").text)
    }

    fun testUndefinedAliasIsEmpty() {
        val value = map("broken: *missing")["broken"]
        assertInstanceOf(value, YEmpty::class.java)
        assertEquals("*missing", textAt(value!!.range))
    }

    fun testRecursiveAliasesAreCycleSafe() {
        val vars = map(
            """
            list: &list [1, *list]
            map: &map {self: *map, value: 1}
            merge_self: &merge_self
              <<: *merge_self
              b: 1
            """,
        )
        val inner = (vars["list"] as YSeq).items[1] as YSeq
        assertTrue(inner.items.isEmpty())
        assertTrue(vars.map("map").map("self").entries.isEmpty())
        assertEquals(listOf("b"), vars.map("merge_self").keys)
    }

    fun testSequenceMergeEarlierMapWinsAndExplicitKeysComeFirst() {
        val vars = map(
            """
            read_only: &read_only
              "bestand.*": "SELECT"
              "performance_schema.*": "NONE"
            performance: &performance
              "performance_schema.*": "SELECT"
              "sys.*": "SELECT"
            priv:
              explicit: yes
              <<: [*read_only, *performance]
            """,
        )
        val priv = vars.map("priv")
        assertEquals(listOf("explicit", "bestand.*", "performance_schema.*", "sys.*"), priv.keys)
        assertEquals("NONE", priv.scalar("performance_schema.*").text)
        // The merged value keeps the range of its definition under `read_only`.
        val definition = vars.map("read_only").scalar("performance_schema.*")
        assertEquals(definition.range, priv.scalar("performance_schema.*").range)
    }

    fun testSeparateMergeKeysLaterWinsAndExplicitKeysAlwaysWin() {
        val vars = map(
            """
            a: &a {x: 1, y: 1}
            b: &b {x: 2, z: 2}
            two:
              <<: *a
              <<: *b
            explicit_first:
              y: 9
              <<: *a
            explicit_last:
              <<: *a
              y: 9
            """,
        )
        assertEquals("2", vars.map("two").scalar("x").text)
        assertEquals("1", vars.map("two").scalar("y").text)
        assertEquals("9", vars.map("explicit_first").scalar("y").text)
        assertEquals("9", vars.map("explicit_last").scalar("y").text)
        assertEquals(listOf("y", "x"), vars.map("explicit_last").keys)
    }

    fun testInvalidMergeSourcesAreDroppedAndQuotedMergeKeysAreOrdinary() {
        val vars = map(
            """
            scalar_source:
              <<: 1
              a: 1
            quoted:
              "<<": value
            """,
        )
        assertEquals(listOf("a"), vars.map("scalar_source").keys)
        assertEquals("value", vars.map("quoted").scalar("<<").text)
    }

    fun testNestedListsOfDicts() {
        val servers = map(
            """
            haproxy_servers:
              - name: web1
                port: 8080
                options: [check, "inter 2s"]
              - name: web2
                port: "8081"
            """,
        )["haproxy_servers"] as YSeq
        val first = servers.items[0] as YMap
        assertEquals(int(8080), first.scalar("port").resolved)
        assertEquals(listOf("check", "inter 2s"), (first["options"] as YSeq).items.map { (it as YScalar).text })
        assertEquals(Resolved.Str("8081"), (servers.items[1] as YMap).scalar("port").resolved)
    }

    fun testFlowPairsAndKeysWithoutValues() {
        val vars = map(
            """
            pairs: [a: 1, b]
            keys_only: {a, b: 1}
            """,
        )
        val pairs = vars["pairs"] as YSeq
        assertEquals("1", (pairs.items[0] as YMap).scalar("a").text)
        assertEquals("b", (pairs.items[1] as YScalar).text)
        val keysOnly = vars.map("keys_only")
        assertEquals(listOf("a", "b"), keysOnly.keys)
        assertInstanceOf(keysOnly["a"], YEmpty::class.java)
    }

    fun testKeysKeepTheirStyleAndAreUnescaped() {
        val vars = map(
            """
            yes: plain key
            "no": quoted key
            "tab\tkey": escaped
            """,
        )
        val keys = vars.entries.map { it.key }
        assertEquals(Resolved.Bool(true), keys[0].resolved)
        assertEquals(ScalarStyle.DOUBLE_QUOTED, keys[1].style)
        assertEquals(Resolved.Str("no"), keys[1].resolved)
        assertEquals("tab\tkey", keys[2].text)
        assertEquals("\"tab\\tkey\"", textAt(keys[2].range))
    }

    fun testKeyOfAndValueOfOnPsi() {
        val file = myFixture.configureByText("vars.yml", "a: 1\nempty:\n") as YAMLFile
        val (a, empty) = YamlPaths.topLevelKeyValues(file)
        assertEquals("a", PsiYValueAdapter.keyOf(a).text)
        assertEquals("1", (PsiYValueAdapter.valueOf(a) as YScalar).text)
        assertInstanceOf(PsiYValueAdapter.valueOf(empty), YEmpty::class.java)
        assertEquals(YEmpty(), PsiYValueAdapter.toYValue(null))
        assertEquals("1", (PsiYValueAdapter.toYValue(a.value) as YScalar).text)
    }

    fun testTopLevelSequenceAndFirstDocumentOnly() {
        val plays = load(
            """
            - hosts: all
              tasks: []
            ---
            second: document
            """,
        )
        assertInstanceOf(plays, YSeq::class.java)
        assertEquals(1, (plays as YSeq).items.size)
    }

    fun testDocumentValueIsNullWithoutContent() {
        assertNull(load(""))
        assertNull(load("# only a comment"))
    }

    fun testDocumentValueIsCachedUntilTheFileChanges() {
        val file = myFixture.configureByText("vars.yml", "a: 1\n") as YAMLFile
        val first = PsiYValueAdapter.documentValue(file)
        assertSame(first, PsiYValueAdapter.documentValue(file))
        WriteCommandAction.runWriteCommandAction(project) {
            myFixture.editor.document.setText("a: 2\n")
            PsiDocumentManager.getInstance(project).commitAllDocuments()
        }
        val second = PsiYValueAdapter.documentValue(file) as YMap
        assertNotSame(first, second)
        assertEquals("2", second.scalar("a").text)
    }

    fun testRangesPointIntoTheFile() {
        val vars = map(
            """
            server:
              name: web
              ports: [80, 443]
            """,
        )
        val server = vars.map("server")
        assertEquals("name: web\n  ports: [80, 443]", textAt(server.range))
        assertEquals("web", textAt(server.scalar("name").range))
        assertEquals("[80, 443]", textAt(server["ports"]!!.range))
        assertEquals("name", textAt(server.entries.first().key.range))
    }

    fun testMatchesPsiKeyValuesOneToOne() {
        val file = myFixture.configureByText("vars.yml", "a: 1\nb:\n  c: 2\n") as YAMLFile
        val value = PsiYValueAdapter.documentValue(file) as YMap
        val psiKeys = YamlPaths.topLevelKeyValues(file).map(YAMLKeyValue::getKeyText)
        assertEquals(psiKeys, value.keys)
    }
}
