package de.terletzkiy.ansibility.vars

import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.terletzkiy.ansibility.vault.VaultVectors
import de.terletzkiy.ansibility.yaml.YamlPsi
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLScalar

/**
 * [VaultInfo] on the codec (V2b) against the hand-written header parser it replaced: the same answers for every value
 * Ansible accepts or rejects by its body, and the documented differences where the old parser was more lenient than
 * Ansible (it trimmed lines and skipped blank leading lines).
 */
class VaultInfoParityTest : BasePlatformTestCase() {
    private val lines = VaultVectors.encrypt("value", VaultVectors.PW1).formatLines()
    private val payload = lines.drop(1)

    /** The replaced implementation, kept here as the parity oracle. */
    private object Legacy {
        fun isVaultValue(keyValue: YAMLKeyValue): Boolean {
            val value = keyValue.value ?: return false
            return YamlPsi.tagOf(value) == "!vault" || YamlPsi.tagOf(keyValue) == "!vault"
        }

        fun headerOf(keyValue: YAMLKeyValue): VaultInfo.Header? {
            if (!isVaultValue(keyValue)) return null
            val text = (keyValue.value as? YAMLScalar)?.textValue ?: return null
            val first = text.lineSequence().map(String::trim).firstOrNull { it.isNotEmpty() } ?: return null
            return parse(first)
        }

        fun parse(line: String): VaultInfo.Header? {
            val parts = line.trim().split(';')
            if (parts.size < 3 || parts[0] != "\$ANSIBLE_VAULT") return null
            val version = parts[1].trim().takeIf { it.isNotEmpty() } ?: return null
            val cipher = parts[2].trim().takeIf { it.isNotEmpty() } ?: return null
            return VaultInfo.Header(version, cipher, parts.getOrNull(3)?.trim()?.takeIf { it.isNotEmpty() })
        }
    }

    private fun keyValues(text: String): Map<String, YAMLKeyValue> {
        val file = myFixture.configureByText("vault.yml", text) as YAMLFile
        return PsiTreeUtil.findChildrenOfType(file, YAMLKeyValue::class.java).associateBy { it.keyText }
    }

    private fun block(key: String, header: String, body: List<String> = payload, indicator: String = "|"): String =
        "$key: !vault $indicator\n  $header\n" + body.joinToString("") { "  $it\n" }

    fun testSameAnswersForValuesWithAHeaderLine() {
        val text = buildString {
            append(block("v11", "\$ANSIBLE_VAULT;1.1;AES256"))
            append(block("v12", "\$ANSIBLE_VAULT;1.2;AES256;prod"))
            append(block("label_on_11", "\$ANSIBLE_VAULT;1.1;AES256;dev"))
            append(block("empty_label", "\$ANSIBLE_VAULT;1.2;AES256;"))
            append(block("bad_cipher", "\$ANSIBLE_VAULT;1.1;aes256"))
            append(block("bad_body", "\$ANSIBLE_VAULT;1.1;AES256", listOf("zz")))
            append(block("two_fields", "\$ANSIBLE_VAULT;1.1"))
            append(block("strip", "\$ANSIBLE_VAULT;1.1;AES256", indicator = "|-"))
            append("plain: not a vault\n")
            append("untagged: |\n  \$ANSIBLE_VAULT;1.1;AES256\n  6162\n")
            append("empty: !vault\n")
        }
        val values = keyValues(text)
        for ((key, keyValue) in values) {
            assertEquals(key, Legacy.isVaultValue(keyValue), VaultInfo.isVaultValue(keyValue))
            assertEquals(key, Legacy.headerOf(keyValue), VaultInfo.headerOf(keyValue))
        }
        assertEquals("AES256, 1.1", VaultInfo.headerOf(values.getValue("v11"))!!.summary)
        assertEquals("AES256, 1.2, prod", VaultInfo.headerOf(values.getValue("v12"))!!.summary)
        assertEquals("a malformed body keeps its header", "AES256, 1.1", VaultInfo.headerOf(values.getValue("bad_body"))!!.summary)
        assertNull(VaultInfo.headerOf(values.getValue("two_fields")))
        assertNull(VaultInfo.headerOf(values.getValue("plain")))
    }

    fun testParseOfHeaderLinesMatchesTheLegacyParser() {
        val samples = listOf(
            "\$ANSIBLE_VAULT;1.1;AES256", "\$ANSIBLE_VAULT;1.2;AES256;prod", "\$ANSIBLE_VAULT;1.2;AES256;", "\$ANSIBLE_VAULT;1.1",
            "\$ANSIBLE_VAULT;9.9;AES256", "\$ANSIBLE_VAULT;1.1;aes256", "not a vault", "\$ANSIBLE_VAULT;1.1;AES256\n" + payload.joinToString("\n"),
        )
        for (sample in samples) assertEquals(sample, Legacy.parse(sample.lineSequence().first()), VaultInfo.parse(sample))
    }

    fun testIntendedDifferencesFollowAnsible() {
        val values = keyValues(
            buildString {
                append("blank_first: !vault |\n\n  \$ANSIBLE_VAULT;1.1;AES256\n").append(payload.joinToString("") { "  $it\n" })
                append("leading_space: !vault \" \$ANSIBLE_VAULT;1.1;AES256\"\n")
                append("legacy_tag: !vault-encrypted |\n  \$ANSIBLE_VAULT;1.1;AES256\n").append(payload.joinToString("") { "  $it\n" })
            },
        )
        // Ansible refuses a value that does not start with the magic: no header to show.
        assertNotNull(Legacy.headerOf(values.getValue("leading_space")))
        assertNull(VaultInfo.headerOf(values.getValue("leading_space")))
        assertNull(VaultInfo.headerOf(values.getValue("blank_first")))
        // `!vault-encrypted` is Ansible's deprecated spelling of the same tag.
        assertFalse(Legacy.isVaultValue(values.getValue("legacy_tag")))
        assertTrue(VaultInfo.isVaultValue(values.getValue("legacy_tag")))
        assertEquals("AES256, 1.1", VaultInfo.headerOf(values.getValue("legacy_tag"))!!.summary)
    }
}
