package de.terletzkiy.ansibility.settings

import com.intellij.util.xmlb.annotations.Attribute
import com.intellij.util.xmlb.annotations.Tag
import com.intellij.util.xmlb.annotations.XCollection
import de.terletzkiy.ansibility.semantics.diagnostics.Preset

/**
 * The XML form of [ProjectSettings], shared by the workspace component and the team-shared component. Values equal
 * to the defaults are not written; the ignored paths are stored only when [customIgnoredPaths] is set, so the
 * built-in defaults can improve with plugin updates (and an emptied list stays empty).
 *
 * Migration (plan amendment R20, D151): the old `moleculeSupport` option has no field any more. The serializer skips
 * the unknown option, so a stored value (true or false) loads as the [MoleculeSettings] defaults (tests on, Molecule
 * hidden from navigation) and is gone from the next save.
 */
open class ProjectSettingsBean {
    @get:XCollection(style = XCollection.Style.v2)
    var roots: MutableList<RootSettingsBean> = ArrayList()

    var detachedRule: Boolean = true

    var customIgnoredPaths: Boolean = false

    @get:XCollection(style = XCollection.Style.v2, elementName = "path")
    var extraIgnoredPaths: MutableList<String> = ArrayList()

    var schemaStoreExclusion: Boolean = true

    /** [MoleculeSettings.runTests]. */
    var moleculeRunTests: Boolean = MoleculeSettings.DEFAULT.runTests

    /** [MoleculeSettings.showInNavigation]. */
    var moleculeShowInNavigation: Boolean = MoleculeSettings.DEFAULT.showInNavigation

    fun toSettings(): ProjectSettings = ProjectSettings(
        roots = roots.filter { it.path.isNotBlank() }.associate { it.path to it.toSettings() },
        paths = PathSettings(
            detachedRule = detachedRule,
            extraIgnoredPaths = if (customIgnoredPaths) {
                extraIgnoredPaths.map { it.trim() }.filter { it.isNotEmpty() }
            } else {
                PathSettings.DEFAULT_IGNORED_PATHS
            },
            schemaStoreExclusion = schemaStoreExclusion,
        ),
        molecule = MoleculeSettings(runTests = moleculeRunTests, showInNavigation = moleculeShowInNavigation),
    ).normalized()

    /** Copies [settings] into this bean. */
    fun fill(settings: ProjectSettings) {
        roots = settings.normalized().roots.entries.sortedBy { it.key }.mapTo(ArrayList()) { (key, root) -> RootSettingsBean.of(key, root) }
        detachedRule = settings.paths.detachedRule
        customIgnoredPaths = settings.paths.extraIgnoredPaths != PathSettings.DEFAULT_IGNORED_PATHS
        extraIgnoredPaths = if (customIgnoredPaths) settings.paths.extraIgnoredPaths.toMutableList() else ArrayList()
        schemaStoreExclusion = settings.paths.schemaStoreExclusion
        moleculeRunTests = settings.molecule.runTests
        moleculeShowInNavigation = settings.molecule.showInNavigation
    }
}

/** The XML form of one root's [RootSettings]. */
@Tag("root")
class RootSettingsBean {
    @get:Attribute("path")
    var path: String = ""

    var targetCore: String? = null
    var collectionsSource: CollectionsSource = CollectionsSource.PINS
    var preset: Preset = Preset.DOCUMENTED_TYPES
    var moduleOptionCoercions: Boolean = false
    var requireReachablePlayForRed: Boolean = false
    var redForClaudeCertainFailures: Boolean = true
    var unguardedOptionalAlwaysError: Boolean = false
    var unknownModuleOptionWhenDocsDiffer: DocsMismatchSeverity = DocsMismatchSeverity.WARNING
    var chainDepth: Int = RootSettings.DEFAULT_CHAIN_DEPTH
    var hashBehaviour: HashBehaviour? = null
    var precedence: String? = null
    var playbookVarsRoot: PlaybookVarsRoot? = null
    var jinja2Native: Boolean? = null
    var privateRoleVars: Boolean? = null

    fun toSettings(): RootSettings = RootSettings(
        targetCore = targetCore.nonBlank(),
        collectionsSource = collectionsSource,
        cfgOverrides = AnsibleCfgOverrides(
            hashBehaviour = hashBehaviour,
            precedence = AnsibleCfgOverrides.parsePrecedence(precedence),
            playbookVarsRoot = playbookVarsRoot,
            jinja2Native = jinja2Native,
            privateRoleVars = privateRoleVars,
        ),
        preset = preset,
        moduleOptionCoercions = moduleOptionCoercions,
        requireReachablePlayForRed = requireReachablePlayForRed,
        redForClaudeCertainFailures = redForClaudeCertainFailures,
        unguardedOptionalAlwaysError = unguardedOptionalAlwaysError,
        unknownModuleOptionWhenDocsDiffer = unknownModuleOptionWhenDocsDiffer,
        chainDepth = chainDepth,
    )

    companion object {
        fun of(key: String, settings: RootSettings): RootSettingsBean = RootSettingsBean().apply {
            path = key
            targetCore = settings.targetCore.nonBlank()
            collectionsSource = settings.collectionsSource
            preset = settings.preset
            moduleOptionCoercions = settings.moduleOptionCoercions
            requireReachablePlayForRed = settings.requireReachablePlayForRed
            redForClaudeCertainFailures = settings.redForClaudeCertainFailures
            unguardedOptionalAlwaysError = settings.unguardedOptionalAlwaysError
            unknownModuleOptionWhenDocsDiffer = settings.unknownModuleOptionWhenDocsDiffer
            chainDepth = settings.chainDepth
            hashBehaviour = settings.cfgOverrides.hashBehaviour
            precedence = settings.cfgOverrides.precedence?.joinToString(", ")
            playbookVarsRoot = settings.cfgOverrides.playbookVarsRoot
            jinja2Native = settings.cfgOverrides.jinja2Native
            privateRoleVars = settings.cfgOverrides.privateRoleVars
        }
    }
}

/** The XML form of the team-shared copy: [shared] says whether sharing is on; the fields are the shared settings. */
class SharedProjectSettingsBean : ProjectSettingsBean() {
    var shared: Boolean = false
}
