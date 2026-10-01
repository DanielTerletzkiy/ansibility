package de.terletzkiy.ansibility.context

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.PlatformCoreDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootEvent
import com.intellij.openapi.roots.ModuleRootListener
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.NlsContexts
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.CustomStatusBarWidget
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.openapi.wm.impl.status.TextPanel
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.ui.ClickListener
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import de.terletzkiy.ansibility.api.AnsibleContextService
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.ContextWidgetSegment
import de.terletzkiy.ansibility.api.ContextWidgetSegmentListener
import de.terletzkiy.ansibility.api.FileContext
import de.terletzkiy.ansibility.api.WidgetSegment
import de.terletzkiy.ansibility.context.host.AnsibleContextServiceImpl
import de.terletzkiy.ansibility.context.switching.ContextChoices
import de.terletzkiy.ansibility.context.switching.ContextPopupGroup
import de.terletzkiy.ansibility.context.switching.ContextTexts
import de.terletzkiy.ansibility.context.switching.EnvironmentGroup
import de.terletzkiy.ansibility.context.switching.HostGroup
import de.terletzkiy.ansibility.context.switching.WidgetTexts
import de.terletzkiy.ansibility.settings.AnsibilitySettingsListener
import de.terletzkiy.ansibility.settings.RootContext
import de.terletzkiy.ansibility.settings.WorkspaceState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.TestOnly
import java.awt.Component
import java.awt.Point
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.MouseEvent
import java.util.concurrent.atomic.AtomicLong
import javax.swing.JComponent

/** Registers the X02 Ansible context widget (`statusBarWidgetFactory` in `ansibility-core.xml`). */
class AnsibleContextWidgetFactory : StatusBarWidgetFactory {
    override fun getId(): String = AnsibleContextWidget.ID

    @NlsContexts.ConfigurableName
    override fun getDisplayName(): String = AnsibilityCoreBundle.message("status.widget.name")

    override fun createWidget(project: Project, scope: CoroutineScope): StatusBarWidget = AnsibleContextWidget(project, scope)
}

/**
 * X02: the Ansible context of the selected editor's file, `Ansibility: falcon · prod › prod-prod1 · core 2.18.8`, followed
 * by the contributed segments in EP order (`api.ContextWidgetSegment`: the file scope `file: postfix → 4 hosts`, the
 * vault lock state, the workspace scope). It hides itself for files outside every Ansible root. When the status bar is
 * narrow, the segments move into the tooltip ([WidgetTexts.isNarrow]).
 *
 * The parts of the text are click targets (plan amendment R7/R8, F8.1): the environment opens the environment
 * choices, the host the host choices, a segment its popup actions, and anything else the whole context popup of the
 * file's root (Environment, Host and Play, Follow editor, each segment's popup actions, Root Details…, Switch
 * Context…). Every switch goes through `AnsibleContextService.setSelection`; the widget never restarts the daemon.
 *
 * The state, segments included, is computed in a background read action whenever the selected editor, the project
 * roots, the Ansible structure, a selection or Follow editor change, a segment contributor publishes
 * [ContextWidgetSegmentListener.TOPIC], or indexing ends, and [MODEL_REFRESH_DELAY_MS] after the last change of a model
 * input (`AnsibleContextService.modelTracker` moved): an edit of an inventory, vars file or playbook, so the file
 * scope's host count follows a half-typed `hosts.yml` without a refresh per keystroke, and a new target version, which
 * the local ansible probe reports with a daemon restart. Only the final text update runs on the EDT.
 * A narrowed or widened status bar only re-renders the text.
 *
 * [StatusBarWidget] is a Kotlin interface reached through the Java interface [CustomStatusBarWidget]. The plugin is
 * compiled with `-jvm-default=no-compatibility` (`plugin/build.gradle.kts`), so no delegating bridges for its default
 * methods land in this class, among them the deprecated `getPresentation(PlatformType)` that the plugin verifier would
 * report as an override of deprecated API; this widget only uses the non-deprecated [getComponent].
 */
