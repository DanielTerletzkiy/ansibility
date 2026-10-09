package de.terletzkiy.ansibility.toolwindow

import com.intellij.icons.AllIcons
import com.intellij.ide.CommonActionsManager
import com.intellij.ide.DefaultTreeExpander
import com.intellij.ide.DataManager
import com.intellij.ide.TreeExpander
import com.intellij.ide.util.treeView.NodeRenderer
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.CommonShortcuts
import com.intellij.openapi.actionSystem.CustomizedDataContext
import com.intellij.openapi.actionSystem.DataSink
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.application.readAction
import com.intellij.openapi.progress.coroutineToIndicator
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.DumbAwareToggleAction
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.pom.Navigatable
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.PopupHandler
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.TreeSpeedSearch
import com.intellij.ui.tree.AsyncTreeModel
import com.intellij.ui.tree.StructureTreeModel
import com.intellij.ui.tree.TreeVisitor
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.tree.TreeModelAdapter
import com.intellij.util.ui.tree.TreeUtil
import de.terletzkiy.ansibility.api.HostKey
import de.terletzkiy.ansibility.api.PlayGraph
import de.terletzkiy.ansibility.api.RoleTests
import de.terletzkiy.ansibility.api.WorkspaceScopeService
import de.terletzkiy.ansibility.golden.GoldenDataKeys
import de.terletzkiy.ansibility.golden.history.LastChanges
import de.terletzkiy.ansibility.golden.remote.GoldenMirrorConsents
import de.terletzkiy.ansibility.golden.remote.GoldenMirrorListener
import de.terletzkiy.ansibility.golden.remote.GoldenMirrors
import de.terletzkiy.ansibility.model.drift.AnsibilityDriftBundle
import de.terletzkiy.ansibility.model.drift.DriftTexts
import de.terletzkiy.ansibility.model.drift.RoleDriftListener
import de.terletzkiy.ansibility.model.drift.RoleDriftService
import de.terletzkiy.ansibility.model.role.ExternalGoldenRoot
import de.terletzkiy.ansibility.settings.AnsibilitySettingsListener
import de.terletzkiy.ansibility.settings.GoldenRoot
import de.terletzkiy.ansibility.settings.WorkspaceState
import de.terletzkiy.ansibility.settings.ui.AnsibilityConfigurable
import de.terletzkiy.ansibility.toolwindow.host.EffectivePlayChoices
import de.terletzkiy.ansibility.toolwindow.host.PlayChoice
import de.terletzkiy.ansibility.toolwindow.model.AnsibleTreeNode
import de.terletzkiy.ansibility.toolwindow.model.DriftDetailsContent
import de.terletzkiy.ansibility.toolwindow.model.LastChangeRequest
import de.terletzkiy.ansibility.toolwindow.model.NavigationTarget
import de.terletzkiy.ansibility.toolwindow.model.NodeActivation
import de.terletzkiy.ansibility.toolwindow.model.NodeDetails
import de.terletzkiy.ansibility.toolwindow.model.RoleCopyData
import de.terletzkiy.ansibility.toolwindow.model.RoleNameNode
import de.terletzkiy.ansibility.toolwindow.model.RoleNode
import de.terletzkiy.ansibility.toolwindow.model.RolesNode
import de.terletzkiy.ansibility.toolwindow.model.RootNode
import de.terletzkiy.ansibility.toolwindow.model.TreeContext
import de.terletzkiy.ansibility.toolwindow.model.TreeView
import de.terletzkiy.ansibility.toolwindow.model.VariantGroupNode
import de.terletzkiy.ansibility.toolwindow.model.WorkspaceNode
import de.terletzkiy.ansibility.toolwindow.model.WorkspaceSnapshot
import de.terletzkiy.ansibility.toolwindow.model.rootsKnown
import de.terletzkiy.ansibility.workspace.WorkspaceScopeListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.concurrency.AsyncPromise
import org.jetbrains.concurrency.Promise
import java.awt.BorderLayout
import java.awt.event.HierarchyEvent
import java.awt.event.InputEvent
import java.awt.event.MouseEvent
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.Timer
import javax.swing.ToolTipManager
import javax.swing.event.TreeExpansionEvent
import javax.swing.event.TreeExpansionListener
import javax.swing.event.TreeModelEvent
import javax.swing.tree.TreePath
import javax.swing.tree.TreeSelectionModel

