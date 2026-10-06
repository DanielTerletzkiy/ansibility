package de.terletzkiy.ansibility.vault.actions

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.SmartPsiElementPointer
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.FileContext
import de.terletzkiy.ansibility.api.FileKind
import de.terletzkiy.ansibility.api.SourceLocation
import de.terletzkiy.ansibility.semantics.vault.EnvelopeParse
import de.terletzkiy.ansibility.semantics.yaml.ScalarStyle
import de.terletzkiy.ansibility.semantics.vault.VaultEnvelope
import de.terletzkiy.ansibility.yaml.YamlPsi
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLScalar
import org.jetbrains.yaml.psi.YAMLScalarText

/**
 * An inline `!vault` value an action works on, captured in a read action when the action starts. The smart pointer
 * finds the scalar again for the write after the asynchronous crypto; [envelopeText] (ciphertext, not secret) tells
 * whether somebody changed the value meanwhile, in which case nothing is written.
 */
internal class VaultValueRef private constructor(
    val project: Project,
    val file: VirtualFile,
    val root: AnsibleRoot,
    private val pointer: SmartPsiElementPointer<YAMLScalar>,
    /** The YAML key holding the value, or null for a sequence item. */
    val keyName: String?,
    /** The value's start (its tag): what the vault services resolve. */
    val location: SourceLocation,
    /** The envelope text as YAML loads it, when the action started. */
    val envelopeText: String,
) {
    /** The scalar now, or null when it is gone. Call in a read action. */
    val scalar: YAMLScalar? get() = pointer.element

    /** The envelope when it parses (else the vault services report the format failure). */
    val envelope: VaultEnvelope? get() = (VaultEnvelope.parse(envelopeText) as? EnvelopeParse.Ok)?.envelope

    override fun toString(): String = "VaultValueRef(${file.name}, $keyName)"

    companion object {
        /** The vault value [scalar] inside an Ansible root, or null. Call in a read action. */
        fun of(scalar: YAMLScalar): VaultValueRef? {
            if (!VaultValuePsi.isVault(scalar)) return null
            val psiFile = scalar.containingFile ?: return null
            val file = psiFile.originalFile.viewProvider.virtualFile
            val project = psiFile.project
            val root = AnsibleWorkspace.getInstance(project).rootFor(file) ?: return null
            val pointer = SmartPointerManager.getInstance(project).createSmartPsiElementPointer(scalar)
            val location = SourceLocation(file, VaultValuePsi.valueStart(scalar))
            return VaultValueRef(project, file, root, pointer, VaultValuePsi.keyName(scalar), location, scalar.textValue)
        }
    }
}

/**
 * A plain YAML value that "Encrypt value" (F7.3) may turn into a `!vault |` block, captured on the EDT when the
 * action starts. [value] is the string YAML loads (the text that gets encrypted); it is already in the open document.
 */
internal class PlainValueRef private constructor(
    val project: Project,
    val file: VirtualFile,
    val root: AnsibleRoot,
    private val pointer: SmartPsiElementPointer<YAMLScalar>,
    val keyName: String,
    /** The environments the file belongs to (several for shared inventory vars), for the env → id mapping (F7.9). */
    val environments: List<String>,
    /** The value as YAML loads it. */
    val value: String,
    /** The value is a plain (unquoted) one-line scalar, typed by YAML 1.1 (`5432` is an int). */
    val isPlainStyle: Boolean,
) {
    /** The scalar now, or null when it is gone. Call in a read action. */
    val scalar: YAMLScalar? get() = pointer.element

    override fun toString(): String = "PlainValueRef(${file.name}, $keyName)"

    companion object {
        /**
         * The kinds of files Ansible's loader reads `!vault` from (F7.3): vars files, inventories, play and task
         * files, role defaults and vars, molecule inventories. Never argument specs, role metadata, templates or
         * tool configuration.
         */
        private val KINDS = setOf(
            FileKind.ROLE_TASKS, FileKind.ROLE_HANDLERS, FileKind.ROLE_DEFAULTS, FileKind.ROLE_VARS,
            FileKind.PLAYBOOK, FileKind.INVENTORY, FileKind.GROUP_VARS, FileKind.HOST_VARS,
            FileKind.MOLECULE_CONFIG, FileKind.MOLECULE_PLAYBOOK, FileKind.MOLECULE_TASKS, FileKind.MOLECULE_VARS,
        )

        /**
         * The directory names under which an otherwise unclassified YAML file is loaded with `!vault` support: a vars
         * file (`vars_files`, `include_vars`) or a playbook-level task file (`include_tasks`, `import_tasks`).
         */
        private val LOADED_DIRECTORIES = setOf("vars", "group_vars", "host_vars", "tasks", "handlers")

        /**
         * True when the key-value [scalar] may be encrypted: an untagged scalar without anchor, not empty, a mapping
         * value (never a key) in an Ansible file kind that loads vault values. PSI and file classification only (no
         * pointer is created), so it is cheap enough for intention availability. Call in a read action.
         */
        fun accepts(scalar: YAMLScalar): Boolean = fileContext(scalar) != null

        /** The value [scalar] when [accepts] holds, with a smart pointer for the later write. Call in a read action. */
        fun of(scalar: YAMLScalar): PlainValueRef? {
            val context = fileContext(scalar) ?: return null
            val keyValue = scalar.parent as YAMLKeyValue
            val file = scalar.containingFile.originalFile.viewProvider.virtualFile
            val project = scalar.project
            val pointer = SmartPointerManager.getInstance(project).createSmartPsiElementPointer(scalar)
            val plain = YamlPsi.contentStart(scalar)?.let(YamlPsi::styleOf) == ScalarStyle.PLAIN
            return PlainValueRef(project, file, context.root, pointer, keyValue.keyText, context.environments, scalar.textValue, plain)
        }

        /** The context of [scalar]'s file when [accepts] holds, else null. */
        fun fileContext(scalar: YAMLScalar): FileContext? {
            val keyValue = scalar.parent as? YAMLKeyValue ?: return null
            if (keyValue.value != scalar || VaultValuePsi.tagOf(scalar) != null || VaultValuePsi.hasAnchor(scalar)) return null
            if (scalar is YAMLScalarText || scalar.textValue.isEmpty()) return null
            val psiFile = scalar.containingFile ?: return null
            val file = psiFile.originalFile.viewProvider.virtualFile
            val context = AnsibleWorkspace.getInstance(psiFile.project).contextOf(file) ?: return null
            return context.takeIf { it.kind in KINDS || it.kind == FileKind.OTHER && isLoadedPath(file, it.root) }
        }

        private fun isLoadedPath(file: VirtualFile, root: AnsibleRoot): Boolean {
            var dir = file.parent
            while (dir != null && dir != root.dir) {
                if (dir.name in LOADED_DIRECTORIES) return true
                dir = dir.parent
            }
            return false
        }
    }
}
