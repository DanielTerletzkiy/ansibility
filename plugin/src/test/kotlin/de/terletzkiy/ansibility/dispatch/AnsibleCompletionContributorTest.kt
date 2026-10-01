package de.terletzkiy.ansibility.dispatch

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionContributorEP
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.extensions.DefaultPluginDescriptor
import com.intellij.openapi.extensions.LoadingOrder
import com.intellij.openapi.fileTypes.PlainTextLanguage
import com.intellij.openapi.project.Project
import com.intellij.testFramework.replaceService
import de.terletzkiy.ansibility.api.AnsibleSite
import de.terletzkiy.ansibility.api.CompletionSource
import de.terletzkiy.ansibility.api.JinjaContainer
import de.terletzkiy.ansibility.coexist.CoexistenceSettings
import org.jetbrains.yaml.YAMLLanguage

class AnsibleCompletionContributorTest : DispatchTestCase() {

    /**
     * Another plugin's YAML contributor: one item that duplicates ours, one of its own and a pair of twins. Each
     * item wraps its own object, so the platform does not collapse them as equal lookup elements by itself.
     */
    class ForeignContributor : CompletionContributor() {
        override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
            for ((lookupString, typeText) in listOf(DUPLICATE to FOREIGN, FOREIGN_ONLY to FOREIGN, TWIN to FOREIGN, TWIN to "$FOREIGN 2")) {
                result.addElement(LookupElementBuilder.create(Any(), lookupString).withTypeText(typeText))
            }
        }
    }

    private class FixedSettings(private val hide: Boolean) : CoexistenceSettings {
        override fun hideOtherAnsibleCompletions(): Boolean = hide
        override fun schemaStoreExclusion(project: Project): Boolean = true
    }

    private val source = RecordingCompletionSource(listOf(DUPLICATE, OURS_ONLY))
    private val tasksFile = "$GOLDEN_HAPROXY/tasks/completion.yml"

    /**
     * A plain YAML value: the foreign contributor is a YAML one, and since M5 the inside of `{{ }}` is injected Ansible
     * Jinja, where only contributors of that language (and ours, `language="any"`) run.
     */
    private val taskText = "- name: Configure\n  ansible.builtin.debug:\n    msg: <caret>\n"

    override fun setUp() {
        super.setUp()
        copyFixture(GOLDEN_HAPROXY)
        completionSources(source)
        val bean = CompletionContributorEP(YAMLLanguage.INSTANCE.id, ForeignContributor::class.java.name, DefaultPluginDescriptor("ansibility.test.foreign"))
        CompletionContributor.EP.point.registerExtension(bean, LoadingOrder.LAST, testRootDisposable)
        useSettings(hide = false)
    }

    private fun useSettings(hide: Boolean) {
        ApplicationManager.getApplication().replaceService(CoexistenceSettings::class.java, FixedSettings(hide), testRootDisposable)
    }

    /** Creates [path] from [text] (with a `<caret>` marker), completes there and returns the lookup strings. */
    private fun complete(path: String, text: String = taskText): List<String> {
        val caret = text.indexOf(CARET).coerceAtLeast(0)
        createFile(path, text.replace(CARET, ""))
        myFixture.configureFromTempProjectFile(path)
        myFixture.editor.caretModel.moveToOffset(caret)
        myFixture.completeBasic()
        return myFixture.lookupElementStrings.orEmpty()
    }

    private fun typeTextOf(lookupString: String): List<String?> = myFixture.lookupElements.orEmpty()
        .filter { it.lookupString == lookupString }
        .map { LookupElementPresentation.renderElement(it).typeText }

    fun testOurItemsAppearOnceAndForeignItemsPassThrough() {
        val items = complete(tasksFile)

        assertEquals("ours and the foreign duplicate collapse into one item: $items", listOf<String?>(OURS), typeTextOf(DUPLICATE))
        assertContainsElements(items, OURS_ONLY, FOREIGN_ONLY)
        assertEquals("foreign items that do not collide with ours stay untouched", 2, items.count { it == TWIN })
    }

    fun testClassifiedSiteIsPassedToEverySource() {
        val site = AnsibleSite.VarRef("haproxy_log_path", emptyList(), JinjaContainer.YAML_TEMPLATE, rangeOf(CONFIGURE, "haproxy_log_path"))
        val silent = RecordingClassifier { _, _ -> null }
        val classifier = RecordingClassifier { _, _ -> site }
        classifiers(silent, classifier)
        val second = RecordingCompletionSource(listOf("haproxy_second_source"))
        completionSources(source, second)

        val items = complete(tasksFile)

        assertEquals(listOf<AnsibleSite?>(site), source.sites)
        assertEquals(listOf<AnsibleSite?>(site), second.sites)
        assertContainsElements(items, OURS_ONLY, "haproxy_second_source")
        val (file, offset) = classifier.calls.single()
        assertEquals("the original file is classified, not the completion copy", psi(tasksFile), file)
        assertEquals(taskText.indexOf(CARET), offset)
        assertEquals(1, silent.calls.size)
    }

    fun testSourcesDecideWhenNoSiteIsClassified() {
        classifiers(RecordingClassifier { _, _ -> null })
        complete(tasksFile)
        assertEquals(listOf<AnsibleSite?>(null), source.sites)
    }

    fun testHideOtherAnsibleCompletionsStopsAfterOurItems() {
        useSettings(hide = true)
        val items = complete(tasksFile)

        assertContainsElements(items, DUPLICATE, OURS_ONLY)
        assertDoesntContain(items, FOREIGN_ONLY, TWIN)
    }

    /** One of our sources: [items] are (lookup string, type text, priority); each item wraps its own object. */
    private class PrioritySource(private val items: List<Triple<String, String, Double>>, private val prefix: String? = null) : CompletionSource {
        override fun complete(site: AnsibleSite?, parameters: CompletionParameters, result: CompletionResultSet) {
            val sink = prefix?.let(result::withPrefixMatcher) ?: result
            for ((lookupString, typeText, priority) in items) {
                sink.addElement(PrioritizedLookupElement.withPriority(LookupElementBuilder.create(Any(), lookupString).withTypeText(typeText), priority))
            }
        }
    }

    fun testOurOwnSourcesKeepTheHigherPriorityItemOfADuplicate() {
        completionSources(
            PrioritySource(listOf(Triple(OWN_TWIN, "first", 10.0), Triple(OURS_ONLY, "first", 1.0), Triple(TIE, "first", 5.0))),
            PrioritySource(listOf(Triple(OWN_TWIN, "second", 50.0), Triple(TIE, "second", 5.0), Triple(SECOND_ONLY, "second", 1.0))),
        )
        val items = complete(tasksFile)

        assertEquals("the higher priority wins across sources", listOf<String?>("second"), typeTextOf(OWN_TWIN))
        assertEquals("on equal priority the earlier source wins", listOf<String?>("first"), typeTextOf(TIE))
        assertContainsElements(items, OURS_ONLY, SECOND_ONLY, FOREIGN_ONLY)
    }

    fun testDuplicatesWithinOneSourceAndDerivedResultSetsStay() {
        completionSources(
            PrioritySource(listOf(Triple(OWN_TWIN, "a", 1.0), Triple(OWN_TWIN, "b", 1.0))),
            PrioritySource(listOf(Triple(OWN_TWIN, "c", 0.5), Triple(DUPLICATE, "derived", 1.0)), prefix = ""),
        )
        val items = complete(tasksFile)

        assertEquals("a source's own twins are its choice", listOf<String?>("a", "b"), typeTextOf(OWN_TWIN).sortedBy { it })
        assertEquals("items of a derived result set count as ours", listOf<String?>("derived"), typeTextOf(DUPLICATE))
        assertEquals(2, items.count { it == OWN_TWIN })
    }

    fun testWithoutOurItemsTheOtherContributorsRunAsUsual() {
        completionSources(RecordingCompletionSource(emptyList()))
        val items = complete(tasksFile)

        assertEquals(listOf<String?>(FOREIGN), typeTextOf(DUPLICATE))
        assertContainsElements(items, FOREIGN_ONLY, TWIN)
    }

    fun testOutsideRootsNothingOfOursRuns() {
        val classifier = RecordingClassifier { _, _ -> null }
        classifiers(classifier)

        val items = complete("outside/tasks/main.yml")

        assertTrue(source.sites.isEmpty())
        assertTrue(classifier.calls.isEmpty())
        assertContainsElements(items, DUPLICATE, FOREIGN_ONLY)
        assertDoesntContain(items, OURS_ONLY)
    }

    fun testFilesOfKindOtherAreSkipped() {
        val classifier = RecordingClassifier { _, _ -> null }
        classifiers(classifier)
        copyFixture(FALCON)

        complete("$FALCON/ansible/notes.yml", "foo: bar\n$CARET\n")

        assertTrue(source.sites.isEmpty())
        assertTrue(classifier.calls.isEmpty())
    }

    fun testRunsBeforeTheYamlSchemaAndWordContributors() {
        // Other `order="first"` platform contributors (e.g. the combo-box editor one) may share the head of the list.
        fun indexOf(contributors: List<CompletionContributor>, className: String) =
            contributors.indexOfFirst { it.javaClass.name == className }.also { assertTrue("$className not registered", it >= 0) }

        myFixture.configureFromTempProjectFile(CONFIGURE)
        val editor = myFixture.editor
        val yaml = CompletionContributor.forLanguage(YAMLLanguage.INSTANCE, editor)
        val ours = yaml.indexOfFirst { it is AnsibleCompletionContributor }
        assertTrue(ours >= 0)
        assertTrue(ours < indexOf(yaml, "org.jetbrains.yaml.schema.YamlJsonSchemaCompletionContributor"))
        assertTrue(ours < indexOf(yaml, "com.intellij.codeInsight.completion.WordCompletionContributor"))
        assertTrue(yaml.withIndex().all { (index, contributor) -> index > ours || !contributor.javaClass.name.startsWith("org.jetbrains.yaml") })

        val plain = CompletionContributor.forLanguage(PlainTextLanguage.INSTANCE, editor)
        assertTrue("language=\"any\" also covers other file types", plain.indexOfFirst { it is AnsibleCompletionContributor } >= 0)
    }

    companion object {
        const val CARET = "<caret>"
        const val OURS = "ours"
        const val FOREIGN = "foreign"
        const val DUPLICATE = "haproxy_duplicate_item"
        const val OURS_ONLY = "haproxy_ours_only"
        const val FOREIGN_ONLY = "haproxy_foreign_only"
        const val TWIN = "haproxy_foreign_twin"
        const val OWN_TWIN = "haproxy_own_twin"
        const val TIE = "haproxy_own_tie"
        const val SECOND_ONLY = "haproxy_second_only"
    }
}