/**
 * The content of the "Ansibility" tool window (plan F6.1–F6.4): a toolbar, the tree on top and the details pane below.
 *
 * - The tree is a [StructureTreeModel] over [AnsibleTreeStructure] wrapped in an [AsyncTreeModel] and shown by a
 *   [Tree] with a [NodeRenderer] and speed search ([TreeSpeedSearch.installOn]).
 * - Double-click and Enter open a node's target (a group key or host entry in `hosts.yml`, a var file,
 *   `ansible.cfg`, a playbook or play) and expand container nodes; F4 (Jump to Source) always opens the target.
 *   Navigation is `OpenFileDescriptor(project, vf, offset).navigate(true)`, also offered to the platform through
 *   [CommonDataKeys.NAVIGATABLE_ARRAY].
 * - [AnsibleTreeRefresher] rebuilds the snapshot in a background read action; [applySnapshot] swaps it in on the EDT
 *   and invalidates the model, which keeps expansion and selection.
 * - The details of the selected node are computed in a background read action ([updateDetails]) and shown on the EDT:
 *   HA7's details (Effective vars, reach, play matches) evaluate the host context. A newer selection cancels the
 *   computation of an older one.
 * - A play chosen in the Effective vars selector ([choosePlay]), a change of the Ansible context (HA3's
 *   `workspaceStateChanged`) and the end of indexing re-render the tree from the current snapshot: nothing is rebuilt,
 *   the cached tables answer (or compute what the indexes now allow). A change of the workspace scope re-renders
 *   the Roles tab's tree (plan amendment R19, D143: a role-name row's marker and "in scope" count follow the scope
 *   picker); no other row and no details read the scope.
 * - The toolbar is asked to update after every snapshot and scope change ([updateToolbar]): actions whose visibility
 *   depends on them (Run Molecule Tests, D140) appear and disappear without waiting for the next mouse move, also
 *   once a named scope's roots are worked out in the background.
 * - Expand All ([expandAll]) leaves the per-host subtrees collapsed (a host's Effective vars and Targeted by).
 * - Role drift (plan amendment R24, D178–D181): with a golden root ([WorkspaceSnapshot.golden]) role rows carry drift
 *   badges and "Differences from golden" groups. Only the Roles tab starts the computation of every name
 *   ([RoleDriftService.request], visible names first), and only while it is shown ([tabShown]): never at project
 *   open. The Repos tab computes only the selected copy's name ([RoleDriftService.requestNames]). [RoleDriftListener] events are collected for [DRIFT_DEBOUNCE_MS] and invalidate only that name's rows (the
 *   whole tree under Drifted Only), and the details when the selection belongs to that name. The Roles tab shows a
 *   header line ([RolesHeader]): the golden root, or "No golden root · Choose…" and the role-library offer.
 * - Golden actions (other work units) get the selected copy through [GoldenDataKeys] in [uiDataSnapshot];
 *   double-click, Enter and F4 ask the node first ([AnsibleTreeNode.activate]: a drift file row runs Compare with
 *   Golden when it is registered).
 * - Group by Variant (X123, the Roles toolbar next to Drifted Only, in memory per panel like it) lists a name's copies
 *   in variant groups; a copy row keeps its key there, and the selection follows a copy into its group.
 * - "Last changed" (D180): the details read only the cache of [LastChanges]; the sides they still miss
 *   ([DriftDetailsContent.lastChanges]) are looked up in the background, outside the read action, and the details
 *   are computed again once an answer arrives ([fetchLastChanges]).
 * - External golden root (plan amendment R25): the header follows the mirror's state ([GoldenMirrorListener]; Fetch
 *   Now, Retry, the consent's Fetch and Not on this machine, Allow background refreshes) and renders its relative
 *   times again every [ExternalGoldenHeader.TICK_MS] while the tab is shown ([ExternalGoldenHeader]).
 */
class AnsibleToolWindowPanel(private val project: Project, val view: TreeView = TreeView.REPOS) : SimpleToolWindowPanel(true, true), Disposable {
    private val structure = AnsibleTreeStructure(project, TreeContext { PlayGraph.getInstance(project).playsOf(it) }, view)
    private val structureModel = StructureTreeModel(structure, this)
    private val asyncModel = AsyncTreeModel(structureModel, this)

    /** The tree; public for tests. */
    val tree: Tree = Tree(asyncModel)

    /** The details pane; public for tests. */
    val detailsView: AnsibleDetailsView = AnsibleDetailsView(::navigate, ::choosePlay, { id, copy, path -> runAction(id, copy, path) })

    /** The Roles tab's header line (plan amendment R24, D178); public for tests. */
    val rolesHeader: RolesHeader = RolesHeader()

    /** Opens Settings › Ansibility (the header's Choose…); replaced in tests. */
    internal var openSettings: () -> Unit = {
        ShowSettingsUtil.getInstance().showSettingsDialog(project, AnsibilityConfigurable::class.java)
    }

    /** The clock of the header's relative times ("fetched 2 min ago"); replaced in tests. */
    internal var headerClock: () -> Instant = Instant::now

    /** Renders the Roles header again while the tab is shown: the relative times move on (R25). */
    private val headerTimer: Timer? = if (view == TreeView.ROLES) Timer(ExternalGoldenHeader.TICK_MS) { if (tabShown) updateHeader() } else null

    /**
     * Whether this tab is on screen: drift is computed only for a shown Roles tab (plan amendment R24, D179). Follows
     * the panel's `SHOWING` state; tests set it.
     */
    @Volatile
    var tabShown: Boolean = false
        internal set(value) {
            field = value
            if (value) requestDrift()
        }

    /** The Roles tab's Drifted Only (toolbar). */
    var driftedOnly: Boolean
        get() = structure.driftedOnly
        internal set(value) {
            if (structure.driftedOnly == value) return
            structure.driftedOnly = value
            structureModel.invalidateAsync()
        }

    /**
     * The Roles tab's Group by Variant (toolbar, plan amendment R24, X123), in memory per panel like [driftedOnly]. The
     * selected copy row stays selected: it has the same key below its variant group.
     */
    var groupByVariant: Boolean
        get() = structure.groupByVariant
        internal set(value) {
            if (structure.groupByVariant == value) return
            structure.groupByVariant = value
            keepSelection(structureModel.invalidateAsync())
        }

    /** How many times an arriving last change made the details compute again (tests wait on it). */
    @Volatile
    var lastChangeUpdates: Long = 0
        private set

    private val pendingDriftNames: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val driftFlushScheduled = AtomicBoolean()

    /** How many drift events were applied to the tree so far (tests wait on it). */
    @Volatile
    var driftUpdates: Long = 0
        private set

    /** What the toolbar's Expand All and Collapse All act on; Expand All is [expandAll]. Public for tests. */
    val treeExpander: TreeExpander = object : DefaultTreeExpander(tree) {
        override fun expandAll(tree: JTree) {
            this@AnsibleToolWindowPanel.expandAll()
        }
    }

