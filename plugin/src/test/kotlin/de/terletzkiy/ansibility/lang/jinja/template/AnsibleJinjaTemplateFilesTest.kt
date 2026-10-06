package de.terletzkiy.ansibility.lang.jinja.template

import com.intellij.codeInsight.highlighting.HighlightErrorFilter
import com.intellij.lang.Language
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.ex.util.LayeredLexerEditorHighlighter
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory
import com.intellij.openapi.fileTypes.PlainTextLanguage
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.psi.templateLanguages.OuterLanguageElement
import com.intellij.psi.templateLanguages.TemplateDataLanguageMappings
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.util.FileContentUtilCore
import de.terletzkiy.ansibility.fixtures.RequiresInfraFixture
import de.terletzkiy.ansibility.lang.jinja.AnsibleJinjaFileType
import de.terletzkiy.ansibility.lang.jinja.AnsibleJinjaLanguage
import de.terletzkiy.ansibility.lang.jinja.filetype.AnsibleTemplatePaths
import de.terletzkiy.ansibility.lang.jinja.lexer.AnsibleJinjaTokenTypes
import de.terletzkiy.ansibility.lang.jinja.psi.AnsibleJinjaFile
import de.terletzkiy.ansibility.lang.jinja.psi.JinjaOutputTag
import de.terletzkiy.ansibility.settings.JinjaSettings
import de.terletzkiy.ansibility.settings.OuterLanguageRule
import org.jetbrains.yaml.YAMLLanguage
import org.jetbrains.yaml.YAMLTokenTypes

/**
 * Template files (plan A.5, F2.1, M5 acceptance 1–3): the view provider, the outer language per sample file, user
 * overrides, the layered highlighter, the hidden outer-language errors and the no-op formatter.
 */
@RequiresInfraFixture
class AnsibleJinjaTemplateFilesTest : AnsibleJinjaTemplateTestCase() {
    fun testOuterLanguagePerSampleFile() {
        copyInfra("golden/roles/haproxy", "golden/roles/docker", "golden/roles/loki")
        val expected = mapOf(
            "golden/roles/haproxy/templates/haproxy.cfg.j2" to PlainTextLanguage.INSTANCE,
            "golden/roles/docker/templates/services/docker.service.override.conf.j2" to PlainTextLanguage.INSTANCE,
            "golden/roles/docker/templates/observability/config.alloy.j2" to PlainTextLanguage.INSTANCE,
            "golden/roles/haproxy/templates/logrotate.conf.j2" to PlainTextLanguage.INSTANCE,
            "golden/roles/loki/templates/loki/docker-compose.yaml.j2" to YAMLLanguage.INSTANCE,
            // Nginx and Dockerfile come from plugins that the test IDE does not load: plain text, never HTML
            "golden/roles/loki/templates/nginx/main.site.combined.conf.j2" to installedOrPlainText("Nginx"),
            "golden/roles/haproxy/molecule/default/Dockerfile.j2" to installedOrPlainText("Dockerfile"),
            "golden/roles/docker/templates/services/docker-cleanup.timer.j2" to installedOrPlainText("Unit File (systemd)"),
        )
        for ((path, language) in expected) {
            assertSame(path, AnsibleJinjaFileType, vf(path).fileType)
            val provider = viewProvider(path)
            assertInstanceOf(provider, AnsibleJinjaFileViewProvider::class.java)
            provider as AnsibleJinjaFileViewProvider
            assertEquals(path, language, provider.templateDataLanguage)
            assertSame(AnsibleJinjaLanguage, provider.baseLanguage)
            assertInstanceOf(provider.getPsi(AnsibleJinjaLanguage), AnsibleJinjaFile::class.java)
            assertEquals(path, language, provider.getPsi(provider.templateDataLanguage)?.language)
        }
    }

