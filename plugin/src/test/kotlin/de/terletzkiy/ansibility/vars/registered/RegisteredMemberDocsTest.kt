package de.terletzkiy.ansibility.vars.registered

import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.psi.PsiDocumentManager
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.util.text.StringUtil
import com.intellij.platform.backend.documentation.DocumentationLinkHandler
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.CardContext
import de.terletzkiy.ansibility.api.CardSection
import de.terletzkiy.ansibility.api.CardSubject
import de.terletzkiy.ansibility.api.SiteDocumentation
import de.terletzkiy.ansibility.api.SiteNavigation
import de.terletzkiy.ansibility.dispatch.WebDocTarget
import de.terletzkiy.ansibility.resolve.register.RegisteredFixture
import de.terletzkiy.ansibility.resolve.register.RegisteredFixture.TASKS
import de.terletzkiy.ansibility.resolve.register.RegisteredFixture.TEMPLATE
import de.terletzkiy.ansibility.vars.VarDocumentationTarget
import de.terletzkiy.ansibility.vars.VarLinks
import de.terletzkiy.ansibility.vars.VarTargetElement
import de.terletzkiy.ansibility.vars.VarsTestCase

/**
 * Hover, Ctrl+B and the variable card of registered results (plan amendment FU, F1.12) on [RegisteredFixture]: the
 * return value's card on `x.stdout` in the injected `until:` and in templates, Ctrl+B to the module page at
 * `#return-<path>` (common keys to the common return values page), the name itself and undocumented members still
 * going to the `register:` line, the "Result of" rows of the variable card, the card's links and the registrations.
 */
class RegisteredMemberDocsTest : VarsTestCase() {
    override fun setUp() {
        super.setUp()
        RegisteredFixture.create { path, text -> myFixture.tempDirFixture.createFile(path, text) }
        refreshRoots()
    }

    /** The offset of [marker] (plus [delta]) on the first line of [path] that contains [line]. */
    private fun at(path: String, line: String, marker: String, delta: Int = 0): Int {
        val text = VfsUtilCore.loadText(vf(path))
        val start = StringUtil.lineColToOffset(text, RegisteredFixture.lineOf(text, line) - 1, 0)
        val index = text.indexOf(marker, start)
        check(index >= 0) { "no '$marker'" }
        return index + delta
    }

    private val untilLine = "until:"

    fun testHoverOnAMemberInTheInjectedUntilShowsTheReturnValue() {
        val target = hover(TASKS, at(TASKS, untilLine, "check.stdout", "check.st".length))
        assertTrue(target.toString(), target is RegisteredMemberDocumentationTarget)
        val card = text(html(target))
        assertTrue(card, "keepalived_floating_ip_check.stdout : str" in card)
        assertTrue(card, "return value of ansible.builtin.command" in card)
        assertTrue(card, "The command standard output." in card)
        assertTrue(card, "Type str" in card)
        assertTrue(card, "Returned always" in card)
        assertTrue(card, "Sample Clustering node" in card)
        val html = html(target)
        assertTrue(html, "collections/ansible/builtin/command_module.html#return-stdout" in html)
        assertEquals("keepalived_floating_ip_check.stdout: str · return value of ansible.builtin.command", hint(target))
    }

    fun testCtrlBOnAMemberOpensTheModulePageAtTheReturnValue() {
        val targets = gotoTargets(TASKS, at(TASKS, untilLine, "check.stdout", "check.st".length))
        val web = targets.single() as WebDocTarget
        assertTrue(web.url, web.url.endsWith("collections/ansible/builtin/command_module.html#return-stdout"))
        assertEquals("Open ansible.builtin.command docs: keepalived_floating_ip_check.stdout", web.presentableText)

        val exists = gotoTargets(TASKS, at(TASKS, "keepalived_conf.stat.exists }} {{ keepalived_floating_ip_check.rc", "stat.exists", "stat.ex".length))
        assertTrue((exists.single() as WebDocTarget).url.endsWith("stat_module.html#return-stat/exists"))

        val changed = gotoTargets(TASKS, at(TASKS, "keepalived_floating_ip_check.changed", "check.changed", "check.ch".length))
        val common = changed.single() as WebDocTarget
        assertTrue(common.url, common.url.endsWith("reference_appendices/common_return_values.html#changed"))
        assertEquals("Open “Common return values” docs: keepalived_floating_ip_check.changed", common.presentableText)
    }

    fun testCtrlBOnTheNameAndOnUndocumentedMembersGoesToTheRegisterLine() {
        val name = gotoTargets(TASKS, at(TASKS, untilLine, "keepalived_floating_ip_check.stdout", 3))
        assertEquals(listOf("site/roles/keepalived/tasks/main.yml:10"), name.map(::describe))
        val nope = gotoTargets(TASKS, at(TASKS, "keepalived_api.nope", "api.nope", "api.no".length))
        assertTrue(nope.toString(), nope.isNotEmpty() && nope.all { it is VarTargetElement })
        assertEquals("site/roles/keepalived/tasks/main.yml:19", describe(nope.first()))
        assertTrue(hover(TASKS, at(TASKS, "keepalived_api.nope", "api.nope", "api.no".length)) is VarDocumentationTarget)
    }

