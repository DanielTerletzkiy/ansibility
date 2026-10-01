package de.terletzkiy.ansibility.context

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.FileKind
import de.terletzkiy.ansibility.api.RootKind
import de.terletzkiy.ansibility.api.VarsLayer
import de.terletzkiy.ansibility.context.ContextTestTree.DANGER_ZONE
import de.terletzkiy.ansibility.context.ContextTestTree.EXCLUDED_WT_FALCON
import de.terletzkiy.ansibility.context.ContextTestTree.FALCON
import de.terletzkiy.ansibility.context.ContextTestTree.GOLDEN
import de.terletzkiy.ansibility.context.ContextTestTree.PELICAN
import de.terletzkiy.ansibility.context.ContextTestTree.PLATFORM
import de.terletzkiy.ansibility.context.ContextTestTree.SANDBOX_SITE
import de.terletzkiy.ansibility.context.ContextTestTree.WT_FALCON
import de.terletzkiy.ansibility.context.ContextTestTree.WT_GOLDEN

class AnsibleWorkspaceImplTest : BasePlatformTestCase() {
    private lateinit var workspace: AnsibleWorkspaceImpl

    override fun setUp() {
        super.setUp()
        ContextTestTree.create(myFixture)
        workspace = AnsibleWorkspaceImpl.getInstance(project) ?: error("AnsibleWorkspace is not AnsibleWorkspaceImpl")
        workspace.structureChanged()
    }

    private fun vf(path: String): VirtualFile = myFixture.findFileInTempDir(path) ?: error("missing $path")

    private fun root(path: String): AnsibleRoot = workspace.roots().single { it.dir == vf(path) }

    private fun relative(file: VirtualFile): String = file.path.substringAfter("/src/")

    // ------------------------------------------------------------------ roots

    fun testServiceIsRegisteredForTheApiInterface() {
        assertSame(workspace, AnsibleWorkspace.getInstance(project))
    }

    fun testDetectsAllRootsAndNothingElse() {
        val found = workspace.roots().map { relative(it.dir) to it.kind }
        assertEquals(
            listOf(
                WT_GOLDEN to RootKind.ROLE_LIBRARY,
                WT_FALCON to RootKind.PROJECT,
                GOLDEN to RootKind.ROLE_LIBRARY,
                FALCON to RootKind.PROJECT,
                PELICAN to RootKind.PROJECT,
                DANGER_ZONE to RootKind.NESTED_PLAYBOOK,
                PLATFORM to RootKind.PROJECT,
                SANDBOX_SITE to RootKind.PROJECT,
            ),
            found,
        )
    }

    fun testDetachedRules() {
        assertTrue("worktree under .claude/worktrees", root(WT_FALCON).detached)
        assertTrue("worktree under .claude/worktrees", root(WT_GOLDEN).detached)
        assertTrue(".git file pointing into .git/worktrees", root(SANDBOX_SITE).detached)
        assertFalse(".git file pointing into .git/modules is a submodule", root(FALCON).detached)
        assertFalse(root(PLATFORM).detached)
        assertFalse(root(GOLDEN).detached)
        assertFalse(root(DANGER_ZONE).detached)
        assertEquals("wt-2", workspace.worktreeOf(root(WT_FALCON))?.name)
        assertEquals(vf("checkouts/.claude/worktrees/wt-2"), workspace.worktreeOf(root(WT_GOLDEN))?.dir)
        assertEquals(vf("sandbox/feature-x"), workspace.worktreeOf(root(SANDBOX_SITE))?.dir)
        assertNull(workspace.worktreeOf(root(FALCON)))
        assertEquals(listOf("wt-2", "feature-x"), workspace.detachedWorktrees().map { it.name })
    }

    fun testPlatformExcludedWorktreeStaysInvisible() {
        val worktree = vf(".claude/worktrees/wt-1")
        assertTrue("2026.2 excludes <project>/.claude/worktrees by itself", ProjectFileIndex.getInstance(project).isExcluded(worktree))
        assertNull(workspace.rootFor(vf("$EXCLUDED_WT_FALCON/ansible.cfg")))
        assertNull(workspace.contextOf(vf("$EXCLUDED_WT_FALCON/roles/postfix/tasks/main.yml")))
    }

