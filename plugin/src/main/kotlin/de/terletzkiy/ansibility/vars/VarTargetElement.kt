package de.terletzkiy.ansibility.vars

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.impl.FakePsiElement
import de.terletzkiy.ansibility.api.SourceLocation
import de.terletzkiy.ansibility.navigation.EditorJumps
import javax.swing.Icon

/**
 * A Go to Declaration target for a variable (plan F1.4, F1.5, F4.5): the key or name written at [location], with an
 * item presentation that tells chooser entries apart by role, kind, file and line
 * (`postfix_relayhost  postfix · spec · roles/postfix/meta/argument_specs.yml:6`), which YAML's own presentation of
 * a key-value (its value text and file name) cannot.
 *
 * Navigating opens [location] (also in template files typed as plain text, whose PSI has no element per name).
 * [getNavigationElement] is the YAML element written there (a key-value, or the scalar of `register: name`) when it
 * starts exactly at [location], so platform code that looks for the source finds it; otherwise it is the target
 * itself, whose [navigate] opens the exact offset. The Ctrl-hover hint of a single target comes from
 * [VarPsiDocumentationTargetProvider] through [subject].
 *
 * The element written there and the [subject] are resolved on first use (plan amendment R31 fixes, decision 10): a
 * chooser of hundreds of `host_vars` rows parses only the file of the row that is chosen. Until then the target's
 * parent is its file.
 */
class VarTargetElement internal constructor(
    private val project: Project,
    val location: SourceLocation,
    private val displayName: String,
    private val locationText: String,
    subject: () -> VarSubject,
    anchor: () -> PsiElement?,
) : FakePsiElement() {
    /** A target whose element and subject are known already. */
    internal constructor(anchor: PsiElement, location: SourceLocation, displayName: String, locationText: String, subject: VarSubject) :
        this(anchor.project, location, displayName, locationText, { subject }, { anchor })

    private val anchorValue = lazy(anchor)
    private val resolvedAnchor: PsiElement? get() = anchorValue.value

    /** Whether the element written at [location] was looked up (its file parsed) yet. */
    internal val isResolved: Boolean get() = anchorValue.isInitialized()

    internal val subject: VarSubject by lazy(subject)

    private fun file(): PsiFile? = if (location.file.isValid) PsiManager.getInstance(project).findFile(location.file) else null

    override fun getProject(): Project = project

    override fun getManager(): PsiManager = PsiManager.getInstance(project)

    override fun getParent(): PsiElement = file() ?: resolvedAnchor ?: this

    override fun getNavigationElement(): PsiElement = resolvedAnchor?.takeIf { it.textOffset == location.offset } ?: this

    override fun getName(): String = displayName

    override fun getPresentableText(): String = displayName

    override fun getLocationString(): String = locationText

    override fun getIcon(open: Boolean): Icon = AllIcons.Nodes.Variable

    override fun getTextRange(): TextRange = TextRange.from(location.offset, 0)

    override fun getTextOffset(): Int = location.offset

    override fun getContainingFile(): PsiFile? = file()

    override fun isValid(): Boolean = location.file.isValid

    override fun canNavigate(): Boolean = location.file.isValid

    override fun canNavigateToSource(): Boolean = canNavigate()

    override fun navigate(requestFocus: Boolean) {
        EditorJumps.open(project, location.file, location.offset, requestFocus)
    }

    override fun equals(other: Any?): Boolean = this === other || other is VarTargetElement && other.location == location

    override fun hashCode(): Int = location.hashCode()

    override fun toString(): String = "VarTargetElement($displayName @ ${location.file.name}:${location.offset})"
}