    fun testMembersInTemplatesAndOfLoopItems() {
        val exists = text(html(hover(TEMPLATE, at(TEMPLATE, "keepalived_conf.stat", "stat.exists", "stat.ex".length))))
        assertTrue(exists, "keepalived_conf.stat.exists : bool" in exists)
        assertTrue(exists, "If the destination path actually exists or not" in exists)
        val item = text(html(hover(TASKS, at(TASKS, "msg: \"{{ item.stdout", "item.stdout", "item.st".length))))
        assertTrue(item, "item.stdout : str" in item)
        assertTrue(item, "Set by task “Probe each address”" in item)
        val results = text(html(hover(TASKS, at(TASKS, "msg: \"{{ item.stdout", "results[0].stdout", "results[0].st".length))))
        assertTrue(results, "keepalived_pings.results[0].stdout : str" in results)
    }

    fun testTheVariableCardListsTheResult() {
        val card = text(html(hover(TASKS, at(TASKS, untilLine, "keepalived_floating_ip_check.stdout", 3))))
        assertTrue(card, "Result of ansible.builtin.command · task “Wait for keepalived to assign the floating IP” (roles/keepalived/tasks/main.yml:10)" in card)
        assertTrue(card, "Result keys cmd, delta, end, msg, rc, start, stderr, stderr_lines, stdout, stdout_lines, attempts, retries, changed" in card)
        val loop = text(html(hover(TASKS, at(TASKS, "loop: \"{{ keepalived_pings.results }}\"", "keepalived_pings", 3))))
        assertTrue(loop, "Result keys results, msg, changed, failed, skipped" in loop)
        assertTrue(loop, "Item keys cmd," in loop)
        assertTrue(loop, "item, ansible_loop_var" in loop)
        // A reference written at the end of the role sees both registering tasks.
        val placeholder = at(TASKS, "PLACEHOLDER", "PLACEHOLDER")
        myFixture.configureFromExistingVirtualFile(vf(TASKS))
        WriteCommandAction.runWriteCommandAction(project) {
            myFixture.editor.document.replaceString(placeholder, placeholder + "PLACEHOLDER".length, "keepalived_either")
        }
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        val either = text(html(hover(TASKS, placeholder + 3)))
        assertTrue(either, "Result of ansible.legacy.shell" !in either)
        assertTrue(either, "ansible.builtin.shell · task “Shell variant”" in either && "ansible.builtin.command · task “Command variant”" in either)
    }

    fun testCardsOpenedThroughLinksAndDefinitionCards() {
        val section = CardSection.EP_NAME.extensionList.single { it is RegisteredCardSection }
        val root = runReadActionBlocking { AnsibleWorkspace.getInstance(project).contextOf(vf(TASKS))!!.root }
        val viaLink = inBackgroundReadAction {
            section.section(CardSubject.Variable(root, "keepalived_conf", emptyList(), null), CardContext(project, vf(TEMPLATE), -1))
        }!!.toString()
        assertTrue(viaLink, "ansible.builtin.stat" in viaLink)
        val definition = de.terletzkiy.ansibility.api.SourceLocation(vf(TASKS), at(TASKS, "register: keepalived_either", "keepalived_either"))
        val own = inBackgroundReadAction {
            section.section(CardSubject.Variable(root, "keepalived_either", emptyList(), definition), CardContext(project, vf(TASKS), definition.offset))
        }!!.toString()
        assertTrue(own, "Shell variant" in own)
        assertFalse(own, "Command variant" in own)
        assertNull(inBackgroundReadAction {
            section.section(CardSubject.Variable(root, "keepalived_conf", emptyList(), null, local = true), CardContext(project, vf(TASKS), 0))
        })
    }

    fun testCardLinksOpenTheDefinitionAndModuleCards() {
        val target = hover(TASKS, at(TASKS, untilLine, "check.stdout", "check.st".length)) as RegisteredMemberDocumentationTarget
        assertTrue(LINK_HANDLERS.extensionList.any { it is RegisteredDocumentationLinkHandler })
        val handler = RegisteredDocumentationLinkHandler()
        val register = target.result.tasks.single().register
        val definition = inBackgroundReadAction { handler.resolveTarget(target, VarLinks.definition(register)) }
        assertTrue(definition.toString(), definition is VarDocumentationTarget)
        val module = inBackgroundReadAction { handler.resolveTarget(target, "psi_element://ansibility-module/ansible.builtin.command") }
        assertEquals("ModuleDocumentationTarget", module!!::class.simpleName)
        assertNull(inBackgroundReadAction { handler.resolveTarget(target, "https://example.de/") })
    }

    fun testRegistration() {
        assertTrue(SiteDocumentation.EP_NAME.extensionList.indexOfFirst { it is RegisteredSiteDocumentation } <
            SiteDocumentation.EP_NAME.extensionList.indexOfFirst { it is de.terletzkiy.ansibility.vars.VarSiteDocumentation })
        assertTrue(SiteNavigation.EP_NAME.extensionList.indexOfFirst { it is RegisteredNavigation } <
            SiteNavigation.EP_NAME.extensionList.indexOfFirst { it is de.terletzkiy.ansibility.vars.VarNavigation })
        assertTrue(CardSection.EP_NAME.extensionList.any { it is RegisteredCardSection })
    }

    private companion object {
        /** The platform's EP, looked up by name (its `EP_NAME` field is internal API). */
        val LINK_HANDLERS: ExtensionPointName<DocumentationLinkHandler> =
            ExtensionPointName.create("com.intellij.platform.backend.documentation.linkHandler")
    }
}
