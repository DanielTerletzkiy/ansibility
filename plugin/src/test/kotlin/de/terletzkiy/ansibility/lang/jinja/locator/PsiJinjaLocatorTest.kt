package de.terletzkiy.ansibility.lang.jinja.locator

import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.PlatformTestUtil
import de.terletzkiy.ansibility.api.AnsibleSite
import de.terletzkiy.ansibility.api.JinjaContainer
import de.terletzkiy.ansibility.api.JinjaLocator
import de.terletzkiy.ansibility.completion.jinja.JinjaLookupItem
import de.terletzkiy.ansibility.fixtures.RequiresInfraFixture
import de.terletzkiy.ansibility.lang.jinja.AnsibleJinjaFileType
import de.terletzkiy.ansibility.lang.jinja.filetype.J2Mappings
import de.terletzkiy.ansibility.lang.jinja.psi.AnsibleJinjaFile
import de.terletzkiy.ansibility.vars.TextJinjaLocator
import de.terletzkiy.ansibility.vars.VarTargetElement
import de.terletzkiy.ansibility.vars.VarsSiteClassifier
import de.terletzkiy.ansibility.vars.VarsTestCase
import org.jetbrains.yaml.YAMLFileType

/**
 * [PsiJinjaLocator] (plan A.4, WU C5): registered before the text locator, the same answers as the text locator on
 * every position where Jinja PSI exists (template files and injected YAML fragments), and the M5 acceptance checks 4–6
 * on the sanitised infra fixture (line numbers identical to the real repo).
 */
@RequiresInfraFixture
class PsiJinjaLocatorTest : VarsTestCase() {
    private val psiLocator = PsiJinjaLocator()
    private val textLocator = TextJinjaLocator()

    private fun locate(path: String, line: Int, marker: String, delta: Int = 0): AnsibleSite.VarRef? {
        val offset = offsetAt(path, line, marker, delta)
        return runReadActionBlocking { psiLocator.locate(psi(path), offset) }
    }

    /** Runs the file type refresh the copied `ansible.cfg` schedules (`invokeLater`) and waits for re-indexing. */
    private fun settle() {
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        IndexingTestUtil.waitUntilIndexesAreReady(project)
    }

    /** The fixture keeps only the raven compose template; its root needs the `ansible.cfg` and a role task file. */
    private fun ravenRoot() {
        copyInfra("repos/raven")
        createFile("repos/raven/ansible/ansible.cfg", "[defaults]\n")
        createFile("repos/raven/ansible/roles/app-raven-mono/tasks/main.yml", "- ansible.builtin.debug:\n    msg: raven\n")
        settle()
    }

    private fun rangeText(path: String, ref: AnsibleSite.VarRef): String =
        VfsUtilCore.loadText(vf(path)).substring(ref.range.startOffset, ref.range.endOffset)

    fun testIsRegisteredFirstBeforeTheTextLocator() {
        val locators = JinjaLocator.EP_NAME.extensionList
        assertTrue(locators.first() is PsiJinjaLocator)
        assertTrue(locators.last() is TextJinjaLocator)
    }

    fun testTemplateFileReferences() {
        copyVarsData("site")
        val template = "site/roles/web/templates/site.conf.j2"
        assertEquals(AnsibleJinjaFileType, vf(template).fileType)
        val ref = locate(template, 1, "web_port", 1)!!
        assertEquals("web_port", ref.name)
        assertEquals(JinjaContainer.TEMPLATE_FILE, ref.container)
        assertEquals("web_port", rangeText(template, ref))
        assertNull("comments", locate(template, 2, "commented_var", 1))
        assertNull("raw bodies", locate(template, 3, "raw_var", 1))
        assertNull("string literals", locate(template, 5, "not_var", 1))
        assertNull("filter names", locate(template, 5, "default", 1))
        val local = locate(template, 5, "local_name", 1)!!
        assertTrue(local.localNames.toString(), "local_name" in local.localNames)
        val loopTarget = locate(template, 6, "srv.name", 1)!!
        assertEquals("srv", loopTarget.name)
        assertTrue("srv" in loopTarget.localNames)
        val subscript = locate(template, 7, "cert_file", 1)!!
        assertEquals(listOf("floating", "ssl", "cert_file"), subscript.attrPath)
        assertEquals("item.floating.ssl['cert_file']", rangeText(template, subscript))
        assertEquals(listOf("floating", "ssl"), locate(template, 7, "ssl", 1)!!.attrPath)
    }