    private val refresher: AnsibleTreeRefresher

    /** The toolbar, kept to update its actions after a snapshot or scope change; tests read its visible actions. */
    internal lateinit var actionToolbar: ActionToolbar
        private set

    private var expandedOnce = false
    private var detailsUpdateScheduled = false
    private var detailsJob: Job? = null
    private var scopeRootsJob: Job? = null
    private var detailsGeneration = 0L
    private val shownDetails = AtomicLong()

    /** The node whose details the pane shows now (null: the hint); tests wait for it after a selection. */
    var detailsNode: AnsibleTreeNode? = null
        private set

    /** How many details were shown so far (tests wait on it). */
    val detailsCount: Long get() = shownDetails.get()

    init {
        tree.isRootVisible = false
        tree.showsRootHandles = true
        tree.cellRenderer = NodeRenderer()
        // Several rows at once (Cmd/Shift-click): the Molecule tests of the selected roles (R16); the details show one node.
        tree.selectionModel.selectionMode = TreeSelectionModel.DISCONTIGUOUS_TREE_SELECTION
        tree.emptyText.text = AnsibilityToolWindowBundle.message("toolwindow.loading")
        // Double-click is handled below: navigable nodes open their target, containers toggle.
        tree.toggleClickCount = 0
        ToolTipManager.sharedInstance().registerComponent(tree)
        TreeSpeedSearch.installOn(tree)
        TreeUtil.installActions(tree)
        // Right-click menu: "Use as Ansible context" (HA3) and other areas' node actions join this group.
        PopupHandler.installPopupMenu(tree, POPUP_GROUP, POPUP_PLACE)
        installNavigation()
        tree.addTreeSelectionListener { updateDetails() }
        // A refresh keeps the selected row but gives it a new element: re-render the details once the model settled.
        asyncModel.addTreeModelListener(object : TreeModelAdapter() {
            override fun process(event: TreeModelEvent, type: TreeModelAdapter.EventType) = scheduleDetailsUpdate()
        })

        val scrollPane = ScrollPaneFactory.createScrollPane(tree, true)
        val top = if (view == TreeView.ROLES) {
            JPanel(BorderLayout()).apply {
                add(rolesHeader.component, BorderLayout.NORTH)
                add(scrollPane, BorderLayout.CENTER)
            }
        } else {
            scrollPane
        }
        val splitter = OnePixelSplitter(true, SPLITTER_PROPORTION_KEY, DEFAULT_PROPORTION).apply {
            firstComponent = top
            secondComponent = detailsView.component
        }
        setContent(splitter)
        setToolbar(createToolbar())

        refresher = AnsibleTreeRefresher.start(project, this, ::applySnapshot)
        val connection = project.messageBus.connect(this)
        // HA3 switches the Ansible context: the selected environment of plays and Auto play choices follow it (D33).
        connection.subscribe(
            AnsibilitySettingsListener.TOPIC,
            object : AnsibilitySettingsListener {
                override fun workspaceStateChanged(old: WorkspaceState, new: WorkspaceState) {
                    if (old.roots != new.roots) scheduleRerender()
                }
            },
        )
        // R16: a role's Molecule tests started or ended: its marker changes.
        connection.subscribe(RoleTests.TOPIC, RoleTests.Listener { scheduleRerender() })
        // R19: the scope picker decides what a role-name row runs (its marker and "in scope" count) and whether the
        // toolbar offers Molecule tests. Fired on any thread, often (every editor switch under "Current file's root"):
        // only the Roles tab's rows read the scope, so only that tree re-renders; the snapshot and the details stay.
        connection.subscribe(
            WorkspaceScopeListener.TOPIC,
            WorkspaceScopeListener {
                ApplicationManager.getApplication().invokeLater({
                    if (view == TreeView.ROLES) structureModel.invalidateAsync()
                    updateToolbar()
                }, ModalityState.any()) { isDisposed }
            },
        )
        // Runtime markers (variable index) and var-file effects (the background summary) wait for the indexes.
        connection.subscribe(
            DumbService.DUMB_MODE,
            object : DumbService.DumbModeListener {
                override fun exitDumbMode() = scheduleRerender()
            },
        )
        // R24: the drift of a role name was computed or changed (background thread): its rows and the header follow.
        if (view != TreeView.ENVIRONMENTS) connection.subscribe(RoleDriftListener.TOPIC, RoleDriftListener(::driftChanged))
        if (view == TreeView.ROLES) {
            // R25: the external golden root's state (fetching, errors, pauses, consent) shows in the header at once.
            connection.subscribe(
                GoldenMirrorListener.TOPIC,
                GoldenMirrorListener { ApplicationManager.getApplication().invokeLater({ updateHeader() }, ModalityState.any()) { isDisposed } },
            )
            headerTimer?.start()
        }
        if (view == TreeView.ROLES) {
            // R24, D179: drift is computed only while the Roles tab is on screen, visible names first.
            addHierarchyListener { event ->
                if (event.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong() != 0L) tabShown = isShowing
            }
            scrollPane.viewport.addChangeListener { requestDrift() }
            tree.addTreeExpansionListener(object : TreeExpansionListener {
                override fun treeExpanded(event: TreeExpansionEvent) = requestDrift()

                override fun treeCollapsed(event: TreeExpansionEvent) = Unit
            })
            updateHeader()
        }
    }

    /** The snapshot the tree shows now. */
    val snapshot: WorkspaceSnapshot get() = structure.snapshot

