package de.terletzkiy.ansibility.resolve.include

import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.psi.util.PsiModificationTracker
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.RoleRegistry
import de.terletzkiy.ansibility.context.MoleculeView
import de.terletzkiy.ansibility.fixtures.RequiresInfraFixture
import de.terletzkiy.ansibility.inspections.undefined.UndefinedTestCase
import de.terletzkiy.ansibility.model.task.IncludeKind
import de.terletzkiy.ansibility.resolve.register.RoleTaskOrder
import de.terletzkiy.ansibility.semantics.yaml.YMap
import de.terletzkiy.ansibility.semantics.yaml.YScalar
import de.terletzkiy.ansibility.vars.VarLocations
import org.jetbrains.yaml.YAMLLanguage

/** [IncludeBindings]: who includes a task file, what each includer gives it, include paths, coverage and the cache. */
@RequiresInfraFixture
class IncludeBindingsTest : UndefinedTestCase() {
    override fun addFixtureFiles() {
        IncludeFixture.FILES.forEach { (path, text) -> add(path, text) }
        add(
            "$FALCON/roles/fw_cycle/tasks/main.yml",
            """
            ---
            - name: Into a
              ansible.builtin.include_tasks: a.yml
            """,
        )
        add("$FALCON/roles/fw_cycle/tasks/a.yml", "---\n- name: Into b\n  ansible.builtin.include_tasks: b.yml\n  vars:\n    fwc_a: 1\n")
        add("$FALCON/roles/fw_cycle/tasks/b.yml", "---\n- name: Back into a\n  ansible.builtin.include_tasks: a.yml\n")
        // more include paths than are followed: the first MAX_PATHS give the name, the last one does not
        val includes = (1..IncludeBindings.MAX_PATHS).joinToString("") { "- name: Include $it\n  ansible.builtin.include_tasks: part.yml\n  vars:\n    fwn_x: $it\n" }
        add("$FALCON/roles/fw_many/tasks/main.yml", "---\n$includes- name: Include plainly\n  ansible.builtin.include_tasks: part.yml\n")
        add("$FALCON/roles/fw_many/tasks/part.yml", "---\n- name: Use it\n  ansible.builtin.debug:\n    msg: \"{{ fwn_x }}\"\n")
    }

    private fun at(includer: Includer): String = "${includer.file.name}:${VarLocations.line(includer.file, includer.task.range.startOffset)}"

    private fun <T> read(action: () -> T): T = runReadActionBlocking(action)

    fun testTheLoopingIncludeAndItsOwnIncluderNearestFirst() = read {
        val includers = IncludeBindings.includers(project, vf(IncludeFixture.LOOP_RULESET), MoleculeView.EXCLUDE)
        assertEquals(listOf("rules.yml:8", "main.yml:2"), includers.map(::at))
        val looping = includers.first()
        assertEquals(IncludeKind.INCLUDE, looping.kind)
        assertEquals(listOf("fwl_ruleset"), looping.loopNames)
        assertTrue("fwl_ruleset" in IncludeBindings.names(project, vf(IncludeFixture.LOOP_RULESET), MoleculeView.EXCLUDE))
        assertEquals(listOf("rules.yml:8"), IncludeBindings.loopBindings(project, vf(IncludeFixture.LOOP_RULESET), "fwl_ruleset", MoleculeView.EXCLUDE).map(::at))
        val paths = IncludeBindings.paths(project, vf(IncludeFixture.LOOP_RULESET), MoleculeView.EXCLUDE)
        assertEquals(listOf(listOf("rules.yml:8", "main.yml:2")), paths.map { path -> path.includers.map(::at) })
    }

    fun testEachIncludeWithVarsIsAPathAndEveryPathCoversTheName() = read {
        val file = vf(IncludeFixture.VARS_RULESET)
        val paths = IncludeBindings.paths(project, file, MoleculeView.EXCLUDE)
        assertEquals(listOf("rules.yml:2", "rules.yml:10"), paths.map { at(it.nearest!!) })
        val ruleset = paths[1].vars().getValue("fwv_ruleset").value as YMap
        assertEquals("ipv6", (ruleset.entries.single { it.key.text == "ip_version" }.value as YScalar).text)
        val coverage = IncludeBindings.coverage(project, file, "fwv_ruleset", MoleculeView.EXCLUDE)
        assertTrue(coverage.everywhere)
        assertFalse(coverage.byLoop("fwv_ruleset"))
        assertEquals(2, coverage.providers.size)
    }

