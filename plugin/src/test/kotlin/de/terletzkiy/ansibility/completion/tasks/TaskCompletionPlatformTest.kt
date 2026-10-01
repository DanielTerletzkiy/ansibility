package de.terletzkiy.ansibility.completion.tasks

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionContributorEP
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.openapi.extensions.DefaultPluginDescriptor
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.extensions.LoadingOrder
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.util.TextRange
import com.intellij.platform.backend.documentation.DocumentationTarget
import com.intellij.platform.backend.documentation.LookupElementDocumentationTargetProvider
import com.intellij.psi.PsiFile
import com.intellij.testFramework.DumbModeTestUtils
import com.intellij.testFramework.ExtensionTestUtil
import de.terletzkiy.ansibility.api.AnsibleSite
import de.terletzkiy.ansibility.api.CompletionSource
import de.terletzkiy.ansibility.api.JinjaContainer
import de.terletzkiy.ansibility.api.KeywordLevel
import de.terletzkiy.ansibility.api.SiteClassifier
import de.terletzkiy.ansibility.docs.KeywordDocumentationTarget
import de.terletzkiy.ansibility.docs.ModuleDocumentationTarget
import de.terletzkiy.ansibility.docs.OptionDocumentationTarget
import org.jetbrains.yaml.YAMLLanguage

/** Registration, the dispatcher's de-duplication, Ctrl+Q in the popup and the file kinds (M3 acceptance 1, 10). */
class TaskCompletionPlatformTest : TaskCompletionTestCase() {

