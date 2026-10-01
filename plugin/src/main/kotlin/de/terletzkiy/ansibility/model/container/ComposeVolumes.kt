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

    fun parse(document: YValue?): List<Volume> {
        val services = (document as? YMap)?.get("services") as? YMap ?: return emptyList()
        return services.entries.flatMap { service ->
            val volumes = (service.value as? YMap)?.get("volumes") as? YSeq ?: return@flatMap emptyList()
            volumes.items.mapNotNull(::volume)
        }
    }

    private fun volume(item: YValue): Volume? = when (item) {
        is YScalar -> {
            val parts = item.text.trim().split(':')
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

    private fun mount(host: String, container: String): Volume? {
        val target = ContainerPathMapper.normalize(container.trim()) ?: return null
        val source = host.trim().takeIf { it.isNotEmpty() } ?: return null
        return Volume(source, target)
    }
}
