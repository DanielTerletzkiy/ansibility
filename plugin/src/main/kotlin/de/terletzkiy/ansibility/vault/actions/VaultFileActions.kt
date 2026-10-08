package de.terletzkiy.ansibility.vault.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.fileTypes.FileTypeRegistry
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.vault.envelope.VaultEnvelopes
import de.terletzkiy.ansibility.vault.ui.AnsibilityVaultUiBundle.message
import org.jetbrains.annotations.Nls
import org.jetbrains.yaml.YAMLFileType

/**
 * The files and folders an action event selects (the Project view's selection, or the editor's file), and the cheap
 * facts the file actions are shown by: the root, the first bytes of a file, its file type. Nothing here decrypts or
 * parses.
 */
internal object VaultFileSelection {
    /** At most this many selected items are looked at to decide whether an action shows. */
    private const val INSPECTED = 64

    fun of(e: AnActionEvent): List<VirtualFile> =
        e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)?.toList()?.takeIf { it.isNotEmpty() } ?: listOfNotNull(e.getData(CommonDataKeys.VIRTUAL_FILE))

    /** The items of [selection] inside an Ansible root (of the first [INSPECTED]). Read action. */
    fun inRoots(project: Project, selection: List<VirtualFile>): List<VirtualFile> {
        val workspace = AnsibleWorkspace.getInstance(project)
        return selection.asSequence().take(INSPECTED).filter { it.isValid && it.isInLocalFileSystem && workspace.rootFor(it) != null }.toList()
    }

    fun isYaml(file: VirtualFile): Boolean = FileTypeRegistry.getInstance().isFileOfType(file, YAMLFileType.YML)
}

/**
 * The texts of the Ansibility Vault actions by place (plan amendment R19, D141). In a menu they sit in the
 * "Ansibility Vault" submenu (Project view, editor and editor tab menus, Tools) or in New, which name them already, so
 * they show the short text (`Encrypt File`); everywhere else, Find Action and toolbars, they read
 * `Ansibility Vault: Encrypt File`. The template texts are the prefixed ones, so the Keymap shows those too.
 */
internal object VaultActionTexts {
    /** True for a menu: a context menu or popup (Project view, editor, editor tab, New) or the main menu (Tools, File › New). */
    fun inMenu(e: AnActionEvent): Boolean = e.isFromContextMenu || e.isFromMainMenu

    /** [menuText] in a menu, else `Ansibility Vault: ` and [menuText]. */
    @Nls
    fun forPlace(e: AnActionEvent, @Nls menuText: String): String = if (inMenu(e)) menuText else message("file.action.prefixed", menuText)
}

/**
 * Base of the file actions (V6b; F7.5, F7.6, F7.8): shown for a selection inside Ansible roots that holds something
 * [accepts] takes; [VaultFileOperations] does the work, which starts on the EDT with its questions. The text depends on
 * the place ([VaultActionTexts]) and on the selection (counts).
 */
abstract class VaultFileAction : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    final override fun update(e: AnActionEvent) {
        val project = e.project
        val relevant = project?.let { VaultFileSelection.inRoots(it, VaultFileSelection.of(e)).filter(::accepts) }.orEmpty()
        e.presentation.isEnabledAndVisible = relevant.isNotEmpty()
        if (relevant.isNotEmpty()) e.presentation.text = VaultActionTexts.forPlace(e, text(relevant) ?: message(menuKey))
    }

    /** The bundle key of the short text for one file (`Encrypt File`). */
    protected abstract val menuKey: String

    final override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val selection = VaultFileSelection.of(e).filter { it.isValid }
        if (selection.isNotEmpty()) perform(VaultFileOperations.getInstance(project), selection)
    }

    /** True when the action applies to [file] (a file or folder inside a root). */
    protected abstract fun accepts(file: VirtualFile): Boolean

    /** The short text for the [relevant] items when it differs from the one-file text (counts), or null. */
    @Nls
    protected open fun text(relevant: List<VirtualFile>): String? = null

    protected abstract fun perform(operations: VaultFileOperations, selection: List<VirtualFile>)

    /** [many] with the number of files when several are selected, [folder] when a folder is, else null (the one-file text). */
    @Nls
    protected fun counted(relevant: List<VirtualFile>, many: String, folder: String?): String? = when {
        folder != null && relevant.any { it.isDirectory } -> message(folder)
        relevant.size > 1 -> message(many, relevant.size)
        else -> null
    }
}

