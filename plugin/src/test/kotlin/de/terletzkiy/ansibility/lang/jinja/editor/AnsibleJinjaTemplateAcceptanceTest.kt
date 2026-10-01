package de.terletzkiy.ansibility.lang.jinja.editor

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.fileTypes.PlainTextLanguage
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileVisitor
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import de.terletzkiy.ansibility.fixtures.InfraTestData
import de.terletzkiy.ansibility.lang.jinja.AnsibleJinjaFileType
import de.terletzkiy.ansibility.lang.jinja.AnsibleJinjaLanguage
import de.terletzkiy.ansibility.lang.jinja.filetype.AnsibleTemplatePaths
import de.terletzkiy.ansibility.lang.jinja.lexer.AnsibleJinjaTokenTypes
import de.terletzkiy.ansibility.lang.jinja.psi.AnsibleJinjaFile
import de.terletzkiy.ansibility.lang.jinja.psi.JinjaRawStatement
import de.terletzkiy.ansibility.lang.jinja.psi.JinjaVariableReference
import de.terletzkiy.ansibility.lang.jinja.refs.JinjaRefs
import de.terletzkiy.ansibility.lang.jinja.template.AnsibleJinjaFileViewProvider
import de.terletzkiy.ansibility.lang.jinja.template.AnsibleJinjaOuterLanguages
import de.terletzkiy.ansibility.lang.jinja.template.AnsibleJinjaTemplateTestCase
import de.terletzkiy.ansibility.settings.JinjaSettings
import org.jetbrains.yaml.YAMLLanguage
import org.jetbrains.yaml.psi.YAMLMapping
import org.jetbrains.yaml.inspections.YAMLDuplicatedKeysInspection
import org.jetbrains.yaml.inspections.YAMLRecursiveAliasInspection
import org.jetbrains.yaml.inspections.YAMLUnresolvedAliasInspection
import org.jetbrains.yaml.inspections.YAMLUnusedAnchorInspection

/**
 * M5 acceptance 2 and 3 on the sanitised infra fixture: no visible outer-language errors or outer-language inspection
 * warnings in any template (the YAML ones included), `docker-compose.yaml.j2:22` in particular; the raw block of
 * `config.alloy.j2` is inert; `Dockerfile.j2` uses Dockerfile as its outer language.
 */
class AnsibleJinjaTemplateAcceptanceTest : AnsibleJinjaTemplateTestCase() {
    override fun setUp() {
        super.setUp()
        myFixture.enableInspections(
            YAMLDuplicatedKeysInspection::class.java,
            YAMLUnresolvedAliasInspection::class.java,
            YAMLRecursiveAliasInspection::class.java,
            YAMLUnusedAnchorInspection::class.java,
        )
    }

    /** Acceptance 2 at line 22 (`{{ loki_docker_network }}:`), with the YAML inspections on. */
    fun testComposeTemplateLine22() {
        copyInfra("golden/roles/loki")
        val path = "golden/roles/loki/templates/loki/docker-compose.yaml.j2"
        val provider = viewProvider(path) as AnsibleJinjaFileViewProvider
        assertEquals(YAMLLanguage.INSTANCE, provider.templateDataLanguage)
        val text = provider.contents.toString()
        val line22 = text.lineSequence().drop(21).first()
        assertTrue(line22, "{{ loki_docker_network }}:" in line22)
        myFixture.configureFromExistingVirtualFile(vf(path))
        val visible = myFixture.doHighlighting().filter { it.severity >= HighlightSeverity.WEAK_WARNING }
        assertEmpty(visible.joinToString { describe(text, it) }, visible)
    }

    /** Acceptance 2 over every template of the fixture: no visible errors, no outer-language warnings. */
    fun testNoVisibleOuterLanguageNoiseInFixtureTemplates() {
        myFixture.copyDirectoryToProject(InfraTestData.INFRA, "")
        dispatchEvents()
        val templates = ArrayList<VirtualFile>()
        VfsUtilCore.visitChildrenRecursively(myFixture.findFileInTempDir(""), object : VirtualFileVisitor<Unit>() {
            override fun visitFile(file: VirtualFile): Boolean {
                if (!file.isDirectory && file.fileType == AnsibleJinjaFileType) templates += file
                return true
            }
        })
        assertTrue("expected the fixture templates, found ${templates.size}", templates.size > 150)
        val problems = ArrayList<String>()
        var yamlOuter = 0
        var hiddenOuterErrors = 0
        var duplicateKeys = 0
        for (file in templates) {
            val provider = viewProvider(file)
            if (provider.templateDataLanguage == YAMLLanguage.INSTANCE) {
                yamlOuter++
                val yaml = provider.getPsi(YAMLLanguage.INSTANCE)
                hiddenOuterErrors += PsiTreeUtil.findChildrenOfType(yaml, PsiErrorElement::class.java).size
                duplicateKeys += PsiTreeUtil.findChildrenOfType(yaml, YAMLMapping::class.java).sumOf { mapping ->
                    mapping.keyValues.groupBy { it.keyText }.values.sumOf { it.size - 1 }
                }
            }
            myFixture.configureFromExistingVirtualFile(file)
            val text = myFixture.editor.document.text
            val jinja = provider.getPsi(AnsibleJinjaLanguage) as AnsibleJinjaFile
            for (info in myFixture.doHighlighting()) {
                val outer = isOuterText(jinja, info.startOffset)
                if (info.severity >= HighlightSeverity.ERROR || info.severity >= HighlightSeverity.WEAK_WARNING && outer) {
                    problems += "${file.path}: ${describe(text, info)}"
                }
            }
        }
        println(
            "AnsibleJinjaTemplateAcceptanceTest: ${templates.size} fixture templates highlighted, $yamlOuter with YAML outer; " +
                "$hiddenOuterErrors outer-language syntax errors and $duplicateKeys duplicate YAML keys hidden; ${problems.size} visible problems",
        )
        assertEmpty(problems.take(30).joinToString("\n"), problems)
    }

