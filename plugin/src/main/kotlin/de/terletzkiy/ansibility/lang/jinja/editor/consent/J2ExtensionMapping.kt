package de.terletzkiy.ansibility.lang.jinja.editor.consent

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileTypes.ExtensionFileNameMatcher
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.UnknownFileType
import de.terletzkiy.ansibility.lang.jinja.AnsibleJinjaFileType

/**
 * The `*.j2` extension mapping of the IDE's file types (plan D8, F2.9): the user's own `*.j2 → YAML` mapping, or
 * whatever else names the extension (PyCharm Professional's bundled `Jinja2`, a plain-text mapping). Inside Ansible
 * roots the Ansible Jinja overrider beats it; outside them it still decides.
 *
 * It is only read on its own; [remove] changes it, and only from an explicit user action.
 */
object J2ExtensionMapping {
    /** The extension, without the dot. */
    const val EXTENSION: String = "j2"

    /** The file type `*.j2` is mapped to, or null when nothing names the extension. */
    fun mappedType(manager: FileTypeManager = FileTypeManager.getInstance()): FileType? {
        val type = manager.getFileTypeByExtension(EXTENSION)
        return type.takeUnless { it == UnknownFileType.INSTANCE || it == AnsibleJinjaFileType }
    }

    /**
     * Removes the `*.j2` association of [type] (the user consented); afterwards `.j2` files outside Ansible roots have
     * no file type until the user picks one. The platform re-types the files itself. Runs a write action, so call it
     * on the EDT. Returns whether the mapping is gone.
     */
    fun remove(type: FileType, manager: FileTypeManager = FileTypeManager.getInstance()): Boolean {
        val matcher = ExtensionFileNameMatcher(EXTENSION)
        ApplicationManager.getApplication().runWriteAction {
            if (matcher in manager.getAssociations(type)) manager.removeAssociation(type, matcher)
        }
        return manager.getFileTypeByExtension(EXTENSION) != type
    }
}
