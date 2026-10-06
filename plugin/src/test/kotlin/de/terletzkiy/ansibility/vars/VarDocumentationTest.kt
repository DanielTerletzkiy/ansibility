package de.terletzkiy.ansibility.vars

import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.util.TextRange
import com.intellij.platform.backend.documentation.DocumentationTarget
import com.intellij.psi.PsiDocumentManager
import de.terletzkiy.ansibility.api.AnsibleSite
import de.terletzkiy.ansibility.api.SourceLocation
import de.terletzkiy.ansibility.fixtures.InfraTestData
import de.terletzkiy.ansibility.fixtures.RequiresInfraFixture
import de.terletzkiy.ansibility.lang.jinja.AnsibleJinjaFileType
import de.terletzkiy.ansibility.lang.jinja.filetype.J2Mappings
import org.jetbrains.yaml.YAMLFileType

/**
 * The variable card (plan F1.2, F4.3, F4.8; X06, X07, X84) through the plugin's documentation entry point, on the
 * sanitised infra fixture (M2 acceptance 1–4, 7, 8) and on the synthetic `vars/site` root.
 */
@RequiresInfraFixture
class VarDocumentationTest : VarsTestCase() {

    // ------------------------------------------------------------------------------------------------ M2 acceptance

    /** Acceptance 1 and 4 (golden, with the detached worktree copy present). */
    fun testGoldenHaproxyCards() {
        copyInfra("golden/roles/haproxy")
        myFixture.copyDirectoryToProject("${InfraTestData.INFRA}/${InfraTestData.WORKTREE_DIR}", "checkouts/${InfraTestData.WORKTREE_DIR}")
        refreshRoots()

        val sysctl = "golden/roles/haproxy/tasks/sysctl.yml"
        val somaxconn = hover(sysctl, offsetAt(sysctl, 11, "haproxy_settings_kernel_somaxconn", 3))
        val card = text(html(somaxconn))
        assertTrue(card, card.startsWith("haproxy_settings_kernel_somaxconn : str ⚠ role haproxy · golden · optional"))
        assertTrue(card, "Type str" in card)
        assertTrue(card, "Required no" in card)
        assertTrue(card, "Default (spec) '65535' documentation only — not applied at runtime" in card)
        val runtime = section(card, "Runtime default", *SECTIONS)
        assertTrue(runtime, "'{{ haproxy_settings_maximum_connections }}' evaluated at runtime" in runtime)
        assertTrue(runtime, "roles/haproxy/defaults/main.yml:15" in runtime)
        assertTrue("the chain to the literal", "→ int 65535 via haproxy_settings_maximum_connections (roles/haproxy/defaults/main.yml:12)" in runtime)
        assertTrue("X06 badge: spec and runtime defaults differ", "⚠ the spec default '65535' differs from the runtime default" in runtime)
        assertTrue("X06 badge: int for str through the chain", "resolves to a YAML int for documented str" in runtime)
        assertTrue("the comment of line 14", "# Maximum connections at kernel level" in runtime)
        assertTrue(card, "Set in no other definition in golden" in card)
        assertFalse("never the worktree copy", "worktrees" in html(somaxconn))
        val hint = hint(somaxconn)
        assertTrue(hint, hint.startsWith("haproxy_settings_kernel_somaxconn: str = '{{ haproxy_settings_maximum_connections"))
        assertTrue(hint, "haproxy (optional)" in hint)

        val apt = "golden/roles/haproxy/tasks/apt.yml"
        val version = text(html(hover(apt, offsetAt(apt, 24, "haproxy_backports_version", 3))))
        assertTrue("the description's Jinja is shown literally", "Repo URL uses {{ haproxy_backports_version }}-backports." in version)
        assertTrue(version, "Default (spec) '3.2'" in version)
        val versionRuntime = section(version, "Runtime default", *SECTIONS)
        assertTrue(versionRuntime, versionRuntime.startsWith(" 3.2 · roles/haproxy/defaults/main.yml:1"))
        assertTrue(versionRuntime, "⚠ the runtime default is a YAML float for documented str" in versionRuntime)
    }

