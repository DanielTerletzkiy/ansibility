package de.terletzkiy.ansibility.model.effective

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.psi.PsiDocumentManager
import de.terletzkiy.ansibility.api.AnsibleContextService
import de.terletzkiy.ansibility.api.EvalTarget
import de.terletzkiy.ansibility.api.HostKey
import de.terletzkiy.ansibility.api.PlayGraph
import de.terletzkiy.ansibility.api.PlayInfo
import de.terletzkiy.ansibility.api.RuntimeMarkerKind
import de.terletzkiy.ansibility.model.inventory.InventoryModels
import de.terletzkiy.ansibility.model.inventory.InventoryTestCase
import de.terletzkiy.ansibility.model.inventory.ModelCaches
import de.terletzkiy.ansibility.semantics.precedence.VarLayer
import de.terletzkiy.ansibility.semantics.yaml.YMap
import de.terletzkiy.ansibility.semantics.yaml.YScalar
import de.terletzkiy.ansibility.semantics.yaml.YVault

/**
 * The per-file caching of the model services (plan amendment R7/R8, A.9 changes 1 and 2) on a synthetic root: each
 * value is rebuilt exactly when a file it read changes, and `PlayInfo.vars` and the molecule graph carry what the
 * execution and molecule views need.
 */
class ModelCachingTest : InventoryTestCase() {
    override fun setUp() {
        super.setUp()
        for ((path, text) in FILES) myFixture.addFileToProject("$SITE/$path", text.trimIndent() + "\n")
        refreshRoots()
    }

    private val graph: PlayGraph get() = PlayGraph.getInstance(project)

    private val sources: ExecutionSources get() = ExecutionSources.getInstance(project)

    private fun web(): PlayInfo = graph.playsOf(vf("$SITE/site.yml")).single { it.ref.name == "Web" }

    /** Appends [text] to [path] and commits it, as typing does (never saved). */
    private fun type(path: String, text: String) {
        WriteCommandAction.runWriteCommandAction(project) {
            val document = FileDocumentManager.getInstance().getDocument(vf("$SITE/$path"))!!
            document.insertString(document.textLength, text)
            PsiDocumentManager.getInstance(project).commitDocument(document)
        }
    }

    fun testPlayInfoCarriesThePlayVarsAsLoaded() {
        val vars = web().vars!!
        assertEquals(listOf("play_var", "play_secret", "play_map"), vars.keys)
        assertEquals(web().varsKeys, vars.keys)
        assertEquals("x", (vars["play_var"] as YScalar).text)
        assertTrue("vault values are value-free markers", vars["play_secret"] is YVault)
        assertEquals(listOf("a"), (vars["play_map"] as YMap).keys)
        val keyRange = vars.entries.first().key.range!!
        val text = FileDocumentManager.getInstance().getDocument(vf("$SITE/site.yml"))!!.text
        assertEquals("ranges point into the playbook", "play_var", text.substring(keyRange.start, keyRange.end))
        assertNull("a play without vars", graph.playsOf(vf("$SITE/site.yml")).single { it.ref.name == "Plain" }.vars)

        val inputs = sources.inputs(root(SITE), web().ref, null)
        val playVars = inputs.sources.single { it.layer == VarLayer.PLAY_VARS }
        assertEquals("the L12 source is PlayInfo.vars", vars.keys, playVars.entries.keys.toList())
    }

    fun testPlaysApplyingIsCachedPerPlaybookGraph() {
        val site = root(SITE)
        val first = graph.playsApplying(site, "app")
        assertSame(first, graph.playsApplying(site, "app"))
        type("roles/app/tasks/main.yml", "# typed\n")
        type("environments/prod/group_vars/all.yml", "typed: 1\n")
        assertSame("edits of files no graph read keep the result", first, graph.playsApplying(site, "app"))
        type("site.yml", "\n- name: Late\n  hosts: web\n  roles:\n    - app\n")
        val after = graph.playsApplying(site, "app")
        assertNotSame(first, after)
        assertEquals(listOf("Web", "Late"), after.filter { it.file == vf("$SITE/site.yml") }.map { it.name })
        assertEquals("the molecule converge play applies the role too", 1, after.count { it.file.name == "converge.yml" })
    }

