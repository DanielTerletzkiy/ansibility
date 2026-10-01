package de.terletzkiy.ansibility.model.play

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.FileKind
import de.terletzkiy.ansibility.api.PlayGraph
import de.terletzkiy.ansibility.api.RoleEntryKind
import de.terletzkiy.ansibility.api.RoleRegistry
import de.terletzkiy.ansibility.fixtures.InfraTestData
import de.terletzkiy.ansibility.model.role.ModelFixture
import java.util.Locale

/**
 * Opt-in check of the role registry and the play graph against the real infra repo (read-only): set
 * `ANSIBLE_INFRA_REPO` to its path. The repo is added as a content root of the test module for the duration of
 * the test. Expected numbers come from the research reports (roles.md §0 and §5, tasks.md §2).
 */
class PlayGraphCorpusTest : BasePlatformTestCase() {
    private val repo: VirtualFile? by lazy {
        System.getenv(InfraTestData.INFRA_REPO_ENV)?.let { LocalFileSystem.getInstance().refreshAndFindFileByPath(it) }
    }

    private fun withRepo(action: (VirtualFile) -> Unit) {
        val repo = repo ?: return
        PsiTestUtil.addContentRoot(module, repo)
        try {
            ModelFixture.rescan(project)
            action(repo)
        } finally {
            PsiTestUtil.removeContentEntry(module, repo)
            ModelFixture.rescan(project)
        }
    }

    fun testRolesAndPlaysOfTheRealRepo() = withRepo { repo ->
        val workspace = AnsibleWorkspace.getInstance(project)
        val graph = PlayGraph.getInstance(project)
        val registry = RoleRegistry.getInstance(project)
        val roots = workspace.roots().filter { !it.detached }
        assertEquals("8 project roots, golden, 2 danger zones", 11, roots.size)

        val roles = roots.flatMap { registry.roles(it) }
        assertEquals("real roles (research: 363 dirs minus the 9 role-state ghosts, i.e. the 354 tasks/main.yml)", 354, roles.size)
        assertEquals("research: 66 names, role-state included", 65, roles.map { it.name }.distinct().size)
        assertFalse(roles.any { it.name == "role-state" })
        assertEquals("argument_specs (research: 311)", 311, roles.count { registry.role(workspaceRoot(it.rootDir), it.name)!!.specFile != null })

        val playbooks = roots.flatMap { graph.playbooks(it) }
        val kinds = playbooks.groupingBy { workspace.contextOf(it)?.kind }.eachCount()
        assertEquals("playbooks (research: 82)", 82, kinds[FileKind.PLAYBOOK])

        val plain = playbooks.filter { workspace.contextOf(it)?.kind == FileKind.PLAYBOOK }
        val entries = plain.flatMap { graph.playsOf(it) }.flatMap { it.roles }
        assertEquals("role entries in playbooks (research: 545)", 545, entries.count { it.kind == RoleEntryKind.PLAY_ROLE })

        val molecule = playbooks.filter { workspace.contextOf(it)?.kind == FileKind.MOLECULE_PLAYBOOK }
        val absolute = molecule.flatMap { graph.playsOf(it) }.flatMap { it.roles }.filter { it.written.startsWith("/ansible/roles/") }
        assertTrue("molecule references roles by container path", absolute.size > 200)
        val unresolved = absolute.filter { it.role == null }.map { "${it.location?.file?.path}: ${it.written}" }
        assertEquals("every /ansible/roles/X resolves to the enclosing roles dir", emptyList<String>(), unresolved)

        val keycloak = graph.playsOf(repo.findFileByRelativePath("golden/playbooks/playbook-setup-keycloak.yml")!!)
            .flatMap { it.roles }.single { it.kind == RoleEntryKind.INCLUDE_ROLE }
        assertEquals("the literal tasks_from value is the entry point", "configure", keycloak.entryPoint)

        val clone = repo.findFileByRelativePath("repos/pelican/ansible/danger_zone/database/playbook-clone-to-replisync.yml")!!
        val order = graph.executionOrder(clone)
        assertEquals(repo.findFileByRelativePath("repos/pelican/ansible"), order.last().playbookDir)
        assertEquals(clone.parent, order.first().playbookDir)

        val agents = plain.flatMap { graph.playsOf(it) }.map { it.roles }.filter { list -> list.any { it.name == "jenkins-agent-docker" } }
        for (list in agents) {
            val agent = list.indexOfFirst { it.name == "jenkins-agent-docker" && it.kind == RoleEntryKind.PLAY_ROLE }
            val dependency = list.indexOfFirst { it.kind == RoleEntryKind.DEPENDENCY && it.name == "docker" && it.requiredBy == "jenkins-agent-docker" }
            assertTrue("docker is expanded before jenkins-agent-docker", dependency in 0 until agent)
        }
        assertTrue(VfsUtilCore.isAncestor(repo, clone, true))
    }