    fun testRuleLanguageIds() {
        copyInfra("golden/roles/haproxy", "golden/roles/loki")
        val jinja = JinjaSettings()
        fun rule(path: String) = jinja.outerLanguageId(vf(path).name, AnsibleTemplatePaths.relativePath(vf(path)))
        assertEquals("Dockerfile", rule("golden/roles/haproxy/molecule/default/Dockerfile.j2"))
        assertEquals("Nginx", rule("golden/roles/loki/templates/nginx/main.site.combined.conf.j2"))
        assertEquals(OuterLanguageRule.PLAIN_TEXT, rule("golden/roles/haproxy/templates/haproxy.cfg.j2"))
        assertEquals(OuterLanguageRule.PLAIN_TEXT, rule("golden/roles/loki/templates/logrotate.conf.j2"))
        assertEquals("yaml", rule("golden/roles/loki/templates/loki/docker-compose.yaml.j2"))
        assertNull(rule("golden/roles/haproxy/templates/observability/config.alloy.j2"))
        assertEquals(
            "relative to golden/, the role library root",
            "roles/loki/templates/nginx/main.site.combined.conf.j2",
            AnsibleTemplatePaths.relativePath(vf("golden/roles/loki/templates/nginx/main.site.combined.conf.j2")),
        )
    }

    fun testOuterTreeHoldsJinjaAsOuterElements() {
        copyInfra("golden/roles/loki")
        val path = "golden/roles/loki/templates/loki/docker-compose.yaml.j2"
        val provider = viewProvider(path)
        val yaml = provider.getPsi(YAMLLanguage.INSTANCE) ?: error("no YAML tree")
        val outer = PsiTreeUtil.collectElementsOfType(yaml, OuterLanguageElement::class.java)
        assertTrue(outer.isNotEmpty())
        assertTrue(outer.all { "{{" in it.text || "{%" in it.text || "{#" in it.text })
        assertEquals(provider.contents.toString(), yaml.text)
        val jinja = provider.getPsi(AnsibleJinjaLanguage) as AnsibleJinjaFile
        assertEmpty(PsiTreeUtil.findChildrenOfType(jinja, PsiErrorElement::class.java))
        val offset = provider.contents.indexOf("{{ loki_docker_network }}:")
        assertTrue(offset > 0)
        assertInstanceOf(PsiTreeUtil.getParentOfType(provider.findElementAt(offset + 4), JinjaOutputTag::class.java), JinjaOutputTag::class.java)
        assertSame(YAMLLanguage.INSTANCE, provider.findElementAt(provider.contents.indexOf("external: true"))?.language)
    }

    /** Acceptance 2: the YAML outer tree has syntax errors at `{{ loki_docker_network }}:`, none of them is visible. */
    fun testOuterLanguageErrorsAreHidden() {
        copyInfra("golden/roles/loki")
        val path = "golden/roles/loki/templates/loki/docker-compose.yaml.j2"
        val provider = viewProvider(path)
        val outerErrors = PsiTreeUtil.findChildrenOfType(provider.getPsi(YAMLLanguage.INSTANCE), PsiErrorElement::class.java)
        val filter = HighlightErrorFilter.EP_NAME.getExtensions(project).filterIsInstance<AnsibleJinjaOuterErrorFilter>().single()
        assertTrue(outerErrors.none { filter.shouldHighlightErrorElement(it) })
        myFixture.configureFromExistingVirtualFile(vf(path))
        val errors = myFixture.doHighlighting(HighlightSeverity.ERROR)
        assertEmpty(errors.joinToString { "${it.description} at ${it.startOffset}" }, errors)
    }

    fun testJinjaErrorsStayVisible() {
        val path = "roles/web/templates/broken.conf.j2"
        createFile("roles/web/tasks/main.yml", "- debug: msg=x\n")
        createFile(path, "listen {{ port + }};\n")
        myFixture.configureFromExistingVirtualFile(vf(path))
        val errors = myFixture.doHighlighting(HighlightSeverity.ERROR)
        assertEquals(errors.joinToString { it.description }, 1, errors.size)
    }

