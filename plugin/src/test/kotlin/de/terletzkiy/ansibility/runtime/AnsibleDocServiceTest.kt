package de.terletzkiy.ansibility.runtime

import de.terletzkiy.ansibility.api.AnsibleDocService
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.DocAnchor
import de.terletzkiy.ansibility.api.DocKind
import de.terletzkiy.ansibility.api.DocSourceKind
import de.terletzkiy.ansibility.api.KeywordLevel
import de.terletzkiy.ansibility.semantics.CoreVersion
import de.terletzkiy.ansibility.semantics.schema.DocSnapshot
import de.terletzkiy.ansibility.semantics.schema.PluginKind
import de.terletzkiy.ansibility.semantics.schema.TemplateMode

/** Routing, source selection, keywords, plugins and URLs of [AnsibleDocServiceImpl] against the bundled snapshots. */
class AnsibleDocServiceTest : RuntimeTestCase() {
    private val service: AnsibleDocService get() = AnsibleDocService.getInstance(project)
    private lateinit var root: AnsibleRoot

    override fun setUp() {
        super.setUp()
        root = createRoot()
        target(root, CoreVersion.PINNED)
    }

    fun testServiceIsRegistered() {
        assertInstanceOf(service, AnsibleDocServiceImpl::class.java)
    }

    fun testRedirectedModuleWithDeprecation() {
        // golden/roles/percona/tasks/database.yml:3 → "redirects to ansible.mysql.mysql_user (deprecated, removal 6.0.0)"
        val doc = service.moduleDoc(root, "community.mysql.mysql_user")!!
        assertEquals("community.mysql.mysql_user", doc.requested)
        assertEquals("ansible.mysql.mysql_user", doc.canonical)
        assertEquals(listOf("community.mysql.mysql_user", "ansible.mysql.mysql_user"), doc.redirectChain)
        assertTrue(doc.isRedirected)
        assertEquals("6.0.0", doc.routingDeprecations.single().notice.removalVersion)
        assertEquals("community.mysql.mysql_user", doc.routingDeprecations.single().name)
        assertTrue(doc.isDeprecated)
        assertNull(doc.tombstone)
        assertEquals("the doc is the canonical module's, named as requested", "community.mysql.mysql_user", doc.doc!!.fqcn)
        assertEquals("ansible.mysql.mysql_user", doc.doc!!.canonicalFqcn)
        assertEquals("https://docs.ansible.com/ansible/11/collections/ansible/mysql/mysql_user_module.html", doc.docsUrl)
        val source = doc.source!!
        assertEquals(DocSourceKind.BUNDLED, source.kind)
        assertEquals("ansible-core 2.18.8 bundled + pinned collections", source.label)
        assertEquals(CoreVersion.PINNED, source.core)
        assertTrue(source.matchesTarget)
        assertEquals("5.2.0", source.collections["ansible.mysql"])
    }

    fun testAliasedModuleUsesTheCanonicalPage() {
        val doc = service.moduleDoc(root, "ansible.builtin.systemd")!!
        assertEquals("ansible.builtin.systemd_service", doc.canonical)
        assertTrue(doc.route.isAlias)
        assertNotNull(doc.doc)
        assertEquals("https://docs.ansible.com/ansible/11/collections/ansible/builtin/systemd_service_module.html", doc.docsUrl)
        assertEquals(doc.docsUrl, service.docsUrl(root, DocKind.MODULE, "ansible.builtin.systemd"))
        assertEquals(doc.docsUrl, service.docsUrl(root, DocKind.MODULE, "systemd"))
    }

    fun testShortAndLegacyNamesAreBuiltin() {
        assertEquals("ansible.builtin.copy", service.moduleDoc(root, "copy")!!.canonical)
        assertEquals("ansible.builtin.copy", service.moduleDoc(root, "ansible.legacy.copy")!!.canonical)
        assertFalse(service.moduleDoc(root, "copy")!!.isDeprecated)
    }

    fun testTombstonesAndUnknownModules() {
        val include = service.moduleDoc(root, "ansible.builtin.include")!!
        assertNull(include.doc)
        assertNull(include.source)
        assertEquals("2023-05-16", include.tombstone!!.notice.removalDate)
        assertNull("a module nobody knows gets nothing", service.moduleDoc(root, "acme.custom.thing"))
        assertNull(service.moduleDoc(root, "  "))
    }