class AnsibleContextWidget(private val project: Project, private val scope: CoroutineScope) : CustomStatusBarWidget {

    /** What the widget shows for one file. */
    data class State(
        val root: AnsibleRoot,
        val context: FileContext?,
        val target: TargetVersion,
        val worktree: DetachedWorktree?,
        /** The root's stored selection (env, host, play). */
        val selection: RootContext = RootContext.DEFAULT,
        /** What is wrong with [selection] ("prod-db9 is no longer in environments/prod/hosts.yml"), or null. */
        @Nls val problem: String? = null,
        /** The play of [selection] as users read it, or null for Auto. */
        val playLabel: String? = null,
        /** The contributed segments, in EP order. */
        val segments: List<WidgetSegment> = emptyList(),
        /** The file the state was computed for. */
        val file: VirtualFile? = null,
    ) {
        /** The built-in parts: root, environment, host and core version, each a click target. */
        val builtInParts: List<WidgetTexts.Part> get() = ContextTexts.statusParts(root, target, selection, problem != null)

        /** The built-in text `Ansibility: <root> · <env> › <host> · core <v>`, without the segments. */
        val text: String get() = builtInParts.joinToString("") { it.text }

        /** The built-in text and every segment. */
        val fullText: String get() = WidgetTexts.fullParts(builtInParts, segments).joinToString("") { it.text }

        /** The parts and tooltip, with the segments moved to the tooltip when [narrow]. */
        fun render(narrow: Boolean): WidgetTexts.Rendered {
            val header = listOfNotNull(
                ContextTexts.message("widget.tooltip.selection", root.displayName, ContextTexts.selectionLine(selection, playLabel)),
                problem,
            )
            return WidgetTexts.render(builtInParts, segments, narrow, header)
        }
    }

    private val label = TextPanel.WithIconAndArrows().apply {
        border = JBUI.CurrentTheme.StatusBar.Widget.border()
        isVisible = false
        toolTipText = AnsibilityCoreBundle.message("status.tooltip")
        // Left-aligned without an icon, the text starts at the left inset: clicks map onto its parts.
        setTextAlignment(Component.LEFT_ALIGNMENT)
    }

