package de.terletzkiy.ansibility.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.SettingsCategory
import com.intellij.openapi.components.State
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.terletzkiy.ansibility.semantics.CoreVersion

class AnsibilityAppSettingsTest : BasePlatformTestCase() {
    override fun tearDown() {
        try {
            SettingsTestSupport.resetAll(project)
        } finally {
            super.tearDown()
        }
    }

    private fun events(): MutableList<Pair<AppSettings, AppSettings>> {
        val events = mutableListOf<Pair<AppSettings, AppSettings>>()
        ApplicationManager.getApplication().messageBus.connect(testRootDisposable)
            .subscribe(AnsibilityAppSettingsListener.TOPIC, AnsibilityAppSettingsListener { old, new -> events += old to new })
        return events
    }

    private val customized = AppSettings(
        executables = ExecutableSettings(
            ansibleDoc = "/opt/homebrew/bin/ansible-doc",
            ansibleInventory = null,
            ansible = "/usr/local/bin/ansible",
            localTargetGuess = false,
        ),
        docs = DocsSettings(DocsWebBase.CUSTOM, "https://mirror.example/ansible/11/", ModuleNavigationTarget.QUICK_DOC, backgroundRefresh = false),
        jinja = JinjaSettings(
            claimJ2InsideRoots = false,
            keepYamlForJ2 = true,
            treatJinjaTemplatesUnderTemplatesDir = false,
            deferToPyCharmJinja = true,
            outerLanguageRules = listOf(OuterLanguageRule("*.alloy", "HCL"), OuterLanguageRule("**/templates/nginx/**", "Nginx")),
            autoCloseDelimiters = false,
            autoInsertEndTags = false,
        ),
        coexistence = CoexistenceSettings(conflictNotifications = false, hideOtherAnsibleCompletions = true),
    )

    fun testDefaultsAreExactlyThePlannedOnes() {
        val defaults = AnsibilityAppSettings().settings
        assertEquals(AppSettings.DEFAULT, defaults)
        with(defaults.executables) {
            assertNull(ansibleDoc)
            assertNull(ansibleInventory)
            assertNull(ansible)
            assertTrue("D10: guess the target from the local install", localTargetGuess)
        }
        with(defaults.docs) {
            assertEquals(DocsWebBase.TARGET_VERSIONED, webBase)
            assertEquals("", customWebBaseUrl)
            assertEquals(ModuleNavigationTarget.WEB_DOCS, moduleNavigation)
            assertTrue(backgroundRefresh)
        }
        with(defaults.jinja) {
            assertTrue(claimJ2InsideRoots)
            assertFalse(keepYamlForJ2)
            assertTrue(treatJinjaTemplatesUnderTemplatesDir)
            assertFalse(deferToPyCharmJinja)
            assertEquals(JinjaSettings.DEFAULT_OUTER_LANGUAGE_RULES, outerLanguageRules)
            assertTrue(autoCloseDelimiters)
            assertTrue(autoInsertEndTags)
            assertTrue(claimsJ2)
        }
        with(defaults.coexistence) {
            assertTrue(conflictNotifications)
            assertFalse(hideOtherAnsibleCompletions)
        }
    }

    fun testStoredInAnsibilityXml() {
        val state = AnsibilityAppSettings::class.java.getAnnotation(State::class.java)
        assertEquals("AnsibilitySettings", state.name)
        assertEquals("ansibility.xml", state.storages.single().value)
        assertEquals(SettingsCategory.PLUGINS, state.category)
    }

    fun testDefaultStateWritesNothing() {
        val (xml, _) = SettingsTestSupport.xmlRoundTrip(AnsibilityAppSettings().state, AnsibilityAppSettings.StateBean())
        assertEquals("<component />", xml)
    }

    fun testGetStateLoadStateRoundTrip() {
        val source = AnsibilityAppSettings()
        source.noStateLoaded()
        source.update { customized }
        val target = AnsibilityAppSettings()
        target.loadState(source.state)
        assertEquals(customized, target.settings)
    }

    fun testXmlRoundTripKeepsEveryValue() {
        val source = AnsibilityAppSettings()
        source.update { customized }
        val (xml, bean) = SettingsTestSupport.xmlRoundTrip(source.state, AnsibilityAppSettings.StateBean())
        assertEquals(customized, bean.toSettings())
        assertTrue(xml, xml.contains("pattern=\"*.alloy\""))
        assertTrue(xml, xml.contains("language=\"HCL\""))
    }

    fun testDefaultRulesAreNotWrittenSoPluginUpdatesCanImproveThem() {
        val bean = AnsibilityAppSettings.StateBean.of(AppSettings.DEFAULT.copy(docs = DocsSettings(backgroundRefresh = false)))
        assertFalse(bean.customOuterLanguageRules)
        assertTrue(bean.outerLanguageRules.isEmpty())
        assertEquals(JinjaSettings.DEFAULT_OUTER_LANGUAGE_RULES, bean.toSettings().jinja.outerLanguageRules)
    }

    fun testAnEmptyRuleListSurvivesTheRoundTrip() {
        val empty = AppSettings.DEFAULT.copy(jinja = JinjaSettings(outerLanguageRules = emptyList()))
        val (_, bean) = SettingsTestSupport.xmlRoundTrip(AnsibilityAppSettings.StateBean.of(empty), AnsibilityAppSettings.StateBean())
        assertEquals(emptyList<OuterLanguageRule>(), bean.toSettings().jinja.outerLanguageRules)
    }

