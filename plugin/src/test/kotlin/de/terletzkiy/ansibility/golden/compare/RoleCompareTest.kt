package de.terletzkiy.ansibility.golden.compare

import com.intellij.diff.chains.DiffRequestChain
import com.intellij.diff.chains.SimpleDiffRequestChain
import com.intellij.diff.contents.EmptyContent
import com.intellij.diff.contents.FileContent
import com.intellij.diff.requests.MessageDiffRequest
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.diff.util.DiffUserDataKeys
import com.intellij.notification.NotificationDisplayType
import com.intellij.notification.NotificationGroupManager
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.keymap.KeymapManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.ExtensionTestUtil
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.registerOrReplaceServiceInstance
import com.intellij.toolWindow.ToolWindowHeadlessManagerImpl
import de.terletzkiy.ansibility.api.ScopeChoice
import de.terletzkiy.ansibility.api.WorkspaceScopeService
import de.terletzkiy.ansibility.golden.GoldenDataKeys
import de.terletzkiy.ansibility.golden.GoldenTarget
import de.terletzkiy.ansibility.golden.GoldenTargets
import de.terletzkiy.ansibility.golden.GoldenTestSupport
import de.terletzkiy.ansibility.golden.RecordingDiffUi
import de.terletzkiy.ansibility.golden.history.LastChange
import de.terletzkiy.ansibility.golden.history.LastChangeLookup
import de.terletzkiy.ansibility.golden.history.LastChanges
import de.terletzkiy.ansibility.model.drift.DriftFixture
import de.terletzkiy.ansibility.model.drift.RoleDriftService
import de.terletzkiy.ansibility.model.role.ModelFixture
import de.terletzkiy.ansibility.model.role.RoleCatalog
import de.terletzkiy.ansibility.model.role.RoleCopy
import de.terletzkiy.ansibility.settings.GoldenRoot
import de.terletzkiy.ansibility.settings.SettingsTestSupport
import de.terletzkiy.ansibility.toolwindow.AnsibleToolWindowFactory
import de.terletzkiy.ansibility.vault.crypto.VaultCrypto
import de.terletzkiy.ansibility.workspace.WorkspaceScopeServiceImpl
import java.time.Instant

/**
 * Compare with Golden and Compare with… (plan amendment R24, D182) on the synthetic drift tree: chain size and order,
 * the start file, titles (with the last change, D183), missing sides, the sensitive placeholder (never decrypted),
 * visibility, texts and registration, and the ranking of Compare with….
 */
class RoleCompareTest : BasePlatformTestCase() {
    private lateinit var ui: RecordingDiffUi
    private val lookup = FixedLookup()

    override fun getTestDataPath(): String = ModelFixture.testDataPath

    override fun setUp() {
        super.setUp()
        DriftFixture.copy(myFixture)
        GoldenTestSupport.useFirstLibraryAsGolden(project, testRootDisposable)
        ui = RecordingDiffUi()
        GoldenTestSupport.install(ui, testRootDisposable)
        // No VCS in the light project: one fake lookup, which answers nothing unless a test gives it answers.
        ExtensionTestUtil.maskExtensions(LastChangeLookup.EP_NAME, listOf(lookup), testRootDisposable)
        LastChanges.getInstance(project).clearForTests()
    }