    /** How many snapshots were applied (tests wait for a refresh with it). */
    val refreshCount: Long get() = refresher.appliedCount

    /** Asks for a rebuild of the snapshot (the Refresh action). */
    fun requestRefresh() = refresher.request()

    /**
     * Shows [snapshot]: invalidates the model (expansion and selection are kept, the presentation is new), expands the
     * roots on the first load and refreshes the details of the selection. Must be called on the EDT.
     */
    fun applySnapshot(snapshot: WorkspaceSnapshot): CompletableFuture<*> {
        structure.snapshot = snapshot
        if (snapshot.isEmpty) {
            tree.emptyText.setText(AnsibilityToolWindowBundle.message("toolwindow.empty"))
                .appendLine(AnsibilityToolWindowBundle.message("toolwindow.empty.hint"))
        } else {
            tree.emptyText.clear()
        }
        val done = structureModel.invalidateAsync()
        // Run Molecule Tests shows only while a role in scope has scenarios, which the new snapshot may change (R19).
        updateToolbar()
        if (view == TreeView.ROLES) {
            updateHeader()
            done.thenRun { ApplicationManager.getApplication().invokeLater({ requestDrift() }, ModalityState.any()) { isDisposed } }
        }
        if (!expandedOnce && snapshot.roots.isNotEmpty() && view == TreeView.REPOS) {
            expandedOnce = true
            done.thenRun {
                ApplicationManager.getApplication().invokeLater(
                    { TreeUtil.promiseExpand(tree, INITIAL_EXPAND_DEPTH) },
                    ModalityState.any(),
                ) { isDisposed }
            }
        }
        return done
    }

    /** The node of the selected row, or null (also when several rows are selected). */
    fun selectedNode(): AnsibleTreeNode? = nodeOf(TreeUtil.getSelectedPathIfOne(tree))

    /** The nodes of the selected rows, in tree order. */
    fun selectedNodes(): List<AnsibleTreeNode> = tree.selectionPaths.orEmpty().sortedBy { tree.getRowForPath(it) }.mapNotNull(::nodeOf)

    /** Opens [node]'s target; false when it has none or it is gone. */
    fun navigate(node: AnsibleTreeNode): Boolean = node.target?.let(::navigate) == true

    /** Opens [target] with `OpenFileDescriptor(project, vf, offset).navigate(true)`; false when it is gone. */
    fun navigate(target: NavigationTarget): Boolean {
        if (!target.file.isValid) return false
        val descriptor = target.descriptor(project)
        if (!descriptor.canNavigate()) return false
        descriptor.navigate(true)
        return true
    }

    /**
     * The selected node's target (Navigatable, VIRTUAL_FILE), and for a role copy, a drift file row or a role file row
     * the copy and the file's path inside it ([GoldenDataKeys], plan amendment R24) for the golden actions. A file row's
     * VIRTUAL_FILE is the copy's file where it exists (never the golden copy's).
     */
    override fun uiDataSnapshot(sink: DataSink) {
        super.uiDataSnapshot(sink)
        val node = selectedNode() ?: return
        val target = node.target?.takeIf { it.file.isValid }
        if (target != null) {
            sink[CommonDataKeys.NAVIGATABLE_ARRAY] = arrayOf<Navigatable>(target.descriptor(project))
            sink[CommonDataKeys.VIRTUAL_FILE] = target.file
        }
        if (node is RoleCopyData && node.copyDir.isValid) {
            sink[GoldenDataKeys.ROLE_COPY] = node.copyDir
            node.rolePath?.let { sink[GoldenDataKeys.ROLE_PATH] = it }
            if (target == null) node.existingFile?.let { sink[CommonDataKeys.VIRTUAL_FILE] = it }
        }
    }

    /**
     * Runs the registered action [id] with the tree's data context; false when no such action is registered or it is
     * disabled there. The details pane names the copy it shows ([copyDir]) and, for a Compare link, the file
     * ([rolePath]): they replace the selection's (a button stands for the copy, so it gets no file). Must be called on
     * the EDT.
     */
    internal fun runAction(id: String, copyDir: VirtualFile? = null, rolePath: String? = null, inputEvent: InputEvent? = null): Boolean {
        val action = ActionManager.getInstance().getAction(id) ?: return false
        val base = DataManager.getInstance().getDataContext(tree)
        val context = if (copyDir == null) base else CustomizedDataContext.withSnapshot(base) { sink ->
            sink[GoldenDataKeys.ROLE_COPY] = copyDir
            if (rolePath != null) sink[GoldenDataKeys.ROLE_PATH] = rolePath else sink.setNull(GoldenDataKeys.ROLE_PATH)
        }
        val event = AnActionEvent.createEvent(action, context, action.templatePresentation.clone(), ACTION_PLACE, ActionUiKind.NONE, inputEvent)
        ActionUtil.updateAction(action, event)
        if (!event.presentation.isEnabled) return false
        ActionUtil.performAction(action, event)
        return true
    }

    /** What nodes may do on activation (double-click, Enter, F4). */
    private val activation = object : NodeActivation {
        override fun runAction(id: String): Boolean = this@AnsibleToolWindowPanel.runAction(id)

        override fun navigate(target: NavigationTarget): Boolean = this@AnsibleToolWindowPanel.navigate(target)
    }

    /** True once the tool window content was closed. */
    @Volatile
    var isDisposed: Boolean = false
        private set

    override fun dispose() {
        isDisposed = true
        headerTimer?.stop()
        detailsJob?.cancel()
        scopeRootsJob?.cancel()
    }

    // ------------------------------------------------------------------------------------------------ R24 role drift

