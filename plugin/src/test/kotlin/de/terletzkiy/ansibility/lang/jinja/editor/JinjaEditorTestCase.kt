package de.terletzkiy.ansibility.lang.jinja.editor

import com.intellij.openapi.vfs.VirtualFile
import de.terletzkiy.ansibility.context.AnsibleWorkspaceImpl
import de.terletzkiy.ansibility.lang.jinja.AnsibleJinjaFileType
import de.terletzkiy.ansibility.lang.jinja.template.AnsibleJinjaTemplateTestCase

/**
 * Base of the editor tests: an Ansible project `site/` (with `ansible.cfg` and a role `web`) in the light project, and
 * files opened in the editor with the caret at `<caret>`.
 */
abstract class JinjaEditorTestCase : AnsibleJinjaTemplateTestCase() {
    override fun setUp() {
        super.setUp()
        createFile("site/ansible.cfg", "[defaults]\n")
        createFile("site/roles/web/tasks/main.yml", "- ansible.builtin.debug:\n    msg: hello\n")
        dispatchEvents()
        refreshRoots()
    }

    /** Creates [path] with [text] (`<caret>` marks the caret) and opens it in the editor. */
    protected fun open(path: String, text: String): VirtualFile {
        val caret = text.indexOf(CARET)
        val file = createFile(path, text.replace(CARET, ""))
        refreshRoots()
        myFixture.configureFromExistingVirtualFile(file)
        if (caret >= 0) myFixture.editor.caretModel.moveToOffset(caret)
        return file
    }

    /** Opens a template of role `web` (`site/roles/web/templates/<name>`). */
    protected fun openTemplate(name: String, text: String): VirtualFile =
        open("site/roles/web/templates/$name", text).also { assertSame(name, AnsibleJinjaFileType, it.fileType) }

    protected fun refreshRoots() {
        (AnsibleWorkspaceImpl.getInstance(project) ?: error("AnsibleWorkspace is not AnsibleWorkspaceImpl")).structureChanged()
    }

    protected companion object {
        const val CARET = "<caret>"
    }
}
