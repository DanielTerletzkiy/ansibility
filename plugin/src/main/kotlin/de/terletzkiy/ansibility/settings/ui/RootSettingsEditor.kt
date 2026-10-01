package de.terletzkiy.ansibility.settings.ui

import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.JBIntSpinner
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.UIUtil
import de.terletzkiy.ansibility.inspections.undefined.AnsibilityUndefinedBundle
import de.terletzkiy.ansibility.settings.AnsibilitySettingsBundle.message
import de.terletzkiy.ansibility.settings.AnsibleCfgOverrides
import de.terletzkiy.ansibility.settings.DocsMismatchSeverity
import de.terletzkiy.ansibility.settings.HashBehaviour
import de.terletzkiy.ansibility.settings.PlaybookVarsRoot
import de.terletzkiy.ansibility.settings.RootSettings
import javax.swing.JComponent
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * The editor for the toggles and `ansible.cfg` overrides of the root selected in the roots table: [load] shows a
 * root's settings, every user change calls [onChange], and [applyTo] writes the editor's fields into a root's
 * settings (the target, preset and collections columns of the table stay as they are).
 */
internal class RootSettingsEditor(private val onChange: () -> Unit) {
    val moduleOptionCoercions = JBCheckBox(message("root.toggle.module.coercions"))
    val requireReachablePlay = JBCheckBox(message("root.toggle.reachable"))
    val redForCertainFailures = JBCheckBox(message("root.toggle.certain.failures"))
    /** HA8d (F8.12): [RootSettings.unguardedOptionalAlwaysError]. */
    val unguardedOptionalAlwaysError = JBCheckBox(AnsibilityUndefinedBundle.message("settings.unguarded.always.error"))
    val docsMismatch = ComboBox(DocsMismatchSeverity.entries.toTypedArray()).apply {
        renderer = SettingsLabels.listRenderer(SettingsLabels::docsMismatch)
    }
    val chainDepth = JBIntSpinner(RootSettings.DEFAULT_CHAIN_DEPTH, RootSettings.CHAIN_DEPTH_RANGE.first, RootSettings.CHAIN_DEPTH_RANGE.last)
    val hashBehaviour = ComboBox(arrayOf(Choice<HashBehaviour>(null, message("cfg.from.ansible.cfg"))) + HashBehaviour.entries.map { Choice(it, it.cfgValue) })
    val precedence = JBTextField()
    val playbookVarsRoot = ComboBox(arrayOf(Choice<PlaybookVarsRoot>(null, message("cfg.from.ansible.cfg"))) + PlaybookVarsRoot.entries.map { Choice(it, it.cfgValue) })
    val jinja2Native = ComboBox(booleanChoices())
    val privateRoleVars = ComboBox(booleanChoices())

    private var loading = false

    val component: JComponent = panel {
        row { cell(moduleOptionCoercions).comment(message("root.toggle.module.coercions.comment")) }
        row { cell(requireReachablePlay).comment(message("root.toggle.reachable.comment")) }
        row { cell(redForCertainFailures).comment(message("root.toggle.certain.failures.comment")) }
        row { cell(unguardedOptionalAlwaysError).comment(AnsibilityUndefinedBundle.message("settings.unguarded.always.error.comment")) }
        row(message("root.docs.mismatch")) { cell(docsMismatch) }
        row(message("root.chain.depth")) { cell(chainDepth).comment(message("root.chain.depth.comment")) }
        group(message("root.cfg.group"), indent = false) {
            row { comment(message("root.cfg.comment")) }
            row("hash_behaviour:") { cell(hashBehaviour) }
            row("precedence:") {
                cell(precedence).align(AlignX.FILL).comment(message("root.cfg.precedence.comment", AnsibleCfgOverrides.PRECEDENCE_ENTRIES.joinToString(", ")))
            }
            row("playbook_vars_root:") { cell(playbookVarsRoot) }
            row("jinja2_native:") { cell(jinja2Native) }
            row("private_role_vars:") { cell(privateRoleVars) }
        }
    }

    init {
        val changed = { if (!loading) onChange() }
        listOf(moduleOptionCoercions, requireReachablePlay, redForCertainFailures, unguardedOptionalAlwaysError).forEach { it.addActionListener { changed() } }
        listOf(docsMismatch, hashBehaviour, playbookVarsRoot, jinja2Native, privateRoleVars).forEach { it.addActionListener { changed() } }
        chainDepth.addChangeListener { changed() }
        precedence.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = changed()
            override fun removeUpdate(e: DocumentEvent) = changed()
            override fun changedUpdate(e: DocumentEvent) = changed()
        })
        setEnabled(false)
    }

    /** Shows [settings], or disables the editor when [settings] is null (no root selected). */
    fun load(settings: RootSettings?) {
        loading = true
        try {
            val loaded = settings ?: RootSettings.DEFAULT
            moduleOptionCoercions.isSelected = loaded.moduleOptionCoercions
            requireReachablePlay.isSelected = loaded.requireReachablePlayForRed
            redForCertainFailures.isSelected = loaded.redForClaudeCertainFailures
            unguardedOptionalAlwaysError.isSelected = loaded.unguardedOptionalAlwaysError
            docsMismatch.selectedItem = loaded.unknownModuleOptionWhenDocsDiffer
            chainDepth.number = loaded.effectiveChainDepth
            select(hashBehaviour, loaded.cfgOverrides.hashBehaviour)
            precedence.text = loaded.cfgOverrides.precedence?.joinToString(", ").orEmpty()
            select(playbookVarsRoot, loaded.cfgOverrides.playbookVarsRoot)
            select(jinja2Native, loaded.cfgOverrides.jinja2Native)
            select(privateRoleVars, loaded.cfgOverrides.privateRoleVars)
            setEnabled(settings != null)
        } finally {
            loading = false
        }
    }

    /** [base] with the editor's values. */
    fun applyTo(base: RootSettings): RootSettings = base.copy(
        moduleOptionCoercions = moduleOptionCoercions.isSelected,
        requireReachablePlayForRed = requireReachablePlay.isSelected,
        redForClaudeCertainFailures = redForCertainFailures.isSelected,
        unguardedOptionalAlwaysError = unguardedOptionalAlwaysError.isSelected,
        unknownModuleOptionWhenDocsDiffer = docsMismatch.item ?: DocsMismatchSeverity.WARNING,
        chainDepth = chainDepth.number,
        cfgOverrides = AnsibleCfgOverrides(
            hashBehaviour = hashBehaviour.item?.value,
            precedence = AnsibleCfgOverrides.parsePrecedence(precedence.text),
            playbookVarsRoot = playbookVarsRoot.item?.value,
            jinja2Native = jinja2Native.item?.value,
            privateRoleVars = privateRoleVars.item?.value,
        ),
    )

    private fun setEnabled(enabled: Boolean) {
        UIUtil.setEnabled(component, enabled, true)
    }

    private fun <T> select(combo: ComboBox<Choice<T>>, value: T?) {
        val model = combo.model
        combo.selectedItem = (0 until model.size).map(model::getElementAt).firstOrNull { it.value == value } ?: model.getElementAt(0)
    }

    private companion object {
        fun booleanChoices(): Array<Choice<Boolean>> = arrayOf(
            Choice(null, message("cfg.from.ansible.cfg")),
            Choice(true, "true"),
            Choice(false, "false"),
        )
    }
}
