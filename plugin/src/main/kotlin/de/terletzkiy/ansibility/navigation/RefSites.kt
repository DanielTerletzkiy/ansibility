package de.terletzkiy.ansibility.navigation

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiTreeUtil
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.FileContext
import de.terletzkiy.ansibility.api.FileKind
import de.terletzkiy.ansibility.context.TargetVersionDetector
import de.terletzkiy.ansibility.model.role.RoleMeta
import de.terletzkiy.ansibility.model.task.BlockNode
import de.terletzkiy.ansibility.model.task.NameRef
import de.terletzkiy.ansibility.model.task.SrcKind
import de.terletzkiy.ansibility.model.task.SrcRef
import de.terletzkiy.ansibility.model.task.TaskFileModels
import de.terletzkiy.ansibility.model.task.TaskItem
import de.terletzkiy.ansibility.model.task.TaskModelBuilder
import de.terletzkiy.ansibility.model.task.TaskNode
import de.terletzkiy.ansibility.model.task.YamlFiles
import de.terletzkiy.ansibility.semantics.yaml.YScalar
import org.jetbrains.yaml.YAMLLanguage
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLScalar
import org.jetbrains.yaml.psi.YAMLSequence
import org.jetbrains.yaml.psi.YAMLSequenceItem

/**
 * Finds the reference values of one file (plan F1.8, X50), without looking at any other file:
 * - task-like files ([TASK_KINDS]), read through the task model ([TaskFileModels]): `include_tasks`/`import_tasks`
 *   targets, `include_role`/`import_role` `name` and `tasks_from`/`handlers_from`/`vars_from`/`defaults_from`,
 *   `template`/`copy` `src` (a static value, or the static prefix of a value with Jinja after it; never a copy with a
 *   truthy or templated `remote_src`), `notify` items of tasks and blocks, handler `listen` topics, plays' `roles:`
 *   entries and `vars_files`, `import_playbook` targets;
 * - `meta/main.yml` ([FileKind.ROLE_META]): the `dependencies` role references;
 * - role templates ([FileKind.ROLE_TEMPLATE]), at text level: the quoted name of `{% include %}`, `{% import %}`,
 *   `{% from %}` and `{% extends %}` tags outside `{% raw %}`.
 *
 * Only values: keys are never references, and a value with Jinja (other than a dynamic `src` prefix) is skipped, so
 * Jinja ranges stay with the variables area. Template-name values in vars files depend on other files and come from
 * [TemplateNames]. The result is cached on the PSI file (also on completion copies) until it, the Ansible structure
 * or the root's target version changes. Call inside a read action.
 */
internal object RefSites {
    private val OCCURRENCES = Key.create<CachedValue<List<RefOccurrence>>>("ansibility.navigation.occurrences")

    /** File kinds whose YAML holds tasks, handlers or plays. */
    val TASK_KINDS: Set<FileKind> = setOf(
        FileKind.ROLE_TASKS, FileKind.ROLE_HANDLERS, FileKind.PLAYBOOK, FileKind.MOLECULE_PLAYBOOK, FileKind.MOLECULE_TASKS,
    )

    /** Vars-like file kinds whose values may name templates ([RefKind.TEMPLATE_NAME]). */
    val VARS_KINDS: Set<FileKind> = setOf(
        FileKind.GROUP_VARS, FileKind.HOST_VARS, FileKind.ROLE_DEFAULTS, FileKind.ROLE_VARS, FileKind.MOLECULE_VARS,
    )

    /** Every file kind that can hold a reference value. */
    val KINDS: Set<FileKind> = TASK_KINDS + VARS_KINDS + setOf(FileKind.ROLE_META, FileKind.ROLE_TEMPLATE)

    /** `{% include 'x' %}` and friends; group 3 is the name, group 4 the rest of the tag. */
    private val TEMPLATE_TAG = Regex("""\{%[-+]?\s*(include|import|from|extends)\s+(['"])([^'"\r\n]*)\2([^%]*)%}""")
    private val RAW_BLOCK = Regex("""\{%[-+]?\s*raw\s*[-+]?%}.*?\{%[-+]?\s*endraw\s*[-+]?%}""", RegexOption.DOT_MATCHES_ALL)

    /** The reference values of [file] (not template-name values), in file order. */
    fun occurrences(file: PsiFile): List<RefOccurrence> = CachedValuesManager.getCachedValue(file, OCCURRENCES) {
        val project = file.project
        val workspace = AnsibleWorkspace.getInstance(project)
        val virtualFile = file.originalFile.viewProvider.virtualFile
        val context = workspace.contextOf(virtualFile)
        val result = if (context == null) emptyList() else compute(file, context)
        CachedValueProvider.Result.create(result, file, workspace.structureTracker, TargetVersionDetector.getInstance(project).modificationTracker)
    }

