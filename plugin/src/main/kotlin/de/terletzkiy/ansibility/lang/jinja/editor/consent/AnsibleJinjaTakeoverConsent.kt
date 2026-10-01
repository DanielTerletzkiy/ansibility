package de.terletzkiy.ansibility.lang.jinja.editor.consent

import com.intellij.ide.util.PropertiesComponent
import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.readAction
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.PlainTextLanguage
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.util.text.HtmlBuilder
import com.intellij.openapi.util.text.HtmlChunk
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.context.AnsibleOnboarding
import de.terletzkiy.ansibility.lang.jinja.AnsibilityJinjaBundle
import de.terletzkiy.ansibility.lang.jinja.AnsibleJinjaFileType
import de.terletzkiy.ansibility.lang.jinja.filetype.AnsibleJinjaFileTypeRefresh
import de.terletzkiy.ansibility.lang.jinja.filetype.AnsibleJinjaFileTypeRules
import de.terletzkiy.ansibility.lang.jinja.template.AnsibleJinjaOuterLanguages
import de.terletzkiy.ansibility.settings.AnsibilityAppSettings
import de.terletzkiy.ansibility.settings.JinjaSettings
import org.jetbrains.yaml.YAMLFileType
import org.jetbrains.yaml.YAMLLanguage

/**
 * The consent flow of the `.j2` takeover (plan D8, F2.9). The first time a project opens whose `.j2` files inside
 * Ansible roots are taken over, one notification says so, with examples from the project ("`haproxy.cfg.j2` as Ansible
 * Jinja2 (Plain text)"), and offers:
 * - **Keep YAML for .j2** (named after the user's own `*.j2` mapping when there is one): turns the claim off and
 *   re-types the files, so `.j2` files open as before;
 * - **Remove my `*.j2 → YAML` mapping**, only when such a mapping exists: removes it from the IDE's file types. It is
 *   never changed without this click.
 *
 * Shown once per IDE installation (an application-level flag, never project state). The flag is the one of the
 * earlier D8 notice, which this flow replaced, so users who saw that notice are not asked again. Variable hover, Ctrl+B and completion work through the text-level locator whatever the user picks.
 */
object AnsibleJinjaTakeoverConsent {
    /** Application-level [PropertiesComponent] flag: the notification was shown (shared with the earlier D8 notice). */
    const val SHOWN_KEY: String = "ansibility.jinja.j2TakeoverAnnounced"

    /** How many example files the notification names. */
    private const val MAX_EXAMPLES = 3

    private const val REFRESH_REASON = "Ansibility: Keep YAML for .j2"

    /** A template of the project and the outer language it opens with. */
    data class Example(val fileName: String, val outerLanguage: String)

    /**
     * The startup flow: when `.j2` files are claimed, the notification was not shown yet and [project] has Ansible
     * roots, waits for smart mode (the examples come from the file name index) and shows the notification if some
     * `.j2` file of the project is taken over. A project without one leaves the one-time flag for the next project.
     * Each step takes its own read action and nothing runs on the EDT; returns the notification shown, or null.
     */
    suspend fun announce(project: Project, properties: PropertiesComponent = PropertiesComponent.getInstance()): Notification? {
        if (!AnsibleJinjaFileTypeRules.claimsJ2(currentSettings()) || properties.getBoolean(SHOWN_KEY)) return null
        val hasRoots = readAction { !project.isDisposed && AnsibleWorkspace.getInstance(project).roots().isNotEmpty() }
        if (!hasRoots) return null
        val examples = smartReadAction(project) { if (project.isDisposed) emptyList() else examples(project) }
        if (project.isDisposed || examples.isEmpty()) return null
        // the settings may have changed while indexing: notifyIfNeeded reads them again
        return notifyIfNeeded(project, hasAnsibleRoots = true, examples = examples, properties = properties)
    }

    /**
     * Shows the notification for [project] unless it was shown before, `.j2` files are not claimed, or [hasAnsibleRoots]
     * is false. Returns the notification shown, or null.
     */
    fun notifyIfNeeded(
        project: Project,
        hasAnsibleRoots: Boolean,
        examples: List<Example>,
        settings: JinjaSettings = currentSettings(),
        properties: PropertiesComponent = PropertiesComponent.getInstance(),
    ): Notification? {
        if (!hasAnsibleRoots || !AnsibleJinjaFileTypeRules.claimsJ2(settings) || properties.getBoolean(SHOWN_KEY)) return null
        properties.setValue(SHOWN_KEY, true)
        val notification = createNotification(project, examples)
        notification.notify(project)
        return notification
    }

