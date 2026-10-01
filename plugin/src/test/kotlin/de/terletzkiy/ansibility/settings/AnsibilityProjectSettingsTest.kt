package de.terletzkiy.ansibility.settings

import com.intellij.openapi.components.State
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.context.AnsibleWorkspaceImpl
import de.terletzkiy.ansibility.context.ContextTestTree
import de.terletzkiy.ansibility.context.ContextTestTree.DANGER_ZONE
import de.terletzkiy.ansibility.context.ContextTestTree.FALCON
import de.terletzkiy.ansibility.context.ContextTestTree.GOLDEN
import de.terletzkiy.ansibility.context.ContextTestTree.PELICAN
import de.terletzkiy.ansibility.semantics.CoreVersion
import de.terletzkiy.ansibility.semantics.diagnostics.Preset

class AnsibilityProjectSettingsTest : BasePlatformTestCase() {
    private lateinit var settings: AnsibilityProjectSettings

    override fun setUp() {
        super.setUp()
        ContextTestTree.create(myFixture)
        AnsibleWorkspaceImpl.getInstance(project)!!.structureChanged()
        settings = AnsibilityProjectSettings.getInstance(project)
    }

    override fun tearDown() {
        try {
            SettingsTestSupport.resetAll(project)
        } finally {
            super.tearDown()
        }
    }

    private fun vf(path: String): VirtualFile = myFixture.findFileInTempDir(path) ?: error("missing $path")

    private fun root(path: String): AnsibleRoot = AnsibleWorkspaceImpl.getInstance(project)!!.roots().single { it.dir == vf(path) }

    private fun events(): MutableList<Pair<ProjectSettings, ProjectSettings>> {
        val events = mutableListOf<Pair<ProjectSettings, ProjectSettings>>()
        project.messageBus.connect(testRootDisposable).subscribe(
            AnsibilitySettingsListener.TOPIC,
            object : AnsibilitySettingsListener {
                override fun projectSettingsChanged(old: ProjectSettings, new: ProjectSettings) {
                    events += old to new
                }
            },
        )
        return events
    }

    private val customRoot = RootSettings(
        targetCore = "2.19",
        collectionsSource = CollectionsSource.LOCAL,
        cfgOverrides = AnsibleCfgOverrides(
            hashBehaviour = HashBehaviour.MERGE,
            precedence = listOf("all_plugins_play", "groups_plugins_play"),
            playbookVarsRoot = PlaybookVarsRoot.ALL,
            jinja2Native = true,
            privateRoleVars = false,
        ),
        preset = Preset.RUNTIME_FAITHFUL,
        moduleOptionCoercions = true,
        requireReachablePlayForRed = true,
        redForClaudeCertainFailures = false,
        unknownModuleOptionWhenDocsDiffer = DocsMismatchSeverity.OFF,
        chainDepth = 4,
    )

    private val customized = ProjectSettings(
        roots = mapOf(FALCON to customRoot, GOLDEN to RootSettings(preset = Preset.STRICT)),
        paths = PathSettings(detachedRule = false, extraIgnoredPaths = listOf("vendor/**"), moleculeSupport = false, schemaStoreExclusion = false),
    )

    // ------------------------------------------------------------------ defaults

    fun testRootDefaultsAreExactlyThePlannedOnes() {
        val defaults = RootSettings()
        assertNull("target core is Auto", defaults.targetCore)
        assertNull(defaults.targetCoreVersion)
        assertEquals(CollectionsSource.PINS, defaults.collectionsSource)
        assertEquals(AnsibleCfgOverrides.NONE, defaults.cfgOverrides)
        assertEquals("Documented types is the default preset (D4)", Preset.DOCUMENTED_TYPES, defaults.preset)
        assertFalse("module-option coercions are silent (D5)", defaults.moduleOptionCoercions)
        assertFalse("red never needs a reachable play (D6)", defaults.requireReachablePlayForRed)
        assertTrue("Claude's certain failures are red (D7)", defaults.redForClaudeCertainFailures)
        assertEquals(DocsMismatchSeverity.WARNING, defaults.unknownModuleOptionWhenDocsDiffer)
        assertEquals(8, defaults.chainDepth)
    }

    fun testPathDefaultsAreExactlyThePlannedOnes() {
        val paths = ProjectSettings.DEFAULT.paths
        assertTrue(paths.detachedRule)
        assertEquals(listOf("**/.ansible/**", "patches/**"), paths.extraIgnoredPaths)
        assertTrue(paths.moleculeSupport)
        assertTrue(paths.schemaStoreExclusion)
    }

