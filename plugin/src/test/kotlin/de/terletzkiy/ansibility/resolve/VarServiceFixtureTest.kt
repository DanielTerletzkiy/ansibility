package de.terletzkiy.ansibility.resolve

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.terletzkiy.ansibility.api.JinjaContainer
import de.terletzkiy.ansibility.api.VarDefKind
import de.terletzkiy.ansibility.api.VarDefinition
import de.terletzkiy.ansibility.api.VarService
import de.terletzkiy.ansibility.api.VarsLayer
import de.terletzkiy.ansibility.fixtures.InfraTestData
import de.terletzkiy.ansibility.fixtures.RequiresInfraFixture
import de.terletzkiy.ansibility.resolve.IndexFixtureSupport.Companion.WORKTREE_GOLDEN
import de.terletzkiy.ansibility.semantics.schema.OptionType

/** [VarServiceImpl] and [VarUsageQuery] on sub-trees of the infra fixture (acceptance examples of F1.4, F1.5, F4.3). */
@RequiresInfraFixture
class VarServiceFixtureTest : BasePlatformTestCase() {
    private lateinit var support: IndexFixtureSupport

    override fun getTestDataPath(): String = InfraTestData.testDataPath.toString()

    override fun setUp() {
        super.setUp()
        support = IndexFixtureSupport(myFixture)
    }

    /** golden, falcon and the detached worktree: copying and indexing them is the expensive part, so tests share it. */
    private fun copyGoldenFalconAndWorktree() {
        support.copy("golden")
        support.copy("repos/falcon")
        support.copyWorktree()
        support.refreshRoots()
    }

    private val service: VarService get() = VarService.getInstance(project)

    private fun VarDefinition.describe() = "${support.describe(location)} $kind"

    fun testFalconAndGoldenSymbols() {
        copyGoldenFalconAndWorktree()
        assertTrue(service is VarServiceImpl)
        checkPostfixRelayhostInFalcon()
        checkSystemNetworkingMainIpBindingsAreAllFalconRoles()
        checkHaproxyBackportsVersionInGolden()
        checkDocCommentsAndAllNames()
    }

    fun testDetachedWorktreeAndUsages() {
        copyGoldenFalconAndWorktree()
        checkDetachedWorktreeNeverLeaksIntoOtherRoots()
        checkVarUsagesSkipArgumentSpecDescriptions()
    }

    private fun checkPostfixRelayhostInFalcon() {
        val falcon = support.root("repos/falcon/ansible")
        val symbol = service.symbol(falcon, "postfix_relayhost")
        assertEquals(falcon.dir, symbol.rootDir)
        assertEquals(
            listOf(
                "repos/falcon/ansible/environments/prod/group_vars/all/vars.yml:471 GROUP_VARS",
                "repos/falcon/ansible/environments/test/group_vars/all/vars.yml:323 GROUP_VARS",
                "repos/falcon/ansible/group_vars/all/vars.yml:156 GROUP_VARS",
                "repos/falcon/ansible/roles/postfix/defaults/main.yml:2 ROLE_DEFAULT",
                "repos/falcon/ansible/roles/postfix/meta/argument_specs.yml:6 SPEC_OPTION",
                "repos/falcon/ansible/roles/postfix/molecule/default/molecule.yml:56 MOLECULE_INVENTORY",
            ),
            symbol.definitions.map { it.describe() },
        )
        val byLine = symbol.definitions.associateBy { support.describe(it.location) }
        val prod = byLine.getValue("repos/falcon/ansible/environments/prod/group_vars/all/vars.yml:471")
        assertEquals(VarsLayer.INVENTORY_GROUP_VARS_ALL, prod.layer)
        assertEquals("prod", prod.environment)
        assertEquals("all", prod.group)
        assertEquals("relay.mx.example.de", prod.preview)
        val playbook = byLine.getValue("repos/falcon/ansible/group_vars/all/vars.yml:156")
        assertEquals(VarsLayer.PLAYBOOK_GROUP_VARS_ALL, playbook.layer)
        assertNull(playbook.environment)
        val default = byLine.getValue("repos/falcon/ansible/roles/postfix/defaults/main.yml:2")
        assertEquals(VarsLayer.ROLE_DEFAULTS, default.layer)
        assertEquals("postfix", default.roleName)
        assertEquals("\"\"", default.preview)
        val molecule = byLine.getValue("repos/falcon/ansible/roles/postfix/molecule/default/molecule.yml:56")
        assertEquals(VarsLayer.MOLECULE_INVENTORY, molecule.layer)
        assertEquals("all", molecule.group)

        val binding = symbol.specBindings.single()
        assertEquals("postfix", binding.role.name)
        assertEquals(falcon.dir, binding.role.rootDir)
        assertEquals(support.file("repos/falcon/ansible/roles/postfix"), binding.role.dir)
        assertEquals("main", binding.entryPoint)
        assertEquals("postfix_relayhost", binding.option.name)
        assertEquals(OptionType.Str, binding.option.type)
        assertEquals("repos/falcon/ansible/roles/postfix/meta/argument_specs.yml:6", support.describe(binding.location))
        assertTrue("nothing from golden", (symbol.definitions.map { it.location } + binding.location).all { it.file.path.contains("/repos/falcon/") })
    }