    fun testIncludeRoleWithTasksFromIncludesItsEntryFile() = read {
        val direct = IncludeBindings.directIncluders(project, vf(IncludeFixture.ENTRY_APPLY), MoleculeView.EXCLUDE)
        val includer = direct.single()
        assertTrue(includer.role)
        assertEquals("playbook-fw.yml", includer.file.name)
        assertEquals(listOf("fwe_set"), includer.loopNames)
        assertEquals(setOf("fwe_mode"), includer.vars.keys)
        assertEmpty("main.yml is not the entry point it names", IncludeBindings.directIncluders(project, vf("${IncludeFixture.ENTRY}/tasks/main.yml"), MoleculeView.EXCLUDE))
    }

    fun testOnlySomePathsCoverAName() = read {
        val coverage = IncludeBindings.coverage(project, vf(IncludeFixture.TWO_PART), "fwt_mode", MoleculeView.EXCLUDE)
        assertTrue(coverage.provided)
        assertFalse(coverage.everywhere)
        assertEquals(listOf("main.yml:2"), coverage.providers.map(::at))
        assertEquals(listOf("main.yml:7"), coverage.missing.map(::at))
    }

    fun testMoleculeIncludersCountOnlyInAViewThatIncludesMolecule() = read {
        val file = vf(IncludeFixture.MOL_APPLY)
        assertEquals(listOf("main.yml:2"), IncludeBindings.directIncluders(project, file, MoleculeView.EXCLUDE).map(::at))
        assertFalse("fwm_flag" in IncludeBindings.names(project, file, MoleculeView.EXCLUDE))
        assertEquals(listOf("main.yml:2", "converge.yml:5"), IncludeBindings.directIncluders(project, file, MoleculeView.INCLUDE).map(::at))
        // A play's include_role passes role params the witness evaluates per play, so ANS-V003's names leave them out;
        // the include still gives them to the file.
        assertTrue(IncludeBindings.coverage(project, file, "fwm_flag", MoleculeView.INCLUDE).provided)
        assertFalse(IncludeBindings.coverage(project, file, "fwm_flag", MoleculeView.EXCLUDE).provided)
    }

    fun testImportsGiveTheirVarsButNoLoopNames() = read {
        assertTrue("fwi_mode" in IncludeBindings.names(project, vf(IncludeFixture.IMP_PART), MoleculeView.EXCLUDE))
        val looped = IncludeBindings.directIncluders(project, vf(IncludeFixture.IMP_LOOPED), MoleculeView.EXCLUDE).single()
        assertEquals(IncludeKind.IMPORT, looped.kind)
        assertFalse(looped.loops)
        assertEmpty(looped.loopNames)
        assertFalse("fwi_each" in IncludeBindings.names(project, vf(IncludeFixture.IMP_LOOPED), MoleculeView.EXCLUDE))
    }

    fun testIncludeCyclesEndTheirPaths() = read {
        val b = vf("$FALCON/roles/fw_cycle/tasks/b.yml")
        val paths = IncludeBindings.paths(project, b, MoleculeView.EXCLUDE)
        assertEquals(listOf(listOf("a.yml:2", "main.yml:2"), listOf("a.yml:2", "b.yml:2")), paths.map { path -> path.includers.map(::at) })
        assertTrue("fwc_a" in IncludeBindings.names(project, b, MoleculeView.EXCLUDE))
    }

    /**
     * A role entry file a play applies directly (`roles:`) that an `include_role` with `vars:` also runs: a direct path
     * besides the include's, so the include's vars are not "everywhere"; ANS-V003's names leave them to the witness.
     */
    fun testADirectlyAppliedEntryFileHasADirectPathBesideItsInclude() = read {
        val file = vf(IncludeFixture.DIR_MAIN)
        val paths = IncludeBindings.paths(project, file, MoleculeView.EXCLUDE)
        assertEquals(listOf(emptyList(), listOf("playbook-fw-direct.yml:12")), paths.map { path -> path.includers.map(::at) })
        assertTrue(paths.first().direct)
        val coverage = IncludeBindings.coverage(project, file, "fwd_mode", MoleculeView.EXCLUDE)
        assertTrue(coverage.provided)
        assertTrue(coverage.direct)
        assertFalse("the direct run does not get it", coverage.everywhere)
        assertFalse("the witness judges the direct run's hosts", "fwd_mode" in IncludeBindings.names(project, file, MoleculeView.EXCLUDE))
        assertTrue(IncludeGraph.getInstance(project).directlyApplied(file, MoleculeView.EXCLUDE))
        assertFalse("rules.yml is no entry point a play applies", IncludeGraph.getInstance(project).directlyApplied(vf(IncludeFixture.LOOP_RULES), MoleculeView.EXCLUDE))
    }

