package de.terletzkiy.ansibility.workspace

import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.packageDependencies.DependencyValidationManager
import com.intellij.psi.search.scope.packageSet.NamedScope
import com.intellij.psi.search.scope.packageSet.NamedScopeManager
import com.intellij.psi.search.scope.packageSet.NamedScopesHolder
import com.intellij.psi.search.scope.packageSet.PackageSetFactory
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.WorkspaceScopeService
import de.terletzkiy.ansibility.context.AnsibleWorkspaceImpl
import de.terletzkiy.ansibility.fixtures.InfraTestData
import de.terletzkiy.ansibility.settings.SettingsTestSupport

/**
 * Base of the workspace-scope tests: the sanitised infra fixture (golden, repos/falcon, heron, pelican with its nested
 * danger_zone root, platform, wren, and the detached worktree) as the light project's content, and scopes created in
 * either holder the way `.idea/scopes` files define them: recursive file patterns relative to the content root.
 *
 * The light project outlives a test, so tear-down restores both holders' scopes, the per-user state and the service.
 */
abstract class WorkspaceScopeTestCase : BasePlatformTestCase() {
    private lateinit var sharedBefore: Array<NamedScope>
    private lateinit var localBefore: Array<NamedScope>

    override fun getTestDataPath(): String = InfraTestData.testDataPath.toString()

    override fun setUp() {
        super.setUp()
        sharedBefore = shared.editableScopes
        localBefore = local.editableScopes
        service.resetForTests()
    }

    override fun tearDown() {
        try {
            shared.scopes = sharedBefore
            local.scopes = localBefore
            SettingsTestSupport.resetAll(project)
            service.resetForTests()
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    protected val service: WorkspaceScopeServiceImpl
        get() = WorkspaceScopeService.getInstance(project) as WorkspaceScopeServiceImpl

    protected val shared: NamedScopesHolder get() = DependencyValidationManager.getInstance(project)

    protected val local: NamedScopesHolder get() = NamedScopeManager.getInstance(project)

    /**
     * Copies the whole infra fixture, plus the synthetic detached worktree below `checkouts/` (the platform itself
     * excludes `<project>/.claude/worktrees`), and re-detects the roots.
     */
    protected fun copyInfra() {
        myFixture.copyDirectoryToProject(InfraTestData.INFRA, "")
        myFixture.copyDirectoryToProject("${InfraTestData.INFRA}/${InfraTestData.WORKTREE_DIR}", WORKTREE)
        rescan()
    }

    protected fun rescan() {
        AnsibleWorkspaceImpl.getInstance(project)!!.structureChanged()
    }

    /** A scope `id` with the package-set [pattern] in [holder] (the shared one by default). */
    protected fun addScope(id: String, pattern: String, holder: NamedScopesHolder = shared): NamedScope {
        val scope = NamedScope(id, PackageSetFactory.getInstance().compile(pattern))
        holder.addScope(scope)
        return scope
    }

    /** Replaces the scope `id` of [holder] with one over [pattern] (an edit in the Scopes settings). */
    protected fun replaceScope(id: String, pattern: String, holder: NamedScopesHolder = shared): NamedScope {
        val scope = NamedScope(id, PackageSetFactory.getInstance().compile(pattern))
        holder.scopes = holder.editableScopes.filter { it.scopeId != id }.toTypedArray() + scope
        return scope
    }

    protected fun vf(path: String): VirtualFile = myFixture.findFileInTempDir(path) ?: error("missing $path")

    /** The non-detached root displayed as [name] ("falcon", "golden", "pelican › danger_zone/database"). */
    protected fun root(name: String): AnsibleRoot =
        AnsibleWorkspace.getInstance(project).roots().singleOrNull { it.displayName == name && !it.detached }
            ?: error("no root $name; roots: ${AnsibleWorkspace.getInstance(project).roots().map { it.displayName to it.detached }}")

    protected fun names(roots: Collection<AnsibleRoot>): List<String> = roots.map { it.displayName }

    /** The current scope's coverage, computed in a background read action the way consumers call it. */
    protected fun coverage(): ScopeCoverage = runReadActionBlocking { service.currentScope().coverage() }

    /** Pumps the EDT until [condition] holds (background coroutines of the service finish), failing after 10 s. */
    protected fun waitFor(message: String, condition: () -> Boolean) {
        PlatformTestUtil.waitWithEventsDispatching(message, { condition() }, 10)
    }

    companion object {
        /** Where [copyInfra] puts the detached worktree copy. */
        const val WORKTREE: String = "checkouts/${InfraTestData.WORKTREE_DIR}"
    }
}
