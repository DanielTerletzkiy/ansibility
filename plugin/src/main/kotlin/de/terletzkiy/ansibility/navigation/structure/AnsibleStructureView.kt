package de.terletzkiy.ansibility.navigation.structure

import com.intellij.icons.AllIcons
import com.intellij.ide.structureView.StructureViewBuilder
import com.intellij.ide.structureView.StructureViewModel
import com.intellij.ide.structureView.StructureViewModelBase
import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.ide.structureView.TreeBasedStructureViewBuilder
import com.intellij.ide.projectView.PresentationData
import com.intellij.ide.util.treeView.smartTree.TreeElement
import com.intellij.navigation.ItemPresentation
import com.intellij.pom.Navigatable
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import de.terletzkiy.ansibility.navigation.structure.AnsibleOutline.NodeKind
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLSequenceItem
import org.jetbrains.yaml.structureView.YAMLCustomStructureViewFactory
import javax.swing.Icon

/** Structure view of playbooks and task files as plays, sections, blocks and tasks (plan X55); other YAML is left alone. */
class AnsibleStructureViewFactory : YAMLCustomStructureViewFactory {
    override fun getStructureViewBuilder(file: YAMLFile): StructureViewBuilder? {
        val shape = AnsibleOutline.shape(file) ?: return null
        return object : TreeBasedStructureViewBuilder() {
            override fun createStructureViewModel(editor: Editor?): StructureViewModel =
                StructureViewModelBase(file, editor, FileElement(file, shape))
                    .withSuitableClasses(YAMLSequenceItem::class.java, YAMLKeyValue::class.java)

            override fun isRootNodeShown(): Boolean = false
        }
    }
}

private class FileElement(private val file: YAMLFile, private val shape: AnsibleOutline.Shape) : OutlineTreeElement(file) {
    override fun getPresentation(): ItemPresentation = PresentationData(file.name, null, null, null)

    override fun getChildren(): Array<TreeElement> = AnsibleOutline.topLevel(file, shape).map(::OutlineElement).toTypedArray()
}

private class OutlineElement(private val node: AnsibleOutline.Node) : OutlineTreeElement(node.element) {
    override fun getPresentation(): ItemPresentation = PresentationData(node.text, node.location, icon(), null)

    private fun icon(): Icon = when (node.kind) {
        NodeKind.PLAY -> AllIcons.Nodes.Module
        NodeKind.IMPORT_PLAYBOOK -> AllIcons.Nodes.Include
        NodeKind.SECTION -> AllIcons.Nodes.Folder
        NodeKind.BLOCK -> AllIcons.Nodes.Tag
        NodeKind.TASK -> AllIcons.Nodes.Method
        NodeKind.ROLE -> AllIcons.Nodes.Package
    }

    override fun getChildren(): Array<TreeElement> = AnsibleOutline.children(node).map(::OutlineElement).toTypedArray()
}

private abstract class OutlineTreeElement(private val element: PsiElement) : StructureViewTreeElement {
    override fun getValue(): Any = element

    override fun navigate(requestFocus: Boolean) {
        (element as? Navigatable)?.navigate(requestFocus)
    }

    override fun canNavigate(): Boolean = (element as? Navigatable)?.canNavigate() == true

    override fun canNavigateToSource(): Boolean = canNavigate()
}
