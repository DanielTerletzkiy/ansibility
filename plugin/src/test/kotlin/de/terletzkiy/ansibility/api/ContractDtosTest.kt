package de.terletzkiy.ansibility.api

import com.intellij.testFramework.LightVirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.terletzkiy.ansibility.semantics.diagnostics.DiagnosticCode
import de.terletzkiy.ansibility.semantics.diagnostics.Level
import de.terletzkiy.ansibility.semantics.diagnostics.Preset
import de.terletzkiy.ansibility.settings.FindingContext
import de.terletzkiy.ansibility.settings.RootContext
import de.terletzkiy.ansibility.settings.RootSettings
import de.terletzkiy.ansibility.settings.SeverityPolicy

/** The behaviour of the CT0 contract DTOs (plan amendment R7/R8, A.13 and A.14). */
class ContractDtosTest : BasePlatformTestCase() {
    private val dir = LightVirtualFile("ansible")
    private val file = LightVirtualFile("vars.yml")
    private val root = AnsibleRoot(dir, RootKind.PROJECT, detached = false, parentDir = null, rolesDirs = emptyList(), environmentsDir = null, displayName = "falcon")

    private fun host(env: String, name: String) = HostKey("repos/falcon/ansible", env, name)

    fun testMoleculeHostKeys() {
        assertEquals("molecule:haproxy/default", HostKey.moleculeEnvironment("haproxy", "default"))
        assertEquals("molecule:default", HostKey.moleculeEnvironment(null, "default"))
        assertTrue(host(HostKey.moleculeEnvironment("haproxy", "default"), "haproxy_deb12-local").isMolecule)
        assertFalse(host("prod", "prod-prod1").isMolecule)
        assertFalse("hosts of different roots differ", host("prod", "prod-prod1") == HostKey("repos/heron/ansible", "prod", "prod-prod1"))
    }

    fun testHostScopeListsDistinctHosts() {
        val play = PlayRef(file, 0, "System", "all", dir)
        val p1 = host("prod", "prod-prod1")
        val p2 = host("prod", "prod-prod2")
        val scope = HostScope(
            root, RootContext.DEFAULT, HostScopeOrigin.RoleReach("keepalived", listOf(play)),
            targets = listOf(EvalTarget(p1, play, dir), EvalTarget(p2, play, dir), EvalTarget(p1, null, null)),
            fileHosts = listOf(p1, p2), overriddenSelection = false, emptyReason = null,
        )
        assertEquals(listOf(p1, p2), scope.hosts)
    }

    fun testChainWinnerIsTheLastWinningStep() {
        fun ref(layer: VarsLayer, offset: Int) = VarSourceRef(file, offset, layer, "all", null, "x", isVault = false)
        val target = EvalTarget(host("prod", "prod-prod1"), null, dir)
        val chain = PrecedenceChain(
            "postfix_relayhost", target, runningRole = "postfix",
            steps = listOf(
                ChainStep(ref(VarsLayer.ROLE_DEFAULTS, 1), ChainOutcome.SHADOWED),
                ChainStep(ref(VarsLayer.INVENTORY_GROUP_VARS_ALL, 2), ChainOutcome.SHADOWED),
                ChainStep(ref(VarsLayer.PLAYBOOK_GROUP_VARS_ALL, 3), ChainOutcome.WINNER),
            ),
            runtimeMarkers = listOf(RuntimeMarker("postfix_relayhost", RuntimeMarkerKind.SET_FACT, SourceLocation(file, 9))),
            unknownSources = emptyList(),
        )
        assertEquals(3, chain.winner!!.source.offset)
        assertNull(chain.copy(steps = emptyList()).winner)
        assertEquals(VarsLayer.SET_FACT_REGISTER, RuntimeMarkerKind.SET_FACT.layer)
        assertEquals(VarsLayer.SET_FACT_REGISTER, RuntimeMarkerKind.REGISTER.layer)
        assertEquals(VarsLayer.INCLUDE_VARS, RuntimeMarkerKind.INCLUDE_VARS.layer)
    }

    fun testInventoryFactsLookups() {
        val p1 = host("prod", "prod-prod1")
        val t1 = host("test", "test-test1")
        val facts = InventoryFacts(
            listOf(
                EnvironmentFacts("prod", mapOf("all" to listOf("prod-prod1"), "keepalived" to listOf("prod-prod1")), listOf(HostFacts(p1, "192.0.2.10", null, listOf("keepalived"), emptyList()))),
                EnvironmentFacts("test", mapOf("all" to listOf("test-test1")), listOf(HostFacts(t1, "198.51.100.10", null, emptyList(), listOf(host("test", "preview-dev1"))))),
            ),
        )
        assertEquals("192.0.2.10", facts.host(p1)!!.address)
        assertEquals(1, facts.host(t1)!!.sharesAddressWith.size)
        assertNull(facts.host(host("ops", "ops-ops1")))
        assertNull(facts.environment("build"))
    }

