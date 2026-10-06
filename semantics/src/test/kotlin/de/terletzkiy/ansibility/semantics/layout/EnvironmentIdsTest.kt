package de.terletzkiy.ansibility.semantics.layout

import de.terletzkiy.ansibility.semantics.layout.EnvironmentIds.Candidate
import de.terletzkiy.ansibility.semantics.layout.EnvironmentIds.EnvironmentId
import de.terletzkiy.ansibility.semantics.layout.EnvironmentIds.NameProblem
import de.terletzkiy.ansibility.semantics.layout.EnvironmentIds.Origin
import de.terletzkiy.ansibility.semantics.layout.EnvironmentIds.Source
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The identity table of plan amendment R10 (D48–D50, F10.5), every collision rule and the reserved prefix. */
class EnvironmentIdsTest {
    private fun file(path: String) = Source(path)
    private fun dir(path: String, vararg files: String) = Source(path, isDirectory = true, files = files.toList())
    private val rootDir = Source(".", isDirectory = true, name = "demo")

    private fun ids(vararg candidates: Candidate) = EnvironmentIds.assign(candidates.toList()).map { it.id }
    private fun ids(candidates: List<Candidate>) = EnvironmentIds.assign(candidates).map { it.id }
    private fun bare(id: String, label: String = id) = EnvironmentId(id, label, renamed = false)

    @Test
    fun `convention directories keep their names`() {
        val candidates = listOf(
            Candidate.convention("environments/prod", listOf(file("environments/prod/hosts.yml"))),
            Candidate.convention("environments/staging", listOf(file("environments/staging/hosts.yaml"))),
            Candidate.convention("inventories/qa"),
        )
        assertEquals(listOf(bare("prod"), bare("staging"), bare("qa")), EnvironmentIds.assign(candidates))
        assertTrue(candidates.all { it.origin == Origin.CONVENTION })
    }

    @Test
    fun `a cfg inventory is one environment named by its stems`() {
        assertEquals(listOf(bare("hosts")), EnvironmentIds.assign(EnvironmentIds.fromCfg(listOf(file("hosts.ini")), onePerFile = false)))
        assertEquals(
            listOf(bare("hosts+extra", "hosts + extra")),
            EnvironmentIds.assign(EnvironmentIds.fromCfg(listOf(file("hosts.ini"), dir("extra")), onePerFile = false)),
        )
        assertEquals(listOf("inventory"), ids(EnvironmentIds.fromCfg(listOf(dir("inventory")), onePerFile = false)))
    }

    @Test
    fun `stems that repeat inside a cfg list become paths`() {
        val repeated = EnvironmentIds.assign(EnvironmentIds.fromCfg(listOf(file("a/hosts.ini"), file("b/hosts.yml")), false))
        assertEquals(listOf(bare("a/hosts.ini+b/hosts.yml", "a/hosts.ini + b/hosts.yml")), repeated)
        assertEquals(
            listOf("base+a/hosts.ini+b/hosts.ini"),
            ids(EnvironmentIds.fromCfg(listOf(file("base.ini"), file("a/hosts.ini"), file("b/hosts.ini")), false)),
        )
    }

    @Test
    fun `the cfg directory as a source takes the root's name`() {
        assertEquals(listOf(bare("demo")), EnvironmentIds.assign(EnvironmentIds.fromCfg(listOf(rootDir), onePerFile = false)))
        assertEquals(
            listOf(bare("hosts+demo", "hosts + demo")),
            EnvironmentIds.assign(EnvironmentIds.fromCfg(listOf(file("hosts.ini"), rootDir), onePerFile = false)),
        )
    }

    @Test
    fun `D49 a cfg directory is merged unless one environment per file is chosen`() {
        val inventory = dir("inventory", "inventory/prod.yml", "inventory/staging.yml")
        val merged = EnvironmentIds.fromCfg(listOf(inventory), onePerFile = false)
        assertEquals(listOf("inventory"), ids(merged))
        assertEquals(listOf(listOf(inventory)), merged.map { it.sources })

        val perFile = EnvironmentIds.fromCfg(listOf(inventory), onePerFile = true)
        assertEquals(listOf("prod", "staging"), ids(perFile))
        assertEquals(listOf(listOf(file("inventory/prod.yml")), listOf(file("inventory/staging.yml"))), perFile.map { it.sources })
        assertTrue(perFile.all { it.origin == Origin.ANSIBLE_CFG })

        // The switch splits a single directory only: a list stays one environment, an empty directory too.
        assertEquals(listOf("hosts+inventory"), ids(EnvironmentIds.fromCfg(listOf(file("hosts.ini"), inventory), onePerFile = true)))
        assertEquals(listOf("inventory"), ids(EnvironmentIds.fromCfg(listOf(dir("inventory")), onePerFile = true)))
    }

    @Test
    fun `D50 detected inventories are one environment per file`() {
        // Layout B: inventory/prod.yml and inventory/staging.yml without a cfg.
        val b = EnvironmentIds.fromDetected(listOf(dir("inventory", "inventory/prod.yml", "inventory/staging.yml")))
        assertEquals(listOf("prod", "staging"), ids(b))
        assertTrue(b.all { it.origin == Origin.DETECTED })
        // Layout E: production.ini and staging.ini at the root.
        assertEquals(listOf("production", "staging"), ids(EnvironmentIds.fromDetected(listOf(file("production.ini"), file("staging.ini")))))
        // Layout D: one root file.
        assertEquals(listOf("inventory"), ids(EnvironmentIds.fromDetected(listOf(file("inventory.ini")))))
        assertEquals(listOf("hosts"), ids(EnvironmentIds.fromDetected(listOf(file("hosts")))))
        // A detected directory whose files the target core all skips stays one (empty) environment.
        assertEquals(listOf("inventory"), ids(EnvironmentIds.fromDetected(listOf(dir("inventory")))))
    }

