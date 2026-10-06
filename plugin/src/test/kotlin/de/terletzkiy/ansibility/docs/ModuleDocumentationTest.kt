package de.terletzkiy.ansibility.docs

import com.intellij.lang.LanguageDocumentation
import com.intellij.lang.documentation.AbstractDocumentationProvider
import com.intellij.lang.documentation.ide.IdeDocumentationTargetProvider
import com.intellij.openapi.editor.Editor
import com.intellij.platform.backend.documentation.DocumentationLinkHandler
import com.intellij.platform.backend.documentation.DocumentationTarget
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import de.terletzkiy.ansibility.api.AnsibleDocService
import de.terletzkiy.ansibility.fixtures.RequiresInfraFixture
import de.terletzkiy.ansibility.semantics.CoreVersion
import org.jetbrains.yaml.YAMLLanguage

/** F5.2 module hover through the dispatch entry point, on fixture tasks (M2 acceptance 8 and 9) and synthetic ones. */
@RequiresInfraFixture
class ModuleDocumentationTest : DocsTestCase() {

    override fun setUp() {
        super.setUp()
        copyInfra(HAPROXY, PERCONA, KEYCLOAK_TASKS)
        copyDocsData()
    }

    private fun moduleTarget(path: String, offset: Int): ModuleDocumentationTarget =
        docTarget(path, offset) as? ModuleDocumentationTarget ?: error("not a module target at $path:$offset")

    fun testTemplateCardWithSourceBanner() {
        // golden/roles/haproxy/tasks/configure.yml:3
        val target = moduleTarget(CONFIGURE, at(CONFIGURE, 3, "ansible.builtin.template", 3))
        assertEquals("ansible.builtin.template", target.fqcn)
        val data = documentation(target)
        val text = plain(data.html)
        assertTrue(text, text.startsWith("ansible.builtin.template module · ansible.builtin · core 2.18.8"))
        assertTrue(text, text.contains("Template a file out to a target host"))
        assertTrue(text, text.contains("Required options: dest (path) · src (path)"))
        assertTrue(text, text.contains("check_mode: full · diff_mode: full"))
        assertTrue(text, text.contains("Returns: checksum"))
        assertTrue(text, text.contains("See also: ansible.builtin.copy"))
        assertTrue(text, text.contains("Example: - name: Template a file to /etc/file.conf ansible.builtin.template: src: /mytemplates/foo.j2"))
        assertFalse("no deprecation at 2.18.8", text.contains("Deprecated"))
        assertTrue("the X23 source banner is the last line: $text", text.endsWith(PINNED_SOURCE))
        assertTrue(data.html, data.html.contains("href=\"psi_element://ansibility-module/ansible.builtin.copy\""))
        assertTrue(data.html, data.html.contains("href=\"psi_element://ansibility-option/ansible.builtin.template/dest\""))
        assertEquals(TEMPLATE_URL, externalUrl(data))
    }

    fun testRedirectedAndDeprecatedMysqlUser() {
        // golden/roles/percona/tasks/database.yml:3
        val target = moduleTarget(PERCONA_DATABASE, at(PERCONA_DATABASE, 3, "community.mysql.mysql_user"))
        val data = documentation(target)
        val text = plain(data.html)
        assertTrue(text, text.startsWith("community.mysql.mysql_user module · ansible.mysql 5.2.0 · core 2.18.8"))
        assertTrue(text, text.contains("Redirects to ansible.mysql.mysql_user"))
        assertTrue(text, text.contains("Deprecated. The name community.mysql.mysql_user is deprecated. Removal in community.mysql 6.0.0. Use ansible.mysql.mysql_user instead."))
        assertTrue("the ansible.mysql docs are shown", text.contains("Adds or removes a user from a MySQL"))
        assertTrue(text, text.endsWith(PINNED_SOURCE))
        assertEquals("${DOCS_11}collections/ansible/mysql/mysql_user_module.html", externalUrl(data))
        assertTrue(data.html.contains("href=\"psi_element://ansibility-module/ansible.mysql.mysql_user\""))
    }

    fun testSystemdShowsSystemdServiceDocs() {
        val target = moduleTarget(HAPROXY_HANDLERS, at(HAPROXY_HANDLERS, 3, "ansible.builtin.systemd"))
        val data = documentation(target)
        val text = plain(data.html)
        assertTrue(text, text.startsWith("ansible.builtin.systemd module"))
        assertTrue(text, text.contains("Documented as ansible.builtin.systemd_service"))
        assertTrue(text, text.contains("Manage systemd units"))
        assertFalse(text.contains("Deprecated"))
        assertEquals("${DOCS_11}collections/ansible/builtin/systemd_service_module.html", externalUrl(data))
    }

