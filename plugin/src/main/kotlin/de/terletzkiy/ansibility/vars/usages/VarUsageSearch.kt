package de.terletzkiy.ansibility.vars.usages

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiSearchScopeUtil
import com.intellij.psi.search.SearchScope
import com.intellij.usageView.UsageInfo
import com.intellij.util.Processor
import de.terletzkiy.ansibility.api.AnsibleSite
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.FileContext
import de.terletzkiy.ansibility.api.FileKind
import de.terletzkiy.ansibility.api.JinjaContainer
import de.terletzkiy.ansibility.api.LoopVarSite
import de.terletzkiy.ansibility.api.SiteClassifier
import de.terletzkiy.ansibility.api.SourceLocation
import de.terletzkiy.ansibility.context.MoleculeView
import de.terletzkiy.ansibility.dispatch.SiteDispatch
import de.terletzkiy.ansibility.lang.jinja.refs.JinjaIndirectRef
import de.terletzkiy.ansibility.lang.jinja.refs.JinjaIndirection
import de.terletzkiy.ansibility.lang.jinja.refs.JinjaLocal
import de.terletzkiy.ansibility.lang.jinja.refs.JinjaLocalKind
import de.terletzkiy.ansibility.lang.jinja.refs.JinjaRefs
import de.terletzkiy.ansibility.lang.jinja.refs.JinjaRefsResult
import de.terletzkiy.ansibility.model.task.LoopControlInfo
import de.terletzkiy.ansibility.model.task.TaskFileModels
import de.terletzkiy.ansibility.model.task.TaskNode
import de.terletzkiy.ansibility.model.task.YamlFiles
import de.terletzkiy.ansibility.resolve.InlineInventoryDefinitions
import de.terletzkiy.ansibility.resolve.VarViews
import de.terletzkiy.ansibility.resolve.loop.LoopItemTyper
import de.terletzkiy.ansibility.resolve.loop.TaskChains
import de.terletzkiy.ansibility.vars.JinjaTextSites
import de.terletzkiy.ansibility.vars.LoopItems
import de.terletzkiy.ansibility.vars.VarKeySites
import de.terletzkiy.ansibility.vars.VarsSiteClassifier
import de.terletzkiy.ansibility.yaml.YamlPaths
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLKeyValue
import java.util.concurrent.Callable

/**
 * The one place that turns a caret into a [VarSymbolElement] and a symbol into its usages (plan amendment FU,
 * F1.10), shared by the usage target provider, the Find Usages handler, the highlighting handler and the card row.
 *
 * **Caret → symbol** ([symbolAt], needs a read lock): the host position of the caret ([SiteDispatch.hostPosition],
 * so injected fragments and their hosts agree) is classified once ([VarsSiteClassifier]):
 * - a member read by name (FU2: `hostvars[h].x`, `vars['x']`, `lookup('vars', 'x')`;
 *   [JinjaRefsResult.indirectReferenceAt]) → that member, before the root (`vars['x']` classifies as `vars` with a
 *   path); a `hostvars` member is a root variable, never a loop's (a member called `item` gives no symbol);
 * - a Jinja reference to a template local, or a local's binding (`{% set x %}`, a `for` target, a macro parameter)
 *   → that local ([VarScope.Local]); `loop` and macro implicits are no variables;
 * - a reference to a loop variable ([LoopItems]: the task's loop, an include task's loop that runs the file, the loops
 *   that render a template) and a `loop_control.loop_var`/`index_var` value ([LoopVarSite]) → the loop
 *   ([VarScope.Loop], the same target from each); `item` and `ansible_loop` where no loop binds them → their uses in
 *   that file;
 * - any other reference, a top-level variable key and a `register:` value → the root variable ([VarScope.Root]);
 *   nested keys are accessor-level and give no symbol (v1.x);
 * - a name outside the braces of a braced implicit expression (`that: "{{ a }} == b"`, rendered first and then
 *   evaluated; [JinjaRefs.analyzeBracedExpression]) → as a reference, when the `ansible.var.use` indexer records it
 *   there (only implicit-expression keys are evaluated, and the indexer knows which those are).
 *
 * Root variables and members carry the [MoleculeView] of the file the search starts in (plan amendment R20, D153): a
 * search from a production file with "Show Molecule in navigation and search" off lists no Molecule occurrence; one
 * from a Molecule file lists them all. Rename always edits every occurrence ([VarOccurrences.of] with
 * [MoleculeView.INCLUDE]).
 *
 * **Symbol → usages** ([process]): the occurrences come from the indexes in one non-blocking read action
 * ([VarOccurrences]), the usages from the PSI in batches, each in its own non-blocking read action, so a write action
 * never waits for the search; cancellation is checked per occurrence.
 */