    /**
     * Asks the drift service for the names of the visible Roles rows first (then every other name): only for the
     * Roles tab, only while it is shown and a golden root is set (D178: without one nothing is computed). Cheap; EDT.
     */
    internal fun requestDrift() {
        if (view != TreeView.ROLES || !tabShown || isDisposed || !snapshot.golden.isSet) return
        RoleDriftService.getInstance(project).request(visibleRoleNames())
    }

    /**
     * In the Repos tab, computes the drift of the selected row's role name when it is not known yet (review fix U3):
     * that tab never runs the all-names pass of the Roles tab ([requestDrift]), so its details would otherwise say
     * "being computed" with nothing computing. Only that one name ([RoleDriftService.requestNames]); only with a golden
     * root (D178). Cheap; EDT.
     */
    private fun requestSelectedDrift(node: AnsibleTreeNode) {
        if (view != TreeView.REPOS || isDisposed || !snapshot.golden.isSet) return
        val name = generateSequence(node) { it.parent }.firstNotNullOfOrNull { ancestor ->
            when (ancestor) {
                is RoleNode -> ancestor.role.name
                is RoleNameNode -> ancestor.name
                else -> null
            }
        } ?: return
        val service = RoleDriftService.getInstance(project)
        if (!service.isComputed(name)) service.requestNames(listOf(name))
    }

    /** The role names of the rows in the viewport, top to bottom (a copy or file row counts for its name). */
    private fun visibleRoleNames(): List<String> {
        val rect = tree.visibleRect
        if (tree.rowCount == 0 || rect.isEmpty) return emptyList()
        val first = tree.getClosestRowForLocation(rect.x, rect.y).coerceAtLeast(0)
        val last = tree.getClosestRowForLocation(rect.x, rect.y + rect.height).coerceAtLeast(first)
        return (first..last).mapNotNull { row ->
            val node = nodeOf(tree.getPathForRow(row)) ?: return@mapNotNull null
            generateSequence(node) { it.parent }.filterIsInstance<RoleNameNode>().firstOrNull()?.name
        }.distinct()
    }

    /** Collects a drift event (any thread) and applies the names after a short pause on the EDT. */
    private fun driftChanged(name: String) {
        pendingDriftNames += name
        if (!driftFlushScheduled.compareAndSet(false, true)) return
        AnsibleToolWindowScope.getInstance(project).scope.launch(Dispatchers.Default) {
            delay(DRIFT_DEBOUNCE_MS)
            withContext(Dispatchers.EDT + ModalityState.any().asContextElement()) {
                driftFlushScheduled.set(false)
                if (!isDisposed) applyDrift()
            }
        }
    }

    /** Invalidates the rows of the collected names (the whole tree under Drifted Only), the header and the details. */
    private fun applyDrift() {
        val names = pendingDriftNames.toList()
        pendingDriftNames.removeAll(names.toSet())
        if (names.isEmpty()) return
        val current = snapshot
        if (current.golden.isSet) {
            when (view) {
                TreeView.ROLES -> {
                    val moves = selectionMovesGroup(names)
                    val done = if (driftedOnly) {
                        structureModel.invalidateAsync()
                    } else {
                        val root = WorkspaceNode(current, view)
                        CompletableFuture.allOf(*names.map { structureModel.invalidateAsync(RoleNameNode(root, it, emptyList()), true) }.toTypedArray())
                    }
                    if (moves) keepSelection(done)
                }
                TreeView.REPOS -> {
                    val root = WorkspaceNode(current, view)
                    for (rootSnapshot in current.roots) {
                        val roles = rootSnapshot.ownRoles.filter { it.name in names }
                        if (roles.isEmpty()) continue
                        val rolesNode = RolesNode(RootNode(root, rootSnapshot), rootSnapshot)
                        for (role in roles) structureModel.invalidateAsync(RoleNode(rolesNode, rootSnapshot, role, null, null), true)
                    }
                }
                TreeView.ENVIRONMENTS -> Unit
            }
            if (view == TreeView.ROLES) updateHeader()
            if (selectionBelongsTo(names)) updateDetails()
        }
        driftUpdates++
    }

    /**
     * Whether, under Group by Variant, the selected copy row (or a row below it) of one of [names] is about to move:
     * its name's drift now puts it into another variant group (or into one at all, once the drift is known).
     */
    private fun selectionMovesGroup(names: Collection<String>): Boolean {
        if (!groupByVariant) return false
        val node = selectedNode() ?: return false
        val copyRow = generateSequence(node) { it.parent }.filterIsInstance<RoleNode>().firstOrNull() ?: return false
        val nameRow = copyRow.parent?.let { it as? RoleNameNode ?: it.parent as? RoleNameNode } ?: return false
        if (nameRow.name !in names) return false
        val group = copyRow.parent as? VariantGroupNode
        val drift = RoleDriftService.getInstance(project).cached(nameRow.name) ?: return group != null
        val variant = drift.copyOf(copyRow.role.dir)?.let(drift::variantOf) ?: return group != null
        val dirs = variant.copies.map { it.dir }.toSet()
        val leader = nameRow.copies.firstOrNull { it.second.dir in dirs }?.second?.dir
        return group == null || group.copies.first().second.dir != leader
    }

    /**
     * Selects the rows selected now again, by key, once [done] (the model's invalidation) has completed: Group by
     * Variant moves a copy row below a variant group (with the same key) or into another one. The visit goes through a
     * group whose children's keys skip it ([AnsibleTreeNode.childKeyBase]).
     */
    private fun keepSelection(done: CompletableFuture<*>) {
        val keys = selectedNodes().map { it.key }
        if (keys.isEmpty()) return
        done.thenRun {
            ApplicationManager.getApplication().invokeLater({
                if (!isDisposed) TreeUtil.promiseSelect(tree, keys.stream().map(::visitorOf))
            }, ModalityState.any()) { isDisposed }
        }
    }

