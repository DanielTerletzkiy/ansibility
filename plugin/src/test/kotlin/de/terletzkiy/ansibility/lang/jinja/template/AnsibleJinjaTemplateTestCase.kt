package de.terletzkiy.ansibility.lang.jinja.template

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.terletzkiy.ansibility.fixtures.InfraTestData
import de.terletzkiy.ansibility.settings.AnsibilityAppSettings
import de.terletzkiy.ansibility.settings.JinjaSettings

/** Base of the template-file tests: infra fixture sub-trees or synthetic files in the light project. */
abstract class AnsibleJinjaTemplateTestCase : BasePlatformTestCase() {
    override fun getTestDataPath(): String = InfraTestData.testDataPath.toString()

    /** Copies `infra/<path>` to the same project path. */
    protected fun copyInfra(vararg paths: String) {
        for (path in paths) myFixture.copyDirectoryToProject("${InfraTestData.INFRA}/$path", path)
    }

    protected fun createFile(path: String, text: String = ""): VirtualFile = myFixture.tempDirFixture.createFile(path, text)

    protected fun vf(path: String): VirtualFile = myFixture.findFileInTempDir(path) ?: error("missing $path")

    /** The view provider PSI uses for [path] (an [AnsibleJinjaFileViewProvider] for templates). */
    protected fun viewProvider(path: String) = PsiManager.getInstance(project).findViewProvider(vf(path)) ?: error("no view provider for $path")

    /** Runs pending EDT events (the file type refresh is scheduled with `invokeLater`) and waits for re-indexing. */
    protected fun dispatchEvents() {
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        IndexingTestUtil.waitUntilIndexesAreReady(project)
    }

    /** Runs [action] with the Jinja settings changed by [transform]; restores them afterwards. */
    protected fun withJinjaSettings(transform: JinjaSettings.() -> JinjaSettings, action: () -> Unit) {
        val service = AnsibilityAppSettings.getInstance()
        val before = service.settings
        service.update { it.copy(jinja = it.jinja.transform()) }
        dispatchEvents()
        try {
            action()
        } finally {
            service.update { before }
            dispatchEvents()
        }
    }

    /** Runs [action] with `*.j2` associated to [type] (the user's `*.j2 → YAML` mapping of D8). */
    protected fun withJ2As(type: FileType, action: () -> Unit) {
        val manager = FileTypeManager.getInstance()
        val previous = manager.getFileTypeByExtension("j2")
        WriteAction.runAndWait<Throwable> { manager.associateExtension(type, "j2") }
        try {
            action()
        } finally {
            if (previous != type) {
                WriteAction.runAndWait<Throwable> {
                    manager.removeAssociatedExtension(type, "j2")
                    if (previous.name != "UNKNOWN") manager.associateExtension(previous, "j2")
                }
            }
        }
    }
}