internal object VarUsageSearch {
    private const val BATCH = 64

    /** argument_specs files: a nested key there is a nested option, not a member of a value. */
    private val SPEC_KINDS = setOf(FileKind.ROLE_ARGSPEC, FileKind.ROLE_META)

    /** Names only a loop binds: searched in their loop, never across the root (`item` is a fifth of all uses). */
    val LOOP_ONLY_NAMES: Set<String> = setOf(LoopControlInfo.DEFAULT_LOOP_VAR, LoopItemTyper.ANSIBLE_LOOP)

    /** The free names whose accesses read a variable by name ([JinjaIndirection]): `hostvars`, `vars` and the lookups. */
    private val INDIRECTION_ROOTS = setOf("hostvars", "vars", "lookup", "query", "q")

    /** Local kinds that are not names a user binds: `loop` in a `for` body and a macro's `varargs`/`kwargs`/`caller`. */
    private val IMPLICIT_LOCALS = setOf(JinjaLocalKind.LOOP, JinjaLocalKind.MACRO_IMPLICIT, JinjaLocalKind.NAMESPACE_ATTRIBUTE)

    /**
     * The variable named at [offset] of [file] (a host file, an injected Ansible Jinja fragment or a template), or null.
     *
     * The caret highlighting runs this on every caret move and after every keystroke, so it stays off the root-wide
     * indexes: one classification by the variables classifier only (the other classifiers' sites are no variables,
     * and some of them resolve template names through the indexes), per-file models, and the loop typing of
     * [LoopItems] only for a name the enclosing task's loop binds; templates ask their (cached) render contexts.
     */
    fun symbolAt(file: PsiFile, offset: Int): VarSymbolElement? {
        val position = SiteDispatch.hostPosition(file, offset)
        val host = position.file
        val context = SiteDispatch.contextOf(host) ?: return null
        if (context.kind == FileKind.INVENTORY_INI) return iniSymbol(host, context, position.offset)
        val caret = Caret(host, host.originalFile.viewProvider.virtualFile, context, position.offset)
        val site = variablesClassifier()?.classify(host, position.offset)
        if (site == null || site is AnsibleSite.VarRef && site.name in INDIRECTION_ROOTS) indirectSymbol(caret)?.let { return it }
        return when (site) {
            is AnsibleSite.VarRef -> referenceSymbol(caret, site)
            is AnsibleSite.VarKey -> keySymbol(caret, site)
            // A `loop_control.loop_var`/`index_var` value: the task's loop variable (never dropped by `else`).
            is LoopVarSite -> nameValueSymbol(caret)
            null -> nameValueSymbol(caret) ?: bindingSymbol(caret) ?: bracedSymbol(caret)
            else -> null
        }
    }

    /** The inline variable whose key is at [offset] of an INI inventory (plan amendment R10, D65). */
    private fun iniSymbol(file: PsiFile, context: FileContext, offset: Int): VarSymbolElement? {
        val virtualFile = file.originalFile.viewProvider.virtualFile
        val definition = InlineInventoryDefinitions.inFile(file.project, context.root, virtualFile).firstOrNull {
            offset >= it.location.offset && offset <= it.location.offset + it.name.length
        } ?: return null
        val anchor = file.findElementAt(definition.location.offset) ?: file
        return VarSymbolElement(anchor, context.root, definition.name, VarScope.Root(virtualFile, MoleculeView.of(file.project, virtualFile)))
    }

    /**
     * The variable a top-level variable key names (an argument_specs option, a `defaults/` or `group_vars` key, a
     * `set_fact` or task `vars:` key …), or null for structural and nested keys. Ctrl+B's "show usages" outcome hands
     * the platform the [YAMLKeyValue] itself, so the Find Usages handler factory accepts it. Needs a read lock.
     */
    fun symbolOfKey(keyValue: YAMLKeyValue): VarSymbolElement? {
        val file = keyValue.containingFile ?: return null
        val virtualFile = file.originalFile.viewProvider.virtualFile
        val context = AnsibleWorkspace.getInstance(keyValue.project).contextOf(virtualFile) ?: return null
        val keySite = VarKeySites.of(keyValue, context, virtualFile) ?: return null
        val path = keySite.site.keyPath
        val view = MoleculeView.of(keyValue.project, virtualFile)
        if (path.size == 1 && keySite.variable == keyValue) return VarSymbolElement(file, context.root, keySite.name, VarScope.Root(virtualFile, view))
        if (path.size < 2 || keySite.site.kind in SPEC_KINDS || keyValue.keyText != path.last()) return null
        return VarSymbolElement(file, context.root, keySite.name, VarScope.Member(virtualFile, path.drop(1), view))
    }

