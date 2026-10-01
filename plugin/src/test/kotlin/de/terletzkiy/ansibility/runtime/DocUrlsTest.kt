package de.terletzkiy.ansibility.runtime

import de.terletzkiy.ansibility.api.DocAnchor
import de.terletzkiy.ansibility.api.KeywordLevel
import de.terletzkiy.ansibility.semantics.CoreVersion
import de.terletzkiy.ansibility.semantics.schema.PluginKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DocUrlsTest {
    @Test
    fun packageMajorsFollowTheCoreLine() {
        // research/ecosystem.md: core 2.18 ↔ package 11, 2.21 ↔ 14; TargetVersionDetector maps pins the same way
        assertEquals(11, DocUrls.packageMajor(CoreVersion(2, 18, 8)))
        assertEquals(12, DocUrls.packageMajor(CoreVersion(2, 19, 3)))
        assertEquals(13, DocUrls.packageMajor(CoreVersion(2, 20, 0)))
        assertEquals(14, DocUrls.packageMajor(CoreVersion(2, 21, 4)))
        assertEquals(3, DocUrls.packageMajor(CoreVersion(2, 10)))
        assertNull("before Ansible 3 there was no split package", DocUrls.packageMajor(CoreVersion(2, 9, 27)))
    }

    @Test
    fun baseFollowsThePolicy() {
        assertEquals("https://docs.ansible.com/ansible/11/", DocUrls.base(DocsBase.TargetVersioned, CoreVersion(2, 18, 8)))
        assertEquals("https://docs.ansible.com/ansible/14/", DocUrls.base(DocsBase.TargetVersioned, CoreVersion(2, 21, 4)))
        assertEquals("unknown target → latest", "https://docs.ansible.com/ansible/latest/", DocUrls.base(DocsBase.TargetVersioned, null))
        assertEquals("https://docs.ansible.com/ansible/latest/", DocUrls.base(DocsBase.TargetVersioned, CoreVersion(2, 9)))
        assertEquals("https://docs.ansible.com/ansible/latest/", DocUrls.base(DocsBase.Latest, CoreVersion(2, 18, 8)))
        assertEquals("https://mirror.example/ansible/", DocUrls.base(DocsBase.Custom("https://mirror.example/ansible/"), CoreVersion(2, 18, 8)))
    }

    @Test
    fun parsesTheBaseSetting() {
        assertEquals(DocsBase.TargetVersioned, DocsBase.parse(null))
        assertEquals(DocsBase.TargetVersioned, DocsBase.parse(" "))
        assertEquals(DocsBase.TargetVersioned, DocsBase.parse("target"))
        assertEquals(DocsBase.Latest, DocsBase.parse("latest"))
        assertEquals(DocsBase.Custom("https://docs.ansible.com/ansible/12/"), DocsBase.parse("12"))
        assertEquals(DocsBase.Custom("https://mirror.example/docs/"), DocsBase.parse("https://mirror.example/docs"))
        assertEquals("garbage falls back to the default", DocsBase.TargetVersioned, DocsBase.parse("ftp://x"))
    }

    @Test
    fun modulePagesUseTheCanonicalName() {
        val base = "https://docs.ansible.com/ansible/11/"
        assertEquals("${base}collections/ansible/builtin/systemd_service_module.html", DocUrls.module(base, "ansible.builtin.systemd_service"))
        assertEquals("${base}collections/community/docker/docker_container_module.html", DocUrls.module(base, "community.docker.docker_container"))
        assertEquals(
            "only the first two dots separate (ansible-core's fqcn.replace('.', '/', 2))",
            "${base}collections/community/general/system.foo_module.html",
            DocUrls.module(base, "community.general.system.foo"),
        )
    }

    @Test
    fun anchorsJoinSubOptionsWithSlashes() {
        val base = "https://docs.ansible.com/ansible/11/"
        assertEquals(
            "${base}collections/ansible/builtin/template_module.html#parameter-dest",
            DocUrls.module(base, "ansible.builtin.template", DocAnchor.Parameter(listOf("dest"))),
        )
        assertEquals(
            "${base}collections/community/docker/docker_container_module.html#parameter-healthcheck/interval",
            DocUrls.module(base, "community.docker.docker_container", DocAnchor.Parameter(listOf("healthcheck", "interval"))),
        )
        assertEquals(
            "${base}collections/ansible/builtin/stat_module.html#return-stat/exists",
            DocUrls.module(base, "ansible.builtin.stat", DocAnchor.ReturnValue(listOf("stat", "exists"))),
        )
        assertEquals("", DocUrls.fragment(DocAnchor.Parameter(emptyList())))
    }

    @Test
    fun keywordPageSections() {
        val base = "https://docs.ansible.com/ansible/11/"
        assertEquals("${base}reference_appendices/playbooks_keywords.html#task", DocUrls.keywords(base, DocUrls.section(KeywordLevel.TASK)))
        assertEquals("#play", DocUrls.fragment(DocAnchor.KeywordSection(KeywordLevel.PLAY)))
        assertEquals("#role", DocUrls.fragment(DocAnchor.KeywordSection(KeywordLevel.ROLE_ENTRY)))
        assertEquals("#block", DocUrls.fragment(DocAnchor.KeywordSection(KeywordLevel.BLOCK)))
        assertEquals("handlers are documented with tasks", "#task", DocUrls.fragment(DocAnchor.KeywordSection(KeywordLevel.HANDLER)))
        assertEquals("#task", DocUrls.fragment(DocAnchor.KeywordSection(KeywordLevel.LOOP_CONTROL)))
        assertEquals("play", DocUrls.section("PlaybookInclude"))
        assertNull(DocUrls.section("Something"))
        assertEquals("${base}reference_appendices/playbooks_keywords.html", DocUrls.keywords(base, null))
    }

    @Test
    fun pluginPages() {
        val base = "https://docs.ansible.com/ansible/latest/"
        assertEquals("${base}collections/ansible/builtin/to_json_filter.html", DocUrls.plugin(base, PluginKind.FILTER, "ansible.builtin.to_json"))
        assertEquals("${base}collections/ansible/builtin/file_lookup.html", DocUrls.plugin(base, PluginKind.LOOKUP, "ansible.builtin.file"))
        assertEquals(
            "${base}collections/ansible/builtin/version_test.html#parameter-strict",
            DocUrls.plugin(base, PluginKind.TEST, "ansible.builtin.version", DocAnchor.Parameter(listOf("strict"))),
        )
        assertEquals("https://jinja.palletsprojects.com/en/stable/templates/#jinja-filters.default", DocUrls.jinjaBuiltin(PluginKind.FILTER, "ansible.builtin.default"))
        assertEquals("https://jinja.palletsprojects.com/en/stable/templates/#jinja-tests.defined", DocUrls.jinjaBuiltin(PluginKind.TEST, "ansible.builtin.defined"))
    }
}
