package de.terletzkiy.ansibility.yaml

import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLScalar
import org.jetbrains.yaml.psi.YAMLSequenceItem

class YamlPathsTest : BasePlatformTestCase() {
    private lateinit var file: YAMLFile

    private fun configure(yaml: String): YAMLFile {
        file = myFixture.configureByText("vars.yml", yaml.trimIndent() + "\n") as YAMLFile
        return file
    }

    /** The innermost element of type [T] at the first occurrence of [marker]. */
    private inline fun <reified T : PsiElement> at(marker: String): T {
        val offset = file.text.indexOf(marker)
        assertTrue("marker not found: $marker", offset >= 0)
        return PsiTreeUtil.getParentOfType(file.findElementAt(offset), T::class.java, false)!!
    }

    private fun keyValue(path: List<String>): YAMLKeyValue =
        YamlPaths.find(YamlPaths.topLevelValue(file)!!, path) as YAMLKeyValue

    private fun doc(key: String): String? = YamlPaths.docCommentAbove(at<YAMLKeyValue>("$key:"))

    fun testKeyPathThroughNestedListsOfDicts() {
        configure(
            """
            haproxy_servers:
              - name: web1
                port: 8080
              - name: web2
                port: 8081
            """,
        )
        assertEquals(listOf("haproxy_servers", "0", "port"), YamlPaths.keyPath(at<YAMLScalar>("8080")))
        assertEquals(listOf("haproxy_servers", "1", "port"), YamlPaths.keyPath(at<YAMLKeyValue>("port: 8081")))
        assertEquals(listOf("haproxy_servers", "1"), YamlPaths.keyPath(at<YAMLSequenceItem>("- name: web2")))
        assertEquals(listOf("haproxy_servers"), YamlPaths.keyPath(file.findElementAt(0)!!))
    }

    fun testKeyPathInPlaybooksAndFlowCollections() {
        configure(
            """
            - hosts: all
              tasks:
                - name: Ping
                  ansible.builtin.ping:
                - name: Copy
                  ansible.builtin.copy: {src: a, mode: "0644"}
            """,
        )
        val mode = at<YAMLScalar>("\"0644\"")
        assertEquals(listOf("0", "tasks", "1", "ansible.builtin.copy", "mode"), YamlPaths.keyPath(mode))
        assertEquals(listOf("0", "tasks", "0", "name"), YamlPaths.keyPath(at<YAMLScalar>("Ping")))
    }

    fun testKeyPathUsesLoadedKeyText() {
        configure(
            """
            priv:
              "bestand.*": SELECT
            """,
        )
        assertEquals(listOf("priv", "bestand.*"), YamlPaths.keyPath(at<YAMLScalar>("SELECT")))
    }

    fun testFindReturnsKeyValuesAndSequenceItems() {
        configure(
            """
            servers:
              - name: web1
                port: 8080
            duplicate: first
            duplicate: second
            """,
        )
        val root = YamlPaths.topLevelValue(file)!!
        assertSame(root, YamlPaths.find(root, emptyList()))
        assertEquals("8080", keyValue(listOf("servers", "0", "port")).valueText)
        assertInstanceOf(YamlPaths.find(root, listOf("servers", "0")), YAMLSequenceItem::class.java)
        assertEquals("second", keyValue(listOf("duplicate")).valueText)
        assertNull(YamlPaths.find(root, listOf("servers", "1")))
        assertNull(YamlPaths.find(root, listOf("servers", "first")))
        assertNull(YamlPaths.find(root, listOf("missing")))
        assertNull(YamlPaths.find(root, listOf("duplicate", "deeper")))
    }

    fun testFindFollowsAliasesAndMergeKeys() {
        configure(
            """
            read_only: &read_only
              "bestand.*": "SELECT"
              "performance_schema.*": "NONE"
            performance: &performance
              "performance_schema.*": "SELECT"
              "sys.*": "SELECT"
            users:
              - priv:
                  <<: [*read_only, *performance]
                  own: "ALL"
              - priv: *read_only
            later_wins:
              <<: *read_only
              <<: *performance
            """,
        )
        val readOnly = at<YAMLKeyValue>("\"performance_schema.*\": \"NONE\"")
        assertSame(readOnly, keyValue(listOf("users", "0", "priv", "performance_schema.*")))
        assertSame(at<YAMLKeyValue>("\"sys.*\""), keyValue(listOf("users", "0", "priv", "sys.*")))
        assertSame(at<YAMLKeyValue>("own:"), keyValue(listOf("users", "0", "priv", "own")))
        assertSame(at<YAMLKeyValue>("\"bestand.*\""), keyValue(listOf("users", "1", "priv", "bestand.*")))
        val performance = at<YAMLKeyValue>("\"performance_schema.*\": \"SELECT\"")
        assertSame(performance, keyValue(listOf("later_wins", "performance_schema.*")))
    }

