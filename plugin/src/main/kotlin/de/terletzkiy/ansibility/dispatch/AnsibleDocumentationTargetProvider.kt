package de.terletzkiy.ansibility.dispatch

import com.intellij.platform.backend.documentation.DocumentationTarget
import com.intellij.platform.backend.documentation.DocumentationTargetProvider
import com.intellij.psi.PsiFile

/**
 * The one documentation entry point of the plugin (`platform.backend.documentation.targetProvider`, plan A.4):
 * classifies the offset once and returns the first target a `siteDocumentation` extension offers.
 *
 * The platform collects offset-based targets from every provider and falls back to PSI targets (legacy
 * `lang.documentationProvider`s such as SchemaStore's or other Ansible plugins') only when that list is empty,
 * so our target replaces them on Ansible sites without ordering tricks. Outside Ansible roots nothing is
 * classified. Offsets inside injected fragments are mapped to the host file first. While indexing, only
 * `DumbAware` classifiers and documentation extensions are asked (the platform does not filter providers itself).
 */
class AnsibleDocumentationTargetProvider : DocumentationTargetProvider {
    override fun documentationTargets(file: PsiFile, offset: Int): List<DocumentationTarget> {
        val position = SiteDispatch.hostPosition(file, offset)
        if (SiteDispatch.contextOf(position.file) == null) return emptyList()
        val dumbAwareOnly = SiteDispatch.isDumb(file.project)
        val classified = SiteDispatch.classify(position, dumbAwareOnly) ?: return emptyList()
        return listOfNotNull(SiteDispatch.documentation(classified.site, position.file, dumbAwareOnly))
    }
}