    private fun checkSystemNetworkingMainIpBindingsAreAllFalconRoles() {
        val falcon = support.root("repos/falcon/ansible")
        val symbol = service.symbol(falcon, "system_networking_main_ip")
        assertEquals(listOf("grafana", "haproxy", "loki", "system"), symbol.specBindings.map { it.role.name }.sorted())
        assertTrue(symbol.specBindings.all { it.role.rootDir == falcon.dir && it.location.file.path.contains("/repos/falcon/ansible/roles/") })
        val hostVars = symbol.definitions.filter { it.kind == VarDefKind.HOST_VARS }
        assertTrue(hostVars.any { support.describe(it.location) == "repos/falcon/ansible/environments/prod/host_vars/prod-prod1/vars.yml:25" })
        assertTrue(hostVars.all { it.layer == VarsLayer.INVENTORY_HOST_VARS && it.host != null && it.environment != null })
        assertEquals("prod-prod1", hostVars.first { it.location.file.path.contains("prod-prod1") }.host)

        val golden = service.symbol(support.root("golden"), "system_networking_main_ip")
        assertEquals(listOf("coolify", "grafana", "haproxy", "jenkins-controller", "keycloak", "loki", "system"), golden.specBindings.map { it.role.name }.sorted())
        assertTrue(golden.specBindings.all { it.location.file.path.contains("/golden/roles/") && !it.location.file.path.contains("worktrees") })
    }

    private fun checkHaproxyBackportsVersionInGolden() {
        val golden = support.root("golden")
        val symbol = service.symbol(golden, "haproxy_backports_version")
        assertEquals(
            listOf("golden/roles/haproxy/defaults/main.yml:1 ROLE_DEFAULT", "golden/roles/haproxy/meta/argument_specs.yml:42 SPEC_OPTION"),
            symbol.definitions.map { it.describe() },
        )
        assertEquals("3.2", symbol.definitions.first().preview)
        val binding = symbol.specBindings.single()
        assertEquals("haproxy", binding.role.name)
        assertEquals(golden.dir, binding.role.rootDir)
        assertEquals(OptionType.Str, binding.option.type)
    }

    private fun checkDetachedWorktreeNeverLeaksIntoOtherRoots() {
        val golden = support.root("golden")
        val worktree = support.root(WORKTREE_GOLDEN)
        assertTrue(worktree.detached)
        val inGolden = service.symbol(golden, "haproxy_stats_http_port")
        assertEquals(
            listOf("golden/roles/haproxy/defaults/main.yml:10 ROLE_DEFAULT", "golden/roles/haproxy/meta/argument_specs.yml:162 SPEC_OPTION"),
            inGolden.definitions.map { it.describe() },
        )
        assertEquals("8404", inGolden.definitions.first().preview)
        assertTrue(inGolden.specBindings.none { it.location.file.path.contains("worktrees") })
        val inFalcon = service.symbol(support.root("repos/falcon/ansible"), "haproxy_stats_http_port")
        assertTrue(inFalcon.definitions.none { it.location.file.path.contains("worktrees") })

        val own = service.symbol(worktree, "haproxy_stats_http_port")
        assertEquals(
            listOf(
                "$WORKTREE_GOLDEN/roles/haproxy/defaults/main.yml:4 ROLE_DEFAULT",
                "$WORKTREE_GOLDEN/roles/haproxy/meta/argument_specs.yml:8 SPEC_OPTION",
            ),
            own.definitions.map { it.describe() },
        )
        assertEquals("9999", own.definitions.first().preview)
        assertTrue(own.definitions.first().docComment!!.startsWith("Synthetic fixture written by tools/fixtures/sync.py"))
        assertEquals(OptionType.Int, own.specBindings.single().option.type)

        assertFalse(service.allNames(golden).isEmpty())
        val usages = VarUsageQuery.getInstance(project).usages(golden, "haproxy_stats_http_port")
        assertTrue(usages.none { it.location.file.path.contains("worktrees") })
        assertEquals(
            listOf("$WORKTREE_GOLDEN/roles/haproxy/tasks/main.yml:6"),
            VarUsageQuery.getInstance(project).usages(worktree, "haproxy_stats_http_port").map { support.describe(it.location) },
        )
    }