    fun testExcludingAWorktreeRemovesItsRoots() {
        val worktree = vf("sandbox/feature-x")
        assertTrue(WorktreeExcluder.exclude(project, worktree))
        try {
            assertTrue(ProjectFileIndex.getInstance(project).isExcluded(worktree))
            assertFalse(workspace.roots().any { it.dir == vf(SANDBOX_SITE) })
            assertEquals(listOf("wt-2"), workspace.detachedWorktrees().map { it.name })
        } finally {
            ModuleRootModificationUtil.updateModel(module) { model ->
                model.contentEntries.forEach { entry ->
                    entry.excludeFolders.filter { it.file == worktree }.forEach(entry::removeExcludeFolder)
                }
            }
        }
        assertTrue(workspace.roots().any { it.dir == vf(SANDBOX_SITE) })
    }

    fun testDisplayNames() {
        assertEquals("falcon", root(FALCON).displayName)
        assertEquals("platform", root(PLATFORM).displayName)
        assertEquals("golden", root(GOLDEN).displayName)
        assertEquals("pelican › danger_zone/database", root(DANGER_ZONE).displayName)
        assertEquals("[wt-2] falcon", root(WT_FALCON).displayName)
        assertEquals("[wt-2] golden", root(WT_GOLDEN).displayName)
        assertEquals("[feature-x] site", root(SANDBOX_SITE).displayName)
    }

    fun testRolesDirsEnvironmentsAndParents() {
        assertEquals(listOf(vf("$FALCON/roles")), root(FALCON).rolesDirs)
        assertEquals(vf("$FALCON/environments"), root(FALCON).environmentsDir)
        assertEquals(listOf(vf("$GOLDEN/roles")), root(GOLDEN).rolesDirs)
        assertNull("golden has no inventory", root(GOLDEN).environmentsDir)
        val nested = root(DANGER_ZONE)
        assertEquals(vf(PELICAN), nested.parentDir)
        assertEquals(listOf(vf("$DANGER_ZONE/roles")), nested.rolesDirs)
        assertEquals("nested roots share the parent's inventories", vf("$PELICAN/environments"), nested.environmentsDir)
        assertNull(root(FALCON).parentDir)
    }

    fun testRootForReturnsTheInnermostRoot() {
        assertEquals(root(DANGER_ZONE), workspace.rootFor(vf("$DANGER_ZONE/roles/xtrabackup/tasks/main.yml")))
        assertEquals(root(PELICAN), workspace.rootFor(vf("$PELICAN/danger_zone/README.md")))
        assertEquals(root(FALCON), workspace.rootFor(vf(FALCON)))
        assertNull(workspace.rootFor(vf("docs/README.md")))
        assertNull(workspace.rootFor(vf("patches/roles/nginx/defaults/main.yml")))
        assertNull(workspace.rootFor(vf("node_modules/pkg/ansible.cfg")))
    }

    fun testConfigOfProjectAndNestedRoots() {
        assertEquals("provisioner", workspace.configOf(root(PLATFORM))?.value("defaults", "remote_user"))
        assertSame(workspace.configOf(root(PELICAN)), workspace.configOf(root(DANGER_ZONE)))
        assertNull(workspace.configOf(root(GOLDEN)))
    }

    // ------------------------------------------------------------------ file kinds

    private data class Expected(
        val path: String,
        val kind: FileKind?,
        val root: String? = null,
        val role: String? = null,
        val env: String? = null,
        val group: String? = null,
        val host: String? = null,
        val layer: VarsLayer? = null,
        val scenario: String? = null,
    )

