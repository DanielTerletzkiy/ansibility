package de.terletzkiy.ansibility.fixtures

import de.terletzkiy.ansibility.semantics.vault.EnvelopeParse
import de.terletzkiy.ansibility.semantics.vault.SecretBytes
import de.terletzkiy.ansibility.semantics.vault.VaultAes256
import de.terletzkiy.ansibility.semantics.vault.VaultEnvelope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.ByteBuffer

/**
 * Guards the sanitised infra fixture (WU0.4) in CI, independently of `tools/fixtures/verify.py`.
 *
 * It re-implements the hard rules of DEV.md rule 2 and D15 on the files as they are on disk: every vault
 * envelope is the synthetic `1.1` fixture envelope (it parses with the codec and decrypts with the synthetic fixture
 * password of `tools/vault/SYNTHETIC.md` to the dummy), no `vault_*` key has a plaintext value, every IPv4 address is TEST-NET (or
 * loopback, `0.0.0.0`, a netmask), no forbidden file exists, and the synthetic git links that the
 * detached-root rule depends on are present. Problems are reported as `path:line` without the value.
 */
class FixtureSanityTest {
    private val root: Path = InfraTestData.root

    @Test
    fun fixtureContainsTheCuratedSubset() {
        assertTrue("fixture not found at $root; run python3 tools/fixtures/sync.py", Files.isDirectory(root))
        val missing = ANCHOR_FILES.filterNot { Files.isRegularFile(root.resolve(it)) }
        assertEquals("anchor files missing from the fixture", emptyList<String>(), missing)
        assertTrue("fixture looks truncated", files().size > 600)
    }

    @Test
    fun everyVaultEnvelopeIsTheSyntheticFixtureEnvelope() {
        val problems = mutableListOf<String>()
        var headers = 0
        var payloadLines = 0
        val password = SecretBytes.of(InfraTestData.FIXTURE_VAULT_PASSWORD.toByteArray(StandardCharsets.UTF_8))
        for ((rel, lines) in textFiles()) {
            var i = 0
            while (i < lines.size) {
                if (!lines[i].contains(VAULT_MARK)) {
                    i++
                    continue
                }
                val header = VAULT_HEADER.matchEntire(lines[i])
                if (header == null) {
                    problems += "$rel:${i + 1}: $VAULT_MARK outside a vault header line"
                    i++
                    continue
                }
                headers++
                val indent = header.groupValues[1]
                val envelope = StringBuilder(lines[i].substring(indent.length)).append('\n')
                var j = i + 1
                while (j < lines.size) {
                    val payload = HEX_LINE.matchEntire(lines[j]) ?: break
                    if (payload.groupValues[1] != indent) break
                    envelope.append(lines[j].substring(indent.length)).append('\n')
                    payloadLines++
                    j++
                }
                fixtureEnvelopeProblem(envelope, password)?.let { problems += "$rel:${i + 1}: $it" }
                i = j
            }
            lines.forEachIndexed { index, line ->
                if (VAULT_TAG.containsMatchIn(line)) {
                    val next = lines.drop(index + 1).firstOrNull { it.isNotBlank() }
                    if (next == null || !VAULT_HEADER.matches(next)) problems += "$rel:${index + 1}: !vault without header"
                }
            }
        }
        password.zero()
        assertEquals(emptyList<String>(), problems)
        assertTrue("expected vault blocks in the fixture (found $headers)", headers >= 100 && payloadLines > headers)
    }

    /** Why [text] (an envelope without indentation) is not the synthetic fixture envelope, or null when it is. */
    private fun fixtureEnvelopeProblem(text: CharSequence, password: SecretBytes): String? {
        if (!text.startsWith("\$ANSIBLE_VAULT;1.1;AES256\n")) return "vault header is not the synthetic 1.1 header"
        val envelope = (VaultEnvelope.parse(text) as? EnvelopeParse.Ok)?.envelope ?: return "vault envelope does not parse"
        if (envelope.label != null) return "vault envelope has a label"
        val plaintext = VaultAes256.decrypt(envelope, password) ?: return "vault payload is not the synthetic fixture envelope"
        val dummy = InfraTestData.FIXTURE_VAULT_PLAINTEXT.matches(String(plaintext, StandardCharsets.US_ASCII))
        plaintext.fill(0)
        return if (dummy) null else "vault payload does not decrypt to the fixture dummy"
    }