    private fun visitorOf(key: String): TreeVisitor = TreeVisitor { path ->
        val node = nodeOf(path)
        when {
            node == null -> TreeVisitor.Action.CONTINUE
            node.key == key -> TreeVisitor.Action.INTERRUPT
            key.startsWith(node.childKeyBase + "/") -> TreeVisitor.Action.CONTINUE
            else -> TreeVisitor.Action.SKIP_CHILDREN
        }
    }

    /** Whether the selected node is a row of one of [names] (the name, a copy, its differences or files). */
    private fun selectionBelongsTo(names: Collection<String>): Boolean {
        val node = selectedNode() ?: return false
        return generateSequence(node) { it.parent }.any { ancestor ->
            when (ancestor) {
                is RoleNameNode -> ancestor.name in names
                is RoleNode -> ancestor.role.name in names
                else -> false
            }
        }
    }

    /**
     * The header line of the Roles tab (D178/D179): the golden root with counts, or a configured golden root that cannot
     * be used. Without a golden root (the default) there is no header at all: drift is opt-in, and Settings › Ansibility
     * › Role drift or a root row's "Use as Golden Root" is where it starts (user feedback 2026-10-09: no reminder).
     */
    internal fun updateHeader() {
        if (view != TreeView.ROLES || isDisposed) return
        val current = snapshot
        val golden = current.golden
        val links = ArrayList<HeaderLink>()
        val choose = HeaderLink(AnsibilityDriftBundle.message("drift.header.choose")) { openSettings() }
        val setting = golden.resolution.setting
        val text = when {
            golden.isExternalSetting -> externalHeader(current, links)
            golden.isSet -> {
                val names = current.roleCopies.map { it.second.name }.distinct()
                val service = RoleDriftService.getInstance(project)
                val drifting = names.count { (service.cached(it)?.differingCount ?: 0) > 0 }
                // Until the worker has computed every name, the count is a lower bound: "12+ drifting", "… drifting".
                val complete = names.all(service::isComputed)
                links += HeaderLink(AnsibilityDriftBundle.message("drift.header.change")) { openSettings() }
                listOf(
                    AnsibilityDriftBundle.message("drift.header.golden", golden.name.orEmpty()),
                    AnsibilityDriftBundle.message("drift.header.names", names.size),
                    DriftTexts.drifting(drifting, complete),
                ).joinToString(" · ")
            }
            setting is GoldenRoot.Root -> {
                links += choose
                AnsibilityDriftBundle.message("drift.header.missing", setting.key)
            }
            setting == GoldenRoot.FirstRoleLibrary -> {
                links += choose
                AnsibilityDriftBundle.message("drift.header.noLibrary")
            }
            else -> null
        }
        if (text != null) rolesHeader.update(text, links) else rolesHeader.update("", emptyList())
        rolesHeader.component.isVisible = text != null && !current.isEmpty
    }

    /**
     * The header of an external golden root (plan amendment R25): [ExternalGoldenHeader] over the mirror's current
     * state, the names of the project's and the golden root's copies and how many drift. Fills [links]. EDT.
     */
    private fun externalHeader(current: WorkspaceSnapshot, links: MutableList<HeaderLink>): String {
        val state = GoldenMirrors.getInstance(project).state()
        val external = current.golden.external
        val counted = if (external == null) null else {
            val names = (current.roleCopies.map { it.second.name } + external.roles.map { it.name }).distinct()
            val service = RoleDriftService.getInstance(project)
            val drifting = names.count { (service.cached(it)?.differingCount ?: 0) > 0 }
            names.size to ExternalGoldenHeader.drifting(drifting, names.all(service::isComputed))
        }
        val line = ExternalGoldenHeader.compose(state, counted?.first, counted?.second, ExternalGoldenRoot.getInstance(project).errorSince(), headerClock())
        for (link in line.links) links += HeaderLink(ExternalGoldenHeader.text(link)) { runHeaderLink(link) }
        return line.text
    }

    /** What an external golden root's header link does: user actions only (D202: they may ask for credentials). */
    private fun runHeaderLink(link: ExternalGoldenHeader.Link) {
        val mirrors = GoldenMirrors.getInstance(project)
        when (link) {
            ExternalGoldenHeader.Link.FETCH_NOW, ExternalGoldenHeader.Link.RETRY -> mirrors.fetchNow(interactive = true)
            ExternalGoldenHeader.Link.CONSENT -> mirrors.consent(true)
            ExternalGoldenHeader.Link.DECLINE -> mirrors.consent(false)
            ExternalGoldenHeader.Link.ALLOW_SSH_AGENT -> {
                GoldenMirrorConsents.getInstance().sshAgentRefresh = true
                mirrors.sshAgentRefreshChanged()
            }
            ExternalGoldenHeader.Link.CHANGE -> openSettings()
        }
    }

    /**
     * Stores [choice] for [host]'s Effective vars and re-renders: the tree's Effective vars nodes and the details pane
     * pick it up, computing the new table in the background. Must be called on the EDT.
     */
    fun choosePlay(host: HostKey, choice: PlayChoice) {
        if (EffectivePlayChoices.getInstance(project).set(host, choice)) rerender()
    }

    private fun scheduleRerender() = ApplicationManager.getApplication().invokeLater({ rerender() }, ModalityState.any()) { isDisposed }

