package de.terletzkiy.ansibility.index

import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.FileTypeRegistry
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFileFactory
import com.intellij.util.Consumer
import com.intellij.util.indexing.FileBasedIndex
import com.intellij.util.indexing.FileContent
import de.terletzkiy.ansibility.context.AnsibleLayout
import de.terletzkiy.ansibility.lang.jinja.AnsibleJinjaFileType
import de.terletzkiy.ansibility.semantics.yaml.YValue
import de.terletzkiy.ansibility.yaml.PsiYValueAdapter
import org.jetbrains.yaml.YAMLFileType
import org.jetbrains.yaml.YAMLLanguage
import org.jetbrains.yaml.psi.YAMLFile

/**
 * One file as the Ansible indexers see it: its [facts], its [text] and, for YAML content, its PSI and loaded value.
 *
 * Only the file's own path and content are available, so every indexer is pure per file (plan A.7). Vars files that
 * Ansible loads without a YAML extension (`group_vars/all`, `host_vars/web1.json`) are parsed as YAML text even when
 * the IDE types them as plain text or JSON.
 */
class IndexInput(val facts: PathFacts, val text: CharSequence, private val yamlSource: () -> YAMLFile?) {
    /** The YAML PSI, or null for templates and for files that are not YAML. */
    val yaml: YAMLFile? by lazy(LazyThreadSafetyMode.NONE) { if (facts.hint == PathHint.TEMPLATE) null else yamlSource() }

    /** The first document's value as Ansible loads it (aliases and merge keys applied), or null. */
    val document: YValue? by lazy(LazyThreadSafetyMode.NONE) { yaml?.let(PsiYValueAdapter::documentValue) }

    /** True when the whole file is a Jinja template. */
    val isTemplate: Boolean get() = facts.hint == PathHint.TEMPLATE

    companion object {
        private val REPARSED_YAML = Key.create<YAMLFile>("ansibility.index.reparsedYaml")

        /** The input for one indexed [content]. */
        fun of(content: FileContent): IndexInput {
            val facts = PathFacts.of(content.file)
            return IndexInput(facts, content.contentAsText) {
                if (content.fileType == YAMLFileType.YML) {
                    content.psiFile as? YAMLFile
                } else {
                    // Shared by the six indexers, which all receive the same content object for one file.
                    content.getUserData(REPARSED_YAML)
                        ?: parseAsYamlIfNamed(facts, content.project, content.contentAsText)?.also { content.putUserData(REPARSED_YAML, it) }
                }
            }
        }

        /** An input built from [text] alone (tests and corpus measurements), parsed with [project]'s PSI factory. */
        fun of(path: String, text: CharSequence, project: Project): IndexInput {
            val facts = PathFacts.of(path)
            return IndexInput(facts, text) {
                if (facts.isYamlName || isVarsWithoutYamlName(facts)) parse(project, facts.fileName, text) else null
            }
        }

        /** YAML content under another file type: a `.yml` name typed otherwise, or a vars file without YAML name. */
        private fun parseAsYamlIfNamed(facts: PathFacts, project: Project?, text: CharSequence): YAMLFile? =
            if (facts.isYamlName || isVarsWithoutYamlName(facts)) {
                parse(project ?: ProjectManager.getInstance().defaultProject, facts.fileName, text)
            } else {
                null
            }

        private fun parse(project: Project, name: String, text: CharSequence): YAMLFile? =
            PsiFileFactory.getInstance(project).createFileFromText(name, YAMLLanguage.INSTANCE, text) as? YAMLFile

        /**
         * A vars file that ansible-core may load although its name has no YAML extension. Below `group_vars` and
         * `host_vars` this is lenient (any name that could be a host, such as `preview-dev1.bike.example.de`, or a
         * `.json` file); the query-time file kind drops names ansible-core would skip. Below `defaults` and `vars`
         * only names without an extension and `.json` files count.
         */
        internal fun isVarsWithoutYamlName(facts: PathFacts): Boolean {
            if (facts.isYamlName) return false
            val name = facts.fileName
            return when (facts.hint) {
                PathHint.GROUP_VARS, PathHint.HOST_VARS -> AnsibleLayout.hostFromFileName(name) != null
                PathHint.DEFAULTS, PathHint.VARS ->
                    !AnsibleLayout.isIgnoredVarsEntry(name) &&
                        name.substringAfterLast('.', "").let { it.isEmpty() || it.equals("json", ignoreCase = true) }
                else -> false
            }
        }
    }
}

/**
 * The input filter of the Ansible indexes (plan A.7): YAML files, plus (when [templates] is set) Jinja templates of any
 * file type, plus vars files without a YAML extension. [acceptInput] is a cheap path check; nothing under
 * `.claude/worktrees` or elsewhere is ignored here, because detached roots are indexed like any other and scoped away at
 * query time.
 */
class AnsibleIndexInputFilter(private val templates: Boolean) : FileBasedIndex.FileTypeSpecificInputFilter {
    override fun registerFileTypesUsedForIndexing(fileTypeSink: Consumer<in FileType>) {
        val types = LinkedHashSet<FileType>()
        types += YAMLFileType.YML
        types += PlainTextFileType.INSTANCE
        types += AnsibleJinjaFileType
        // Templates and vars files keep whatever type the IDE gives them (Shell, JSON, INI, Dockerfile, …).
        FileTypeRegistry.getInstance().registeredFileTypes.filterTo(types) { !it.isBinary }
        types.forEach(fileTypeSink::consume)
    }

    override fun acceptInput(file: VirtualFile): Boolean {
        if (file.isDirectory || file.length > MAX_FILE_SIZE) return false
        if (templates && PathFacts.isJ2(file.name)) return true
        return accepts(PathFacts.of(file), templates)
    }

    companion object {
        /** The path rule of [acceptInput] for a regular file with [facts]. */
        fun accepts(facts: PathFacts, templates: Boolean): Boolean = when (facts.hint) {
            PathHint.TEMPLATE -> templates
            PathHint.FILES -> false
            else -> facts.isYamlName || IndexInput.isVarsWithoutYamlName(facts)
        }

        /** Larger files are never Ansible content worth indexing (the biggest vars file of the target repo is ~100 KB). */
        const val MAX_FILE_SIZE: Long = 2L * 1024 * 1024
    }
}