    /**
     * Refresh requests; the latest is replayed, so the first request is not lost when it is made before the collector
     * subscribed, and a burst collapses into one refresh ([collectLatest]).
     */
    private val requests = MutableSharedFlow<Unit>(replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val refreshes = AtomicLong()

    /** Changes of model inputs, debounced into one refresh ([MODEL_REFRESH_DELAY_MS]). */
    private val modelChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** The `modelTracker` count last seen; a refresh is requested only when it moves. */
    private val modelStamp = AtomicLong(-1)

    @Volatile
    private var state: State? = null

    /** What [render] showed last (EDT); clicks are mapped onto its parts. */
    private var rendered: WidgetTexts.Rendered? = null
    private var statusBar: StatusBar? = null
    private var started = false

    private val resizeListener = object : ComponentAdapter() {
        override fun componentResized(event: ComponentEvent) = render()
    }

    override fun ID(): String = ID

    override fun getComponent(): JComponent = label

    override fun install(statusBar: StatusBar) {
        this.statusBar = statusBar
        statusBar.component?.addComponentListener(resizeListener)
        start()
    }

    override fun dispose() {
        statusBar?.component?.removeComponentListener(resizeListener)
        statusBar = null
    }

    /** Subscribes to every change that may alter the text and computes the first state; called once. */
    internal fun start() {
        if (started) return
        started = true
        val connection = project.messageBus.connect(this)
        connection.subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun selectionChanged(event: FileEditorManagerEvent) = requestUpdate()
            },
        )
        connection.subscribe(AnsibleStructureListener.TOPIC, AnsibleStructureListener { requestUpdate() })
        connection.subscribe(
            ModuleRootListener.TOPIC,
            object : ModuleRootListener {
                override fun rootsChanged(event: ModuleRootEvent) = requestUpdate()
            },
        )
        connection.subscribe(ContextWidgetSegmentListener.TOPIC, ContextWidgetSegmentListener { requestUpdate() })
        connection.subscribe(
            AnsibilitySettingsListener.TOPIC,
            object : AnsibilitySettingsListener {
                override fun workspaceStateChanged(old: WorkspaceState, new: WorkspaceState) = requestUpdate()
            },
        )
        connection.subscribe(
            DumbService.DUMB_MODE,
            object : DumbService.DumbModeListener {
                override fun exitDumbMode() = requestUpdate()
            },
        )
        // Called on the EDT inside the write action of every PSI change: only compare a counter there.
        connection.subscribe(PsiModificationTracker.TOPIC, PsiModificationTracker.Listener { modelMayHaveChanged() })
        connection.subscribe(
            DaemonCodeAnalyzer.DAEMON_EVENT_TOPIC,
            object : DaemonCodeAnalyzer.DaemonListener {
                override fun daemonFinished(fileEditors: Collection<FileEditor>) = modelMayHaveChanged()
            },
        )
        object : ClickListener() {
            override fun onClick(event: MouseEvent, clickCount: Int): Boolean {
                val current = state ?: return true
                showPopup(current, popupGroup(current, clickedPart(event.x)), event)
                return true
            }
        }.installOn(label, true)
        scope.launch(Dispatchers.Default) {
            requests.collectLatest { refresh() }
        }
        scope.launch(Dispatchers.Default) {
            modelChanges.collectLatest {
                delay(MODEL_REFRESH_DELAY_MS)
                requestUpdate()
            }
        }
        modelStamp.set(AnsibleContextService.getInstance(project).modelTracker.modificationCount)
        requestUpdate()
    }

    private fun requestUpdate() {
        requests.tryEmit(Unit)
    }

    /**
     * A PSI change or a finished highlighting pass: schedules a debounced refresh when a model input changed (the model
     * tracker, which also covers the target versions, moved). Only compares a counter.
     */
    private fun modelMayHaveChanged() {
        val stamp = AnsibleContextService.getInstance(project).modelTracker.modificationCount
        if (modelStamp.getAndSet(stamp) != stamp) modelChanges.tryEmit(Unit)
    }

    private suspend fun refresh() {
        val file = withContext(Dispatchers.EDT) { FileEditorManager.getInstance(project).selectedFiles.firstOrNull() }
        val newState = readAction { computeState(project, file, withSegments = true) }
        withContext(Dispatchers.EDT) {
            state = newState
            render()
            refreshes.incrementAndGet()
        }
    }

    /** Shows [state] (EDT); the status bar's width decides whether the segments fit. */
    private fun render() {
        val current = state
        label.isVisible = current != null
        if (current == null) {
            label.text = null
            rendered = null
        } else {
            val width = statusBar?.component?.width ?: 0
            val shown = current.render(WidgetTexts.isNarrow(width, textWidth(current.fullText)))
            rendered = shown
            label.text = shown.text
            label.toolTipText = shown.tooltip
        }
        statusBar?.updateWidget(ID)
    }

    private fun textWidth(text: String): Int = label.getFontMetrics(label.font).stringWidth(text)

    /** The part of the shown text under [x] (widget coordinates), or null. */
    private fun clickedPart(x: Int): WidgetTexts.Part? =
        rendered?.let { WidgetTexts.partAt(it.parts, x - label.insets.left, ::textWidth) }

    /**
     * What a click on [part] opens: the environment or host choices for those parts, a segment's popup actions for a
     * segment that has some, else the whole context popup (also for the root, the core version and separators).
     */
    internal fun popupGroup(state: State, part: WidgetTexts.Part?): ActionGroup {
        val whole = ContextPopupGroup(state.root, state.file, state.segments, listOf(DetailsAction(state)))
        return when (part?.kind) {
            WidgetTexts.PartKind.ENVIRONMENT -> EnvironmentGroup(state.root, part.text)
            WidgetTexts.PartKind.HOST -> HostGroup(state.root, part.text)
            WidgetTexts.PartKind.SEGMENT -> {
                val actions = state.segments.getOrNull(part.segment)?.popupActions.orEmpty()
                if (actions.isEmpty()) whole else DefaultActionGroup(actions)
            }
            else -> whole
        }
    }

    private fun showPopup(state: State, group: ActionGroup, event: MouseEvent) {
        val dataContext = SimpleDataContext.builder()
            .add(CommonDataKeys.PROJECT, project)
            .add(CommonDataKeys.VIRTUAL_FILE, state.file)
            .add(PlatformCoreDataKeys.CONTEXT_COMPONENT, label)
            .build()
        val popup = JBPopupFactory.getInstance().createActionGroupPopup(
            ContextTexts.message("popup.title", state.root.displayName),
            group,
            dataContext,
            JBPopupFactory.ActionSelectionAid.SPEEDSEARCH,
            true,
        )
        popup.show(RelativePoint(event.component, Point(0, -popup.content.preferredSize.height)))
    }

    /** "Root Details…": the root, file and target-version facts of the file (the widget's popup before F8.1). */
    private inner class DetailsAction(private val state: State) : DumbAwareAction(ContextTexts.message("popup.details")) {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

        override fun actionPerformed(e: AnActionEvent) = showDetails(state)
    }

    private fun showDetails(state: State) {
        val details = ContextPresentation.details(state.root, state.context, state.target, state.worktree)
        val content = panel {
            for (detail in details) {
                row(detail.label) { label(detail.value) }
            }
        }.apply { border = JBUI.Borders.empty(8, 12) }
        val popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(content, null)
            .setTitle(AnsibilityCoreBundle.message("details.title"))
            .setRequestFocus(true)
            .setResizable(false)
            .setMovable(true)
            .createPopup()
        popup.show(RelativePoint(label, Point(0, -content.preferredSize.height)))
    }

    /** The text the status bar shows now (EDT). */
    @get:TestOnly
    internal val shownText: String? get() = label.text?.takeIf { label.isVisible }

    /** The tooltip the status bar shows now (EDT). */
    @get:TestOnly
    internal val shownTooltip: String? get() = label.toolTipText

    /** How many background refreshes reached the EDT. */
    @get:TestOnly
    internal val refreshCount: Long get() = refreshes.get()

    /** The last computed state. */
    @get:TestOnly
    internal val currentState: State? get() = state

    companion object {
        const val ID: String = "Ansibility.Context"

        /** How long after the last edit of a model input the widget refreshes (the file scope's host count). */
        const val MODEL_REFRESH_DELAY_MS: Long = 300

        /**
         * The widget state for [file], or null when the file is outside every Ansible root. Needs a read lock. The
         * selection's problem and, with [withSegments], the contributed segments are left out while indexing.
         */
        fun computeState(project: Project, file: VirtualFile?, withSegments: Boolean = false): State? {
            if (file == null || project.isDisposed) return null
            val workspace = AnsibleWorkspace.getInstance(project)
            val context = workspace.contextOf(file)
            val root = context?.root ?: workspace.rootFor(file) ?: return null
            val target = TargetVersionDetector.getInstance(project).targetVersion(root)
            val worktree = AnsibleWorkspaceImpl.getInstance(project)?.worktreeOf(root)
            val selection = AnsibleContextService.getInstance(project).selection(root)
            if (DumbService.isDumb(project)) return State(root, context, target, worktree, selection, file = file)
            val choices = ContextChoices(project)
            val problem = AnsibleContextServiceImpl.getInstance(project)?.selectionProblem(root)
            val playLabel = selection.play?.let { choices.playLabel(root, it) }
            val segments = if (withSegments) ContextWidgetSegment.segments(project, file) else emptyList()
            return State(root, context, target, worktree, selection, problem, playLabel, segments, file)
        }
    }
}