    @Test
    fun noVaultKeyHasAPlaintextValue() {
        val problems = mutableListOf<String>()
        var vaultKeys = 0
        for ((rel, lines) in textFiles()) {
            lines.forEachIndexed { index, line ->
                val m = VAULT_KEY.matchEntire(line) ?: return@forEachIndexed
                vaultKeys++
                val keyColumn = m.groupValues[1].length
                val value = m.groupValues[4].trim().removeSuffix(",").trim()
                when {
                    value.isEmpty() || value.startsWith("#") || value.startsWith("!vault") || value.startsWith("*") -> Unit
                    value in ALLOWED_VAULT_VALUES || PURE_TEMPLATE.matches(value) -> Unit
                    BLOCK_INDICATOR.matches(value) -> {
                        lines.drop(index + 1)
                            .takeWhile { it.isBlank() || indentOf(it) > keyColumn }
                            .forEachIndexed { offset, content ->
                                if (content.isNotBlank() && content.trim() != REDACTED) {
                                    problems += "$rel:${index + 2 + offset}: plaintext vault_* block line"
                                }
                            }
                    }
                    else -> problems += "$rel:${index + 1}: plaintext vault_* value"
                }
            }
        }
        assertEquals(emptyList<String>(), problems)
        assertTrue("expected vault_* keys in the fixture (found $vaultKeys)", vaultKeys >= 100)
    }

    @Test
    fun everyIpv4AddressIsTestNetOrSpecialPurpose() {
        val problems = mutableListOf<String>()
        var testNet = 0
        for ((rel, lines) in textFiles()) {
            IPV4.findAll(rel).forEach { if (!isAllowed(it)) problems += "$rel: IPv4 address outside TEST-NET in the path" }
            lines.forEachIndexed { index, line ->
                IPV4.findAll(line).forEach { m ->
                    if (!isAllowed(m)) problems += "$rel:${index + 1}: IPv4 address outside TEST-NET"
                    if (isTestNet(octets(m))) testNet++
                }
            }
        }
        assertEquals(emptyList<String>(), problems)
        assertTrue("expected mapped TEST-NET addresses in the fixture (found $testNet)", testNet >= 100)
    }

    @Test
    fun noForbiddenFiles() {
        val problems = files().mapNotNull { path ->
            val rel = relative(path)
            val segments = rel.split('/')
            val name = segments.last()
            when {
                ".git" in segments.dropLast(1) -> "$rel: inside a .git directory"
                name == ".git" -> null // checked by syntheticGitLinksArePresentAndOnlyThose
                name in FORBIDDEN_NAMES || name.startsWith(".env") -> "$rel: vault password or env file"
                FORBIDDEN_SUFFIXES.any { name.lowercase().endsWith(it) } -> "$rel: key, certificate or password file"
                segments.dropLast(1).zipWithNext().any { (a, b) -> a == "files" && (b == "ssl" || b == "ssh") } ->
                    "$rel: files/ssl or files/ssh subtree"
                else -> null
            }
        }
        assertEquals(emptyList<String>(), problems)
    }

    @Test
    fun syntheticGitLinksArePresentAndOnlyThose() {
        val hint = "(git cannot track files named .git: run python3 tools/fixtures/sync.py --links-only)"
        val expected = InfraTestData.SUBMODULES.associate { "repos/$it/.git" to InfraTestData.submoduleGitLink(it) } +
            ("${InfraTestData.WORKTREE_DIR}/.git" to InfraTestData.WORKTREE_GIT_LINK)
        for ((rel, content) in expected) {
            val file = root.resolve(rel)
            assertTrue("$rel missing $hint", Files.isRegularFile(file))
            assertEquals("$rel content", content, String(Files.readAllBytes(file), StandardCharsets.UTF_8))
        }
        val actual = files().map(::relative).filter { it == ".git" || it.endsWith("/.git") }.sorted()
        assertEquals(expected.keys.sorted(), actual)
        assertTrue(
            "the detached worktree needs its duplicate role",
            Files.isRegularFile(root.resolve("${InfraTestData.WORKTREE_DIR}/golden/roles/haproxy/tasks/main.yml")),
        )
    }

    /** Opt-in (set `ANSIBLE_INFRA_REPO`): sanitising kept the line count of every copied file. Reads the repo only. */
    @Test
    fun lineCountsMatchTheInfraRepo() {
        val repo = System.getenv(InfraTestData.INFRA_REPO_ENV)
        assumeTrue("set ${InfraTestData.INFRA_REPO_ENV} to compare against the infra repo", !repo.isNullOrBlank())
        val source = Paths.get(repo!!)
        val problems = mutableListOf<String>()
        var compared = 0
        for (path in files()) {
            val rel = relative(path)
            if (rel.endsWith("/.git") || rel.startsWith(InfraTestData.WORKTREE_DIR) || IPV4.containsMatchIn(rel)) continue
            val original = source.resolve(rel)
            if (!Files.isRegularFile(original)) {
                problems += "$rel: not in the infra repo"
                continue
            }
            compared++
            val expected = newlines(Files.readAllBytes(original))
            val actual = newlines(Files.readAllBytes(path))
            if (expected != actual) problems += "$rel: $expected line breaks in the repo, $actual in the fixture"
        }
        assertEquals(emptyList<String>(), problems)
        assertTrue(compared > 600)
    }

    private fun files(): List<Path> = Files.walk(root).use { stream ->
        stream.filter { Files.isRegularFile(it) && it.fileName.toString() != ".DS_Store" }.sorted().toList()
    }

