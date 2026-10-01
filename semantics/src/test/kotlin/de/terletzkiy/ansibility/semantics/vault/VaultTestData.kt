package de.terletzkiy.ansibility.semantics.vault

import de.terletzkiy.ansibility.semantics.json.Json
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.nodes.MappingNode
import org.yaml.snakeyaml.nodes.Node
import org.yaml.snakeyaml.nodes.ScalarNode
import org.yaml.snakeyaml.nodes.SequenceNode
import java.io.StringReader

/**
 * The synthetic vectors and oracle tables written by `tools/vault/vectors.sh` into `src/test/resources/vault/`.
 * Every password is listed in `tools/vault/SYNTHETIC.md`; nothing here touches a real vault.
 */
internal object VaultTestData {
    /** The ansible-core versions the oracle tables come from. */
    val VERSIONS = listOf("2.18", "2.21")

    /** The probe tree root that `{T}` in the config tables stands for. */
    const val TREE = "/T"

    fun bytes(path: String): ByteArray =
        checkNotNull(VaultTestData::class.java.getResourceAsStream("/vault/$path")) { "missing test resource vault/$path" }
            .use { it.readBytes() }

    fun text(path: String): String = String(bytes(path), Charsets.UTF_8)

    @Suppress("UNCHECKED_CAST")
    fun json(path: String): Map<String, Any?> = Json.parseObject(text(path))

    val index: Map<String, Any?> by lazy { json("index.json") }

    /** id → synthetic password (SYNTHETIC.md's table, as the generator read it). */
    val passwords: Map<String, Password> by lazy {
        index.obj("passwords").mapValues { (_, v) ->
            @Suppress("UNCHECKED_CAST")
            val m = v as Map<String, Any?>
            Password(m.str("label"), unhex(m.str("hex")), unhex(m.str("sourceHex")), m.str("source"))
        }
    }

    val vectors: List<Vector> by lazy {
        index.list("vectors").map { Vector(json("vectors/${(it as Map<*, *>)["id"]}.json")) }
    }

    fun vector(id: String): Vector = vectors.single { it.id == id }

    fun secret(passwordId: String): SecretBytes = SecretBytes.of(passwords.getValue(passwordId).bytes)

    fun labelled(label: String, passwordId: String): LabelledSecret = LabelledSecret(label, secret(passwordId))

    fun tolerance(version: String): Map<String, Any?> = json("tol-$version.json")

    fun config(version: String): Map<String, Any?> = json("config-$version.json")

    /** Parses [text], which must be a well-formed envelope. */
    fun envelope(text: CharSequence): VaultEnvelope =
        (VaultEnvelope.parse(text) as? EnvelopeParse.Ok ?: error("not a well-formed envelope: ${VaultEnvelope.parse(text)}")).envelope

    /**
     * The value of the `!vault` scalar at key `x` (or the first item of `l`), as YAML 1.1 hands it to Ansible's
     * vault reader (SnakeYAML, like PyYAML: indentation removed, chomping applied, line breaks normalised).
     */
    fun vaultScalar(yaml: String, key: String = "x"): String? {
        val root = Yaml(LoaderOptions()).compose(StringReader(yaml)) as? MappingNode ?: return null
        val node = valueOf(root, key) ?: (valueOf(root, "l") as? SequenceNode)?.value?.firstOrNull()
        return (node as? ScalarNode)?.value
    }

    /** Every `key: !vault` scalar of a YAML mapping document. */
    fun vaultScalars(yaml: String): Map<String, String> {
        val root = Yaml(LoaderOptions()).compose(StringReader(yaml)) as MappingNode
        return root.value.mapNotNull { tuple ->
            val value = tuple.valueNode as? ScalarNode ?: return@mapNotNull null
            if (value.tag.value != "!vault") return@mapNotNull null
            (tuple.keyNode as ScalarNode).value to value.value
        }.toMap()
    }

    private fun valueOf(map: MappingNode, key: String): Node? =
        map.value.firstOrNull { (it.keyNode as? ScalarNode)?.value == key }?.valueNode

    class Password(val label: String, val bytes: ByteArray, val sourceBytes: ByteArray, val source: String)

    class Vector(private val json: Map<String, Any?>) {
        val id: String = json.str("id")
        val kind: String = json.str("kind")
        val passwordId: String = json.str("passwordId")
        val envelope: String = json.str("envelope")
        val header: String = json.str("header")
        val label: String? = json["label"] as String?
        val labelAsParsedByAnsible: String = json.str("labelAsParsedByAnsible")
        val formatVersion: String = json.str("formatVersion")
        val yamlKey: String? = json["yamlKey"] as String?
        val rawFile: String = json.str("rawFile")
        val sourceFileText: String? = json["sourceFileText"] as String?
        val plaintext: ByteArray = unhex(json.str("plaintextHex"))
        val plaintextUtf8: String? = json["plaintextUtf8"] as String?
        val salt: ByteArray = unhex(json.str("saltHex"))
        val hmac: ByteArray = unhex(json.str("hmacHex"))
        val ciphertext: ByteArray = unhex(json.str("ciphertextHex"))
        val passwordHex: String = json.str("passwordHex")
        val passwordSourceHex: String = json.str("passwordSourceHex")
        val encryptSalt: String? = json["encryptSalt"] as String?
        val derived: Map<String, Any?> = json.obj("derived")

        override fun toString(): String = id
    }
}

@Suppress("UNCHECKED_CAST")
internal fun Map<String, Any?>.obj(key: String): Map<String, Any?> = this[key] as Map<String, Any?>

internal fun Map<String, Any?>.list(key: String): List<Any?> = this[key] as List<Any?>

@Suppress("UNCHECKED_CAST")
internal fun Map<String, Any?>.rows(key: String): List<Map<String, Any?>> = this[key] as List<Map<String, Any?>>

internal fun Map<String, Any?>.str(key: String): String = checkNotNull(this[key] as String?) { "no string $key in $this" }

internal fun Map<String, Any?>.strOrNull(key: String): String? = this[key] as String?

@Suppress("UNCHECKED_CAST")
internal fun Map<String, Any?>.strings(key: String): List<String> = (this[key] as List<Any?>).map { it as String }

internal fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

internal fun unhex(text: String): ByteArray = ByteArray(text.length / 2) { text.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

/** Replaces the oracle's tree placeholder `{T}` with [VaultTestData.TREE]. */
internal fun String.inTree(): String = replace("{T}", VaultTestData.TREE)
