package de.terletzkiy.ansibility.index

import de.terletzkiy.ansibility.semantics.yaml.ScalarStyle
import de.terletzkiy.ansibility.semantics.yaml.SourceRange
import de.terletzkiy.ansibility.semantics.yaml.YEntry
import de.terletzkiy.ansibility.semantics.yaml.YMap
import de.terletzkiy.ansibility.semantics.yaml.YScalar
import de.terletzkiy.ansibility.semantics.yaml.YSeq
import de.terletzkiy.ansibility.semantics.yaml.YValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JinjaBearingTest {
    private fun bearing(path: String, keyPath: List<String>, tag: String? = null) =
        JinjaBearing.isJinjaBearingScalar(PathFacts.of(path), keyPath, tag)

    @Test
    fun `ordinary Ansible content is Jinja-bearing`() {
        assertTrue(bearing("golden/roles/haproxy/tasks/configure.yml", listOf("0", "ansible.builtin.file", "path")))
        assertTrue(bearing("golden/roles/haproxy/defaults/main.yml", listOf("haproxy_backports_version_debian", "bookworm")))
        assertTrue(bearing("repos/falcon/ansible/environments/prod/group_vars/all/vars.yml", listOf("alloy_tenant_api_key")))
        assertTrue("vault files hold templated values too", bearing("repos/falcon/ansible/group_vars/all/vault.yml", listOf("a")))
        assertTrue(bearing("repos/falcon/ansible/environments/prod/hosts.yml", listOf("app_mono", "vars", "x")))
        assertTrue(bearing("repos/falcon/ansible/playbook-setup-system.yml", listOf("0", "name")))
    }

    @Test
    fun `argument specs are never Jinja`() {
        assertFalse(bearing("golden/roles/haproxy/meta/argument_specs.yml", listOf("argument_specs", "main", "options", "haproxy_backports_version", "description")))
        assertFalse(bearing("golden/roles/haproxy/meta/argument_specs.yml", listOf("argument_specs", "main", "options", "x", "default")))
        assertFalse(bearing("golden/roles/haproxy/meta/main.yml", listOf("argument_specs", "main", "options", "x", "description")))
        assertFalse(bearing("golden/roles/haproxy/meta/main.yml", listOf("galaxy_info", "description")))
        assertTrue("role dependency params are templated", bearing("golden/roles/haproxy/meta/main.yml", listOf("dependencies", "0", "vars", "x")))
    }

    @Test
    fun `vault and unsafe scalars are never Jinja`() {
        assertFalse(bearing("repos/falcon/ansible/group_vars/all/vault.yml", listOf("vault_x"), "!vault"))
        assertFalse(bearing("golden/roles/x/defaults/main.yml", listOf("x"), "!unsafe"))
        assertTrue(bearing("golden/roles/x/defaults/main.yml", listOf("x"), "!!str"))
    }

    @Test
    fun `molecule config is Jinja only in its inventory`() {
        val config = "golden/roles/haproxy/molecule/default/molecule.yml"
        assertFalse("platform names use \${MOLECULE_RUN_ID:-local}", bearing(config, listOf("platforms", "0", "name")))
        assertFalse(bearing(config, listOf("provisioner", "env", "X")))
        assertFalse(bearing(config, listOf("provisioner", "inventory")))
        assertTrue(bearing(config, listOf("provisioner", "inventory", "group_vars", "all", "x")))
        assertTrue(bearing(config, listOf("provisioner", "inventory", "host_vars", "h", "x")))
    }

    @Test
    fun `files, templates and tool configuration are never Jinja-bearing scalars`() {
        assertFalse(bearing("golden/roles/x/files/data.yml", listOf("a")))
        assertFalse(bearing("golden/roles/x/templates/compose.yml", listOf("a")))
        assertFalse(bearing("repos/falcon/ansible/requirements.yml", listOf("collections", "0", "name")))
        assertFalse(bearing("repos/falcon/ansible/ansible-lint.yml", listOf("skip_list", "0")))
        assertFalse(bearing("repos/falcon/ansible/docker-compose.yaml", listOf("services", "a", "image")))
    }

    @Test
    fun `implicit expressions are the condition values of tasks, blocks, role entries and imports`() {
        var offset = 0
        fun scalar(text: String, style: ScalarStyle = ScalarStyle.PLAIN) = YScalar(text, style, range = SourceRange(offset, offset + text.length).also { offset += 100 })
        fun map(vararg entries: Pair<String, YValue>) = YMap(entries.map { (k, v) -> YEntry(YScalar(k, ScalarStyle.PLAIN), v) })
        val whenTask = scalar("x is defined")
        val listItem = scalar("y > 0")
        val boolean = scalar("false")
        val that = scalar("z | length > 0")
        val blockWhen = scalar("b")
        val nestedUntil = scalar("done", ScalarStyle.DOUBLE_QUOTED)
        val name = scalar("not an expression")
        val document = YSeq(
            listOf(
                map("name" to name, "ansible.builtin.command" to scalar("echo"), "when" to whenTask, "changed_when" to boolean,
                    "failed_when" to YSeq(listOf(listItem))),
                map("ansible.builtin.assert" to map("that" to YSeq(listOf(that)))),
                map("when" to blockWhen, "block" to YSeq(listOf(map("ansible.builtin.command" to scalar("echo"), "until" to nestedUntil)))),
            ),
        )
        val tasks = PathFacts.of("golden/roles/x/tasks/main.yml")
        assertEquals(
            listOf(whenTask, listItem, that, blockWhen, nestedUntil).map { it.range!!.start }.toSet(),
            JinjaBearing.implicitExpressionOffsets(document, tasks),
        )
        assertTrue(JinjaBearing.implicitExpressionOffsets(map("when" to whenTask), tasks).isEmpty())
        assertTrue(JinjaBearing.implicitExpressionOffsets(document, PathFacts.of("golden/roles/x/files/tasks.yml")).isEmpty())
    }
}
