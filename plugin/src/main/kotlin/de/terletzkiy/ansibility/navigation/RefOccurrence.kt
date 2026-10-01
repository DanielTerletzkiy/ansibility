package de.terletzkiy.ansibility.navigation

import com.intellij.openapi.util.TextRange
import de.terletzkiy.ansibility.api.AnsibleSite

/** What a reference value names (plan F1.8, X50). */
internal enum class RefKind {
    /** `include_tasks`/`import_tasks` (free-form string or `file:`). */
    TASK_INCLUDE,

    /** `tasks_from` of `include_role`/`import_role`. */
    TASKS_FROM,

    /** `handlers_from` of `include_role`/`import_role`. */
    HANDLERS_FROM,

    /** `vars_from` of `include_role`/`import_role`. */
    VARS_FROM,

    /** `defaults_from` of `include_role`/`import_role`. */
    DEFAULTS_FROM,

    /** A role: a play's `roles:` entry, `include_role`/`import_role` `name`, a `meta/main.yml` dependency. */
    ROLE,

    /** The `src` of `ansible.builtin.template`. */
    TEMPLATE_SRC,

    /** The `src` of `ansible.builtin.copy` (never with a truthy or templated `remote_src`). */
    COPY_SRC,

    /** A `notify` item of a task or block. */
    NOTIFY,

    /** A `listen` topic of a handler. */
    LISTEN,

    /** A play's `vars_files` entry. */
    VARS_FILE,

    /** The target of `import_playbook`. */
    IMPORT_PLAYBOOK,

    /** A vars-file value that a rendering task's dynamic `src` turns into a template name. */
    TEMPLATE_NAME,

    /** The name in `{% include %}`, `{% import %}`, `{% from %}` or `{% extends %}` of a template. */
    TEMPLATE_INCLUDE,
}

/** Where a [RefKind.ROLE] reference is written; decides the role search path. */
internal enum class RoleSite {
    /** A play's `roles:` entry. */
    PLAY_ROLE,

    /** `include_role`/`import_role` `name`. */
    INCLUDE_ROLE,

    /** A `meta/main.yml` dependency. */
    DEPENDENCY,
}

/**
 * One reference value in a file: the pure-syntax part of plan F1.8, found by [RefSites] and resolved by
 * [RefResolver]. Nothing here depends on other files, except [slot] (template-name values).
 */
internal data class RefOccurrence(
    val kind: RefKind,
    /** The reference as Ansible loads it (unquoted); for a dynamic `src`, the whole `src` text. */
    val text: String,
    /** The value's text in the file, inside quotes; for a dynamic `src`, only its static prefix. This is the site range. */
    val range: TextRange,
    /** For the `*_FROM` kinds: the role reference of the same include, as written (null when it has none). */
    val role: String? = null,
    /** For [RefKind.ROLE]: where it is written. */
    val roleSite: RoleSite? = null,
    /** For a `src` with a static prefix followed by Jinja: that prefix (`templates/nginx/`); null for a static `src`. */
    val dynamicPrefix: String? = null,
    /** The index of the play (among the file's plays) the value belongs to; null outside plays. */
    val playIndex: Int? = null,
    /** For [RefKind.VARS_FILE] items of a nested list (the first file that exists is loaded): the list's start offset. */
    val alternativeGroup: Int? = null,
    /** For [RefKind.TEMPLATE_INCLUDE]: the tag says `ignore missing`. */
    val optional: Boolean = false,
    /** For [RefKind.TEMPLATE_NAME]: the rendering task the value feeds. */
    val slot: TemplateNameSlot? = null,
) {
    /** The value contains Jinja, so it names nothing statically. */
    val isTemplated: Boolean get() = isTemplated(text)

    /** The value is a dynamic `src`: [text] has Jinja after [dynamicPrefix]. */
    val isDynamic: Boolean get() = dynamicPrefix != null

    /** The [AnsibleSite] the classifier reports for this value (plan A.4). */
    fun toSite(ownRole: String?): AnsibleSite = when (kind) {
        RefKind.TASK_INCLUDE -> AnsibleSite.TaskFileRef(ownRole, text, range)
        RefKind.TASKS_FROM, RefKind.HANDLERS_FROM, RefKind.VARS_FROM, RefKind.DEFAULTS_FROM ->
            AnsibleSite.TaskFileRef(role?.let(::roleNameOf), text, range)
        RefKind.ROLE -> AnsibleSite.RoleRef(text, range)
        RefKind.TEMPLATE_SRC, RefKind.TEMPLATE_NAME, RefKind.TEMPLATE_INCLUDE -> AnsibleSite.TemplateRef(text, false, range)
        RefKind.COPY_SRC -> AnsibleSite.TemplateRef(text, true, range)
        RefKind.NOTIFY, RefKind.LISTEN -> AnsibleSite.HandlerRef(text, range)
        RefKind.VARS_FILE, RefKind.IMPORT_PLAYBOOK -> AnsibleSite.PlaybookFileRef(text, range)
    }

    companion object {
        /** Whether [text] contains a Jinja expression or statement. */
        fun isTemplated(text: CharSequence): Boolean = text.contains("{{") || text.contains("{%")

        /** A role reference's name: a path's last segment (`/ansible/roles/haproxy` → `haproxy`), else the text. */
        fun roleNameOf(written: String): String = written.trim().trimEnd('/').substringAfterLast('/')
    }
}