    /** Another plugin's YAML contributor that offers `path` too, plus an item of its own. */
    class ForeignContributor : CompletionContributor() {
        override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
            result.addElement(LookupElementBuilder.create(Any(), PATH).withTypeText(FOREIGN))
            result.addElement(LookupElementBuilder.create(Any(), FOREIGN_ONLY).withTypeText(FOREIGN))
        }
    }

    override fun setUp() {
        super.setUp()
        copyInfra(HAPROXY)
    }

    private val fileTask = "- name: Create\n  ansible.builtin.file:\n    $CARET\n"

    fun testExtensionsAreRegistered() {
        assertEquals(1, CompletionSource.EP_NAME.extensionList.filterIsInstance<TaskCompletionSource>().size)
        assertEquals(1, LOOKUP_DOCS.extensionList.filterIsInstance<TaskLookupDocumentationProvider>().size)
        assertTrue("works while indexing", DumbService.isDumbAware(TaskCompletionSource()))
        assertTrue(DumbService.isDumbAware(TaskLookupDocumentationProvider()))
    }

    fun testForeignDuplicateIsShownOnce() {
        val bean = CompletionContributorEP(YAMLLanguage.INSTANCE.id, ForeignContributor::class.java.name, DefaultPluginDescriptor("ansibility.test.foreign"))
        CompletionContributor.EP.point.registerExtension(bean, LoadingOrder.LAST, testRootDisposable)

        val items = complete(SCRATCH_TASKS, fileTask)
        assertEquals("the foreign `path` collapses into ours: $items", 1, items.count { it == PATH })
        assertTrue(myFixture.lookupElements!!.single { it.lookupString == PATH }.`object` is TaskLookupObject.Option)
        assertTrue(presentation(PATH).isItemTextBold)
        assertContainsElements("foreign items without a twin pass", items, FOREIGN_ONLY)
    }

    fun testCtrlQInThePopupShowsTheDocsCards() {
        complete(SCRATCH_TASKS, fileTask)
        val option = lookupDocs(item(PATH)) as OptionDocumentationTarget
        assertEquals("ansible.builtin.file", option.fqcn)
        assertEquals(listOf(PATH), option.path)
        val html = documentation(option).html
        assertTrue(html, plain(html).contains("Path to the file being managed."))

        complete("$HAPROXY/tasks/completion2.yml", "- name: Render\n  ansible.builtin.templ$CARET\n")
        val module = lookupDocs(item("ansible.builtin.template")) as ModuleDocumentationTarget
        assertEquals("ansible.builtin.template", module.fqcn)
        assertTrue(plain(documentation(module).html).startsWith("ansible.builtin.template module"))

        complete("$HAPROXY/tasks/completion3.yml", "- name: Ping\n  ansible.builtin.ping:\n  regis$CARET\n")
        val keyword = lookupDocs(item("register")) as KeywordDocumentationTarget
        assertEquals("register", keyword.keyword)
        assertEquals(KeywordLevel.TASK, keyword.level)
    }

    fun testCtrlQOnValuesShowsTheOptionOrKeywordCard() {
        complete(SCRATCH_TASKS, "- name: Create\n  ansible.builtin.file:\n    path: /tmp/x\n    state: $CARET\n")
        val option = lookupDocs(item("directory")) as OptionDocumentationTarget
        assertEquals(listOf("state"), option.path)

        complete("$HAPROXY/tasks/completion2.yml", "- name: Ping\n  ansible.builtin.ping:\n  become: $CARET\n")
        assertEquals("become", (lookupDocs(item("true")) as KeywordDocumentationTarget).keyword)
    }

    fun testForeignLookupItemsHaveNoDocsOfOurs() {
        complete(SCRATCH_TASKS, fileTask)
        assertNull(TaskLookupDocumentationProvider().documentationTarget(myFixture.file, LookupElementBuilder.create("x"), 0))
    }

    fun testOtherAreasSitesEndTheSource() {
        val varRef = object : SiteClassifier {
            override fun classify(file: PsiFile, offset: Int): AnsibleSite =
                AnsibleSite.VarRef("haproxy_log_path", emptyList(), JinjaContainer.YAML_TEMPLATE, TextRange(offset, offset))
        }
        ExtensionTestUtil.maskExtensions(SiteClassifier.EP_NAME, listOf(varRef), testRootDisposable)
        complete(SCRATCH_TASKS, fileTask)
        assertEmpty(ours())
    }

    fun testFilesOfOtherKindsGetNothing() {
        complete("$HAPROXY/defaults/completion.yml", "haproxy_x:\n  $CARET\n")
        assertEmpty(ours())
        complete("$HAPROXY/vars/completion.yml", "haproxy_y:\n  $CARET\n")
        assertEmpty(ours())
    }

    fun testMoleculeFilesAreTaskLike() {
        // golden/roles/haproxy/molecule/default/converge.yml is a molecule playbook.
        completeInserted("$HAPROXY/molecule/default/converge.yml", 5, "  $CARET\n")
        assertContainsElements(ourStrings(), "pre_tasks", "vars_files", "become")
        assertDoesntContain("converge.yml:5-6 sets hosts and roles", ourStrings(), "hosts", "roles")
        complete("$HAPROXY/molecule/default/verify_tasks.yml", "- name: Check\n  $CARET\n")
        assertContainsElements(ourStrings(), "ansible.builtin.assert", "register")
    }

    fun testWorksWhileIndexing() {
        val text = "- name: Render\n  ansible.builtin.templ\n"
        myFixture.tempDirFixture.createFile(SCRATCH_TASKS, text)
        refreshRoots()
        myFixture.configureFromTempProjectFile(SCRATCH_TASKS)
        myFixture.editor.caretModel.moveToOffset(text.indexOf("templ") + "templ".length)
        DumbModeTestUtils.runInDumbModeSynchronously(project) {
            myFixture.completeBasic()
            assertContainsElements("modules and keywords need no indexes", ourStrings(), "ansible.builtin.template")
        }
    }

    private fun lookupDocs(element: LookupElement): DocumentationTarget? = inBackgroundReadAction {
        val file = myFixture.file
        LOOKUP_DOCS.extensionList.firstNotNullOfOrNull { it.documentationTarget(file, element, myFixture.caretOffset) }
    }

    private companion object {
        const val PATH = "path"
        const val FOREIGN = "foreign"
        const val FOREIGN_ONLY = "foreign_only_item"

        /** `@ApiStatus.Experimental` extension point of the platform. */
        val LOOKUP_DOCS = ExtensionPointName<LookupElementDocumentationTargetProvider>("com.intellij.platform.backend.documentation.lookupElementTargetProvider")
    }
}
