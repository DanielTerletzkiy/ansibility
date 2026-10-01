package de.terletzkiy.ansibility.model.molecule

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.context.AnsibleLayout
import de.terletzkiy.ansibility.model.task.NameRef
import de.terletzkiy.ansibility.model.task.YamlFiles
import de.terletzkiy.ansibility.semantics.yaml.YMap
import de.terletzkiy.ansibility.semantics.yaml.YScalar
import de.terletzkiy.ansibility.semantics.yaml.YSeq
import de.terletzkiy.ansibility.semantics.yaml.YValue
import de.terletzkiy.ansibility.yaml.PsiYValueAdapter
import org.jetbrains.yaml.psi.YAMLFile

/** One `platforms:` entry of a molecule scenario (an instance, which becomes an inventory host). */
data class MoleculePlatform(
    val name: NameRef?,
    val image: String?,
    /** Inventory groups the instance joins (`groups:`). */
    val groups: List<NameRef>,
    /** Child groups of those groups (`children:`). */
    val children: List<NameRef>,
    val value: YMap,
)

/**
 * The variables of one group or host in `provisioner.inventory.group_vars`/`host_vars`: [key] is the group or
 * host name as written, [vars] the mapping of variables, both with the ranges of their text in `molecule.yml`.
 */
data class MoleculeVarsBlock(val key: YScalar, val vars: YMap) {
    /** The group or host name. */
    val target: String get() = key.text
}

/** A molecule scenario: `roles/<r>/molecule/<scenario>/molecule.yml` and the files around it (plan A.5 MOLECULE_SCENARIO). */
data class MoleculeScenario(
    val configFile: VirtualFile,
    val scenarioDir: VirtualFile,
    /** `scenario.name`, or the scenario directory's name. */
    val name: String,
    /** The role the scenario tests, when it lies inside one. */
    val roleDir: VirtualFile?,
    val platforms: List<MoleculePlatform>,
    /** `provisioner.inventory.group_vars`, per group in file order. */
    val groupVars: List<MoleculeVarsBlock>,
    /** `provisioner.inventory.host_vars`, per host in file order. */
    val hostVars: List<MoleculeVarsBlock>,
    /** `provisioner.inventory.hosts`: an inline YAML inventory (`all: {children: …}`), as written. */
    val hosts: YValue?,
    /** `provisioner.inventory.links`: `hosts`/`group_vars`/`host_vars` → path relative to the scenario, as written. */
    val inventoryLinks: Map<String, NameRef>,
    /** Scenario playbooks by step (`converge`, `verify`, `prepare` …): `provisioner.playbooks`, else the default file names. */
    val playbooks: Map<String, VirtualFile>,
    /**
     * The vars files of the pseudo-inventory: `molecule/vars/`, the scenario's `vars/`, `group_vars/`, `host_vars/`
     * and a scenario-level `vars.yml`, sorted by path.
     */
    val varsFiles: List<VirtualFile>,
) {
    /** Variables of [group] in `provisioner.inventory.group_vars` (the last block of that name, as YAML loads it). */
    fun groupVars(group: String): YMap? = groupVars.lastOrNull { it.target == group }?.vars

    /** Variables of [host] in `provisioner.inventory.host_vars`. */
    fun hostVars(host: String): YMap? = hostVars.lastOrNull { it.target == host }?.vars
}

/**
 * Reads molecule scenarios (plan A.5, research roles.md §6): the inline inventory of `molecule.yml` forms a
 * pseudo-inventory together with the molecule vars files. Models are cached on the `molecule.yml` PSI file until
 * it or the Ansible structure changes. Call inside a read action.
 */
object MoleculeScenarioModel {
    private val SCENARIO = Key.create<CachedValue<MoleculeScenario?>>("ansibility.model.moleculeScenario")

    /** The scenario [config] (a `molecule.yml`) describes; null when the file is not in a directory. */
    fun of(config: YAMLFile): MoleculeScenario? =
        CachedValuesManager.getCachedValue(config, SCENARIO) {
            val virtualFile = config.originalFile.virtualFile
            val scenario = virtualFile?.parent?.let { build(config, virtualFile, it) }
            CachedValueProvider.Result.create(scenario, config, AnsibleWorkspace.getInstance(config.project).structureTracker)
        }

    /** The scenario of [scenarioDir] (a directory holding `molecule.yml`), or null. */
    fun forScenarioDir(project: Project, scenarioDir: VirtualFile): MoleculeScenario? {
        val config = scenarioDir.children.orEmpty().firstOrNull { !it.isDirectory && AnsibleLayout.isMoleculeConfigName(it.name) } ?: return null
        val yaml = YamlFiles.yamlFile(project, config) ?: return null
        return of(yaml)
    }

