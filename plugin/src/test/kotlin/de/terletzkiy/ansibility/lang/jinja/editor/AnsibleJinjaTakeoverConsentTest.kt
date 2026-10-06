package de.terletzkiy.ansibility.lang.jinja.editor

import com.intellij.ide.util.PropertiesComponent
import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.fileTypes.PlainTextLanguage
import com.intellij.testFramework.TestActionEvent
import de.terletzkiy.ansibility.context.AnsibleWorkspaceImpl
import de.terletzkiy.ansibility.fixtures.RequiresInfraFixture
import de.terletzkiy.ansibility.lang.jinja.AnsibleJinjaFileType
import de.terletzkiy.ansibility.lang.jinja.editor.consent.AnsibleJinjaTakeoverConsent
import de.terletzkiy.ansibility.lang.jinja.editor.consent.AnsibleJinjaTakeoverConsentActivity
import de.terletzkiy.ansibility.lang.jinja.editor.consent.J2ExtensionMapping
import de.terletzkiy.ansibility.lang.jinja.filetype.J2Mappings
import de.terletzkiy.ansibility.lang.jinja.template.AnsibleJinjaFileViewProvider
import de.terletzkiy.ansibility.lang.jinja.template.AnsibleJinjaTemplateTestCase
import de.terletzkiy.ansibility.settings.AnsibilityAppSettings
import de.terletzkiy.ansibility.settings.JinjaSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.jetbrains.yaml.YAMLFileType

/**
 * The D8/F2.9 consent flow and M5 acceptance 1: with the user's `*.j2 → YAML` mapping in place, `haproxy.cfg.j2`
 * opens as Ansible Jinja2 (Plain text); the one-time notification's "Keep YAML for .j2" reverts it, and "Remove my
 * `*.j2 → YAML` mapping" removes the mapping, only on click.
 */
@RequiresInfraFixture
class AnsibleJinjaTakeoverConsentTest : AnsibleJinjaTemplateTestCase() {
    private val properties: PropertiesComponent get() = PropertiesComponent.getInstance()
    private val haproxy = "golden/roles/haproxy/templates/haproxy.cfg.j2"

    override fun setUp() {
        super.setUp()
        properties.unsetValue(AnsibleJinjaTakeoverConsent.SHOWN_KEY)
    }