    fun testFreshProjectHasDefaultsEverywhere() {
        assertEquals(ProjectSettings.DEFAULT, settings.settings)
        assertFalse(settings.isSharedWithTeam)
        assertEquals(RootSettings.DEFAULT, settings.rootSettings(root(FALCON)))
    }

    fun testStoredInTheWorkspaceFileAndSharedInIdeaAnsibilityXml() {
        val local = AnsibilityProjectSettings::class.java.getAnnotation(State::class.java)
        assertEquals(StoragePathMacros.WORKSPACE_FILE, local.storages.single().value)
        val shared = AnsibilitySharedProjectSettings::class.java.getAnnotation(State::class.java)
        assertEquals("ansibility.xml", shared.storages.single().value)
    }

    // ------------------------------------------------------------------ persistence

    fun testDefaultStateWritesNothing() {
        val (xml, _) = SettingsTestSupport.xmlRoundTrip(settings.state, ProjectSettingsBean())
        assertEquals("<component />", xml)
    }

    fun testGetStateLoadStateRoundTrip() {
        settings.update { customized }
        val copy = AnsibilityProjectSettings(project)
        copy.loadState(settings.state)
        assertEquals(customized, copy.settings)
    }

    fun testXmlRoundTripKeepsEveryValue() {
        settings.update { customized }
        val (xml, bean) = SettingsTestSupport.xmlRoundTrip(settings.state, ProjectSettingsBean())
        assertEquals(customized, bean.toSettings())
        assertTrue(xml, xml.contains("path=\"$FALCON\""))
        assertTrue(xml, xml.contains("all_plugins_play, groups_plugins_play"))
    }

    fun testAnEmptyIgnoredPathListSurvivesTheRoundTrip() {
        val none = ProjectSettings(paths = PathSettings(extraIgnoredPaths = emptyList()))
        val (_, bean) = SettingsTestSupport.xmlRoundTrip(ProjectSettingsBean().apply { fill(none) }, ProjectSettingsBean())
        assertEquals(emptyList<String>(), bean.toSettings().paths.extraIgnoredPaths)
    }

    fun testDefaultRootEntriesAreDropped() {
        val updated = settings.updateRoot(FALCON) { it.copy(preset = Preset.STRICT) }
        assertEquals(setOf(FALCON), updated.roots.keys)
        val reverted = settings.updateRoot(FALCON) { it.copy(preset = Preset.DOCUMENTED_TYPES) }
        assertTrue(reverted.roots.isEmpty())
        assertTrue(ProjectSettingsBean().apply { fill(ProjectSettings(roots = mapOf(GOLDEN to RootSettings()))) }.roots.isEmpty())
    }

    fun testPrecedenceParsing() {
        assertEquals(listOf("all_inventory", "groups_inventory"), AnsibleCfgOverrides.parsePrecedence(" all_inventory,groups_inventory \n"))
        assertNull(AnsibleCfgOverrides.parsePrecedence(" , "))
        assertNull(AnsibleCfgOverrides.parsePrecedence(null))
    }

    // ------------------------------------------------------------------ root keys and lookups

    fun testRootsAreKeyedByTheirPathRelativeToTheProject() {
        assertEquals(FALCON, RootKeys.keyOf(project, root(FALCON).dir))
        assertEquals(GOLDEN, RootKeys.keyOf(project, root(GOLDEN).dir))
        assertEquals(RootKeys.PROJECT_DIR, RootKeys.keyOf(project, vf(FALCON).parent.parent.parent))
    }

    fun testRootSettingsAreScopedToTheirRoot() {
        settings.update { customized }
        assertEquals(customRoot, settings.rootSettings(root(FALCON)))
        assertEquals(Preset.STRICT, settings.rootSettings(root(GOLDEN)).preset)
        assertEquals(RootSettings.DEFAULT, settings.rootSettings(root(PELICAN)))
    }

    fun testNestedRootsOnAutoFollowTheParentTarget() {
        settings.updateRoot(PELICAN) { it.copy(targetCore = "2.19.1") }
        assertEquals(CoreVersion(2, 19, 1), settings.targetCoreOverride(root(PELICAN)))
        assertEquals(CoreVersion(2, 19, 1), settings.targetCoreOverride(root(DANGER_ZONE)))
        settings.updateRoot(DANGER_ZONE) { it.copy(targetCore = "2.20") }
        assertEquals(CoreVersion(2, 20, 0), settings.targetCoreOverride(root(DANGER_ZONE)))
        assertNull("other roots stay on Auto", settings.targetCoreOverride(root(FALCON)))
        assertEquals("only the target is inherited", Preset.DOCUMENTED_TYPES, settings.rootSettings(root(DANGER_ZONE)).preset)
    }

