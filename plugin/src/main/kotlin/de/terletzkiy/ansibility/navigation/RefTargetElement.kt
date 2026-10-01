package de.terletzkiy.ansibility.navigation

import com.intellij.icons.AllIcons
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.impl.FakePsiElement
import javax.swing.Icon

/**
 * A Go to Declaration target at a position in a file, with a chooser presentation that tells entries apart by role,
 * kind and place (`Restart otel-collector  keycloak · cross-role (play scope) · roles/keycloak/handlers/main.yml:7`),
 * which the YAML element's own presentation (its text and file name) cannot.
 *
 * Navigating opens [file] at [offset]. [getNavigationElement] is the YAML element written there when it starts
 * exactly at [offset], so platform code looking for the source finds it; otherwise the target itself.
 */
class RefTargetElement internal constructor(
    private val anchor: PsiElement,
    val file: VirtualFile,
    val offset: Int,
    private val displayName: String,
    private val locationText: String,
    /** The kind shown in the chooser, e.g. `cross-role (play scope)`. */
    val label: String?,
) : FakePsiElement() {

    override fun getParent(): PsiElement = anchor

    override fun getNavigationElement(): PsiElement = if (anchor.textOffset == offset) anchor else this

    override fun getName(): String = displayName

    override fun getPresentableText(): String = displayName

    override fun getLocationString(): String = locationText

    override fun getIcon(open: Boolean): Icon = AllIcons.Nodes.Function

    override fun getTextRange(): TextRange? = anchor.textRange

    override fun getTextOffset(): Int = offset

    override fun getContainingFile(): PsiFile? = anchor.containingFile

    override fun isValid(): Boolean = anchor.isValid && file.isValid

    override fun canNavigate(): Boolean = file.isValid

    override fun canNavigateToSource(): Boolean = canNavigate()

    override fun navigate(requestFocus: Boolean) {
        OpenFileDescriptor(anchor.project, file, offset).navigate(requestFocus)
    }

    override fun equals(other: Any?): Boolean = this === other || other is RefTargetElement && other.file == file && other.offset == offset

    override fun hashCode(): Int = 31 * file.hashCode() + offset

    override fun toString(): String = "RefTargetElement($displayName @ ${file.name}:$offset)"
}
