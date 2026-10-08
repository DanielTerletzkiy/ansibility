package de.terletzkiy.ansibility.vars.usages

import com.intellij.codeInsight.highlighting.HighlightUsagesHandler
import com.intellij.codeInsight.highlighting.HighlightUsagesHandlerBase
import com.intellij.codeInsight.navigation.actions.GotoDeclarationOrUsageHandler2
import com.intellij.find.FindManager
import com.intellij.find.findUsages.FindUsagesHandlerFactory
import com.intellij.find.findUsages.FindUsagesManager
import com.intellij.find.findUsages.FindUsagesOptions
import com.intellij.find.findUsages.PsiElement2UsageTargetAdapter
import com.intellij.find.impl.FindManagerBase
import com.intellij.ide.IdeEventQueue
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.impl.NonBlockingReadActionImpl
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ex.util.EditorUtil
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.impl.source.tree.injected.InjectedLanguageEditorUtil
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.TestActionEvent
import com.intellij.usageView.UsageInfo
import com.intellij.usages.ReadWriteAccessUsage
import com.intellij.usages.Usage
import com.intellij.usages.UsageInfo2UsageAdapter
import com.intellij.usages.UsageView
import com.intellij.usages.UsageViewManager
import com.intellij.usages.impl.UsageViewImpl
import com.intellij.util.CommonProcessors
import de.terletzkiy.ansibility.vars.VarsTestCase
import java.util.concurrent.TimeUnit

/**
 * Base for the Find Usages tests (plan amendment FU, F1.10): sub-trees of `testData/usages` or `testData/vars` (or
 * the infra fixture) as Ansible roots, with helpers that drive the real actions the way the IDE does: Alt+F7 through
 * `FindUsagesAction` with the top-level editor's data context (so the injected context applies inside an injection),
 * Ctrl+B's GTDU decision, Highlight Usages in File and the caret highlighting's handler.
 *
 * Usages are described as `path:line:text R|W` (path relative to the project, the usage's own text, read or write).
 */
abstract class UsagesTestCase : VarsTestCase() {

    /** Copies `usages/<path>` (this area's own test data) to [target] and re-detects the roots. */
    protected fun copyUsagesData(path: String, target: String = path) {
        myFixture.copyDirectoryToProject("$USAGES_DATA/$path", target)
        refreshRoots()
        settle()
    }

    /**
     * Runs what the copy left pending: a new `ansible.cfg` makes a background VFS listener schedule a re-parse of the
     * files that depend on it (invokeLater, after a non-blocking read action when the walk is large; a file types
     * change, whose rescan starts dumb mode, only when there are too many files). Their re-indexing, or dumb mode, in
     * the middle of a later Find Usages would leave its targets empty, so that no search starts.
     */
    protected fun settle() {
        do {
            NonBlockingReadActionImpl.waitForAsyncTaskCompletion()
            IndexingTestUtil.waitUntilIndexesAreReady(project)
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        } while (DumbService.isDumb(project))
        IndexingTestUtil.waitUntilIndexesAreReady(project)
    }

    /** Opens [path] with the caret at [marker] on 1-based [line] plus [delta], and builds the injection there. */
    protected fun at(path: String, line: Int, marker: String, delta: Int = 1): Int {
        myFixture.configureFromTempProjectFile(path)
        val offset = offsetAt(path, line, marker, delta)
        hostEditor().caretModel.moveToOffset(offset)
        runReadActionBlocking { InjectedLanguageManager.getInstance(project).findInjectedElementAt(hostFile(), offset) }
        return offset
    }

    /** The top-level editor: once the caret sits in an injection, the fixture's `editor` is the injected one. */
    protected fun hostEditor(): Editor = InjectedLanguageEditorUtil.getTopLevelEditor(myFixture.editor)

    protected fun hostFile(): PsiFile = runReadActionBlocking { PsiDocumentManager.getInstance(project).getPsiFile(hostEditor().document)!! }

    /** The data context the editor actions see (their template presentation prefers injected PSI). */
    protected fun injectedContext(): DataContext = AnActionEvent.getInjectedDataContext(EditorUtil.getEditorDataContext(hostEditor()))

    /**
     * The variable Find Usages searches from the caret, the way the action decides: its first usage target is our
     * symbol (Jinja, `register:` values), or, at a YAML key, the key-value that the injected data context offers
     * there (its injected `PSI_ELEMENT` falls back to the host key-value, so the editor-based providers are skipped),
     * which our Find Usages handler factory turns into the symbol.
     */
    protected fun targetAtCaret(): VarSymbolElement? {
        val element = (injectedContext().getData(UsageView.USAGE_TARGETS_KEY)?.firstOrNull() as? PsiElement2UsageTargetAdapter)?.element ?: return null
        if (element is VarSymbolElement) return element
        val manager = (FindManager.getInstance(project) as FindManagerBase).findUsagesManager
        val handler = runReadActionBlocking { manager.getFindUsagesHandler(element, FindUsagesHandlerFactory.OperationMode.USAGES_WITH_DEFAULT_OPTIONS) }
        return handler?.primaryElements?.singleOrNull() as? VarSymbolElement
    }

    /** Ctrl+B's decision as `GotoDeclarationAction` makes it from the host editor (injection first, then the host). */
    protected fun gtdu(offset: Int): GotoDeclarationOrUsageHandler2.GTDUOutcome? {
        val editor = hostEditor()
        val file = hostFile()
        return inBackgroundReadAction { GotoDeclarationOrUsageHandler2.testGTDUOutcome(editor, file, offset) }
    }

