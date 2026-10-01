package de.terletzkiy.ansibility.settings.ui

import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.RootKind
import de.terletzkiy.ansibility.semantics.diagnostics.Preset
import de.terletzkiy.ansibility.settings.AnsibilitySettingsBundle.message
import de.terletzkiy.ansibility.settings.CollectionsSource
import de.terletzkiy.ansibility.settings.DocsMismatchSeverity
import de.terletzkiy.ansibility.settings.DocsWebBase
import de.terletzkiy.ansibility.settings.ModuleNavigationTarget
import javax.swing.ListCellRenderer
import javax.swing.table.DefaultTableCellRenderer

/** User-facing names of the settings values. */
internal object SettingsLabels {
    fun preset(preset: Preset): String = when (preset) {
        Preset.DOCUMENTED_TYPES -> message("preset.documented")
        Preset.RUNTIME_FAITHFUL -> message("preset.runtime")
        Preset.STRICT -> message("preset.strict")
    }

    fun collections(source: CollectionsSource): String = when (source) {
        CollectionsSource.PINS -> message("collections.pins")
        CollectionsSource.LOCAL -> message("collections.local")
    }

    fun docsMismatch(severity: DocsMismatchSeverity): String = when (severity) {
        DocsMismatchSeverity.WARNING -> message("docs.mismatch.warning")
        DocsMismatchSeverity.OFF -> message("docs.mismatch.off")
    }

    fun webBase(base: DocsWebBase): String = when (base) {
        DocsWebBase.TARGET_VERSIONED -> message("docs.web.base.target")
        DocsWebBase.LATEST -> message("docs.web.base.latest")
        DocsWebBase.CUSTOM -> message("docs.web.base.custom")
    }

    fun moduleNavigation(target: ModuleNavigationTarget): String = when (target) {
        ModuleNavigationTarget.WEB_DOCS -> message("docs.module.navigation.web")
        ModuleNavigationTarget.QUICK_DOC -> message("docs.module.navigation.quick.doc")
        ModuleNavigationTarget.MODULE_SOURCE -> message("docs.module.navigation.source")
    }

    fun kind(root: AnsibleRoot): String {
        val kind = when (root.kind) {
            RootKind.PROJECT -> message("root.kind.project")
            RootKind.ROLE_LIBRARY -> message("root.kind.library")
            RootKind.NESTED_PLAYBOOK -> message("root.kind.nested")
        }
        return if (root.detached) message("root.kind.detached", kind) else kind
    }

    /** A list renderer showing [label] of each item (the UI DSL renderer; `SimpleListCellRenderer.create` is scheduled for removal). */
    fun <T> listRenderer(label: (T) -> String): ListCellRenderer<T?> = textListCellRenderer { value: T? -> value?.let(label).orEmpty() }

    /** A table renderer showing [label] of each cell value. */
    fun <T> tableRenderer(label: (T) -> String): DefaultTableCellRenderer = object : DefaultTableCellRenderer() {
        override fun setValue(value: Any?) {
            @Suppress("UNCHECKED_CAST")
            text = (value as? T)?.let(label) ?: value?.toString().orEmpty()
        }
    }
}

/** A combo-box item for an optional value; [value] null means "not overridden". */
internal data class Choice<T>(val value: T?, val label: String) {
    override fun toString(): String = label
}