    override fun tearDown() {
        try {
            SettingsTestSupport.resetAll(project)
            (WorkspaceScopeService.getInstance(project) as WorkspaceScopeServiceImpl).resetForTests()
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    private fun dir(team: String, role: String = "web"): VirtualFile = DriftFixture.file(myFixture, DriftFixture.roleDir(team, role))

    private fun vf(team: String, relative: String): VirtualFile = DriftFixture.file(myFixture, "${DriftFixture.roleDir(team)}/$relative")

    private fun copy(team: String, role: String = "web"): RoleCopy = GoldenTestSupport.copy(project, dir(team, role))

    private val compare: RoleCompare get() = RoleCompare.getInstance(project)

    /** Runs Compare with Golden and waits for the chain [RoleDiffUi] receives. */
    private fun compareWithGolden(team: String, relPath: String? = null): DiffRequestChain {
        val shown = ui.chains.size
        compare.compareWithGolden(GoldenTarget(copy(team), relPath))
        GoldenTestSupport.waitFor("the chain is shown") { ui.chains.size > shown }
        return ui.chains.last()
    }

    private fun names(chain: DiffRequestChain): List<String> = chain.requests.map { it.name }

    private fun request(chain: DiffRequestChain, index: Int) = GoldenTestSupport.process(chain.requests[index])

    // ------------------------------------------------------------------ the chain

    fun testTheMoleculeOnlyCopyGivesOneRequestGoldenLeft() {
        val chain = compareWithGolden("mol")
        assertEquals(listOf("molecule/default/verify.yml"), names(chain))
        assertEquals(0, chain.index)
        val request = request(chain, 0) as SimpleDiffRequest
        assertEquals("web · molecule/default/verify.yml (golden ↔ mol)", request.title)
        assertEquals(listOf("golden", "mol"), request.contentTitles)
        val (left, right) = request.contents.map { it as FileContent }
        assertEquals("golden on the left", vf("golden", "molecule/default/verify.yml"), left.file)
        assertEquals("the copy on the right, the real file (editable, blame)", vf("mol", "molecule/default/verify.yml"), right.file)
        assertEquals(emptyList<String>(), ui.notices)
    }

    fun testTheBehaviourCopyGivesEveryDifferingFileInPathOrder() {
        val chain = compareWithGolden("missing")
        assertEquals(12, chain.requests.size)
        assertEquals(names(chain).sorted(), names(chain))
        assertEquals(listOf("defaults/main.yml", "handlers/main.yml", "meta/argument_specs.yml"), names(chain).take(3))
        val onlyInGolden = request(chain, names(chain).indexOf("tasks/install.yml")) as SimpleDiffRequest
        assertTrue(onlyInGolden.contents[0] is FileContent)
        assertTrue("a missing side is empty", onlyInGolden.contents[1] is EmptyContent)
        assertEquals(listOf("golden", "missing (no such file)"), onlyInGolden.contentTitles)
    }

    fun testIgnoringMoleculeFollowsTheDriftSetting() {
        val drift = de.terletzkiy.ansibility.model.drift.RoleDriftService.getInstance(project)
        val before = drift.options
        try {
            drift.options = de.terletzkiy.ansibility.model.drift.DriftOptions(ignoreMolecule = true)
            assertEquals(8, compareWithGolden("missing").requests.size)
            val shown = ui.chains.size
            compare.compareWithGolden(GoldenTarget(copy("mol"), null))
            GoldenTestSupport.waitFor("the notice is shown") { ui.notices.isNotEmpty() }
            assertEquals("nothing else differs: no chain", shown, ui.chains.size)
            // Review fix U4: never "the same" when molecule/ differs.
            assertEquals(listOf("web in mol differs from golden only below molecule/, which Role drift ignores"), ui.notices)
        } finally {
            drift.options = before
        }
    }

    fun testWithMoleculeIgnoredACompareStartedBelowMoleculeIncludesThatPath() {
        val drift = de.terletzkiy.ansibility.model.drift.RoleDriftService.getInstance(project)
        val before = drift.options
        try {
            drift.options = de.terletzkiy.ansibility.model.drift.DriftOptions(ignoreMolecule = true)
            val file = compareWithGolden("mol", "molecule/default/verify.yml")
            assertEquals(listOf("molecule/default/verify.yml"), names(file))
            assertEquals("molecule/default/verify.yml", file.requests[file.index].name)
            val folder = compareWithGolden("specmol", "molecule")
            assertEquals("the folder it was started on, and what drift compares", listOf("defaults/main.yml", "molecule/default/verify.yml"), names(folder))
            assertEquals("molecule/default/verify.yml", folder.requests[folder.index].name)
            assertEquals("elsewhere molecule/ stays out", listOf("defaults/main.yml"), names(compareWithGolden("specmol", "defaults/main.yml")))
            assertEquals(emptyList<String>(), ui.notices)
            val same = GoldenTestSupport.await { compare.chain(copy("golden"), copy("same"), "molecule/default/verify.yml") }
            assertEquals("web in same is the same as in golden", same.notice)
        } finally {
            drift.options = before
        }
    }

    fun testWithoutAGoldenRootCompareWithComputesNoDriftAndShowsNoTier() {
        DriftFixture.useGolden(project, testRootDisposable, GoldenRoot.None)
        val service = DriftFixture.freshService(project, testRootDisposable)
        project.registerOrReplaceServiceInstance(RoleDriftService::class.java, service, testRootDisposable)
        val choices = GoldenTestSupport.await { compare.choices(copy("mol")) }
        assertEquals(7, choices.size)
        assertEquals("no tier without a golden root, never \"no golden copy\"", List(7) { null }, choices.map { it.tier })
        assertEquals("no drift is computed (D178)", 0L, service.counters.copiesWalked)
    }

    fun testTheChainStartsAtTheSelectedFile() {
        val chain = compareWithGolden("missing", "tasks/main.yml")
        assertEquals("tasks/main.yml", chain.requests[chain.index].name)
        val folder = compareWithGolden("missing", "tasks")
        assertEquals("a folder starts at its first differing file", "tasks/configure.yml", folder.requests[folder.index].name)
        val onlyInGolden = compareWithGolden("missing", "templates/site.conf.j2")
        assertEquals("templates/site.conf.j2", onlyInGolden.requests[onlyInGolden.index].name)
        assertEquals(emptyList<String>(), ui.notices)

        val same = compareWithGolden("missing", "files/motd.txt")
        assertEquals(0, same.index)
        assertEquals(listOf("files/motd.txt is the same in golden and missing; showing the files that differ"), ui.notices)
    }

    fun testAnUnsavedEditIsCompared() {
        val document = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(vf("same", "tasks/main.yml"))!!
        val text = document.text
        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) { document.insertString(document.textLength, "# typed\n") }
        try {
            assertEquals(listOf("tasks/main.yml"), names(compareWithGolden("same")))
        } finally {
            com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) { document.setText(text) }
            com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().saveDocument(document)
        }
    }

