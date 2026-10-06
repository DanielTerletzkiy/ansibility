package de.terletzkiy.ansibility.settings

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionContributorEP
import com.intellij.openapi.extensions.DefaultPluginDescriptor
import com.intellij.openapi.extensions.LoadingOrder
import com.jetbrains.jsonSchema.ide.JsonSchemaService
import de.terletzkiy.ansibility.coexist.AnsibleSchemaStoreExclusion
import de.terletzkiy.ansibility.dispatch.AnsibleCompletionContributorTest
import de.terletzkiy.ansibility.dispatch.AnsibleCompletionContributorTest.Companion.DUPLICATE
import de.terletzkiy.ansibility.dispatch.AnsibleCompletionContributorTest.Companion.FOREIGN_ONLY
import de.terletzkiy.ansibility.dispatch.AnsibleCompletionContributorTest.Companion.OURS_ONLY
import de.terletzkiy.ansibility.dispatch.DispatchTestCase
import de.terletzkiy.ansibility.dispatch.RecordingCompletionSource
import de.terletzkiy.ansibility.fixtures.RequiresInfraFixture
import org.jetbrains.yaml.YAMLLanguage

/**
 * The persistent coexistence settings drive the real features (task b): X85 completion hiding through the completion
 * contributor, and the SchemaStore exclusion through the catalog exclusion and a reset of the JSON schema mappings.
 */
@RequiresInfraFixture
class CoexistenceSettingsWiringTest : DispatchTestCase() {
    private val tasksFile = "$GOLDEN_HAPROXY/tasks/completion.yml"

    override fun setUp() {
        super.setUp()
        copyFixture(GOLDEN_HAPROXY)
        AnsibilitySettingsWiring.getInstance(project).install()
    }

    override fun tearDown() {
        try {
            SettingsTestSupport.resetAll(project)
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    private fun complete(): List<String> {
        // a plain YAML value: inside `{{ }}` (injected Ansible Jinja since M5) the foreign YAML contributor never runs
        if (myFixture.findFileInTempDir(tasksFile) == null) {
            createFile(tasksFile, "- name: Configure\n  ansible.builtin.debug:\n    msg: \n")
        }
        myFixture.configureFromTempProjectFile(tasksFile)
        myFixture.editor.caretModel.moveToOffset(myFixture.editor.document.text.indexOf("msg: ") + 5)
        myFixture.completeBasic()
        return myFixture.lookupElementStrings.orEmpty()
    }

    fun testHideOtherCompletionsSettingStopsForeignItems() {
        completionSources(RecordingCompletionSource(listOf(DUPLICATE, OURS_ONLY)))
        val bean = CompletionContributorEP(
            YAMLLanguage.INSTANCE.id,
            AnsibleCompletionContributorTest.ForeignContributor::class.java.name,
            DefaultPluginDescriptor("ansibility.test.foreign"),
        )
        CompletionContributor.EP.point.registerExtension(bean, LoadingOrder.LAST, testRootDisposable)

        assertContainsElements("off by default: foreign items pass through", complete(), OURS_ONLY, FOREIGN_ONLY)
        myFixture.lookup?.hideLookup(true)

        AnsibilityAppSettings.getInstance().update { it.copy(coexistence = it.coexistence.copy(hideOtherAnsibleCompletions = true)) }
        val hidden = complete()
        assertContainsElements(hidden, DUPLICATE, OURS_ONLY)
        assertDoesntContain(hidden, FOREIGN_ONLY)
    }

    fun testSchemaStoreExclusionSettingDecidesAndResetsTheMappings() {
        val tasks = vf(CONFIGURE)
        val exclusion = AnsibleSchemaStoreExclusion()
        assertTrue("on by default", exclusion.isExcluded(project, tasks))

        var resets = 0
        val schemas = JsonSchemaService.Impl.get(project)
        val counter = Runnable { resets++ }
        schemas.registerResetAction(counter)
        try {
            val settings = AnsibilityProjectSettings.getInstance(project)
            settings.update { it.copy(paths = it.paths.copy(schemaStoreExclusion = false)) }
            assertFalse(exclusion.isExcluded(project, tasks))
            assertEquals("the toggle resets the schema mappings", 1, resets)

            settings.update { it.copy(paths = it.paths.copy(moleculeSupport = false)) }
            assertEquals("other path settings do not", 1, resets)

            settings.update { it.copy(paths = it.paths.copy(schemaStoreExclusion = true)) }
            assertTrue(exclusion.isExcluded(project, tasks))
            assertEquals(2, resets)
        } finally {
            schemas.unregisterResetAction(counter)
        }
    }
}