    /**
     * The first [PlayGraph] queries after a YAML edit on the real repo (wave-4 report: the whole graph was rebuilt with
     * a VFS walk on every YAML change, 60–100 ms in the first completion after an edit). The edits go to a scratch
     * file of the test project, never into the read-only repo: any YAML PSI change used to drop the whole graph.
     */
    fun testFirstQueryAfterAnEditOnTheRealRepo() = withRepo { repo ->
        val graph = PlayGraph.getInstance(project)
        val registry = RoleRegistry.getInstance(project)
        val roots = AnsibleWorkspace.getInstance(project).roots().filter { !it.detached && VfsUtilCore.isAncestor(repo, it.dir, false) }
        val golden = roots.single { it.dir == repo.findChild("golden") }
        val scratch = myFixture.tempDirFixture.createFile("scratch/notes.yml", "a: 1\n")
        val roleNames = roots.associateWith { root -> registry.roles(root).map { it.name }.distinct() }
        val narrow = { roots.sumOf { graph.playsApplying(it, "haproxy").size } + graph.playsApplying(golden, "nginx").size }
        val wide = { roleNames.entries.sumOf { (root, names) -> names.sumOf { graph.playsApplying(root, it).size } } }
        val expectedNarrow = narrow()
        val expectedWide = wide()
        val narrowSamples = ArrayList<Long>()
        val wideSamples = ArrayList<Long>()
        repeat(CORPUS_ROUNDS) { round ->
            type(scratch, "b$round: 1\n")
            var start = System.nanoTime()
            assertEquals(expectedNarrow, narrow())
            narrowSamples += (System.nanoTime() - start) / 1_000
            type(scratch, "c$round: 1\n")
            start = System.nanoTime()
            assertEquals(expectedWide, wide())
            wideSamples += (System.nanoTime() - start) / 1_000
        }
        val n = narrowSamples.sorted()
        val w = wideSamples.sorted()
        println(
            "PlayGraph first query after a YAML edit on the real repo (${roots.size} roots, $CORPUS_ROUNDS rounds): " +
                "haproxy+nginx p50 ${ms(n, 50)} ms, p95 ${ms(n, 95)} ms; every role of every root p50 ${ms(w, 50)} ms, p95 ${ms(w, 95)} ms",
        )
    }

    /** Appends [text] to the document of [file] and commits it, as typing does. */
    private fun type(file: VirtualFile, text: String) {
        WriteCommandAction.runWriteCommandAction(project) {
            val documents = PsiDocumentManager.getInstance(project)
            val document = documents.getDocument(PsiManager.getInstance(project).findFile(file)!!)!!
            document.insertString(document.textLength, text)
            documents.commitDocument(document)
        }
    }

    private fun ms(sorted: List<Long>, p: Int): String =
        "%.1f".format(Locale.ROOT, sorted[(sorted.size * p / 100).coerceAtMost(sorted.lastIndex)] / 1000.0)

    private fun workspaceRoot(dir: VirtualFile) = AnsibleWorkspace.getInstance(project).roots().single { it.dir == dir }

    private companion object {
        const val CORPUS_ROUNDS = 20
    }
}
