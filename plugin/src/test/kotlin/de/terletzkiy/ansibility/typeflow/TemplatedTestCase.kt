package de.terletzkiy.ansibility.typeflow

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.context.AnsibleWorkspaceImpl
import de.terletzkiy.ansibility.context.TargetVersionDetector
import de.terletzkiy.ansibility.fixtures.InfraTestData
import de.terletzkiy.ansibility.inspections.templated.AnsibleTemplatedValueTypeInspection
import de.terletzkiy.ansibility.semantics.CoreVersion
import de.terletzkiy.ansibility.settings.AnsibilityProjectSettings
import de.terletzkiy.ansibility.settings.RootKeys
import de.terletzkiy.ansibility.settings.RootSettings
import de.terletzkiy.ansibility.settings.SettingsTestSupport
import org.jetbrains.yaml.psi.YAMLFile

/**
 * Base for the ANS-T020 platform tests: sub-trees of the sanitised infra fixture (line numbers identical to the real
 * repo) and synthetic roles become Ansible roots of the light project.
 */
abstract class TemplatedTestCase : BasePlatformTestCase() {
    override fun getTestDataPath(): String = InfraTestData.testDataPath.toString()

    override fun setUp() {
        super.setUp()
        // Since M5 templated values carry injected Jinja: keep the host editor when a file is re-opened with the caret
        // inside a fragment, so offsets and lines stay host-file coordinates (quick fixes still see the fragment).
        myFixture.setCaresAboutInjection(false)
        myFixture.enableInspections(AnsibleTemplatedValueTypeInspection())
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

    protected fun copyInfra(vararg paths: String) {
        for (path in paths) myFixture.copyDirectoryToProject("${InfraTestData.INFRA}/$path", path)
        refreshRoots()
    }

    protected fun createFile(path: String, text: String): VirtualFile =
        myFixture.tempDirFixture.createFile(path, text.trimIndent() + "\n").also { refreshRoots() }

    protected fun refreshRoots() {
        (AnsibleWorkspaceImpl.getInstance(project) ?: error("AnsibleWorkspace is not AnsibleWorkspaceImpl")).structureChanged()
    }

    protected fun vf(path: String): VirtualFile = myFixture.findFileInTempDir(path) ?: error("missing $path")

    /** Pins every root's target ansible-core (the settings hook of `TargetVersionDetector`). */
    protected fun target(version: CoreVersion?) {
        TargetVersionDetector.getInstance(project).overrideFor = { version }
    }

    /** Changes the settings of the root at [rootPath]. */
    protected fun rootSettings(rootPath: String, transform: (RootSettings) -> RootSettings) {
        AnsibilityProjectSettings.getInstance(project).updateRoot(RootKeys.keyOf(project, vf(rootPath)), transform)
    }

    /** The analysis of [path] as `line: message` (no severities, no fixes). */
    protected fun findings(path: String): List<TemplatedFinding> = runReadActionBlocking {
        val file = PsiManager.getInstance(project).findFile(vf(path)) as YAMLFile
        val context = AnsibleWorkspace.getInstance(project).contextOf(vf(path)) ?: error("no context for $path")
        TemplatedValueTypes(project, file, context).findings()
    }

    protected fun lineOf(path: String, offset: Int): Int = StringUtil.offsetToLineNumber(VfsUtilCore.loadText(vf(path)), offset) + 1

    /** The ANS-T020 highlights of [path] in the editor. */
    protected fun highlights(path: String): List<HighlightInfo> {
        myFixture.configureFromTempProjectFile(path)
        return myFixture.doHighlighting().filter { it.inspectionToolId == AnsibleTemplatedValueTypeInspection.SHORT_NAME }.sortedBy { it.startOffset }
    }

    protected fun line(info: HighlightInfo): Int = StringUtil.offsetToLineNumber(myFixture.editor.document.charsSequence, info.startOffset) + 1

    protected fun lineText(line: Int): String {
        val document = myFixture.editor.document
        return document.charsSequence.subSequence(document.getLineStartOffset(line - 1), document.getLineEndOffset(line - 1)).toString()
    }

    companion object {
        const val HAPROXY_DEFAULTS = "golden/roles/haproxy/defaults/main.yml"

        /** A synthetic role with one option per documented type, plus the variables its chains read. */
        const val DEMO_SPEC = """
            argument_specs:
              main:
                short_description: Typed options for the ANS-T020 tests
                options:
                  td_str:
                    type: str
                  td_path:
                    type: path
                  td_int:
                    type: int
                  td_float:
                    type: float
                  td_bool:
                    type: bool
                  td_list:
                    type: list
                    elements: str
                  td_dict:
                    type: dict
                  td_raw:
                    type: raw
                  td_names:
                    type: list
                    elements: str
                  td_servers:
                    type: list
                    elements: dict
                    options:
                      name:
                        type: str
                      port:
                        type: int
        """

        const val DEMO_DEFAULTS = """
            td_port: 8080
            td_items: [a, b]
            td_name: web
            td_started: 2024-01-01
            td_alias: '{{ td_port }}'
            td_mixed: 5
            td_cycle_a: '{{ td_cycle_b }}'
            td_cycle_b: '{{ td_cycle_a }}'
        """
    }
}
