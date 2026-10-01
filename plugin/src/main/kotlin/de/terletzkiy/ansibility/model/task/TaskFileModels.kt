package de.terletzkiy.ansibility.model.task

import com.intellij.openapi.util.Key
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiTreeUtil
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.FileKind
import de.terletzkiy.ansibility.context.TargetVersionDetector
import de.terletzkiy.ansibility.yaml.PsiYValueAdapter
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLPsiElement

/**
 * Entry point to the task model: the cached [TaskFileModel] of a YAML file (plan A.9 per-file models).
 *
 * The file is read as the kind its [FileKind] says (`PLAYBOOK`/`MOLECULE_PLAYBOOK` → playbook, `ROLE_HANDLERS`
 * → handlers, other task kinds → tasks); for other files the content decides (a sequence of plays is a
 * playbook). Keywords come from the root's target ansible-core version ([TargetVersionDetector]). The model is
 * cached on the PSI file until it, the Ansible structure or a root's target version
 * ([TargetVersionDetector.modificationTracker], e.g. a settings override) changes.
 *
 * Call inside a read action.
 */
object TaskFileModels {
    private val AUTO = Key.create<CachedValue<TaskFileModel>>("ansibility.model.taskFile")
    private val EXPLICIT: Map<TaskFileKind, Key<CachedValue<TaskFileModel>>> =
        TaskFileKind.entries.associateWith { Key.create<CachedValue<TaskFileModel>>("ansibility.model.taskFile.$it") }

    /** The model of [file], read as its file kind (or its content) suggests. */
    fun of(file: YAMLFile): TaskFileModel = CachedValuesManager.getCachedValue(file, AUTO) { compute(file, null) }

    /** The model of [file], read as [kind] regardless of its file kind. */
    fun of(file: YAMLFile, kind: TaskFileKind): TaskFileModel =
        CachedValuesManager.getCachedValue(file, EXPLICIT.getValue(kind)) { compute(file, kind) }

    /** How [kind] files are read; null when only the content can tell. */
    fun kindFor(kind: FileKind?): TaskFileKind? = when (kind) {
        FileKind.PLAYBOOK, FileKind.MOLECULE_PLAYBOOK -> TaskFileKind.PLAYBOOK
        FileKind.ROLE_HANDLERS -> TaskFileKind.HANDLERS
        FileKind.ROLE_TASKS, FileKind.MOLECULE_TASKS -> TaskFileKind.TASKS
        else -> null
    }

    private fun compute(file: YAMLFile, explicit: TaskFileKind?): CachedValueProvider.Result<TaskFileModel> {
        val workspace = AnsibleWorkspace.getInstance(file.project)
        val virtualFile = file.originalFile.virtualFile
        val context = virtualFile?.let(workspace::contextOf)
        val detector = TargetVersionDetector.getInstance(file.project)
        val version = context?.root?.let { detector.targetVersion(it).version }
        val builder = TaskModelBuilder(TaskSyntaxService.getInstance().forVersion(version))
        val document = PsiYValueAdapter.documentValue(file)
        val kind = explicit ?: kindFor(context?.kind)
            ?: if (builder.looksLikePlaybook(document)) TaskFileKind.PLAYBOOK else TaskFileKind.TASKS
        return CachedValueProvider.Result.create(builder.build(document, kind), file, workspace.structureTracker, detector.modificationTracker)
    }
}

/** Finds the PSI a model range was built from. */
object ModelAnchors {
    /**
     * The YAML element that spans exactly [range] in [file] (a key-value, mapping, sequence item or scalar), or
     * the innermost YAML element containing its start when no element matches exactly.
     */
    fun element(file: PsiFile, range: TextRange): PsiElement? =
        PsiTreeUtil.findElementOfClassAtRange(file, range.startOffset, range.endOffset, YAMLPsiElement::class.java)
            ?: file.findElementAt(range.startOffset)?.let { PsiTreeUtil.getParentOfType(it, YAMLPsiElement::class.java, false) }
}