    /** The notification with its actions; [mappedType] is the type of the user's `*.j2` mapping, if any. */
    fun createNotification(
        project: Project,
        examples: List<Example>,
        mappedType: FileType? = J2ExtensionMapping.mappedType(),
    ): Notification {
        val notification = NotificationGroupManager.getInstance()
            .getNotificationGroup(AnsibleOnboarding.NOTIFICATION_GROUP)
            .createNotification(
                AnsibilityJinjaBundle.message("consent.title"),
                content(examples, mappedType),
                NotificationType.INFORMATION,
            )
        val keptType = mappedType?.displayName ?: YAMLFileType.YML.displayName
        notification.addAction(
            NotificationAction.createSimpleExpiring(AnsibilityJinjaBundle.message("consent.action.keep", keptType)) {
                keepUserFileTypes()
            },
        )
        if (mappedType != null) {
            val remove = AnsibilityJinjaBundle.message("consent.action.remove.mapping", mappedType.displayName)
            notification.addAction(NotificationAction.createSimpleExpiring(remove) { removeMapping(project, mappedType) })
        }
        return notification
    }

    /** "Keep YAML for .j2": the claim is switched off and every file is re-typed. */
    fun keepUserFileTypes() {
        AnsibilityAppSettings.getInstance().update { it.copy(jinja = it.jinja.copy(keepYamlForJ2 = true)) }
        // the settings listener schedules the same change; the request is coalesced
        AnsibleJinjaFileTypeRefresh.scheduleFileTypesChange(REFRESH_REASON)
    }

    /** "Remove my `*.j2` mapping", with a confirmation (or a warning when the platform kept it). */
    fun removeMapping(project: Project, type: FileType) {
        val removed = J2ExtensionMapping.remove(type)
        val (key, notificationType) = when {
            removed -> "consent.mapping.removed" to NotificationType.INFORMATION
            else -> "consent.mapping.not.removed" to NotificationType.WARNING
        }
        val message = AnsibilityJinjaBundle.message(key, StringUtil.escapeXmlEntities(type.displayName))
        NotificationGroupManager.getInstance()
            .getNotificationGroup(AnsibleOnboarding.NOTIFICATION_GROUP)
            .createNotification(message, notificationType)
            .notify(project)
    }

    /**
     * Up to three `.j2` templates of [project] that open as Ansible Jinja2, each with a different outer language: one
     * with YAML (what a `*.j2 → YAML` mapping gave them), one with plain text (what changes most), then another one.
     * Needs smart mode (the file name index) and a read action.
     */
    fun examples(project: Project): List<Example> {
        val yaml = YAMLLanguage.INSTANCE.displayName
        val plainText = PlainTextLanguage.INSTANCE.displayName
        val byLanguage = LinkedHashMap<String, Example>()
        val files = FilenameIndex.getAllFilesByExt(project, J2ExtensionMapping.EXTENSION, GlobalSearchScope.projectScope(project))
        for (file in files.sortedBy { it.path }) {
            ProgressManager.checkCanceled()
            if (file.fileType != AnsibleJinjaFileType) continue
            val language = AnsibleJinjaOuterLanguages.templateDataLanguage(project, file).displayName
            byLanguage.putIfAbsent(language, Example(file.name, language))
            if (yaml in byLanguage && plainText in byLanguage && byLanguage.size >= MAX_EXAMPLES) break
        }
        return byLanguage.values.sortedBy {
            when (it.outerLanguage) {
                yaml -> 0
                plainText -> 1
                else -> 2
            }
        }.take(MAX_EXAMPLES)
    }

    private fun currentSettings(): JinjaSettings = AnsibilityAppSettings.getInstance().settings.jinja

    private fun content(examples: List<Example>, mappedType: FileType?): String {
        // the bundle texts are HTML (`<code>`); file and language names are escaped
        val builder = HtmlBuilder().appendRaw(AnsibilityJinjaBundle.message("consent.content"))
        if (examples.isNotEmpty()) {
            builder.br().appendRaw(AnsibilityJinjaBundle.message("consent.examples"))
            for (example in examples) {
                builder.br().append(HtmlChunk.text("· ")).append(HtmlChunk.text(example.fileName).code())
                    .append(HtmlChunk.text(" → " + AnsibilityJinjaBundle.message("consent.example.type", example.outerLanguage)))
            }
        }
        if (mappedType != null) {
            val typeName = StringUtil.escapeXmlEntities(mappedType.displayName)
            builder.br().appendRaw(AnsibilityJinjaBundle.message("consent.mapping", typeName))
        }
        return builder.toString()
    }
}

/**
 * Runs [AnsibleJinjaTakeoverConsent.announce] after the project opened (`postStartupActivity`). Skipped in unit tests,
 * whose projects are opened and closed by the hundred.
 */
class AnsibleJinjaTakeoverConsentActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        if (ApplicationManager.getApplication().isUnitTestMode) return
        AnsibleJinjaTakeoverConsent.announce(project)
    }
}
