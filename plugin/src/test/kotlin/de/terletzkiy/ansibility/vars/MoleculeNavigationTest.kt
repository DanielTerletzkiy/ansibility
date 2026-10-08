package de.terletzkiy.ansibility.vars

import com.intellij.navigation.NavigationItem
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.psi.PsiElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.refactoring.rename.PsiElementRenameHandler
import com.intellij.refactoring.rename.RenameHandlerRegistry
import com.intellij.util.CommonProcessors
import com.intellij.util.indexing.FindSymbolParameters
import de.terletzkiy.ansibility.api.AnsibleSite
import de.terletzkiy.ansibility.api.SiteClassifier
import de.terletzkiy.ansibility.context.MoleculeNavigationFixture
import de.terletzkiy.ansibility.context.MoleculeNavigationFixture.CONVERGE
import de.terletzkiy.ansibility.context.MoleculeNavigationFixture.DEFAULTS
import de.terletzkiy.ansibility.context.MoleculeNavigationFixture.GROUP_VARS
import de.terletzkiy.ansibility.context.MoleculeNavigationFixture.MOLECULE
import de.terletzkiy.ansibility.context.MoleculeNavigationFixture.TASKS
import de.terletzkiy.ansibility.context.MoleculeNavigationFixture.VERIFY
import de.terletzkiy.ansibility.context.MoleculeView
import de.terletzkiy.ansibility.refactoring.VarRenameHandler
import de.terletzkiy.ansibility.settings.SettingsTestSupport
import de.terletzkiy.ansibility.vars.registered.RegisteredSites
import de.terletzkiy.ansibility.vars.usages.UsagesTestCase
import de.terletzkiy.ansibility.vars.usages.VarScope
import de.terletzkiy.ansibility.workspace.crossroot.CrossRootVars
import de.terletzkiy.ansibility.workspace.search.AnsibleSymbolContributor
import de.terletzkiy.ansibility.workspace.search.AnsibleSymbolItem

/**
 * Plan amendment R20, D153–D155: "Show Molecule in navigation and search" (off by default) hides Molecule content from
 * requests that start outside a `molecule/` folder; requests from a Molecule file see everything; rename always edits
 * Molecule occurrences. Every feature is checked from a production origin with the setting off and on, and from a
 * Molecule origin with it off ([MoleculeNavigationFixture]).
 */
