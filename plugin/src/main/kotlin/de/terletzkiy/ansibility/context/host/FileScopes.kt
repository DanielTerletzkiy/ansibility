package de.terletzkiy.ansibility.context.host

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.EvalTarget
import de.terletzkiy.ansibility.api.FileContext
import de.terletzkiy.ansibility.api.FileKind
import de.terletzkiy.ansibility.api.HostScopeOrigin
import de.terletzkiy.ansibility.api.MoleculeInventory
import de.terletzkiy.ansibility.api.PlayGraph
import de.terletzkiy.ansibility.api.PlayRef
import de.terletzkiy.ansibility.api.RoleReach
import de.terletzkiy.ansibility.api.TemplateContextService
import de.terletzkiy.ansibility.context.AnsibleLayout
import de.terletzkiy.ansibility.context.MoleculeVisibility
import de.terletzkiy.ansibility.model.inventory.EnvironmentModel
import de.terletzkiy.ansibility.model.inventory.ModelInputs
import de.terletzkiy.ansibility.model.task.TaskFileKind
import de.terletzkiy.ansibility.model.task.TaskFileModels
import de.terletzkiy.ansibility.model.task.YamlFiles
import de.terletzkiy.ansibility.semantics.inventory.InventoryLocation
import de.terletzkiy.ansibility.yaml.YamlPaths
import org.jetbrains.yaml.psi.YAMLFile

/**
 * Per-file inference of the hosts a file applies to (plan amendment R7/R8, A.14 "Per-file inference"):
 *
 * | File | Applies to |
 * |---|---|
 * | `environments/E/host_vars/H` | E/H, in each play that hits H |
 * | `environments/E/group_vars/G` | the hosts of G in E, children included (`all`: every host of E) |
 * | playbook-level `group_vars`/`host_vars` | the same in every environment, only in plays of that playbook dir |
 * | `environments/E/hosts.yml` | the group block or host entry at the caret, else every host of E |
 * | role file | the role's reach ([RoleReach]); the role is the running role |
 * | template | as a role file, through the roles of its render contexts |
 * | playbook | the play at the caret, else the plays the playbook runs |
 * | `vars_files` target | where the plays that load it run |
 * | molecule file | its scenario's pseudo-inventory |
 * | golden role (no inventory) | its molecule scenarios (with `moleculeHosts`; otherwise "no inventory") |
 * | other | [HostScopeOrigin.RootWide]: the selection |
 *
 * `moleculeHosts` (plan amendment R20, D156): whether a role library's role without inventory may use its Molecule
 * scenarios as its hosts. Inspections ([AnsibleContextServiceImpl.allHostsScope]) and the other presentation scopes
 * ([AnsibleContextServiceImpl.hostScope]: Template Preview, value previews, banners, Show Ansible Context) always pass
 * true; cards, the status-bar segment and Ctrl+B's ranking ([AnsibleContextServiceImpl.cardScope]) pass "Show Molecule in
 * navigation and search", so with it off such a role's cards say "no inventory".
 *
 * An inventory-level file's hosts are evaluated in every play that hits them (deduplicated, [ContextModel.deduplicate]),
 * or inventory-only when no play does. Call in a read action in smart mode (render contexts and reach read indexes).
 *
 * Everything a scope is computed from is recorded as an input of the caller's cache entry ([ModelInputs]): the model
 * caches it reads, the file itself where the caret position matters (`hosts.yml`, playbooks) and, for templates, the
 * roles and plays that render them ([TemplateContextService], compared by value).
 */