    private val expectations = listOf(
        // role files
        Expected("$FALCON/roles/postfix/tasks/main.yml", FileKind.ROLE_TASKS, FALCON, role = "postfix"),
        Expected("$FALCON/roles/postfix/defaults/main.yml", FileKind.ROLE_DEFAULTS, FALCON, role = "postfix", layer = VarsLayer.ROLE_DEFAULTS),
        Expected("$FALCON/roles/postfix/vars/main.yml", FileKind.ROLE_VARS, FALCON, role = "postfix", layer = VarsLayer.ROLE_VARS),
        Expected("$FALCON/roles/postfix/meta/argument_specs.yml", FileKind.ROLE_ARGSPEC, FALCON, role = "postfix"),
        Expected("$FALCON/roles/postfix/templates/main.cf.j2", FileKind.ROLE_TEMPLATE, FALCON, role = "postfix"),
        Expected("$FALCON/roles/postfix/templates/deployment/docker-compose.yml", FileKind.ROLE_TEMPLATE, FALCON, role = "postfix"),
        Expected("$GOLDEN/roles/haproxy/tasks/apt.yml", FileKind.ROLE_TASKS, GOLDEN, role = "haproxy"),
        Expected("$GOLDEN/roles/haproxy/meta/argument_specs.yml", FileKind.ROLE_ARGSPEC, GOLDEN, role = "haproxy"),
        Expected("$GOLDEN/roles/certs-client/handlers/main.yaml", FileKind.ROLE_HANDLERS, GOLDEN, role = "certs-client"),
        Expected("$GOLDEN/roles/grafana/handlers/molecule.yml", FileKind.ROLE_HANDLERS, GOLDEN, role = "grafana"),
        Expected("$GOLDEN/roles/grafana/meta/main.yml", FileKind.ROLE_META, GOLDEN, role = "grafana"),
        Expected("$GOLDEN/roles/grafana/files/dashboards/keycloak.json", FileKind.ROLE_FILE, GOLDEN, role = "grafana"),
        Expected("$DANGER_ZONE/roles/xtrabackup/tasks/main.yml", FileKind.ROLE_TASKS, DANGER_ZONE, role = "xtrabackup"),
        Expected("$WT_FALCON/roles/postfix/tasks/main.yml", FileKind.ROLE_TASKS, WT_FALCON, role = "postfix"),
        // playbooks
        Expected("$FALCON/playbook-setup-system.yml", FileKind.PLAYBOOK, FALCON),
        Expected("$FALCON/site.yml", FileKind.PLAYBOOK, FALCON),
        Expected("$FALCON/notes.yml", FileKind.OTHER, FALCON),
        Expected("$GOLDEN/playbooks/playbook-setup-system.yml", FileKind.PLAYBOOK, GOLDEN),
        Expected("$DANGER_ZONE/playbook-clone-to-replisync.yml", FileKind.PLAYBOOK, DANGER_ZONE),
        Expected("$PELICAN/playbook-setup-replisync.yml", FileKind.PLAYBOOK, PELICAN),
        Expected("$SANDBOX_SITE/playbook-deploy.yml", FileKind.PLAYBOOK, SANDBOX_SITE),
        // inventories
        Expected("$FALCON/environments/prod/hosts.yml", FileKind.INVENTORY, FALCON, env = "prod"),
        Expected("$PLATFORM/environments/prod/hosts.yaml", FileKind.INVENTORY, PLATFORM, env = "prod"),
        // group_vars: directory form, file form, any file names
        Expected(
            "$FALCON/environments/prod/group_vars/all/vars.yml", FileKind.GROUP_VARS, FALCON,
            env = "prod", group = "all", layer = VarsLayer.INVENTORY_GROUP_VARS_ALL,
        ),
        Expected(
            "$FALCON/environments/prod/group_vars/all/vault.yml", FileKind.GROUP_VARS, FALCON,
            env = "prod", group = "all", layer = VarsLayer.INVENTORY_GROUP_VARS_ALL,
        ),
        Expected(
            "$FALCON/environments/prod/group_vars/keycloak/vars.yml", FileKind.GROUP_VARS, FALCON,
            env = "prod", group = "keycloak", layer = VarsLayer.INVENTORY_GROUP_VARS,
        ),
        Expected(
            "$PLATFORM/environments/prod/group_vars/all.yml", FileKind.GROUP_VARS, PLATFORM,
            env = "prod", group = "all", layer = VarsLayer.INVENTORY_GROUP_VARS_ALL,
        ),
        Expected(
            "$PLATFORM/environments/prod/group_vars/keycloak.yml", FileKind.GROUP_VARS, PLATFORM,
            env = "prod", group = "keycloak", layer = VarsLayer.INVENTORY_GROUP_VARS,
        ),
        Expected(
            "$PLATFORM/environments/prod/group_vars/contracting/mysql_users.yml", FileKind.GROUP_VARS, PLATFORM,
            env = "prod", group = "contracting", layer = VarsLayer.INVENTORY_GROUP_VARS,
        ),
        Expected("$FALCON/group_vars/all/vars.yml", FileKind.GROUP_VARS, FALCON, group = "all", layer = VarsLayer.PLAYBOOK_GROUP_VARS_ALL),
        Expected("$PLATFORM/group_vars/all.yml", FileKind.GROUP_VARS, PLATFORM, group = "all", layer = VarsLayer.PLAYBOOK_GROUP_VARS_ALL),
        Expected(
            "$PLATFORM/group_vars/monitoring_client.yml", FileKind.GROUP_VARS, PLATFORM,
            group = "monitoring_client", layer = VarsLayer.PLAYBOOK_GROUP_VARS,
        ),
        Expected("$FALCON/environments/test/group_vars/all/.gitkeep", FileKind.OTHER, FALCON, env = "test"),
        Expected("$FALCON/environments/test/group_vars/all/notes.md", FileKind.OTHER, FALCON, env = "test"),
        // host_vars: dotted host names, directory and file forms
        Expected(
            "$FALCON/environments/test/host_vars/preview-dev1.bike.example.de/vars.yml", FileKind.HOST_VARS, FALCON,
            env = "test", host = "preview-dev1.bike.example.de", layer = VarsLayer.INVENTORY_HOST_VARS,
        ),
        Expected(
            "$FALCON/environments/test/host_vars/preview-dev2.bike.example.de", FileKind.HOST_VARS, FALCON,
            env = "test", host = "preview-dev2.bike.example.de", layer = VarsLayer.INVENTORY_HOST_VARS,
        ),
        Expected(
            "$FALCON/environments/test/host_vars/test-test1.yml", FileKind.HOST_VARS, FALCON,
            env = "test", host = "test-test1", layer = VarsLayer.INVENTORY_HOST_VARS,
        ),
        Expected(
            "$PLATFORM/environments/prod/host_vars/prod-training1/mysql.yml", FileKind.HOST_VARS, PLATFORM,
            env = "prod", host = "prod-training1", layer = VarsLayer.INVENTORY_HOST_VARS,
        ),
        // molecule
        Expected(
            "$PLATFORM/roles/docker-registry/molecule/cleanup/molecule.yml", FileKind.MOLECULE_CONFIG, PLATFORM,
            role = "docker-registry", scenario = "cleanup",
        ),
        Expected(
            "$PLATFORM/roles/docker-registry/molecule/cleanup/converge.yml", FileKind.MOLECULE_PLAYBOOK, PLATFORM,
            role = "docker-registry", scenario = "cleanup",
        ),
        Expected(
            "$PLATFORM/roles/docker-registry/molecule/cleanup/verify_per_repo_tasks.yml", FileKind.MOLECULE_TASKS, PLATFORM,
            role = "docker-registry", scenario = "cleanup",
        ),
        Expected("$GOLDEN/roles/chronod/molecule/default/prepare.yml", FileKind.MOLECULE_PLAYBOOK, GOLDEN, role = "chronod", scenario = "default"),
        Expected("$GOLDEN/roles/chronod/molecule/default/molecule.yml", FileKind.MOLECULE_CONFIG, GOLDEN, role = "chronod", scenario = "default"),
        Expected("$GOLDEN/roles/chronod/molecule/default/Dockerfile.j2", FileKind.OTHER, GOLDEN, role = "chronod", scenario = "default"),
        Expected("$GOLDEN/roles/chronod/molecule/vars/vars.yml", FileKind.MOLECULE_VARS, GOLDEN, role = "chronod", layer = VarsLayer.MOLECULE_INVENTORY),
        // configuration files
        Expected("$FALCON/ansible.cfg", FileKind.ANSIBLE_CFG, FALCON),
        Expected("$FALCON/ansible-lint.yml", FileKind.LINT_CONFIG, FALCON),
        Expected("$GOLDEN/ansible-lint.yml", FileKind.LINT_CONFIG, GOLDEN),
        Expected("$FALCON/docker/ansible-molecule/requirements.yml", FileKind.REQUIREMENTS, FALCON),
        Expected("$PLATFORM/docker/ansible-molecule/requirements.yml/placeholder.txt", FileKind.OTHER, PLATFORM),
        Expected("$FALCON/docker/ansible-playbook/Dockerfile", FileKind.OTHER, FALCON),
        Expected("$PELICAN/danger_zone/README.md", FileKind.OTHER, PELICAN),
        // skipped
        Expected("$FALCON/roles/role-state/callback_plugins/__pycache__/role_state.cpython-314.pyc", null),
        Expected("$GOLDEN/roles/role-state/callback_plugins/__pycache__/role_state.cpython-314.pyc", null),
        Expected("$GOLDEN/roles/nginx-snippets/handlers/main.yml", null),
        Expected("$GOLDEN/roles/nginx-snippets/templates/site.conf.j2", null),
        Expected("$FALCON/.ansible/roles/cached/tasks/main.yml", null),
        Expected("$GOLDEN/.ansible/roles/.keep", null),
        Expected("patches/roles/nginx/tasks/main.yml.patch", null),
        Expected("patches/roles/nginx/defaults/main.yml", null),
        Expected("docs/README.md", null),
        Expected("node_modules/pkg/ansible.cfg", null),
    )