    @Test
    fun `settings names are used as given`() {
        assertEquals(listOf(bare("all")), EnvironmentIds.assign(listOf(Candidate.settings("all", listOf(dir("inventory"))))))
        assertEquals(listOf("Prod EU", "prod"), ids(Candidate.settings("Prod EU", emptyList()), Candidate.settings("prod", emptyList())))
    }

    @Test
    fun `on a collision the strongest origin keeps the bare id and the others get their path`() {
        val detected = Candidate.detected(file("inventory/hosts.yml"))
        val cfg = Candidate.cfg(listOf(file("hosts.ini")))
        val convention = Candidate.convention("environments/hosts", listOf(file("environments/hosts/hosts.yml")))
        val settings = Candidate.settings("hosts", emptyList())

        assertEquals(
            listOf(EnvironmentId("inventory/hosts.yml", "inventory/hosts.yml", renamed = true), bare("hosts")),
            EnvironmentIds.assign(listOf(detected, cfg)),
        )
        assertEquals(listOf("hosts.ini", "hosts"), ids(cfg, convention), "convention ids never change")
        assertEquals(listOf("environments/hosts", "hosts"), ids(convention, settings), "settings names win")
        assertEquals(
            listOf("inventory/hosts.yml", "hosts.ini", "environments/hosts", "hosts"),
            ids(detected, cfg, convention, settings),
        )
    }

    @Test
    fun `equal origins keep the earlier candidate`() {
        assertEquals(
            listOf("prod", "inventories/prod"),
            ids(Candidate.convention("environments/prod"), Candidate.convention("inventories/prod")),
        )
        // fromDetected keeps the walk order, in which nested/prod.ini comes before prod.yml.
        assertEquals(
            listOf("prod", "inventory/prod.yml"),
            ids(EnvironmentIds.fromDetected(listOf(dir("inventory", "inventory/nested/prod.ini", "inventory/prod.yml")))),
        )
    }

    @Test
    fun `an id is unique even when a fallback is taken`() {
        val result = EnvironmentIds.assign(
            listOf(
                Candidate.settings("prod", emptyList()),
                Candidate.settings("prod", emptyList()),
                Candidate.settings("prod (2)", emptyList()),
                Candidate.detected(file("a/x.ini")),
                Candidate.detected(file("b/x.ini")),
                Candidate.settings("b/x.ini", emptyList()),
            ),
        )
        assertEquals(listOf("prod", "prod (3)", "prod (2)", "x", "b/x.ini (2)", "b/x.ini"), result.map { it.id })
        assertEquals("prod (3)", result[1].label)
        assertEquals(listOf(false, true, false, false, true, false), result.map { it.renamed })
        assertEquals(result.size, result.map { it.id }.toSet().size)
    }

    @Test
    fun `molecule ids are reserved`() {
        assertEquals(listOf("_molecule:x.ini"), ids(Candidate.detected(file("molecule:x.ini"))))
        assertEquals(listOf("inventory/molecule:x.yml"), ids(Candidate.detected(file("inventory/molecule:x.yml"))))
        assertEquals(listOf("_molecule:a"), ids(Candidate.settings("molecule:a", emptyList())))
        assertTrue(EnvironmentIds.isReserved("molecule:role/default"))
        assertFalse(EnvironmentIds.isReserved("Molecule:x"))
        val ids = ids(
            Candidate.convention("environments/prod"),
            Candidate.cfg(listOf(file("molecule:a.ini"), file("b.ini"))),
            Candidate.detected(file("hosts")),
        )
        assertTrue(ids.none(EnvironmentIds::isReserved), "$ids")
    }

    @Test
    fun `the same layout always gives the same ids`() {
        fun layout() = listOf(
            Candidate.convention("environments/prod"),
            Candidate.cfg(listOf(file("prod.ini"))),
        ) + EnvironmentIds.fromDetected(listOf(dir("inventory", "inventory/prod.yml", "inventory/staging.yml")))
        val first = EnvironmentIds.assign(layout())
        assertEquals(first, EnvironmentIds.assign(layout()))
        assertEquals(listOf("prod", "prod.ini", "inventory/prod.yml", "staging"), first.map { it.id })
    }

    @Test
    fun `stems and names`() {
        assertEquals("hosts", EnvironmentIds.stem("hosts.ini"))
        assertEquals("hosts", EnvironmentIds.stem("hosts"))
        assertEquals(".hosts", EnvironmentIds.stem(".hosts"))
        assertEquals("a.b", EnvironmentIds.stem("a.b.ini"))
        assertEquals("x.ini", EnvironmentIds.stem("x.ini.disabled"))
        assertEquals("web.example", EnvironmentIds.stem("web.example.test"))
        assertEquals("prod", EnvironmentIds.nameOf("inventory/prod.yml", isDirectory = false))
        assertEquals("group.vars", EnvironmentIds.nameOf("x/group.vars/", isDirectory = true))
        assertEquals("inventory", Source("inventory/", isDirectory = true).name)
    }

    @Test
    fun `settings name validation`() {
        assertEquals(
            mapOf(1 to NameProblem.EMPTY, 2 to NameProblem.RESERVED, 4 to NameProblem.DUPLICATE),
            EnvironmentIds.nameProblems(listOf("prod", " ", "molecule:x", "staging", "prod")),
        )
        assertEquals(emptyMap<Int, NameProblem>(), EnvironmentIds.nameProblems(listOf("prod", "Prod", "prod-eu")))
    }
}
