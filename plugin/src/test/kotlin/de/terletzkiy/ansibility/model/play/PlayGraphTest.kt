package de.terletzkiy.ansibility.model.play

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import com.intellij.psi.impl.PsiManagerEx
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.terletzkiy.ansibility.api.PlayGraph
import de.terletzkiy.ansibility.api.PlayRef
import de.terletzkiy.ansibility.api.PlayRoleEntry
import de.terletzkiy.ansibility.api.PlaySection
import de.terletzkiy.ansibility.api.RoleEntryKind
import de.terletzkiy.ansibility.fixtures.RequiresInfraFixture
import de.terletzkiy.ansibility.model.role.ModelFixture

/** [PlayGraphService] on fixture playbooks (falcon, pelican danger zone, golden and falcon molecule) and synthetic ones. */
@RequiresInfraFixture
class PlayGraphTest : BasePlatformTestCase() {
    override fun getTestDataPath(): String = ModelFixture.testDataPath

    private val graph: PlayGraph get() = PlayGraph.getInstance(project)

    private fun vf(path: String): VirtualFile = ModelFixture.file(myFixture, path)

    private fun rel(file: VirtualFile): String = VfsUtilCore.getRelativePath(file, myFixture.tempDirFixture.getFile("")!!)!!

    private fun summary(entry: PlayRoleEntry): String =
        "${entry.kind}:${entry.name}@${entry.role?.dir?.let(::rel) ?: "?"}#${entry.entryPoint}${if (entry.tags.isEmpty()) "" else entry.tags}"

    fun testServiceIsTheImplementation() {
        assertTrue(graph is PlayGraphService)
    }

    fun testFalconSetupSystemRolesInOrderWithTags() {
        ModelFixture.copyInfra(myFixture, "repos/falcon")
        val playbook = vf("repos/falcon/ansible/playbook-setup-system.yml")
        val plays = graph.playsOf(playbook)
        assertEquals("Ping all hosts serially", plays.first().ref.name)
        assertEquals("all", plays.first().ref.hostsPattern)
        assertEquals(vf("repos/falcon/ansible"), plays.first().ref.playbookDir)
        val system = plays.single { it.ref.name == "System" }
        assertEquals("system", system.ref.hostsPattern)
        assertEquals(2, system.ref.playIndex)
        assertEquals(
            listOf("system", "system-access", "system-apt", "system-heartbeat", "postfix", "inventory-docs-client", "iptables"),
            system.roles.map { it.name },
        )
        assertEquals(system.roles.map { listOf(it.name) }, system.roles.map { it.tags })
        assertTrue(system.roles.all { it.kind == RoleEntryKind.PLAY_ROLE && it.section == PlaySection.ROLES && it.entryPoint == "main" })
        assertEquals("roles present in the fixture resolve", vf("repos/falcon/ansible/roles/system"), system.roles[0].role?.dir)
        assertEquals(vf("repos/falcon/ansible"), system.roles[0].role?.rootDir)
        assertNull("the fixture has no system-access role", system.roles[1].role)
        val location = system.roles[4].location!!
        assertEquals(playbook, location.file)
        assertTrue(VfsUtilCore.loadText(playbook).startsWith("postfix", location.offset))
        assertEquals(graph.rolesOfPlay(system.ref), system.roles)
        assertEquals(system, graph.play(system.ref))

        val proxy = plays.single { it.ref.name == "Proxy" }
        assertEquals(listOf("haproxy"), proxy.roles.map { it.name })
        assertEquals(graph.playsOf(playbook).map { it.ref }, graph.executionOrder(playbook))
    }