    /** Acceptance 2: the template is typed as YAML (the user's `*.j2` mapping) or as plain text. */
    fun testPostfixRelayhostInTemplateOfAnyFileType() {
        copyInfra("repos/falcon")
        val template = "repos/falcon/ansible/roles/postfix/templates/main.cf.j2"
        for (type in listOf<FileType>(YAMLFileType.YML, PlainTextFileType.INSTANCE, AnsibleJinjaFileType)) {
            withJ2As(type) {
                assertEquals(type, psi(template).fileType)
                val card = text(html(hover(template, offsetAt(template, 9, "postfix_relayhost", 2))))
                assertTrue(card, card.startsWith("postfix_relayhost : str role postfix · falcon · optional"))
                val runtime = section(card, "Runtime default", *SECTIONS)
                assertTrue(runtime, runtime.startsWith(" \"\" · roles/postfix/defaults/main.yml:2"))
                val setIn = section(card, "Set in", *SECTIONS)
                for (expected in listOf(
                    "env prod environments/prod/group_vars/all/vars.yml:471 · inventory group_vars/all · level 4 · relay.mx.example.de",
                    "env test environments/test/group_vars/all/vars.yml:323 · inventory group_vars/all · level 4",
                    "all environments (playbook vars) group_vars/all/vars.yml:156 · playbook group_vars/all · level 5",
                )) {
                    assertTrue("$expected in $setIn", expected in setIn)
                }
                assertTrue("the fixture also sets it in the role's molecule inventory", "molecule roles/postfix/molecule/default/molecule.yml:56" in setIn)
                assertFalse(setIn, "golden" in setIn)
            }
        }
    }

    /** Acceptance 3 and 7: vars-file keys. */
    fun testFalconVarsFileKeys() {
        copyInfra("repos/falcon", "golden/roles/postfix")
        val prod = "repos/falcon/ansible/environments/prod/group_vars/all/vars.yml"
        val relayhost = hover(prod, offsetAt(prod, 471, "postfix_relayhost", 2))
        val card = text(html(relayhost))
        assertEquals(
            "inventory group_vars/all · env prod · level 4 · overrides postfix default \"\" (roles/postfix/defaults/main.yml:2)",
            section(card, "This definition", *SECTIONS).trim(),
        )
        val setIn = section(card, "Set in", *SECTIONS)
        assertTrue(setIn, "group_vars/all/vars.yml:156 · playbook group_vars/all · level 5" in setIn)
        assertTrue(setIn, "environments/test/group_vars/all/vars.yml:323" in setIn)
        assertFalse("the definition itself is not repeated", "vars.yml:471" in setIn)
        assertEquals("postfix_relayhost: str = \"\" · postfix (optional)", hint(relayhost))
        assertFalse("nothing from golden's postfix", "golden" in html(relayhost))

        val servers = text(html(hover(prod, offsetAt(prod, 537, "haproxy_servers", 2))))
        assertTrue(servers, servers.startsWith("haproxy_servers : list[dict] role haproxy · falcon · optional"))
        val options = section(servers, "Options", *SECTIONS)
        assertTrue(options, "name str required Server name identifier in HAProxy config." in options)
        assertTrue(options, "ip str required IP address or hostname of the backend server." in options)
        assertTrue(options, "port int required Port the backend server listens on." in options)
        assertTrue(options, "weight int optional Optional server weight for load distribution." in options)

        val port = hover(prod, offsetAt(prod, 540, "port", 1))
        val portCard = text(html(port))
        assertTrue(portCard, portCard.startsWith("haproxy_servers.port : int role haproxy · falcon · required"))
        assertTrue(portCard, "Port the backend server listens on." in portCard)
        assertTrue(portCard, "Set in (haproxy_servers)" in portCard)
    }

    /** Acceptance 8 (hover part): vault indirection never shows a value. */
    fun testVaultValuesAreNeverShown() {
        copyInfra("repos/falcon")
        val vars = "repos/falcon/ansible/group_vars/all/vars.yml"
        val html = html(hover(vars, offsetAt(vars, 172, "vault_system_access_root_pw", 3)))
        val card = text(html)
        assertTrue(card, "🔒 vault-encrypted (AES256, 1.1)" in card)
        assertTrue(card, "environments/prod/host_vars/prod-prod1/vault.yml:4" in card)
        assertFalse("no payload", InfraTestData.containsVaultPayload(html))
        assertFalse("no envelope", "\$ANSIBLE_VAULT" in html)

        val vault = "repos/falcon/ansible/environments/prod/host_vars/prod-prod1/vault.yml"
        val key = hover(vault, offsetAt(vault, 4, "vault_system_access_root_pw", 2))
        val keyCard = text(html(key))
        assertTrue(keyCard, "🔒 vault-encrypted (AES256, 1.1) in environments/prod/host_vars/prod-prod1/vault.yml:4" in keyCard)
        assertFalse(InfraTestData.containsVaultPayload(html(key)))
        assertFalse(InfraTestData.containsVaultPayload(hint(key)))
    }