    fun testClassifiesRepresentativeFiles() {
        assertTrue("at least 25 representative files", expectations.size >= 25)
        val mismatches = expectations.mapNotNull { expected ->
            val context = workspace.contextOf(vf(expected.path))
            val actual = if (context == null) {
                Expected(expected.path, null)
            } else {
                Expected(
                    expected.path, context.kind, relative(context.root.dir), context.roleName, context.environment,
                    context.group, context.host, context.layer, context.moleculeScenarioDir?.name,
                )
            }
            if (actual == expected) null else "expected $expected\n  but was $actual"
        }
        assertTrue(mismatches.joinToString("\n"), mismatches.isEmpty())
    }

    fun testRoleDirIsReported() {
        val context = workspace.contextOf(vf("$GOLDEN/roles/haproxy/tasks/apt.yml"))!!
        assertEquals(vf("$GOLDEN/roles/haproxy"), context.roleDir)
        assertEquals(vf("$PLATFORM/roles/docker-registry/molecule/cleanup"),
            workspace.contextOf(vf("$PLATFORM/roles/docker-registry/molecule/cleanup/converge.yml"))!!.moleculeScenarioDir)
    }

    fun testDirectoriesHaveNoContext() {
        assertNull("a directory named requirements.yml", workspace.contextOf(vf("$PLATFORM/docker/ansible-molecule/requirements.yml")))
        assertNull(workspace.contextOf(vf("$FALCON/roles/postfix")))
    }