    // ------------------------------------------------------------------ titles (D183)

    fun testTitlesCarryTheLastChangeOfEachSide() {
        val golden = vf("golden", "molecule/default/verify.yml")
        lookup.answers[golden] = LastChange("alice", Instant.parse("2026-09-12T10:00:00Z"), "Fix verify", "0123abcd")
        val request = request(compareWithGolden("mol"), 0) as SimpleDiffRequest
        val date = java.time.format.DateTimeFormatter.ISO_LOCAL_DATE.withZone(java.time.ZoneId.systemDefault()).format(Instant.parse("2026-09-12T10:00:00Z"))
        assertEquals(listOf("golden · $date · alice", "mol"), request.contentTitles)
    }

    // ------------------------------------------------------------------ sensitive files

    fun testSensitiveFilesArePlaceholdersAndNothingIsDecrypted() {
        val guard = GoldenTestSupport.guardVault(project, testRootDisposable)
        val decrypts = VaultCrypto.getInstance(project).decryptAttempts
        DriftFixture.write(myFixture, "${DriftFixture.roleDir("golden")}/files/ssl/web.key", "synthetic golden key\n")
        DriftFixture.write(myFixture, "${DriftFixture.roleDir("spec")}/files/ssl/web.key", "synthetic repo key!!\n")
        DriftFixture.write(myFixture, "${DriftFixture.roleDir("spec")}/files/secrets.yml", DriftFixture.syntheticVault("synthetic compare vault"))

        val chain = compareWithGolden("spec")
        assertEquals(listOf("files/secrets.yml", "files/ssl/web.key", "meta/argument_specs.yml"), names(chain))
        val key = request(chain, 1)
        assertTrue(key is MessageDiffRequest)
        key as MessageDiffRequest
        assertTrue(key.message, key.message.startsWith("files/ssl/web.key differs (content not shown)"))
        assertFalse(key.message.contains("synthetic"))
        val vault = request(chain, 0) as MessageDiffRequest
        assertTrue(vault.message, vault.message.startsWith("files/secrets.yml exists only in spec (content not shown)"))
        assertTrue(request(chain, 2) is SimpleDiffRequest)

        val action = key.getUserData(DiffUserDataKeys.CONTEXT_ACTIONS)!!.single() as RoleCompare.ShowSensitiveDiffAction
        assertEquals("Show Diff", action.templatePresentation.text)
        val shown = ui.chains.size
        action.actionPerformed(TestActionEvent.createTestEvent(action, SimpleDataContext.getProjectContext(project)))
        assertEquals(shown + 1, ui.chains.size)
        val revealed = ui.chains.last() as SimpleDiffRequestChain
        val real = GoldenTestSupport.process(revealed.requests.single()) as SimpleDiffRequest
        assertEquals(listOf(vf("golden", "files/ssl/web.key"), vf("spec", "files/ssl/web.key")), real.contents.map { (it as FileContent).file })

        assertEquals("compare never touches vault operations", 0, guard.calls.get())
        assertEquals("nothing is decrypted", decrypts, VaultCrypto.getInstance(project).decryptAttempts)
    }

