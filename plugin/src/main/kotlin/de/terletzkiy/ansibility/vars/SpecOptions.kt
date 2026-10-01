package de.terletzkiy.ansibility.vars

import com.intellij.openapi.project.Project
import de.terletzkiy.ansibility.api.SourceLocation
import de.terletzkiy.ansibility.api.SpecBinding
import de.terletzkiy.ansibility.model.task.YamlFiles
import de.terletzkiy.ansibility.semantics.schema.OptionSpec
import de.terletzkiy.ansibility.semantics.schema.OptionType
import de.terletzkiy.ansibility.yaml.YamlPaths
import org.jetbrains.yaml.psi.YAMLKeyValue

/** Nested role-spec options: resolving an accessor or key path, and finding the option's key in the spec file. */
internal object SpecOptions {
    /** An option reached from a top-level option through [names] (option names only, sequence indices dropped). */
    class Nested(val option: OptionSpec, val names: List<String>)

    /**
     * Walks [path] (accessors or keys as written, e.g. `["0", "port"]` below `haproxy_servers`) through the `options`
     * of [option]; an integer segment below a `list` option addresses an element and is skipped. Aliases count.
     * Null when a segment names no declared sub-option.
     */
    fun resolve(option: OptionSpec, path: List<String>): Nested? {
        var current = option
        val names = ArrayList<String>(path.size)
        for (segment in path) {
            if (current.type == OptionType.List && segment.toIntOrNull() != null) continue
            val options = current.options ?: return null
            val next = options[segment] ?: options.values.firstOrNull { segment in it.aliases } ?: return null
            names += next.name
            current = next
        }
        return Nested(current, names)
    }

    /** The key of the option [names] below [binding]'s option in its spec file, or null. */
    fun keyOf(project: Project, binding: SpecBinding, names: List<String>): YAMLKeyValue? {
        if (names.isEmpty()) return VarLocations.keyValueAt(project, binding.location)
        val yaml = YamlFiles.yamlFile(project, binding.location.file) ?: return null
        val top = YamlPaths.topLevelValue(yaml) ?: return null
        val path = listOf("argument_specs", binding.entryPoint, "options", binding.option.name) + names.flatMap { listOf("options", it) }
        return YamlPaths.find(top, path) as? YAMLKeyValue
    }

    /** Where the option [names] below [binding]'s option is written (the top-level option's key when not found). */
    fun locationOf(project: Project, binding: SpecBinding, names: List<String>): SourceLocation {
        val key = keyOf(project, binding, names)?.key ?: return binding.location
        return SourceLocation(binding.location.file, key.textRange.startOffset)
    }
}