    override fun tearDown() {
        try {
            properties.unsetValue(AnsibleJinjaTakeoverConsent.SHOWN_KEY)
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    fun testAcceptance1KeepYamlRevertsTheTakeover() {
        copyInfra("golden/roles/haproxy")
        J2Mappings.withJ2As(AnsibleJinjaFileType) {
            assertSame(YAMLFileType.YML, FileTypeManager.getInstance().getFileTypeByExtension("j2"))
            assertSame("the overrider beats the mapping", AnsibleJinjaFileType, vf(haproxy).fileType)
            assertEquals(PlainTextLanguage.INSTANCE, (viewProvider(haproxy) as AnsibleJinjaFileViewProvider).templateDataLanguage)
            val notification = AnsibleJinjaTakeoverConsent.createNotification(project, emptyList())
            assertEquals(listOf("Keep YAML for .j2", "Remove my *.j2 → YAML mapping"), notification.actions.map { it.templateText })
            val settings = AnsibilityAppSettings.getInstance()
            val before = settings.settings
            try {
                perform(notification, "Keep YAML for .j2")
                dispatchEvents()
                assertTrue(settings.settings.jinja.keepYamlForJ2)
                assertSame(YAMLFileType.YML, vf(haproxy).fileType)
                assertFalse(viewProvider(haproxy) is AnsibleJinjaFileViewProvider)
                assertSame("the mapping is untouched", YAMLFileType.YML, FileTypeManager.getInstance().getFileTypeByExtension("j2"))
            } finally {
                settings.update { before }
                dispatchEvents()
            }
            assertSame(AnsibleJinjaFileType, vf(haproxy).fileType)
        }
    }

    fun testRemoveMappingOnlyOnClick() {
        copyInfra("golden/roles/haproxy")
        createFile("elsewhere/config.j2", "a: 1\n")
        val hadMapping = FileTypeManager.getInstance().getFileTypeByExtension("j2") == YAMLFileType.YML
        J2Mappings.withJ2As(AnsibleJinjaFileType) {
            val notification = AnsibleJinjaTakeoverConsent.createNotification(project, emptyList())
            assertSame("creating the notification changes nothing", YAMLFileType.YML, J2ExtensionMapping.mappedType())
            assertSame(YAMLFileType.YML, vf("elsewhere/config.j2").fileType)
            try {
                perform(notification, "Remove my *.j2 → YAML mapping")
                dispatchEvents()
                assertNull(J2ExtensionMapping.mappedType())
                assertNotSame(YAMLFileType.YML, vf("elsewhere/config.j2").fileType)
                assertSame("inside roots nothing changes", AnsibleJinjaFileType, vf(haproxy).fileType)
            } finally {
                // J2Mappings removes the test mapping afterwards; a mapping that existed before the test comes back
                if (hadMapping && J2ExtensionMapping.mappedType() == null) restoreYamlMapping()
            }
        }
    }

    fun testNoRemoveActionWithoutMapping() {
        val notification = AnsibleJinjaTakeoverConsent.createNotification(project, emptyList(), mappedType = null)
        assertEquals(listOf("Keep YAML for .j2"), notification.actions.map { it.templateText })
        val plain = AnsibleJinjaTakeoverConsent.createNotification(project, emptyList(), mappedType = PlainTextFileType.INSTANCE)
        assertEquals(
            listOf("Keep ${PlainTextFileType.INSTANCE.displayName} for .j2", "Remove my *.j2 → ${PlainTextFileType.INSTANCE.displayName} mapping"),
            plain.actions.map { it.templateText },
        )
    }

    fun testShownOnceAndOnlyWhenClaimed() {
        assertNull(AnsibleJinjaTakeoverConsent.notifyIfNeeded(project, hasAnsibleRoots = false, examples = emptyList()))
        assertNull(AnsibleJinjaTakeoverConsent.notifyIfNeeded(project, true, emptyList(), JinjaSettings(keepYamlForJ2 = true)))
        assertNull(AnsibleJinjaTakeoverConsent.notifyIfNeeded(project, true, emptyList(), JinjaSettings(claimJ2InsideRoots = false)))
        assertFalse(properties.getBoolean(AnsibleJinjaTakeoverConsent.SHOWN_KEY))
        val notification = AnsibleJinjaTakeoverConsent.notifyIfNeeded(project, true, emptyList())
        assertNotNull(notification)
        notification!!.expire()
        assertEquals(NotificationType.INFORMATION, notification.type)
        assertNull("only once", AnsibleJinjaTakeoverConsent.notifyIfNeeded(project, true, emptyList()))
    }

    /** The notification names templates of the project with their outer language, real languages first. */
    fun testExamplesFromTheProject() {
        copyInfra("golden/roles/haproxy", "golden/roles/loki")
        dispatchEvents()
        val examples = runReadActionBlocking { AnsibleJinjaTakeoverConsent.examples(project) }
        assertTrue(examples.toString(), examples.size in 2..3)
        assertEquals(examples.toString(), examples.size, examples.map { it.outerLanguage }.toSet().size)
        assertEquals(examples.toString(), listOf("YAML", PlainTextLanguage.INSTANCE.displayName), examples.take(2).map { it.outerLanguage })
        val content = AnsibleJinjaTakeoverConsent.createNotification(project, examples, mappedType = YAMLFileType.YML).content
        assertTrue(content, "Ansible Jinja2 (YAML)" in content)
        assertTrue(content, "<code>${examples.first().fileName}</code>" in content)
        assertTrue(content, "still applies outside Ansible roots" in content)
    }

    /**
     * The startup flow: nothing without Ansible roots or without a `.j2` file taken over inside them; then one
     * notification naming the project's templates.
     */
    fun testAnnounceOnStartup() {
        refreshRoots()
        assertNull("no Ansible roots", announce())
        assertFalse("the flag stays free for an Ansible project", properties.getBoolean(AnsibleJinjaTakeoverConsent.SHOWN_KEY))
        createFile("site/ansible.cfg", "[defaults]\n")
        createFile("site/roles/web/tasks/main.yml", "- ansible.builtin.debug:\n    msg: x\n")
        createFile("site/roles/web/templates/nginx.conf", "listen {{ web_port }};\n")
        createFile("elsewhere/config.j2", "a: {{ b }}\n")
        refreshRoots()
        dispatchEvents()
        assertNull("no .j2 file is taken over", announce())
        assertFalse(properties.getBoolean(AnsibleJinjaTakeoverConsent.SHOWN_KEY))
        createFile("site/roles/web/templates/app.yml.j2", "port: {{ web_port }}\n")
        createFile("site/roles/web/templates/motd.j2", "Welcome to {{ inventory_hostname }}\n")
        refreshRoots()
        dispatchEvents()
        val notification = announce()
        assertNotNull("shown for a project with Ansible roots", notification)
        notification!!.expire()
        assertTrue(properties.getBoolean(AnsibleJinjaTakeoverConsent.SHOWN_KEY))
        assertTrue(notification.content, "<code>app.yml.j2</code>" in notification.content)
        assertTrue(notification.content, "<code>motd.j2</code>" in notification.content)
        assertTrue(notification.content, "Ansible Jinja2 (${PlainTextLanguage.INSTANCE.displayName})" in notification.content)
        assertFalse("only .j2 files inside roots are examples", "config.j2" in notification.content || "nginx.conf" in notification.content)
        assertNull("only once", announce())
    }

    fun testAnnounceRespectsTheOptOut() {
        createFile("site/ansible.cfg", "[defaults]\n")
        createFile("site/roles/web/templates/motd.j2", "Welcome to {{ inventory_hostname }}\n")
        refreshRoots()
        withJinjaSettings({ copy(keepYamlForJ2 = true) }) {
            assertNull(announce())
        }
        assertFalse(properties.getBoolean(AnsibleJinjaTakeoverConsent.SHOWN_KEY))
    }

    /**
     * The flow runs as a startup activity of the jinja fragment. Read from the descriptor: listing the extension point
     * would instantiate every plugin's startup activities.
     */
    fun testActivityIsRegistered() {
        val fragment = javaClass.getResource("/META-INF/ansibility-jinja.xml")?.readText() ?: error("no ansibility-jinja.xml")
        val editorBlock = fragment.substringAfter("<!-- w6-editor -->").substringBefore("<!-- /w6-editor -->")
        val registration = Regex("""<postStartupActivity\s+implementation="([^"]+)"""").findAll(editorBlock).map { it.groupValues[1] }.toList()
        assertEquals(listOf(AnsibleJinjaTakeoverConsentActivity::class.java.name), registration)
    }

    private fun refreshRoots() {
        (AnsibleWorkspaceImpl.getInstance(project) ?: error("AnsibleWorkspace is not AnsibleWorkspaceImpl")).structureChanged()
    }

    /** Runs the startup flow off the EDT, as the platform does. */
    private fun announce(): Notification? = runBlocking(Dispatchers.Default) { AnsibleJinjaTakeoverConsent.announce(project, properties) }

    private fun restoreYamlMapping() {
        WriteAction.runAndWait<Throwable> { FileTypeManager.getInstance().associateExtension(YAMLFileType.YML, "j2") }
    }

    private fun perform(notification: Notification, text: String) {
        val action = notification.actions.single { it.templateText == text } as NotificationAction
        action.actionPerformed(TestActionEvent.createTestEvent(action), notification)
    }
}