    fun testDangerZoneImportKeepsThePlaybookDirPerPlay() {
        ModelFixture.copyInfra(myFixture, "repos/pelican")
        myFixture.addFileToProject("repos/pelican/ansible/roles/percona/tasks/main.yml", "---\n- name: Percona\n  ansible.builtin.ping:\n")
        myFixture.addFileToProject("repos/pelican/ansible/danger_zone/database/roles/percona/tasks/main.yml", "---\n- name: Wrong copy\n  ansible.builtin.ping:\n")
        ModelFixture.rescan(project)
        val clone = vf("repos/pelican/ansible/danger_zone/database/playbook-clone-to-replisync.yml")
        val replisync = vf("repos/pelican/ansible/playbook-setup-replisync.yml")

        val edge = graph.imports(clone).single()
        assertEquals("../../playbook-setup-replisync.yml", edge.path)
        assertEquals(replisync, edge.target)

        val order = graph.executionOrder(clone)
        assertEquals(
            listOf("Ping replisync hosts serially", "Clone to replisync", "Ping replisync hosts serially", "Debug", "Install Percona on the replisync"),
            order.map { it.name },
        )
        assertEquals(listOf(clone, clone, replisync, replisync, replisync), order.map { it.file })
        val dangerZone = vf("repos/pelican/ansible/danger_zone/database")
        val pelican = vf("repos/pelican/ansible")
        assertEquals(listOf(dangerZone, dangerZone, pelican, pelican, pelican), order.map { it.playbookDir })

        val cloneRoles = graph.rolesOfPlay(order[1])
        assertEquals(listOf("replica-turn-off", "clone-percona-to-replica", "setup-percona-replica-replication"), cloneRoles.map { it.name })
        assertTrue(cloneRoles.all { it.role?.dir?.parent == vf("repos/pelican/ansible/danger_zone/database/roles") })
        assertEquals(listOf("percona_clone_donor_group", "percona_replication_source_group"), graph.play(order[1])!!.varsKeys)

        val percona = graph.rolesOfPlay(order[4]).single()
        assertEquals("imported plays resolve roles from their own playbook dir", vf("repos/pelican/ansible/roles/percona"), percona.role?.dir)
        assertEquals(pelican, percona.role?.rootDir)

        val pelicanRoot = ModelFixture.root(myFixture, "repos/pelican/ansible")
        val dzRoot = ModelFixture.root(myFixture, "repos/pelican/ansible/danger_zone/database")
        assertEquals(listOf(order[4]), graph.playsApplying(pelicanRoot, "percona"))
        assertEquals("the danger zone's own percona copy is never applied", emptyList<PlayRef>(), graph.playsApplying(dzRoot, "percona"))
        val turnOff = graph.playsApplying(dzRoot, "replica-turn-off")
        assertTrue(order[1] in turnOff)
        assertTrue(turnOff.all { it.playbookDir == dangerZone })
        assertEquals("plays of the parent root do not apply the danger zone's roles", emptyList<PlayRef>(), graph.playsApplying(pelicanRoot, "replica-turn-off"))
        assertFalse(clone in graph.playbooks(pelicanRoot))
        assertTrue(clone in graph.playbooks(dzRoot))
        assertTrue(replisync in graph.playbooks(pelicanRoot))
    }