    fun testDeprecationDependsOnTheTarget() {
        // golden/roles/haproxy/tasks/apt.yml:20: silent at 2.18.8, deprecated (removal 2.25) at >= 2.21
        assertNull(service.moduleDoc(root, "ansible.builtin.apt_repository")!!.deprecation)
        target(root, CoreVersion(2, 21, 4))
        val latest = service.moduleDoc(root, "ansible.builtin.apt_repository")!!
        assertEquals("2.25", latest.deprecation!!.removedIn)
        assertEquals("ansible.builtin.deb822_repository", latest.deprecation!!.alternative)
        assertEquals("ansible-core 2.21.4 bundled + latest collections", latest.source!!.label)
        assertTrue(latest.source!!.matchesTarget)
        assertEquals("https://docs.ansible.com/ansible/14/collections/ansible/builtin/apt_repository_module.html", latest.docsUrl)
    }

    fun testSnapshotSelectionByTarget() {
        target(root, CoreVersion(2, 19, 3))
        val nineteen = service.moduleDoc(root, "ansible.builtin.copy")!!.source!!
        assertEquals(CoreVersion(2, 21, 4), nineteen.core)
        assertFalse("2.21 docs for a 2.19 target do not match", nineteen.matchesTarget)
        assertEquals("https://docs.ansible.com/ansible/12/collections/ansible/builtin/copy_module.html", service.docsUrl(root, DocKind.MODULE, "copy"))

        target(root, CoreVersion(2, 18, 0))
        assertEquals(CoreVersion.PINNED, service.primarySource(root).core)
        assertTrue(service.primarySource(root).matchesTarget)

        target(root, null)
        val unknown = service.primarySource(root)
        assertEquals("unknown target → pinned", CoreVersion.PINNED, unknown.core)
        assertFalse(unknown.matchesTarget)
        assertEquals("https://docs.ansible.com/ansible/latest/collections/ansible/builtin/copy_module.html", service.docsUrl(root, DocKind.MODULE, "copy"))
    }

    fun testOtherLineFillsGaps() {
        // community.general.appimage exists only in the latest line's collections
        val doc = service.moduleDoc(root, "community.general.appimage")!!
        assertNotNull(doc.doc)
        assertEquals(DocSourceKind.BUNDLED, doc.source!!.kind)
        assertEquals(CoreVersion(2, 21, 4), doc.source!!.core)
        assertFalse(doc.source!!.matchesTarget)
    }

    fun testKeywords() {
        val whenDoc = service.keywordDoc(root, "when")!!
        assertEquals(TemplateMode.IMPLICIT, whenDoc.template)
        assertEquals(TemplateMode.IMPLICIT, service.keywordDoc(root, "changed_when")!!.template)
        assertEquals(TemplateMode.EXPLICIT, service.keywordDoc(root, "loop")!!.template)
        assertNotNull(service.keywordDoc(root, "when", KeywordLevel.TASK))
        assertNotNull(service.keywordDoc(root, "hosts", KeywordLevel.PLAY))
        assertNull("hosts is a play keyword", service.keywordDoc(root, "hosts", KeywordLevel.TASK))
        assertNotNull(service.keywordDoc(root, "loop_var", KeywordLevel.LOOP_CONTROL))
        assertNull(service.keywordDoc(root, "loop_var", KeywordLevel.TASK))
        assertNotNull(service.keywordDoc(root, "listen", KeywordLevel.HANDLER))
        assertNull(service.keywordDoc(root, "listen", KeywordLevel.TASK))
        assertNotNull("import_playbook entries sit at play level", service.keywordDoc(root, "import_playbook", KeywordLevel.PLAY))
        assertEquals(DocSnapshot.WITH_LOOKUP, service.keywordDoc(root, "with_items", KeywordLevel.TASK)!!.name)
        assertNull("unknown keyword", service.keywordDoc(root, "become_usr"))
        assertTrue(service.keywords(root).any { it.name == "when" })

        assertNull("validate_argspec arrived in 2.21", service.keywordDoc(root, "validate_argspec"))
        target(root, CoreVersion(2, 21, 4))
        assertNotNull(service.keywordDoc(root, "validate_argspec", KeywordLevel.PLAY))
    }

