package de.terletzkiy.ansibility.vars

import com.intellij.openapi.application.runReadActionBlocking
import de.terletzkiy.ansibility.api.AnsibleSite
import de.terletzkiy.ansibility.api.FileKind
import de.terletzkiy.ansibility.api.SiteClassifier
import de.terletzkiy.ansibility.context.host.symbols.HostSymbolClassifier

/** [VarsSiteClassifier]: variable references and structurally decided variable keys; null for everything else. */
class VarsSiteClassifierTest : VarsTestCase() {
    private val classifier = VarsSiteClassifier()

    override fun setUp() {
        super.setUp()
        copyVarsData("site")
    }

    private fun site(path: String, line: Int, marker: String, delta: Int = 1): AnsibleSite? {
        val offset = offsetAt(path, line, marker, delta)
        return runReadActionBlocking { classifier.classify(psi(path), offset) }
    }

    private fun key(path: String, line: Int, marker: String, delta: Int = 1): AnsibleSite.VarKey =
        site(path, line, marker, delta) as? AnsibleSite.VarKey ?: error("no VarKey at $path:$line '$marker': ${site(path, line, marker, delta)}")

    fun testIsRegisteredFirst() {
        // The host-symbol classifier (F8.8) precedes it; it answers only on host and group names.
        val classifiers = SiteClassifier.EP_NAME.extensionList
        assertTrue(classifiers[0] is HostSymbolClassifier)
        assertTrue(classifiers[1] is VarsSiteClassifier)
    }

    fun testReferencesComeFromTheLocator() {
        val ref = site(TASKS, 4, "web_nested", 2) as AnsibleSite.VarRef
        assertEquals("web_nested", ref.name)
        assertNull("filters are left to the docs track", site(TASKS, 9, "int"))
    }

    fun testVarsFileKeys() {
        val defaults = key(DEFAULTS, 3, "web_port")
        assertEquals(listOf("web_port"), defaults.keyPath)
        assertEquals(FileKind.ROLE_DEFAULTS, defaults.kind)
        assertEquals(listOf("web_nested", "inner"), key(DEFAULTS, 5, "inner").keyPath)
        assertEquals(FileKind.ROLE_VARS, key("site/roles/web/vars/main.yml", 2, "web_internal").kind)

        val groupVars = key(GROUP_VARS, 2, "web_port")
        assertEquals(listOf("web_port"), groupVars.keyPath)
        assertEquals(FileKind.GROUP_VARS, groupVars.kind)
        assertEquals(listOf("web_nested", "inner"), key(GROUP_VARS, 4, "inner").keyPath)
        assertTrue("a value is not a key", site(GROUP_VARS, 2, "8080") !is AnsibleSite.VarKey)
    }

    fun testArgumentSpecOptionsOnly() {
        val option = key(SPECS, 5, "web_port")
        assertEquals(listOf("web_port"), option.keyPath)
        assertEquals(FileKind.ROLE_ARGSPEC, option.kind)
        assertEquals(listOf("web_nested", "inner"), key(SPECS, 11, "inner").keyPath)
        assertEquals("an option literally named type", listOf("web_nested", "type"), key(SPECS, 15, "type").keyPath)
        assertNull("type of an option", site(SPECS, 6, "type"))
        assertNull("description of an option", site(SPECS, 7, "description"))
        assertNull("the options mapping", site(SPECS, 4, "options"))
        assertNull("the entry point", site(SPECS, 3, "main"))
        assertNull("the top-level key", site(SPECS, 2, "argument_specs"))
    }

    fun testInventoryVars() {
        assertEquals(listOf("inv_group_var"), key(HOSTS, 4, "inv_group_var").keyPath)
        assertEquals(FileKind.INVENTORY, key(HOSTS, 4, "inv_group_var").kind)
        assertEquals("a host's own key", listOf("ansible_host"), key(HOSTS, 7, "ansible_host").keyPath)
        assertEquals("children nest", listOf("child_var"), key(HOSTS, 11, "child_var").keyPath)
        assertNull("a group", site(HOSTS, 2, "web"))
        assertNull("a host", site(HOSTS, 6, "web1"))
        assertNull("the hosts key", site(HOSTS, 5, "hosts"))
        assertNull("the children key", site(HOSTS, 8, "children"))
    }

    fun testMoleculeInventoryVars() {
        val mol = key(MOLECULE, 9, "mol_var")
        assertEquals(listOf("mol_var"), mol.keyPath)
        assertEquals(FileKind.MOLECULE_CONFIG, mol.kind)
        assertNull("driver settings", site(MOLECULE, 3, "name"))
        assertNull("the group owner", site(MOLECULE, 8, "all"))
    }

    fun testTaskAndPlayVars() {
        assertEquals(listOf("task_var"), key(TASKS, 19, "task_var").keyPath)
        assertEquals(FileKind.ROLE_TASKS, key(TASKS, 19, "task_var").kind)
        assertEquals(listOf("task_var", "nested_task"), key(TASKS, 20, "nested_task").keyPath)
        assertEquals(listOf("block_var"), key(TASKS, 22, "block_var").keyPath)
        assertEquals("include_role vars", listOf("include_var"), key(TASKS, 27, "include_var").keyPath)

        assertEquals(listOf("play_var"), key(PLAYBOOK, 5, "play_var").keyPath)
        assertEquals("role parameters", listOf("web_param"), key(PLAYBOOK, 8, "web_param").keyPath)
        assertEquals("set_fact keys", listOf("fact_one"), key(PLAYBOOK, 13, "fact_one").keyPath)
        assertNull("set_fact's cacheable", site(PLAYBOOK, 14, "cacheable"))
    }

    fun testStructuralKeysAreLeftToOtherClassifiers() {
        assertNull("task keyword", site(TASKS, 2, "name"))
        assertNull("module name", site(TASKS, 3, "ansible.builtin.stat", 3))
        assertNull("module option", site(TASKS, 4, "path"))
        assertNull("register keyword", site(TASKS, 5, "register"))
        assertNull("when keyword", site(TASKS, 9, "when"))
        assertNull("the vars keyword itself", site(TASKS, 18, "vars"))
        assertNull("include_role options", site(TASKS, 25, "name"))
        assertNull("play keyword", site(PLAYBOOK, 3, "hosts"))
        assertNull("role entry keyword", site(PLAYBOOK, 7, "role"))
        assertNull("role entry tags", site(PLAYBOOK, 9, "tags"))
        assertNull("set_fact module name", site(PLAYBOOK, 12, "ansible.builtin.set_fact", 3))
    }

    fun testOutsideRootsNothingIsClassified() {
        createFile("outside/group_vars/all.yml", "web_port: 1\n")
        assertNull(site("outside/group_vars/all.yml", 1, "web_port"))
    }

    companion object {
        const val TASKS = "site/roles/web/tasks/main.yml"
        const val DEFAULTS = "site/roles/web/defaults/main.yml"
        const val SPECS = "site/roles/web/meta/argument_specs.yml"
        const val GROUP_VARS = "site/environments/dev/group_vars/all/vars.yml"
        const val HOSTS = "site/environments/dev/hosts.yml"
        const val MOLECULE = "site/roles/web/molecule/default/molecule.yml"
        const val PLAYBOOK = "site/playbook.yml"
    }
}
