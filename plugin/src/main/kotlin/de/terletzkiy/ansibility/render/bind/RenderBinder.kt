package de.terletzkiy.ansibility.render.bind

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.vfs.VirtualFile
import de.terletzkiy.ansibility.api.EvalTarget
import de.terletzkiy.ansibility.api.HostValue
import de.terletzkiy.ansibility.api.HostValues
import de.terletzkiy.ansibility.api.InventoryFacts
import de.terletzkiy.ansibility.api.RuntimeMarkerKind
import de.terletzkiy.ansibility.api.SourceLocation
import de.terletzkiy.ansibility.semantics.CoreVersion
import de.terletzkiy.ansibility.semantics.render.Binding
import de.terletzkiy.ansibility.semantics.render.Placeholder
import de.terletzkiy.ansibility.semantics.render.RValue
import de.terletzkiy.ansibility.semantics.render.RValues
import de.terletzkiy.ansibility.semantics.render.RenderOptions
import de.terletzkiy.ansibility.semantics.render.RenderScope

/**
 * A render site plus a context gives a [RenderScope] (plan amendment R11, A.17 "The binder"), lowest to highest:
 * the effective values of [values] (the task's own vars already inside the precedence engine), magic variables,
 * then [locals] (the loop item, `index_var`, `ansible_loop`). Facts, registered results and names only a runtime
 * task sets are holes; vault values are holes too and are never decrypted (D71).
 */
internal class RenderBinder(
    private val target: EvalTarget?,
    private val values: HostValues?,
    private val facts: InventoryFacts?,
    private val template: VirtualFile,
    private val roleName: String?,
    private val roleDir: VirtualFile?,
    private val playbookDir: VirtualFile?,
    private val core: CoreVersion,
    /** `ansible_managed` from the layout's `ansible.cfg`, or null for the default. */
    private val ansibleManaged: String?,
    /** Decrypted vault values by name, only while the preview's "Render vault values" is on; else every vault value is a hole. */
    private val secrets: Map<String, String> = emptyMap(),
) {
    /** The vault values the render met, by name, with where each is written (so the preview can decrypt them on request). */
    val secretSources: MutableMap<String, SourceLocation> = LinkedHashMap()

    /** How many vault values the render printed in plaintext. */
    var secretsShown: Int = 0
        private set

    fun scope(locals: Map<String, RValue> = emptyMap()): RenderScope = RenderScope { name ->
        ProgressManager.checkCanceled()
        locals[name]?.let { return@RenderScope Binding.Computed(it) }
        lookup(name)
    }

    private fun lookup(name: String): Binding {
        // 2.19+: a user variable named ansible_managed wins over the configured one; 2.18 always uses the config.
        val userManaged = name == ANSIBLE_MANAGED && core >= RenderOptions.V2_19
        if (!userManaged) magic(name)?.let { return it }
        values?.valueOf(name)?.let { return binding(name, it) }
        if (userManaged) magic(name)?.let { return it }
        if (name == "ansible_facts" || name.startsWith("ansible_") || name.startsWith("facter_") || name.startsWith("ohai_")) {
            return Binding.Unknown(Placeholder(Placeholder.Kind.FACT, name))
        }
        if (target == null) return Binding.Undefined(proven = false, why = "no host renders this template")
        return Binding.Undefined(proven = true)
    }

    private fun binding(name: String, value: HostValue): Binding {
        if (value.secret) {
            value.source?.let { secretSources[name] = SourceLocation(it.file, it.offset) }
            secrets[name]?.let {
                secretsShown++
                return Binding.Computed(RValue.Str(it))
            }
            return Binding.Unknown(Placeholder.secret(name))
        }
        val yaml = value.value
        if (yaml == null) {
            val marker = value.runtimeMarkers.first()
            val what = when (marker.kind) {
                RuntimeMarkerKind.REGISTER -> "register $name"
                RuntimeMarkerKind.SET_FACT -> "set_fact $name"
                RuntimeMarkerKind.INCLUDE_VARS -> "include_vars $name"
            }
            return Binding.Unknown(Placeholder(Placeholder.Kind.RUNTIME, "$what (${marker.location.file.name})"))
        }
        val label = value.source?.file?.name ?: name
        return Binding.Yaml(yaml, label, json = value.source?.file?.extension == "json")
    }

    private fun magic(name: String): Binding? {
        val host = target?.host
        val computed: RValue? = when (name) {
            "inventory_hostname" -> host?.let { RValue.Str(it.host) }
            "inventory_hostname_short" -> host?.let { RValue.Str(it.host.substringBefore('.')) }
            "group_names" -> host?.let { key ->
                facts?.host(key)?.groupNames?.let { names -> RValue.List(names.mapTo(ArrayList()) { RValue.Str(it) }) }
            }
            "groups" -> host?.let { key ->
                facts?.environment(key.environment)?.groups?.let { groups ->
                    RValue.Dict.of(groups.map { (group, hosts) -> RValue.Str(group) to RValue.List(hosts.mapTo(ArrayList()) { RValue.Str(it) }) })
                }
            }
            "playbook_dir" -> playbookDir?.let { RValue.Str(it.path) }
            "ansible_play_name" -> target?.play?.name?.let { RValue.Str(it) }
            "role_name" -> roleName?.let { RValue.Str(it) }
            "role_path" -> roleDir?.let { RValue.Str(it.path) }
            "ansible_check_mode", "ansible_diff_mode" -> RValue.Bool(false)
            "ansible_version" -> RValue.Dict.of(
                listOf(
                    RValue.Str("full") to RValue.Str(core.toString()),
                    RValue.Str("major") to RValues.int(core.major),
                    RValue.Str("minor") to RValues.int(core.minor),
                    RValue.Str("revision") to RValues.int(core.patch),
                    RValue.Str("string") to RValue.Str(core.toString()),
                ),
            )
            ANSIBLE_MANAGED -> RValue.Str(ansibleManaged ?: DEFAULT_ANSIBLE_MANAGED)
            "template_path", "template_fullpath" -> RValue.Str(template.path)
            else -> null
        }
        if (computed != null) return Binding.Computed(computed)
        return when (name) {
            "omit" -> Binding.Unknown(Placeholder(Placeholder.Kind.OMIT, "omit"))
            "template_host", "template_uid", "template_run_date", "template_mtime" -> Binding.Unknown(Placeholder(Placeholder.Kind.CONTROLLER, name))
            "template_destpath" -> Binding.Unknown(Placeholder.unknown("template_destpath: the task's rendered dest"))
            "hostvars" -> Binding.Unknown(Placeholder.notEmulated("hostvars"))
            "inventory_dir", "inventory_file" -> Binding.Unknown(Placeholder(Placeholder.Kind.CONTROLLER, name))
            else -> null
        }
    }

    private companion object {
        const val ANSIBLE_MANAGED = "ansible_managed"
        const val DEFAULT_ANSIBLE_MANAGED = "Ansible managed"
    }
}