    fun testMoleculeAbsoluteRolePathsAndIncludeRole() {
        ModelFixture.copyInfra(myFixture, "golden/roles/haproxy", "golden/roles/grafana", "golden/roles/redis")
        val golden = ModelFixture.root(myFixture, "golden")
        val converge = vf("golden/roles/haproxy/molecule/default/converge.yml")
        val entry = graph.playsOf(converge).single().roles.single()
        assertEquals("/ansible/roles/haproxy", entry.written)
        assertEquals("haproxy", entry.name)
        assertEquals(vf("golden/roles/haproxy"), entry.role?.dir)
        assertEquals(golden.dir, entry.role?.rootDir)
        assertEquals(vf("golden/roles/haproxy/molecule/default"), graph.playsOf(converge).single().ref.playbookDir)

        val grafanaConverge = vf("golden/roles/grafana/molecule/default/converge.yml")
        val include = graph.playsOf(grafanaConverge).single().roles.single()
        assertEquals(RoleEntryKind.INCLUDE_ROLE, include.kind)
        assertEquals(PlaySection.TASKS, include.section)
        assertEquals("alerting.yml", include.entryPoint)
        assertEquals("molecule.yml", include.handlersFrom)
        assertEquals(vf("golden/roles/grafana"), include.role?.dir)

        val applying = graph.playsApplying(golden, "grafana", "alerting.yml")
        assertTrue(applying.any { it.file == grafanaConverge })
        assertTrue("verify.yml includes the role too", applying.any { it.file == vf("golden/roles/grafana/molecule/default/verify.yml") })
        assertEquals(emptyList<PlayRef>(), graph.playsApplying(golden, "grafana", "main").filter { it.file == grafanaConverge })
        assertEquals(listOf(converge), graph.playsApplying(golden, "haproxy").map { it.file }.filter { it.name == "converge.yml" })

        val redis = graph.playsOf(vf("golden/roles/redis/molecule/auth/converge.yml")).single()
        assertEquals(listOf("vars/main.yml"), redis.varsFiles.map { it.path })
        assertEquals(vf("golden/roles/redis/molecule/auth/vars/main.yml"), redis.varsFiles.single().file)

        val playbooks = graph.playbooks(golden).map(::rel)
        assertTrue(playbooks.contains("golden/roles/haproxy/molecule/default/converge.yml"))
        assertTrue(playbooks.contains("golden/roles/grafana/molecule/default/verify.yml"))
        assertFalse("task files are not playbooks", playbooks.any { "/tasks/" in it })
    }

    fun testMetaDependenciesExpandBeforeTheDependentRole() {
        ModelFixture.copyInfra(myFixture, "repos/falcon")
        val falcon = ModelFixture.root(myFixture, "repos/falcon/ansible")
        val converge = vf("repos/falcon/ansible/roles/jenkins-agent-docker/molecule/default/converge.yml")
        val entries = graph.playsOf(converge).single().roles
        assertEquals(
            listOf(
                "DEPENDENCY:docker@repos/falcon/ansible/roles/docker#main[docker]",
                "PLAY_ROLE:jenkins-agent-docker@repos/falcon/ansible/roles/jenkins-agent-docker#main",
            ),
            entries.map(::summary),
        )
        assertEquals("jenkins-agent-docker", entries[0].requiredBy)
        assertEquals(vf("repos/falcon/ansible/roles/jenkins-agent-docker/meta/main.yml"), entries[0].location?.file)

        val applyingDocker = graph.playsApplying(falcon, "docker")
        assertTrue("the dependency counts as applying docker", applyingDocker.any { it.file == converge })
        assertTrue(applyingDocker.any { it.file == vf("repos/falcon/ansible/playbook-setup-jenkins.yml") && it.name == "Jenkins Nodes (all)" })
        assertTrue(applyingDocker.any { it.file == vf("repos/falcon/ansible/roles/docker/molecule/default/converge.yml") })
    }

    fun testRootScoping() {
        ModelFixture.copyInfra(myFixture, "golden/roles/haproxy", "repos/falcon")
        val golden = ModelFixture.root(myFixture, "golden")
        val falcon = ModelFixture.root(myFixture, "repos/falcon/ansible")
        val goldenPlays = graph.playsApplying(golden, "haproxy")
        val falconPlays = graph.playsApplying(falcon, "haproxy")
        assertTrue(goldenPlays.isNotEmpty())
        assertTrue(falconPlays.isNotEmpty())
        assertTrue(goldenPlays.all { VfsUtilCore.isAncestor(golden.dir, it.file, true) })
        assertTrue(falconPlays.all { VfsUtilCore.isAncestor(falcon.dir, it.file, true) })
        assertTrue(falconPlays.any { it.file == vf("repos/falcon/ansible/playbook-setup-system.yml") && it.name == "Proxy" })
        assertEquals(emptyList<PlayRef>(), graph.playsApplying(falcon, "no-such-role"))
    }