    /** A play's `include_role` passes its own `vars:` as role params, which the witness evaluates per play: no V003 name. */
    fun testAPlaysIncludeRoleVarsAreLeftToTheWitness() = read {
        val file = vf(IncludeFixture.ENTRY_APPLY)
        assertFalse("fwe_mode" in IncludeBindings.names(project, file, MoleculeView.EXCLUDE))
        assertTrue("its loop variable still counts", "fwe_set" in IncludeBindings.names(project, file, MoleculeView.EXCLUDE))
        assertTrue("the card still sees the include give it", IncludeBindings.coverage(project, file, "fwe_mode", MoleculeView.EXCLUDE).everywhere)
    }

    /** An `include_role` whose `tasks_from` is templated names no file statically: it binds nothing in `main.yml`. */
    fun testATemplatedTasksFromIncludesNoFile() = read {
        val file = vf(IncludeFixture.TPL_MAIN)
        assertEmpty(IncludeBindings.directIncluders(project, file, MoleculeView.EXCLUDE))
        assertFalse("fwp_mode" in IncludeBindings.names(project, file, MoleculeView.EXCLUDE))
        assertFalse(IncludeBindings.coverage(project, file, "fwp_mode", MoleculeView.EXCLUDE).provided)
    }

    /** More include paths than [IncludeBindings.MAX_PATHS]: the ones not followed are unknown, so the name is not "everywhere". */
    fun testMorePathsThanFollowedAreNeverEverywhere() = read {
        val file = vf("$FALCON/roles/fw_many/tasks/part.yml")
        val found = IncludeBindings.pathsOf(project, file, MoleculeView.EXCLUDE)
        assertEquals(IncludeBindings.MAX_PATHS, found.paths.size)
        assertTrue(found.truncated)
        val coverage = IncludeBindings.coverage(project, file, "fwn_x", MoleculeView.EXCLUDE)
        assertTrue("every followed path gives it", coverage.missing.isEmpty())
        assertTrue(coverage.truncated)
        assertFalse(coverage.everywhere)
    }

    /** The include param of a dynamic include wins over an import's `vars:` on the same path. */
    fun testADynamicIncludeWinsOverTheTasksOwnVarsAndAnImport() = read {
        val path = IncludeBindings.paths(project, vf(IncludeFixture.PREC_PART), MoleculeView.EXCLUDE).single()
        assertEquals("main.yml:2", at(path.winnerOf("fwr_policy")!!))
        assertEquals(setOf("fwr_policy"), path.params().keys)
        assertEmpty(path.importVars().keys)
        val imported = IncludeBindings.paths(project, vf(IncludeFixture.IMP_PART), MoleculeView.EXCLUDE).single()
        assertEquals(setOf("fwi_mode"), imported.importVars().keys)
        assertEmpty(imported.params().keys)
    }

    fun testTheGraphIsCachedPerRoleUntilATaskFileChanges() {
        val file = vf(IncludeFixture.TWO_PART)
        val role = read { RoleRegistry.getInstance(project).roleOf(file)!! }
        // The graph is valid while YAML, the structure and the roots stay the same: measure between two equal stamps (a
        // background structure scan of the fixture may still bump the structure once).
        fun stamp(): Long = read {
            AnsibleWorkspace.getInstance(project).structureTracker.modificationCount +
                PsiModificationTracker.getInstance(project).forLanguage(YAMLLanguage.INSTANCE).modificationCount +
                ProjectRootManager.getInstance(project).modificationCount
        }
        var first = read { IncludeGraph.getInstance(project).taskIncludes(role) }
        var again = read { IncludeGraph.getInstance(project).taskIncludes(role) }
        for (attempt in 1..STAMP_TRIES) {
            val before = stamp()
            first = read { IncludeGraph.getInstance(project).taskIncludes(role) }
            again = read { IncludeGraph.getInstance(project).taskIncludes(role) }
            if (stamp() == before) break
        }
        assertSame("no change: the cached role graph", first, again)
        assertEquals(2, first.getValue(file).size)
        assertEquals(2, read { RoleTaskOrder(project, role).enclosingIncludes(file) }.size)

        replace(IncludeFixture.TWO_MAIN, "- name: Include plainly\n  ansible.builtin.include_tasks: part.yml\n", "")
        val after = read { IncludeGraph.getInstance(project).includersOf(file) }
        assertEquals(1, after.size)
        assertNotSame(first, read { IncludeGraph.getInstance(project).taskIncludes(RoleRegistry.getInstance(project).roleOf(file)!!) })
        val root = read { AnsibleWorkspace.getInstance(project).contextOf(file)!!.root }
        assertEquals(1, read { RoleTaskOrder(project, RoleRegistry.getInstance(project).role(root, "fw_two")!!).enclosingIncludes(file) }.size)
    }

    private companion object {
        const val STAMP_TRIES = 5
    }
}