class MoleculeNavigationTest : UsagesTestCase() {
    override fun setUp() {
        super.setUp()
        MoleculeNavigationFixture.create { path, text -> myFixture.tempDirFixture.createFile(path, text) }
        refreshRoots()
        settle()
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

    private fun show(show: Boolean) = MoleculeNavigationFixture.showInNavigation(project, show)

    /** Ctrl+B targets of the reference starting with [marker] (`{{ name`) on [line] of [path], as `path:line`. */
    private fun targets(path: String, line: Int, marker: String): List<String> =
        gotoTargets(path, offsetAt(path, line, marker, 3)).map(::where)

    /** `path:line` of any navigation target. */
    private fun where(element: PsiElement): String = (element as? VarTargetElement)?.let(::describe) ?: runReadActionBlocking {
        val file = element.containingFile.viewProvider.virtualFile
        val base = myFixture.tempDirFixture.getFile("")!!
        "${VfsUtilCore.getRelativePath(file, base)}:${StringUtil.offsetToLineNumber(VfsUtilCore.loadText(file), element.textOffset) + 1}"
    }

    // ------------------------------------------------------------------------------------------------ Ctrl+B

    /** Rule 6 used to offer the converge play var first; filtered before the rules, rule 8 finds the production key. */
    fun testCtrlBFromProductionSkipsTheConvergePlayVarAndFindsTheProductionDefinition() {
        assertEquals("off (default)", listOf("$GROUP_VARS:2"), targets(TASKS, 4, "{{ shared_value"))
        show(true)
        assertEquals("on: today's targets, the converge play var first", listOf("$CONVERGE:5", "$GROUP_VARS:2"), targets(TASKS, 4, "{{ shared_value"))
    }

    fun testCtrlBFromProductionOffersNoMoleculeOnlyDefinition() {
        assertEquals("off", emptyList<String>(), targets(TASKS, 4, "{{ only_in_tests"))
        show(true)
        assertEquals("on", setOf("$CONVERGE:6", "$MOLECULE:14"), targets(TASKS, 4, "{{ only_in_tests").toSet())
    }

    fun testCtrlBFromAMoleculeFileSeesEverythingWithTheSettingOff() {
        assertEquals(listOf("$CONVERGE:5", "$GROUP_VARS:2"), targets(CONVERGE, 15, "{{ shared_value"))
    }

    /** VarRanking: only the converge play applies `db` together with `web`, so `db` ranks first only when it counts. */
    fun testRolesOfConvergePlaysRankOnlyWhenMoleculeIsShown() {
        val alpha = "${MoleculeNavigationFixture.ALPHA_DEFAULTS}:2"
        val db = "${MoleculeNavigationFixture.DB_DEFAULTS}:2"
        assertEquals("off: by name", listOf(alpha, db), targets(TASKS, 4, "{{ shared_port"))
        show(true)
        assertEquals("on: the role of the same (converge) play first", listOf(db, alpha), targets(TASKS, 4, "{{ shared_port"))
    }

    /** Handlers.playScope and the root tier: a handler only a converge play defines. */
    fun testNotifyReachesAConvergeHandlerOnlyWhenMoleculeIsShown() {
        val offset = offsetAt(TASKS, 5, "restart web", 1)
        assertEquals("off", emptyList<String>(), gotoTargets(TASKS, offset).map(::where))
        show(true)
        assertEquals("on", listOf("$CONVERGE:9"), gotoTargets(TASKS, offset).map(::where))
    }

    // ------------------------------------------------------------------------------------------------ Find Usages

    fun testFindUsagesFromProductionLeavesMoleculeOut() {
        val production = listOf("$DEFAULTS:2:web_port W", "$TASKS:4:web_port R")
        at(DEFAULTS, 2, "web_port")
        assertEquals("off, from defaults", production, describeUsages(findUsagesViaAction()))
        at(TASKS, 4, "web_port")
        assertEquals("off, from the tasks", production, describeUsages(findUsagesViaAction()))
        at(CONVERGE, 15, "web_port")
        assertEquals("off, from converge.yml: everything", ALL_WEB_PORT, describeUsages(findUsagesViaAction()))
        show(true)
        at(DEFAULTS, 2, "web_port")
        assertEquals("on, from defaults", ALL_WEB_PORT, describeUsages(findUsagesViaAction()))
    }

    /** The view is part of the target: the platform never reuses a search from converge.yml for one from the tasks. */
    fun testTheMoleculeViewIsPartOfTheUsageTarget() {
        at(TASKS, 4, "web_port")
        val production = targetAtCaret()!!
        at(DEFAULTS, 2, "web_port")
        val defaults = targetAtCaret()!!
        at(CONVERGE, 15, "web_port")
        val molecule = targetAtCaret()!!
        assertEquals(MoleculeView.EXCLUDE, (production.scope as VarScope.Root).view)
        assertEquals(MoleculeView.INCLUDE, (molecule.scope as VarScope.Root).view)
        assertEquals("same name, root and view", defaults, production)
        assertFalse("a different view is a different target", production == molecule)
        show(true)
        at(TASKS, 4, "web_port")
        assertEquals("with the setting on both see everything", molecule, targetAtCaret())
    }

    // ------------------------------------------------------------------------------------------------ rename (D155)

    fun testRenameFromProductionStillEditsMoleculeOccurrences() {
        myFixture.configureFromExistingVirtualFile(vf(DEFAULTS))
        myFixture.editor.caretModel.moveToOffset(offsetAt(DEFAULTS, 2, "web_port", 1))
        val context = SimpleDataContext.builder()
            .add(CommonDataKeys.PROJECT, project)
            .add(CommonDataKeys.EDITOR, myFixture.editor)
            .add(CommonDataKeys.PSI_FILE, myFixture.file)
            .add(PsiElementRenameHandler.DEFAULT_NAME, "http_port")
            .build()
        val handler = RenameHandlerRegistry.getInstance().getRenameHandler(context)
        assertTrue("the variable handler: $handler", handler is VarRenameHandler)
        handler!!.invoke(project, myFixture.editor, myFixture.file, context)
        for ((path, part) in listOf(DEFAULTS to "http_port: 80", TASKS to "{{ http_port }}", MOLECULE to "http_port: 8080", CONVERGE to "{{ http_port }}", VERIFY to "that: http_port == 8080")) {
            val text = FileDocumentManager.getInstance().getDocument(vf(path))!!.text
            assertTrue("'$part' in $path:\n$text", part in text)
        }
    }

    // ------------------------------------------------------------------------------------------------ cards

    private fun card(path: String, line: Int, marker: String): String = text(html(hover(path, offsetAt(path, line, marker, 3))))

    fun testCardOutsideMoleculeHidesMoleculeDefinitionsAndCountsTheHiddenUses() {
        val off = card(TASKS, 4, "{{ shared_value")
        assertFalse("Set in without converge.yml: $off", "converge.yml" in section(off, "Set in", "Used in"))
        assertTrue("Set in keeps group_vars: $off", "group_vars/all.yml" in section(off, "Set in", "Used in"))
        assertTrue("Used in counts production and says what it hid: $off", section(off, "Used in").trim().startsWith("1 task · 1 use in Molecule files (hidden) — Show usages"))
        show(true)
        val on = card(TASKS, 4, "{{ shared_value")
        assertTrue("Set in with converge.yml: $on", "converge.yml" in section(on, "Set in", "Used in"))
        assertTrue("Used in counts both, hides nothing: $on", section(on, "Used in").trim().startsWith("2 tasks — Show usages"))
    }

    fun testCardInsideMoleculeShowsEverythingWithTheSettingOff() {
        val card = card(CONVERGE, 15, "{{ shared_value")
        assertTrue(card, "group_vars/all.yml" in section(card, "Set in", "Used in"))
        assertTrue(card, section(card, "Used in").trim().startsWith("2 tasks — Show usages"))
    }

    /** Hidden is not undefined: a name only Molecule files set gets its own note, not "defined nowhere". */
    fun testCardSaysWhenOnlyMoleculeFilesSetTheVariable() {
        val off = card(TASKS, 4, "{{ only_in_tests")
        assertTrue(off, "only Molecule files of m set it" in off)
        assertTrue(off, "Only Molecule files of m define or set it; they are hidden here." in off)
        assertFalse(off, "Not defined, declared or set anywhere" in off)
        show(true)
        val on = card(TASKS, 4, "{{ only_in_tests")
        assertFalse(on, "only Molecule files of m set it" in on)
    }

    /** The "Used in" row of a variable only Molecule files read: no "no uses in m" next to the hidden count. */
    fun testUsedInSaysWhenOnlyMoleculeFilesReadTheVariable() {
        createFile("${MoleculeNavigationFixture.ROOT}/environments/prod/group_vars/web.yml", "---\nweb_flag: 1\n")
        createFile(
            "${MoleculeNavigationFixture.ROOT}/roles/web/molecule/default/side_effect.yml",
            "---\n- name: Side effect\n  hosts: all\n  tasks:\n    - name: Read\n      ansible.builtin.debug:\n        msg: \"{{ web_flag }} {{ web_flag }}\"\n",
        )
        settle()
        val path = "${MoleculeNavigationFixture.ROOT}/environments/prod/group_vars/web.yml"
        val off = text(html(hover(path, offsetAt(path, 2, "web_flag", 1))))
        assertTrue(off, section(off, "Used in").trim().startsWith("only in Molecule files (2 uses, hidden) — Show usages"))
        show(true)
        val on = text(html(hover(path, offsetAt(path, 2, "web_flag", 1))))
        assertTrue("on: every read counts, as for tasks: $on", section(on, "Used in").trim().startsWith("2 tasks — Show usages"))
    }

    /** A production template rendered by a role task and by a verify task: only the role task's vars count off. */
    fun testTaskVarsOfAMoleculeRendererStayOffProductionTemplateCards() {
        val root = MoleculeNavigationFixture.ROOT
        createFile("$root/environments/prod/group_vars/web.yml", "---\nweb_mode: dev\n")
        createFile("$root/roles/web/templates/web.conf.j2", "mode {{ web_mode }}\n")
        createFile("$root/roles/web/tasks/conf.yml", "---\n- name: Conf\n  ansible.builtin.template:\n    src: web.conf.j2\n    dest: /etc/web.conf\n  vars:\n    web_mode: prod\n")
        createFile(
            "$root/roles/web/molecule/default/verify_conf.yml",
            "---\n- name: Verify conf\n  hosts: all\n  tasks:\n    - name: Expected\n      ansible.builtin.template:\n        src: web.conf.j2\n        dest: /tmp/expected\n      vars:\n        web_test_banner: x\n",
        )
        settle()
        val template = "$root/roles/web/templates/web.conf.j2"
        val off = card(template, 1, "{{ web_mode")
        assertTrue("off: its only production render sets it: $off", "= prod · roles/web/tasks/conf.yml:7 · L15 block/task vars" in off)
        assertFalse(off, "some renders set it in their task vars" in off)
        show(true)
        val on = card(template, 1, "{{ web_mode")
        assertTrue("on: the verify render sets none: $on", "some renders set it in their task vars (L15): roles/web/tasks/conf.yml:7" in on)
    }

    /** RefResolver.playbookDirs: `db` runs only in the converge play, so its relative task file resolves from there. */
    fun testRelativeFilesOfARoleResolveFromConvergeOnlyWhenMoleculeIsShown() {
        val root = MoleculeNavigationFixture.ROOT
        createFile("$root/roles/db/tasks/setup.yml", "---\n- name: Setup\n  ansible.builtin.include_tasks: db_setup.yml\n")
        createFile("$root/roles/web/molecule/default/db_setup.yml", "---\n- name: Prepared\n  ansible.builtin.debug: {}\n")
        settle()
        val setup = "$root/roles/db/tasks/setup.yml"
        val offset = offsetAt(setup, 3, "db_setup.yml", 2)
        assertEquals("off", emptyList<String>(), gotoTargets(setup, offset).map(::where))
        show(true)
        assertEquals("on", listOf("$root/roles/web/molecule/default/db_setup.yml:1"), gotoTargets(setup, offset).map(::where))
    }

    /** RegisteredSites: the member `db_result.stdout` is documented only through the converge play. */
    fun testRegisteredMemberThroughAConvergePlayOnlyWhenMoleculeIsShown() {
        val path = "${MoleculeNavigationFixture.ROOT}/roles/web/tasks/member.yml"
        createFile(path, "---\n- name: Member\n  ansible.builtin.debug:\n    msg: \"{{ db_result.stdout }}\"\n")
        settle()
        fun member(): String? = runReadActionBlocking {
            val psi = psi(path)
            val offset = offsetAt(path, 4, "stdout", 1)
            val site = SiteClassifier.EP_NAME.extensionList.firstNotNullOfOrNull { it.classify(psi, offset) } as? AnsibleSite.VarRef
            site?.let { RegisteredSites.memberAt(psi, it) }?.display
        }
        assertNull("off", member())
        show(true)
        assertEquals("on", "db_result.stdout", member())
    }

    /** RegisterVisibility: `db_result` reaches web's tasks only through the converge play (roles web and db). */
    fun testRegisteredResultThroughAConvergePlayOnlyWhenMoleculeIsShown() {
        // The registered card's row title (`section.result.of`).
        val resultOf = "Result of"
        assertFalse("off", resultOf in card(TASKS, 8, "{{ db_result"))
        show(true)
        assertTrue("on", resultOf in card(TASKS, 8, "{{ db_result"))
    }

    // ------------------------------------------------------------------------------------------------ no origin

    fun testSearchEverywhereFollowsTheSetting() {
        assertFalse("off: no Molecule-only name", "only_in_tests" in symbolNames())
        assertEquals("off: production items only", listOf(GROUP_VARS), symbolFiles("shared_value"))
        show(true)
        assertTrue("on", "only_in_tests" in symbolNames())
        assertEquals("on", listOf(CONVERGE, GROUP_VARS).sorted(), symbolFiles("shared_value"))
    }

    /** D154: the action started in a Molecule file lists Molecule definitions whatever the setting. */
    fun testVariableInAllReposFromAMoleculeFileListsEverything() {
        assertEquals("off, from converge.yml", listOf(CONVERGE, MOLECULE).sorted(), reportFiles("only_in_tests", vf(CONVERGE)))
        assertEquals("off, from the role's tasks", emptyList<String>(), reportFiles("only_in_tests", vf(TASKS)))
    }

    fun testVariableInAllReposFollowsTheSetting() {
        assertEquals("off", listOf(GROUP_VARS), reportFiles("shared_value"))
        show(true)
        assertEquals("on", listOf(CONVERGE, GROUP_VARS).sorted(), reportFiles("shared_value"))
    }

    private fun symbolNames(): Set<String> {
        val names = CommonProcessors.CollectProcessor<String>()
        runReadActionBlocking { AnsibleSymbolContributor().processNames(names, GlobalSearchScope.allScope(project), null) }
        return names.results.toSet()
    }

    private fun symbolFiles(name: String): List<String> {
        val items = CommonProcessors.CollectProcessor<NavigationItem>()
        runReadActionBlocking { AnsibleSymbolContributor().processElementsWithName(name, items, FindSymbolParameters.simple(project, true)) }
        return items.results.filterIsInstance<AnsibleSymbolItem>().map { relative(it.location.file) }.sorted()
    }

    private fun reportFiles(name: String, origin: com.intellij.openapi.vfs.VirtualFile? = null): List<String> =
        runReadActionBlocking { CrossRootVars.report(project, name, null, origin) }.groups.flatMap { it.definitions }.map { relative(it.definition.location.file) }.sorted()

    private fun relative(file: com.intellij.openapi.vfs.VirtualFile): String = VfsUtilCore.getRelativePath(file, myFixture.tempDirFixture.getFile("")!!)!!

    private companion object {
        val ALL_WEB_PORT = listOf(
            "$DEFAULTS:2:web_port W",
            "$CONVERGE:15:web_port R",
            "$MOLECULE:13:web_port W",
            "$VERIFY:7:web_port R",
            "$TASKS:4:web_port R",
        ).sorted()
    }
}