    /** The scenario a molecule file ([contextFile]: playbook, tasks, vars or config) belongs to, or null. */
    fun scenarioOf(project: Project, contextFile: VirtualFile): MoleculeScenario? {
        val dir = AnsibleWorkspace.getInstance(project).contextOf(contextFile)?.moleculeScenarioDir
            ?: contextFile.parent?.takeIf { it.parent?.name == AnsibleLayout.MOLECULE }
            ?: return null
        return forScenarioDir(project, dir)
    }

    private fun build(file: YAMLFile, configFile: VirtualFile, scenarioDir: VirtualFile): MoleculeScenario {
        val document = PsiYValueAdapter.documentValue(file) as? YMap
        val provisioner = document?.get("provisioner") as? YMap
        val inventory = provisioner?.get("inventory") as? YMap
        val name = ((document?.get("scenario") as? YMap)?.get("name") as? YScalar)?.text ?: scenarioDir.name
        val moleculeDir = scenarioDir.parent?.takeIf { it.name == AnsibleLayout.MOLECULE }
        return MoleculeScenario(
            configFile = configFile,
            scenarioDir = scenarioDir,
            name = name,
            roleDir = moleculeDir?.parent,
            platforms = (document?.get("platforms") as? YSeq)?.items.orEmpty().filterIsInstance<YMap>().map(::platform),
            groupVars = varsBlocks(inventory?.get("group_vars")),
            hostVars = varsBlocks(inventory?.get("host_vars")),
            hosts = inventory?.get("hosts"),
            inventoryLinks = (inventory?.get("links") as? YMap)?.entries.orEmpty().mapNotNull { entry ->
                (entry.value as? YScalar)?.let { entry.key.text to nameRef(it) }
            }.toMap(),
            playbooks = playbooks(provisioner?.get("playbooks") as? YMap, scenarioDir),
            varsFiles = varsFiles(scenarioDir, moleculeDir),
        )
    }

    private fun platform(map: YMap): MoleculePlatform = MoleculePlatform(
        name = (map["name"] as? YScalar)?.let(::nameRef),
        image = (map["image"] as? YScalar)?.text,
        groups = names(map["groups"]),
        children = names(map["children"]),
        value = map,
    )

    private fun varsBlocks(value: YValue?): List<MoleculeVarsBlock> =
        (value as? YMap)?.entries.orEmpty().mapNotNull { entry -> (entry.value as? YMap)?.let { MoleculeVarsBlock(entry.key, it) } }

    private fun playbooks(declared: YMap?, scenarioDir: VirtualFile): Map<String, VirtualFile> {
        val result = LinkedHashMap<String, VirtualFile>()
        declared?.entries?.forEach { entry ->
            val path = (entry.value as? YScalar)?.text ?: return@forEach
            scenarioDir.findFileByRelativePath(path)?.takeIf { !it.isDirectory }?.let { result[entry.key.text] = it }
        }
        for (step in AnsibleLayout.MOLECULE_PLAYBOOK_STEMS) {
            if (step in result) continue
            val file = scenarioDir.findChild("$step.yml") ?: scenarioDir.findChild("$step.yaml")
            if (file != null && !file.isDirectory) result[step] = file
        }
        return result
    }

    private fun varsFiles(scenarioDir: VirtualFile, moleculeDir: VirtualFile?): List<VirtualFile> {
        val result = ArrayList<VirtualFile>()
        val dirs = listOfNotNull(
            moleculeDir?.findChild("vars"),
            scenarioDir.findChild("vars"),
            scenarioDir.findChild(AnsibleLayout.GROUP_VARS),
            scenarioDir.findChild(AnsibleLayout.HOST_VARS),
        ).filter { it.isDirectory }
        for (dir in dirs) collectVarsFiles(dir, result)
        listOf("vars.yml", "vars.yaml").mapNotNull { scenarioDir.findChild(it) }.filterTo(result) { !it.isDirectory }
        return result.distinct().sortedBy { it.path }
    }

    private fun collectVarsFiles(dir: VirtualFile, into: MutableList<VirtualFile>) {
        for (child in dir.children.orEmpty()) {
            ProgressManager.checkCanceled()
            when {
                AnsibleLayout.isIgnoredVarsEntry(child.name) -> Unit
                child.isDirectory -> collectVarsFiles(child, into)
                child.name.substringAfterLast('.', "").lowercase() in AnsibleLayout.VARS_EXTENSIONS -> into += child
            }
        }
    }

    private fun names(value: YValue?): List<NameRef> = when (value) {
        is YScalar -> listOf(nameRef(value))
        is YSeq -> value.items.filterIsInstance<YScalar>().map(::nameRef)
        else -> emptyList()
    }

    private fun nameRef(scalar: YScalar): NameRef =
        NameRef(scalar.text, scalar.range?.let { TextRange(it.start, it.end) } ?: TextRange.EMPTY_RANGE)
}
