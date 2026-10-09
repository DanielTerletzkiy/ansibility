package de.terletzkiy.ansibility.model.drift

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.terletzkiy.ansibility.model.role.ModelFixture
import de.terletzkiy.ansibility.model.role.RoleCatalog
import de.terletzkiy.ansibility.settings.AnsibilityProjectSettings
import de.terletzkiy.ansibility.settings.GoldenRoot
import de.terletzkiy.ansibility.settings.SettingsTestSupport
import kotlinx.coroutines.runBlocking
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds

/**
 * [RoleDriftService] and the golden-root setting (plan amendment R24, D177/D178) on the synthetic drift tree: nothing is
 * computed in the background without a golden root, a chosen root is the reference, a new golden root re-tiers without
 * rehashing, and "Ignore molecule/" comes from the settings.
 */
class RoleDriftGoldenTest : BasePlatformTestCase() {
    private lateinit var service: RoleDriftService

    override fun getTestDataPath(): String = ModelFixture.testDataPath

    override fun setUp() {
        super.setUp()
        DriftFixture.copy(myFixture)
        service = DriftFixture.freshService(project, testRootDisposable)
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

    private fun dir(team: String, role: String = "web"): VirtualFile = DriftFixture.file(myFixture, DriftFixture.roleDir(team, role))

    private fun golden(golden: GoldenRoot) = AnsibilityProjectSettings.getInstance(project).update { it.copy(drift = it.drift.copy(golden = golden)) }

    private fun allCurrent(): Boolean = NAMES.all(service::isCurrent)

    fun testWithoutAGoldenRootTheWorkerComputesNothing() {
        val published = Collections.synchronizedList(ArrayList<String>())
        project.messageBus.connect(testRootDisposable).subscribe(RoleDriftListener.TOPIC, RoleDriftListener { published += it })
        service.request(listOf("web"))
        Thread.sleep(300)
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        assertEquals("D178: nothing is computed without a golden root", 0, service.counters.copiesWalked)
        assertNull(service.cached("web"))
        assertTrue(published.isEmpty())

        golden(GoldenRoot.FirstRoleLibrary)
        DriftFixture.waitFor("a golden root set later wakes the requested worker") { allCurrent() }
        assertEquals(DriftTier.MOLECULE_ONLY, service.cached(RoleCatalog.getInstance(project).copyOf(dir("mol"))!!)?.tier)
        assertEquals(NAMES.toSet(), published.toSet())
    }

    fun testAChosenProjectRootIsTheReference() {
        golden(GoldenRoot.Root("repos/same/ansible"))
        val web = DriftFixture.drift(service, "web")
        assertEquals(dir("same"), web.reference?.dir)
        assertEquals("the golden copy leads", listOf("same", "golden", "missing", "mol", "mol2", "spec", "specmol", "tasks"), web.copies.map { it.copy.root.displayName })
        assertEquals(
            listOf(
                DriftTier.REFERENCE, DriftTier.IDENTICAL, DriftTier.BEHAVIOUR, DriftTier.MOLECULE_ONLY, DriftTier.MOLECULE_ONLY,
                DriftTier.SPEC_DEFAULTS, DriftTier.SPEC_DEFAULTS, DriftTier.BEHAVIOUR,
            ),
            web.copies.map { it.tier },
        )
        val solo = DriftFixture.drift(service, "solo")
        assertEquals("same's solo is golden now", dir("same", "solo"), solo.reference?.dir)
        assertEquals("tasks' solo differs in defaults/main.yml", DriftTier.SPEC_DEFAULTS, solo.copyOf(dir("tasks", "solo"))?.tier)
    }

    fun testANewGoldenRootReTiersWithoutRehashing() {
        golden(GoldenRoot.FirstRoleLibrary)
        service.request()
        DriftFixture.waitFor("every name computed") { allCurrent() }
        val hashed = service.counters.filesHashed
        assertEquals(dir("golden"), service.cached("web")?.reference?.dir)

        golden(GoldenRoot.Root("repos/tasks/ansible"))
        DriftFixture.waitFor("re-tiered against tasks") { service.cached("web")?.reference?.dir == dir("tasks") && allCurrent() }
        val web = service.cached("web")!!
        assertEquals(DriftTier.REFERENCE, web.copyOf(dir("tasks"))?.tier)
        assertEquals("golden now differs from the golden root tasks", DriftTier.BEHAVIOUR, web.copyOf(dir("golden"))?.tier)
        assertEquals(listOf("tasks/main.yml"), web.copyOf(dir("golden"))?.paths?.changed)
        assertEquals("the fingerprints stay: nothing is rehashed", hashed, service.counters.filesHashed)

        golden(GoldenRoot.None)
        assertNull("no result survives a change of the golden root", service.cached("web"))
    }

    fun testIgnoreMoleculeFollowsTheSettings() {
        golden(GoldenRoot.FirstRoleLibrary)
        assertEquals(DriftOptions(), service.options)
        AnsibilityProjectSettings.getInstance(project).update { it.copy(drift = it.drift.copy(ignoreMolecule = true)) }
        assertEquals(DriftOptions(ignoreMolecule = true), service.options)
        assertEquals(DriftTier.IDENTICAL, DriftFixture.copyDrift(myFixture, service, "web", "mol").tier)
        assertEquals("a new service starts with the stored option", DriftOptions(ignoreMolecule = true), DriftFixture.freshService(project, testRootDisposable).options)
    }

    fun testTheWordingNamesAGoldenRootThatIsNotNamedGolden() {
        golden(GoldenRoot.Root("repos/mol/ansible"))
        val base = DriftFixture.drift(service, "base")
        assertEquals("= mol", DriftTexts.badge(base, base.copyOf(dir("same", "base"))!!))
        val solo = DriftFixture.drift(service, "solo")
        assertNull("mol has no copy of solo", solo.reference)
        assertEquals("the golden root is known without a reference copy", "mol", solo.goldenName)
        val copy = solo.copyOf(dir("same", "solo"))!!
        assertEquals(DriftTier.NO_REFERENCE, copy.tier)
        // Push, Align and Compare pass no name: the defaults must name mol, not "golden".
        assertEquals("no mol copy", DriftTexts.badge(solo, copy))
        assertEquals("no mol copy", DriftTexts.rowBadge(solo, copy))
        assertEquals("no mol copy", DriftTexts.nameSummary(solo))
        assertEquals("no mol copy", DriftTexts.tierName(DriftTier.NO_REFERENCE, solo.goldenName))
        assertEquals("The golden root mol has no copy of this role; only the variants are compared", DriftTexts.tooltip(copy.tier, solo.goldenName))
    }

    fun testVariantsRankGoldensFirstThenBySizeThenByName() {
        golden(GoldenRoot.FirstRoleLibrary)
        // same takes mol's molecule edit: mol, mol2 and same share one variant, golden is alone.
        DriftFixture.copyFile(myFixture, "mol", "same", "molecule/default/verify.yml")
        val web = DriftFixture.drift(service, "web")
        assertEquals(
            listOf(listOf("golden"), listOf("mol", "mol2", "same"), listOf("missing"), listOf("spec"), listOf("specmol"), listOf("tasks")),
            web.rankedVariants.map { variant -> variant.copies.map { it.root.displayName } },
        )
        assertEquals(listOf("mol", "mol2", "same"), web.majorityVariant?.copies?.map { it.root.displayName })
        assertEquals(listOf("golden"), web.referenceVariant?.copies?.map { it.root.displayName })

        fun majority(team: String) = DriftTexts.variantMajority(web, web.copyOf(dir(team)))
        assertEquals(listOf("3 of 8 copies share mol's variant", "No other copy shares golden's variant"), majority("tasks"))
        assertEquals("its own variant is the details' Same as line", listOf("No other copy shares golden's variant"), majority("same"))
        assertEquals(listOf("3 of 8 copies share mol's variant"), majority("golden"))
    }

    fun testGoldensVariantIsNamedWhenItIsTheLargest() {
        golden(GoldenRoot.FirstRoleLibrary)
        val web = DriftFixture.drift(service, "web")
        assertEquals("a tie goes to golden's variant", listOf("golden", "same"), web.majorityVariant?.copies?.map { it.root.displayName })
        assertEquals(listOf("golden's variant is shared by 2 of 8 copies"), DriftTexts.variantMajority(web, web.copyOf(dir("missing"))))
        assertEquals(emptyList<String>(), DriftTexts.variantMajority(web, web.copyOf(dir("same"))))
        val base = DriftFixture.drift(service, "base")
        assertNull("identical everywhere: nothing to tell", base.majorityVariant)
        assertEquals(emptyList<String>(), DriftTexts.variantMajority(base, base.copyOf(dir("mol", "base"))))
        val solo = DriftFixture.drift(service, "solo")
        assertEquals("two copies: nothing to tell", emptyList<String>(), DriftTexts.variantMajority(solo, solo.copies.first()))
    }

    // ------------------------------------------------------------------ review fixes P1 (requestNames) and P4

    fun testRequestNamesComputesOnlyThoseNamesAndFollowsTheirEdits() {
        golden(GoldenRoot.FirstRoleLibrary)
        service.documentDebounce = 50.milliseconds
        service.requestNames(listOf("base"))
        DriftFixture.waitFor("the requested name") { service.isCurrent("base") }
        val until = System.currentTimeMillis() + 1000
        while (System.currentTimeMillis() < until && listOf("app-same", "solo", "web").none(service::isComputed)) Thread.sleep(50)
        assertFalse("no all-names pass", listOf("app-same", "solo", "web").any(service::isComputed))
        assertEquals("only base's three copies", 3L, service.counters.copiesWalked)

        DriftFixture.write(myFixture, "${DriftFixture.roleDir("same", "base")}/tasks/extra.yml", "---\n- name: Extra\n  ansible.builtin.meta: noop\n")
        DriftFixture.waitFor("a watched name follows a VFS edit") { service.cached("base")?.copyOf(dir("same", "base"))?.tier == DriftTier.BEHAVIOUR }
        val document = FileDocumentManager.getInstance().getDocument(DriftFixture.file(myFixture, "${DriftFixture.roleDir("same", "base")}/tasks/extra.yml"))!!
        WriteCommandAction.runWriteCommandAction(project) { document.setText("---\n- name: Typed\n  ansible.builtin.meta: noop\n") }
        DriftFixture.waitFor("and a document edit") { service.cached("base")?.copyOf(dir("same", "base"))?.paths?.onlyHere == listOf("tasks/extra.yml") && service.isCurrent("base") }
        DriftFixture.write(myFixture, "${DriftFixture.roleDir("same")}/tasks/main.yml", "---\n# edited\n")
        Thread.sleep(300)
        assertFalse("an edit of a name nobody asked for computes nothing", service.isComputed("web"))
    }

    fun testAComputationAgainstThePreviousGoldenRootIsNotStored() {
        golden(GoldenRoot.FirstRoleLibrary)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        service.beforeStoreForTests = { name ->
            if (name == "web" && entered.count > 0) {
                entered.countDown()
                release.await(30, TimeUnit.SECONDS)
            }
        }
        val computing = ApplicationManager.getApplication().executeOnPooledThread<RoleDrift?> { runBlocking { service.drift("web") } }
        assertTrue("the computation against golden is under way", entered.await(30, TimeUnit.SECONDS))
        golden(GoldenRoot.Root("repos/same/ansible"))
        release.countDown()
        val returned = PlatformTestUtil.waitForFuture(computing, TimeUnit.MINUTES.toMillis(1))
        val cached = service.cached("web")
        assertTrue("never the drift against the previous golden root: ${cached?.reference?.root?.displayName}", cached == null || cached.reference?.dir == dir("same"))
        assertEquals("the caller gets the drift against the new golden root", dir("same"), returned?.reference?.dir)
    }

    private companion object {
        val NAMES = listOf("app-same", "base", "solo", "web")
    }
}