    fun testExecutionSourceFieldsDefaultToNull() {
        val ref = VarSourceRef(file, 0, VarsLayer.INVENTORY_GROUP_VARS_ALL, "all", null, "x", isVault = false)
        assertNull("inventory layers belong to no role", ref.role)
        assertNull("inventory layers belong to no play", ref.play)
        val play = PlayRef(file, 0, null, "all", dir)
        val info = PlayInfo(play, SourceLocation(file, 0), listOf("a"), emptyList(), emptyList())
        assertNull(info.vars)
        assertEquals(play, ref.copy(role = "postfix", play = play).play)
    }

    fun testVaultHeaderAndLengths() {
        assertEquals("default", VaultHeaderInfo("1.1", "AES256", null).labelOrDefault())
        assertEquals("team", VaultHeaderInfo("1.1", "AES256", null).labelOrDefault("team"))
        assertEquals("dev", VaultHeaderInfo("1.2", "AES256", "dev").labelOrDefault("team"))
        assertEquals("\$ANSIBLE_VAULT", VaultHeaderInfo.MAGIC)

        assertEquals(0..15, VaultEnvelopeInfo.plaintextLengthOf(16))
        assertEquals(16..31, VaultEnvelopeInfo.plaintextLengthOf(32))
        assertNull("not a whole number of blocks", VaultEnvelopeInfo.plaintextLengthOf(33))
        assertNull(VaultEnvelopeInfo.plaintextLengthOf(0))
        assertNull(VaultEnvelopeInfo.plaintextLengthOf(null))
        val info = VaultEnvelopeInfo(VaultEnvelopeKind.INLINE, SourceLocation(file, 4), VaultHeaderInfo("1.1", "AES256", null), 48, "vault_x")
        assertEquals(32..47, info.plaintextLength)
    }

    fun testVaultIdMatchQuirk() {
        fun config(raw: String?) = VaultRootConfig(emptyList(), "default", raw, null)
        assertFalse(config(null).idMatch)
        assertFalse(config("").idMatch)
        assertTrue("any non-empty value turns matching on, false included", config("false").idMatch)
        assertTrue(config("0").idMatch)

        val source = VaultSecretSource(VaultSourceKind.PASSWORD_FILE, ".vault-pass", VaultSourceOrigin.ENV_LOCAL)
        val locked = VaultRootConfig(listOf(VaultIdentity("default", source, VaultLockState.LOCKED)), "default", null, null)
        assertFalse(locked.anyUnlocked)
        assertTrue(locked.copy(identities = listOf(VaultIdentity("default", source, VaultLockState.UNLOCKED))).anyUnlocked)
    }

    fun testDecryptedResultsNeverPrintTheirPlaintext() {
        val plaintext = object : VaultPlaintext {
            private val bytes = "ANSIBILITY-SENTINEL-01".toByteArray()
            override val size: Int get() = bytes.size

            override fun <T> read(block: (ByteArray) -> T): T = block(bytes)

            override fun close() = bytes.fill(0)

            override fun toString(): String = "***"
        }
        val result = VaultDecryptResult.Decrypted("default", plaintext)
        assertFalse(result.toString(), result.toString().contains("SENTINEL"))
        assertEquals("Decrypted(default, ***)", result.toString())
        plaintext.close()
    }

    fun testNewCodesFlowThroughTheSeverityPolicy() {
        val settings = RootSettings.DEFAULT
        assertEquals(Level.ERROR, SeverityPolicy.levelFor(DiagnosticCode.V101_MALFORMED_ENVELOPE, settings))
        assertEquals(Level.WARNING, SeverityPolicy.levelFor(DiagnosticCode.V104_NO_ID_DECRYPTS, settings))
        assertEquals(Level.WEAK_WARNING, SeverityPolicy.levelFor(DiagnosticCode.V105_LABEL_SECRET_MISMATCH, settings))
        assertEquals(Level.INFO, SeverityPolicy.levelFor(DiagnosticCode.V106_UNKNOWN_VAULT_LABEL, settings))
        assertEquals(Level.INFO, SeverityPolicy.levelFor(DiagnosticCode.P001B_FALLBACK_ONLY_OVERRIDE, settings))
        assertEquals(Level.WARNING, SeverityPolicy.levelFor(DiagnosticCode.P001_INEFFECTIVE_OVERRIDE, settings, FindingContext(reachable = false)))
        assertEquals(Level.WARNING, SeverityPolicy.levelFor(DiagnosticCode.P002_REDUNDANT_OVERRIDE, RootSettings(preset = Preset.STRICT)))
    }
}