    fun testInjectedFragmentsMapToTheHost() {
        copyVarsData("site")
        val tasks = "site/roles/web/tasks/main.yml"
        val template = locate(tasks, 4, "inner", 2)!!
        assertEquals("web_nested", template.name)
        assertEquals(listOf("inner"), template.attrPath)
        assertEquals(JinjaContainer.YAML_TEMPLATE, template.container)
        assertEquals("web_nested.inner", rangeText(tasks, template))

        val expression = locate(tasks, 8, "exists", 1)!!
        assertEquals("web_stat", expression.name)
        assertEquals(listOf("stat", "exists"), expression.attrPath)
        assertEquals(JinjaContainer.YAML_EXPRESSION, expression.container)
        assertEquals("web_stat.stat.exists", rangeText(tasks, expression))
        assertNull("filter names are not variables", locate(tasks, 9, "int", 1))
        assertNull("keywords are not variables", locate(tasks, 9, "and", 1))

        val folded = locate(tasks, 35, "web_version", 4)!!
        assertEquals("web_version", rangeText(tasks, folded))
        val escaped = locate(tasks, 38, "web_version", 2)!!
        assertEquals("the double-quoted escape before it is mapped back", "web_version", rangeText(tasks, escaped))
    }

    /** Acceptance 5 (ranges): the three hard scalars of F2.2, with the `for` target `item` local at dns.yml:27. */
    fun testAcceptanceInjectionRanges() {
        copyInfra("golden/roles/deployment-target", "golden/roles/jenkins-controller", "golden/roles/system")
        val deployment = "golden/roles/deployment-target/tasks/main.yml"
        val keyfile = locate(deployment, 50, "keyfile) ~", 2)!!
        assertEquals("keyfile", keyfile.name)
        assertEquals("keyfile", rangeText(deployment, keyfile))
        assertTrue("the for target is a local", "keyfile" in keyfile.localNames)
        val list = locate(deployment, 50, "deployment_target_builder_public_key_files", 3)!!
        assertEquals("deployment_target_builder_public_key_files", rangeText(deployment, list))
        assertFalse(list.localNames.toString(), "keyfile" in list.localNames)

        val jenkins = "golden/roles/jenkins-controller/tasks/jenkins.yml"
        val folded = locate(jenkins, 139, "jenkins_proxy_config.changed", "jenkins_proxy_config.".length + 1)!!
        assertEquals("jenkins_proxy_config", folded.name)
        assertEquals(listOf("changed"), folded.attrPath)
        assertEquals(JinjaContainer.YAML_EXPRESSION, folded.container)
        assertEquals("jenkins_proxy_config.changed", rangeText(jenkins, folded))

        val dns = "golden/roles/system/tasks/dns.yml"
        val item = locate(dns, 28, "item.ip", "item.".length + 1)!!
        assertEquals("item", item.name)
        assertEquals(listOf("ip"), item.attrPath)
        assertEquals("item.ip", rangeText(dns, item))
        assertTrue("the for target shadows the task's item", "item" in item.localNames)
        val iterable = locate(dns, 27, "system_hosts_file_entries", 1)!!
        assertEquals("system_hosts_file_entries", rangeText(dns, iterable))
        assertFalse("item" in iterable.localNames)
    }

    /** Acceptance 4: Ctrl+B in the raven compose template goes to the macro and its parameter, not to inventory variables. */
    fun testAcceptanceMacroNavigation() {
        ravenRoot()
        val template = "repos/raven/ansible/roles/app-raven-mono/templates/deployment/docker-compose.yml.j2"
        assertEquals(AnsibleJinjaFileType, vf(template).fileType)
        assertTrue(psi(template) is AnsibleJinjaFile)

        val call = offsetAt(template, 31, "env(", 1)
        val callRef = runReadActionBlocking { psiLocator.locate(psi(template), call) }!!
        assertEquals("env", callRef.name)
        assertTrue("the macro is a local", "env" in callRef.localNames)
        assertEquals(callRef, classify(template, call))
        assertEquals(listOf("$template:2"), gotoTargets(template, call).map(::describe))

        val parameter = offsetAt(template, 3, "{{ key }}", 4)
        val parameterRef = runReadActionBlocking { psiLocator.locate(psi(template), parameter) }!!
        assertEquals("key", parameterRef.name)
        assertTrue("key" in parameterRef.localNames)
        val targets = gotoTargets(template, parameter)
        assertEquals(listOf("$template:2"), targets.map(::describe))
        val target = targets.single() as VarTargetElement
        assertEquals("the parameter in the macro tag", offsetAt(template, 2, "key, value"), target.location.offset)
    }

