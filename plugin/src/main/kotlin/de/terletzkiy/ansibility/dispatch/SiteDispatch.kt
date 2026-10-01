package de.terletzkiy.ansibility.dispatch

import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.platform.backend.documentation.DocumentationTarget
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import de.terletzkiy.ansibility.api.AnsibleSite
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.FileContext
import de.terletzkiy.ansibility.api.FileKind
import de.terletzkiy.ansibility.api.SiteClassifier
import de.terletzkiy.ansibility.api.SiteDocumentation
import de.terletzkiy.ansibility.api.SiteNavigation

/**
 * A caret position in a top-level (host) file. Positions inside injected fragments are mapped to their host
 * first, so [AnsibleSite.range]s are always in host-file coordinates (the `api.Sites` contract).
 */
data class HostPosition(val file: PsiFile, val offset: Int)

/** An [AnsibleSite] and the [SiteClassifier] extension that produced it (shown by X75 for bug reports). */
data class ClassifiedSite(val site: AnsibleSite, val classifier: SiteClassifier)

/**
 * The single classification step shared by the three global-precedence dispatchers and X75 (plan A.4):
 * the `siteClassifier` extensions are asked in their `order`, the first non-null [AnsibleSite] wins, and the site
 * is handed to the `siteDocumentation` / `siteNavigation` / `completionSource` extensions.
 *
 * Extensions run through `computeSafeIfAny`/`forEachExtensionSafe`, so one failing feature is logged and skipped
 * instead of breaking hover, Ctrl+B and completion for every other feature. With `dumbAwareOnly` (quick doc and
 * completion while indexing) only extensions that implement `DumbAware` are asked; index-backed ones would fail.
 * All functions need a read lock.
 */
object SiteDispatch {

    /** Maps [offset] in [file] to its top-level file; a non-injected file maps to itself. */
    fun hostPosition(file: PsiFile, offset: Int): HostPosition {
        val manager = InjectedLanguageManager.getInstance(file.project)
        val host = manager.getTopLevelFile(file) ?: file
        if (host == file) return HostPosition(file, offset)
        return HostPosition(host, manager.injectedToHost(file, offset))
    }

    /** The Ansible context of a host [file] (completion copies are mapped to their original), or null outside every root. */
    fun contextOf(file: PsiFile): FileContext? {
        val original = file.originalFile
        if (original.project.isDisposed) return null
        val virtualFile = original.viewProvider.virtualFile
        return AnsibleWorkspace.getInstance(original.project).contextOf(virtualFile)
    }

    /**
     * True when completion should consult the `completionSource` extensions: the file is inside a root and has
     * an Ansible [FileKind] (anything but [FileKind.OTHER]).
     */
    fun isCompletionTarget(context: FileContext?): Boolean = context != null && context.kind != FileKind.OTHER

    /** True while [project] is indexing, i.e. when only `DumbAware` extensions may be asked by quick doc and completion. */
    fun isDumb(project: Project): Boolean = DumbService.isDumb(project)

    /** Whether [extension] may run: always in smart mode, and in dumb mode only when it is `DumbAware`. */
    fun mayRun(extension: Any, dumbAwareOnly: Boolean): Boolean = !dumbAwareOnly || DumbService.isDumbAware(extension)

    /** The first non-null classification of [position]; the caller has checked that the file is inside a root. */
    fun classify(position: HostPosition, dumbAwareOnly: Boolean = false): ClassifiedSite? =
        SiteClassifier.EP_NAME.computeSafeIfAny { classifier ->
            ProgressManager.checkCanceled()
            if (!mayRun(classifier, dumbAwareOnly)) return@computeSafeIfAny null
            classifier.classify(position.file, position.offset)?.let { ClassifiedSite(it, classifier) }
        }

    /** The first non-null documentation target for [site]. */
    fun documentation(site: AnsibleSite, file: PsiFile, dumbAwareOnly: Boolean = false): DocumentationTarget? =
        SiteDocumentation.EP_NAME.computeSafeIfAny { documentation ->
            ProgressManager.checkCanceled()
            if (!mayRun(documentation, dumbAwareOnly)) return@computeSafeIfAny null
            documentation.documentation(site, file)
        }

    /**
     * The first non-empty target list for [site], without duplicates, nearest first. Go to Declaration asks every
     * extension even while indexing: the platform runs its handlers with access to reliable index data.
     */
    fun navigationTargets(site: AnsibleSite, file: PsiFile): List<PsiElement> = SiteNavigation.EP_NAME.computeSafeIfAny {
        ProgressManager.checkCanceled()
        it.targets(site, file).takeIf { targets -> targets.isNotEmpty() }?.distinct()
    }.orEmpty()
}