    fun testSectionsIncludesDependenciesAndCycles() {
        ModelFixture.copyModelRoles(myFixture)
        val site = ModelFixture.root(myFixture, "site")
        val playbook = vf("site/playbook-site.yml")
        val web = graph.playsOf(playbook).single()
        assertEquals(
            listOf(
                "IMPORT_ROLE:common@site/shared-roles/common#main",
                "DEPENDENCY:base@site/roles/base#main",
                "DEPENDENCY:common@site/shared-roles/common#main[common, deps]",
                "DEPENDENCY:common@site/shared-roles/common#main",
                "PLAY_ROLE:web@site/roles/web#main",
                "PLAY_ROLE:base@site/roles/base#main[base, always]",
                "DEPENDENCY:base@site/roles/base#main",
                "DEPENDENCY:common@site/shared-roles/common#main[common, deps]",
                "DEPENDENCY:common@site/shared-roles/common#main",
                "INCLUDE_ROLE:web@site/roles/web#extra",
            ),
            web.roles.map(::summary),
        )
        assertEquals(
            listOf(PlaySection.PRE_TASKS) + List(5) { PlaySection.ROLES } + List(4) { PlaySection.TASKS },
            web.roles.map { it.section },
        )
        assertEquals("../shared-roles/common", web.roles[3].written)

        assertEquals("import cycles are cut", listOf("Web", "A", "B"), graph.executionOrder(playbook).map { it.name })
        val cycle = graph.playsOf(vf("site/playbook-cycle.yml")).single()
        assertEquals(
            listOf("DEPENDENCY:cycle-b@site/roles/cycle-b#main", "PLAY_ROLE:cycle-a@site/roles/cycle-a#main"),
            cycle.roles.map(::summary),
        )
        assertEquals(listOf("Web"), graph.playsApplying(site, "web", "extra").map { it.name })
        assertEquals(listOf("A", "Web"), graph.playsApplying(site, "common").map { it.name })
    }

    fun testGraphFollowsEdits() {
        ModelFixture.copyModelRoles(myFixture)
        val site = ModelFixture.root(myFixture, "site")
        assertEquals(emptyList<PlayRef>(), graph.playsApplying(site, "specinmeta"))
        val playbook = ModelFixture.yaml(myFixture, "site/playbook-cycle.yml")
        WriteCommandAction.runWriteCommandAction(project) {
            val document = PsiDocumentManager.getInstance(project).getDocument(playbook)!!
            document.insertString(document.textLength, "    - specinmeta\n")
            PsiDocumentManager.getInstance(project).commitDocument(document)
        }
        assertEquals(listOf("Cycle"), graph.playsApplying(site, "specinmeta").map { it.name })
    }

    // ------------------------------------------------------------------------------------------------ incremental caching

    /** Appends [text] to the document of [path] and commits it, as typing does. */
    private fun type(path: String, text: String) {
        WriteCommandAction.runWriteCommandAction(project) {
            val document = PsiDocumentManager.getInstance(project).getDocument(ModelFixture.yaml(myFixture, path))!!
            document.insertString(document.textLength, text)
            PsiDocumentManager.getInstance(project).commitDocument(document)
        }
    }

    fun testUnrelatedEditsKeepAPlaybookGraph() {
        ModelFixture.copyModelRoles(myFixture)
        val playbook = vf("site/playbook-site.yml")
        val before = graph.playsOf(playbook)
        type("site/roles/base/defaults/main.yml", "# typed\n")
        type("site/playbook-cycle.yml", "# typed\n")
        assertSame("a YAML edit elsewhere keeps the graph of this playbook", before, graph.playsOf(playbook))

        // The meta/main.yml of a role the playbook expands is an input of its graph.
        type("site/roles/web/meta/main.yml", "  - specinmeta\n")
        val after = graph.playsOf(playbook)
        assertNotSame(before, after)
        val web = after.single().roles
        assertEquals("DEPENDENCY:specinmeta@site/roles/specinmeta#main", summary(web[web.indexOfFirst { it.name == "web" } - 1]))
    }