    fun testFiltersTestsAndLookups() {
        val toJson = service.filterDoc(root, "to_json")!!
        assertEquals("ansible.builtin.to_json", toJson.canonical)
        assertEquals(PluginKind.FILTER, toJson.kind)
        assertEquals("https://docs.ansible.com/ansible/11/collections/ansible/builtin/to_json_filter.html", toJson.docsUrl)
        val default = service.filterDoc(root, "default")!!
        assertTrue(default.doc!!.jinjaBuiltin)
        assertEquals("https://jinja.palletsprojects.com/en/stable/templates/#jinja-filters.default", default.docsUrl)
        val jsonQuery = service.filterDoc(root, "json_query")!!
        assertEquals("community.general.json_query", jsonQuery.canonical)
        assertTrue(jsonQuery.route.isRedirected)
        assertEquals("https://jinja.palletsprojects.com/en/stable/templates/#jinja-tests.defined", service.testDoc(root, "defined")!!.docsUrl)
        assertEquals("https://docs.ansible.com/ansible/11/collections/ansible/builtin/file_lookup.html", service.lookupDoc(root, "file")!!.docsUrl)
        assertNull(service.filterDoc(root, "no_such_filter_anywhere"))
    }

    fun testUrlsWithAnchors() {
        assertEquals(
            "https://docs.ansible.com/ansible/11/collections/community/docker/docker_container_module.html#parameter-healthcheck/interval",
            service.docsUrl(root, DocKind.MODULE, "community.docker.docker_container", DocAnchor.Parameter(listOf("healthcheck", "interval"))),
        )
        assertEquals(
            "https://docs.ansible.com/ansible/11/collections/ansible/builtin/stat_module.html#return-stat/exists",
            service.docsUrl(root, DocKind.MODULE, "ansible.builtin.stat", DocAnchor.ReturnValue(listOf("stat", "exists"))),
        )
        assertEquals(
            "https://docs.ansible.com/ansible/11/reference_appendices/playbooks_keywords.html#task",
            service.docsUrl(root, DocKind.KEYWORD, "when", DocAnchor.KeywordSection(KeywordLevel.TASK)),
        )
        assertEquals(
            "without a level, the first class the keyword applies to",
            "https://docs.ansible.com/ansible/11/reference_appendices/playbooks_keywords.html#play",
            service.docsUrl(root, DocKind.KEYWORD, "hosts"),
        )
        assertEquals(
            "https://docs.ansible.com/ansible/11/collections/community/general/json_query_filter.html#parameter-_input",
            service.docsUrl(root, DocKind.FILTER, "json_query", DocAnchor.Parameter(listOf("_input"))),
        )
        assertEquals(
            "https://docs.ansible.com/ansible/11/collections/ansible/builtin/file_lookup.html",
            service.docsUrl(root, DocKind.LOOKUP, "ansible.builtin.file"),
        )
    }

    fun testDocsBaseIsConfigurable() {
        // The settings bind the hook as soon as a project opens; without it the system property decides.
        options.docsBaseOverride = null
        System.setProperty(DocsBase.PROPERTY, "latest")
        assertEquals("https://docs.ansible.com/ansible/latest/collections/ansible/builtin/copy_module.html", service.docsUrl(root, DocKind.MODULE, "copy"))
        System.setProperty(DocsBase.PROPERTY, "https://mirror.example/ansible")
        assertEquals("https://mirror.example/ansible/collections/ansible/builtin/copy_module.html", service.moduleDoc(root, "copy")!!.docsUrl)
        options.docsBaseOverride = DocsBase.TargetVersioned
        assertEquals("the settings hook beats the property", "https://docs.ansible.com/ansible/11/collections/ansible/builtin/copy_module.html", service.moduleDoc(root, "copy")!!.docsUrl)
    }

    fun testAllModulesForCompletion() {
        val names = service.allModules(root)
        assertTrue("ansible.builtin.copy" in names)
        assertTrue("alias", "ansible.builtin.systemd" in names)
        assertTrue("collection redirect", "community.mysql.mysql_user" in names)
        assertTrue("ansible.mysql.mysql_user" in names)
        assertFalse("ansible.builtin's 2.9 migration table is not offered", "ansible.builtin.a10_server" in names)
        assertFalse("latest-line-only modules are not offered for a 2.18 target", "community.general.appimage" in names)
    }

    fun testDocsTrackerFollowsOptions() {
        val before = service.docsTracker.modificationCount
        options.docsBaseOverride = DocsBase.Latest
        assertTrue(service.docsTracker.modificationCount > before)
    }

    fun testNoProcessIsQueuedWhenTheRefreshIsOff() {
        options.localDocRefresh = false
        assertNull(service.moduleDoc(root, "acme.custom.thing"))
        assertNull(LocalDocRefresher.getInstance(project).localDocs(root))
    }
}