    fun testAptRepositoryDeprecationDependsOnTheTarget() {
        // golden/roles/haproxy/tasks/apt.yml:20: no banner at 2.18.8, deprecated from 2.21 (removal 2.25)
        val offset = at(HAPROXY_APT, 20, "ansible.builtin.apt_repository")
        val pinned = plain(documentation(moduleTarget(HAPROXY_APT, offset)).html)
        assertFalse(pinned, pinned.contains("Deprecated"))
        assertTrue(pinned, pinned.endsWith(PINNED_SOURCE))

        overrideTarget("golden", CoreVersion(2, 21, 0))
        val latest = documentation(moduleTarget(HAPROXY_APT, offset))
        val text = plain(latest.html)
        assertTrue(text, text.contains("Deprecated. Removal in ansible-core 2.25."))
        assertTrue(text, text.contains("Alternative: ansible.builtin.deb822_repository"))
        assertEquals("one banner for the module and its routing entry: $text", 1, "Deprecated.".toRegex().findAll(text).count())
        assertTrue(latest.html.contains("href=\"psi_element://ansibility-module/ansible.builtin.deb822_repository\""))
        assertTrue(text, text.endsWith("docs: ansible-core 2.21.4 bundled + latest collections"))
        assertEquals("https://docs.ansible.com/ansible/14/collections/ansible/builtin/apt_repository_module.html", externalUrl(latest))
    }

    fun testSourceBannerNamesAMismatchingTarget() {
        overrideTarget("golden", CoreVersion(2, 19, 3))
        val text = plain(documentation(moduleTarget(CONFIGURE, at(CONFIGURE, 3, "ansible.builtin.template"))).html)
        assertTrue(text, text.endsWith("docs: ansible-core 2.21.4 bundled + latest collections · target ansible-core 2.19.3"))
    }

    fun testKeycloakAuthenticationV2IsDocumented() {
        // golden/roles/keycloak/tasks/authentication_flows.yml:4: the pinned community.general 13.2.0 documents it
        val target = moduleTarget(KEYCLOAK_FLOWS, at(KEYCLOAK_FLOWS, 4, "community.general.keycloak_authentication_v2"))
        val text = plain(documentation(target).html)
        assertTrue(text, text.startsWith("community.general.keycloak_authentication_v2 module · community.general 13.2.0 · core 2.18.8"))
        assertFalse(text, text.contains("No documentation"))
    }

    fun testUnknownModuleGetsAnInfoCard() {
        val target = moduleTarget(DEMO_TASKS, offsetOf(DEMO_TASKS, "acme.custom.no_such_module"))
        val data = documentation(target)
        val text = plain(data.html)
        assertEquals("acme.custom.no_such_module module No documentation for acme.custom.no_such_module in ansible-core 2.18.8 bundled + pinned collections.", text)
        assertNull("no page to open", externalUrl(data))
        assertNull("options of an unknown module have no docs", docTargets(DEMO_TASKS, offsetOf(DEMO_TASKS, "foo: bar")).singleOrNull())
    }

    fun testRemovedModule() {
        val target = moduleTarget(DEMO_TASKS, offsetOf(DEMO_TASKS, "ansible.builtin.include:"))
        val data = documentation(target)
        val text = plain(data.html)
        assertTrue(text, text.contains("Removed. ansible.builtin.include no longer exists. Removed on 2023-05-16. Use include_tasks or import_tasks instead."))
        assertNull("a removed module has no page", externalUrl(data))
        assertEquals("ansible.builtin.include module (removed)", plain(hint(target)!!))
    }

    fun testActionFormAndBlockModules() {
        assertEquals("ansible.builtin.ping", moduleTarget(DEMO_TASKS, offsetOf(DEMO_TASKS, "ansible.builtin.ping data", 2)).fqcn)
        assertEquals("ansible.builtin.debug", moduleTarget(DEMO_TASKS, offsetOf(DEMO_TASKS, "ansible.builtin.debug")).fqcn)
    }