    fun testNewFilesAreSeenWithoutAStructureChange() {
        ModelFixture.copyModelRoles(myFixture)
        val site = ModelFixture.root(myFixture, "site")
        val before = graph.playbooks(site)
        assertFalse(before.any { it.name == "site.yml" })

        // `site.yml` is a playbook only by its content (no `playbook-` prefix), so no structure change announces it.
        val created = myFixture.tempDirFixture.createFile("site/site.yml", "- hosts: all\n  roles:\n    - fresh\n")
        assertTrue("a new YAML file in the root is classified", created in graph.playbooks(site))
        assertNull("no role fresh yet", graph.playsOf(created).single().roles.single().role)

        myFixture.tempDirFixture.createFile("site/fresh/tasks/main.yml", "- ansible.builtin.ping:\n")
        assertEquals("a role next to the playbook resolves once it exists", vf("site/fresh"), graph.playsOf(created).single().roles.single().role?.dir)

        // A saved content change can turn a vars-like file into a playbook (the probe reads saved content).
        val notes = myFixture.tempDirFixture.createFile("site/notes.yml", "a: 1\n")
        assertFalse(notes in graph.playbooks(site))
        WriteCommandAction.runWriteCommandAction(project) { VfsUtil.saveText(notes, "- hosts: web\n  roles:\n    - web\n") }
        assertTrue("saved content is probed again", notes in graph.playbooks(site))
        assertEquals(listOf("web"), graph.playsOf(notes).single().roles.filter { it.kind == RoleEntryKind.PLAY_ROLE }.map { it.name })
    }

    fun testANewVarsFilesTargetInAnExistingDirectoryIsSeen() {
        ModelFixture.copyModelRoles(myFixture)
        // `vars/` is no structural directory: creating a file in it changes the file tree only.
        myFixture.tempDirFixture.createFile("site/vars/common.yml", "a: 1\n")
        val playbook = myFixture.tempDirFixture.createFile("site/playbook-extra.yml", "- hosts: all\n  vars_files:\n    - vars/extra.yml\n")
        assertNull(graph.playsOf(playbook).single().varsFiles.single().file)
        val extra = myFixture.tempDirFixture.createFile("site/vars/extra.yml", "b: 2\n")
        assertEquals(extra, graph.playsOf(playbook).single().varsFiles.single().file)
    }

    fun testToolDirectoriesAndFilesOutsideTheContentKeepTheGraph() {
        ModelFixture.copyModelRoles(myFixture)
        val site = ModelFixture.root(myFixture, "site")
        val playbook = vf("site/playbook-site.yml")
        val before = graph.playsOf(playbook)
        val playbooks = graph.playbooks(site)
        myFixture.tempDirFixture.createFile("site/.ansible/tmp/facts.yml", "a: 1\n")
        myFixture.tempDirFixture.createFile("site/.git/objects/ab/cdef", "x")
        assertSame("tool directories are no file-tree change", before, graph.playsOf(playbook))
        assertSame(playbooks, graph.playbooks(site))

        WriteCommandAction.runWriteCommandAction(project) { VfsUtil.saveText(vf("site/roles/base/defaults/main.yml"), "base_x: 2\n") }
        assertSame("a saved content change of a file the graph did not read keeps it", before, graph.playsOf(playbook))
    }

    fun testGraphsAreKeptByTheServiceNotByThePsi() {
        ModelFixture.copyModelRoles(myFixture)
        val playbook = vf("site/playbook-site.yml")
        val before = graph.playsOf(playbook)
        // Dropping the PSI of the playbook (as the garbage collector may for files nobody has open) keeps the graph.
        val files = (PsiManager.getInstance(project) as PsiManagerEx).fileManager
        assertNotNull(files.getCachedPsiFile(playbook))
        WriteCommandAction.runWriteCommandAction(project) { files.setViewProvider(playbook, null) }
        assertNull(files.getCachedPsiFile(playbook))
        assertSame(before, graph.playsOf(playbook))
        assertNull("a cached graph needs no PSI", files.getCachedPsiFile(playbook))
    }
}