    private fun relative(path: Path): String = root.relativize(path).joinToString("/")

    private fun textFiles(): List<Pair<String, List<String>>> = files().map { path ->
        val rel = relative(path)
        val text = try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(Files.readAllBytes(path)))
                .toString()
        } catch (e: CharacterCodingException) {
            throw AssertionError("$rel is not UTF-8 text; the fixture must only hold verifiable text files", e)
        }
        rel to text.lines()
    }

    private companion object {
        const val VAULT_MARK = "\$ANSIBLE_VAULT"
        const val REDACTED = "REDACTED"

        val ANCHOR_FILES = listOf(
            "golden/roles/haproxy/defaults/main.yml",
            "golden/roles/haproxy/molecule/default/molecule.yml",
            "golden/roles/jenkins-controller/meta/argument_specs.yml",
            "golden/playbooks/playbook-setup-keycloak.yml",
            "golden/docker/ansible-lint/Dockerfile",
            "repos/falcon/ansible/ansible.cfg",
            "repos/falcon/ansible/group_vars/all/vault.yml",
            "repos/falcon/ansible/environments/prod/hosts.yml",
            "repos/falcon/ansible/environments/prod/group_vars/keycloak/vars.yml",
            "repos/falcon/ansible/roles/jenkins-agent-docker/meta/main.yml",
            "repos/heron/ansible/environments/prod/group_vars/keycloak/vars.yml",
            "repos/platform/ansible/environments/prod/hosts.yml",
            "repos/platform/ansible/group_vars/all.yml",
            "repos/platform/ansible/group_vars/monitoring_client.yml",
            "repos/platform/ansible/roles/mysql-databases/molecule/default/verify.yml",
            "repos/pelican/ansible/danger_zone/database/playbook-initial-setup.yml",
            "repos/wren/ansible/roles/app-wren-mono/tasks/nginx.yml",
            "repos/thrush/ansible/roles/app-thrush-mono/templates/deployment/docker-compose.yml.j2",
            "${InfraTestData.WORKTREE_DIR}/golden/roles/haproxy/defaults/main.yml",
        )

        val VAULT_HEADER = Regex("""([ \t]*)\${'$'}ANSIBLE_VAULT;\d+\.\d+;AES256(?:;[^\s;]+)?[ \t]*""")
        val HEX_LINE = Regex("""([ \t]*)([0-9A-Fa-f]+)[ \t]*""")
        val VAULT_TAG = Regex("""(?:^|[\s:\-\[{,])!vault[ \t]+[|>][0-9+-]*[ \t]*(?:#.*)?$""")
        val VAULT_KEY = Regex("""([ \t]*(?:-[ \t]+)*)(["']?)(vault_\w*)\2[ \t]*:(?:[ \t]+(.*?))?[ \t]*""")
        val ALLOWED_VAULT_VALUES = setOf(REDACTED, "\"$REDACTED\"", "'$REDACTED'", "~", "null", "\"\"", "''")
        val PURE_TEMPLATE = Regex("""(["']?)\{\{.*\}\}\1""")
        val BLOCK_INDICATOR = Regex("""[|>][0-9+-]*(?:[ \t]+#.*)?""")
        val IPV4 = Regex("""(?<![\d.])(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})(?!\d|\.\d)""")

        val FORBIDDEN_NAMES = setOf(".vault-pass", ".vault_pass", ".vault-password", ".initial-root-pass")
        val FORBIDDEN_SUFFIXES = listOf(".key", ".pem", ".crt", ".password", ".p12", ".pfx", ".jks", ".keystore")

        fun indentOf(line: String): Int = line.length - line.trimStart(' ', '\t').length

        fun octets(m: MatchResult): List<Int> = m.groupValues.drop(1).map(String::toInt)

        fun isTestNet(o: List<Int>): Boolean =
            (o[0] == 192 && o[1] == 0 && o[2] == 2) || (o[0] == 198 && o[1] == 51 && o[2] == 100) ||
                (o[0] == 203 && o[1] == 0 && o[2] == 113)

        fun isAllowed(m: MatchResult): Boolean {
            val o = octets(m)
            if (o.any { it > 255 }) return true // not an address
            val value = o.fold(0L) { acc, b -> (acc shl 8) or b.toLong() }
            val inverted = value.inv() and 0xFFFFFFFFL
            val netmask = o[0] == 255 && (inverted and (inverted + 1)) == 0L
            return isTestNet(o) || o[0] == 127 || value == 0L || netmask
        }

        fun newlines(bytes: ByteArray): Int {
            var count = 0
            var i = 0
            while (i < bytes.size) {
                val b = bytes[i]
                if (b == '\n'.code.toByte()) {
                    count++
                } else if (b == '\r'.code.toByte()) {
                    count++
                    if (i + 1 < bytes.size && bytes[i + 1] == '\n'.code.toByte()) i++
                }
                i++
            }
            return count
        }
    }
}
