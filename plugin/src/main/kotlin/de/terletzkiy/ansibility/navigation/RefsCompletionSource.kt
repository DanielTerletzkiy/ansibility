package de.terletzkiy.ansibility.navigation

import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.icons.AllIcons
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.AnsibleSite
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.CompletionSource
import de.terletzkiy.ansibility.api.FileContext
import de.terletzkiy.ansibility.api.RoleRegistry
import de.terletzkiy.ansibility.api.VarService
import de.terletzkiy.ansibility.context.AnsibleLayout
import de.terletzkiy.ansibility.model.container.ContainerPathMapper
import de.terletzkiy.ansibility.model.container.MappingSource
import de.terletzkiy.ansibility.semantics.schema.OptionSpec
import javax.swing.Icon

/**
 * The role-navigation area's `completionSource` (plan F1.8, X50): completes the values [RefsSiteClassifier] knows,
 * from the same resolution rules as Ctrl+B:
 * - `include_tasks`/`import_tasks`: the task files of the own role's `tasks/` (outside roles, the YAML files next to
 *   the file);
 * - `tasks_from`/`handlers_from`/`vars_from`/`defaults_from`: the files of the named role's subdirectory (also
 *   matched without extension);
 * - `template`/`copy` `src`: the files of the role's `templates/` or `files/`, written `templates/<path>` unless the
 *   typed text says otherwise;
 * - `notify`: the handler names and `listen` topics of the own role (bold), then those of the play scope; `role : name`
 *   forms once the typed text has a colon;
 * - roles: the role names of the root (in molecule files also `/ansible/roles/<role>`);
 * - template-name values: the templates below the rendering task's `src` prefix (left to the vars-file value
 *   completion when a spec declares `choices` for the key);
 * - `vars_files`/`import_playbook`: YAML files relative to the playbook; `{% include %}`: the role's templates.
 *
 * It analyses the completion copy (which holds the dummy identifier, so an empty value is a scalar there) and returns
 * at once for other sites and files. The prefix is the value's text up to the caret, so names with spaces, dots and
 * slashes match as a whole. Not `DumbAware`: handler and template-name items need the indexes.
 */
class RefsCompletionSource : CompletionSource {
    override fun complete(site: AnsibleSite?, parameters: CompletionParameters, result: CompletionResultSet) {
        if (site != null && !RefsNavigation.isOurs(site)) return
        val original = parameters.originalFile
        val project = original.project
        val injected = InjectedLanguageManager.getInstance(project)
        val hostOriginal = injected.getTopLevelFile(original) ?: original
        val context = AnsibleWorkspace.getInstance(project).contextOf(hostOriginal.viewProvider.virtualFile) ?: return
        if (context.kind !in RefSites.KINDS) return
        val position = parameters.position
        val copy = injected.getTopLevelFile(position) ?: position.containingFile ?: return
        val offset = if (copy == position.containingFile) parameters.offset else injected.injectedToHost(position, parameters.offset)
        val occurrence = RefSites.at(copy, offset, context) ?: return
        if (occurrence.isDynamic || offset < occurrence.range.startOffset) return
        val text = copy.viewProvider.contents
        val prefix = text.subSequence(occurrence.range.startOffset, offset.coerceAtMost(text.length)).toString()
        val items = Items(project, context, RefResolver(copy, context), prefix).of(occurrence)
        if (items.isEmpty()) return
        val sink = result.withPrefixMatcher(prefix)
        items.forEach { ProgressManager.checkCanceled(); sink.addElement(it) }
    }