internal class FileScopes(
    private val project: Project,
    private val model: ContextModel,
    private val reach: (AnsibleRoot, String) -> RoleReach,
) {
    /** The bucket of [offset] in [file]: what decides the caret-dependent part of its scope (cache key). */
    fun bucket(file: VirtualFile, context: FileContext?, offset: Int): String = when {
        offset < 0 || context == null -> ""
        context.kind == FileKind.PLAYBOOK -> playAt(file, offset)?.playIndex?.let { "play:$it" }.orEmpty()
        context.kind == FileKind.INVENTORY -> inventoryEntryAt(file, offset).let { (group, host) -> "group:${group.orEmpty()}|host:${host.orEmpty()}" }
        context.kind == FileKind.INVENTORY_INI && context.environment != null ->
            iniEntryAt(context.root, context.environment, file, offset).let { (group, host) -> "group:${group.orEmpty()}|host:${host.orEmpty()}" }
        else -> ""
    }

    /** The file scope of [file] at [offset] (negative: file level) in [root]; [moleculeHosts] as in the class comment. */
    fun of(root: AnsibleRoot, file: VirtualFile, context: FileContext?, offset: Int, moleculeHosts: Boolean): FileScope {
        if (context == null) return rootWide(root)
        val molecule = MoleculeVisibility.isMoleculeFile(context.root, file)
        return when {
            molecule -> moleculeScope(root, context)
            context.kind == FileKind.HOST_VARS && context.host != null -> hostVarsScope(root, file, context, context.host)
            context.kind == FileKind.GROUP_VARS && context.group != null -> groupVarsScope(root, file, context, context.group)
            context.kind == FileKind.INVENTORY && context.environment != null -> inventoryScope(root, file, context.environment, offset)
            context.kind == FileKind.INVENTORY_INI && context.environment != null -> inventoryScope(root, file, context.environment, offset, ini = true)
            context.kind == FileKind.PLAYBOOK -> playbookScope(root, file, offset)
            context.kind == FileKind.ROLE_TEMPLATE && context.roleName != null && context.roleDir != null ->
                templateScope(root, file, context.roleName, context.roleDir, moleculeHosts)
            context.roleName != null && context.roleDir != null -> roleScope(root, context.roleName, context.roleDir, moleculeHosts)
            context.kind == FileKind.OTHER -> varsFilesScope(root, file) ?: rootWide(root)
            else -> rootWide(root)
        }
    }

    private fun rootWide(root: AnsibleRoot) = FileScope(HostScopeOrigin.RootWide, emptyList(), null, null, followsSelection = true)

    // ------------------------------------------------------------------------------------------------ inventory files

    private fun hostVarsScope(root: AnsibleRoot, file: VirtualFile, context: FileContext, host: String): FileScope {
        val environment = context.environment
        val playbookDir = if (environment == null) varsOwnerDir(file) else null
        val environments = model.environments(root).filter { (environment == null || it.name in context.environments) && it.graph.host(host) != null }
        if (environments.isEmpty()) {
            val reason = if (environment != null) {
                AnsibilityHostBundle.message("scope.empty.host.not.in.environment", host, environment)
            } else {
                AnsibilityHostBundle.message("scope.empty.host.not.in.inventory", host, root.displayName)
            }
            val origin = environment?.let { HostScopeOrigin.HostVars(model.hostKey(root, it, host)) } ?: HostScopeOrigin.RootWide
            return FileScope(origin, emptyList(), reason, null)
        }
        val targets = environments.flatMap { env -> hostTargets(root, env, listOf(host), playbookDir) }
        return FileScope(HostScopeOrigin.HostVars(model.hostKey(root, environments.first().name, host)), finish(root, targets), null, null)
    }

    private fun groupVarsScope(root: AnsibleRoot, file: VirtualFile, context: FileContext, group: String): FileScope {
        val environment = context.environment
        val playbookDir = if (environment == null) varsOwnerDir(file) else null
        val environments = model.environments(root).filter { (environment == null || it.name in context.environments) && it.graph.group(group) != null }
        val origin = HostScopeOrigin.GroupVars(environment, group)
        if (environments.isEmpty()) {
            val reason = if (environment != null) {
                AnsibilityHostBundle.message("scope.empty.group.not.in.environment", group, environment)
            } else {
                AnsibilityHostBundle.message("scope.empty.group.not.in.inventory", group, root.displayName)
            }
            return FileScope(origin, emptyList(), reason, null)
        }
        val targets = environments.flatMap { env -> hostTargets(root, env, env.graph.hostsOf(group), playbookDir) }
        return FileScope(origin, finish(root, targets), null, null)
    }

    private fun inventoryScope(root: AnsibleRoot, file: VirtualFile, environment: String, offset: Int, ini: Boolean = false): FileScope {
        val env = model.environment(root, environment)
            ?: return FileScope(HostScopeOrigin.InventoryEntry(environment, null, null), emptyList(), AnsibilityHostBundle.message("scope.empty.no.inventory", root.displayName), null)
        val (group, host) = when {
            offset < 0 -> null to null
            ini -> iniEntryAt(root, environment, file, offset)
            else -> inventoryEntryAt(file, offset)
        }
        val knownHost = host?.takeIf { env.graph.host(it) != null }
        val knownGroup = group?.takeIf { env.graph.group(it) != null }
        val hosts = when {
            knownHost != null -> listOf(knownHost)
            knownGroup != null -> env.graph.hostsOf(knownGroup)
            else -> env.graph.hosts.keys.toList()
        }
        val origin = HostScopeOrigin.InventoryEntry(environment, knownGroup.takeIf { knownHost == null }, knownHost)
        return FileScope(origin, finish(root, hostTargets(root, env, hosts, playbookDir = null)), null, null)
    }

    /**
     * Each of [hosts] of [env] in every play that hits it; with [playbookDir] (playbook-level vars) only in plays of
     * that directory. A host no such play hits is evaluated inventory-only (with [playbookDir], or the root's dir).
     */
    private fun hostTargets(root: AnsibleRoot, env: EnvironmentModel, hosts: List<String>, playbookDir: VirtualFile?): List<EvalTarget> {
        val hits = model.playHits(root).filter { playbookDir == null || it.play.playbookDir == playbookDir }
        val targets = ArrayList<EvalTarget>()
        for (host in hosts) {
            ProgressManager.checkCanceled()
            val key = model.hostKey(root, env.name, host)
            val plays = hits.filter { host in it.hostsByEnvironment[env.name].orEmpty() }
            if (plays.isEmpty()) targets += EvalTarget(key, null, playbookDir ?: model.defaultPlaybookDir(root))
            else plays.mapTo(targets) { EvalTarget(key, it.play, it.play.playbookDir) }
        }
        return targets
    }

    /** The playbook dir of a playbook-level vars file: the parent of its `group_vars`/`host_vars` directory. */
    private fun varsOwnerDir(file: VirtualFile): VirtualFile? =
        generateSequence(file.parent) { it.parent }
            .firstOrNull { it.name == AnsibleLayout.GROUP_VARS || it.name == AnsibleLayout.HOST_VARS }
            ?.parent

    /** The group block and host entry of `hosts.yml` at [offset] (the innermost `children` group). */
    private fun inventoryEntryAt(file: VirtualFile, offset: Int): Pair<String?, String?> {
        ModelInputs.file(project, file)
        val yaml = PsiManager.getInstance(project).findFile(file) as? YAMLFile ?: return null to null
        val element = yaml.findElementAt(offset) ?: return null to null
        val path = YamlPaths.keyPath(element)
        if (path.isEmpty()) return null to null
        var group = path[0]
        var i = 1
        while (i < path.size) {
            when (path[i]) {
                HOSTS -> return group to path.getOrNull(i + 1)
                CHILDREN -> {
                    group = path.getOrNull(i + 1) ?: return group to null
                    i += 2
                }
                else -> return group to null
            }
        }
        return group to null
    }

    /**
     * The group section and host line at [offset] of the INI inventory [file], from the parsed graph's ranges: the
     * host when the caret is on its line, and the section the caret is in (`[web]`, `[web:vars]`, `[web:children]`).
     */
    private fun iniEntryAt(root: AnsibleRoot, environment: String, file: VirtualFile, offset: Int): Pair<String?, String?> {
        val env = model.environment(root, environment) ?: return null to null
        val text = PsiManager.getInstance(project).findFile(file)?.text ?: return null to null
        val at = offset.coerceIn(0, text.length)
        val lineStart = if (at == 0) 0 else text.lastIndexOf('\n', at - 1) + 1
        val lineEnd = text.indexOf('\n', at).let { if (it < 0) text.length else it }
        fun onLine(location: InventoryLocation): Boolean {
            val start = location.range?.start ?: return false
            return env.fileOf(location) == file && start >= lineStart && start <= lineEnd
        }
        val host = env.graph.hosts.values.firstOrNull { h -> h.definitions.any(::onLine) }?.name
        val group = INI_SECTION.findAll(text.substring(0, lineEnd)).lastOrNull()?.groupValues?.get(1)?.trim()
            ?.takeIf { env.graph.group(it) != null }
        return group to host
    }

    // ------------------------------------------------------------------------------------------------ playbooks

    private fun playbookScope(root: AnsibleRoot, file: VirtualFile, offset: Int): FileScope {
        val graph = PlayGraph.getInstance(project)
        val atCaret = if (offset >= 0) playAt(file, offset) else null
        val plays = atCaret?.let(::listOf) ?: graph.executionOrder(file)
        val first = atCaret ?: graph.playsOf(file).firstOrNull()?.ref ?: plays.firstOrNull()
            ?: return FileScope(HostScopeOrigin.RootWide, emptyList(), null, null, followsSelection = true)
        val environments = model.environments(root)
        val targets = ArrayList<EvalTarget>()
        for (play in plays) {
            val hits = model.playHits(root).firstOrNull { it.play == play } ?: model.hitsOf(play, environments)
            for ((environment, hosts) in hits.hostsByEnvironment) {
                hosts.mapTo(targets) { EvalTarget(model.hostKey(root, environment, it), play, play.playbookDir) }
            }
        }
        val reason = when {
            targets.isNotEmpty() -> null
            atCaret != null -> AnsibilityHostBundle.message("scope.empty.play.no.hosts", Selections.playLabel(atCaret), root.displayName)
            else -> AnsibilityHostBundle.message("scope.empty.playbook.no.hosts", root.displayName)
        }
        return FileScope(HostScopeOrigin.Play(first), finish(root, targets, plays), reason, null)
    }

    /** A file that plays load through `vars_files`: where those plays run; null when no play loads it. */
    private fun varsFilesScope(root: AnsibleRoot, file: VirtualFile): FileScope? {
        val graph = PlayGraph.getInstance(project)
        val hits = model.playHits(root).filter { hit -> graph.play(hit.play)?.varsFiles?.any { it.file == file } == true }
        if (hits.isEmpty()) return null
        val targets = hits.flatMap { hit ->
            hit.hostsByEnvironment.flatMap { (environment, hosts) -> hosts.map { EvalTarget(model.hostKey(root, environment, it), hit.play, hit.play.playbookDir) } }
        }
        val plays = hits.map { it.play }
        val reason = AnsibilityHostBundle.message("scope.empty.play.no.hosts", Selections.playLabel(plays.first()), root.displayName).takeIf { targets.isEmpty() }
        return FileScope(HostScopeOrigin.Play(plays.first()), finish(root, targets, plays), reason, null)
    }

    /** The play of [file] whose mapping contains [offset]. */
    private fun playAt(file: VirtualFile, offset: Int): PlayRef? {
        ModelInputs.file(project, file)
        val yaml = YamlFiles.yamlFile(project, file) ?: return null
        val node = TaskFileModels.of(yaml, TaskFileKind.PLAYBOOK).playAt(offset) ?: return null
        return PlayGraph.getInstance(project).playsOf(file).getOrNull(node.index)?.ref
    }

    // ------------------------------------------------------------------------------------------------ roles

    private fun roleScope(root: AnsibleRoot, role: String, roleDir: VirtualFile, moleculeHosts: Boolean): FileScope {
        val reach = reach(root, role)
        if (reach.targets.isEmpty() && model.environments(root).isEmpty()) {
            val scenarios = if (moleculeHosts) model.molecules(root).filter { it.scenarioDir.parent?.parent == roleDir } else emptyList()
            if (scenarios.isNotEmpty()) {
                return FileScope(HostScopeOrigin.RoleReach(role, emptyList()), scenarios.flatMap { model.moleculeTargets(root, it) }, null, role)
            }
            return FileScope(HostScopeOrigin.RootWide, emptyList(), AnsibilityHostBundle.message("scope.empty.no.inventory", root.displayName), role, followsSelection = true)
        }
        return FileScope(HostScopeOrigin.RoleReach(role, reach.plays), finish(root, reach.targets, reach.plays), reach.emptyReason, role)
    }

    /** The roles of [root] and the plays whose tasks render [template] ([TemplateContextService.renderContexts]). */
    private data class Renderers(val roles: Set<String>, val directPlays: Set<PlayRef>)

    private fun renderers(root: AnsibleRoot, template: VirtualFile): Renderers {
        val roles = LinkedHashSet<String>()
        val directPlays = LinkedHashSet<PlayRef>()
        for (context in TemplateContextService.getInstance(project).renderContexts(template)) {
            ProgressManager.checkCanceled()
            val role = context.role
            if (role != null && role.rootDir == root.dir) roles += role.name
            else if (role == null) playAt(context.taskSite.file, context.taskSite.offset)?.let(directPlays::add)
        }
        return Renderers(roles, directPlays)
    }

    /** A template applies where the roles that render it run (playbook-task renders: where that play runs). */
    private fun templateScope(root: AnsibleRoot, template: VirtualFile, ownRole: String, roleDir: VirtualFile, moleculeHosts: Boolean): FileScope {
        // The render contexts have their own cache: the scope depends on what they say, compared by value.
        val renderers = ModelInputs.untracked { renderers(root, template) }
        ModelInputs.external(renderers) { renderers(root, template) }
        val roles = renderers.roles
        val directPlays = renderers.directPlays
        if (roles.isEmpty() && directPlays.isEmpty()) return roleScope(root, ownRole, roleDir, moleculeHosts)
        val primary = ownRole.takeIf { it in roles } ?: roles.firstOrNull() ?: ownRole
        val reaches = roles.map { reach(root, it) }
        val plays = (reaches.flatMap { it.plays } + directPlays).distinct()
        val environments = model.environments(root)
        val targets = reaches.flatMap { it.targets } + directPlays.flatMap { play ->
            val hits = model.playHits(root).firstOrNull { it.play == play } ?: model.hitsOf(play, environments)
            hits.hostsByEnvironment.flatMap { (environment, hosts) -> hosts.map { EvalTarget(model.hostKey(root, environment, it), play, play.playbookDir) } }
        }
        val reason = reaches.firstOrNull { it.role == primary }?.emptyReason.takeIf { targets.isEmpty() }
        return FileScope(HostScopeOrigin.RoleReach(primary, plays), finish(root, targets, plays), reason, primary)
    }

    // ------------------------------------------------------------------------------------------------ molecule

    private fun moleculeScope(root: AnsibleRoot, context: FileContext): FileScope {
        val scenarios = model.molecules(root).let { all ->
            val scenarioDir = context.moleculeScenarioDir
            if (scenarioDir != null) {
                all.filter { it.scenarioDir == scenarioDir }
            } else {
                all.filter { it.roleName == context.roleName && (context.roleDir == null || it.scenarioDir.parent?.parent == context.roleDir) }
            }
        }
        val originDir = context.moleculeScenarioDir ?: scenarios.firstOrNull()?.scenarioDir?.parent ?: context.roleDir ?: root.dir
        val origin = HostScopeOrigin.Molecule(originDir)
        if (scenarios.isEmpty()) {
            return FileScope(origin, emptyList(), AnsibilityHostBundle.message("scope.empty.molecule.unknown", root.displayName), context.roleName)
        }
        val targets = scenarios.flatMap { model.moleculeTargets(root, it) }
        val reason = AnsibilityHostBundle.message("scope.empty.molecule.no.hosts", scenarioLabel(scenarios.first())).takeIf { targets.isEmpty() }
        return FileScope(origin, targets, reason, scenarios.first().roleName ?: context.roleName)
    }

    private fun scenarioLabel(molecule: MoleculeInventory): String =
        molecule.roleName?.let { "$it/${molecule.scenarioDir.name}" } ?: molecule.scenarioDir.name

    // ------------------------------------------------------------------------------------------------ common

    /** [targets] deduplicated and sorted (environments, inventory order, then [plays] order). */
    private fun finish(root: AnsibleRoot, targets: List<EvalTarget>, plays: List<PlayRef> = model.playHits(root).map { it.play }): List<EvalTarget> =
        model.deduplicate(model.sorted(root, targets.distinct(), plays))

    private companion object {
        val INI_SECTION = Regex("""^[ \t]*\[([^\]:]+)(?::\w+)?][ \t]*$""", RegexOption.MULTILINE)
        const val HOSTS = "hosts"
        const val CHILDREN = "children"
    }
}