    /** Acceptance 6: `{{ postfix_| }}` in `main.cf.j2` completes with the template on the PSI path (Ansible Jinja). */
    fun testAcceptancePostfixCompletionOnThePsiPath() {
        copyInfra("repos/falcon")
        settle()
        val template = "repos/falcon/ansible/roles/postfix/templates/main.cf.j2"
        assertEquals(AnsibleJinjaFileType, vf(template).fileType)
        val ref = locate(template, 9, "postfix_relayhost", 1)!!
        assertEquals("postfix_relayhost", ref.name)
        assertEquals(JinjaContainer.TEMPLATE_FILE, ref.container)
        val start = offsetAt(template, 9, "postfix_relayhost")
        myFixture.configureFromExistingVirtualFile(vf(template))
        WriteCommandAction.runWriteCommandAction(project) {
            myFixture.editor.document.replaceString(start, start + "postfix_relayhost".length, "postfix_")
        }
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        myFixture.editor.caretModel.moveToOffset(start + "postfix_".length)
        val typed = runReadActionBlocking { psiLocator.locate(psi(template), start + "postfix_".length) }!!
        assertEquals("the PSI locator reads the edited template", "postfix_", typed.name)
        val items: List<LookupElement> = myFixture.completeBasic()?.toList().orEmpty()
        val ours = items.filter { it.`object` is JinjaLookupItem }.map { it.lookupString }
        assertContainsElements(ours, "postfix_relayhost", "postfix_header_classification", "postfix_master_entries")
    }

    /** The PSI locator gives the text locator's answer on every offset of the fixture files that have Jinja PSI. */
    fun testSameAnswersAsTheTextLocator() {
        copyVarsData("site")
        copyInfra(
            "golden/roles/haproxy", "golden/roles/deployment-target", "golden/roles/system", "golden/roles/jenkins-controller",
            "golden/roles/grafana",
        )
        ravenRoot()
        val files = listOf("site", "golden", "repos").flatMap { collect(myFixture.findFileInTempDir(it)) }
        val mismatches = ArrayList<String>()
        var compared = 0
        var answered = 0
        for (file in files) {
            val psiFile = psi(VfsUtilCore.getRelativePath(file, myFixture.tempDirFixture.getFile("")!!)!!)
            val text = psiFile.viewProvider.contents
            runReadActionBlocking {
                for (offset in 0..text.length) {
                    if (JinjaPsiSite.at(psiFile, offset) == null) continue
                    compared++
                    val expected = textLocator.locate(psiFile, offset)
                    val actual = psiLocator.locate(psiFile, offset)
                    if (actual != null) answered++
                    if (expected != actual && mismatches.size < 30) {
                        mismatches += "${file.path}@$offset <${context(text, offset)}>: text=$expected psi=$actual"
                    }
                }
            }
        }
        println("PsiJinjaLocatorTest: ${files.size} files, $compared positions with Jinja PSI, $answered references")
        assertTrue("expected Jinja PSI positions, found $compared", compared > 10_000)
        assertEmpty(mismatches.joinToString("\n"), mismatches)
    }

    fun testFallsBackWithoutPsi() {
        copyInfra("repos/falcon")
        val template = "repos/falcon/ansible/roles/postfix/templates/main.cf.j2"
        J2Mappings.withJ2As(YAMLFileType.YML) {
            assertEquals(YAMLFileType.YML, vf(template).fileType)
            val offset = offsetAt(template, 9, "postfix_relayhost", 1)
            assertNull("a .j2 kept as YAML has no Jinja PSI", runReadActionBlocking { psiLocator.locate(psi(template), offset) })
            val classified = runReadActionBlocking { VarsSiteClassifier().classify(psi(template), offset) } as AnsibleSite.VarRef
            assertEquals("the text locator answers", "postfix_relayhost", classified.name)
        }
    }

    private fun collect(dir: VirtualFile?): List<VirtualFile> {
        val result = ArrayList<VirtualFile>()
        VfsUtilCore.iterateChildrenRecursively(dir ?: return result, null) { file ->
            val name = file.name
            if (!file.isDirectory && (name.endsWith(".j2") || name.endsWith(".yml") || name.endsWith(".yaml"))) result += file
            true
        }
        return result.sortedBy { it.path }
    }

    private fun context(text: CharSequence, offset: Int): String =
        text.subSequence(maxOf(0, offset - 20), minOf(text.length, offset + 20)).toString().replace("\n", "\\n")
}