    fun testExecutionInputsFollowTheFilesTheyLoadedOnly() {
        val site = root(SITE)
        val play = web().ref
        val first = sources.inputs(site, play, "app")
        type("roles/app/tasks/main.yml", "# typed in a task file\n")
        type("roles/other/defaults/main.yml", "other_typed: 1\n")
        assertSame("task files and unrelated roles are no inputs", first, sources.inputs(site, play, "app"))
        type("roles/app/defaults/main.yml", "app_typed: 1\n")
        val second = sources.inputs(site, play, "app")
        assertNotSame(first, second)
        assertTrue(second.sources.any { it.layer == VarLayer.ROLE_DEFAULTS && "app_typed" in it.entries })
        type("vars/common.yml", "common_typed: 1\n")
        assertTrue("vars_files targets are inputs", sources.inputs(site, play, "app").sources.any { "common_typed" in it.entries })
    }

    fun testIncludeVarsMarkersFollowTheTaskFiles() {
        val context = AnsibleContextService.getInstance(project)
        val target = EvalTarget(HostKey(SITE, "prod", "web1"), web().ref, vf(SITE))
        assertEquals(RuntimeMarkerKind.INCLUDE_VARS, context.explain(target, "extra_var").runtimeMarkers.single().kind)
        val inputs = sources.inputs(root(SITE), web().ref, null)
        type("roles/app/tasks/main.yml", "- name: More\n  ansible.builtin.include_vars: more.yml\n")
        assertSame("the inputs keep", inputs, sources.inputs(root(SITE), web().ref, null))
        type("roles/app/vars/more.yml", "more_var: 1\n")
        assertEquals("the new include_vars task is seen", RuntimeMarkerKind.INCLUDE_VARS, context.explain(target, "more_var").runtimeMarkers.single().kind)
    }

    fun testEnvironmentModelsAreCachedPerEnvironment() {
        val models = InventoryModels.getInstance(project)
        val site = root(SITE)
        val prod = models.environment(site, "prod")!!
        val test = models.environment(site, "test")!!
        val list = models.environments(site)
        assertSame(prod, list.single { it.name == "prod" })
        type("environments/prod/hosts.yml", "    web3:\n")
        val prodAfter = models.environment(site, "prod")!!
        assertNotSame(prod, prodAfter)
        assertEquals(listOf("web1", "web2", "web3"), prodAfter.graph.hostsOf("web"))
        assertSame("another environment's model is kept", test, models.environment(site, "test"))
        assertNotSame("the list follows its members", list, models.environments(site))
        assertSame(test, models.environments(site).single { it.name == "test" })
    }

    fun testMoleculeScenariosExposeTheirGraphWithInlineVariables() {
        val models = InventoryModels.getInstance(project)
        val site = root(SITE)
        val scenario = models.moleculeModels(site).single()
        assertEquals("app", scenario.inventory.roleName)
        assertEquals(listOf("m1"), scenario.graph.hosts.keys.toList())
        assertEquals(mapOf("inline_host_var" to "1"), scenario.graph.host("m1")!!.vars.mapValues { (it.value as YScalar).text })
        assertEquals(listOf("inline_group_var"), scenario.graph.group("web")!!.vars.keys.toList())
        assertEquals("owners of the inline group_vars", listOf("all"), scenario.groupVars!!.keys)
        assertNull(scenario.hostVars)
        assertSame("parsed once per molecule.yml version", scenario, models.moleculeModel(site, scenario.inventory))

        val view = MoleculeViews.getInstance(project).view(site, scenario.inventory, "m1")!!
        assertEquals(VarLayer.INVENTORY_FILE_HOST, view.view["inline_host_var"]!!.winner.source.layer)
        assertEquals(VarLayer.INVENTORY_FILE_GROUP, view.view["inline_group_var"]!!.winner.source.layer)
        assertEquals(VarLayer.MOLECULE_INVENTORY, view.view["mol_all"]!!.winner.source.layer)
        assertSame(view, MoleculeViews.getInstance(project).view(site, scenario.inventory, "m1"))

        val inventoryViews = HostViews.getInstance(project).views(site, "prod", vf(SITE))
        type("roles/app/molecule/default/molecule.yml", "# typed\n")
        assertNotSame(view, MoleculeViews.getInstance(project).view(site, scenario.inventory, "m1"))
        assertSame("inventory views do not read molecule.yml", inventoryViews, HostViews.getInstance(project).views(site, "prod", vf(SITE)))
    }

