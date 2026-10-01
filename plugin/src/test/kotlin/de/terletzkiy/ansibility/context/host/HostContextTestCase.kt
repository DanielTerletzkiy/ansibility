package de.terletzkiy.ansibility.context.host

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.VirtualFile
import de.terletzkiy.ansibility.api.AnsibleContextService
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.EvalTarget
import de.terletzkiy.ansibility.api.HostKey
import de.terletzkiy.ansibility.api.HostScope
import de.terletzkiy.ansibility.api.OutcomeGroup
import de.terletzkiy.ansibility.api.VarDefinition
import de.terletzkiy.ansibility.api.VarService
import de.terletzkiy.ansibility.model.inventory.InventoryTestCase
import de.terletzkiy.ansibility.settings.AnsibilityWorkspaceState

/**
 * Base of the host-context tests: copies the falcon, platform and pelican roots and golden's `postfix` role of the sanitised
 * infra fixture (line numbers are the real repo's) and offers path, line and host helpers.
 */
abstract class HostContextTestCase : InventoryTestCase() {
    protected val context: AnsibleContextService get() = AnsibleContextService.getInstance(project)

    protected val impl: AnsibleContextServiceImpl get() = AnsibleContextServiceImpl.getInstance(project)!!

    override fun setUp() {
        super.setUp()
        AnsibilityWorkspaceState.getInstance(project).loadState(AnsibilityWorkspaceState.StateBean())
        for (repo in listOf(FALCON, PLATFORM, PELICAN)) copyInfraTree(repo)
        copyInfraTree("golden/roles/postfix")
        addFixtureFiles()
        refreshRoots()
    }

    /** Synthetic additions to the fixture, written before the roots are scanned. */
    protected open fun addFixtureFiles() {}

    protected fun add(path: String, text: String): VirtualFile = myFixture.addFileToProject(path, text.trimIndent() + "\n").virtualFile

    /** The offset of the start of 1-based [line] in [path], plus [column]. */
    protected fun offset(path: String, line: Int, column: Int = 0): Int =
        FileDocumentManager.getInstance().getDocument(vf(path))!!.getLineStartOffset(line - 1) + column

    /** The offset of the first occurrence of [text] in [path]. */
    protected fun offsetOf(path: String, text: String): Int {
        val index = FileDocumentManager.getInstance().getDocument(vf(path))!!.text.indexOf(text)
        assertTrue("$text not in $path", index >= 0)
        return index
    }

    protected fun hosts(scope: HostScope): List<String> = scope.hosts.map(::label)

    protected fun label(key: HostKey): String = "${key.environment}/${key.host}"

    protected fun labels(group: OutcomeGroup): List<String> = group.hosts.map(::label)

    protected fun targets(scope: HostScope): List<String> = scope.targets.map(::label)

    protected fun label(target: EvalTarget): String = "${label(target.host)}@${target.play?.name ?: "-"}"

    /** The definition of [name] written at [path]:[line] in [root]. */
    protected fun definition(root: AnsibleRoot, name: String, path: String, line: Int): VarDefinition =
        VarService.getInstance(project).symbol(root, name).definitions.singleOrNull { at(it.location) == "$path:$line" }
            ?: error("no definition of $name at $path:$line: ${VarService.getInstance(project).symbol(root, name).definitions.map { at(it.location) }}")

    protected companion object {
        const val FALCON = "repos/falcon/ansible"
        const val PLATFORM = "repos/platform/ansible"
        const val PELICAN = "repos/pelican/ansible"
        const val DANGER_ZONE = "repos/pelican/ansible/danger_zone/database"
        const val POSTFIX_TEMPLATE = "$FALCON/roles/postfix/templates/main.cf.j2"
        const val FALCON_PLAYBOOK_ALL = "$FALCON/group_vars/all/vars.yml"
        const val FALCON_PROD_ALL = "$FALCON/environments/prod/group_vars/all/vars.yml"
        const val FALCON_TEST_ALL = "$FALCON/environments/test/group_vars/all/vars.yml"
        const val POSTFIX_DEFAULTS = "$FALCON/roles/postfix/defaults/main.yml"
        const val POSTFIX_MOLECULE = "$FALCON/roles/postfix/molecule/default/molecule.yml"
    }
}
