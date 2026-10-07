package de.terletzkiy.ansibility.run

import com.intellij.psi.PsiElement
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLMapping
import org.jetbrains.yaml.psi.YAMLScalar
import org.jetbrains.yaml.psi.YAMLSequence
import org.jetbrains.yaml.psi.YAMLSequenceItem
import org.jetbrains.yaml.psi.YAMLValue

/** One top-level item of a playbook: a play, or an `import_playbook` entry. */
class PlaybookPlay(
    val index: Int,
    /** The play's `name:`, the imported playbook's path, or empty. */
    val name: String,
    /** What the UI shows: [name], else `hosts: …`, else `#n`. */
    val label: String,
    val item: YAMLSequenceItem,
    val isImport: Boolean,
    /** The play's own `tags:`, which every task of the play inherits. */
    val tags: List<String>,
    /** Tasks of `pre_tasks`/`tasks`/`post_tasks` that are no role entry and have no `tags:` of their own. */
    val untaggedTasks: Int,
    /** The play has `become:` on (or templated). */
    val becomes: Boolean = false,
) {
    /** The play's role entries, in the order they run. */
    var roles: List<PlaybookRole> = emptyList()
        private set

    val target: PlaybookTarget get() = PlaybookTarget.play(index, name)

    internal fun withRoles(entries: (PlaybookPlay) -> List<PlaybookRole>): PlaybookPlay = apply { roles = entries(this) }
}

/** One role a play applies: an item of `roles:`, or an `import_role`/`include_role` task of a task section. */
class PlaybookRole(
    val index: Int,
    val name: String,
    val item: YAMLSequenceItem,
    val section: String,
    val play: PlaybookPlay,
    /** The tags that select every task of the role with `--tags` (for `include_role`: those its `apply:` passes on too). */
    val tags: List<String>,
    /** An `include_role` with an `apply:` that lacks the task's tags: no tag can be added for it. */
    val blockedInclude: Boolean = false,
    /** The entry or task has `become:` on (or templated). */
    val becomes: Boolean = false,
) {
    val fromTask: Boolean get() = section != ROLES

    val target: PlaybookTarget get() = PlaybookTarget.role(play.index, play.name, index, name)

    /** The tag the role gets when it has none: its name, the last segment of a path. */
    val proposedTag: String get() = name.substringAfterLast('/')

    companion object {
        const val ROLES = "roles"
    }
}

/** A tag to add to the playbook so that a play or role can be selected: on the role entry, or (a play without roles) on the play. */
data class TagAddition(val target: PlaybookTarget, val tag: String, val text: String)

/**
 * How `--tags` selects a play or a role of a playbook (Ansible cannot select a play itself): [tags] is what selects it
 * now; [additions] are tags the playbook would need for the rest of it; [notSelected] names what of the target the
 * tags leave out, and [alsoSelected] what else in the playbook the same tags select.
 */
class TagSelection(
    val tags: List<String>,
    val additions: List<TagAddition>,
    val notSelected: List<String>,
    val alsoSelected: List<String>,
) {
    /** The tags once [additions] are in the playbook. */
    val tagsWithAdditions: List<String> get() = (tags + additions.map { it.tag }).distinct()
}

/**
 * The plays and roles of a playbook (its first document) with their tags, and the [TagSelection] of each: the run
 * dialog prefills `--tags` from it and runs the real playbook. Read action.
 */
object PlaybookParts {
    private val SECTIONS = listOf("pre_tasks", PlaybookRole.ROLES, "tasks", "post_tasks")
    private val TASK_SECTIONS = listOf("pre_tasks", "tasks", "post_tasks")
    private val IMPORT_PLAYBOOK = setOf("import_playbook", "ansible.builtin.import_playbook", "ansible.legacy.import_playbook")
    private val IMPORT_ROLE = setOf("import_role", "ansible.builtin.import_role", "ansible.legacy.import_role")
    private val INCLUDE_ROLE = setOf("include_role", "ansible.builtin.include_role", "ansible.legacy.include_role")
    private val SLUG = Regex("[^a-z0-9]+")
    private val TRUE = setOf("true", "yes", "on", "y", "t", "1")

    /** The plays of [file], cached per PSI modification. */
    fun plays(file: YAMLFile): List<PlaybookPlay> =
        CachedValuesManager.getCachedValue(file) { CachedValueProvider.Result.create(compute(file), file) }

    /** The play and role [target] names in [file]; null when the playbook no longer has them. */
    fun find(file: YAMLFile, target: PlaybookTarget): Pair<PlaybookPlay, PlaybookRole?>? = find(plays(file), target)

