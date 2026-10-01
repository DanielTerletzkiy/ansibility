package de.terletzkiy.ansibility.context

import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileVisitor
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.terletzkiy.ansibility.api.FileKind
import de.terletzkiy.ansibility.api.RootKind
import de.terletzkiy.ansibility.semantics.CoreVersion

/**
 * Opt-in check against the real infra repo (read-only): set `ANSIBLE_INFRA_REPO` to its path. Without the
 * variable every test returns immediately. Expected numbers come from the research reports.
 */
class ContextCorpusTest : BasePlatformTestCase() {
    private val repo: VirtualFile? by lazy {
        System.getenv("ANSIBLE_INFRA_REPO")?.let { LocalFileSystem.getInstance().refreshAndFindFileByPath(it) }
    }

    /** The exclusions of `.idea/ansible-golden.iml`; the platform's own `.claude/worktrees` rule is off. */
    private fun scan(repo: VirtualFile): RootScan =
        RootDetector(isExcluded = { VfsUtilCore.getRelativePath(it, repo) in setOf("patches", ".idea/dictionaries") })
            .detect(listOf(repo))

    fun testRootsOfTheRealRepo() {
        val repo = repo ?: return
        val scan = scan(repo)
        val normal = scan.roots.filter { !it.detached }
        assertEquals(
            listOf("falcon", "heron", "pelican", "platform", "raven", "tern", "thrush", "wren"),
            normal.filter { it.kind == RootKind.PROJECT }.map { it.displayName },
        )
        assertEquals(listOf("golden"), normal.filter { it.kind == RootKind.ROLE_LIBRARY }.map { it.displayName })
        assertEquals(
            listOf("pelican › danger_zone/database", "wren › danger_zone/database"),
            normal.filter { it.kind == RootKind.NESTED_PLAYBOOK }.map { it.displayName },
        )
        assertEquals(listOf("feature-x-1a2b3c"), scan.worktrees.map { it.name })
        assertTrue("the worktree copy is detached", scan.roots.filter { it.detached }.all { it.displayName.startsWith("[feature-x-1a2b3c] ") })
        assertEquals(
            "8 project roots · golden · 2 danger-zone · 1 detached worktree",
            RootsSummary.of(scan.roots, scan.worktrees).text(),
        )
        assertFalse(scan.truncated)
    }

    fun testFileKindsOfTheRealRepo() {
        val repo = repo ?: return
        val scan = scan(repo)
        val classifier = AnsibleFileClassifier()
        val counts = HashMap<FileKind?, Int>()
        val rootDirs = scan.roots.map { it.dir }.toSet()
        for (root in scan.roots.filter { !it.detached }) {
            VfsUtilCore.visitChildrenRecursively(
                root.dir,
                object : VirtualFileVisitor<Unit>() {
                    override fun visitFile(file: VirtualFile): Boolean {
                        if (file.isDirectory) return file.name != ".git" && (file == root.dir || file !in rootDirs)
                        if (scan.rootFor(file) == root) {
                            val kind = classifier.classify(file, root).context?.kind
                            counts[kind] = (counts[kind] ?: 0) + 1
                        }
                        return true
                    }
                },
            )
        }
        assertEquals("argument_specs (research: 311)", 311, counts[FileKind.ROLE_ARGSPEC])
        assertEquals("inventories (research: 32)", 32, counts[FileKind.INVENTORY])
        assertEquals("one ansible.cfg per project root", 8, counts[FileKind.ANSIBLE_CFG])
        assertTrue(counts.getValue(FileKind.GROUP_VARS) > 100)
        assertTrue(counts.getValue(FileKind.HOST_VARS) > 100)
        assertTrue(counts.getValue(FileKind.MOLECULE_CONFIG) >= 300)
        assertTrue(counts.getValue(FileKind.ROLE_TEMPLATE) > 1000)
    }

    fun testAcceptanceFiles() {
        val repo = repo ?: return
        val scan = scan(repo)
        val classifier = AnsibleFileClassifier()
        fun context(path: String) = repo.findFileByRelativePath(path)!!.let { classifier.classify(it, scan.rootFor(it)!!).context!! }

        val template = context("repos/falcon/ansible/roles/postfix/templates/main.cf.j2")
        assertEquals(FileKind.ROLE_TEMPLATE, template.kind)
        assertEquals("falcon", template.root.displayName)
        val versions = TargetVersionDetector.resolve(scan.roots, { null }, TargetVersionDetector::findPins)
        assertEquals(CoreVersion.PINNED, versions.getValue(template.root.dir).version)
        assertEquals(
            "Ansibility: falcon · All envs · core 2.18.8",
            ContextPresentation.statusText(template.root, versions.getValue(template.root.dir)),
        )

        val haproxy = context("golden/roles/haproxy/tasks/apt.yml")
        assertEquals("Ansibility: golden (role library) · core 2.18.8", ContextPresentation.statusText(haproxy.root, versions.getValue(haproxy.root.dir)))

        val clone = context("repos/pelican/ansible/danger_zone/database/playbook-clone-to-replisync.yml")
        assertEquals(FileKind.PLAYBOOK, clone.kind)
        assertEquals("pelican › danger_zone/database", clone.root.displayName)

        val host = context("repos/falcon/ansible/environments/test/host_vars/preview-dev1.bike.example.de/vars.yml")
        assertEquals("preview-dev1.bike.example.de", host.host)
        assertEquals(FileKind.MOLECULE_TASKS, context("golden/roles/docker-registry/molecule/cleanup/verify_per_repo_tasks.yml").kind)
        val ghost = repo.findFileByRelativePath("golden/roles/role-state/callback_plugins/__pycache__")!!.children.first()
        assertNull("role-state ghosts are skipped", classifier.classify(ghost, scan.rootFor(ghost)!!).context)
    }
}
