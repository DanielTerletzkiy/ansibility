package de.terletzkiy.ansibility.model.container

import de.terletzkiy.ansibility.semantics.yaml.YMap
import de.terletzkiy.ansibility.semantics.yaml.YScalar
import de.terletzkiy.ansibility.semantics.yaml.YSeq
import de.terletzkiy.ansibility.semantics.yaml.YValue

/**
 * Reads the bind mounts of a Compose file: `services.<name>.volumes`, in the short syntax
 * (`./golden/roles:/ansible/roles[:mode]`) and the long syntax (`{type: bind, source: ./x, target: /ansible}`).
 * Anonymous volumes (a container path alone) and non-absolute targets are skipped.
 */
object ComposeVolumes {
    /** One mount: [host] as written, [container] normalised (absolute, no trailing slash). */
    data class Volume(val host: String, val container: String)

    /** One service: its bind mounts and its `environment` entries (values as written, null for a bare name). */
    data class Service(val name: String, val volumes: List<Volume>, val environment: Map<String, String?>)

    fun parse(document: YValue?): List<Volume> = services(document).flatMap { it.volumes }

    fun services(document: YValue?): List<Service> {
        val services = (document as? YMap)?.get("services") as? YMap ?: return emptyList()
        return services.entries.map { (name, value) ->
            val service = value as? YMap
            val volumes = (service?.get("volumes") as? YSeq)?.items?.mapNotNull(::volume).orEmpty()
            Service(name.text, volumes, environment(service?.get("environment")))
        }
    }

    private fun environment(value: YValue?): Map<String, String?> = when (value) {
        is YMap -> value.entries.associate { (key, item) -> key.text to (item as? YScalar)?.text }
        is YSeq -> value.items.mapNotNull { (it as? YScalar)?.text?.trim() }.filter { it.isNotEmpty() }.associate { entry ->
            val eq = entry.indexOf('=')
            if (eq < 0) entry to null else entry.substring(0, eq) to entry.substring(eq + 1)
        }
        else -> emptyMap()
    }

    private fun volume(item: YValue): Volume? = when (item) {
        is YScalar -> {
            val parts = splitShort(item.text.trim())
            if (parts.size < 2) null else mount(parts[0], parts[1])
        }
        is YMap -> {
            val type = (item["type"] as? YScalar)?.text
            val source = (item["source"] as? YScalar)?.text
            val target = (item["target"] as? YScalar)?.text
            if ((type == null || type == "bind") && source != null && target != null) mount(source, target) else null
        }
        else -> null
    }

    /** Splits a short-syntax volume on the colons outside `${…}` (`${A-${B}}:/x` has one separator). */
    private fun splitShort(text: String): List<String> {
        val parts = ArrayList<String>()
        val current = StringBuilder()
        var depth = 0
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '$' && i + 1 < text.length && text[i + 1] == '{' -> { depth++; current.append("\${"); i += 2; continue }
                c == '}' && depth > 0 -> depth--
                c == ':' && depth == 0 -> { parts += current.toString(); current.setLength(0); i++; continue }
            }
            current.append(c)
            i++
        }
        parts += current.toString()
        return parts
    }

    private fun mount(host: String, container: String): Volume? {
        val target = ContainerPathMapper.normalize(container.trim()) ?: return null
        val source = host.trim().takeIf { it.isNotEmpty() } ?: return null
        return Volume(source, target)
    }
}
