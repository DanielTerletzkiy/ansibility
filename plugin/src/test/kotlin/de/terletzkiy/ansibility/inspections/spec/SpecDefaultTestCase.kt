package de.terletzkiy.ansibility.inspections.spec

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.terletzkiy.ansibility.context.AnsibleWorkspaceImpl
import de.terletzkiy.ansibility.settings.SettingsTestSupport

/**
 * Base of the ANS-S003–S005 tests (plan amendment R23): a synthetic root `site` (`ansible.cfg`, a playbook applying the
 * roles) whose roles the subclasses write with [file]. Highlights come from the registered inspections.
 */
abstract class SpecDefaultTestCase : BasePlatformTestCase() {
    override fun setUp() {
        super.setUp()
        myFixture.setCaresAboutInjection(false)
        file("$ROOT/ansible.cfg", "[defaults]\nroles_path = roles\n")
        addFiles()
        refreshRoots()
    }

    override fun tearDown() {
        try {
            SettingsTestSupport.resetAll(project)
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    /** The subclass's files, written before the roots are detected. */
    protected abstract fun addFiles()

    protected fun file(path: String, text: String): VirtualFile = myFixture.tempDirFixture.createFile(path, text.trimIndent() + "\n")

    protected fun refreshRoots() {
        AnsibleWorkspaceImpl.getInstance(project)!!.structureChanged()
        IndexingTestUtil.waitUntilIndexesAreReady(project)
    }

    protected fun vf(path: String): VirtualFile = myFixture.findFileInTempDir(path) ?: error("missing $path")

    /** The current text of [path] (its document when open). */
    protected fun text(path: String): String {
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        val file = vf(path)
        return FileDocumentManager.getInstance().getCachedDocument(file)?.text ?: VfsUtil.loadText(file)
    }

    /** The highlights of [inspection] in [path] with a description, as `line SEVERITY text`, by offset. */
    protected fun highlights(path: String, inspection: Class<out LocalInspectionTool>): List<String> =
        infos(path, inspection).map { "${line(it.startOffset)} ${severity(it)} ${myFixture.file.text.substring(it.startOffset, it.endOffset)}" }

    protected fun infos(path: String, inspection: Class<out LocalInspectionTool>): List<HighlightInfo> {
        myFixture.enableInspections(inspection)
        myFixture.configureFromExistingVirtualFile(vf(path))
        val marker = when (inspection) {
            AnsibleSpecDefaultMismatchInspection::class.java -> "documented default is never applied"
            AnsibleSpecDefaultNotAppliedInspection::class.java -> "never applies a documented default"
            else -> "documents no default"
        }
        return myFixture.doHighlighting().filter { it.description?.contains(marker) == true }.sortedBy { it.startOffset }
    }

    /** The message of the highlight on 1-based [line] of [path]. */
    protected fun message(path: String, inspection: Class<out LocalInspectionTool>, line: Int): String =
        infos(path, inspection).firstOrNull { line(it.startOffset) == line }?.description ?: error("no highlight on line $line of $path")

    /** The quick fixes of our families offered at the highlight on [line] (after [highlights] or [infos]). */
    protected fun fixesOn(path: String, inspection: Class<out LocalInspectionTool>, line: Int): List<IntentionAction> {
        val info = infos(path, inspection).firstOrNull { line(it.startOffset) == line } ?: error("no highlight on line $line of $path")
        myFixture.editor.caretModel.moveToOffset(info.startOffset)
        return myFixture.availableIntentions.filter { action -> OUR_FIXES.any { action.text.startsWith(it) } }
    }

    protected fun fixNames(path: String, inspection: Class<out LocalInspectionTool>, line: Int): List<String> =
        fixesOn(path, inspection, line).map { it.text }

    /** Applies the fix named [name] at the highlight on [line] of [path]. */
    protected fun apply(path: String, inspection: Class<out LocalInspectionTool>, line: Int, name: String) {
        val fix = fixesOn(path, inspection, line).firstOrNull { it.text == name }
            ?: error("no fix '$name' on line $line of $path: ${fixNames(path, inspection, line)}")
        assertNotNull(myFixture.getIntentionPreviewText(fix))
        myFixture.launchAction(fix)
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        FileDocumentManager.getInstance().saveAllDocuments()
    }

    private fun line(offset: Int): Int = runReadActionBlocking { StringUtil.offsetToLineNumber(myFixture.file.text, offset) + 1 }

    private fun severity(info: HighlightInfo): String = when (info.severity) {
        HighlightSeverity.ERROR -> "ERROR"
        HighlightSeverity.WARNING -> "WARNING"
        HighlightSeverity.WEAK_WARNING -> "WEAK_WARNING"
        HighlightSeverity.INFORMATION -> "INFO"
        else -> info.severity.name
    }

    protected companion object {
        const val ROOT = "site"
        val OUR_FIXES = listOf("Set the documented default", "Remove the documented default", "Add '", "Document the default")
    }
}
