package de.terletzkiy.ansibility.docs

import de.terletzkiy.ansibility.fixtures.RequiresInfraFixture

/** F5.3 option hover (M2 acceptance 8: `dest:` at configure.yml:5; `mounts` sub-options of docker_container). */
@RequiresInfraFixture
class OptionDocumentationTest : DocsTestCase() {

    override fun setUp() {
        super.setUp()
        copyInfra(HAPROXY)
        copyDocsData()
    }

    private fun optionTarget(path: String, offset: Int): OptionDocumentationTarget =
        docTarget(path, offset) as? OptionDocumentationTarget ?: error("not an option target at $path:$offset")

    fun testDestAtConfigureLine5() {
        // golden/roles/haproxy/tasks/configure.yml:5
        val target = optionTarget(CONFIGURE, at(CONFIGURE, 5, "dest", 2))
        val data = documentation(target)
        val text = plain(data.html)
        assertTrue(text, text.startsWith("dest : path · required"))
        assertTrue(text, text.contains("Location to render the template to on the remote machine."))
        assertTrue(text, text.contains("Module: ansible.builtin.template"))
        assertTrue(text, text.endsWith(PINNED_SOURCE))
        assertEquals("$TEMPLATE_URL#parameter-dest", externalUrl(data))
        assertEquals("dest : path · required — Location to render the template to on the remote machine.", plain(hint(target)!!))
    }

    fun testMountsSubOptionsTree() {
        val target = optionTarget(DEMO_TASKS, offsetOf(DEMO_TASKS, "mounts:"))
        val data = documentation(target)
        val text = plain(data.html)
        assertTrue(text, text.startsWith("mounts : list / elements=dict"))
        assertTrue(text, text.contains("Sub-options (18):"))
        for (name in listOf("consistency", "read_only", "source", "target", "type", "volume_options")) {
            assertTrue(name, data.html.contains("href=\"psi_element://ansibility-option/community.docker.docker_container/mounts/$name\""))
        }
        assertEquals("${DOCS_11}collections/community/docker/docker_container_module.html#parameter-mounts", externalUrl(data))
    }

    fun testNestedOptionWithChoices() {
        val target = optionTarget(DEMO_TASKS, offsetOf(DEMO_TASKS, "- type: bind", 2))
        assertEquals(listOf("mounts", "type"), target.path)
        val data = documentation(target)
        val text = plain(data.html)
        assertTrue(text, text.startsWith("mounts.type : str"))
        assertTrue(text, text.contains("Default: \"volume\""))
        assertTrue(text, text.contains("Choices: \"bind\" · \"npipe\" · \"tmpfs\" · \"volume\" (default) ·"))
        assertEquals("${DOCS_11}collections/community/docker/docker_container_module.html#parameter-mounts/type", externalUrl(data))
    }

    fun testDescribedChoicesTable() {
        val target = optionTarget(DEMO_TASKS, offsetOf(DEMO_TASKS, "follow_redirects:"))
        val html = documentation(target).html
        assertTrue(html, html.contains("<table><tr><td valign='top'><code>&quot;all&quot;</code></td><td valign='top'><p>Will follow all redirects.</p></td></tr>"))
        assertTrue(plain(html), plain(html).contains("\"safe\" (default)"))
    }

    fun testUndocumentedDictKeysHaveNoDocs() {
        assertEmpty(docTargets(DEMO_TASKS, offsetOf(DEMO_TASKS, "FOO: bar")))
        assertNotNull("the documented parent does", docTargets(DEMO_TASKS, offsetOf(DEMO_TASKS, "env:")).singleOrNull())
    }

    fun testAliasesResolveToTheDocumentedOption() {
        val root = root(DEMO_TASKS)
        val target = inBackgroundReadAction { OptionDocumentationTarget.create(project, root, "ansible.builtin.file", listOf("dest")) }!!
        val data = documentation(target)
        val text = plain(data.html)
        assertTrue(text, text.startsWith("path : path · required"))
        assertTrue(text, text.contains("Aliases: dest · name"))
        assertEquals("${DOCS_11}collections/ansible/builtin/file_module.html#parameter-path", externalUrl(data))
    }

    fun testFreeFormPseudoOption() {
        val root = root(DEMO_TASKS)
        val target = inBackgroundReadAction { OptionDocumentationTarget.create(project, root, "ansible.builtin.command", listOf("free_form")) }!!
        val text = plain(documentation(target).html)
        assertTrue(text, text.contains("Free-form pseudo-option: ansible.builtin.command takes this value as the string written directly after the module name"))
        assertTrue(text, text.contains("There is no actual parameter named free_form."))
    }

    fun testArgsAndKeyValueOptions() {
        val path = optionTarget(DEMO_TASKS, offsetOf(DEMO_TASKS, "path: \"/srv"))
        assertTrue(plain(documentation(path).html).startsWith("path : path · required"))
        val dest = optionTarget(DEMO_TASKS, offsetOf(DEMO_TASKS, "dest=/tmp", 1))
        assertEquals("ansible.builtin.copy", dest.fqcn)
        assertTrue(plain(documentation(dest).html).startsWith("dest : path · required"))
    }

    fun testRedirectedModuleOptions() {
        copyInfra(PERCONA)
        val target = optionTarget(PERCONA_DATABASE, at(PERCONA_DATABASE, 8, "login_unix_socket"))
        val data = documentation(target)
        assertTrue(plain(data.html), plain(data.html).startsWith("login_unix_socket : str"))
        assertEquals("${DOCS_11}collections/ansible/mysql/mysql_user_module.html#parameter-login_unix_socket", externalUrl(data))
    }
}