    fun find(plays: List<PlaybookPlay>, target: PlaybookTarget): Pair<PlaybookPlay, PlaybookRole?>? {
        if (target.kind == TargetKind.PLAYBOOK) return null
        val play = pick(plays, target.playName, target.playIndex, { it.name }, { it.index }) ?: return null
        if (target.kind == TargetKind.PLAY) return play to null
        val role = pick(play.roles, target.roleName, target.roleIndex, { it.name }, { it.index }) ?: return null
        return play to role
    }

    /** How `--tags` selects [target] in [plays]; null for the whole playbook or a target that is gone. */
    fun selection(plays: List<PlaybookPlay>, target: PlaybookTarget): TagSelection? {
        val (play, role) = find(plays, target) ?: return null
        val own = HashSet<Any>()
        val tags: List<String>
        val additions = ArrayList<TagAddition>()
        val notSelected = ArrayList<String>()
        if (role != null) {
            own += role
            tags = role.tags
            when {
                tags.isNotEmpty() -> Unit
                role.blockedInclude -> notSelected += message("run.tags.blocked.include", role.name)
                else -> additions += roleAddition(role)
            }
        } else {
            own += play
            own.addAll(play.roles)
            when {
                play.tags.isNotEmpty() -> tags = play.tags
                play.roles.isEmpty() -> {
                    tags = emptyList()
                    if (!play.isImport) additions += TagAddition(play.target, playTag(plays, play), message("run.tags.add.play", play.label, playTag(plays, play)))
                }
                else -> {
                    tags = play.roles.flatMap { it.tags }.distinct()
                    for (entry in play.roles.filter { it.tags.isEmpty() }) {
                        if (entry.blockedInclude) notSelected += message("run.tags.blocked.include", entry.name) else additions += roleAddition(entry)
                    }
                    if (play.untaggedTasks > 0) notSelected += message("run.tags.untagged.tasks", play.untaggedTasks)
                }
            }
        }
        val selecting = (tags + additions.map { it.tag }).toSet()
        val also = ArrayList<String>()
        for (other in plays) {
            if (other !in own && other.tags.any { it in selecting }) {
                also += message("run.tags.also.play", other.label, other.tags.filter { it in selecting }.joinToString())
                continue
            }
            for (entry in other.roles) {
                if (entry in own || other in own) continue
                val shared = entry.tags.filter { it in selecting }
                if (shared.isNotEmpty()) also += message("run.tags.also.role", entry.name, other.label, shared.joinToString())
            }
        }
        return TagSelection(tags, additions, notSelected, also)
    }

    /** The play or role entry whose first line starts at [element] (its `-`, or the first leaf of a flow item). */
    fun entryAt(element: PsiElement): Any? {
        val item = itemStartingAt(element) ?: return null
        val file = item.containingFile as? YAMLFile ?: return null
        val plays = plays(file)
        plays.firstOrNull { it.item == item }?.let { return it }
        for (play in plays) play.roles.firstOrNull { it.item == item }?.let { return it }
        return null
    }

    /** Whether running [target] becomes another user anywhere the playbook shows it (`become:` of a play, a role entry, a role task). */
    fun usesBecome(plays: List<PlaybookPlay>, target: PlaybookTarget): Boolean {
        if (target.kind == TargetKind.PLAYBOOK) return plays.any { play -> play.becomes || play.roles.any { it.becomes } }
        val (play, role) = find(plays, target) ?: return false
        return play.becomes || if (role != null) role.becomes else play.roles.any { it.becomes }
    }

    /** Whether a `become:` value is on: Ansible's true words, or a template that may be. */
    private fun becomes(mapping: YAMLMapping?): Boolean {
        val text = (mapping?.getKeyValueByKey("become")?.value as? YAMLScalar)?.textValue?.trim()?.lowercase() ?: return false
        return text in TRUE || "{{" in text
    }

    /** The values of a `tags:` value: a list, or a comma-separated string. */
    fun tagsOf(value: YAMLValue?): List<String> = when (value) {
        is YAMLSequence -> value.items.mapNotNull { (it.value as? YAMLScalar)?.textValue?.trim() }
        is YAMLScalar -> value.textValue.split(',')
        else -> emptyList()
    }.map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    private fun roleAddition(role: PlaybookRole) = TagAddition(role.target, role.proposedTag, message("run.tags.add.role", role.name, role.proposedTag))

    /** A play tag from the play's name that no other part of the playbook uses. */
    private fun playTag(plays: List<PlaybookPlay>, play: PlaybookPlay): String {
        val base = play.name.lowercase().replace(SLUG, "-").trim('-').ifEmpty { "play-${play.index + 1}" }
        val used = plays.flatMap { p -> p.tags + p.roles.flatMap { it.tags } }.toSet()
        return if (base in used) "$base-play" else base
    }

    private fun itemStartingAt(element: PsiElement): YAMLSequenceItem? {
        var current = element
        repeat(5) {
            val parent = current.parent ?: return null
            if (parent.firstChild != current) return null
            if (parent is YAMLSequenceItem) return parent
            current = parent
        }
        return null
    }