    // ------------------------------------------------------------------ Compare with…

    fun testCompareWithRanksInScopeCopiesFirstAndComparesThePickWithTheCopy() {
        (WorkspaceScopeService.getInstance(project) as WorkspaceScopeServiceImpl).set(ScopeChoice.Roots(setOf("repos/tasks/ansible", "repos/spec/ansible")))
        ui.pick = { choices -> choices.first { it.name == "golden" } }
        val shown = ui.chains.size
        compare.compareWith(GoldenTarget(copy("mol"), "molecule/default/verify.yml"), null)
        GoldenTestSupport.waitFor("the chain is shown") { ui.chains.size > shown }

        val choices = ui.choiceLists.single()
        assertEquals("in scope first, then the rest in catalog order; never filtered", listOf("spec", "tasks", "golden", "missing", "mol2", "same", "specmol"), choices.map { it.name })
        assertEquals(listOf(true, true, false, false, false, false, false), choices.map { it.inScope })
        assertEquals(listOf("Δ spec/defaults", "Δ tasks/templates", "golden root", "Δ tasks/templates", "≈ molecule only", "= golden", "Δ spec/defaults"), choices.map { it.tier })
        assertEquals("spec · Δ spec/defaults", choices.first().text)

        val chain = ui.chains.last()
        assertEquals(listOf("molecule/default/verify.yml"), names(chain))
        val request = request(chain, 0) as SimpleDiffRequest
        assertEquals("the pick on the left, the copy on the right", listOf("golden", "mol"), request.contentTitles)
    }

    fun testCompareWithBetweenTwoRepoCopies() {
        val chain = GoldenTestSupport.await { compare.chain(copy("mol"), copy("specmol"), null) }
        assertEquals(listOf("defaults/main.yml"), chain.producers.map { it.name })
        assertEquals(copy("mol"), chain.left)
        val request = GoldenTestSupport.process(chain.chain()!!.requests.single()) as SimpleDiffRequest
        assertEquals("web · defaults/main.yml (mol ↔ specmol)", request.title)
        assertNull(chain.notice)
    }

    // ------------------------------------------------------------------ actions

    private fun context(file: VirtualFile? = null, roleCopy: VirtualFile? = null, rolePath: String? = null, extra: (SimpleDataContext.Builder) -> Unit = {}): DataContext {
        val builder = SimpleDataContext.builder().add(CommonDataKeys.PROJECT, project)
        file?.let { builder.add(CommonDataKeys.VIRTUAL_FILE, it) }
        roleCopy?.let { builder.add(GoldenDataKeys.ROLE_COPY, it) }
        rolePath?.let { builder.add(GoldenDataKeys.ROLE_PATH, it) }
        extra(builder)
        return builder.build()
    }

    private fun update(id: String, context: DataContext, place: String = ActionPlaces.UNKNOWN, uiKind: ActionUiKind = ActionUiKind.NONE): AnActionEvent {
        val action = ActionManager.getInstance().getAction(id) ?: error("$id is not registered")
        val event = AnActionEvent.createEvent(action, context, action.templatePresentation.clone(), place, uiKind, null)
        runReadActionBlocking { action.update(event) }
        return event
    }

    private fun visible(id: String, context: DataContext): Boolean = update(id, context).presentation.isEnabledAndVisible

    fun testTargetsComeFromTheToolWindowKeysOrTheFile() {
        val catalog = RoleCatalog.getInstance(project).snapshot()
        assertEquals(GoldenTarget(copy("mol"), "tasks/main.yml"), GoldenTargets.of(project, context(file = vf("mol", "tasks/main.yml"))))
        assertEquals(GoldenTarget(copy("mol"), null), GoldenTargets.of(project, context(file = dir("mol"))))
        assertEquals(GoldenTarget(copy("mol"), "tasks"), GoldenTargets.ofFile(catalog, vf("mol", "tasks")))
        assertEquals(
            "the tool window's keys win, also for a file that exists only in golden",
            GoldenTarget(copy("missing"), "tasks/install.yml"),
            GoldenTargets.of(project, context(file = vf("mol", "tasks/main.yml"), roleCopy = dir("missing"), rolePath = "tasks/install.yml")),
        )
        assertEquals(GoldenTarget(copy("missing"), null), GoldenTargets.of(project, context(roleCopy = dir("missing"))))
        assertNull("a path leaving the role is ignored", GoldenTargets.of(project, context(roleCopy = dir("missing"), rolePath = "../x"))?.relPath)
        assertNull("outside every role", GoldenTargets.of(project, context(file = DriftFixture.file(myFixture, "repos/mol/ansible/ansible.cfg"))))
    }

