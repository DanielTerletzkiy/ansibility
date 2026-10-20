package de.terletzkiy.ansibility.navigation

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.ex.IdeDocumentHistory
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** A jump through [EditorJumps] leaves a Back place at its origin, and Back returns there. */
class EditorJumpsTest : BasePlatformTestCase() {
    fun testJumpRecordsBackPlace() {
        val target = myFixture.addFileToProject("roles/web/defaults/main.yml", "port: 80\nname: web\n").virtualFile
        myFixture.configureByText("site.yml", "- <caret>hosts: all\n")
        val origin = myFixture.file.virtualFile
        val history = IdeDocumentHistory.getInstance(project)

        EditorJumps.open(project, target, 9)

        assertEquals(target, FileEditorManager.getInstance(project).selectedFiles.single())
        assertTrue(history.isBackAvailable)
        history.back()
        assertEquals(origin, FileEditorManager.getInstance(project).selectedFiles.first())
        assertTrue(history.isForwardAvailable)
    }
}