/** Ansibility Vault › Encrypt File: plaintext files and folders inside a root; encrypted in place (0600). */
class EncryptFileAction : VaultFileAction() {
    override val menuKey: String get() = "file.action.encrypt"

    override fun accepts(file: VirtualFile): Boolean = file.isDirectory || !VaultEnvelopes.isWholeFileVault(file)

    override fun text(relevant: List<VirtualFile>): String? = counted(relevant, "file.action.encrypt.many", "file.action.encrypt.folder")

    override fun perform(operations: VaultFileOperations, selection: List<VirtualFile>) = operations.encrypt(selection)
}

/** Ansibility Vault › Decrypt File in Place…: whole-file vaults and folders inside a root; decrypted in place (0600) after a confirmation. */
class DecryptFileInPlaceAction : VaultFileAction() {
    override val menuKey: String get() = "file.action.decrypt"

    override fun accepts(file: VirtualFile): Boolean = file.isDirectory || VaultEnvelopes.isWholeFileVault(file)

    override fun text(relevant: List<VirtualFile>): String? = counted(relevant, "file.action.decrypt.many", "file.action.decrypt.folder")

    override fun perform(operations: VaultFileOperations, selection: List<VirtualFile>) = operations.decrypt(selection)
}

/** Ansibility Vault › Rekey…: whole-file vaults, YAML files (their `!vault` values) and folders inside a root. */
class RekeyFileAction : VaultFileAction() {
    override val menuKey: String get() = "file.action.rekey"

    override fun accepts(file: VirtualFile): Boolean = file.isDirectory || VaultEnvelopes.isWholeFileVault(file) || VaultFileSelection.isYaml(file)

    override fun text(relevant: List<VirtualFile>): String? = counted(relevant, "file.action.rekey.many", null)

    override fun perform(operations: VaultFileOperations, selection: List<VirtualFile>) = operations.rekey(selection)
}

/** Ansibility Vault › Change Vault Id…: whole-file vaults, YAML files (their `!vault` values) and folders inside a root. */
class ChangeFileIdAction : VaultFileAction() {
    override val menuKey: String get() = "file.action.change.id"

    override fun accepts(file: VirtualFile): Boolean = file.isDirectory || VaultEnvelopes.isWholeFileVault(file) || VaultFileSelection.isYaml(file)

    override fun text(relevant: List<VirtualFile>): String? = counted(relevant, "file.action.change.id.many", null)

    override fun perform(operations: VaultFileOperations, selection: List<VirtualFile>) = operations.changeId(selection)
}

/**
 * New › Ansibility Vault File (an Ansible Vault file), in a directory of an Ansible root (or the directory of a selected
 * file); Find Action and the Keymap call it `Ansibility Vault: New Vault File` (D142).
 */
class NewVaultFileAction : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        val directory = directoryOf(e)
        e.presentation.isEnabledAndVisible = project != null && directory != null && directory.isInLocalFileSystem &&
            AnsibleWorkspace.getInstance(project).rootFor(directory) != null
        if (e.presentation.isVisible) e.presentation.text = if (VaultActionTexts.inMenu(e)) message("file.action.new") else templatePresentation.text
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val directory = directoryOf(e)?.takeIf { it.isValid } ?: return
        VaultFileOperations.getInstance(project).newVaultFile(directory)
    }

    private fun directoryOf(e: AnActionEvent): VirtualFile? = e.getData(CommonDataKeys.VIRTUAL_FILE)?.let { if (it.isDirectory) it else it.parent }
}

/** The "Ansibility Vault" menus (context menus and Tools): shown for a selection inside an Ansible root; the actions decide the rest. */
class VaultFileActionGroup : DefaultActionGroup(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isVisible = project != null && VaultFileSelection.inRoots(project, VaultFileSelection.of(e)).isNotEmpty()
        e.presentation.isHideGroupIfEmpty = true
    }
}
