package de.terletzkiy.ansibility.inspections.modules

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.terletzkiy.ansibility.context.AnsibleWorkspaceImpl
import de.terletzkiy.ansibility.context.TargetVersionDetector
import de.terletzkiy.ansibility.fixtures.InfraTestData
import de.terletzkiy.ansibility.inspections.keywords.AnsibleKeywordValueInspection
import de.terletzkiy.ansibility.inspections.keywords.AnsibleUnknownKeywordInspection
import de.terletzkiy.ansibility.semantics.CoreVersion
import de.terletzkiy.ansibility.settings.SettingsTestSupport

/**
 * Base for the module option and keyword inspection tests: sub-trees of the sanitised infra fixture (line numbers
 * identical to the real repo) become Ansible roots of the light project, and the six inspections are enabled.
 */
abstract class ModuleChecksTestCase : BasePlatformTestCase() {
    override fun getTestDataPath(): String = InfraTestData.testDataPath.toString()

    override fun setUp() {
        super.setUp()
        myFixture.enableInspections(*INSPECTIONS.map { it() }.toTypedArray())
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

    /**
     * Copies `infra/<path>` for each path to the same project path and re-detects the roots. `golden/docker` comes
     * along, so every root's target is ansible-core 2.18.8 as in the real repo (its own pin, or the majority pin).
     */
    protected fun copyInfra(vararg paths: String) {
        for (path in paths.toList() + PINS) myFixture.copyDirectoryToProject("${InfraTestData.INFRA}/$path", path)
        refreshRoots()
    }

    /**
     * The fixture copies only the `molecule/` directory of role `mysql-databases`; the real repo's role also has
     * `tasks/`, without which the directory is no role and its molecule playbooks are not classified.
     */
    protected fun completeMysqlDatabasesRole() {
        createFile("$MYSQL_DATABASES/tasks/main.yml", "---\n")
    }

    /** Makes every root target ansible-core 2.18.8, for sub-trees whose roots have no pin of their own (platform). */
    protected fun pinTarget() {
        TargetVersionDetector.getInstance(project).overrideFor = { CoreVersion.PINNED }
    }

    protected fun createFile(path: String, text: String): VirtualFile = myFixture.tempDirFixture.createFile(path, text).also { refreshRoots() }

    protected fun refreshRoots() {
        (AnsibleWorkspaceImpl.getInstance(project) ?: error("AnsibleWorkspace is not AnsibleWorkspaceImpl")).structureChanged()
    }

    /** The highlights of our inspections in [path] as `line: SEVERITY shortName: description`, in offset order. */
    protected fun highlights(path: String): List<String> {
        myFixture.configureFromTempProjectFile(path)
        return infos().map { "${line(it.startOffset)}: ${it.severity.name} ${it.inspectionToolId}: ${it.description}" }
    }

    /** The highlights of our inspections in the open editor. */
    protected fun infos(): List<HighlightInfo> =
        myFixture.doHighlighting().filter { it.inspectionToolId in SHORT_NAMES }.sortedBy { it.startOffset }

    protected fun line(offset: Int): Int = StringUtil.offsetToLineNumber(myFixture.editor.document.charsSequence, offset) + 1

    protected fun lineText(line: Int): String {
        val document = myFixture.editor.document
        return document.charsSequence.subSequence(document.getLineStartOffset(line - 1), document.getLineEndOffset(line - 1)).toString()
    }

    /** The text the highlight covers. */
    protected fun text(info: HighlightInfo): String = myFixture.editor.document.charsSequence.subSequence(info.startOffset, info.endOffset).toString()

    /** Replaces the first occurrence of [old] on 1-based [line] of the open editor with [new] and commits. */
    protected fun type(line: Int, old: String, new: String) {
        val document = myFixture.editor.document
        val start = document.getLineStartOffset(line - 1)
        val index = document.charsSequence.subSequence(start, document.getLineEndOffset(line - 1)).toString().indexOf(old)
        check(index >= 0) { "'$old' not on line $line: ${lineText(line)}" }
        WriteCommandAction.runWriteCommandAction(project) {
            document.replaceString(start + index, start + index + old.length, new)
        }
        PsiDocumentManager.getInstance(project).commitAllDocuments()
    }

    protected fun applyFix(name: String) {
        val fix = myFixture.getAllQuickFixes().firstOrNull { it.text == name } ?: error("no fix '$name' in ${myFixture.getAllQuickFixes().map { it.text }}")
        myFixture.launchAction(fix)
    }

    /** Runs every inspection's `checkFile` on [file] (no editor), as `line: HIGHLIGHT shortName: description`. */
    protected fun check(file: VirtualFile): List<String> = runReadActionBlocking {
        val psi = PsiManager.getInstance(project).findFile(file) ?: return@runReadActionBlocking emptyList()
        val manager = InspectionManager.getInstance(project)
        val text = VfsUtilCore.loadText(file)
        INSPECTIONS.flatMap { create ->
            val inspection = create()
            inspection.checkFile(psi, manager, false).orEmpty().map { problem -> describe(problem, inspection.shortName, text) }
        }
    }

    private fun describe(problem: ProblemDescriptor, shortName: String, text: CharSequence): String {
        val offset = problem.psiElement.textRange.startOffset + (problem.textRangeInElement?.startOffset ?: 0)
        return "${StringUtil.offsetToLineNumber(text, offset) + 1}: ${problem.highlightType} $shortName: ${problem.descriptionTemplate}"
    }

    companion object {
        /** The Dockerfiles that pin ansible-core 2.18.8 for the golden root (and the majority pin for the others). */
        const val PINS = "golden/docker"
        const val MYSQL_DATABASES = "repos/platform/ansible/roles/mysql-databases"
        const val VERIFY = "$MYSQL_DATABASES/molecule/default/verify.yml"

        val INSPECTIONS: List<() -> TaskProblemInspection> = listOf(
            ::AnsibleUnknownModuleOptionInspection, ::AnsibleMissingModuleOptionInspection, ::AnsibleModuleOptionValueInspection,
            ::AnsibleModuleOptionCoercionInspection, ::AnsibleKeywordValueInspection, ::AnsibleUnknownKeywordInspection,
        )

        val SHORT_NAMES: Set<String> = setOf(
            "AnsibleUnknownModuleOption", "AnsibleMissingModuleOption", "AnsibleModuleOptionValue", "AnsibleModuleOptionCoercion",
            "AnsibleKeywordValue", "AnsibleUnknownKeyword",
        )
    }
}
