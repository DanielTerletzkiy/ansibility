package de.terletzkiy.ansibility.settings

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.ComboBox
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.UIUtil
import de.terletzkiy.ansibility.context.AnsibleWorkspaceImpl
import de.terletzkiy.ansibility.context.ContextTestTree
import de.terletzkiy.ansibility.context.ContextTestTree.DANGER_ZONE
import de.terletzkiy.ansibility.context.ContextTestTree.FALCON
import de.terletzkiy.ansibility.context.ContextTestTree.GOLDEN
import de.terletzkiy.ansibility.semantics.diagnostics.Preset
import de.terletzkiy.ansibility.settings.AnsibilitySettingsBundle.message
import de.terletzkiy.ansibility.settings.ui.AnsibilityConfigurable
import de.terletzkiy.ansibility.settings.ui.RootsTable
import javax.swing.JComponent
import javax.swing.JEditorPane
import javax.swing.JTable

/** The settings page, created headlessly: apply, reset and the roots table. */
class AnsibilityConfigurableTest : BasePlatformTestCase() {
    private lateinit var configurable: AnsibilityConfigurable
    private lateinit var component: JComponent

    override fun setUp() {
        super.setUp()
        ContextTestTree.create(myFixture)
        AnsibleWorkspaceImpl.getInstance(project)!!.structureChanged()
        configurable = AnsibilityConfigurable(project)
        component = configurable.createComponent()
        configurable.reset()
        PlatformTestUtil.waitForPromise(configurable.rootsLoading!!)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
    }

    override fun tearDown() {
        try {
            configurable.disposeUIResources()
            SettingsTestSupport.resetAll(project)
        } finally {
            super.tearDown()
        }
    }

    private fun checkBox(key: String): JBCheckBox =
        UIUtil.findComponentsOfType(component, JBCheckBox::class.java).single { it.text == message(key) }

    private fun tables(): List<JTable> = UIUtil.findComponentsOfType(component, JTable::class.java)

    private fun rootsTable(): JTable = tables().single { it.columnCount == 5 }

    private fun rulesTable(): JTable = tables().single { it.columnCount == 2 }

    private fun rowOf(table: JTable, displayName: String): Int =
        (0 until table.rowCount).single { table.getValueAt(it, 0) == displayName }

    fun testRegisteredUnderLanguagesAndFrameworks() {
        val ep = Configurable.PROJECT_CONFIGURABLE.getExtensions(project).single { it.id == AnsibilityConfigurable.ID }
        assertEquals("language", ep.parentId)
        assertEquals("Ansibility", ep.getDisplayName())
        assertEquals("Ansibility", configurable.displayName)
    }

    fun testFreshPageIsNotModified() {
        assertFalse(configurable.isModified)
    }

    fun testApplyWritesApplicationSettings() {
        checkBox("jinja.auto.close").doClick()
        checkBox("coexist.hide.completions").doClick()
        assertTrue("D10 local guess is on by default", checkBox("app.executables.local.target").isSelected)
        checkBox("app.executables.local.target").doClick()
        assertTrue(configurable.isModified)
        configurable.apply()
        val app = AnsibilityAppSettings.getInstance().settings
        assertFalse(app.jinja.autoCloseDelimiters)
        assertTrue(app.coexistence.hideOtherAnsibleCompletions)
        assertFalse(app.executables.localTargetGuess)
        assertFalse(configurable.isModified)
    }

    fun testResetDiscardsEditsAndShowsStoredValues() {
        checkBox("docs.background.refresh").doClick()
        assertTrue(configurable.isModified)
        configurable.reset()
        assertFalse(configurable.isModified)
        assertTrue(checkBox("docs.background.refresh").isSelected)

        AnsibilityAppSettings.getInstance().update { it.copy(jinja = it.jinja.copy(keepYamlForJ2 = true)) }
        AnsibilityProjectSettings.getInstance(project).update { it.copy(paths = it.paths.copy(detachedRule = false)) }
        configurable.reset()
        assertTrue(checkBox("jinja.keep.yaml").isSelected)
        assertFalse(checkBox("paths.detached").isSelected)
        assertFalse(configurable.isModified)
    }

