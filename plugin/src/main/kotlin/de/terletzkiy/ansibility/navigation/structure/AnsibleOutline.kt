package de.terletzkiy.ansibility.navigation.structure

import com.intellij.psi.PsiElement
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.FileKind
import de.terletzkiy.ansibility.index.TaskKeywords
import de.terletzkiy.ansibility.navigation.AnsibilityNavigationBundle.message
import org.jetbrains.yaml.psi.YAMLDocument
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping
import org.jetbrains.yaml.psi.YAMLScalar
import org.jetbrains.yaml.psi.YAMLSequence
import org.jetbrains.yaml.psi.YAMLSequenceItem

/**
 * The play → section → block → task outline of an Ansible file (plan X55), read straight from the YAML PSI so it
 * follows unsaved edits. Shared by the structure view and the breadcrumbs.
 */
internal object AnsibleOutline {
    enum class Shape { PLAYS, TASKS }

    enum class NodeKind { PLAY, IMPORT_PLAYBOOK, SECTION, BLOCK, TASK, ROLE }

    /** [element] is the sequence item of a play, task or role entry, or the key-value of a `tasks:`/`block:` section. */
    class Node(val element: PsiElement, val kind: NodeKind, val text: String, val location: String?)

    fun shape(file: YAMLFile): Shape? {
        val virtualFile = file.originalFile.virtualFile ?: return null
        return when (AnsibleWorkspace.getInstance(file.project).contextOf(virtualFile)?.kind) {
            FileKind.PLAYBOOK, FileKind.MOLECULE_PLAYBOOK -> Shape.PLAYS
            FileKind.ROLE_TASKS, FileKind.ROLE_HANDLERS, FileKind.MOLECULE_TASKS -> Shape.TASKS
            else -> null
        }
    }

    fun topLevel(file: YAMLFile, shape: Shape): List<Node> = file.documents.flatMap { document ->
        val sequence = document.topLevelValue as? YAMLSequence ?: return@flatMap emptyList()
        sequence.items.mapNotNull { if (shape == Shape.PLAYS) play(it) else task(it) }
    }

    fun children(node: Node): List<Node> = when (node.kind) {
        NodeKind.PLAY -> sections(node.element, PLAY_SECTIONS)
        NodeKind.BLOCK -> sections(node.element, TaskKeywords.BLOCK_SECTIONS)
        NodeKind.SECTION -> {
            val keyValue = node.element as YAMLKeyValue
            val items = (keyValue.value as? YAMLSequence)?.items.orEmpty()
            if (keyValue.keyText == "roles") items.mapNotNull(::role) else items.mapNotNull(::task)
        }
        else -> emptyList()
    }

    /** The outline node [element] stands for, if it is a play, task, role entry or section in a file of [shape]. */
    fun nodeOf(element: PsiElement, shape: Shape): Node? = when (element) {
        is YAMLSequenceItem -> {
            val holder = element.parent?.parent
            val parentKey = (holder as? YAMLKeyValue)?.keyText
            when {
                holder is YAMLDocument -> if (shape == Shape.PLAYS) play(element) else task(element)
                parentKey == "roles" && shape == Shape.PLAYS -> role(element)
                parentKey in TaskKeywords.PLAY_TASK_SECTIONS || parentKey in TaskKeywords.BLOCK_SECTIONS -> task(element)
                else -> null
            }
        }
        is YAMLKeyValue -> {
            val owner = (element.parent as? YAMLMapping)?.parent as? YAMLSequenceItem
            val ownerNode = owner?.let { nodeOf(it, shape) }
            val sections = when (ownerNode?.kind) {
                NodeKind.PLAY -> PLAY_SECTIONS
                NodeKind.BLOCK -> TaskKeywords.BLOCK_SECTIONS
                else -> emptyList()
            }
            if (element.keyText in sections) section(element) else null
        }
        else -> null
    }

    private fun sections(item: PsiElement, keys: List<String>): List<Node> {
        val mapping = (item as YAMLSequenceItem).value as? YAMLMapping ?: return emptyList()
        return mapping.keyValues.filter { it.keyText in keys }.map(::section)
    }

    private fun section(keyValue: YAMLKeyValue): Node {
        val count = (keyValue.value as? YAMLSequence)?.items?.size ?: 0
        return Node(keyValue, NodeKind.SECTION, keyValue.keyText, message("structure.section.count", count))
    }

    private fun play(item: YAMLSequenceItem): Node? {
        val mapping = item.value as? YAMLMapping ?: return null
        TaskKeywords.IMPORT_PLAYBOOK.firstNotNullOfOrNull { mapping.getKeyValueByKey(it) }?.let {
            return Node(item, NodeKind.IMPORT_PLAYBOOK, message("structure.import.playbook", it.valueText), null)
        }
        val hosts = mapping.getKeyValueByKey("hosts")?.let(::flat)
        val name = mapping.getKeyValueByKey("name")?.valueText?.takeIf { it.isNotBlank() }
        val text = name ?: hosts?.let { message("structure.play.hosts", it) } ?: message("structure.play.unnamed")
        return Node(item, NodeKind.PLAY, text, if (name != null) hosts?.let { message("structure.play.hosts", it) } else null)
    }

    private fun task(item: YAMLSequenceItem): Node? {
        val mapping = item.value as? YAMLMapping ?: return null
        val name = mapping.getKeyValueByKey("name")?.valueText?.takeIf { it.isNotBlank() }
        if (TaskKeywords.BLOCK_SECTIONS.any { mapping.getKeyValueByKey(it) != null }) {
            return Node(item, NodeKind.BLOCK, name ?: message("structure.block"), if (name != null) message("structure.block") else null)
        }
        val action = mapping.keyValues.firstOrNull { !TaskKeywords.isTaskKeyword(it.keyText) }
        val module = action?.keyText
        val target = action?.takeIf { it.keyText in INCLUDES }?.let(::includeTarget)
        val described = listOfNotNull(module, target).joinToString(" ").ifEmpty { null }
        return Node(item, NodeKind.TASK, name ?: described ?: message("structure.task.unnamed"), if (name != null) described else null)
    }

    private fun role(item: YAMLSequenceItem): Node? {
        val name = when (val value = item.value) {
            is YAMLScalar -> value.textValue
            is YAMLMapping -> (value.getKeyValueByKey("role") ?: value.getKeyValueByKey("name"))?.valueText
            else -> null
        }?.takeIf { it.isNotBlank() } ?: return null
        return Node(item, NodeKind.ROLE, name, null)
    }

    /** `include_role: {name: x}`, `import_tasks: x.yml`. */
    private fun includeTarget(action: YAMLKeyValue): String? = when (val value = action.value) {
        is YAMLScalar -> value.textValue
        is YAMLMapping -> (value.getKeyValueByKey("name") ?: value.getKeyValueByKey("file"))?.valueText
        else -> null
    }?.takeIf { it.isNotBlank() }

    private fun flat(keyValue: YAMLKeyValue): String? = when (val value = keyValue.value) {
        is YAMLScalar -> value.textValue
        is YAMLSequence -> value.items.mapNotNull { (it.value as? YAMLScalar)?.textValue }.joinToString(", ")
        else -> null
    }?.takeIf { it.isNotBlank() }

    private val PLAY_SECTIONS = listOf("pre_tasks", "roles", "tasks", "post_tasks", "handlers")

    private val INCLUDES: Set<String> = TaskKeywords.INCLUDE_ROLE + TaskKeywords.IMPORT_ROLE + listOf(
        "include_tasks", "import_tasks", "ansible.builtin.include_tasks", "ansible.builtin.import_tasks",
    )
}