    fun testIgnoredPathsAreRelativeToTheProject() {
        assertTrue(settings.isIgnored(vf("patches/roles/nginx/defaults/main.yml")))
        assertTrue(settings.isIgnored(vf("$FALCON/.ansible/roles/cached/tasks/main.yml")))
        assertFalse(settings.isIgnored(vf("$FALCON/roles/postfix/tasks/main.yml")))
        settings.update { it.copy(paths = it.paths.copy(extraIgnoredPaths = listOf("repos/falcon/**"))) }
        assertTrue(settings.isIgnored(vf("$FALCON/roles/postfix/tasks/main.yml")))
        assertFalse(settings.isIgnored(vf("patches/roles/nginx/defaults/main.yml")))
    }

    fun testIgnoredPathMatcherIsASnapshot() {
        val matcher = settings.ignoredPathMatcher()
        val files = listOf("patches/roles/nginx/defaults/main.yml", "$FALCON/.ansible/roles/cached/tasks/main.yml", "$FALCON/roles/postfix/tasks/main.yml")
        assertEquals(files.map { settings.isIgnored(vf(it)) }, files.map { matcher(vf(it)) })
        assertTrue("directories match too", matcher(vf("patches")))
        settings.update { it.copy(paths = it.paths.copy(extraIgnoredPaths = emptyList())) }
        assertTrue("the matcher keeps the globs it was made with", matcher(vf("patches")))
        assertFalse(settings.ignoredPathMatcher()(vf("patches")))
    }

    // ------------------------------------------------------------------ change notification

    fun testUpdatePublishesOldAndNewAndBumpsTheTracker() {
        val events = events()
        val before = settings.modificationTracker.modificationCount
        settings.update { customized }
        assertEquals(listOf(ProjectSettings.DEFAULT to customized), events)
        assertTrue(settings.modificationTracker.modificationCount > before)
        settings.update { it }
        assertEquals("no event without a change", 1, events.size)
    }

    fun testReloadOfTheWorkspaceFilePublishes() {
        val events = events()
        settings.loadState(ProjectSettingsBean().apply { fill(customized) })
        assertEquals(listOf(ProjectSettings.DEFAULT to customized), events)
    }

    // ------------------------------------------------------------------ sharing

    fun testSharingMovesTheSettingsIntoTheSharedComponent() {
        settings.update { customized }
        val shared = project.service<AnsibilitySharedProjectSettings>()
        assertFalse(shared.state.shared)
        val events = events()

        settings.setSharedWithTeam(true)
        assertTrue(settings.isSharedWithTeam)
        assertEquals(customized, settings.settings)
        assertTrue(events.isEmpty())
        val sharedState = shared.state
        assertTrue(sharedState.shared)
        assertEquals(customized, sharedState.toSettings())

        settings.updateRoot(PELICAN) { it.copy(preset = Preset.STRICT) }
        assertEquals("edits go to the shared copy", Preset.STRICT, shared.state.toSettings().root(PELICAN).preset)
        assertEquals("the workspace copy is untouched while shared", customized, settings.state.toSettings())

        settings.setSharedWithTeam(false)
        assertFalse(settings.isSharedWithTeam)
        assertEquals(Preset.STRICT, settings.settings.root(PELICAN).preset)
        assertEquals("the shared file is emptied", "<component />", SettingsTestSupport.xmlRoundTrip(shared.state, SharedProjectSettingsBean()).first)
        assertEquals(Preset.STRICT, settings.state.toSettings().root(PELICAN).preset)
    }

    fun testATeammatesSharedFileTakesEffect() {
        val events = events()
        val sharedComponent = project.service<AnsibilitySharedProjectSettings>()
        val fromVcs = SharedProjectSettingsBean().apply {
            shared = true
            fill(customized)
        }
        sharedComponent.loadState(fromVcs)
        assertTrue(settings.isSharedWithTeam)
        assertEquals(customized, settings.settings)
        assertEquals(listOf(ProjectSettings.DEFAULT to customized), events)

        sharedComponent.loadState(SharedProjectSettingsBean())
        assertFalse(settings.isSharedWithTeam)
        assertEquals(ProjectSettings.DEFAULT, settings.settings)
        assertEquals(2, events.size)
    }

    fun testSharedFileWithoutTheFlagIsIgnored() {
        project.service<AnsibilitySharedProjectSettings>().loadState(SharedProjectSettingsBean().apply { fill(customized) })
        assertFalse(settings.isSharedWithTeam)
        assertEquals(ProjectSettings.DEFAULT, settings.settings)
    }
}