    fun testFindInsideFlowPairs() {
        configure("pairs: [a: 1, b]")
        assertSame(at<YAMLKeyValue>("a: 1"), keyValue(listOf("pairs", "0", "a")))
        assertEquals(listOf("pairs", "0", "a"), YamlPaths.keyPath(at<YAMLScalar>("1")))
        assertNull(YamlPaths.find(YamlPaths.topLevelValue(file)!!, listOf("pairs", "0", "b")))
    }

    fun testFindIsCycleSafe() {
        configure(
            """
            loop: &loop
              <<: *loop
              a: 1
            """,
        )
        assertNull(YamlPaths.find(YamlPaths.topLevelValue(file)!!, listOf("loop", "missing")))
        assertEquals("1", keyValue(listOf("loop", "a")).valueText)
    }

    fun testTopLevelShape() {
        configure("a: 1\nb: 2")
        assertEquals(listOf("a", "b"), YamlPaths.topLevelKeyValues(file).map { it.keyText })
        assertTrue(YamlPaths.isTopLevelMapping(file))
        assertFalse(YamlPaths.isTopLevelSequence(file))

        configure("- name: Ping\n  ansible.builtin.ping:")
        assertTrue(YamlPaths.isTopLevelSequence(file))
        assertFalse(YamlPaths.isTopLevelMapping(file))
        assertEquals(emptyList<YAMLKeyValue>(), YamlPaths.topLevelKeyValues(file))

        configure("# nothing but a comment")
        assertNull(YamlPaths.topLevelValue(file))
        assertFalse(YamlPaths.isTopLevelSequence(file))
        assertEquals(emptyList<YAMLKeyValue>(), YamlPaths.topLevelKeyValues(file))
    }

    fun testDocCommentSingleLine() {
        configure(
            """
            # Maximum connections at kernel level
            haproxy_settings_kernel_somaxconn: 65535
            """,
        )
        assertEquals("Maximum connections at kernel level", doc("haproxy_settings_kernel_somaxconn"))
    }

    fun testDocCommentMultipleLinesWithNonAsciiText() {
        configure(
            """
            ---
            # Buffer size in bytes (≥ 4 KiB, “naïve” café-grade).
            # Raised for large uploads — e.g. Größe ×2.
            #   - default: 4096
            buffer_size: 4096
            """,
        )
        assertEquals(
            "Buffer size in bytes (≥ 4 KiB, “naïve” café-grade).\nRaised for large uploads — e.g. Größe ×2.\n  - default: 4096",
            doc("buffer_size"),
        )
    }

    fun testDocCommentStopsAtABlankLine() {
        configure(
            """
            # DWH GRANTS

            # Grant profiles for the accounts below.
            #
            # They exist to carry the anchors.
            dwh_grants: {}
            # attached to nothing

            other: 1
            """,
        )
        assertEquals("Grant profiles for the accounts below.\n\nThey exist to carry the anchors.", doc("dwh_grants"))
        assertNull(doc("other"))
    }

    fun testDocCommentTrimsDecorationAndBlankEnds() {
        configure(
            """
            #####
            ## Heading
            #
            key: 1
            """,
        )
        assertEquals("Heading", doc("key"))
    }

    fun testTrailingCommentOfThePreviousLineIsNotDocumentation() {
        configure(
            """
            first: 1 # about first
            second: 2
            """,
        )
        assertNull(doc("second"))
    }

    fun testDocCommentMustBeAtTheKeyColumn() {
        configure(
            """
            parent:
              child: 1
              # still inside parent
            sibling: 2
            nested:
              # documents the nested key
              inner: 3
            """,
        )
        assertNull(doc("sibling"))
        assertEquals("documents the nested key", doc("inner"))
    }

    fun testCommentAboveASequenceItemBelongsToTheItemNotItsFirstKey() {
        configure(
            """
            users:
              # the admin account
              - name: admin
              - # inline
                name: other
            """,
        )
        assertNull(YamlPaths.docCommentAbove(at<YAMLKeyValue>("name: admin")))
        assertNull(YamlPaths.docCommentAbove(at<YAMLKeyValue>("name: other")))
    }

    fun testNoCommentGivesNull() {
        configure("plain: 1")
        assertNull(doc("plain"))
    }
}
