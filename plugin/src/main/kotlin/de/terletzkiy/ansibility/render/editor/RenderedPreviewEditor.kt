package de.terletzkiy.ansibility.render.editor

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.codeInsight.daemon.impl.HighlightInfoFilter
import com.intellij.diff.comparison.ComparisonManager
import com.intellij.diff.comparison.ComparisonPolicy
import com.intellij.diff.fragments.LineFragment
import com.intellij.diff.tools.util.BaseSyncScrollable
import com.intellij.diff.tools.util.SyncScrollSupport
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.diff.DiffColors
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.event.VisibleAreaListener
import com.intellij.openapi.progress.DumbProgressIndicator
import com.intellij.openapi.util.Key
import com.intellij.psi.PsiFile
import com.intellij.testFramework.LightVirtualFile
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.edtWriteAction
import com.intellij.openapi.application.readAction
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.editor.colors.CodeInsightColors
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.fileTypes.UnknownFileType
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.fileTypes.TemplateLanguageFileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.codeInsight.daemon.ProblemHighlightFilter
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.SourceLocation
import de.terletzkiy.ansibility.api.VaultDecryptResult
import de.terletzkiy.ansibility.api.VaultFailure
import de.terletzkiy.ansibility.api.VaultOperations
import de.terletzkiy.ansibility.api.VaultPurpose
import de.terletzkiy.ansibility.api.VaultUnlockResult
import de.terletzkiy.ansibility.vault.envelope.VaultEnvelopes
import de.terletzkiy.ansibility.vault.secrets.VaultSecretsService
import java.util.concurrent.ConcurrentHashMap
import de.terletzkiy.ansibility.render.AnsibilityRenderBundle
import de.terletzkiy.ansibility.render.service.PreviewPick
import de.terletzkiy.ansibility.render.service.PreviewReport
import de.terletzkiy.ansibility.render.service.TemplatePreviewService
import de.terletzkiy.ansibility.semantics.render.SegmentKind
import de.terletzkiy.ansibility.settings.AnsibilitySettingsListener
import de.terletzkiy.ansibility.settings.WorkspaceState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.event.HierarchyEvent
import java.beans.PropertyChangeListener
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * The right half of the template split editor (plan amendment R11, F11.2): a read-only viewer over an in-memory file
 * of the output's type (`deploy.sh.j2` → `deploy.sh`), so the output gets that type's full highlighting (lexer and
 * annotators). [PreviewProblemFilter] drops every warning and error the platform finds in rendered text, and a light
 * file is never indexed. It renders only while it is showing, 300 ms after the last change of the template, of any
 * PSI or of the root's selection.
 *
 * Next to the template it scrolls like a two-sided diff: a line diff of template and output aligns the unchanged
 * lines. Lines with placeholders or errors are marked like deleted lines of a diff.
 *
 * Vault values are holes, unless [showVaultValues] is on: then the preview decrypts the inline values the render
 * meets (unlocking like Reveal) and prints them. The switch belongs to this editor, so it is off whenever the file
 * is opened; the plaintexts live only here and go with the switch or the tab. While plaintext is shown,
 * [PreviewSecretsFilter] stops every highlighting pass but the lexer, and Copy is off.
 */
class RenderedPreviewEditor(private val project: Project, private val file: VirtualFile) : UserDataHolderBase(), FileEditor {
    private val outputFile: LightVirtualFile = LightVirtualFile(outputName(file), outputType(file), "").apply {
        putUserData(PREVIEW_OUTPUT, true)
    }
    private val document: Document = FileDocumentManager.getInstance().getDocument(outputFile) ?: EditorFactory.getInstance().createDocument("")
    private val viewer = EditorFactory.getInstance().createViewer(document, project, EditorKind.PREVIEW) as EditorEx
    /**
     * The status line under the preview. The preview sits in the split editor's splitter, so neither this line nor the
     * toolbar may set its minimum width (a long line kept the pane from being made narrower, user report 2026-10-09):
     * the text is cut with "…" and its tooltip shows it in full.
     */
    private val headline = object : JBLabel(AnsibilityRenderBundle.message("preview.loading")) {
        override fun getMinimumSize(): Dimension = Dimension(0, super.getMinimumSize().height)
    }.apply { border = JBUI.Borders.empty(2, 6) }
    private val panel = object : JPanel(BorderLayout()) {
        override fun getMinimumSize(): Dimension = Dimension(0, super.getMinimumSize().height)
    }
    private val requests = MutableStateFlow(0L)
    private var job: Job? = null

