package de.terletzkiy.ansibility.model.drift

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.terletzkiy.ansibility.fixtures.InfraTestData
import de.terletzkiy.ansibility.model.role.ModelFixture

/**
 * Role drift on the sanitised infra fixture (golden plus the falcon, heron, platform, wren and pelican danger-zone roles it
 * carries). The fixture holds whole role directories for golden and falcon, so golden vs falcon reproduces the real repo's
 * differences for those roles (both sides went through the same sanitiser); the expected lists were measured on the
 * fixture itself with the plugin's role rule and skip list.
 */
class RoleDriftInfraFixtureTest : BasePlatformTestCase() {
    private lateinit var service: RoleDriftService
    private lateinit var drifts: Map<String, RoleDrift>

    override fun getTestDataPath(): String = ModelFixture.testDataPath

    override fun setUp() {
        super.setUp()
        myFixture.copyDirectoryToProject(InfraTestData.INFRA, "")
        ModelFixture.rescan(project)
        service = DriftFixture.freshService(project, testRootDisposable)
        drifts = DriftFixture.await { service.driftAll() }.associateBy { it.name }
    }

    private fun repoCopy(name: String, team: String): CopyDrift =
        drifts.getValue(name).copies.single { it.copy.root.displayName == team }

    private fun tiers(name: String): Map<String, DriftTier> = drifts.getValue(name).copies.associate { it.copy.root.displayName to it.tier }

    fun testEveryNameIsComputed() {
        assertEquals(38, drifts.size)
        assertEquals(47, drifts.values.sumOf { it.copies.size })
        val repoCopies = drifts.values.filter { it.reference != null }.flatMap { d -> d.copies.filter { !it.copy.isReference } }
        assertEquals("8 falcon copies (not jenkins-agent-docker) and heron keycloak have a golden copy", 9, repoCopies.size)
        assertEquals(
            mapOf(DriftTier.IDENTICAL to 4, DriftTier.MOLECULE_ONLY to 1, DriftTier.SPEC_DEFAULTS to 2, DriftTier.BEHAVIOUR to 2),
            repoCopies.groupingBy { it.tier }.eachCount(),
        )
    }

    fun testHaproxyDiffersOnlyInTheMoleculeCheck() {
        assertEquals(mapOf("golden" to DriftTier.REFERENCE, "falcon" to DriftTier.MOLECULE_ONLY), tiers("haproxy"))
        val falcon = repoCopy("haproxy", "falcon")
        assertEquals("verify.yml:150 checks stat.isreg where golden checks stat.isfile", listOf("molecule/default/verify.yml"), falcon.paths.changed)
        assertEquals(1, falcon.paths.size)
        assertEquals(2, drifts.getValue("haproxy").variants.size)
        assertEquals(20, drifts.getValue("haproxy").copies.first().fileCount)
    }

    fun testSpecDriftOfKeycloakAndSystem() {
        assertEquals(mapOf("golden" to DriftTier.REFERENCE, "heron" to DriftTier.SPEC_DEFAULTS), tiers("keycloak"))
        assertEquals("golden declares optional_client_scopes", listOf("meta/argument_specs.yml"), repoCopy("keycloak", "heron").paths.changed)
        val system = repoCopy("system", "falcon")
        assertEquals(DriftTier.SPEC_DEFAULTS, system.tier)
        assertEquals("the additional_software drift plus the molecule check", listOf("meta/argument_specs.yml", "molecule/default/verify.yml"), system.paths.changed)
    }

    fun testIdenticalAndBehaviourCopies() {
        for (name in listOf("docker", "loki", "nginx", "totp-token")) {
            assertEquals(name, DriftTier.IDENTICAL, repoCopy(name, "falcon").tier)
            assertTrue(name, drifts.getValue(name).identicalEverywhere)
        }
        val grafana = repoCopy("grafana", "falcon")
        assertEquals(DriftTier.BEHAVIOUR, grafana.tier)
        assertEquals(11, grafana.paths.changed.size)
        assertTrue(grafana.paths.changed.contains("templates/config/grafana.ini.j2"))
        assertEquals(
            listOf(
                "files/dashboards/frankenphp-process-health.json", "files/dashboards/frankenphp-workers.json",
                "files/dashboards/outgoing-http-calls.json",
                "molecule/default/files/grafana/alerting/slack-monolith-exceptions-receiver-color.j2",
                "molecule/default/files/grafana/alerting/slack-monolith-exceptions-receiver-text.j2",
                "molecule/default/files/grafana/alerting/slack-monolith-exceptions-receiver-title.j2",
            ),
            grafana.paths.onlyHere,
        )
        val postfix = repoCopy("postfix", "falcon")
        assertEquals(DriftTier.BEHAVIOUR, postfix.tier)
        assertEquals(9, postfix.paths.changed.size)
        assertEquals("falcon has 12 of golden's 24 files", 12, postfix.paths.onlyInReference.size)
        assertEquals(24, drifts.getValue("postfix").copies.first().fileCount)
        assertTrue(postfix.paths.onlyInReference.contains("tasks/inbound-email-processing.yml"))
    }

    fun testCopiesWithoutGolden() {
        for (name in listOf("jenkins-agent-docker", "app-wren-mono", "puppet-migration", "clone-percona-to-primary", "xtrabackup-percona-to-replica")) {
            val drift = drifts.getValue(name)
            assertNull(name, drift.reference)
            assertEquals(name, listOf(DriftTier.NO_REFERENCE), drift.copies.map { it.tier })
            assertEquals(name, "${drift.copies.single().copy.root.displayName} only", DriftTexts.badge(drift, drift.copies.single()))
        }
        assertEquals("pelican › danger_zone/database", drifts.getValue("clone-percona-to-primary").copies.single().copy.root.displayName)
    }

    fun testTheDetachedWorktreeTakesNoPart() {
        val haproxy = drifts.getValue("haproxy")
        assertFalse(haproxy.copies.any { it.copy.dir.path.contains(".claude/worktrees") })
        assertEquals(2, haproxy.copies.size)
    }
}
