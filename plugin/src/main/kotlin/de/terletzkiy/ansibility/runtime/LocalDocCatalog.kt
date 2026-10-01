package de.terletzkiy.ansibility.runtime

import com.intellij.openapi.diagnostic.logger
import de.terletzkiy.ansibility.semantics.CoreVersion
import de.terletzkiy.ansibility.semantics.json.Json
import de.terletzkiy.ansibility.semantics.schema.DocSnapshot
import java.io.IOException
import java.io.OutputStreamWriter
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Module docs of the local Ansible install, in the bundled snapshot format, for one combination of ansible-core
 * and collection versions ([key]). It grows as more modules are refreshed; [missing] remembers the names
 * `ansible-doc` did not find, so they are not asked for again.
 */
class LocalDocCatalog(
    val key: String,
    val core: CoreVersion,
    val collections: Map<String, String>,
    /** Canonical FQCN → normalised module entry (snapshot format). */
    val modules: Map<String, Map<String, Any?>>,
    /** Requested name → canonical name, for documentation aliases found by `ansible-doc`. */
    val aliases: Map<String, String>,
    val missing: Set<String>,
) {
    /** The catalog as a [DocSnapshot]; its docs are labelled `local <core>`. */
    val snapshot: DocSnapshot by lazy { DocSnapshot.fromJson(toJson(), "local $core") }

    /** True when the catalog documents [fqcn] or knows `ansible-doc` does not. */
    fun knows(fqcn: String): Boolean = fqcn in modules || fqcn in aliases || fqcn in missing

    /** A catalog with [added] modules and aliases, and [notFound] names added to [missing]. */
    fun merge(added: AnsibleDocNormalizer.Modules, notFound: Collection<String>): LocalDocCatalog = LocalDocCatalog(
        key = key,
        core = core,
        collections = collections,
        modules = modules + added.modules,
        aliases = aliases + added.aliases,
        missing = (missing + notFound) - added.modules.keys - added.aliases.keys,
    )

    /** The snapshot-format JSON (plus `missing`) written to the disk cache. */
    fun toJson(): Map<String, Any?> = linkedMapOf(
        "format" to DocSnapshot.FORMAT,
        "core" to core.toString(),
        "label" to "local ansible-core $core",
        "collections" to collections.toSortedMap(),
        "modules" to modules.toSortedMap(),
        "routing" to mapOf("aliases" to mapOf("modules" to aliases.toSortedMap())),
        "missing" to missing.sorted(),
        "key" to key,
    )

    companion object {
        private val LOG = logger<LocalDocCatalog>()

        /** An empty catalog for an install. */
        fun empty(core: CoreVersion, collections: Map<String, String>): LocalDocCatalog =
            LocalDocCatalog(keyOf(core, collections), core, collections, emptyMap(), emptyMap(), emptySet())

        /** The cache key: a hash of the snapshot format, the core version and every collection version. */
        fun keyOf(core: CoreVersion, collections: Map<String, String>): String {
            val text = buildString {
                append("format=").append(DocSnapshot.FORMAT).append('\n')
                append("core=").append(core).append('\n')
                for ((name, version) in collections.toSortedMap()) append(name).append('=').append(version).append('\n')
            }
            val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            return digest.take(16).joinToString("") { "%02x".format(it) }
        }

        /** Reads a cache file written by [write]; null when it is missing, unreadable or from another format. */
        fun read(file: Path): LocalDocCatalog? {
            if (!Files.isRegularFile(file)) return null
            return try {
                val json = Files.newInputStream(file).use { stream ->
                    GZIPInputStream(stream).reader(Charsets.UTF_8).use(Json::parseObject)
                }
                fromJson(json)
            } catch (e: Exception) {
                LOG.info("Ignoring unreadable doc cache $file: ${e.message}")
                null
            }
        }

        /** Writes [catalog] to [file] (gzip), replacing it atomically. */
        fun write(file: Path, catalog: LocalDocCatalog) {
            Files.createDirectories(file.parent)
            val temp = Files.createTempFile(file.parent, file.fileName.toString(), ".tmp")
            try {
                OutputStreamWriter(GZIPOutputStream(Files.newOutputStream(temp)), Charsets.UTF_8).use { Json.write(catalog.toJson(), it) }
                try {
                    Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING)
                }
            } catch (e: IOException) {
                Files.deleteIfExists(temp)
                throw e
            }
        }

        @Suppress("UNCHECKED_CAST")
        private fun fromJson(json: Map<String, Any?>): LocalDocCatalog? {
            if ((json["format"] as? Number)?.toInt() != DocSnapshot.FORMAT) return null
            val core = (json["core"] as? String)?.let(CoreVersion::parse) ?: return null
            val collections = (json["collections"] as? Map<String, Any?>).orEmpty().mapValues { it.value.toString() }
            val modules = (json["modules"] as? Map<String, Any?>).orEmpty().mapValues { it.value as? Map<String, Any?> ?: emptyMap() }
            val routing = json["routing"] as? Map<String, Any?>
            val aliases = ((routing?.get("aliases") as? Map<String, Any?>)?.get("modules") as? Map<String, Any?>).orEmpty()
                .mapValues { it.value.toString() }
            val missing = (json["missing"] as? List<*>).orEmpty().mapNotNull { it as? String }.toSet()
            val key = json["key"] as? String ?: keyOf(core, collections)
            return LocalDocCatalog(key, core, collections, modules, aliases, missing)
        }
    }
}