    /** Builds the lookup items of one value. */
    private class Items(
        private val project: Project,
        private val context: FileContext,
        private val resolver: RefResolver,
        private val prefix: String,
    ) {
        private val root: AnsibleRoot = context.root
        private val seen = HashSet<String>()
        private val out = ArrayList<LookupElement>()

        fun of(occurrence: RefOccurrence): List<LookupElement> {
            when (occurrence.kind) {
                RefKind.TASK_INCLUDE, RefKind.VARS_FILE, RefKind.IMPORT_PLAYBOOK -> resolver.suggestions(occurrence)
                    .forEach { file(it, typeText(occurrence.kind), YAML_ICON, PRIORITY_OWN) }
                RefKind.TASKS_FROM, RefKind.HANDLERS_FROM, RefKind.VARS_FROM, RefKind.DEFAULTS_FROM -> roleFiles(occurrence)
                RefKind.ROLE -> roles(occurrence)
                RefKind.TEMPLATE_SRC, RefKind.COPY_SRC -> sources(occurrence)
                RefKind.NOTIFY -> handlers(occurrence)
                RefKind.TEMPLATE_NAME -> templateNames(occurrence)
                RefKind.TEMPLATE_INCLUDE -> resolver.suggestions(occurrence)
                    .forEach { file(it, AnsibilityNavigationBundle.message("completion.type.template"), iconOf(it), PRIORITY_OWN) }
                RefKind.LISTEN -> Unit
            }
            return out
        }

        private fun roleFiles(occurrence: RefOccurrence) {
            val written = occurrence.role ?: return
            val roleDir = (resolver.locateRole(written, RoleSite.INCLUDE_ROLE) as? RoleLocator.Result.Found)?.dir ?: return
            val subdir = when (occurrence.kind) {
                RefKind.TASKS_FROM -> RefResolver.SUBDIR_TASKS
                RefKind.HANDLERS_FROM -> RefResolver.SUBDIR_HANDLERS
                RefKind.VARS_FROM -> RefResolver.SUBDIR_VARS
                else -> RefResolver.SUBDIR_DEFAULTS
            }
            val dir = roleDir.findChild(subdir)?.takeIf { it.isDirectory } ?: return
            val type = AnsibilityNavigationBundle.message("completion.type.role.file", subdir, roleDir.name)
            for (relative in FileListing.relativeFiles(dir, { AnsibleLayout.isYamlName(it) || it.endsWith(".json") })) {
                val stem = AnsibleLayout.stem(relative)
                add(relative, LookupElementBuilder.create(relative).withLookupString(stem).withIcon(YAML_ICON).withTypeText(type, true), PRIORITY_OWN)
            }
        }

        private fun roles(occurrence: RefOccurrence) {
            val own = context.roleDir?.name?.takeIf { occurrence.roleSite == RoleSite.DEPENDENCY }
            val containerPrefix = if (prefix.startsWith("/")) moleculeRolesPath() else null
            if (prefix.startsWith("/") && containerPrefix == null) return
            val type = AnsibilityNavigationBundle.message("completion.type.role")
            for (role in RoleRegistry.getInstance(project).roles(root)) {
                ProgressManager.checkCanceled()
                if (role.name == own) continue
                val lookup = containerPrefix?.let { "$it/${role.name}" } ?: role.name
                add(lookup, LookupElementBuilder.create(lookup).withIcon(AllIcons.Nodes.Module).withTypeText(type, true), PRIORITY_OWN)
            }
        }

        /** `/ansible/roles` for a molecule file (the directory molecule sees the roles dir at), else null. */
        private fun moleculeRolesPath(): String? =
            ContainerPathMapper.getInstance(project).mappings(resolver.virtualFile).firstOrNull { it.source == MappingSource.MOLECULE }?.containerPath

        private fun sources(occurrence: RefOccurrence) {
            val dirname = if (occurrence.kind == RefKind.COPY_SRC) RefResolver.SUBDIR_FILES else RefResolver.SUBDIR_TEMPLATES
            val prefixed = prefix.startsWith("$dirname/") || "$dirname/".startsWith(prefix)
            val type = typeText(occurrence.kind)
            for (lookup in resolver.sourceNames(dirname, prefixed)) file(lookup, type, iconOf(lookup), PRIORITY_OWN)
        }

        private fun handlers(occurrence: RefOccurrence) {
            val scope = resolver.handlerScope(occurrence.playIndex)
            val qualifiedOnly = ':' in prefix
            for (match in scope.completionCandidates()) {
                ProgressManager.checkCanceled()
                val def = match.def
                val priority = when (match.tier) {
                    HandlerScope.Tier.OWN, HandlerScope.Tier.QUALIFIED -> PRIORITY_OWN
                    HandlerScope.Tier.PLAY -> PRIORITY_PLAY
                    else -> PRIORITY_SCOPE
                }
                val owner = def.role ?: AnsibilityNavigationBundle.message("completion.type.play.handler")
                val tail = if (def.listen) AnsibilityNavigationBundle.message("completion.tail.listen") else null
                val lookup = if (qualifiedOnly && def.role != null) HandlerScope.qualify(def.role, def.name) else def.name
                if (qualifiedOnly && def.role == null) continue
                val element = LookupElementBuilder.create(lookup)
                    .withIcon(if (def.listen) AllIcons.Nodes.Tag else AllIcons.Nodes.Function)
                    .withTypeText(owner, true)
                    .withTailText(tail, true)
                    .withBoldness(priority == PRIORITY_OWN)
                add(lookup, element, priority)
            }
        }

        private fun templateNames(occurrence: RefOccurrence) {
            val slot = occurrence.slot ?: return
            if (declaresChoices(slot.pattern)) return
            val type = AnsibilityNavigationBundle.message("completion.type.template")
            for ((value, file) in resolver.templateNameValues(slot)) {
                add(value, LookupElementBuilder.create(value).withIcon(file.fileType.icon ?: AllIcons.FileTypes.Any_type).withTypeText(type, true), PRIORITY_OWN)
            }
        }

        /** True when a spec of the root declares `choices` for the option at [pattern] (the vars-file value completion offers them). */
        private fun declaresChoices(pattern: List<String>): Boolean {
            val names = pattern.filter { it != TemplateNames.ITEM }
            val bindings = VarService.getInstance(project).symbol(root, names.firstOrNull() ?: return false).specBindings
            return bindings.any { binding ->
                var option: OptionSpec? = binding.option
                for (name in names.drop(1)) option = option?.options?.get(name)
                option?.choices != null
            }
        }

        private fun file(lookup: String, typeText: String, icon: Icon, priority: Double) {
            add(lookup, LookupElementBuilder.create(lookup).withIcon(icon).withTypeText(typeText, true), priority)
        }

        private fun add(lookup: String, element: LookupElementBuilder, priority: Double) {
            if (lookup.isEmpty() || !seen.add(lookup)) return
            out += PrioritizedLookupElement.withPriority(element, priority)
        }

        private fun typeText(kind: RefKind): String = when (kind) {
            RefKind.TASK_INCLUDE -> AnsibilityNavigationBundle.message("completion.type.task.file")
            RefKind.COPY_SRC -> AnsibilityNavigationBundle.message("completion.type.file")
            RefKind.TEMPLATE_SRC -> AnsibilityNavigationBundle.message("completion.type.template")
            RefKind.VARS_FILE -> AnsibilityNavigationBundle.message("completion.type.vars.file")
            RefKind.IMPORT_PLAYBOOK -> AnsibilityNavigationBundle.message("completion.type.playbook")
            else -> ""
        }

        private fun iconOf(name: String): Icon = FileTypeManager.getInstance().getFileTypeByFileName(name.substringAfterLast('/')).icon ?: AllIcons.FileTypes.Any_type
    }

    private companion object {
        const val PRIORITY_OWN = 100.0
        const val PRIORITY_PLAY = 80.0
        const val PRIORITY_SCOPE = 60.0
        val YAML_ICON: Icon get() = FileTypeManager.getInstance().getFileTypeByExtension("yml").icon ?: AllIcons.FileTypes.Any_type
    }
}
