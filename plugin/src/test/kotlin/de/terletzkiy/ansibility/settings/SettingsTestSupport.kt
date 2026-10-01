package de.terletzkiy.ansibility.settings

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.SkipDefaultsSerializationFilter
import com.intellij.util.xmlb.XmlSerializer
import com.intellij.openapi.util.JDOMUtil
import org.jdom.Element

/** Helpers shared by the settings tests. */
object SettingsTestSupport {
    /** Resets every settings component to its defaults (light projects and the application outlive a test). */
    fun resetAll(project: Project) {
        AnsibilityAppSettings.getInstance().loadState(AnsibilityAppSettings.StateBean())
        project.service<AnsibilitySharedProjectSettings>().loadState(SharedProjectSettingsBean())
        AnsibilityProjectSettings.getInstance(project).loadState(ProjectSettingsBean())
        AnsibilityWorkspaceState.getInstance(project).loadState(AnsibilityWorkspaceState.StateBean())
    }

    /**
     * Serializes [bean] the way the component store does (values equal to [defaults] skipped) and reads it back.
     * Returns the XML as text and the bean read from it.
     */
    inline fun <reified T : Any> xmlRoundTrip(bean: T, defaults: T): Pair<String, T> {
        val element = Element("component")
        XmlSerializer.serializeInto(bean, element, SkipDefaultsSerializationFilter(defaults))
        return JDOMUtil.write(element) to XmlSerializer.deserialize(element, T::class.java)
    }
}