    /**
     * The noise the filters hide, on a synthetic YAML-outer template: duplicate keys from `{% if %}`/`{% else %}`
     * branches (YAML's duplicate-key inspection) and YAML syntax errors from the text left around stripped tags.
     */
    fun testOuterLanguageNoiseIsHidden() {
        createFile("roles/web/tasks/main.yml", "- ansible.builtin.debug:\n    msg: x\n")
        val path = "roles/web/templates/app.yml.j2"
        createFile(
            path,
            """
            |{% if tls %}
            |port: 443
            |{% else %}
            |port: 80
            |{% endif %}
            |hosts:
            |{% for host in hosts %}
            |  - {{ host }}
            |{% endfor %}
            |  {{ extra_key }}: {{ extra_value }}
            |labels: {{ labels | to_json }}
            |""".trimMargin(),
        )
        val provider = viewProvider(path) as AnsibleJinjaFileViewProvider
        val yaml = provider.getPsi(YAMLLanguage.INSTANCE)
        val duplicates = PsiTreeUtil.findChildrenOfType(yaml, YAMLMapping::class.java).sumOf { mapping ->
            mapping.keyValues.groupBy { it.keyText }.values.sumOf { it.size - 1 }
        }
        val errors = PsiTreeUtil.findChildrenOfType(yaml, PsiErrorElement::class.java)
        println("testOuterLanguageNoiseIsHidden: $duplicates duplicate keys, ${errors.size} syntax errors: ${errors.map { it.errorDescription }}")
        assertTrue("the outer YAML tree has noise to hide", duplicates + errors.size > 0)
        myFixture.configureFromExistingVirtualFile(vf(path))
        val text = myFixture.editor.document.text
        val visible = myFixture.doHighlighting().filter { it.severity >= HighlightSeverity.WEAK_WARNING }
        assertEmpty(visible.joinToString { describe(text, it) }, visible)
    }

    /** Acceptance 3: the Go templates in the raw block are opaque: no Jinja names, tokens or errors. */
    fun testRawBlockIsInert() {
        copyInfra("golden/roles/docker")
        val path = "golden/roles/docker/templates/observability/config.alloy.j2"
        val provider = viewProvider(path) as AnsibleJinjaFileViewProvider
        assertEquals(PlainTextLanguage.INSTANCE, provider.templateDataLanguage)
        val jinja = provider.getPsi(AnsibleJinjaLanguage) as AnsibleJinjaFile
        val raw = PsiTreeUtil.findChildrenOfType(jinja, JinjaRawStatement::class.java).single()
        assertTrue(raw.content, "ToLower .level_name" in raw.content)
        val body = raw.rawText!!.textRange
        val namesInRaw = PsiTreeUtil.findChildrenOfType(jinja, JinjaVariableReference::class.java).filter { body.contains(it.textRange) }
        assertEmpty(namesInRaw)
        val refs = JinjaRefs.analyze(provider.contents)
        val leaked = (refs.references + refs.localReferences).filter { body.contains(it.range) }.map { it.name }
        assertEmpty(leaked)
        myFixture.configureFromExistingVirtualFile(vf(path))
        val iterator = (myFixture.editor as EditorEx).highlighter.createIterator(body.startOffset)
        while (!iterator.atEnd() && iterator.start < body.endOffset) {
            assertEquals(TextRange(iterator.start, iterator.end).toString(), AnsibleJinjaTokenTypes.RAW_TEXT, iterator.tokenType)
            iterator.advance()
        }
        val visible = myFixture.doHighlighting().filter { it.severity >= HighlightSeverity.WEAK_WARNING }
        assertEmpty(visible.joinToString { it.description }, visible)
    }

    /** Acceptance 3: molecule's `Dockerfile.j2` has Dockerfile as its outer language (plain text without the plugin). */
    fun testDockerfileTemplate() {
        copyInfra("golden/roles/alloy")
        val path = "golden/roles/alloy/molecule/default/Dockerfile.j2"
        assertSame(AnsibleJinjaFileType, vf(path).fileType)
        assertEquals("Dockerfile", JinjaSettings().outerLanguageId(vf(path).name, AnsibleTemplatePaths.relativePath(vf(path))))
        val expected = AnsibleJinjaOuterLanguages.usableLanguage("Dockerfile") ?: PlainTextLanguage.INSTANCE
        assertEquals(expected, (viewProvider(path) as AnsibleJinjaFileViewProvider).templateDataLanguage)
    }

    private fun viewProvider(file: VirtualFile): AnsibleJinjaFileViewProvider =
        PsiManager.getInstance(project).findViewProvider(file) as AnsibleJinjaFileViewProvider

    private fun isOuterText(jinja: AnsibleJinjaFile, offset: Int): Boolean =
        jinja.node.findLeafElementAt(offset)?.elementType in AnsibleJinjaTokenTypes.OUTER_TEXT

    private fun describe(text: String, info: HighlightInfo): String {
        val line = text.substring(0, info.startOffset.coerceAtMost(text.length)).count { it == '\n' } + 1
        return "${info.severity} '${info.description}' at line $line <${text.substring(info.startOffset, info.endOffset.coerceAtMost(text.length))}>"
    }
}
