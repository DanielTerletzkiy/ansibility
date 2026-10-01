package de.terletzkiy.ansibility.inspections.undefined

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.context.AnsibleWorkspaceImpl
import de.terletzkiy.ansibility.context.TargetVersionDetector
import de.terletzkiy.ansibility.fixtures.InfraTestData
import de.terletzkiy.ansibility.settings.AnsibilityProjectSettings
import de.terletzkiy.ansibility.settings.AnsibilityWorkspaceState
import de.terletzkiy.ansibility.settings.RootKeys
import de.terletzkiy.ansibility.settings.RootSettings
import de.terletzkiy.ansibility.settings.SettingsTestSupport

/**
 * Base of the ANS-V003 tests: the falcon root of the sanitised infra fixture (line numbers are the real repo's) with the
 * golden `alloy` role copied to `repos/falcon/ansible/roles/alloy`, as the real repo has it (the fixture's falcon subset does
 * not carry that role yet). Every edit happens on this temp copy.
 */
abstract class UndefinedTestCase : BasePlatformTestCase() {
    override fun getTestDataPath(): String = InfraTestData.testDataPath.toString()

    override fun setUp() {
        super.setUp()
        myFixture.setCaresAboutInjection(false)
        AnsibilityWorkspaceState.getInstance(project).loadState(AnsibilityWorkspaceState.StateBean())
        myFixture.copyDirectoryToProject("${InfraTestData.INFRA}/$FALCON", FALCON)
        myFixture.copyDirectoryToProject("${InfraTestData.INFRA}/golden/roles/alloy", ALLOY)
        addFixtureFiles()
        refreshRoots()
    }

    override fun tearDown() {
        try {
            TargetVersionDetector.getInstance(project).overrideFor = { null }
            SettingsTestSupport.resetAll(project)
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    /** Synthetic additions written before the roots are scanned. */
    protected open fun addFixtureFiles() {}

    protected fun refreshRoots() {
        AnsibleWorkspaceImpl.getInstance(project)!!.structureChanged()
    }

    protected fun vf(path: String): VirtualFile = myFixture.findFileInTempDir(path) ?: error("missing $path")

    protected fun add(path: String, text: String): VirtualFile = myFixture.addFileToProject(path, text.trimIndent() + "\n").virtualFile

    /** Replaces the whole text of [path] (a temp copy), keeping it saved and committed. */
    protected fun write(path: String, text: String) {
        val file = vf(path)
        WriteAction.runAndWait<Throwable> {
            VfsUtil.saveText(file, text)
            FileDocumentManager.getInstance().getDocument(file)?.let { FileDocumentManager.getInstance().reloadFromDisk(it) }
        }
        PsiDocumentManager.getInstance(project).commitAllDocuments()
    }

    /** Replaces the first occurrence of [old] in [path] by [new]. */
    protected fun replace(path: String, old: String, new: String) {
        val text = VfsUtil.loadText(vf(path))
        assertTrue("'$old' not in $path", old in text)
        write(path, text.replaceFirst(old, new))
    }

    /** The 1-based line of [offset] in [path]. */
    protected fun lineOf(path: String, offset: Int): Int = StringUtil.offsetToLineNumber(VfsUtil.loadText(vf(path)), offset) + 1

    /** The findings of [path] as `line KIND name [hosts]`, sorted by offset. */
    protected fun findings(path: String): List<String> = analyse(path).map { describe(path, it) }

    protected fun analyse(path: String): List<UndefinedFinding> = runReadActionBlocking {
        val file = PsiManager.getInstance(project).findFile(vf(path)) ?: error("no PSI for $path")
        val context = AnsibleWorkspace.getInstance(project).contextOf(vf(path)) ?: error("no context for $path")
        PossiblyUndefined(project, file, context).findings()
    }

    protected fun describe(path: String, finding: UndefinedFinding): String {
        val hosts = if (finding.missing.isEmpty()) "" else " " + finding.missing.joinToString(",") { "${it.environment}/${it.host}" }
        return "${lineOf(path, finding.use.nameRange.startOffset)} ${finding.kind} ${finding.name}$hosts"
    }

    /** The ANS-V003 highlights of [path] as `line SEVERITY text`, through the registered inspection. */
    protected fun highlights(path: String): List<String> {
        myFixture.enableInspections(AnsiblePossiblyUndefinedInspection::class.java)
        myFixture.configureFromExistingVirtualFile(vf(path))
        return myFixture.doHighlighting()
            .filter { it.description?.contains("ANS-V003") == true }
            .sortedBy { it.startOffset }
            .map { "${lineOf(path, it.startOffset)} ${severity(it)} ${myFixture.file.text.substring(it.startOffset, it.endOffset)}" }
    }

    private fun severity(info: HighlightInfo): String = when {
        info.severity == HighlightSeverity.ERROR -> "ERROR"
        info.severity == HighlightSeverity.WARNING -> "WARNING"
        else -> info.severity.name
    }

    /** Changes the settings of the root at [rootPath]. */
    protected fun rootSettings(rootPath: String, transform: (RootSettings) -> RootSettings) {
        AnsibilityProjectSettings.getInstance(project).updateRoot(RootKeys.keyOf(project, vf(rootPath)), transform)
    }

    protected companion object {
        const val FALCON = "repos/falcon/ansible"
        const val ALLOY = "$FALCON/roles/alloy"
        const val CONFIG_BASE = "$ALLOY/templates/config-base.alloy.j2"
        const val PROD_ALL = "$FALCON/environments/prod/group_vars/all/vars.yml"
        const val GUARD = "{% if alloy_tenant_api_key is defined and alloy_tenant_api_key %}"
    }
}
