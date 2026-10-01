package de.terletzkiy.ansibility.vars

import com.intellij.icons.AllIcons
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.impl.FakePsiElement
import de.terletzkiy.ansibility.api.SourceLocation
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
 */
class VarTargetElement internal constructor(
    private val anchor: PsiElement,
    val location: SourceLocation,
    private val displayName: String,
    private val locationText: String,
    internal val subject: VarSubject,
) : FakePsiElement() {

    override fun getParent(): PsiElement = anchor

    override fun getNavigationElement(): PsiElement = if (anchor.textOffset == location.offset) anchor else this

    override fun getName(): String = displayName

    override fun getPresentableText(): String = displayName

    override fun getLocationString(): String = locationText

    override fun getIcon(open: Boolean): Icon = AllIcons.Nodes.Variable

    override fun getTextRange(): TextRange? = anchor.textRange

    override fun getTextOffset(): Int = location.offset

    override fun getContainingFile(): PsiFile? = anchor.containingFile

    override fun isValid(): Boolean = anchor.isValid && location.file.isValid

    override fun canNavigate(): Boolean = location.file.isValid

    override fun canNavigateToSource(): Boolean = canNavigate()

    override fun navigate(requestFocus: Boolean) {
        OpenFileDescriptor(anchor.project, location.file, location.offset).navigate(requestFocus)
    }

    override fun equals(other: Any?): Boolean = this === other || other is VarTargetElement && other.location == location

    override fun hashCode(): Int = location.hashCode()

    override fun toString(): String = "VarTargetElement($displayName @ ${location.file.name}:${location.offset})"
}