    /**
     * The scope of the loop variable [binding] binds, the same wherever a search for it starts (the tasks' `loop_var`
     * value, their bodies, the files they include, the templates rendered there). [template] is the template a search
     * starts in: when a loop of [binding] is run by no task (Molecule's platform loop over a `Dockerfile.j2`, X77), the
     * uses in that template are the search's own ([VarScope.Loop.file]).
     */
    fun loopScope(project: Project, binding: LoopItems.Binding, template: VirtualFile?): VarScope.Loop =
        VarScope.Loop(binding.tasks.map { it.task }, template?.takeIf { binding.runByNoTask(project) })

    /**
     * The symbol a card about [name] in [file] stands for: what the card's position names ([offset], -1 for a card
     * reached through a link), else the root variable. Needs a read lock and smart mode.
     */
    fun symbolForCard(project: Project, file: VirtualFile, offset: Int, name: String): VarSymbolElement? {
        val psi = PsiManager.getInstance(project).findFile(file) ?: return null
        if (offset >= 0) symbolAt(psi, offset)?.takeIf { it.name == name }?.let { return it }
        val root = AnsibleWorkspace.getInstance(project).contextOf(file)?.root ?: return null
        return VarSymbolElement(psi, root, name, VarScope.Root(file, MoleculeView.of(project, file)))
    }

    /**
     * Feeds [processor] a usage per occurrence of [symbol] inside [scope] (the Find Usages dialog's or the Show
     * Usages popup's scope, intersected with the symbol's own scope), until it returns false. Called by Find Usages on
     * a background thread without a read lock: every step runs in a non-blocking read action in smart mode (directly
     * when the caller already holds a read lock).
     */
    fun process(project: Project, symbol: VarSymbolElement, scope: SearchScope, processor: Processor<in UsageInfo>): Boolean {
        val global = scope as? GlobalSearchScope
        val prepared = inSmartReadAction(project) {
            val occurrences = VarOccurrences.of(project, symbol, global)
            Prepared(occurrences, runtimeHome(project, symbol))
        }
        symbol.kinds.clear()
        val factory = VarUsageInfos(project, symbol, prepared.occurrences, prepared.runtimeHome)
        for (batch in prepared.occurrences.chunked(BATCH)) {
            ProgressManager.checkCanceled()
            val infos = inSmartReadAction(project) {
                batch.mapNotNull { occurrence ->
                    ProgressManager.checkCanceled()
                    val (info, kind) = factory.create(occurrence) ?: return@mapNotNull null
                    val element = info.element ?: return@mapNotNull null
                    if (global == null && !PsiSearchScopeUtil.isInScope(scope, element)) return@mapNotNull null
                    symbol.recordKind(element, kind)
                    info
                }
            }
            for (info in infos) if (!processor.process(info)) return false
        }
        return true
    }

    /**
     * The read and the write ranges of [symbol] in [hostFile], from that file's own entries ([OpenFileIndexData]: no
     * index access on the caret highlighting's path). Needs a read lock and smart mode.
     */
    fun rangesIn(project: Project, symbol: VarSymbolElement, hostFile: VirtualFile): Pair<List<TextRange>, List<TextRange>> {
        val occurrences = VarOccurrences.inFile(project, symbol, hostFile)
        return occurrences.filter { !it.write }.map { it.range } to occurrences.filter { it.write }.map { it.range }
    }

    /** Where navigating [symbol] goes ([VarOccurrences.primaryDeclaration]). Needs a read lock and smart mode. */
    fun primaryDeclaration(project: Project, symbol: VarSymbolElement): SourceLocation? = VarOccurrences.primaryDeclaration(project, symbol)

    /** The search's occurrences and, for a runtime name, the owners whose occurrences are the search's own. */
    private class Prepared(val occurrences: List<VarOccurrence>, val runtimeHome: Set<VarOwner>?)

    private fun runtimeHome(project: Project, symbol: VarSymbolElement): Set<VarOwner>? {
        val home = (symbol.scope as? VarScope.Root)?.home ?: return null
        val definitions = VarViews.symbol(project, symbol.root, symbol.name, symbol.view).definitions
        return VarOwners.runtimeHome(project, symbol.root, home, definitions)
    }

