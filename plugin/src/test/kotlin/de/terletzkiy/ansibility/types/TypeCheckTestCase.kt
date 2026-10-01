package de.terletzkiy.ansibility.types

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileVisitor
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.TypeCheckService
import de.terletzkiy.ansibility.api.TypeFinding
import de.terletzkiy.ansibility.context.AnsibleLayout
import de.terletzkiy.ansibility.context.AnsibleWorkspaceImpl
import de.terletzkiy.ansibility.fixtures.InfraTestData
import de.terletzkiy.ansibility.inspections.types.AnsibleChoiceMismatchInspection
import de.terletzkiy.ansibility.inspections.types.AnsibleCoercedScalarInspection
import de.terletzkiy.ansibility.inspections.types.AnsibleLegacyCoercionInspection
import de.terletzkiy.ansibility.inspections.types.AnsibleMissingRequiredSubOptionInspection
import de.terletzkiy.ansibility.inspections.types.AnsibleNullForOptionalInspection
import de.terletzkiy.ansibility.inspections.types.AnsibleNullForTypedOptionInspection
import de.terletzkiy.ansibility.inspections.types.AnsibleScalarTypeMismatchInspection
import de.terletzkiy.ansibility.inspections.types.AnsibleSpecShapeContradictionInspection
import de.terletzkiy.ansibility.inspections.types.AnsibleStringForNumberOrBoolInspection
import de.terletzkiy.ansibility.inspections.types.AnsibleUnsupportedSubOptionInspection
import de.terletzkiy.ansibility.inspections.types.AnsibleValueRejectedInspection
import de.terletzkiy.ansibility.settings.AnsibilityProjectSettings
import de.terletzkiy.ansibility.settings.RootKeys
import de.terletzkiy.ansibility.settings.RootSettings
import de.terletzkiy.ansibility.settings.SettingsTestSupport

/**
 * Base for the type-check tests: the infra fixture (line numbers identical to the real repo), the eleven type
 * inspections, and helpers to read findings and highlights as `line: code` text.
 */
abstract class TypeCheckTestCase : BasePlatformTestCase() {
    override fun getTestDataPath(): String = InfraTestData.testDataPath.toString()

    override fun tearDown() {
        try {
            SettingsTestSupport.resetAll(project)
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    /** Copies `infra/<path>` for each path to the same project path and re-detects the roots. */
    protected fun copyInfra(vararg paths: String) {
        for (path in paths) myFixture.copyDirectoryToProject("${InfraTestData.INFRA}/$path", path)
        refreshRoots()
    }

    protected fun createFile(path: String, text: String): VirtualFile = myFixture.tempDirFixture.createFile(path, text).also { refreshRoots() }

    protected fun refreshRoots() {
        (AnsibleWorkspaceImpl.getInstance(project) ?: error("AnsibleWorkspace is not AnsibleWorkspaceImpl")).structureChanged()
    }

    protected fun vf(path: String): VirtualFile = myFixture.findFileInTempDir(path) ?: error("missing $path")

    protected fun psi(path: String): PsiFile = runReadActionBlocking { PsiManager.getInstance(project).findFile(vf(path)) } ?: error("no PSI for $path")

    /** The service's findings for [path]. */
    protected fun findings(path: String): List<TypeFinding> = runReadActionBlocking { TypeCheckService.getInstance(project).findings(psi(path)) }

    /** `line: code` for every finding of [path]. */
    protected fun codes(path: String): List<String> {
        val text = VfsUtilCore.loadText(vf(path))
        return findings(path).map { "${StringUtil.offsetToLineNumber(text, it.range.startOffset) + 1}: ${it.code.id}" }
    }

    /** Opens [path] in the editor, enables all type inspections and returns their highlights in file order. */
    protected fun highlights(path: String): List<HighlightInfo> {
        myFixture.enableInspections(*inspections())
        myFixture.configureFromTempProjectFile(path)
        val ids = inspections().map { it.shortName }.toSet()
        return myFixture.doHighlighting().filter { it.inspectionToolId in ids }.sortedBy { it.startOffset }
    }

    protected fun lineOf(info: HighlightInfo): Int = StringUtil.offsetToLineNumber(myFixture.editor.document.charsSequence, info.startOffset) + 1

    protected fun highlightedText(info: HighlightInfo): String =
        myFixture.editor.document.charsSequence.subSequence(info.startOffset, info.endOffset).toString()

    protected fun lineText(line: Int): String {
        val document = myFixture.editor.document
        return document.charsSequence.subSequence(document.getLineStartOffset(line - 1), document.getLineEndOffset(line - 1)).toString()
    }

    /** Changes the settings of the root at [rootPath] (relative to the project). */
    protected fun updateRoot(rootPath: String, transform: (RootSettings) -> RootSettings) {
        AnsibilityProjectSettings.getInstance(project).updateRoot(RootKeys.keyOf(project, vf(rootPath)), transform)
    }

    /**
     * Runs every type inspection's `checkFile` on every YAML file below [base] outside detached worktrees, and returns
     * `path:line SHORT_NAME LEVEL` for each problem, sorted.
     */
    protected fun sweep(base: VirtualFile, levels: Set<ProblemHighlightType> = ALL_LEVELS): List<String> = runReadActionBlocking {
        val workspace = AnsibleWorkspace.getInstance(project)
        val manager = InspectionManager.getInstance(project)
        val tools = inspections()
        val found = ArrayList<String>()
        VfsUtilCore.visitChildrenRecursively(
            base,
            object : VirtualFileVisitor<Unit>() {
                override fun visitFile(file: VirtualFile): Boolean {
                    if (file.isDirectory) return file.name !in SKIPPED && file.name != AnsibleLayout.DOT_GIT
                    val context = workspace.contextOf(file) ?: return true
                    if (context.root.detached) return true
                    val psi = PsiManager.getInstance(project).findFile(file) ?: return true
                    val text = psi.viewProvider.contents
                    for (tool in tools) {
                        for (problem in tool.checkFile(psi, manager, false).orEmpty()) {
                            if (problem.highlightType !in levels) continue
                            val offset = problem.psiElement.textRange.startOffset + (problem.textRangeInElement?.startOffset ?: 0)
                            val line = StringUtil.offsetToLineNumber(text, offset) + 1
                            found += "${VfsUtilCore.getRelativePath(file, base)}:$line ${tool.shortName} ${problem.highlightType.name}"
                        }
                    }
                    return true
                }
            },
        )
        found.sorted()
    }

    companion object {
        val SKIPPED = setOf("node_modules", ".idea", ".gradle", "build")
        val ALL_LEVELS: Set<ProblemHighlightType> = ProblemHighlightType.entries.toSet()

        /** The eleven type inspections, one per code. */
        fun inspections(): Array<LocalInspectionTool> = arrayOf(
            AnsibleValueRejectedInspection(), AnsibleUnsupportedSubOptionInspection(), AnsibleMissingRequiredSubOptionInspection(),
            AnsibleChoiceMismatchInspection(), AnsibleNullForTypedOptionInspection(), AnsibleSpecShapeContradictionInspection(),
            AnsibleCoercedScalarInspection(), AnsibleScalarTypeMismatchInspection(), AnsibleLegacyCoercionInspection(),
            AnsibleNullForOptionalInspection(), AnsibleStringForNumberOrBoolInspection(),
        )
    }
}
