package de.terletzkiy.ansibility.context.host

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.EnvironmentFacts
import de.terletzkiy.ansibility.api.EvalTarget
import de.terletzkiy.ansibility.api.HostFacts
import de.terletzkiy.ansibility.api.HostKey
import de.terletzkiy.ansibility.api.HostScope
import de.terletzkiy.ansibility.api.InventoryFacts
import de.terletzkiy.ansibility.index.JinjaBearing
import de.terletzkiy.ansibility.model.inventory.ModelCache
import de.terletzkiy.ansibility.semantics.yaml.YMap
import de.terletzkiy.ansibility.semantics.yaml.YScalar
import de.terletzkiy.ansibility.semantics.yaml.YValue

/**
 * `groups`, `group_names` and addresses of a scope's environments (plan amendment R7/R8, F8.8/F8.9, the X02 and
 * tool-window badges), from the inventory views only (no role defaults, no play vars: ansible-core's
 * `HostVars.raw_get`).
 *
 * `ansible_host` is the effective value of the host's inventory view, with the playbook dir of the scope (the
 * root's directory for file-free scopes), so playbook-level `group_vars` can feed a templated address. A template of
 * the forms `{{ name }}`, `{{ name['key'] }}` and `{{ name.key }}` is evaluated against the same view; anything else
 * stays a template ([HostFacts.addressTemplate]) without an address. Vault values have no address.
 *
 * Shared addresses are computed per root over every host of every environment, in a [ModelCache] entry that depends on
 * the inventory views it read (so only inventory and vars-file edits of that root invalidate it).
 */
internal class InventoryFactsBuilder(project: Project, private val model: ContextModel, private val evaluator: ContextEvaluator) {
    private class Address(val address: String?, val template: String?)

    private val addresses = ModelCache<VirtualFile, Map<HostKey, Address>>(project, "context.addresses")

    fun facts(scope: HostScope): InventoryFacts {
        val root = scope.root
        val byEnvironment = LinkedHashMap<String, VirtualFile?>()
        for (target in scope.targets) byEnvironment.putIfAbsent(target.host.environment, target.playbookDir)
        val environments = byEnvironment.mapNotNull { (environment, playbookDir) -> environmentFacts(root, environment, playbookDir) }
        return InventoryFacts(environments)
    }

    private fun environmentFacts(root: AnsibleRoot, environment: String, playbookDir: VirtualFile?): EnvironmentFacts? {
        val graph = model.graphOf(root, environment) ?: return null
        val groups = graph.groups.keys.associateWithTo(LinkedHashMap()) { graph.hostsOf(it) }
        val molecule = environment.startsWith(HostKey.MOLECULE_PREFIX)
        val shared = if (molecule) emptyMap() else rootAddresses(root)
        val hosts = graph.hosts.values.map { host ->
            ProgressManager.checkCanceled()
            val key = model.hostKey(root, environment, host.name)
            val address = address(EvalTarget(key, null, playbookDir ?: model.defaultPlaybookDir(root)), root)
            val sharing = address.address?.let { value ->
                shared.filter { (other, otherAddress) -> other != key && otherAddress.address == value }.keys.toList()
            }.orEmpty()
            HostFacts(key, address.address, address.template, host.groupNames, sharing)
        }
        return EnvironmentFacts(environment, groups, hosts)
    }

    /** The address of every host of [root]'s environments, evaluated with the root's own playbook dir. */
    private fun rootAddresses(root: AnsibleRoot): Map<HostKey, Address> {
        val owner = model.inventoryRoot(root)
        return addresses.get(owner.dir) { computeAddresses(owner) }
    }

    private fun computeAddresses(owner: AnsibleRoot): Map<HostKey, Address> {
        val byHost = LinkedHashMap<HostKey, Address>()
        for (environment in model.environments(owner)) {
            for (host in environment.graph.hosts.keys) {
                ProgressManager.checkCanceled()
                val key = model.hostKey(owner, environment.name, host)
                byHost[key] = address(EvalTarget(key, null, model.defaultPlaybookDir(owner)), owner)
            }
        }
        return byHost
    }

    private fun address(target: EvalTarget, root: AnsibleRoot): Address {
        val evaluation = evaluator.evaluation(target, runningRole = null, scopeRoot = root) ?: return Address(null, null)
        val value = evaluation.host.view[ANSIBLE_HOST]?.value as? YScalar ?: return Address(null, null)
        if (!JinjaBearing.hasTemplateMarkers(value.text)) return Address(value.text, null)
        val resolved = evaluateSimple(value.text) { name -> evaluation.host.view[name]?.value }
        return Address(resolved, value.text)
    }

    companion object {
        private const val ANSIBLE_HOST = "ansible_host"
        private val TEMPLATE = Regex("""^\s*\{\{\s*([A-Za-z_][A-Za-z0-9_]*)((?:\s*\[\s*(?:'[^']*'|"[^"]*")\s*]|\.[A-Za-z_][A-Za-z0-9_]*)*)\s*}}\s*$""")
        private val ACCESSOR = Regex("""\[\s*'([^']*)'\s*]|\[\s*"([^"]*)"\s*]|\.([A-Za-z_][A-Za-z0-9_]*)""")

        /**
         * The text [template] evaluates to when it is one variable with literal key accessors whose target is a plain
         * scalar ([lookup] gives a variable's effective value), else null. Nested templates are not followed.
         */
        fun evaluateSimple(template: String, lookup: (String) -> YValue?): String? {
            val match = TEMPLATE.matchEntire(template) ?: return null
            var value: YValue = lookup(match.groupValues[1]) ?: return null
            for (accessor in ACCESSOR.findAll(match.groupValues[2])) {
                val key = accessor.groups[1]?.value ?: accessor.groups[2]?.value ?: accessor.groups[3]?.value ?: return null
                value = (value as? YMap)?.get(key) ?: return null
            }
            val scalar = value as? YScalar ?: return null
            return scalar.text.takeIf { !JinjaBearing.hasTemplateMarkers(it) }
        }
    }
}