    /** The template's editor beside this preview, once the split editor is built. */
    private var source: Editor? = null

    /** The line diff of the template and the shown output; null until rendered, or when the diff is too big. */
    @Volatile
    private var fragments: List<LineFragment>? = null

    /** The last report shown; read by the toolbar actions on the EDT. */
    @Volatile
    var report: PreviewReport? = null
        private set

    /** "Render vault values" of this tab: off on every open, never stored. */
    @Volatile
    var showVaultValues: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            if (!value) {
                plaintexts.clear()
                failures.clear()
            }
            request()
        }

    /** Decrypted inline vault values by where they are written; only while [showVaultValues] is on. */
    private val plaintexts = ConcurrentHashMap<SourceLocation, String>()

    /** Vault values that failed to decrypt (or whose unlock was cancelled), not retried until the switch is turned again. */
    private val failures = ConcurrentHashMap<SourceLocation, VaultFailure>()

    /** The render target chosen in the picker; null: the first one. */
    @Volatile
    var pick: PreviewPick? = null
        set(value) {
            field = value
            request()
        }

    init {
        viewer.settings.apply {
            isLineNumbersShown = true
            isFoldingOutlineShown = false
            isLineMarkerAreaShown = false
            additionalLinesCount = 0
            isCaretRowShown = false
        }
        viewer.highlighter = EditorHighlighterFactory.getInstance().createEditorHighlighter(project, outputFile)

        val group = DefaultActionGroup(
            RenderContextAction(file), RenderTargetAction(this), RefreshRenderedAction(this), RenderVaultValuesAction(this), CopyRenderedAction(this),
        )
        val toolbar = ActionManager.getInstance().createActionToolbar(TOOLBAR_PLACE, group, true)
        toolbar.targetComponent = panel
        // Below the text: the first output line must sit level with the template's first line for the side-by-side scroll.
        val footer = JPanel(BorderLayout())
        footer.border = JBUI.Borders.customLineTop(JBColor.border())
        footer.add(headline, BorderLayout.NORTH)
        footer.add(toolbar.component, BorderLayout.SOUTH)
        panel.add(viewer.component, BorderLayout.CENTER)
        panel.add(footer, BorderLayout.SOUTH)
        panel.addHierarchyListener { e ->
            if (e.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong() != 0L && panel.isShowing) request()
        }

        FileDocumentManager.getInstance().getDocument(file)?.addDocumentListener(
            object : DocumentListener {
                override fun documentChanged(event: DocumentEvent) = request()
            },
            this,
        )
        val connection = project.messageBus.connect(this)
        connection.subscribe(PsiModificationTracker.TOPIC, PsiModificationTracker.Listener { request() })
        connection.subscribe(
            AnsibilitySettingsListener.TOPIC,
            object : AnsibilitySettingsListener {
                override fun workspaceStateChanged(old: WorkspaceState, new: WorkspaceState) = request()
            },
        )
        start()
    }

    /** Asks for a new render; coalesced, and skipped while the preview is not showing. */
    fun request() {
        requests.value = requests.value + 1
    }

    private fun start() {
        job = RenderPreviewScheduler.getInstance(project).scope.launch {
            requests.collectLatest {
                delay(DEBOUNCE_MS)
                if (!withContext(Dispatchers.EDT) { panel.isShowing }) return@collectLatest
                val templateText = readAction { FileDocumentManager.getInstance().getDocument(file)?.text } ?: return@collectLatest
                var next = render(templateText, report?.secretSources.orEmpty())
                if (showVaultValues) {
                    val missing = next.secretSources.values.distinct().filter { !plaintexts.containsKey(it) && !failures.containsKey(it) }
                    if (missing.isNotEmpty()) {
                        for (location in missing) decrypt(location)
                        next = render(templateText, next.secretSources)
                    }
                }
                val lines = alignment(templateText, next.text)
                val secret = next.secretsShown > 0
                edtWriteAction {
                    if (secret) outputFile.putUserData(PREVIEW_SECRETS, true)
                    // Same text, no write: the write itself is a PSI change, which would ask for another render.
                    if (!StringUtil.equals(document.immutableCharSequence, next.text)) {
                        CommandProcessor.getInstance().runUndoTransparentAction { document.setText(next.text) }
                    }
                    if (!secret) outputFile.putUserData(PREVIEW_SECRETS, null)
                }
                fragments = lines
                withContext(Dispatchers.EDT) { show(next) }
            }
        }
    }

    private suspend fun render(templateText: String, sources: Map<String, SourceLocation>): PreviewReport {
        val secrets = if (!showVaultValues) emptyMap() else sources.mapNotNull { (name, at) -> plaintexts[at]?.let { name to it } }.toMap()
        return try {
            smartReadAction(project) { TemplatePreviewService.getInstance(project).render(file, templateText, pick, secrets) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            PreviewReport("", null, emptyList(), pick, AnsibilityRenderBundle.message("preview.failed", e.message ?: e.javaClass.simpleName), e.message)
        }
    }

    /** Decrypts the inline vault value at [location] into [plaintexts], unlocking its root first when it is locked. */
    private suspend fun decrypt(location: SourceLocation) {
        // A whole-file vault decrypts to a YAML document, not to the one value; those stay holes.
        if (readAction { VaultEnvelopes.at(project, location)?.takeUnless { VaultEnvelopes.isWholeFileVault(location.file) } } == null) {
            failures[location] = VaultFailure.FORMAT
            return
        }
        val operations = VaultOperations.getInstance(project)
        var result = operations.decrypt(location, VaultPurpose.RENDER_PREVIEW)
        if (result is VaultDecryptResult.Failed && result.failure == VaultFailure.LOCKED) {
            val target = readAction {
                VaultEnvelopes.at(project, location)?.let { located -> AnsibleWorkspace.getInstance(project).rootFor(located.file)?.let { it to located.envelope } }
            }
            if (target != null) {
                val unlocked = VaultSecretsService.getInstance(project).unlock(target.first, label = null, verify = target.second)
                result = if (unlocked is VaultUnlockResult.Failed) VaultDecryptResult.Failed(unlocked.failure, result.tried) else operations.decrypt(location, VaultPurpose.RENDER_PREVIEW)
            }
        }
        when (result) {
            is VaultDecryptResult.Decrypted -> result.plaintext.use { plaintext ->
                if (showVaultValues) plaintexts[location] = plaintext.read { String(it, Charsets.UTF_8) }
            }
            is VaultDecryptResult.Failed -> failures[location] = result.failure
        }
    }

    private fun show(next: PreviewReport) {
        report = next
        headline.text = when {
            next.secretsShown > 0 -> AnsibilityRenderBundle.message("preview.vault.shown", next.headline, next.secretsShown)
            showVaultValues && next.secretSources.isNotEmpty() ->
                AnsibilityRenderBundle.message("preview.vault.failed", next.headline, next.secretSources.size)
            else -> next.headline
        }
        headline.toolTipText = headline.text
        val markup = viewer.markupModel
        markup.removeAllHighlighters()
        val rendered = next.rendered ?: return
        val length = document.textLength
        val problemLines = sortedSetOf<Int>()
        for (segment in rendered.segments) {
            val key = when (segment.kind) {
                SegmentKind.PLACEHOLDER -> PLACEHOLDER
                SegmentKind.ERROR -> ERROR
                SegmentKind.UNKNOWN_BRANCH -> UNKNOWN_BRANCH
                else -> continue
            }
            val start = segment.outStart.coerceIn(0, length)
            val end = segment.outEnd.coerceIn(start, length)
            if (start == end) continue
            val layer = if (segment.kind == SegmentKind.UNKNOWN_BRANCH) HighlighterLayer.ADDITIONAL_SYNTAX else HighlighterLayer.WARNING
            markup.addRangeHighlighter(key, start, end, layer, HighlighterTargetArea.EXACT_RANGE).apply {
                errorStripeTooltip = segment.note
            }
            if (segment.kind != SegmentKind.UNKNOWN_BRANCH) {
                for (line in document.getLineNumber(start)..document.getLineNumber(maxOf(start, end - 1))) problemLines += line
            }
        }
        for (line in problemLines) {
            markup.addRangeHighlighter(
                PROBLEM_LINE, document.getLineStartOffset(line), document.getLineEndOffset(line), HighlighterLayer.CARET_ROW - 1,
                HighlighterTargetArea.LINES_IN_RANGE,
            )
        }
    }

    /** Test seam: shows [next] as a finished render would. EDT. */
    @org.jetbrains.annotations.TestOnly
    internal fun showForTests(next: PreviewReport) {
        com.intellij.openapi.application.runWriteAction { document.setText(next.text) }
        show(next)
    }

    /** Test seam: the 0-based output lines marked as problem lines. */
    val problemLines: List<Int>
        get() = viewer.markupModel.allHighlighters.filter { it.textAttributesKey == PROBLEM_LINE }.map { document.getLineNumber(it.startOffset) }.sorted()

    /**
     * Pairs this preview with the template's [editor] (the left half of the split editor): from now on each scrolls
     * the other, aligned by the line diff of template and output, whenever both halves are showing.
     */
    fun attachSource(editor: Editor) {
        source = editor
        val scrollable = object : BaseSyncScrollable() {
            override fun isSyncScrollEnabled(): Boolean = fragments != null && panel.isShowing && editor.component.isShowing

            override fun processHelper(helper: ScrollHelper) {
                val lines = fragments ?: return
                for (fragment in lines) {
                    if (!helper.process(fragment.startLine1, fragment.startLine2)) return
                    if (!helper.process(fragment.endLine1, fragment.endLine2)) return
                }
                helper.process(editor.document.lineCount, document.lineCount)
            }
        }
        val support = SyncScrollSupport.TwosideSyncScrollSupport(listOf(editor, viewer), scrollable)
        val listener = VisibleAreaListener { event -> support.visibleAreaChanged(event) }
        editor.scrollingModel.addVisibleAreaListener(listener, this)
        viewer.scrollingModel.addVisibleAreaListener(listener, this)
        request()
    }

    /** Line pairs of [template] and [output] for the synced scroll; null when the texts are too big to compare. */
    private fun alignment(template: CharSequence, output: CharSequence): List<LineFragment>? = try {
        ComparisonManager.getInstance().compareLines(template, output, ComparisonPolicy.DEFAULT, DumbProgressIndicator.INSTANCE)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    /** Test seam: the text the preview shows. */
    val renderedText: String get() = document.text

    override fun getComponent(): JComponent = panel

    override fun getPreferredFocusedComponent(): JComponent = viewer.contentComponent

    override fun getName(): String = AnsibilityRenderBundle.message("preview.editor.name")

    override fun setState(state: FileEditorState) {}

    override fun isModified(): Boolean = false

    override fun isValid(): Boolean = file.isValid

    override fun getFile(): VirtualFile = file

    override fun selectNotify() = request()

    override fun addPropertyChangeListener(listener: PropertyChangeListener) {}

    override fun removePropertyChangeListener(listener: PropertyChangeListener) {}

    override fun dispose() {
        job?.cancel()
        source = null
        plaintexts.clear()
        if (outputFile.getUserData(PREVIEW_SECRETS) == true) {
            com.intellij.openapi.application.runWriteAction { document.setText("") }
        }
        EditorFactory.getInstance().releaseEditor(viewer)
        outputFile.isValid = false
    }

    companion object {
        const val TOOLBAR_PLACE: String = "AnsibilityRenderedPreview"
        private const val DEBOUNCE_MS = 300L

        val PLACEHOLDER: TextAttributesKey =
            TextAttributesKey.createTextAttributesKey("ANSIBILITY_RENDER_PLACEHOLDER", DefaultLanguageHighlighterColors.TEMPLATE_LANGUAGE_COLOR)
        val ERROR: TextAttributesKey = TextAttributesKey.createTextAttributesKey("ANSIBILITY_RENDER_ERROR", CodeInsightColors.ERRORS_ATTRIBUTES)
        val UNKNOWN_BRANCH: TextAttributesKey =
            TextAttributesKey.createTextAttributesKey("ANSIBILITY_RENDER_UNKNOWN_BRANCH", CodeInsightColors.WEAK_WARNING_ATTRIBUTES)
        val PROBLEM_LINE: TextAttributesKey = TextAttributesKey.createTextAttributesKey("ANSIBILITY_RENDER_PROBLEM_LINE", DiffColors.DIFF_DELETED)

        /** Marks the output file while it holds decrypted vault values. */
        internal val PREVIEW_SECRETS: Key<Boolean> = Key.create("ansibility.render.previewSecrets")

        /** Marks the in-memory output file of a preview. */
        internal val PREVIEW_OUTPUT: Key<Boolean> = Key.create("ansibility.render.previewOutput")

        /** `deploy.sh.j2` → `deploy.sh`; a template without `.j2` keeps its name. */
        internal fun outputName(template: VirtualFile): String = template.name.removeSuffix(".j2").ifEmpty { template.name }

        /** The type the output is highlighted as: the type of [outputName], plain text when that is unknown or a template type. */
        internal fun outputType(template: VirtualFile): FileType {
            val type = FileTypeManager.getInstance().getFileTypeByFileName(outputName(template))
            return type.takeUnless { it.isBinary || it == UnknownFileType.INSTANCE || it is TemplateLanguageFileType } ?: PlainTextFileType.INSTANCE
        }
    }
}

/** Rendered text is no source: the preview shows its colours, never the platform's warnings or errors about it. */
class PreviewProblemFilter : HighlightInfoFilter {
    override fun accept(info: HighlightInfo, file: PsiFile?): Boolean =
        info.severity < HighlightSeverity.WEAK_WARNING || file?.viewProvider?.virtualFile?.getUserData(RenderedPreviewEditor.PREVIEW_OUTPUT) != true
}

/** Decrypted vault values are shown, never analysed: no annotator, inspection or external tool sees the output then. */
class PreviewSecretsFilter : ProblemHighlightFilter() {
    override fun shouldHighlight(psiFile: PsiFile): Boolean = psiFile.viewProvider.virtualFile.getUserData(RenderedPreviewEditor.PREVIEW_SECRETS) != true

    override fun shouldProcessInBatch(psiFile: PsiFile): Boolean = shouldHighlight(psiFile)
}

/** Holds the coroutine scope the previews render in; the platform cancels it with the project. */
@com.intellij.openapi.components.Service(com.intellij.openapi.components.Service.Level.PROJECT)
class RenderPreviewScheduler(val scope: kotlinx.coroutines.CoroutineScope) {
    companion object {
        fun getInstance(project: Project): RenderPreviewScheduler = project.getService(RenderPreviewScheduler::class.java)
    }
}