    fun testCompareWithGoldenIsHiddenWithoutAGoldenCopyAndOnGolden() {
        val id = "Ansibility.Golden.CompareWithGolden"
        assertTrue(visible(id, context(file = vf("mol", "tasks/main.yml"))))
        assertTrue("the copy row", visible(id, context(roleCopy = dir("mol"))))
        assertTrue("a file only in golden, from the tool window", visible(id, context(roleCopy = dir("missing"), rolePath = "tasks/install.yml")))
        assertFalse("golden itself", visible(id, context(file = vf("golden", "tasks/main.yml"))))
        assertFalse("no golden copy of solo", visible(id, context(file = DriftFixture.file(myFixture, "${DriftFixture.roleDir("same", "solo")}/tasks/main.yml"))))
        assertFalse("not in a role", visible(id, context(file = DriftFixture.file(myFixture, "repos/mol/ansible/ansible.cfg"))))
        assertFalse("nothing selected", visible(id, context()))

        val with = "Ansibility.Golden.CompareWith"
        assertTrue(visible(with, context(file = vf("golden", "tasks/main.yml"))))
        assertTrue("solo has two copies", visible(with, context(file = DriftFixture.file(myFixture, "${DriftFixture.roleDir("same", "solo")}/tasks/main.yml"))))
        assertFalse("app-same has one copy", visible(with, context(file = DriftFixture.file(myFixture, "repos/same/ansible/roles/app-same/tasks/main.yml"))))
    }

    fun testTextsByPlace() {
        val file = context(file = vf("mol", "tasks/main.yml"))
        assertEquals("Compare with Golden", update("Ansibility.Golden.CompareWithGolden", file, ActionPlaces.PROJECT_VIEW_POPUP, ActionUiKind.POPUP).presentation.text)
        assertEquals("Compare with Golden", update("Ansibility.Golden.CompareWithGolden", file, ActionPlaces.EDITOR_POPUP, ActionUiKind.POPUP).presentation.text)
        assertEquals("Ansibility: Compare with Golden", update("Ansibility.Golden.CompareWithGolden", file, ActionPlaces.ACTION_SEARCH, ActionUiKind.SEARCH_POPUP).presentation.text)
        assertEquals("Compare with Other Copy…", update("Ansibility.Golden.CompareWith", file, ActionPlaces.PROJECT_VIEW_POPUP, ActionUiKind.POPUP).presentation.text)
        assertEquals("Ansibility: Compare with Other Copy…", update("Ansibility.Golden.CompareWith", file, ActionPlaces.ACTION_SEARCH, ActionUiKind.SEARCH_POPUP).presentation.text)
    }

    fun testActionsAreRegisteredWhereCompareLivesAndBranded() {
        val actions = ActionManager.getInstance()
        val ids = actions.getActionIdList("Ansibility.Golden.Compare")
        assertEquals(setOf("Ansibility.Golden.CompareWithGolden", "Ansibility.Golden.CompareWith", "Ansibility.Golden.CompareWithGolden.ToolWindowShortcut"), ids.toSet())
        for (id in ids) assertTrue(id, actions.getAction(id).templateText.orEmpty().startsWith("Ansibility: "))
        for (group in listOf("Ansibility.ToolWindow.Popup", GoldenTestSupport.SUBMENU)) {
            val children = (actions.getAction(group) as DefaultActionGroup).getChildActionsOrStubs().mapNotNull(actions::getId)
            assertTrue("$group: $children", children.containsAll(listOf("Ansibility.Golden.CompareWithGolden", "Ansibility.Golden.CompareWith")))
            assertFalse(group, "Ansibility.Golden.CompareWithGolden.ToolWindowShortcut" in children)
        }
    }