    private fun <T> pick(entries: List<T>, name: String, index: Int, nameOf: (T) -> String, indexOf: (T) -> Int): T? {
        if (name.isEmpty()) return entries.firstOrNull { indexOf(it) == index && nameOf(it).isEmpty() }
        val named = entries.filter { nameOf(it) == name }
        return named.singleOrNull() ?: named.firstOrNull { indexOf(it) == index } ?: named.firstOrNull()
    }

    private fun compute(file: YAMLFile): List<PlaybookPlay> {
        val top = file.documents.firstOrNull()?.topLevelValue as? YAMLSequence ?: return emptyList()
        return top.items.mapIndexed { index, item ->
            val mapping = item.value as? YAMLMapping
            val import = mapping?.keyValues?.firstOrNull { it.keyText in IMPORT_PLAYBOOK }
            val name = scalar(mapping?.getKeyValueByKey("name")?.value) ?: import?.let { scalar(it.value) } ?: ""
            val hosts = mapping?.getKeyValueByKey("hosts")?.value?.let { value ->
                scalar(value) ?: (value as? YAMLSequence)?.items?.mapNotNull { scalar(it.value) }?.joinToString(",")
            }
            val label = when {
                name.isNotEmpty() -> name
                hosts != null -> "hosts: $hosts"
                else -> "#${index + 1}"
            }
            val untagged = if (mapping == null || import != null) 0 else TASK_SECTIONS.sumOf { section ->
                (mapping.getKeyValueByKey(section)?.value as? YAMLSequence)?.items.orEmpty().count { task ->
                    val taskMapping = task.value as? YAMLMapping
                    taskMapping != null && roleTask(taskMapping) == null && tagsOf(taskMapping.getKeyValueByKey("tags")?.value).isEmpty()
                }
            }
            PlaybookPlay(index, name, label, item, import != null, tagsOf(mapping?.getKeyValueByKey("tags")?.value), untagged, becomes(mapping)).withRoles { play ->
                if (mapping == null || import != null) emptyList() else roles(mapping, play)
            }
        }
    }

    /** The role entries of a play in the order they run: `pre_tasks`, `roles`, `tasks`, `post_tasks`. */
    private fun roles(mapping: YAMLMapping, play: PlaybookPlay): List<PlaybookRole> {
        val result = ArrayList<PlaybookRole>()
        for (section in SECTIONS) {
            val items = (mapping.getKeyValueByKey(section)?.value as? YAMLSequence)?.items ?: continue
            for (item in items) {
                val value = item.value
                if (section == PlaybookRole.ROLES) {
                    val name = roleName(value) ?: continue
                    val tags = (value as? YAMLMapping)?.let { tagsOf(it.getKeyValueByKey("tags")?.value) }.orEmpty()
                    result += PlaybookRole(result.size, name, item, section, play, tags, becomes = becomes(value as? YAMLMapping))
                } else {
                    val task = value as? YAMLMapping ?: continue
                    val module = roleTask(task) ?: continue
                    val args = module.value as? YAMLMapping
                    val name = scalar(args?.getKeyValueByKey("name")?.value) ?: continue
                    val tags = tagsOf(task.getKeyValueByKey("tags")?.value)
                    if (module.keyText in INCLUDE_ROLE) {
                        // include_role's tags select the include only; its tasks run when apply passes the tags on.
                        val apply = args?.getKeyValueByKey("apply")
                        val applied = tagsOf((apply?.value as? YAMLMapping)?.getKeyValueByKey("tags")?.value)
                        val becomes = becomes(task) || becomes(apply?.value as? YAMLMapping)
                        result += PlaybookRole(result.size, name, item, section, play, tags.filter { it in applied }, apply != null && applied.isEmpty(), becomes)
                    } else {
                        result += PlaybookRole(result.size, name, item, section, play, tags, becomes = becomes(task))
                    }
                }
            }
        }
        return result
    }

    private fun roleTask(task: YAMLMapping) = task.keyValues.firstOrNull { it.keyText in IMPORT_ROLE || it.keyText in INCLUDE_ROLE }

    internal fun isIncludeRole(key: String): Boolean = key in INCLUDE_ROLE

    private fun roleName(value: YAMLValue?): String? = when (value) {
        is YAMLScalar -> value.textValue.trim().takeIf { it.isNotEmpty() }
        is YAMLMapping -> scalar(value.getKeyValueByKey("role")?.value) ?: scalar(value.getKeyValueByKey("name")?.value)
        else -> null
    }

    private fun scalar(value: YAMLValue?): String? = (value as? YAMLScalar)?.textValue?.trim()?.takeIf { it.isNotEmpty() }

    private fun message(key: String, vararg params: Any): String = AnsibilityRunBundle.message(key, *params)
}
