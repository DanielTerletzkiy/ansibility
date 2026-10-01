package de.terletzkiy.ansibility.vars

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.SmartPsiElementPointer
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.AnsibleSite
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.SourceLocation

/**
 * What a variable card or a Ctrl+B request is about: variable [name] of the root at [rootDir], optionally a nested
 * option [path] below it, seen from [file].
 *
 * - [Origin.REFERENCE]: a Jinja reference at [offset]; [Origin.LOCAL]: a reference to a template local;
 * - [Origin.DEFINITION]: a definition written at [offset] (a vars-file key, a spec option, a `register:` value …),
 *   tracked through edits by a smart pointer, so pointers of documentation targets survive PSI changes.
 *
 * A reference to a loop variable's member is documented as the iterated variable's element ([loop] says which loop
 * variable was written and where the loop is; [LoopItems]).
 *
 * Holds no hard PSI references.
 */
internal class VarSubject private constructor(
    val rootDir: VirtualFile,
    val name: String,
    /** Accessors or keys below [name] as written (sequence indices included), e.g. `["0", "port"]`. */
    val path: List<String>,
    val file: VirtualFile,
    private val initialOffset: Int,
    val origin: Origin,
    private val anchor: SmartPsiElementPointer<PsiElement>?,
    /** [initialOffset] minus the anchor's start when the subject was made. */
    private val anchorDelta: Int = 0,
    /** For a reference written through a loop variable: that loop. */
    val loop: LoopVia? = null,
) {
    enum class Origin { REFERENCE, LOCAL, DEFINITION }

    /**
     * The loop variable a reference was written through: `item` ([loopVar]) iterates [iterated]
     * (`grafana_nginx_sites`, or `x.children`) in the loops of [tasks].
     */
    data class LoopVia(val loopVar: String, val iterated: String, val tasks: List<LoopItems.LoopTask>)

    /** Where the reference or definition is now (definitions follow edits). */
    val offset: Int get() = anchor?.range?.startOffset?.plus(anchorDelta) ?: initialOffset

    /** `haproxy_servers.port`: the name and the option names of [path] (sequence indices dropped). */
    val displayName: String get() = (listOf(name) + path.filter { it.toIntOrNull() == null }).joinToString(".")

    val location: SourceLocation get() = SourceLocation(file, offset)

    /** False once the root, the file or the tracked definition is gone. */
    val isValid: Boolean get() = rootDir.isValid && file.isValid && (anchor == null || anchor.element != null)

    /** The same variable with nested [path] (a sub-option link). */
    fun withPath(path: List<String>): VarSubject = VarSubject(rootDir, name, path, file, initialOffset, origin, anchor, anchorDelta, loop)

    /** The same subject, reached through the loop [via]. */
    fun withLoop(via: LoopVia): VarSubject = VarSubject(rootDir, name, path, file, initialOffset, origin, anchor, anchorDelta, via)

    /** The root this subject belongs to, if it still exists. */
    fun root(project: Project): AnsibleRoot? = AnsibleWorkspace.getInstance(project).roots().firstOrNull { it.dir == rootDir }

    override fun toString(): String = "VarSubject($displayName, $origin, ${file.name}:$offset)"

    companion object {
        /** A reference classified by the locator in host [file]. */
        fun reference(file: PsiFile, site: AnsibleSite.VarRef): VarSubject? {
            val virtualFile = file.originalFile.viewProvider.virtualFile
            val root = AnsibleWorkspace.getInstance(file.project).contextOf(virtualFile)?.root ?: return null
            val origin = if (site.name in site.localNames) Origin.LOCAL else Origin.REFERENCE
            return VarSubject(root.dir, site.name, site.attrPath, virtualFile, site.range.startOffset, origin, null)
        }

        /** A reference to [name] of [root] seen from [file] at [offset] (an `O(name)` link in a description). */
        fun referenceTo(root: AnsibleRoot, name: String, path: List<String>, file: VirtualFile, offset: Int): VarSubject =
            VarSubject(root.dir, name, path, file, offset, Origin.REFERENCE, null)

        /** A variable key; the tracked position is the key that names the variable. */
        fun key(keySite: VarKeySites.KeySite): VarSubject? {
            val virtualFile = keySite.file
            val project = keySite.variable.project
            val root = AnsibleWorkspace.getInstance(project).contextOf(virtualFile)?.root ?: return null
            val variableKey = keySite.variable.key ?: return null
            val start = variableKey.textRange.startOffset
            val anchor = keySite.variable.takeIf { it.isPhysical }
            return VarSubject(
                root.dir, keySite.name, keySite.site.keyPath.drop(1), virtualFile, start, Origin.DEFINITION,
                anchor?.let(::pointer), anchor?.let { start - it.textRange.startOffset } ?: 0,
            )
        }

        /** The definition of [name] written at [location] (a navigation target or a link), below [root]. */
        fun definition(project: Project, root: AnsibleRoot, name: String, path: List<String>, location: SourceLocation): VarSubject {
            val element = VarLocations.elementAt(project, location)?.takeIf { it.isPhysical && it !is PsiFile }
            val delta = element?.let { location.offset - it.textRange.startOffset } ?: 0
            return VarSubject(root.dir, name, path, location.file, location.offset, Origin.DEFINITION, element?.let(::pointer), delta)
        }

        private fun pointer(element: PsiElement): SmartPsiElementPointer<PsiElement> =
            SmartPointerManager.getInstance(element.project).createSmartPsiElementPointer(element)
    }
}
