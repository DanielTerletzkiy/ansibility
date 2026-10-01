package de.terletzkiy.ansibility.model.inventory

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.RootKind
import de.terletzkiy.ansibility.context.AnsibleCfg
import de.terletzkiy.ansibility.context.AnsibleLayout
import de.terletzkiy.ansibility.semantics.inventory.VarsFileLayout
import de.terletzkiy.ansibility.semantics.precedence.HashBehaviour
import de.terletzkiy.ansibility.semantics.precedence.PrecedenceEntry
import java.io.IOException

/**
 * The `ansible.cfg` `[defaults]` settings that change how inventory variables are found and combined:
 * `precedence` (`VARIABLE_PRECEDENCE`), `hash_behaviour`, `playbook_vars_root` and `yaml_valid_extensions`.
 *
 * Environment overrides (`ANSIBLE_PRECEDENCE`, …) are not visible to the IDE and are not applied. A value that
 * ansible-core would reject at startup (an unknown `hash_behaviour`) falls back to the default here.
 *
 * [playbookVarsRoot] only matters when a task is evaluated (it picks among the task's search paths); an inventory
 * view has no task, so ansible-core always uses the playbook directory there (`VariableManager.get_vars`).
 */
data class VarsConfig(
    val precedence: List<PrecedenceEntry> = PrecedenceEntry.DEFAULT,
    /** `precedence` entries ansible-core ignores with "Ignoring unknown variable precedence entry". */
    val ignoredPrecedenceEntries: List<String> = emptyList(),
    val hashBehaviour: HashBehaviour = HashBehaviour.REPLACE,
    /** `top` (default), `bottom`, `playbook_dir` or `all`, lower-cased as written. */
    val playbookVarsRoot: String = DEFAULT_PLAYBOOK_VARS_ROOT,
    /** Extensions of vars files besides "none", in lookup order (`YAML_FILENAME_EXTENSIONS`). */
    val extensions: List<String> = VarsFileLayout.DEFAULT_EXTENSIONS,
) {
    companion object {
        const val DEFAULT_PLAYBOOK_VARS_ROOT: String = "top"
        private const val DEFAULTS = "defaults"
        private val LOG = logger<VarsConfig>()

        val DEFAULT: VarsConfig = VarsConfig()

        /** The settings of a parsed [cfg]; the defaults when [cfg] is null. */
        fun from(cfg: AnsibleCfg?): VarsConfig {
            if (cfg == null) return DEFAULT
            val precedence = cfg.value(DEFAULTS, "precedence")?.let(PrecedenceEntry::parse)
            return VarsConfig(
                precedence = precedence?.entries ?: PrecedenceEntry.DEFAULT,
                ignoredPrecedenceEntries = precedence?.ignored.orEmpty(),
                hashBehaviour = cfg.value(DEFAULTS, "hash_behaviour")?.let(HashBehaviour::parse) ?: HashBehaviour.REPLACE,
                playbookVarsRoot = cfg.value(DEFAULTS, "playbook_vars_root")?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
                    ?: DEFAULT_PLAYBOOK_VARS_ROOT,
                extensions = cfg.value(DEFAULTS, "yaml_valid_extensions")?.let(::parseList) ?: VarsFileLayout.DEFAULT_EXTENSIONS,
            )
        }

        /**
         * The settings that apply to [root]: its own `ansible.cfg`, or for a NESTED_PLAYBOOK root its parent's
         * (ansible-core reads the config of the directory the run starts in, the PROJECT root).
         */
        fun of(root: AnsibleRoot): VarsConfig = load(cfgFile(root))

        /**
         * The settings of the `ansible.cfg` [file] (read from disk, as ansible-core does); the defaults for null. Inside
         * a [ModelCache] computation the file's saved content becomes an input ([ModelInputs.savedFile]).
         */
        fun load(file: VirtualFile?): VarsConfig = from(file?.let { ModelInputs.savedFile(it); read(it) })

        /** The `ansible.cfg` that applies to [root], or null (ROLE_LIBRARY roots have none). */
        fun cfgFile(root: AnsibleRoot): VirtualFile? {
            val dir = if (root.kind == RootKind.NESTED_PLAYBOOK) root.parentDir ?: root.dir else root.dir
            return dir.findChild(AnsibleLayout.ANSIBLE_CFG)?.takeIf { it.isValid && !it.isDirectory }
        }

        /** ansible-core's `list` config type: comma separated, trimmed, surrounding quotes removed. */
        private fun parseList(text: String): List<String> =
            text.split(',').map { it.trim().removeSurrounding("\"").removeSurrounding("'") }.filter { it.isNotEmpty() }

        private fun read(file: VirtualFile): AnsibleCfg? = try {
            AnsibleCfg.parse(VfsUtilCore.loadText(file))
        } catch (e: IOException) {
            LOG.debug("Cannot read ${file.path}", e)
            null
        }
    }
}
