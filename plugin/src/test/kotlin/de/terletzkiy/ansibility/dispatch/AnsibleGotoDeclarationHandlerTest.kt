package de.terletzkiy.ansibility.dispatch

import com.intellij.codeInsight.navigation.actions.GotoDeclarationAction
import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.codeInsight.navigation.actions.GotoDeclarationOrUsageHandler2
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.psi.PsiElement
import com.intellij.testFramework.PlatformTestUtil
import de.terletzkiy.ansibility.api.AnsibleSite
import de.terletzkiy.ansibility.api.FileKind
import de.terletzkiy.ansibility.api.JinjaContainer
import de.terletzkiy.ansibility.fixtures.RequiresInfraFixture

@RequiresInfraFixture
class AnsibleGotoDeclarationHandlerTest : DispatchTestCase() {
    private val handler = AnsibleGotoDeclarationHandler()

    override fun setUp() {
        super.setUp()
        copyFixture(FALCON)
    }

    private fun varKey(name: String) =
        AnsibleSite.VarKey(listOf(name), FileKind.GROUP_VARS, rangeOf(FALCON_GROUP_VARS, "$name:").grown(-1))

    private fun targets(path: String, offset: Int): List<PsiElement>? = runReadActionBlocking {
        val file = psi(path)
        handler.getGotoDeclarationTargets(file.findElementAt(offset), offset, null)?.toList()
    }

    /** Declarations of [name] in the falcon haproxy role: the argument_specs option first, then the default. */
    private fun declarations(name: String): List<PsiElement> =
        listOfNotNull(key(FALCON_SPECS, name), runCatching { key(FALCON_DEFAULTS, name) }.getOrNull())

    fun testFirstNonEmptyNavigationWinsAndReturnsAllItsTargets() {
        val classifier = RecordingClassifier { _, _ -> varKey("haproxy_servers") }
        classifiers(RecordingClassifier { _, _ -> null }, classifier)
        val empty = RecordingNavigation { _, _ -> emptyList() }
        val vars = RecordingNavigation { site, _ -> declarations((site as AnsibleSite.VarKey).keyPath.single()) }
        val later = RecordingNavigation { _, _ -> listOf(psi(FALCON_DEFAULTS)) }
        navigation(empty, vars, later)

        val result = targets(FALCON_GROUP_VARS, offsetOf(FALCON_GROUP_VARS, "haproxy_servers:", 3))

        assertEquals(listOf(key(FALCON_SPECS, "haproxy_servers"), key(FALCON_DEFAULTS, "haproxy_servers")), result)
        assertEquals(listOf<AnsibleSite>(varKey("haproxy_servers")), empty.sites)
        assertTrue("navigation after the first non-empty answer is not asked", later.sites.isEmpty())
    }

    fun testDuplicateTargetsAreReturnedOnce() {
        classifiers(RecordingClassifier { _, _ -> varKey("haproxy_bind_ip") })
        val spec = key(FALCON_SPECS, "haproxy_bind_ip")
        navigation(RecordingNavigation { _, _ -> listOf(spec, spec) })

        assertEquals(listOf<PsiElement>(spec), targets(FALCON_GROUP_VARS, offsetOf(FALCON_GROUP_VARS, "haproxy_bind_ip:")))
    }

    fun testNullWithoutSiteOrTargets() {
        classifiers(RecordingClassifier { _, _ -> null })
        val navigation = RecordingNavigation { _, _ -> listOf(psi(FALCON_DEFAULTS)) }
        navigation(navigation)
        assertNull(targets(FALCON_GROUP_VARS, 10))
        assertTrue(navigation.sites.isEmpty())

        classifiers(RecordingClassifier { _, _ -> varKey("haproxy_bind_ip") })
        navigation(RecordingNavigation { _, _ -> emptyList() })
        assertNull(targets(FALCON_GROUP_VARS, 10))
    }