    fun testFreeFormModuleCard() {
        val text = plain(documentation(moduleTarget(DEMO_TASKS, offsetOf(DEMO_TASKS, "ansible.builtin.command"))).html)
        assertTrue(text, text.contains("Free-form: Takes a free-form string: free_form"))
    }

    fun testHint() {
        val hint = hint(moduleTarget(CONFIGURE, at(CONFIGURE, 3, "ansible.builtin.template")))!!
        assertEquals("ansible.builtin.template module — Template a file out to a target host", plain(hint))
        val mysql = hint(moduleTarget(PERCONA_DATABASE, at(PERCONA_DATABASE, 3, "community.mysql.mysql_user")))!!
        assertTrue(mysql, plain(mysql).endsWith("(deprecated)"))
    }

    fun testPresentationAndPointer() {
        val target = moduleTarget(CONFIGURE, at(CONFIGURE, 3, "ansible.builtin.template"))
        assertEquals("ansible.builtin.template", inBackgroundReadAction { target.computePresentation() }.presentableText)
        assertEquals(target, target.createPointer().dereference())
    }

    fun testLinksOpenOtherModulesAndOptions() {
        val page = moduleTarget(CONFIGURE, at(CONFIGURE, 3, "ansible.builtin.template"))
        val handler = DocumentationLinkHandler.EP_NAME.extensionList.filterIsInstance<AnsibleDocLinkHandler>().single()

        val copy = inBackgroundReadAction { handler.resolveLink(page, "psi_element://ansibility-module/ansible.builtin.copy") }
        assertNotNull(copy)
        val option = inBackgroundReadAction { handler.resolveLink(page, "psi_element://ansibility-option/ansible.builtin.template/dest") }
        assertNotNull(option)
        assertNull("undocumented option", inBackgroundReadAction { handler.resolveLink(page, "psi_element://ansibility-option/ansible.builtin.template/nope") })
        assertNull("foreign links", inBackgroundReadAction { handler.resolveLink(page, "psi_element://some.other/thing") })
        val foreign = object : DocumentationTarget by page {}
        assertNull("foreign pages", inBackgroundReadAction { handler.resolveLink(foreign, "psi_element://ansibility-module/ansible.builtin.copy") })

        // The resolved link is the copy module's card in the same root.
        val direct = ModuleDocumentationTarget(project, page.root, "ansible.builtin.copy")
        val html = inBackgroundReadAction { direct.render(AnsibleDocService.getInstance(project).moduleDoc(page.root, "ansible.builtin.copy")) }
        assertTrue(plain(html), plain(html).startsWith("ansible.builtin.copy module · ansible.builtin · core 2.18.8"))
    }

    fun testOnlyOurTargetAppearsWithACompetingLegacyProvider() {
        // M2 acceptance 12: a legacy lang.documentationProvider for YAML (SchemaStore, other Ansible plugins) gives
        // no second popup on our sites.
        val legacy = object : AbstractDocumentationProvider() {
            override fun getCustomDocumentationElement(editor: Editor, file: PsiFile, contextElement: PsiElement?, targetOffset: Int): PsiElement? =
                contextElement

            override fun generateDoc(element: PsiElement?, originalElement: PsiElement?): String = "legacy"
        }
        LanguageDocumentation.INSTANCE.addExplicitExtension(YAMLLanguage.INSTANCE, legacy, testRootDisposable)
        myFixture.configureFromTempProjectFile(CONFIGURE)
        val editor = myFixture.editor
        val file = myFixture.file
        val providers = IdeDocumentationTargetProvider.getInstance(project)

        val onModule = inBackgroundReadAction { providers.documentationTargets(editor, file, at(CONFIGURE, 3, "ansible.builtin.template", 2)) }
        assertEquals(onModule.toString(), 1, onModule.size)
        assertInstanceOf(onModule.single(), ModuleDocumentationTarget::class.java)

        val onOption = inBackgroundReadAction { providers.documentationTargets(editor, file, at(CONFIGURE, 5, "dest", 1)) }
        assertEquals(onOption.toString(), 1, onOption.size)
        assertInstanceOf(onOption.single(), OptionDocumentationTarget::class.java)

        // Control: where we classify nothing (a task name), the legacy provider is what the platform shows.
        val onName = inBackgroundReadAction { providers.documentationTargets(editor, file, at(CONFIGURE, 2, "Configure", 2)) }
        assertTrue(onName.toString(), onName.isNotEmpty() && onName.none { it is AnsibleDocTarget })
    }
}