    // ------------------------------------------------------------------------------------------------ synthetic root

    fun testBadgesChoicesAndDeclaredBy() {
        copyVarsData("site")
        val version = text(html(hover(TASKS, offsetAt(TASKS, 35, "web_version", 2))))
        assertTrue(version, "Choices '3.2' | '3.3'" in version)
        val runtime = section(version, "Runtime default", *SECTIONS)
        assertTrue(runtime, "⚠ the spec default '3.2' differs from the runtime default" in runtime)
        assertTrue(runtime, "⚠ the runtime default is a YAML float for documented str" in runtime)

        val port = text(html(hover(TASKS, offsetAt(TASKS, 31, "web_port", 2))))
        val declared = section(port, "Declared by", *SECTIONS)
        assertTrue(declared, "1 other role in this root declares it, required in 1" in declared)
        assertTrue("X84: the required difference is highlighted", "role other · required — differs from role web" in declared)
        assertTrue("O(web_nested) is a link", "href=\"psi_element://ansibility-var/var/web_nested\"" in html(hover(TASKS, offsetAt(TASKS, 31, "web_port", 2))))
        val setIn = section(port, "Set in", *SECTIONS)
        assertTrue(setIn, "env dev environments/dev/group_vars/all/vars.yml:2" in setIn)
        assertTrue(setIn, "molecule roles/web/molecule/default/molecule.yml:10" in setIn)
    }

    fun testCommentDocsWithoutSpec() {
        copyVarsData("site")
        val card = text(html(hover(DEFAULTS, offsetAt(DEFAULTS, 8, "web_unspecced", 2))))
        assertTrue(card, card.startsWith("web_unspecced role web · site"))
        assertTrue("X07", "Plain comment docs for an unspecced default from comment" in card)
        assertFalse("no documented type", "Type " in card)
        assertTrue(card, "This definition role defaults · level 2 · role web" in card)
    }

    fun testMultiLineRuntimeDefaultIsACodeBlock() {
        copyVarsData("site")
        val html = html(hover(DEFAULTS, offsetAt(DEFAULTS, 9, "web_motd", 2)))
        val runtime = section(text(html), "Runtime default", *SECTIONS)
        assertTrue(runtime, "Welcome to {{ inventory_hostname }} second line" in runtime)
        assertTrue(runtime, "evaluated at runtime" in runtime)
        assertTrue("a block, not inline code", "<pre" in html.substringAfter("Runtime default"))
    }

    fun testNestedReferenceShowsTheNestedOption() {
        copyVarsData("site")
        val card = text(html(hover(TASKS, offsetAt(TASKS, 4, "inner", 2))))
        assertTrue(card, card.startsWith("web_nested.inner : int role web \u00B7 site \u00B7 required"))
        assertTrue(card, "Inner value, e.g. 1. Second sentence." in card)
        assertTrue(card, "Set in (web_nested)" in card)
    }

    fun testNestedOptionsAndLinks() {
        copyVarsData("site")
        val nested = hover(DEFAULTS, offsetAt(DEFAULTS, 4, "web_nested", 2)) as VarDocumentationTarget
        val card = text(html(nested))
        val options = section(card, "Options", *SECTIONS)
        assertTrue("the first sentence survives an abbreviation", "inner int required Inner value, e.g. 1." in options)
        assertFalse(options, "Second sentence" in options)
        assertTrue("an option literally named type", "type str optional An option literally named type." in options)

        val inner = resolve(nested, VarLinks.option(listOf("inner")))
        val innerCard = text(html(inner))
        assertTrue(innerCard, innerCard.startsWith("web_nested.inner : int role web · site · required"))
        assertTrue("the nested runtime default", section(innerCard, "Runtime default", *SECTIONS).startsWith(" 0 "))

        val port = hover(TASKS, offsetAt(TASKS, 31, "web_port", 2))
        val linked = text(html(resolve(port, VarLinks.variable(listOf("web_nested")))))
        assertTrue(linked, linked.startsWith("web_nested : dict role web"))

        val definition = runReadActionBlocking { VarLinks.definition(SourceLocation(vf(GROUP_VARS), offsetAt(GROUP_VARS, 2, "web_port"))) }
        val defCard = text(html(resolve(port, definition)))
        assertTrue(defCard, "This definition inventory group_vars/all · env dev · level 4 · overrides web default 80 (roles/web/defaults/main.yml:3)" in defCard)
    }