    /**
     * The reference value at [offset] of [file] (its range contains the offset, the end included for a caret after
     * the value). In vars files this is a template-name value, which needs the indexes ([TemplateNames]).
     */
    fun at(file: PsiFile, offset: Int, context: FileContext): RefOccurrence? {
        if (context.kind in VARS_KINDS) return TemplateNames.at(file, offset, context)
        if (context.kind !in KINDS) return null
        return occurrences(file).firstOrNull { offset >= it.range.startOffset && offset <= it.range.endOffset }
    }

    private fun compute(file: PsiFile, context: FileContext): List<RefOccurrence> {
        val text = file.viewProvider.contents
        return when (context.kind) {
            FileKind.ROLE_TEMPLATE -> templateOccurrences(text)
            FileKind.ROLE_META -> yamlOf(file)?.let { metaOccurrences(it, text) }.orEmpty()
            in TASK_KINDS -> yamlOf(file)?.let { TaskOccurrences(it, text).build() }.orEmpty()
            else -> emptyList()
        }
    }

    /**
     * The YAML PSI of [file]: the file itself, the YAML view of a multi-language file, or (for a physical file of
     * another type) the shared YAML copy of [YamlFiles]; a completion copy never falls back to its original.
     */
    fun yamlOf(file: PsiFile): YAMLFile? {
        (file as? YAMLFile)?.let { return it }
        (file.viewProvider.getPsi(YAMLLanguage.INSTANCE) as? YAMLFile)?.let { return it }
        if (file.originalFile != file) return null
        return YamlFiles.yamlFile(file.project, file.viewProvider.virtualFile)
    }

    // ------------------------------------------------------------------------------------------------ task files

    private class TaskOccurrences(private val yaml: YAMLFile, private val text: CharSequence) {
        private val out = ArrayList<RefOccurrence>()

        fun build(): List<RefOccurrence> {
            val model = TaskFileModels.of(yaml)
            if (!model.isSequence) return emptyList()
            items(model.items, null)
            for (play in model.plays) {
                ProgressManager.checkCanceled()
                play.roles.forEach { entry -> entry.name?.let { add(RefKind.ROLE, it, play.index, roleSite = RoleSite.PLAY_ROLE) } }
                play.varsFiles.forEach { add(RefKind.VARS_FILE, it, play.index, alternativeGroup = alternativeGroupOf(it)) }
                play.sections().forEach { items(it, play.index) }
            }
            model.imports.forEach { node -> node.path?.let { add(RefKind.IMPORT_PLAYBOOK, it, null) } }
            return out.sortedBy { it.range.startOffset }
        }

        private fun items(items: List<TaskItem>, playIndex: Int?) {
            for (item in items) {
                ProgressManager.checkCanceled()
                when (item) {
                    is TaskNode -> task(item, playIndex)
                    is BlockNode -> {
                        item.notify.forEach { add(RefKind.NOTIFY, it, playIndex) }
                        items(item.block, playIndex)
                        items(item.rescue, playIndex)
                        items(item.always, playIndex)
                    }
                }
            }
        }

        private fun task(task: TaskNode, playIndex: Int?) {
            task.taskInclude?.file?.let { add(RefKind.TASK_INCLUDE, it, playIndex) }
            task.roleInclude?.let { call ->
                val role = call.name?.text
                call.name?.let { add(RefKind.ROLE, it, playIndex, roleSite = RoleSite.INCLUDE_ROLE) }
                call.tasksFrom?.let { add(RefKind.TASKS_FROM, it, playIndex, role = role) }
                call.handlersFrom?.let { add(RefKind.HANDLERS_FROM, it, playIndex, role = role) }
                call.varsFrom?.let { add(RefKind.VARS_FROM, it, playIndex, role = role) }
                call.defaultsFrom?.let { add(RefKind.DEFAULTS_FROM, it, playIndex, role = role) }
            }
            task.src?.let { src(task, it, playIndex) }
            task.notify.forEach { add(RefKind.NOTIFY, it, playIndex) }
            task.listen.forEach { add(RefKind.LISTEN, it, playIndex) }
        }