    /**
     * Updates the toolbar's actions in the background (their `update` runs on the BGT). While a named scope's roots are
     * still being worked out, Run Molecule Tests counts every root rather than walking in its update (D140), and the
     * workspace scope service says nothing when it has finished: the roots are worked out here in a background read
     * action (the service caches them), and the toolbar updates again once they are known. Without any role with tests
     * the button hides either way, and nothing is worked out. Must be called on the EDT.
     */
    private fun updateToolbar() {
        if (isDisposed) return
        actionToolbar.updateActionsAsync()
        if (view == TreeView.ENVIRONMENTS) return
        val scope = WorkspaceScopeService.getInstance(project).current()
        val snapshot = snapshot
        if (scope.rootsKnown() || snapshot.roots.none { snapshot.hasTestedRoles(it) }) return
        scopeRootsJob?.cancel()
        scopeRootsJob = AnsibleToolWindowScope.getInstance(project).scope.launch(Dispatchers.Default) {
            readAction { scope.roots }
            withContext(Dispatchers.EDT + ModalityState.any().asContextElement()) {
                if (!isDisposed) actionToolbar.updateActionsAsync()
            }
        }
    }

    /** Re-renders every visible node from the current snapshot (the presentation changed, the structure did not). */
    private fun rerender() {
        if (isDisposed) return
        structureModel.invalidateAsync()
        updateDetails()
    }

    private fun nodeOf(path: TreePath?): AnsibleTreeNode? =
        path?.let { TreeUtil.getLastUserObject(AnsibleNodeDescriptor::class.java, it) }?.node

    /**
     * Computes the selected node's details in a background read action and shows them on the EDT; until then the pane
     * keeps what it shows. A newer call cancels an older computation.
     */
    private fun updateDetails() {
        val node = selectedNode()
        detailsJob?.cancel()
        val generation = ++detailsGeneration
        if (node == null) {
            showDetails(null, null)
            return
        }
        requestSelectedDrift(node)
        detailsJob = AnsibleToolWindowScope.getInstance(project).scope.launch(Dispatchers.Default) {
            val details = readAction { node.details() }
            withContext(Dispatchers.EDT) {
                if (!isDisposed && generation == detailsGeneration) showDetails(node, details)
            }
            fetchLastChanges(generation, details)
        }
    }

    /**
     * Looks up the last changes the shown [details] still miss or show as last known (plan amendment R24, D180: each
     * side of a copy or a file) on I/O threads, outside the details' read action, and computes the details again only
     * when the cache then holds another answer than the one shown ([LastChangeRequest.shown]): a revalidation that
     * confirms what is shown, or a VCS change report while it ran (the file status refreshes after every save), never
     * starts another round. Part of the details' job, so a newer selection cancels the wait, and with it a lookup nobody
     * else waits for. Sides whose answer is "none" (untracked, no VCS root) are cached as such.
     */
    private suspend fun fetchLastChanges(generation: Long, details: NodeDetails?) {
        val requests = (details?.content as? DriftDetailsContent)?.lastChanges.orEmpty()
        if (requests.isEmpty()) return
        val changes = LastChanges.getInstance(project)
        val found = coroutineScope {
            requests.map { request -> async(Dispatchers.IO) { coroutineToIndicator { _ -> lookUp(changes, request) } } }.awaitAll()
        }.count { it }
        if (found == 0) return
        withContext(Dispatchers.EDT) {
            if (isDisposed || generation != detailsGeneration) return@withContext
            lastChangeUpdates++
            updateDetails()
        }
    }

    /** Looks [request] up (blocking, on a background thread); whether the cache now holds another answer than the shown one. */
    private fun lookUp(changes: LastChanges, request: LastChangeRequest): Boolean {
        if (request.directory) changes.lastChangeUnder(request.file) else changes.lastChange(request.file)
        val known = changes.known(request.file, request.directory) ?: return false
        return known.value != request.shown
    }

    private fun showDetails(node: AnsibleTreeNode?, details: NodeDetails?) {
        detailsNode = node
        detailsView.show(details)
        shownDetails.incrementAndGet()
    }

    private fun scheduleDetailsUpdate() {
        if (detailsUpdateScheduled) return
        detailsUpdateScheduled = true
        ApplicationManager.getApplication().invokeLater({
            detailsUpdateScheduled = false
            updateDetails()
        }, ModalityState.any()) { isDisposed }
    }

    /** Lets the node handle it ([AnsibleTreeNode.activate]), else opens the target of a navigable node, else expands or collapses [path]. */
    private fun activate(path: TreePath): Boolean {
        val node = nodeOf(path) ?: return false
        if (node.activate(activation)) return true
        if (node.navigatesOnDoubleClick && navigate(node)) return true
        if (tree.isExpanded(path)) tree.collapsePath(path) else tree.expandPath(path)
        return true
    }

