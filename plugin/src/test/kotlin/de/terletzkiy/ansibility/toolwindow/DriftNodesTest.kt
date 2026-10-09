package de.terletzkiy.ansibility.toolwindow

import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.ExtensionTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.registerOrReplaceServiceInstance
import de.terletzkiy.ansibility.golden.GoldenTestSupport
import de.terletzkiy.ansibility.golden.history.LastChange
import de.terletzkiy.ansibility.golden.history.LastChangeLookup
import de.terletzkiy.ansibility.golden.history.LastChanges
import de.terletzkiy.ansibility.model.drift.DriftFixture
import de.terletzkiy.ansibility.model.drift.RoleDriftService
import de.terletzkiy.ansibility.model.role.ModelFixture
import de.terletzkiy.ansibility.settings.AnsibilityProjectSettings
import de.terletzkiy.ansibility.settings.GoldenRoot
import de.terletzkiy.ansibility.settings.SettingsTestSupport
import de.terletzkiy.ansibility.toolwindow.model.AnsibleTreeNode
import de.terletzkiy.ansibility.toolwindow.model.DetailItem
import de.terletzkiy.ansibility.toolwindow.model.DifferencesNode
import de.terletzkiy.ansibility.toolwindow.model.DriftDetailsContent
import de.terletzkiy.ansibility.toolwindow.model.DriftFileKind
import de.terletzkiy.ansibility.toolwindow.model.DriftFileNode
import de.terletzkiy.ansibility.toolwindow.model.DriftGroupNode
import de.terletzkiy.ansibility.toolwindow.model.GoldenActionIds
import de.terletzkiy.ansibility.toolwindow.model.LastChangeRequest
import de.terletzkiy.ansibility.toolwindow.model.NavigationTarget
import de.terletzkiy.ansibility.toolwindow.model.NodeActivation
import de.terletzkiy.ansibility.toolwindow.model.NodeColor
import de.terletzkiy.ansibility.toolwindow.model.NodeIcon
import de.terletzkiy.ansibility.toolwindow.model.NodeDetails
import de.terletzkiy.ansibility.toolwindow.model.NodeStyle
import de.terletzkiy.ansibility.toolwindow.model.RoleFileNode
import de.terletzkiy.ansibility.toolwindow.model.RoleNameNode
import de.terletzkiy.ansibility.toolwindow.model.RoleNode
import de.terletzkiy.ansibility.toolwindow.model.TreeContext
import de.terletzkiy.ansibility.toolwindow.model.TreeView
import de.terletzkiy.ansibility.toolwindow.model.VariantGroupNode
import de.terletzkiy.ansibility.toolwindow.model.WorkspaceNode
import de.terletzkiy.ansibility.toolwindow.model.WorkspaceSnapshotBuilder
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The drift rows and details of the tool window (plan amendment R24, D178–D181) on the synthetic drift tree (golden
 * plus seven repo copies of `web`, one per tier), headless: the nodes over a built snapshot, with a fresh
 * [RoleDriftService] in place of the project's.
 */
class DriftNodesTest : BasePlatformTestCase() {
    private lateinit var service: RoleDriftService

    override fun getTestDataPath(): String = ModelFixture.testDataPath

    override fun setUp() {
        super.setUp()
        DriftFixture.copy(myFixture)
        service = DriftFixture.freshService(project, testRootDisposable)
        project.registerOrReplaceServiceInstance(RoleDriftService::class.java, service, testRootDisposable)
    }

