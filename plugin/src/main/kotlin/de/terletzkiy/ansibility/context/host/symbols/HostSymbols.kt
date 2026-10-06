package de.terletzkiy.ansibility.context.host.symbols

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import de.terletzkiy.ansibility.api.AnsibleContextService
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.RootKind
import de.terletzkiy.ansibility.api.SourceLocation
import de.terletzkiy.ansibility.context.host.AnsibleContextServiceImpl
import de.terletzkiy.ansibility.model.inventory.InventoryModels
import de.terletzkiy.ansibility.semantics.inventory.HostPattern
import de.terletzkiy.ansibility.semantics.inventory.InventoryGraph
import de.terletzkiy.ansibility.semantics.inventory.InventoryLocation
import de.terletzkiy.ansibility.semantics.yaml.YScalar
import de.terletzkiy.ansibility.settings.EnvironmentChoice

/** One inventory a host symbol resolves against: an environment of the root, or the molecule scenario of the file. */
internal class SymbolEnvironment(
    val name: String,
    val graph: InventoryGraph,
    private val sourceFiles: List<VirtualFile?>,
    /** The scenario directory, root-relative, for a molecule scenario. */
    val molecule: String?,
) {
    fun fileOf(location: InventoryLocation): VirtualFile? = sourceFiles.getOrNull(location.sourceIndex)

    /** Where [location] is, or null when it has no file or range. */
    fun locationOf(location: InventoryLocation): SourceLocation? {
        val file = fileOf(location) ?: return null
        return SourceLocation(file, location.range?.start ?: 0)
    }

    /** `ansible_host` of [host] as written, or null; vault values are never shown. */
    fun address(host: String): String? = (graph.host(host)?.ansibleHost as? YScalar)?.text?.takeIf { it.isNotBlank() }
}

/** What a file's host symbols resolve against: its root, the environments in presentation order, and molecule-only groups. */
internal class SymbolScope(
    val root: AnsibleRoot,
    val environments: List<SymbolEnvironment>,
    /** Groups that no environment defines but a molecule scenario of the root does: group → scenario dir. */
    val moleculeOnlyGroups: Map<String, String>,
) {
    fun groupDefinedAnywhere(group: String): Boolean = environments.any { group in it.graph.groups }
}

/**
 * Resolution of host symbols (F8.8). A molecule file resolves against its scenario's pseudo-inventory, every other
 * file against all environments of its inventory root. The selection only orders the environments (the selected one
 * first); it never changes what resolves. Call inside a read action.
 */
internal object HostSymbols {
    fun scope(project: Project, file: VirtualFile): SymbolScope? {
        val context = AnsibleWorkspace.getInstance(project).contextOf(file) ?: return null
        val service = AnsibleContextService.getInstance(project) as? AnsibleContextServiceImpl ?: return null
        val model = service.model
        val root = model.inventoryRoot(context.root)
        val models = InventoryModels.getInstance(project)
        val family = listOf(root) + model.workspace.roots().filter { it.kind == RootKind.NESTED_PLAYBOOK && it.parentDir == root.dir }
        val molecules = (listOf(context.root) + family).distinct().flatMap(model::molecules).distinctBy { it.scenarioDir }
        context.moleculeScenarioDir?.let { scenarioDir ->
            val molecule = molecules.firstOrNull { it.scenarioDir == scenarioDir } ?: return@let
            val owner = model.workspace.rootFor(molecule.scenarioDir) ?: root
            val parsed = models.moleculeModel(owner, molecule)
            val label = relative(root, molecule.scenarioDir)
            return SymbolScope(root, listOf(SymbolEnvironment(model.moleculeEnvironment(molecule), parsed.graph, parsed.sourceFiles, label)), emptyMap())
        }
        val environments = model.environments(root).map { SymbolEnvironment(it.name, it.graph, it.sourceFiles, null) }
        val selected = (service.selection(context.root).environment as? EnvironmentChoice.Named)?.name
        val ordered = environments.sortedBy { if (it.name == selected) 0 else 1 }
        val moleculeOnly = LinkedHashMap<String, String>()
        for (molecule in molecules) {
            for (group in molecule.inventory.groups.keys) {
                if (group in IMPLICIT_GROUPS || environments.any { group in it.graph.groups }) continue
                moleculeOnly.putIfAbsent(group, relative(root, molecule.scenarioDir))
            }
        }
        return SymbolScope(root, ordered, moleculeOnly)
    }

    /** The hosts [pattern] selects in [environment], without the implicit localhost, plus whether it is implicit. */
    fun match(environment: SymbolEnvironment, pattern: String): PatternResult {
        val match = HostPattern.resolve(environment.graph, pattern)
        return PatternResult(match.hosts.filter { it !in match.implicitHosts }, match.implicitHosts.isNotEmpty(), match.unmatched, match.errors)
    }

    class PatternResult(val hosts: List<String>, val implicitLocalhost: Boolean, val unmatched: List<String>, val errors: List<String>)

    /** Where [group] is defined in [environment]: its keys in the inventory sources, in source order. */
    fun groupLocations(environment: SymbolEnvironment, group: String): List<SourceLocation> =
        environment.graph.group(group)?.definitions.orEmpty().mapNotNull(environment::locationOf)

    /** Where [host] is defined in [environment]: its host-pattern keys. */
    fun hostLocations(environment: SymbolEnvironment, host: String): List<SourceLocation> =
        environment.graph.host(host)?.definitions.orEmpty().mapNotNull(environment::locationOf)

    /** The single names of [pattern] (`web:&prod:!db` → `web`, `prod`, `db`), without operators and subscripts. */
    fun names(pattern: String): List<String> = HostPattern.split(pattern)
        .map { it.trimStart('&', '!').substringBefore('[') }
        .filter { it.isNotEmpty() && !it.startsWith('~') && it.none { c -> c == '*' || c == '?' } }

    private fun relative(root: AnsibleRoot, dir: VirtualFile): String = VfsUtilCore.getRelativePath(dir, root.dir) ?: dir.path

    val IMPLICIT_GROUPS = setOf(InventoryGraph.ALL, InventoryGraph.UNGROUPED)
    val LOCALHOST_NAMES = setOf("localhost", "127.0.0.1", "::1")
}