    fun testNotesForUndefinedLoopAndLocalNames() {
        copyVarsData("site")
        val undefined = text(html(hover(TASKS, offsetAt(TASKS, 13, "web_list", 1))))
        assertTrue(undefined, undefined.startsWith("web_list no definition in site"))
        assertTrue(undefined, "Not defined, declared or set anywhere in site" in undefined)

        val loop = text(html(hover(TASKS, offsetAt(TASKS, 12, "item", 1))))
        assertTrue(loop, "loop variable of the task at roles/web/tasks/main.yml:10" in loop)

        val local = text(html(hover(TEMPLATE, offsetAt(TEMPLATE, 5, "local_name", 1))))
        assertTrue(local, local.startsWith("local_name site · Jinja local"))
        assertTrue(local, "bound by set at roles/web/templates/site.conf.j2:4" in local)
    }

    fun testVaultFileValuesAreNeverShown() {
        copyVarsData("site")
        val plain = hover(VAULT, offsetAt(VAULT, 5, "plain_in_vault", 2))
        val html = html(plain)
        assertTrue(text(html), "🔒 value hidden (vault file) in environments/dev/group_vars/all/vault.yml:5" in text(html))
        assertFalse("plaintext-never-shown" in html)
        assertFalse("plaintext-never-shown" in hint(plain))

        val secret = text(html(hover(GROUP_VARS, offsetAt(GROUP_VARS, 6, "vault_web_secret", 2))))
        assertTrue(secret, "environments/dev/group_vars/all/vault.yml:2 · inventory group_vars/all · level 4 · 🔒 vault-encrypted (AES256, 1.1)" in secret)
        assertFalse("no payload (the vars test data's own dummy hex)", "64756d6d79" in secret)
    }

    fun testPointerSurvivesEdits() {
        copyVarsData("site")
        val target = hover(DEFAULTS, offsetAt(DEFAULTS, 3, "web_port", 2))
        val pointer = runReadActionBlocking { target.createPointer() }
        val document = runReadActionBlocking { FileDocumentManager.getInstance().getDocument(vf(DEFAULTS))!! }
        WriteCommandAction.runWriteCommandAction(project) {
            document.insertString(document.text.indexOf("# Port"), "# inserted line\n\n")
            PsiDocumentManager.getInstance(project).commitDocument(document)
        }
        val restored = runReadActionBlocking { pointer.dereference() } ?: error("pointer lost after an edit")
        val card = text(html(restored))
        assertTrue(card, card.startsWith("web_port : int role web"))
        assertTrue(card, "This definition role defaults · level 2 · role web" in card)
    }

    fun testOnlyVarSitesGetCards() {
        copyVarsData("site")
        val documentation = VarSiteDocumentation()
        val site = AnsibleSite.ModuleKey("ansible.builtin.stat", TextRange(0, 1))
        assertNull(runReadActionBlocking { documentation.documentation(site, psi(TASKS)) })
    }

    private fun resolve(target: DocumentationTarget, url: String): DocumentationTarget {
        val result = inBackgroundReadAction { VarDocumentationLinkHandler().resolveTarget(target, url) }
        assertNotNull("unresolved $url", result)
        assertNotNull(inBackgroundReadAction { VarDocumentationLinkHandler().resolveLink(target, url) })
        return result!!
    }

    /**
     * Types `.j2` files inside roots as [type] for [action] (`*.j2` mapped to it, with "Keep YAML for .j2" on since the
     * M5 overrider claims them otherwise; [de.terletzkiy.ansibility.lang.jinja.AnsibleJinjaFileType] keeps the claim
     * over a `*.j2 → YAML` mapping), then restores the mapping and the settings.
     */
    private fun withJ2As(type: FileType, action: () -> Unit) = J2Mappings.withJ2As(type, action)

    companion object {
        const val TASKS = "site/roles/web/tasks/main.yml"
        const val DEFAULTS = "site/roles/web/defaults/main.yml"
        const val TEMPLATE = "site/roles/web/templates/site.conf.j2"
        const val GROUP_VARS = "site/environments/dev/group_vars/all/vars.yml"
        const val VAULT = "site/environments/dev/group_vars/all/vault.yml"
    }
}
