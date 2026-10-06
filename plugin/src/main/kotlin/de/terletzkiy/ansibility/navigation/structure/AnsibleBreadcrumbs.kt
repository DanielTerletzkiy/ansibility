package de.terletzkiy.ansibility.navigation.structure

import com.intellij.psi.PsiElement
import org.jetbrains.yaml.breadcrumbs.YAMLBreadcrumbsInfoProvider
import org.jetbrains.yaml.psi.YAMLFile

/**
 * Breadcrumbs and sticky lines that name plays and tasks (`Deploy web › tasks › Render config`) in playbooks and task
 * files (plan X55); everywhere else, and for plain keys, the YAML plugin's crumbs.
 */
class AnsibleBreadcrumbsProvider : YAMLBreadcrumbsInfoProvider() {
    override fun getElementInfo(element: PsiElement): String = node(element)?.text ?: super.getElementInfo(element)

    override fun getElementTooltip(element: PsiElement): String? =
        node(element)?.let { node -> listOfNotNull(node.text, node.location).joinToString(" \u00B7 ") } ?: super.getElementTooltip(element)

    private fun node(element: PsiElement): AnsibleOutline.Node? {
        val file = element.containingFile as? YAMLFile ?: return null
        val shape = AnsibleOutline.shape(file) ?: return null
        return AnsibleOutline.nodeOf(element, shape)
    }
}