    fun testViewsOfOnePlaybookDirAreSharedByEveryHost() {
        val views = HostViews.getInstance(project)
        val before = ModelCaches.getInstance(project).snapshot()
        val web1 = views.view(root(SITE), "prod", "web1", vf(SITE))!!
        val web2 = views.view(root(SITE), "prod", "web2", vf(SITE))!!
        assertNotSame(web1, web2)
        assertEquals("one entry computes both hosts", 1L, ModelCaches.getInstance(project).snapshot().computationsOf(HostViews.CACHE_NAME, before))
        assertSame(web1.origins, web2.origins)
        assertEquals(listOf("web1", "web2"), views.views(root(SITE), "prod", vf(SITE))!!.hosts.toList())
        assertNull(views.view(root(SITE), "prod", "nobody", vf(SITE)))
        assertTrue(views.estimatedBytes() > 0)
    }

    private companion object {
        const val SITE = "site"

        val FILES: Map<String, String> = mapOf(
            "ansible.cfg" to "[defaults]\nhash_behaviour = replace",
            "environments/prod/hosts.yml" to "all:\n  hosts:\n    web1:\n    web2:\nweb:\n  hosts:\n    web1:\n    web2:",
            "environments/test/hosts.yml" to "all:\n  hosts:\n    t1:\nweb:\n  hosts:\n    t1:",
            "environments/prod/group_vars/all.yml" to "prod_var: 1",
            "environments/test/group_vars/all.yml" to "test_var: 1",
            "group_vars/all.yml" to "shared: 1",
            "vars/common.yml" to "common: 1",
            "site.yml" to """
                - name: Web
                  hosts: web
                  vars:
                    play_var: x
                    play_secret: !vault |
                      ${'$'}ANSIBLE_VAULT;1.1;AES256
                      3132
                    play_map:
                      a: 1
                  vars_files:
                    - vars/common.yml
                  roles:
                    - app
                - name: Plain
                  hosts: web
                  roles:
                    - other
            """,
            "roles/app/defaults/main.yml" to "app_port: 80",
            "roles/app/tasks/main.yml" to "- name: Extra\n  ansible.builtin.include_vars: extra.yml",
            "roles/app/vars/extra.yml" to "extra_var: 1",
            "roles/app/vars/more.yml" to "# more",
            "roles/app/molecule/default/molecule.yml" to """
                platforms:
                  - name: m1
                    groups:
                      - web
                provisioner:
                  name: ansible
                  inventory:
                    hosts:
                      web:
                        hosts:
                          m1:
                            inline_host_var: 1
                        vars:
                          inline_group_var: 2
                    group_vars:
                      all:
                        mol_all: 3
            """,
            "roles/app/molecule/default/converge.yml" to "- hosts: all\n  roles:\n    - app",
            "roles/other/defaults/main.yml" to "other_port: 81",
            "roles/other/tasks/main.yml" to "- name: Noop\n  ansible.builtin.debug:\n    msg: hi",
        )
    }
}