    // -------------------------------------------------------------------------------------------- caret → symbol

    /** The caret in its host file, with the Jinja analysed around it once (null outside Jinja). */
    private class Caret(val host: PsiFile, val file: VirtualFile, val context: FileContext, val offset: Int) {
        val analysis: JinjaTextSites.Analysis? by lazy(LazyThreadSafetyMode.NONE) { JinjaTextSites.analysisAt(host, offset) }

        /** What a search from this caret sees of Molecule content. */
        val view: MoleculeView by lazy(LazyThreadSafetyMode.NONE) { MoleculeView.of(host.project, file) }

        fun symbol(name: String, scope: VarScope): VarSymbolElement = VarSymbolElement(host, context.root, name, scope)
    }

    private fun referenceSymbol(caret: Caret, site: AnsibleSite.VarRef): VarSymbolElement? {
        if (site.name in site.localNames) {
            val analysis = caret.analysis ?: return null
            val local = analysis.localOf(analysis.reference, site.name)?.takeIf { it.kind !in IMPLICIT_LOCALS } ?: return null
            return localSymbol(caret, local, analysis.toHost(local.definitionRange.startOffset))
        }
        bindingSymbol(caret)?.let { return it }
        return nameSymbol(caret, site.name, site.range.startOffset)
    }

    /**
     * A free name read at [start] (host offset): the loop variable a loop binds there, else the root variable. A loop
     * variable's symbol is the same wherever the search starts (its tasks only): in the task, in a file the task
     * includes, in a template rendered there, or at its `loop_var` value.
     */
    private fun nameSymbol(caret: Caret, name: String, start: Int): VarSymbolElement {
        val inTemplate = JinjaTextSites.isTemplateFile(caret.file, caret.context)
        if (inTemplate || mayBeTaskLoopName(caret, name, start)) {
            LoopItems.bindingAt(caret.host.project, caret.file, start, name)?.let { binding ->
                return caret.symbol(name, loopScope(caret.host.project, binding, caret.file.takeIf { inTemplate }))
            }
        }
        if (name in LOOP_ONLY_NAMES) return caret.symbol(name, VarScope.Loop(emptyList(), caret.file))
        return caret.symbol(name, VarScope.Root(caret.file, caret.view))
    }

    /**
     * True when the task around [start] loops and [name] is one of the names its loop can bind (the loop variable,
     * `index_var`, `ansible_loop`), or when an include task that runs a role task file binds [name] through its loop
     * ([LoopItems.includeLoopMayBind], the cached include graph): structural checks, so that [LoopItems] types loops
     * (which reads the loop source's definitions across the root) only for those names.
     */
    private fun mayBeTaskLoopName(caret: Caret, name: String, start: Int): Boolean {
        val yaml = caret.host as? YAMLFile ?: YamlFiles.yamlFile(caret.host.project, caret.file) ?: return false
        if (!YamlPaths.isTopLevelSequence(yaml)) return false
        val task = TaskChains.taskOf(TaskChains.chainAt(TaskFileModels.of(yaml), start))
        if (task != null && (name == task.loopVar || name == task.loopControl?.indexVar?.text || name == LoopItemTyper.ANSIBLE_LOOP && task.loop != null)) return true
        return caret.context.kind == FileKind.ROLE_TASKS && LoopItems.includeLoopMayBind(caret.host.project, caret.file, name)
    }

    /** The variables area's own classifier ([VarsSiteClassifier], `order="first"`): the only sites this search starts from. */
    private fun variablesClassifier(): SiteClassifier? = SiteClassifier.EP_NAME.findExtension(VarsSiteClassifier::class.java)

    /** The member read by name under the caret (FU2): `hostvars[h].x`, `vars['x']`, `lookup('vars', 'x')`. */
    private fun indirectSymbol(caret: Caret): VarSymbolElement? {
        val analysis = caret.analysis ?: return null
        val member = analysis.result.indirectReferenceAt(analysis.textOffset) ?: return null
        return indirectMember(caret, analysis, member)
    }

    private fun indirectMember(caret: Caret, analysis: JinjaTextSites.Analysis, member: JinjaIndirectRef): VarSymbolElement? = when (member.via) {
        // Some host's variable: never the loop's, and `item` is never searched across the root.
        JinjaIndirection.HOSTVARS -> if (member.name in LOOP_ONLY_NAMES) null else caret.symbol(member.name, VarScope.Root(caret.file, caret.view))
        JinjaIndirection.VARS -> nameSymbol(caret, member.name, analysis.toHost(member.nameRange.startOffset))
    }