    fun testNullOutsideAnsibleRoots() {
        val classifier = RecordingClassifier { _, _ -> varKey("haproxy_bind_ip") }
        classifiers(classifier)
        navigation(RecordingNavigation { _, _ -> listOf(psi(FALCON_DEFAULTS)) })
        createFile("outside/group_vars/all.yml", "haproxy_bind_ip: 1.2.3.4\n")

        assertNull(targets("outside/group_vars/all.yml", 3))
        assertTrue(classifier.calls.isEmpty())
    }

    fun testRunsBeforeTheYamlAndJsonSchemaHandlers() {
        // Other `order="first"` handlers (e.g. Python's break/continue provider) may share the head of the list.
        val handlers = GotoDeclarationHandler.EP_NAME.extensionList
        val ours = handlers.indexOfFirst { it is AnsibleGotoDeclarationHandler }
        assertTrue(ours >= 0)
        for (name in listOf("org.jetbrains.yaml.schema.YamlJsonSchemaGotoDeclarationHandler", "com.jetbrains.jsonSchema.impl.JsonSchemaGotoDeclarationHandler")) {
            val index = handlers.indexOfFirst { it.javaClass.name == name }
            assertTrue("$name not registered", index >= 0)
            assertTrue("$name runs before us", ours < index)
        }
    }

    fun testCtrlBOnAVarsFileKeyNavigatesToTheDeclaration() {
        classifiers(RecordingClassifier { _, _ -> varKey("haproxy_bind_ip") })
        navigation(RecordingNavigation { site, _ -> declarations((site as AnsibleSite.VarKey).keyPath.single()) })
        myFixture.configureFromTempProjectFile(FALCON_GROUP_VARS)
        myFixture.editor.caretModel.moveToOffset(offsetOf(FALCON_GROUP_VARS, "haproxy_bind_ip:", 4))

        myFixture.performEditorAction(IdeActions.ACTION_GOTO_DECLARATION)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        val spec = key(FALCON_SPECS, "haproxy_bind_ip")
        val editor = FileEditorManager.getInstance(project).selectedTextEditor!!
        assertEquals(vf(FALCON_SPECS), editor.virtualFile)
        assertEquals(spec.textOffset, editor.caretModel.offset)
    }

    fun testCtrlBOnAKeyIsGotoDeclarationNotShowUsages() {
        myFixture.configureFromTempProjectFile(FALCON_GROUP_VARS)
        val offset = offsetOf(FALCON_GROUP_VARS, "haproxy_bind_ip:", 2)
        val editor = myFixture.editor
        val file = myFixture.file
        fun outcome() = inBackgroundReadAction { GotoDeclarationOrUsageHandler2.testGTDUOutcome(editor, file, offset) }

        assertEquals("without a site the key is its own declaration", GotoDeclarationOrUsageHandler2.GTDUOutcome.SU, outcome())

        classifiers(RecordingClassifier { _, _ -> varKey("haproxy_bind_ip") })
        navigation(RecordingNavigation { site, _ -> declarations((site as AnsibleSite.VarKey).keyPath.single()) })
        assertEquals(GotoDeclarationOrUsageHandler2.GTDUOutcome.GTD, outcome())
    }

    fun testCtrlBInsideAScalarCollectsOurTargets() {
        val scalarOffset = offsetOf(FALCON_GROUP_VARS, "system_ip_floating }}", 3)
        classifiers(RecordingClassifier { _, offset ->
            if (offset == scalarOffset) {
                AnsibleSite.VarRef("system_ip_floating", emptyList(), JinjaContainer.YAML_TEMPLATE, rangeOf(FALCON_GROUP_VARS, "system_ip_floating"))
            } else {
                null
            }
        })
        navigation(RecordingNavigation { _, _ -> declarations("haproxy_servers") })
        myFixture.configureFromTempProjectFile(FALCON_GROUP_VARS)

        val found = runReadActionBlocking { GotoDeclarationAction.findAllTargetElements(project, myFixture.editor, scalarOffset) }

        assertEquals(declarations("haproxy_servers"), found.toList())
    }
}