    fun testUserTemplateDataLanguageMappingWins() {
        copyInfra("golden/roles/haproxy")
        val path = "golden/roles/haproxy/templates/haproxy.cfg.j2"
        assertEquals(PlainTextLanguage.INSTANCE, (viewProvider(path) as AnsibleJinjaFileViewProvider).templateDataLanguage)
        val mappings = TemplateDataLanguageMappings.getInstance(project)
        mappings.setMapping(vf(path), YAMLLanguage.INSTANCE)
        try {
            FileContentUtilCore.reparseFiles(vf(path))
            assertEquals(YAMLLanguage.INSTANCE, (viewProvider(path) as AnsibleJinjaFileViewProvider).templateDataLanguage)
        } finally {
            mappings.setMapping(vf(path), null)
            FileContentUtilCore.reparseFiles(vf(path))
        }
        assertEquals(PlainTextLanguage.INSTANCE, (viewProvider(path) as AnsibleJinjaFileViewProvider).templateDataLanguage)
    }

    fun testSettingsRulesChooseTheOuterLanguage() {
        copyInfra("golden/roles/haproxy")
        val path = "golden/roles/haproxy/templates/haproxy.cfg.j2"
        withJinjaSettings({ copy(outerLanguageRules = listOf(OuterLanguageRule("haproxy.cfg", "yaml")) + outerLanguageRules) }) {
            assertEquals(YAMLLanguage.INSTANCE, (viewProvider(path) as AnsibleJinjaFileViewProvider).templateDataLanguage)
        }
        assertEquals(PlainTextLanguage.INSTANCE, (viewProvider(path) as AnsibleJinjaFileViewProvider).templateDataLanguage)
        withJinjaSettings({ copy(outerLanguageRules = listOf(OuterLanguageRule("*.cfg", "NoSuchLanguage"))) }) {
            assertEquals(PlainTextLanguage.INSTANCE, (viewProvider(path) as AnsibleJinjaFileViewProvider).templateDataLanguage)
        }
    }

    fun testLayeredHighlighterUsesTheOuterLexerForText() {
        copyInfra("golden/roles/loki")
        val file = vf("golden/roles/loki/templates/loki/docker-compose.yaml.j2")
        val highlighter = EditorHighlighterFactory.getInstance().createEditorHighlighter(project, file)
        assertInstanceOf(highlighter, AnsibleJinjaTemplateHighlighter::class.java)
        assertInstanceOf(highlighter, LayeredLexerEditorHighlighter::class.java)
        highlighter.setText(VfsUtilCore.loadText(file))
        val types = HashSet<Any>()
        val iterator = highlighter.createIterator(0)
        while (!iterator.atEnd()) {
            types += iterator.tokenType
            iterator.advance()
        }
        assertTrue("YAML keys in the outer text: $types", YAMLTokenTypes.SCALAR_KEY in types)
        assertTrue("Jinja delimiters: $types", AnsibleJinjaTokenTypes.VAR_START in types)
        assertFalse("outer text is handed to the outer lexer", AnsibleJinjaTokenTypes.TEXT in types)
    }

    fun testReformatNeverChangesTemplateText() {
        copyInfra("golden/roles/loki")
        val file = vf("golden/roles/loki/templates/loki/docker-compose.yaml.j2")
        myFixture.configureFromExistingVirtualFile(file)
        val before = myFixture.editor.document.text
        WriteCommandAction.runWriteCommandAction(project) {
            CodeStyleManager.getInstance(project).reformat(myFixture.file)
        }
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        assertEquals(before, myFixture.editor.document.text)
    }

    private fun installedOrPlainText(id: String): Language = AnsibleJinjaOuterLanguages.usableLanguage(id) ?: PlainTextLanguage.INSTANCE
}