    private fun installNavigation() {
        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean {
                val path = tree.getPathForLocation(event.x, event.y) ?: return false
                return activate(path)
            }
        }.installOn(tree)
        DumbAwareAction.create { TreeUtil.getSelectedPathIfOne(tree)?.let(::activate) }
            .registerCustomShortcutSet(CommonShortcuts.ENTER, tree, this)
        DumbAwareAction.create { selectedNode()?.let { node -> node.activate(activation) || navigate(node) } }
            .registerCustomShortcutSet(CommonShortcuts.getEditSource(), tree, this)
    }

    /**
     * Expands every node except the ones that leave their subtree to the user ([AnsibleTreeNode.expandsWithAll]: a
     * host's Effective vars and Targeted by), as the toolbar's Expand All does; the promise is done once the rows show.
     * The platform's expand-all would open every variable of every host under every group it is in, tens of thousands
     * of rows that never finish loading. Must be called on the EDT.
     */
    fun expandAll(): Promise<*> {
        val paths = ArrayList<TreePath>()
        val done = AsyncPromise<Any?>()
        // The visitor runs on the EDT (TreeVisitor's default); visiting loads the children it continues into.
        TreeUtil.promiseVisit(tree) { path ->
            val node = nodeOf(path)
            if (node?.expandsWithAll == false) {
                TreeVisitor.Action.SKIP_CHILDREN
            } else {
                if (node?.isLeaf != true) paths += path
                TreeVisitor.Action.CONTINUE
            }
        }.onProcessed {
            ApplicationManager.getApplication().invokeLater({
                if (!isDisposed) TreeUtil.expandPaths(tree, paths)
                done.setResult(null)
            }, ModalityState.any())
        }
        return done
    }

    private fun createToolbar(): JComponent {
        val actions = CommonActionsManager.getInstance()
        val group = DefaultActionGroup(
            RefreshAction(),
            actions.createExpandAllAction(treeExpander, tree),
            actions.createCollapseAllAction(treeExpander, tree),
        )
        if (view == TreeView.ROLES) {
            group.add(DriftedOnlyAction())
            group.add(GroupByVariantAction())
        }
        // Context (HA3) and workspace-scope (WS1) actions join from their own fragments (`add-to-group`).
        (ActionManager.getInstance().getAction(TOOLBAR_EXTRA_GROUP) as? ActionGroup)?.let {
            group.addSeparator()
            group.add(it)
        }
        val toolbar = ActionManager.getInstance().createActionToolbar(TOOLBAR_PLACE, group, true)
        toolbar.setTargetComponent(tree)
        actionToolbar = toolbar
        return toolbar.component
    }

    /** Drifted Only (plan amendment R24, D179): role names with a copy that differs from the golden root. */
    private inner class DriftedOnlyAction : DumbAwareToggleAction(
        AnsibilityDriftBundle.message("drift.action.driftedOnly.text"),
        AnsibilityDriftBundle.message("drift.action.driftedOnly.description"),
        AllIcons.General.Filter,
    ) {
        override fun isSelected(e: AnActionEvent): Boolean = driftedOnly

        override fun setSelected(e: AnActionEvent, state: Boolean) {
            driftedOnly = state
        }

        override fun update(e: AnActionEvent) {
            super.update(e)
            e.presentation.isEnabledAndVisible = snapshot.golden.isSet
        }

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
    }

    /** Group by Variant (plan amendment R24, X123): a role name's copies in groups of byte-identical copies. */
    private inner class GroupByVariantAction : DumbAwareToggleAction(
        AnsibilityDriftBundle.message("drift.action.groupByVariant.text"),
        AnsibilityDriftBundle.message("drift.action.groupByVariant.description"),
        AllIcons.Actions.GroupBy,
    ) {
        override fun isSelected(e: AnActionEvent): Boolean = groupByVariant

        override fun setSelected(e: AnActionEvent, state: Boolean) {
            groupByVariant = state
        }

        override fun update(e: AnActionEvent) {
            super.update(e)
            e.presentation.isEnabledAndVisible = snapshot.golden.isSet
        }

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
    }

    private inner class RefreshAction : DumbAwareAction(
        AnsibilityToolWindowBundle.message("action.refresh.text"),
        AnsibilityToolWindowBundle.message("action.refresh.description"),
        AllIcons.Actions.Refresh,
    ) {
        override fun actionPerformed(e: AnActionEvent) = requestRefresh()

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
    }

    companion object {
        const val TOOLBAR_PLACE: String = "AnsibilityToolWindow"
        /** The action group other areas add tool-window toolbar actions to (registered in ansibility-core.xml). */
        const val TOOLBAR_EXTRA_GROUP = "Ansibility.ToolWindow.Toolbar.Extra"
        /** The tree's popup menu group (declared in ansibility-host.xml) and its action place. */
        const val POPUP_GROUP = "Ansibility.ToolWindow.Popup"
        const val POPUP_PLACE = "AnsibilityToolWindowPopup"
        private const val SPLITTER_PROPORTION_KEY = "ansibility.toolwindow.splitter"

        /** The place of golden actions run from a row or the details pane. */
        const val ACTION_PLACE: String = "AnsibilityToolWindowDrift"

        /** Drift events are collected this long before the rows are invalidated. */
        private const val DRIFT_DEBOUNCE_MS: Long = 200
        private const val DEFAULT_PROPORTION = 0.6f

        /** On the first load the roots are expanded, so their Shared vars, Environments and Playbooks show. */
        private const val INITIAL_EXPAND_DEPTH = 2

        /** Creates a panel owned by [parent]. */
        fun create(project: Project, parent: Disposable): AnsibleToolWindowPanel =
            AnsibleToolWindowPanel(project).also { Disposer.register(parent, it) }

        /**
         * Activates the Ansibility tool window on its Roles tab with Drifted Only on (plan amendment R25, X128's Show).
         * EDT.
         */
        fun showDriftedRoles(project: Project) {
            if (project.isDisposed) return
            val window = ToolWindowManager.getInstance(project).getToolWindow(AnsibleToolWindowFactory.ID) ?: return
            window.activate({
                val manager = window.contentManager
                val content = manager.contents.firstOrNull { (it.component as? AnsibleToolWindowPanel)?.view == TreeView.ROLES } ?: return@activate
                manager.setSelectedContent(content, true)
                (content.component as AnsibleToolWindowPanel).driftedOnly = true
            }, true)
        }
    }
}