    /** Alt+F7 as the IDE performs it, then the usages of the usage view it opened. */
    protected fun findUsagesViaAction(): List<Usage> = findUsagesViaActionWithGroups().map { it.first }

    /**
     * Alt+F7 as the IDE performs it; each usage of the view it opened with its usage-type group, as the Find tool
     * window asks the `usageTypeProvider`s (with the view's own targets), or null when none answers.
     */
    protected fun findUsagesViaActionWithGroups(): List<Pair<Usage, String?>> {
        val action = ActionManager.getInstance().getAction(IdeActions.ACTION_FIND_USAGES)
        val event = TestActionEvent.createTestEvent(action, EditorUtil.getEditorDataContext(hostEditor()))
        val previousView = UsageViewManager.getInstance(project).selectedUsageView
        ActionUtil.updateAction(action, event)
        assertTrue("Find Usages is enabled", event.presentation.isEnabled)
        ActionUtil.performAction(action, event)
        var groups: List<Pair<Usage, String?>> = emptyList()
        awaitUsageView(previousView) { view ->
            groups = runReadActionBlocking {
                view.usages.map { usage ->
                    val element = (usage as UsageInfo2UsageAdapter).element
                    usage to element?.let { VarUsageTypeProvider().getUsageType(it, (view as UsageViewImpl).targets)?.toString() }
                }
            }
        }
        return groups
    }

    /**
     * Waits for the selected usage view to finish, lets [inspect] look at it, and returns a copy of its usages
     * (disposing it clears them). A view that was already selected before the search ([previous], e.g. a disposed
     * one of an earlier test) never counts, so a search that did not start fails with "no usage view".
     */
    protected fun awaitUsageView(previous: UsageView? = null, inspect: (UsageView) -> Unit = {}): List<Usage> {
        val deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(60)
        var view: UsageView? = null
        while (System.currentTimeMillis() < deadline) {
            NonBlockingReadActionImpl.waitForAsyncTaskCompletion()
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            FindUsagesManager.waitForAsyncTaskCompletion(project)
            IdeEventQueue.getInstance().flushQueue()
            view = UsageViewManager.getInstance(project).selectedUsageView?.takeIf { it !== previous }
            if (view != null && !view.isSearchInProgress && view.usages.isNotEmpty()) break
            Thread.sleep(20)
        }
        val result = view?.usages?.toList() ?: error("no usage view")
        inspect(view)
        Disposer.dispose(view)
        return result
    }

    /** The usages the platform's Find Usages machinery finds for [element] (our handler from the factory), as `UsageInfo`s. */
    protected fun findUsagesOf(element: PsiElement, scope: GlobalSearchScope? = null): List<UsageInfo> {
        val manager = (FindManager.getInstance(project) as FindManagerBase).findUsagesManager
        val handler = runReadActionBlocking { manager.getFindUsagesHandler(element, FindUsagesHandlerFactory.OperationMode.USAGES_WITH_DEFAULT_OPTIONS) }
            ?: error("no Find Usages handler for $element")
        val options = FindUsagesOptions(project).also { it.searchScope = scope ?: GlobalSearchScope.projectScope(project) }
        val collect = CommonProcessors.CollectProcessor<UsageInfo>()
        val symbol = handler.primaryElements.single()
        onPooledThread { handler.processElementUsages(symbol, collect, options) }
        return collect.results.toList()
    }

    /** `path:line:text R|W`, sorted. */
    protected fun describeUsages(usages: Collection<Usage>): List<String> = usages.map { usage ->
        val info = (usage as UsageInfo2UsageAdapter).usageInfo
        val access = (usage as? ReadWriteAccessUsage)?.let { if (it.isAccessedForWriting) " W" else " R" } ?: ""
        describeInfo(info) + access
    }.sorted()

    protected fun describeInfos(infos: Collection<UsageInfo>): List<String> = infos.map(::describeInfo).sorted()

    protected fun describeInfo(info: UsageInfo): String = runReadActionBlocking {
        val file = info.virtualFile!!
        val base = myFixture.tempDirFixture.getFile("")!!
        val text = VfsUtilCore.loadText(file)
        val segment = info.segment!!
        "${VfsUtilCore.getRelativePath(file, base)}:${StringUtil.offsetToLineNumber(text, segment.startOffset) + 1}:${text.substring(segment.startOffset, segment.endOffset)}"
    }

    /** The row text a usage shows in the Find tool window and the Show Usages popup. */
    protected fun rowText(usage: Usage): String = runReadActionBlocking { usage.presentation.text.joinToString("") { it.text } }

    /** `line:text` of [range] in [path]. */
    protected fun lineAndText(path: String, range: TextRange): String {
        val text = VfsUtilCore.loadText(vf(path))
        return "${StringUtil.offsetToLineNumber(text, range.startOffset) + 1}:${range.substring(text)}"
    }

    /** The handler Ctrl+Shift+F7 and the caret highlighting use at the caret of the host editor, with its usages computed. */
    protected fun highlightHandlerAtCaret(): HighlightUsagesHandlerBase<PsiElement>? = runReadActionBlocking {
        HighlightUsagesHandler.createCustomHandler<PsiElement>(hostEditor(), hostFile())?.also { it.computeUsages(it.targets) }
    }

    /** Runs [action] on a pooled thread without a read lock, the way Find Usages runs its searches. */
    protected fun <T> onPooledThread(action: () -> T): T =
        ApplicationManager.getApplication().executeOnPooledThread<T> { action() }.get(60, TimeUnit.SECONDS)

    companion object {
        const val USAGES_DATA = "usages"
    }
}