    /**
     * A name outside the braces of a braced implicit expression (`that: "{{ a }} == b"`), which Ansible renders and
     * then evaluates. Only implicit-expression keys are evaluated; the `ansible.var.use` indexer knows which scalars
     * those are and records their outside names, so a name counts when the file's entries hold its read at that offset.
     */
    private fun bracedSymbol(caret: Caret): VarSymbolElement? {
        val analysis = caret.analysis?.takeIf { it.container == JinjaContainer.YAML_TEMPLATE } ?: return null
        val outside = JinjaRefs.analyzeBracedExpression(analysis.text)
        // A member first: `vars['b']` is also a reference to `vars` whose path covers the member.
        val member = outside.indirectReferenceAt(analysis.textOffset)
        val reference = outside.references.firstOrNull { it.range.containsOffset(analysis.textOffset) }
        val (name, nameRange) = when {
            member != null -> member.name to member.nameRange
            reference != null -> reference.name to reference.nameRange
            else -> return null
        }
        val start = analysis.toHost(nameRange.startOffset)
        val recorded = VarOccurrences.fileUses(caret.host.project, caret.context.root, caret.file, name)
            .any { it.location.offset == start && it.container == JinjaContainer.YAML_EXPRESSION }
        if (!recorded) return null
        return if (member != null) indirectMember(caret, analysis, member) else nameSymbol(caret, name, start)
    }

    private fun keySymbol(caret: Caret, site: AnsibleSite.VarKey): VarSymbolElement? {
        val keySite = VarKeySites.at(caret.host, site.range.startOffset, caret.context) ?: return null
        if (site.keyPath.size == 1) return caret.symbol(keySite.name, VarScope.Root(caret.file, caret.view))
        if (site.kind in SPEC_KINDS) return null
        return caret.symbol(keySite.name, VarScope.Member(caret.file, site.keyPath.drop(1), caret.view))
    }

    /**
     * A `register:` value (a root variable) or a `loop_control.loop_var`/`index_var` value (the task's loop variable;
     * for a looping include task the loop [LoopItems.bindingOfValue] finds, which also holds the other include tasks
     * binding the name in the files it runs, so the symbol is the one a search from those files finds).
     */
    private fun nameValueSymbol(caret: Caret): VarSymbolElement? {
        val yaml = caret.host as? YAMLFile ?: YamlFiles.yamlFile(caret.host.project, caret.file) ?: return null
        if (!YamlPaths.isTopLevelSequence(yaml)) return null
        val offset = caret.offset
        val task = TaskFileModels.of(yaml).itemAt(offset) as? TaskNode ?: return null
        task.register?.takeIf { it.range.containsOffset(offset) }?.let {
            return caret.symbol(it.text, VarScope.Root(caret.file, caret.view))
        }
        val control = task.loopControl ?: return null
        val name = listOfNotNull(control.loopVar, control.indexVar).firstOrNull { it.range.containsOffset(offset) } ?: return null
        val own = VarScope.Loop(listOf(SourceLocation(caret.file, task.range.startOffset)))
        if (task.taskInclude == null && task.roleInclude == null || DumbService.isDumb(caret.host.project)) return caret.symbol(name.text, own)
        val binding = LoopItems.bindingOfValue(caret.host.project, caret.file, offset)?.takeIf { it.first == name.text }?.second
        return caret.symbol(name.text, binding?.let { loopScope(caret.host.project, it, null) } ?: own)
    }

    /** The Jinja local whose binding name is under the caret. */
    private fun bindingSymbol(caret: Caret): VarSymbolElement? {
        val analysis = caret.analysis ?: return null
        val local = analysis.result.locals.firstOrNull { it.kind !in IMPLICIT_LOCALS && it.definitionRange.containsOffset(analysis.textOffset) } ?: return null
        return localSymbol(caret, local, analysis.toHost(local.definitionRange.startOffset))
    }

    private fun localSymbol(caret: Caret, local: JinjaLocal, binding: Int): VarSymbolElement =
        caret.symbol(local.name, VarScope.Local(caret.file, binding))

    /** Runs [action] in a read action in smart mode: directly when the caller holds a read lock, else non-blocking (restartable). */
    private fun <T> inSmartReadAction(project: Project, action: () -> T): T =
        if (ApplicationManager.getApplication().isReadAccessAllowed) {
            action()
        } else {
            ReadAction.nonBlocking(Callable { action() }).inSmartMode(project).executeSynchronously()
        }
}