    fun testBlankPathsAreStoredAsAuto() {
        val bean = AnsibilityAppSettings.StateBean.of(AppSettings(executables = ExecutableSettings(ansibleDoc = "  ")))
        assertNull(bean.ansibleDocPath)
        assertNull(bean.toSettings().executables.ansibleDoc)
    }

    fun testUpdatePublishesChangesOnly() {
        val events = events()
        val settings = AnsibilityAppSettings.getInstance()
        val before = settings.modificationTracker.modificationCount
        settings.update { it.copy(coexistence = it.coexistence.copy(hideOtherAnsibleCompletions = true)) }
        assertEquals(1, events.size)
        assertFalse(events.single().first.coexistence.hideOtherAnsibleCompletions)
        assertTrue(events.single().second.coexistence.hideOtherAnsibleCompletions)
        assertTrue(settings.modificationTracker.modificationCount > before)
        settings.update { it }
        assertEquals("no event without a change", 1, events.size)
    }

    fun testReloadPublishesButTheInitialLoadDoesNot() {
        val events = events()
        val settings = AnsibilityAppSettings()
        settings.loadState(AnsibilityAppSettings.StateBean.of(customized))
        assertTrue("initial load", events.isEmpty())
        assertEquals(customized, settings.settings)
        settings.loadState(AnsibilityAppSettings.StateBean())
        assertEquals(listOf(customized to AppSettings.DEFAULT), events)
    }

    fun testClaimsJ2NeedsClaimingAndNoOptOut() {
        assertFalse(JinjaSettings(keepYamlForJ2 = true).claimsJ2)
        assertFalse(JinjaSettings(claimJ2InsideRoots = false).claimsJ2)
        assertFalse(JinjaSettings(deferToPyCharmJinja = true).claimsJ2)
    }

    fun testDefaultOuterLanguageRulesOnRealTemplateNames() {
        val jinja = JinjaSettings()
        fun outer(path: String) = jinja.outerLanguageId(path.substringAfterLast('/'), path)
        assertEquals("the logrotate file next to the nginx sites is plain text",
            OuterLanguageRule.PLAIN_TEXT, outer("roles/tempo/templates/nginx/logrotate.conf.j2"))
        assertEquals("Nginx", outer("roles/tempo/templates/nginx/main.site.proxy_protocol.conf.j2"))
        assertEquals("Nginx", outer("roles/nginx/templates/nginx.conf.j2"))
        assertEquals("Nginx", outer("roles/app-thrush-mono/templates/deployment/nginx.conf"))
        assertEquals("Nginx", outer("roles/keycloak/templates/gateway.api.conf.j2"))
        assertEquals("a systemd unit in the nginx role is not nginx",
            "Unit File (systemd)", outer("roles/nginx/templates/services/update-cidrs.service.j2"))
        assertEquals(OuterLanguageRule.PLAIN_TEXT, outer("roles/docker/templates/services/docker.service.override.conf.j2"))
        assertEquals(OuterLanguageRule.PLAIN_TEXT, outer("roles/haproxy/templates/haproxy.cfg.j2"))
        assertEquals(OuterLanguageRule.PLAIN_TEXT, outer("roles/rsyslog/templates/rsyslog-remote.conf.j2"))
        assertEquals("Dockerfile", outer("roles/haproxy/molecule/default/Dockerfile.j2"))
        assertEquals("yaml", outer("roles/loki/templates/loki/docker-compose.yaml.j2"))
        assertEquals("Shell Script", outer("roles/percona/templates/binlog-backup.sh"))
        assertEquals("Ini", outer("roles/percona/templates/my.cnf.j2"))
        assertEquals("DotEnv", outer("roles/app/templates/app.env.j2"))
        assertNull("no rule: plain text, never HTML", outer("roles/alloy/templates/config.alloy.j2"))
        assertNull(outer("roles/deployment-target/templates/sudoers"))
    }

    fun testRuleMatchingUsesTheInnerName() {
        val rule = OuterLanguageRule("Dockerfile", "Dockerfile")
        assertTrue(rule.matches("Dockerfile.j2", "molecule/default/Dockerfile.j2"))
        assertTrue(rule.matches("Dockerfile", "templates/Dockerfile"))
        assertFalse(rule.matches("Dockerfile.j2.bak", "x/Dockerfile.j2.bak"))
        assertFalse("a blank pattern never matches", OuterLanguageRule(" ", "yaml").matches("a.yml", "a.yml"))
    }

    fun testDocsWebBaseUrls() {
        val target = DocsSettings()
        assertEquals("https://docs.ansible.com/ansible/11/", target.webBaseUrl(CoreVersion(2, 18, 8)))
        assertEquals("https://docs.ansible.com/ansible/14/", target.webBaseUrl(CoreVersion(2, 21, 4)))
        assertEquals(DocsSettings.LATEST_URL, target.webBaseUrl(null))
        assertEquals("before the package split", DocsSettings.LATEST_URL, target.webBaseUrl(CoreVersion(2, 9)))
        assertEquals(DocsSettings.LATEST_URL, DocsSettings(DocsWebBase.LATEST).webBaseUrl(CoreVersion(2, 18, 8)))
        assertEquals("https://mirror.example/ansible/11/", DocsSettings(DocsWebBase.CUSTOM, " https://mirror.example/ansible/11 ").webBaseUrl(null))
        assertEquals(DocsSettings.LATEST_URL, DocsSettings(DocsWebBase.CUSTOM, "").webBaseUrl(CoreVersion(2, 18)))
    }
}