    fun testWebBaseComboAndCustomUrl() {
        @Suppress("UNCHECKED_CAST")
        val combo = UIUtil.findComponentsOfType(component, ComboBox::class.java).first { it.selectedItem == DocsWebBase.TARGET_VERSIONED } as ComboBox<DocsWebBase>
        combo.selectedItem = DocsWebBase.LATEST
        configurable.apply()
        assertEquals(DocsWebBase.LATEST, AnsibilityAppSettings.getInstance().settings.docs.webBase)
    }

    fun testProjectPathsAreApplied() {
        checkBox("paths.detached").doClick()
        val ignored = UIUtil.findComponentsOfType(component, JBTextArea::class.java).single()
        assertEquals("**/.ansible/**\npatches/**", ignored.text)
        ignored.text = "patches/**\n\n  vendor/**  \n"
        assertTrue(configurable.isModified)
        configurable.apply()
        val paths = AnsibilityProjectSettings.getInstance(project).settings.paths
        assertFalse(paths.detachedRule)
        assertEquals(listOf("patches/**", "vendor/**"), paths.extraIgnoredPaths)
    }

    fun testTheMoleculeGroupHasTwoIndependentSwitches() {
        // Plan amendment R20, D150: "Molecule support" is gone; two switches replace it, with their comments.
        val texts = UIUtil.findComponentsOfType(component, JBCheckBox::class.java).map { it.text }
        assertFalse("no Molecule support checkbox any more", texts.any { it.contains("Molecule support") })
        val tests = checkBox("molecule.tests")
        val navigation = checkBox("molecule.navigation")
        assertTrue("tests run by default", tests.isSelected)
        assertFalse("Molecule stays out of navigation by default", navigation.isSelected)
        assertTrue("both are enabled", tests.isEnabled && navigation.isEnabled)
        val comments = UIUtil.findComponentsOfType(component, JEditorPane::class.java).joinToString("\n") { it.text }
        assertTrue("the page says how to ignore Molecule folders: $comments", comments.contains("**/molecule/**"))
        // Ignored paths never hide the run UI (MoleculeScenarios reads the VFS), so the page must not promise "completely".
        val plain = com.intellij.openapi.util.text.StringUtil.removeHtmlTags(comments).replace(Regex("\\s+"), " ")
        assertTrue("the ignore comment says runs follow the switch: $plain", plain.contains("Molecule test runs follow Run Molecule tests only"))
        assertFalse(plain, plain.contains("ignore molecule folders completely"))

        tests.doClick()
        assertTrue(configurable.isModified)
        configurable.apply()
        assertEquals(MoleculeSettings(runTests = false, showInNavigation = false), AnsibilityProjectSettings.getInstance(project).settings.molecule)

        navigation.doClick()
        configurable.apply()
        assertEquals(MoleculeSettings(runTests = false, showInNavigation = true), AnsibilityProjectSettings.getInstance(project).settings.molecule)

        tests.doClick()
        configurable.apply()
        assertEquals(MoleculeSettings(runTests = true, showInNavigation = true), AnsibilityProjectSettings.getInstance(project).settings.molecule)
        assertFalse(configurable.isModified)

        AnsibilityProjectSettings.getInstance(project).update { it.copy(molecule = MoleculeSettings()) }
        configurable.reset()
        assertTrue(checkBox("molecule.tests").isSelected)
        assertFalse(checkBox("molecule.navigation").isSelected)
        assertFalse(configurable.isModified)
    }

    fun testShareCheckboxTogglesTeamSharing() {
        checkBox("project.share").doClick()
        configurable.apply()
        assertTrue(AnsibilityProjectSettings.getInstance(project).isSharedWithTeam)
        checkBox("project.share").doClick()
        configurable.apply()
        assertFalse(AnsibilityProjectSettings.getInstance(project).isSharedWithTeam)
    }

    fun testRootsTableListsTheDetectedRoots() {
        val table = rootsTable()
        val names = (0 until table.rowCount).map { table.getValueAt(it, 0) as String }
        val expected = AnsibleWorkspaceImpl.getInstance(project)!!.roots().map { it.displayName }
        assertEquals(expected, names)
        val falcon = rowOf(table, "falcon")
        assertEquals("Auto (2.18.8)", table.getValueAt(falcon, 2))
        assertEquals(Preset.DOCUMENTED_TYPES, table.getValueAt(falcon, 3))
        assertFalse("root and kind are read-only", table.isCellEditable(falcon, 0) || table.isCellEditable(falcon, 1))
        assertTrue(table.isCellEditable(falcon, 2) && table.isCellEditable(falcon, 3) && table.isCellEditable(falcon, 4))
    }