    fun testContextIsCachedUntilTheStructureChanges() {
        val file = vf("$FALCON/roles/postfix/tasks/main.yml")
        val first = workspace.contextOf(file)
        assertSame(first, workspace.contextOf(file))
        workspace.structureChanged()
        val second = workspace.contextOf(file)
        assertNotSame(first, second)
        assertEquals(first, second)
    }

    // ------------------------------------------------------------------ invalidation

    fun testNewAnsibleCfgIsPickedUpThroughTheVfsListener() {
        assertNull(workspace.rootFor(vf("docs/README.md")))
        val before = workspace.structureTracker.modificationCount
        myFixture.tempDirFixture.createFile("docs/ansible.cfg", "[defaults]\n")
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        assertTrue("structure tracker must be bumped", workspace.structureTracker.modificationCount > before)
        assertEquals(RootKind.PROJECT, workspace.rootFor(vf("docs/README.md"))?.kind)
    }

    fun testDeletingARoleDirectoryBumpsTheTracker() {
        val before = workspace.structureTracker.modificationCount
        WriteAction.runAndWait<Exception> { vf("$GOLDEN/roles/chronod").delete(this) }
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        assertTrue(workspace.structureTracker.modificationCount > before)
    }

    fun testContentProbeIsRedoneWhenTheFileChanges() {
        val file = vf("$FALCON/notes.yml")
        assertEquals(FileKind.OTHER, workspace.contextOf(file)?.kind)
        WriteAction.runAndWait<Exception> { file.setBinaryContent("- hosts: all\n  tasks: []\n".toByteArray()) }
        assertEquals(FileKind.PLAYBOOK, workspace.contextOf(file)?.kind)
    }

    fun testStructureListenerIsNotified() {
        var notified = 0
        project.messageBus.connect(testRootDisposable).subscribe(AnsibleStructureListener.TOPIC, AnsibleStructureListener { notified++ })
        workspace.structureChanged()
        assertEquals(1, notified)
    }
}