        private fun src(task: TaskNode, src: SrcRef, playIndex: Int?) {
            val kind = if (src.isCopy) RefKind.COPY_SRC else RefKind.TEMPLATE_SRC
            if (src.isCopy && isRemoteSource(task)) return
            val range = src.value.range?.let { TextRange(it.start, it.end) } ?: return
            when (src.kind) {
                SrcKind.STATIC -> if (src.text.isNotBlank()) out += RefOccurrence(kind, src.text, locate(text, src.text, range), playIndex = playIndex)
                SrcKind.DYNAMIC_PREFIX -> {
                    val start = TaskModelBuilder.textStart(src.value) ?: return
                    val prefix = src.staticPrefix
                    if (start + prefix.length > text.length || text.subSequence(start, start + prefix.length).toString() != prefix) return
                    out += RefOccurrence(kind, src.text, TextRange(start, start + prefix.length), dynamicPrefix = prefix, playIndex = playIndex)
                }
                SrcKind.WHOLE_VAR, SrcKind.TEMPLATED -> Unit
            }
        }

        /** `remote_src` truthy, or templated (it may be truthy at runtime): the source lives on the managed host. */
        private fun isRemoteSource(task: TaskNode): Boolean {
            val value = (task.module?.args?.option(REMOTE_SRC) as? YScalar)?.text?.trim() ?: return false
            return RefOccurrence.isTemplated(value) || value.lowercase() in TRUTHY
        }

        private fun add(
            kind: RefKind,
            ref: NameRef,
            playIndex: Int?,
            role: String? = null,
            roleSite: RoleSite? = null,
            alternativeGroup: Int? = null,
        ) {
            val value = ref.text.trim()
            if (value.isEmpty() || RefOccurrence.isTemplated(value)) return
            out += RefOccurrence(
                kind = kind,
                text = value,
                range = locate(text, value, ref.range),
                role = role,
                roleSite = roleSite,
                playIndex = playIndex,
                alternativeGroup = alternativeGroup,
            )
        }

        /** The start of the nested list holding a `vars_files` item (alternatives), or null for a plain item. */
        private fun alternativeGroupOf(ref: NameRef): Int? {
            val scalar = PsiTreeUtil.findElementOfClassAtRange(yaml, ref.range.startOffset, ref.range.endOffset, YAMLScalar::class.java)
            val sequence = (scalar?.parent as? YAMLSequenceItem)?.parent as? YAMLSequence ?: return null
            return if (sequence.parent is YAMLSequenceItem) sequence.textRange.startOffset else null
        }
    }

    // ------------------------------------------------------------------------------------------------ meta, templates

    private fun metaOccurrences(yaml: YAMLFile, text: CharSequence): List<RefOccurrence> =
        RoleMeta.dependencies(yaml).mapNotNull { dependency ->
            val range = dependency.range ?: return@mapNotNull null
            val written = dependency.written.trim()
            if (RefOccurrence.isTemplated(written)) return@mapNotNull null
            RefOccurrence(RefKind.ROLE, written, locate(text, written, range), roleSite = RoleSite.DEPENDENCY)
        }

    private fun templateOccurrences(text: CharSequence): List<RefOccurrence> {
        if (!text.contains("{%")) return emptyList()
        val raw = RAW_BLOCK.findAll(text).map { it.range }.toList()
        return TEMPLATE_TAG.findAll(text).mapNotNull { match ->
            ProgressManager.checkCanceled()
            if (raw.any { match.range.first in it }) return@mapNotNull null
            val name = match.groups[3] ?: return@mapNotNull null
            if (name.value.isBlank() || RefOccurrence.isTemplated(name.value)) return@mapNotNull null
            val optional = match.groupValues[1] == "include" && IGNORE_MISSING.containsMatchIn(match.groupValues[4])
            RefOccurrence(RefKind.TEMPLATE_INCLUDE, name.value, TextRange(name.range.first, name.range.last + 1), optional = optional)
        }.toList()
    }

    /**
     * The range of [value] inside the scalar at [range]: inside its quotes, at the loaded text when it appears
     * verbatim (a free-form `include_tasks` string, a tagged scalar), else the whole content.
     */
    fun locate(text: CharSequence, value: String, range: TextRange): TextRange {
        if (range.isEmpty || range.endOffset > text.length) return range
        var start = range.startOffset
        var end = range.endOffset
        val first = text[start]
        if ((first == '\'' || first == '"') && end - start >= 2 && text[end - 1] == first) {
            start++
            end--
        }
        if (value.isNotEmpty()) {
            val at = text.subSequence(start, end).indexOf(value)
            if (at >= 0) return TextRange(start + at, start + at + value.length)
        }
        return TextRange(start, end)
    }

    private const val REMOTE_SRC = "remote_src"

    /** Spellings `boolean()` reads as true. */
    private val TRUTHY = setOf("yes", "on", "1", "true", "y", "t")
    private val IGNORE_MISSING = Regex("""\bignore\s+missing\b""")
}