    override fun tearDown() {
        try {
            FileDocumentManager.getInstance().saveAllDocuments()
            SettingsTestSupport.resetAll(project)
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    private fun golden(golden: GoldenRoot) = AnsibilityProjectSettings.getInstance(project).update { it.copy(drift = it.drift.copy(golden = golden)) }

    private fun tab(view: TreeView, driftedOnly: Boolean = false, groupByVariant: Boolean = false) =
        WorkspaceNode(runReadActionBlocking { WorkspaceSnapshotBuilder.build(project) }, view, driftedOnly, groupByVariant)

    private fun children(node: AnsibleTreeNode): List<AnsibleTreeNode> = runReadActionBlocking { node.children(TreeContext.NONE) }

    private fun child(node: AnsibleTreeNode, name: String): AnsibleTreeNode =
        children(node).firstOrNull { it.presentation().name == name } ?: error("no $name below ${node.presentation().name}: ${children(node).map { it.presentation().name }}")

    private fun path(node: AnsibleTreeNode, vararg names: String): AnsibleTreeNode = names.fold(node, ::child)

    private fun details(node: AnsibleTreeNode): NodeDetails? = runReadActionBlocking { node.details() }

    private fun computeAll() {
        DriftFixture.await { service.driftAll() }
    }

    private fun dir(team: String, role: String = "web"): VirtualFile = DriftFixture.file(myFixture, DriftFixture.roleDir(team, role))

    private fun extras(node: AnsibleTreeNode): Map<String, String?> = children(node).associate { it.presentation().name to it.presentation().extra }

    private fun badges(node: AnsibleTreeNode): Map<String, String?> = children(node).associate { it.presentation().name to it.presentation().badge }

    // ------------------------------------------------------------------ D178: no golden root

    fun testWithoutAGoldenRootNoDriftShows() {
        computeAll()
        val roles = tab(TreeView.ROLES)
        val web = child(roles, "web")
        assertEquals("the copies list as before R24", "8 copies · golden, missing, mol, mol2 +4", web.presentation().extra)
        assertTrue(badges(web).values.all { it == null })
        val missing = child(web, "missing")
        assertTrue("no Differences group", children(missing).none { it is DifferencesNode })
        assertNull("no drift details", details(missing))
        assertNull(details(web))
        val main = path(missing, "tasks", "main.yml")
        assertNull("no colours", main.presentation().color)
        assertEquals("Drifted Only has no effect without a golden root", 4, children(tab(TreeView.ROLES, driftedOnly = true)).size)
    }

    // ------------------------------------------------------------------ D179: rows

    fun testRoleNamesAndCopiesCarryTheirTiers() {
        golden(GoldenRoot.FirstRoleLibrary)
        val pending = tab(TreeView.ROLES)
        assertEquals("8 copies · …", child(pending, "web").presentation().extra)
        assertEquals("…", child(child(pending, "web"), "mol").presentation().badge)

        computeAll()
        val roles = tab(TreeView.ROLES)
        assertEquals(
            mapOf(
                "app-same" to "1 copy · no golden copy",
                "base" to "3 copies · identical everywhere",
                "solo" to "2 copies · no golden copy",
                "web" to "8 copies · 6 differ from golden",
            ),
            extras(roles),
        )
        val web = child(roles, "web")
        assertEquals(listOf("golden") + DriftFixture.WEB_REPOS, children(web).map { it.presentation().name })
        assertEquals(
            mapOf(
                "golden" to "golden root",
                "missing" to "Δ tasks/templates · 3 changed · 9 only in golden",
                "mol" to "≈ molecule only · 1 file",
                "mol2" to "≈ molecule only · 1 file",
                "same" to "= golden",
                "spec" to "Δ spec/defaults · 1 file",
                "specmol" to "Δ spec/defaults · 2 files",
                "tasks" to "Δ tasks/templates · 1 file",
            ),
            badges(web),
        )
        assertEquals("same only", child(child(roles, "app-same"), "same").presentation().badge)
        for (text in extras(roles).values + badges(web).values) assertFalse(text, text.orEmpty().contains("outdated", ignoreCase = true))
    }

    fun testAUniformTierIsNamedOnTheRoleRow() {
        golden(GoldenRoot.Root("repos/mol/ansible"))
        DriftFixture.write(myFixture, "${DriftFixture.roleDir("same", "base")}/molecule/default/verify.yml", "---\n- hosts: all\n")
        computeAll()
        assertEquals("3 copies · 1 differs from mol (molecule only)", child(tab(TreeView.ROLES), "base").presentation().extra)
        assertEquals("the golden copy leads", listOf("mol", "golden", "same"), children(child(tab(TreeView.ROLES), "base")).map { it.presentation().name })
    }

    fun testDifferencesGroupTheFilesByCategory() {
        golden(GoldenRoot.FirstRoleLibrary)
        computeAll()
        val web = child(tab(TreeView.ROLES), "web")
        val missing = child(web, "missing") as RoleNode
        val differences = children(missing).first()
        assertTrue(differences is DifferencesNode)
        assertEquals("Differences from golden (12)", differences.presentation().name)
        assertEquals("the yellow folder, unlike the role's own directories", NodeIcon.DRIFT_FOLDER, differences.presentation().icon)
        assertEquals(setOf(NodeIcon.DRIFT_FOLDER), children(differences).map { it.presentation().icon }.toSet())
        assertEquals(
            mapOf("Tasks/templates" to "2", "Spec/defaults" to "1", "Only in golden" to "9"),
            extras(differences),
        )
        val tasks = child(differences, "Tasks/templates") as DriftGroupNode
        assertEquals(listOf("handlers/main.yml", "tasks/main.yml"), children(tasks).map { it.presentation().name })
        val changed = child(tasks, "tasks/main.yml") as DriftFileNode
        assertEquals(NodeColor.MODIFIED, changed.presentation().color)
        assertEquals(NodeStyle.NORMAL, changed.presentation().style)
        assertEquals(NavigationTarget(DriftFixture.file(myFixture, "${DriftFixture.roleDir("missing")}/tasks/main.yml")), changed.target)
        val gone = children(child(differences, "Only in golden")).first() as DriftFileNode
        assertEquals("meta/argument_specs.yml", gone.relPath)
        assertEquals(DriftFileKind.ONLY_IN_GOLDEN, gone.kind)
        assertEquals(NodeColor.DELETED, gone.presentation().color)
        assertEquals("only in golden rows are struck", NodeStyle.STRUCK, gone.presentation().style)
        assertNull("nothing of this copy to open", gone.target)
        assertEquals("the files follow the group", listOf("Differences from golden (12)", "defaults", "files", "handlers"), children(missing).take(4).map { it.presentation().name })

        assertTrue("an identical copy has no Differences", children(child(web, "same")).none { it is DifferencesNode })
        assertTrue("the golden copy has none", children(child(web, "golden")).none { it is DifferencesNode })

        DriftFixture.write(myFixture, "${DriftFixture.roleDir("same")}/files/extra.txt", "only here\n")
        computeAll()
        val same = child(child(tab(TreeView.ROLES), "web"), "same")
        val onlyHere = path(same, "Differences from golden (1)", "Only here", "files/extra.txt")
        assertEquals(NodeColor.ADDED, onlyHere.presentation().color)
    }

    fun testARolesOwnDirectoriesShowWhatAnsibleLoadsFromThem() {
        val golden = child(child(tab(TreeView.ROLES), "web"), "golden")
        assertEquals(
            mapOf(
                "defaults" to NodeIcon.ROLE_VARS, "files" to NodeIcon.ROLE_FILES, "handlers" to NodeIcon.ROLE_HANDLERS,
                "meta" to NodeIcon.ROLE_META, "molecule" to NodeIcon.ROLE_TESTS, "tasks" to NodeIcon.ROLE_TASKS,
                "templates" to NodeIcon.ROLE_TEMPLATES, "vars" to NodeIcon.ROLE_VARS,
            ),
            children(golden).associate { it.presentation().name to it.presentation().icon },
        )
        assertEquals("a directory deeper down is a plain folder", NodeIcon.FOLDER, path(golden, "molecule", "default").presentation().icon)
        assertEquals(NodeIcon.FILE, path(golden, "tasks", "main.yml").presentation().icon)
    }

    fun testRoleFileRowsTakeTheStatusColour() {
        golden(GoldenRoot.FirstRoleLibrary)
        DriftFixture.write(myFixture, "${DriftFixture.roleDir("tasks")}/files/extra.txt", "only here\n")
        computeAll()
        val web = child(tab(TreeView.ROLES), "web")
        val tasks = child(web, "tasks")
        assertEquals(NodeColor.MODIFIED, path(tasks, "tasks", "main.yml").presentation().color)
        assertEquals(NodeColor.ADDED, path(tasks, "files", "extra.txt").presentation().color)
        assertNull("an equal file keeps its colour", path(tasks, "files", "motd.txt").presentation().color)
        assertNull("directories are not coloured", child(tasks, "tasks").presentation().color)
        assertEquals(NodeColor.MODIFIED, path(child(web, "mol"), "molecule", "default", "verify.yml").presentation().color)
        val file = path(tasks, "tasks", "main.yml") as RoleFileNode
        assertEquals(dir("tasks"), file.copyDir)
        assertEquals("tasks/main.yml", file.rolePath)
    }

    fun testTheReposTabShowsKnownDriftOnly() {
        golden(GoldenRoot.FirstRoleLibrary)
        val before = path(tab(TreeView.REPOS), "mol", "Roles (2)", "web")
        assertNull("the Repos tab never starts drift and shows no pending mark", before.presentation().badge)
        computeAll()
        val web = path(tab(TreeView.REPOS), "mol", "Roles (2)", "web") as RoleNode
        assertEquals("≈ molecule only · 1 file", web.presentation().badge)
        assertTrue(children(web).first() is DifferencesNode)
    }

    fun testDriftedOnlyKeepsTheDriftingNames() {
        golden(GoldenRoot.FirstRoleLibrary)
        assertEquals("nothing is known yet", emptyList<String>(), children(tab(TreeView.ROLES, driftedOnly = true)).map { it.presentation().name })
        computeAll()
        assertEquals(listOf("web"), children(tab(TreeView.ROLES, driftedOnly = true)).map { it.presentation().name })
        assertEquals(4, children(tab(TreeView.ROLES)).size)
    }

    // ------------------------------------------------------------------ D181: sensitive files

    fun testSensitiveFilesNeverShowOrOpenTheirContent() {
        golden(GoldenRoot.FirstRoleLibrary)
        DriftFixture.write(myFixture, "${DriftFixture.roleDir("golden")}/files/ssl/web.key", "synthetic golden key\n")
        DriftFixture.write(myFixture, "${DriftFixture.roleDir("spec")}/files/ssl/web.key", "synthetic repo key!!\n")
        DriftFixture.write(myFixture, "${DriftFixture.roleDir("tasks")}/files/secrets.yml", DriftFixture.syntheticVault("synthetic drift vault"))
        computeAll()
        val web = child(tab(TreeView.ROLES), "web")
        val key = path(child(web, "spec"), "Differences from golden (2)", "Tasks/templates", "files/ssl/web.key") as DriftFileNode
        assertTrue(key.sensitive)
        assertEquals("differs (content not shown)", key.presentation().extra)
        assertNull("never opened by itself", key.target)
        val vault = path(child(web, "tasks"), "Differences from golden (3)", "Only here", "files/secrets.yml") as DriftFileNode
        assertEquals("a whole-file vault is sensitive whatever its name", "differs (content not shown)", vault.presentation().extra)
        assertNull(vault.target)
        val listed = path(child(web, "spec"), "files", "ssl", "web.key")
        assertEquals(NodeColor.MODIFIED, listed.presentation().color)
        assertEquals("differs (content not shown)", listed.presentation().extra)

        val opened = ArrayList<NavigationTarget>()
        val activation = object : NodeActivation {
            override fun runAction(id: String): Boolean = false

            override fun navigate(target: NavigationTarget): Boolean = opened.add(target)
        }
        assertTrue(key.activate(activation))
        assertTrue("without Compare a sensitive row opens nothing", opened.isEmpty())

        val content = details(child(web, "spec"))!!.content as DriftDetailsContent
        assertEquals(listOf(true, false), content.files.map { it.sensitive })
        val text = details(child(web, "spec")).toString() + details(key).toString()
        assertFalse(text, text.contains("synthetic") || text.contains("ANSIBLE_VAULT"))
    }

    // ------------------------------------------------------------------ D180: details

    fun testCopyDetails() {
        golden(GoldenRoot.FirstRoleLibrary)
        computeAll()
        val web = child(tab(TreeView.ROLES), "web")
        val missing = details(child(web, "missing"))!!
        assertEquals("web in missing", missing.title)
        assertEquals("Δ tasks/templates · 3 changed · 9 only in golden", missing.subtitle)
        val drift = missing.section("Drift")!!.items.map(DetailItem::text)
        assertEquals("Tier: Δ tasks/templates", drift[0])
        assertTrue(drift[1], drift[1].startsWith("Path: ") && drift[1].endsWith("repos/missing/ansible/roles/web"))
        assertEquals("argument_specs: none", drift[2])
        assertEquals(listOf("No play of missing applies this copy"), missing.section("Applied by")!!.items.map(DetailItem::text))
        assertEquals(
            listOf("No other copy has this content; 1 of 8 copies", "golden's variant is shared by 2 of 8 copies"),
            missing.section("Variant")!!.items.map(DetailItem::text),
        )
        val content = missing.content as DriftDetailsContent
        assertEquals(dir("missing"), content.copyDir)
        assertEquals("golden", content.golden)
        assertEquals(12, content.files.size)
        assertEquals(listOf("Tasks/templates", "Tasks/templates", "Spec/defaults"), content.files.take(3).map { it.group })
        assertEquals(listOf(DriftFileKind.ONLY_IN_GOLDEN), content.files.drop(3).map { it.kind }.distinct())
        assertEquals(GoldenActionIds.COPY_ACTIONS, content.actions)

        val mol = details(child(web, "mol"))!!
        assertEquals(listOf("Same as mol2; 2 of 8 copies", "golden's variant is shared by 2 of 8 copies"), mol.section("Variant")!!.items.map(DetailItem::text))
        val same = details(child(web, "same"))!!
        assertEquals(listOf("Same as golden; 2 of 8 copies"), same.section("Variant")!!.items.map(DetailItem::text))
        assertEquals("argument_specs: present", same.section("Drift")!!.items[2].text)

        val golden = details(child(web, "golden"))!!
        assertEquals("This is the golden copy; the other copies are compared with it", golden.section("Drift")!!.items[0].text)
        assertNull("a role library lists no missing plays", golden.section("Applied by"))
        assertTrue((golden.content as DriftDetailsContent).files.isEmpty())
    }

    fun testRoleNameAndFileDetails() {
        golden(GoldenRoot.FirstRoleLibrary)
        computeAll()
        val web = child(tab(TreeView.ROLES), "web") as RoleNameNode
        val name = details(web)!!
        assertEquals("web", name.title)
        assertEquals("8 copies · 6 differ from golden", name.subtitle)
        assertEquals(
            listOf("golden — golden root", "missing — Δ tasks/templates · 3 changed · 9 only in golden", "mol — ≈ molecule only · 1 file"),
            name.section("Copies")!!.items.take(3).map(DetailItem::toString),
        )
        val file = path(web, "mol", "Differences from golden (1)", "Molecule", "molecule/default/verify.yml")
        val details = details(file)!!
        assertEquals("molecule/default/verify.yml", details.title)
        assertEquals("web in mol", details.subtitle)
        assertEquals("Changed: differs from the copy in golden", details.section("Drift")!!.items.single().text)
        val content = details.content as DriftDetailsContent
        assertEquals(listOf("Molecule"), content.files.map { it.group })
        assertEquals(listOf(GoldenActionIds.COMPARE_WITH_GOLDEN), content.actions)
        assertEquals("the group's details are its copy's", details(child(web, "mol")), details(path(web, "mol", "Differences from golden (1)")))
    }

    // ------------------------------------------------------------------ D180 buttons: what each copy can do

    fun testTheButtonsFollowTheTier() {
        golden(GoldenRoot.FirstRoleLibrary)
        val pending = child(tab(TreeView.ROLES), "web")
        assertEquals("not known yet: every action decides itself", GoldenActionIds.COPY_ACTIONS, (details(child(pending, "mol"))!!.content as DriftDetailsContent).actions)
        computeAll()
        val web = child(tab(TreeView.ROLES), "web")
        fun actions(node: AnsibleTreeNode) = (details(node)!!.content as DriftDetailsContent).actions
        assertEquals(GoldenActionIds.COPY_ACTIONS, actions(child(web, "mol")))
        assertEquals("the golden copy has nothing to compare with itself", listOf(GoldenActionIds.PUSH_TO_REPOS), actions(child(web, "golden")))
        assertEquals("an identical copy has nothing to align", listOf(GoldenActionIds.PUSH_TO_REPOS), actions(child(web, "same")))
        assertEquals("no golden copy", listOf(GoldenActionIds.PUSH_TO_REPOS), actions(child(child(tab(TreeView.ROLES), "solo"), "tasks")))
    }

    // ------------------------------------------------------------------ X123: Group by Variant

    /** same takes mol's molecule edit: golden is alone, mol, mol2 and same share a variant. */
    private fun sameTakesMolsEdit() = DriftFixture.copyFile(myFixture, "mol", "same", "molecule/default/verify.yml")

    fun testGroupByVariantListsVariantGroupsOfTheSameCopyRows() {
        golden(GoldenRoot.FirstRoleLibrary)
        assertEquals(
            "while the drift is not known the copies list as usual",
            listOf("golden") + DriftFixture.WEB_REPOS,
            children(child(tab(TreeView.ROLES, groupByVariant = true), "web")).map { it.presentation().name },
        )
        sameTakesMolsEdit()
        computeAll()
        val grouped = child(tab(TreeView.ROLES, groupByVariant = true), "web")
        assertEquals(
            "golden's group first, then by size, then by name",
            listOf(
                "Variant A: golden (1)", "Variant B: mol, mol2, same (3)", "Variant C: missing (1)", "Variant D: spec (1)",
                "Variant E: specmol (1)", "Variant F: tasks (1)",
            ),
            children(grouped).map { it.presentation().name },
        )
        val group = child(grouped, "Variant B: mol, mol2, same (3)") as VariantGroupNode
        assertEquals("a variant differs from golden the same way in each copy", "≈ molecule only · 1 file", group.presentation().badge)
        assertNull(child(grouped, "Variant A: golden (1)").presentation().badge)
        assertEquals(listOf("Byte-identical copies: mol, mol2, same"), group.presentation().tooltip)
        assertEquals("the group's details are the name's", details(grouped), details(group))

        val flat = child(tab(TreeView.ROLES), "web")
        val rows = children(group)
        assertEquals(listOf("mol", "mol2", "same"), rows.map { it.presentation().name })
        for (row in rows) {
            val twin = child(flat, row.presentation().name)
            assertTrue(row is RoleNode)
            assertEquals("the same key grouped or not", twin.key, row.key)
            assertEquals(twin, row)
            assertEquals(twin.presentation(), row.presentation())
            assertEquals(children(twin).map { it.key }, children(row).map { it.key })
            assertEquals((twin as RoleNode).copyDir, (row as RoleNode).copyDir)
        }
        assertTrue("the Differences group is there", children(rows.first()).first() is DifferencesNode)
        assertEquals(details(child(flat, "mol")), details(rows.first()))

        assertEquals("other names group too", listOf("Variant A: golden, mol, same (3)"), children(child(tab(TreeView.ROLES, groupByVariant = true), "base")).map { it.presentation().name })
        assertEquals("Drifted Only and Group by Variant together", listOf("web"), children(tab(TreeView.ROLES, driftedOnly = true, groupByVariant = true)).map { it.presentation().name })
    }

    fun testAVariantGroupKeepsItsKeyWhenItsMembersOrLetterChange() {
        golden(GoldenRoot.FirstRoleLibrary)
        sameTakesMolsEdit()
        computeAll()
        val before = child(child(tab(TreeView.ROLES, groupByVariant = true), "web"), "Variant B: mol, mol2, same (3)")
        DriftFixture.write(myFixture, "${DriftFixture.roleDir("mol2")}/files/extra.txt", "only in mol2\n")
        computeAll()
        val after = children(child(tab(TreeView.ROLES, groupByVariant = true), "web"))
        val group = after.single { it.presentation().name.startsWith("Variant B:") }
        assertEquals("Variant B: mol, same (2)", group.presentation().name)
        assertEquals("keyed by its first copy, not by its members", before.key, group.key)
        assertEquals("mol2 is alone now, ranked by name", "Variant D: mol2 (1)", after[3].presentation().name)

        DriftFixture.joinSpecsVariant(myFixture)
        computeAll()
        val moved = children(child(tab(TreeView.ROLES, groupByVariant = true), "web"))
        assertEquals(listOf("Variant A: golden (1)", "Variant B: spec, specmol, tasks (3)", "Variant C: mol, same (2)"), moved.take(3).map { it.presentation().name })
        assertEquals("nor by its letter", before.key, moved[2].key)
    }

    fun testWithoutAGoldenRootGroupByVariantHasNoEffect() {
        computeAll()
        assertEquals(listOf("golden") + DriftFixture.WEB_REPOS, children(child(tab(TreeView.ROLES, groupByVariant = true), "web")).map { it.presentation().name })
    }

    // ------------------------------------------------------------------ D180 "Last changed" and X122 hints

    /** Answers from [dirs] and [files] (by path below the project), counting the lookups. */
    private class FakeLookup(private val dirs: Map<String, LastChange>, private val files: Map<String, LastChange> = emptyMap()) : LastChangeLookup {
        override fun lastChange(project: Project, file: VirtualFile): LastChange? = files.entries.firstOrNull { file.path.endsWith("/" + it.key) }?.value

        override fun lastChangeUnder(project: Project, dir: VirtualFile): LastChange? = dirs.entries.firstOrNull { dir.path.endsWith("/" + it.key) }?.value
    }

    private fun day(year: Int, month: Int, day: Int): Instant = LocalDate.of(year, month, day).atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant()

    private fun useLookup(lookup: LastChangeLookup?) {
        ExtensionTestUtil.maskExtensions(LastChangeLookup.EP_NAME, listOfNotNull(lookup), testRootDisposable)
        LastChanges.getInstance(project).clearForTests()
    }

    private val alice get() = LastChange("alice", day(2026, 9, 12), "fix verify", "a1b2c3d4")
    private val bob get() = LastChange("bob", day(2025, 3, 1), "tune the handlers of web so that every restart waits for the health check", "e5f6a7b8")

    fun testCopyDetailsShowTheCachedLastChangeOfEachSide() {
        useLookup(FakeLookup(mapOf(DriftFixture.roleDir("golden") to alice, DriftFixture.roleDir("tasks") to bob)))
        golden(GoldenRoot.FirstRoleLibrary)
        computeAll()
        val web = child(tab(TreeView.ROLES), "web")
        val lookups = LastChanges.getInstance(project).lookupCount
        val before = details(child(web, "tasks"))!!
        assertNull("the details read the cache only", before.section("Last changed"))
        assertEquals(
            "they name the sides to look up",
            listOf(LastChangeRequest(dir("golden"), directory = true), LastChangeRequest(dir("tasks"), directory = true)),
            (before.content as DriftDetailsContent).lastChanges,
        )
        assertEquals("no lookup from the details' read action", lookups, LastChanges.getInstance(project).lookupCount)

        GoldenTestSupport.pooled { listOf(dir("golden"), dir("tasks")).map(LastChanges.getInstance(project)::lastChangeUnder) }
        val after = details(child(web, "tasks"))!!
        assertEquals(
            listOf(
                "golden: 2026-09-12 · alice · fix verify",
                "tasks: 2025-03-01 · bob · tune the handlers of web so that every restart waits for th…",
                "golden changed this role more recently (2026-09-12) than tasks (2025-03-01)",
            ),
            after.section("Last changed")!!.items.map(DetailItem::text),
        )
        assertTrue((after.content as DriftDetailsContent).lastChanges.isEmpty())

        val golden = details(child(web, "golden"))!!
        assertEquals("the golden copy has one side and no direction", listOf("golden: 2026-09-12 · alice · fix verify"), golden.section("Last changed")!!.items.map(DetailItem::text))
        GoldenTestSupport.pooled { LastChanges.getInstance(project).lastChangeUnder(dir("same")) }
        val same = details(child(web, "same"))!!
        assertEquals("an identical copy has no direction; a side without a commit shows nothing", listOf("golden: 2026-09-12 · alice · fix verify"), same.section("Last changed")!!.items.map(DetailItem::text))
        val text = listOf(after, golden, same).joinToString { it.toString() }
        assertFalse(text, text.contains("outdated", ignoreCase = true) || text.contains("newer", ignoreCase = true))
    }

    fun testFileDetailsShowTheLastChangeOfThatFile() {
        useLookup(
            FakeLookup(
                emptyMap(),
                mapOf("${DriftFixture.roleDir("golden")}/tasks/main.yml" to bob, "${DriftFixture.roleDir("missing")}/tasks/main.yml" to alice, "${DriftFixture.roleDir("golden")}/tasks/install.yml" to alice),
            ),
        )
        golden(GoldenRoot.FirstRoleLibrary)
        computeAll()
        val differences = path(child(tab(TreeView.ROLES), "web"), "missing", "Differences from golden (12)")
        val changed = path(differences, "Tasks/templates", "tasks/main.yml")
        val goldenFile = DriftFixture.file(myFixture, "${DriftFixture.roleDir("golden")}/tasks/main.yml")
        val copyFile = DriftFixture.file(myFixture, "${DriftFixture.roleDir("missing")}/tasks/main.yml")
        assertEquals(
            listOf(LastChangeRequest(goldenFile, directory = false), LastChangeRequest(copyFile, directory = false)),
            (details(changed)!!.content as DriftDetailsContent).lastChanges,
        )
        GoldenTestSupport.pooled { listOf(goldenFile, copyFile).map(LastChanges.getInstance(project)::lastChange) }
        assertEquals(
            listOf(
                "golden: 2025-03-01 · bob · tune the handlers of web so that every restart waits for th…",
                "missing: 2026-09-12 · alice · fix verify",
                "missing changed this file more recently (2026-09-12) than golden (2025-03-01)",
            ),
            details(changed)!!.section("Last changed")!!.items.map(DetailItem::text),
        )

        val onlyInGolden = path(differences, "Only in golden", "tasks/install.yml")
        val install = DriftFixture.file(myFixture, "${DriftFixture.roleDir("golden")}/tasks/install.yml")
        assertEquals("only golden's side", listOf(LastChangeRequest(install, directory = false)), (details(onlyInGolden)!!.content as DriftDetailsContent).lastChanges)
        GoldenTestSupport.pooled { LastChanges.getInstance(project).lastChange(install) }
        assertEquals(listOf("golden: 2026-09-12 · alice · fix verify"), details(onlyInGolden)!!.section("Last changed")!!.items.map(DetailItem::text))
    }

    fun testRoleFileRowsShowTheSameFactsForThatFile() {
        fun at(team: String, path: String) = "${DriftFixture.roleDir(team)}/$path"
        useLookup(
            FakeLookup(
                emptyMap(),
                mapOf(at("golden", "tasks/main.yml") to bob, at("tasks", "tasks/main.yml") to alice, at("golden", "files/motd.txt") to bob, at("tasks", "files/motd.txt") to alice),
            ),
        )
        golden(GoldenRoot.FirstRoleLibrary)
        computeAll()
        val web = child(tab(TreeView.ROLES), "web")
        val copy = child(web, "tasks")
        val main = path(copy, "tasks", "main.yml")
        assertEquals(
            "a differing file in the listing has the details of its Differences row",
            details(path(copy, "Differences from golden (1)", "Tasks/templates", "tasks/main.yml")),
            details(main),
        )
        val files = listOf("tasks/main.yml", "files/motd.txt").flatMap { listOf(DriftFixture.file(myFixture, at("golden", it)), DriftFixture.file(myFixture, at("tasks", it))) }
        GoldenTestSupport.pooled { files.map(LastChanges.getInstance(project)::lastChange) }
        assertEquals(
            listOf(
                "golden: 2025-03-01 · bob · tune the handlers of web so that every restart waits for th…",
                "tasks: 2026-09-12 · alice · fix verify",
                "tasks changed this file more recently (2026-09-12) than golden (2025-03-01)",
            ),
            details(main)!!.section("Last changed")!!.items.map(DetailItem::text),
        )

        val motd = details(path(copy, "files", "motd.txt"))!!
        assertEquals("files/motd.txt", motd.title)
        assertEquals(listOf("Same as the copy in golden"), motd.section("Drift")!!.items.map(DetailItem::text))
        assertEquals(
            "a file that is the same has no direction",
            listOf("golden: 2025-03-01 · bob · tune the handlers of web so that every restart waits for th…", "tasks: 2026-09-12 · alice · fix verify"),
            motd.section("Last changed")!!.items.map(DetailItem::text),
        )
        val content = motd.content as DriftDetailsContent
        assertTrue("nothing to compare", content.files.isEmpty() && content.actions.isEmpty())
        assertNull("directories have no details", details(child(copy, "files")))
        assertEquals(
            listOf("This is the golden copy; the other copies are compared with it"),
            details(path(child(web, "golden"), "files", "motd.txt"))!!.section("Drift")!!.items.map(DetailItem::text),
        )
    }

    fun testWithoutVcsThereIsNoLastChanged() {
        useLookup(null)
        golden(GoldenRoot.FirstRoleLibrary)
        computeAll()
        val web = child(tab(TreeView.ROLES), "web")
        for (node in listOf(child(web, "tasks"), path(web, "tasks", "Differences from golden (1)", "Tasks/templates", "tasks/main.yml"))) {
            val details = details(node)!!
            assertNull(details.section("Last changed"))
            assertTrue("nothing to look up", (details.content as DriftDetailsContent).lastChanges.isEmpty())
        }
    }
}