    private fun checkDocCommentsAndAllNames() {
        val golden = support.root("golden")
        val somaxconn = service.symbol(golden, "haproxy_settings_kernel_somaxconn").definitions.single { it.kind == VarDefKind.ROLE_DEFAULT }
        assertEquals("Maximum connections at kernel level", somaxconn.docComment)
        assertNull(service.symbol(golden, "haproxy_backports_version").definitions.first().docComment)

        val falconNames = service.allNames(support.root("repos/falcon/ansible"))
        assertTrue("postfix_relayhost" in falconNames)
        assertTrue("system_networking_main_ip" in falconNames)
        assertFalse("golden-only role", "chronod_binary_checksum" in falconNames)
        val goldenNames = service.allNames(golden)
        assertTrue("chronod_binary_checksum" in goldenNames)
        assertFalse("inventory-only falcon variable", "app_falcon_mono_mysql_password" in goldenNames)
        assertTrue("app_falcon_mono_mysql_password" in falconNames)
    }

    private fun checkVarUsagesSkipArgumentSpecDescriptions() {
        val golden = support.root("golden")
        val usages = VarUsageQuery.getInstance(project)
        val servers = usages.usages(golden, "haproxy_servers").map { "${support.describe(it.location)} ${it.container}" }
        assertTrue(servers.toString(), "golden/roles/haproxy/templates/haproxy.cfg.j2:75 TEMPLATE_FILE" in servers)
        assertTrue(servers.toString(), "golden/roles/haproxy/templates/haproxy.cfg.j2:93 TEMPLATE_FILE" in servers)
        val version = usages.usages(golden, "haproxy_backports_version").map { support.describe(it.location) }
        assertTrue(version.toString(), "golden/roles/haproxy/tasks/apt.yml:24" in version)
        assertTrue(version.toString(), "golden/roles/haproxy/defaults/main.yml:4" in version)
        assertFalse("literal {{ }} in a spec description is no use", version.any { it.startsWith("golden/roles/haproxy/meta/") })
        assertTrue(usages.usages(golden, "haproxy_backports_version").all { it.container == JinjaContainer.YAML_TEMPLATE })
        assertTrue(usages.hasUsages(golden, "haproxy_servers"))
        assertFalse(usages.hasUsages(golden, "no_such_variable_anywhere"))
    }

    fun testEditsInvalidateCachedSymbols() {
        support.copy("golden/roles/haproxy")
        support.refreshRoots()
        val golden = support.root("golden")
        assertEquals("8404", service.symbol(golden, "haproxy_stats_http_port").definitions.first().preview)
        assertFalse("haproxy_brand_new" in service.allNames(golden))
        val file = support.file("golden/roles/haproxy/defaults/main.yml")
        val document = FileDocumentManager.getInstance().getDocument(file)!!
        WriteCommandAction.runWriteCommandAction(project) {
            document.setText(document.text.replace("haproxy_stats_http_port: 8404", "haproxy_stats_http_port: 8405\n# New\nhaproxy_brand_new: 1"))
            PsiDocumentManager.getInstance(project).commitDocument(document)
        }
        assertEquals("8405", service.symbol(golden, "haproxy_stats_http_port").definitions.first().preview)
        assertTrue("haproxy_brand_new" in service.allNames(golden))
        assertEquals("New", service.symbol(golden, "haproxy_brand_new").definitions.single().docComment)
    }
}
