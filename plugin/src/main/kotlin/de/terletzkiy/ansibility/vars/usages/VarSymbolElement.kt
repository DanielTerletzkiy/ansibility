package de.terletzkiy.ansibility.vars.usages

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.impl.FakePsiElement
import com.intellij.util.concurrency.AppExecutorUtil
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.SourceLocation
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.Callable
import javax.swing.Icon

/**
 * Which occurrences of a name one [VarSymbolElement] stands for (plan amendment FU, D-FU5).
 */
internal sealed interface VarScope {
    /**
     * A variable of the root: every definition and use of the name in the root's family, plus the nested playbook
     * roots that read the root's inventory. [home] is the file the search started from; when the name turns out to be
     * a runtime name no role declares (`register`, `set_fact`, task vars), occurrences outside [home]'s owners form
     * their own group. [home] is presentation only: symbols differing only in it are equal.
     */
    class Root(val home: VirtualFile?) : VarScope

    /**
     * A loop variable (`item`, a `loop_var`, an `index_var`, `ansible_loop`): the bodies of the looping [tasks] (their
     * mappings' starts) and the templates they render, plus every use in [file] when it is set: the template the
     * search started in, whoever renders it (molecule renders a `Dockerfile.j2` once per platform, no task does), or
     * the file of an `item` that no loop binds (never a root-wide search for `item`).
     */
    data class Loop(val tasks: List<SourceLocation>, val file: VirtualFile? = null) : VarScope

    /**
     * A member of a mapping variable of the root: the key [path] below the variable in its definitions
     * (`host_ips: {ops-pxe1: …}`) and the reads whose constant accessors start with it (`host_ips['ops-pxe1']`,
     * `host_ips['ops-pxe1'].x`). [home] is presentation only, as for [Root].
     */
    class Member(val home: VirtualFile?, val path: List<String>) : VarScope

    /** A Jinja local (`{% set %}`, a `for` target, a macro parameter): its binding at [binding] in [file], and nothing outside that file. */
    data class Local(val file: VirtualFile, val binding: Int) : VarScope
}

/**
 * The Find Usages target for one variable (F1.10): name [varName] of [root], with the [scope] that decides which
 * occurrences belong to it. One element per (root, name) for root variables, so every caret position that names the
 * same variable searches the same target; loop variables and Jinja locals are their own targets.
 *
 * [getTextRange] stays null (the [FakePsiElement] default): smart pointers then keep the element itself instead of
 * trying to restore a fake element from a text range, and the usage view's target is the instance the search ran
 * with. [anchor] is a physical PSI element (the host file of the caret) that gives the project and the containing file.
 * [navigate] opens the primary declaration (the spec option, else the role default, else the first definition, the
 * loop or the local binding), computed in a background read action, never on the EDT.
 */
class VarSymbolElement internal constructor(
    private val anchor: PsiElement,
    val root: AnsibleRoot,
    private val varName: String,
    internal val scope: VarScope,
) : FakePsiElement() {

    /**
     * The usage-type group of each usage this element's last search produced, by the usage's element (the Find tool
     * window asks [VarUsageTypeProvider] with the element and this target only). Weak keys: PSI that is gone drops out.
     */
    internal val kinds: MutableMap<PsiElement, VarUsageKind> = Collections.synchronizedMap(WeakHashMap())

    /**
     * Records [kind] as the group of the usages on [element]. One element can hold several occurrences (a YAML scalar
     * is one leaf: `msg: "{{ x }} {{ hostvars[h].x }}"`), and the platform asks with the element only: the first
     * direct occurrence names the group, a read by name only an element that holds nothing else.
     */
    internal fun recordKind(element: PsiElement, kind: VarUsageKind) {
        kinds.merge(element, kind) { old, new -> if (old.isByName && !new.isByName) new else old }
    }

    override fun getParent(): PsiElement = anchor

    override fun getName(): String = varName

    override fun getPresentableText(): String = when (scope) {
        is VarScope.Root -> AnsibilityUsagesBundle.message("symbol.variable", varName, root.displayName)
        is VarScope.Member -> AnsibilityUsagesBundle.message("symbol.member", memberText(varName, scope.path), root.displayName)
        is VarScope.Loop -> AnsibilityUsagesBundle.message("symbol.loop", varName, root.displayName)
        is VarScope.Local -> AnsibilityUsagesBundle.message("symbol.local", varName, scope.file.name)
    }

    override fun getLocationString(): String? = null

    override fun getIcon(open: Boolean): Icon = AllIcons.Nodes.Variable

    override fun getContainingFile(): PsiFile? = anchor.containingFile

    override fun isValid(): Boolean = anchor.isValid && root.dir.isValid

    override fun canNavigate(): Boolean = isValid

    override fun canNavigateToSource(): Boolean = canNavigate()

    override fun navigate(requestFocus: Boolean) {
        val project = anchor.project
        ReadAction.nonBlocking(Callable<SourceLocation?> { VarUsageSearch.primaryDeclaration(project, this) })
            .inSmartMode(project)
            .expireWith(project)
            .finishOnUiThread(ModalityState.defaultModalityState()) { location ->
                if (location != null && location.file.isValid) OpenFileDescriptor(project, location.file, location.offset).navigate(requestFocus)
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    /** What two elements must share to be the same target: the root, the name and, except for root variables, the scope. */
    private val identity: Any
        get() = when (scope) {
            is VarScope.Root -> ROOT_IDENTITY
            is VarScope.Member -> scope.path
            is VarScope.Loop, is VarScope.Local -> scope
        }

    override fun equals(other: Any?): Boolean =
        this === other || other is VarSymbolElement && other.root.dir == root.dir && other.varName == varName && other.identity == identity

    override fun hashCode(): Int = (31 * root.dir.hashCode() + varName.hashCode()) * 31 + identity.hashCode()

    override fun toString(): String = "VarSymbolElement($varName @ ${root.dir.name}, ${scope.javaClass.simpleName})"

    internal companion object {
        private val ROOT_IDENTITY = Any()

        /** `host_ips['ops-pxe1']`: the variable with its member path as subscripts. */
        fun memberText(name: String, path: List<String>): String = name + path.joinToString("") { "['$it']" }
    }
}