    fun testEditingTargetAndPresetPerRoot() {
        val table = rootsTable()
        val falcon = rowOf(table, "falcon")
        table.setValueAt("2.19.1", falcon, 2)
        table.setValueAt(Preset.RUNTIME_FAITHFUL, falcon, 3)
        table.setValueAt("not a version", rowOf(table, "golden"), 2)
        assertTrue(configurable.isModified)
        configurable.apply()

        val settings = AnsibilityProjectSettings.getInstance(project).settings
        assertEquals(setOf(FALCON), settings.roots.keys)
        assertEquals("2.19.1", settings.root(FALCON).targetCore)
        assertEquals(Preset.RUNTIME_FAITHFUL, settings.root(FALCON).preset)
        assertEquals(RootSettings.DEFAULT, settings.root(GOLDEN))
        assertFalse(configurable.isModified)

        table.setValueAt("Auto", falcon, 2)
        table.setValueAt(Preset.DOCUMENTED_TYPES, falcon, 3)
        configurable.apply()
        assertTrue("back to defaults removes the entry", AnsibilityProjectSettings.getInstance(project).settings.roots.isEmpty())
    }

    fun testSelectedRootEditorEditsTogglesOfThatRoot() {
        val table = rootsTable()
        val nested = rowOf(table, AnsibleWorkspaceImpl.getInstance(project)!!.roots().single { it.dir == myFixture.findFileInTempDir(DANGER_ZONE) }.displayName)
        table.selectionModel.setSelectionInterval(nested, nested)
        val coercions = checkBox("root.toggle.module.coercions")
        assertTrue(coercions.isEnabled)
        assertFalse(coercions.isSelected)
        coercions.doClick()
        checkBox("root.toggle.certain.failures").doClick()
        assertTrue(configurable.isModified)
        configurable.apply()
        val danger = AnsibilityProjectSettings.getInstance(project).settings.root(DANGER_ZONE)
        assertTrue(danger.moduleOptionCoercions)
        assertFalse(danger.redForClaudeCertainFailures)
        assertEquals(RootSettings.DEFAULT, AnsibilityProjectSettings.getInstance(project).settings.root(FALCON))

        val falcon = rowOf(table, "falcon")
        table.selectionModel.setSelectionInterval(falcon, falcon)
        assertFalse("the editor shows the newly selected root", checkBox("root.toggle.module.coercions").isSelected)
    }

    fun testOuterLanguageRulesTable() {
        val table = rulesTable()
        assertEquals(JinjaSettings.DEFAULT_OUTER_LANGUAGE_RULES.size, table.rowCount)
        table.setValueAt("HCL", JinjaSettings.DEFAULT_OUTER_LANGUAGE_RULES.indexOfFirst { it.pattern == "haproxy.cfg" }, 1)
        assertTrue(configurable.isModified)
        configurable.apply()
        val rules = AnsibilityAppSettings.getInstance().settings.jinja.outerLanguageRules
        assertEquals("HCL", rules.single { it.pattern == "haproxy.cfg" }.languageId)
        assertEquals(JinjaSettings.DEFAULT_OUTER_LANGUAGE_RULES.size, rules.size)

        configurable.reset()
        assertEquals("HCL", table.getValueAt(rules.indexOfFirst { it.pattern == "haproxy.cfg" }, 1))
    }

    fun testTargetCellParsing() {
        assertEquals(RootsTable.Target(null), RootsTable.parseTarget("Auto (2.18.8)"))
        assertEquals(RootsTable.Target(null), RootsTable.parseTarget("  "))
        assertEquals(RootsTable.Target("2.19"), RootsTable.parseTarget(" 2.19 "))
        assertNull(RootsTable.parseTarget("2.x"))
        assertNull(RootsTable.parseTarget("latest"))
    }

    fun testHelpTextsExplainThePresets() {
        assertTrue(message("preset.help.documented").contains("even if ansible-core would convert it"))
        assertTrue(message("preset.help.runtime").contains("rejects"))
        assertTrue(message("root.toggle.certain.failures").contains("Claude's"))
    }
}