    fun testTheEditorAndTheProjectViewShowOneAnsibilityGoldenSubmenu() {
        val actions = ActionManager.getInstance()
        val submenu = actions.getAction(GoldenTestSupport.SUBMENU) as DefaultActionGroup
        assertEquals("Ansibility Golden", submenu.templateText)
        assertTrue("a submenu", submenu.templatePresentation.isPopupGroup)
        for (group in listOf("EditorPopupMenu", "ProjectViewPopupMenu")) {
            val children = (actions.getAction(group) as DefaultActionGroup).getChildActionsOrStubs().mapNotNull(actions::getId)
            val anchor = if (group == "EditorPopupMenu") "CompareClipboardWithSelection" else "CompareFileWithEditor"
            assertEquals("$group: next to the platform's Compare, $children", children.indexOf(anchor) + 1, children.indexOf(GoldenTestSupport.SUBMENU))
            assertTrue("$group: no golden action flat, $children", children.none { it.startsWith("Ansibility.Golden.") && it != GoldenTestSupport.SUBMENU })
        }
        assertTrue("shown in a role copy", update(GoldenTestSupport.SUBMENU, context(file = vf("mol", "tasks/main.yml")), ActionPlaces.EDITOR_POPUP, ActionUiKind.POPUP).presentation.isVisible)
        val outside = update(GoldenTestSupport.SUBMENU, context(file = DriftFixture.file(myFixture, "repos/mol/ansible/ansible.cfg")), ActionPlaces.EDITOR_POPUP, ActionUiKind.POPUP)
        assertFalse("hidden outside role copies", outside.presentation.isVisible)
        assertTrue("hidden when none of its actions applies", outside.presentation.isHideGroupIfEmpty)
        val fragment = javaClass.getResource("/META-INF/ansibility-golden.xml")!!.readText()
        assertTrue(
            "Find Action lists each action once, not the submenu as well",
            Regex("""<group id="${GoldenTestSupport.SUBMENU}"[^>]*searchable="false"""").containsMatchIn(fragment),
        )
    }

    fun testTheStickyGoldenNotificationGroupIsRegistered() {
        val group = NotificationGroupManager.getInstance().getNotificationGroup("Ansibility Golden")
        assertNotNull(group)
        assertEquals(NotificationDisplayType.STICKY_BALLOON, group!!.displayType)
    }

    fun testTheToolWindowShortcutIsTheCompareShortcutAndWorksOnlyThere() {
        val id = "Ansibility.Golden.CompareWithGolden.ToolWindowShortcut"
        val keymap = KeymapManager.getInstance().activeKeymap
        assertEquals(
            "use-shortcut-of Diff.ShowDiff",
            keymap.getShortcuts(IdeActions.ACTION_SHOW_DIFF_COMMON).toList(),
            ActionManager.getInstance().getAction(id).shortcutSet.shortcuts.toList(),
        )
        val window = object : ToolWindowHeadlessManagerImpl.MockToolWindow(project) {
            override fun getId(): String = AnsibleToolWindowFactory.ID
        }
        val inToolWindow = context(roleCopy = dir("mol")) { it.add(PlatformDataKeys.TOOL_WINDOW, window) }
        assertTrue(update(id, inToolWindow, ActionPlaces.KEYBOARD_SHORTCUT).presentation.isEnabledAndVisible)
        assertFalse("not in Find Action", update(id, inToolWindow, ActionPlaces.ACTION_SEARCH, ActionUiKind.SEARCH_POPUP).presentation.isEnabledAndVisible)
        assertFalse("the editor keeps its Cmd+D", visible(id, context(file = vf("mol", "tasks/main.yml"))))
    }

    fun testTheActionComparesTheSelection() {
        val action: AnAction = ActionManager.getInstance().getAction("Ansibility.Golden.CompareWithGolden")
        val shown = ui.chains.size
        action.actionPerformed(TestActionEvent.createTestEvent(action, context(roleCopy = dir("missing"), rolePath = "tasks/install.yml")))
        GoldenTestSupport.waitFor("the chain is shown") { ui.chains.size > shown }
        val chain = ui.chains.last()
        assertEquals(12, chain.requests.size)
        assertEquals("tasks/install.yml", chain.requests[chain.index].name)
    }

    /** A [LastChangeLookup] with the answers a test gives it, for files only. */
    private class FixedLookup : LastChangeLookup {
        val answers = java.util.concurrent.ConcurrentHashMap<VirtualFile, LastChange>()

        override fun lastChange(project: Project, file: VirtualFile): LastChange? = answers[file]

        override fun lastChangeUnder(project: Project, dir: VirtualFile): LastChange? = null
    }
}
