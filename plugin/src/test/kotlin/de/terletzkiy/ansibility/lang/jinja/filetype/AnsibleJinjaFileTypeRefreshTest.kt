package de.terletzkiy.ansibility.lang.jinja.filetype

import com.intellij.json.JsonFileType
import com.intellij.json.JsonLanguage
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.FileTypeEvent
import com.intellij.openapi.fileTypes.FileTypeListener
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.PlainTextLanguage
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.ManagingFS
import com.intellij.openapi.vfs.newvfs.NewVirtualFile
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.psi.FileViewProvider
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.FileContentUtilCore
import de.terletzkiy.ansibility.context.AnsibleLayout
import de.terletzkiy.ansibility.lang.jinja.AnsibleJinjaFileType
import de.terletzkiy.ansibility.lang.jinja.template.AnsibleJinjaFileViewProvider
import de.terletzkiy.ansibility.lang.jinja.template.AnsibleJinjaTemplateTestCase
import de.terletzkiy.ansibility.settings.OuterLanguageRule
import org.jetbrains.yaml.YAMLFileType
import org.jetbrains.yaml.YAMLLanguage
import java.nio.file.Files
import java.util.Collections

/**
 * An `ansible.cfg` that appears, disappears, moves or is renamed re-parses only the files whose type or outer language
 * depends on it ([AnsibleJinjaFileTypeRefresh]): `.j2` files and role templates below its directory, `*-playbook.y*ml`
 * files below it and compose playbooks next to it; so do a directory that carries one and a new role marker. A global
 * file types change (every file re-typed, the project re-indexed) is left to settings changes and to changes that
 * concern more files than the limit.
 *
 * The checks hold on to the view providers from before a change (a provider that was not re-parsed stays cached, so a
 * missing re-parse shows as a stale provider), read the file type index (which keeps the old type of a file that was
 * not re-parsed) or record the re-parse events themselves. Global changes are counted on [FileTypeManager.TOPIC].
 * A walk too large for the VFS write action runs in a non-blocking read action; `settle` waits for it too.
 */
class AnsibleJinjaFileTypeRefreshTest : AnsibleJinjaTemplateTestCase() {
    private var fileTypesChanges = 0

    override fun setUp() {
        super.setUp()
        ApplicationManager.getApplication().messageBus.connect(testRootDisposable).subscribe(
            FileTypeManager.TOPIC,
            object : FileTypeListener {
                override fun fileTypesChanged(event: FileTypeEvent) {
                    fileTypesChanges++
                }
            },
        )
    }

    fun testSettingsChangeIsAGlobalFileTypesChange() {
        val changes = fileTypesChanges
        withJinjaSettings({ copy(keepYamlForJ2 = true) }) {
            assertTrue("the signal the other tests rely on", fileTypesChanges > changes)
        }
    }

    fun testNewAnsibleCfgReparsesTheFilesBelowAndNextToIt() = withPlaybookPatternsAs(JsonFileType.INSTANCE) {
        createFile(TEMPLATE, "{{ motd }}\n")
        createFile(PLAYBOOK, "- hosts: all\n")
        createFile(COMPOSE, "services: {}\n")
        createFile("$ROOT/sub/ansible.cfg", "[defaults]\n")
        createFile(NESTED_TEMPLATE, "{{ motd }}\n")
        createFile(ELSEWHERE, "{{ motd }}\n")
        val before = providers(TEMPLATE, PLAYBOOK, COMPOSE, NESTED_TEMPLATE, ELSEWHERE)
        assertFalse(before.getValue(TEMPLATE) is AnsibleJinjaFileViewProvider)
        assertSame(JsonLanguage.INSTANCE, before.getValue(PLAYBOOK).baseLanguage)
        assertSame(JsonLanguage.INSTANCE, before.getValue(COMPOSE).baseLanguage)
        assertInstanceOf(before.getValue(NESTED_TEMPLATE), AnsibleJinjaFileViewProvider::class.java)
        val changes = fileTypesChanges

        createFile(CFG, "[defaults]\n")
        settle()

        assertSame(AnsibleJinjaFileType, vf(TEMPLATE).fileType)
        assertInstanceOf(viewProvider(TEMPLATE), AnsibleJinjaFileViewProvider::class.java)
        assertTrue("re-indexed", isIndexedAs(TEMPLATE, AnsibleJinjaFileType))
        assertSame("a playbook inside the new root", YAMLLanguage.INSTANCE, viewProvider(PLAYBOOK).baseLanguage)
        assertSame("a compose playbook next to the new root", YAMLLanguage.INSTANCE, viewProvider(COMPOSE).baseLanguage)
        assertTrue("re-indexed", isIndexedAs(COMPOSE, YAMLFileType.YML))
        assertSame("a nested root keeps its own base", before.getValue(NESTED_TEMPLATE), viewProvider(NESTED_TEMPLATE))
        assertSame("outside the new root", before.getValue(ELSEWHERE), viewProvider(ELSEWHERE))
        assertEquals("no global file types change", changes, fileTypesChanges)
    }

    fun testDeletedAnsibleCfgRevertsTheFiles() = withPlaybookPatternsAs(JsonFileType.INSTANCE) {
        val cfg = createFile(CFG, "[defaults]\n")
        createFile(TEMPLATE, "{{ motd }}\n")
        createFile(PLAYBOOK, "- hosts: all\n")
        createFile(COMPOSE, "services: {}\n")
        val before = providers(TEMPLATE, PLAYBOOK, COMPOSE)
        assertInstanceOf(before.getValue(TEMPLATE), AnsibleJinjaFileViewProvider::class.java)
        assertSame(YAMLLanguage.INSTANCE, before.getValue(PLAYBOOK).baseLanguage)
        assertSame(YAMLLanguage.INSTANCE, before.getValue(COMPOSE).baseLanguage)
        val changes = fileTypesChanges

        WriteAction.runAndWait<Throwable> { cfg.delete(this) }
        settle()

        assertNotSame(AnsibleJinjaFileType, vf(TEMPLATE).fileType)
        assertFalse(viewProvider(TEMPLATE) is AnsibleJinjaFileViewProvider)
        assertSame(JsonLanguage.INSTANCE, viewProvider(PLAYBOOK).baseLanguage)
        assertSame(JsonLanguage.INSTANCE, viewProvider(COMPOSE).baseLanguage)
        assertEquals("no global file types change", changes, fileTypesChanges)
    }

    /** The outer-language rules match the path relative to the nearest `ansible.cfg`, which a nested one changes. */
    fun testNestedAnsibleCfgChangesTheOuterLanguage() {
        val template = "site/web/conf/app.cfg.j2"
        createFile("site/ansible.cfg", "[defaults]\n")
        createFile(template, "listen: {{ port }}\n")
        withJinjaSettings({ copy(outerLanguageRules = listOf(OuterLanguageRule("conf/*", "yaml"))) }) {
            val before = viewProvider(template) as AnsibleJinjaFileViewProvider
            assertEquals("web/conf/app.cfg.j2", AnsibleTemplatePaths.relativePath(vf(template)))
            assertSame(PlainTextLanguage.INSTANCE, before.templateDataLanguage)
            val changes = fileTypesChanges

            createFile("site/web/ansible.cfg", "[defaults]\n")
            settle()

            assertEquals("conf/app.cfg.j2", AnsibleTemplatePaths.relativePath(vf(template)))
            val after = viewProvider(template) as AnsibleJinjaFileViewProvider
            assertNotSame("re-parsed", before, after)
            assertSame(YAMLLanguage.INSTANCE, after.templateDataLanguage)
            assertEquals("no global file types change", changes, fileTypesChanges)
        }
    }

    fun testMovedAndCopiedAnsibleCfg() {
        val cfg = createFile("staging/ansible.cfg", "[defaults]\n")
        val staging = "staging/files/motd.j2"
        val falcon = "repos/falcon/ansible/files/motd.j2"
        val tern = "repos/tern/ansible/files/motd.j2"
        listOf(staging, falcon, tern).forEach { createFile(it, "{{ motd }}\n") }
        val before = providers(staging, falcon, tern)
        assertInstanceOf(before.getValue(staging), AnsibleJinjaFileViewProvider::class.java)
        assertFalse(before.getValue(falcon) is AnsibleJinjaFileViewProvider)
        assertFalse(before.getValue(tern) is AnsibleJinjaFileViewProvider)
        val changes = fileTypesChanges

        WriteAction.runAndWait<Throwable> { cfg.move(this, vf("repos/falcon/ansible")) }
        settle()
        assertFalse("the old parent lost its root", viewProvider(staging) is AnsibleJinjaFileViewProvider)
        assertTrue("the new parent became a root", viewProvider(falcon) is AnsibleJinjaFileViewProvider)

        WriteAction.runAndWait<Throwable> { vf("repos/falcon/ansible/ansible.cfg").copy(this, vf("repos/tern/ansible"), "ansible.cfg") }
        settle()
        assertInstanceOf(viewProvider(tern), AnsibleJinjaFileViewProvider::class.java)
        assertEquals("no global file types change", changes, fileTypesChanges)
    }

    fun testRenamedToAndFromAnsibleCfg() {
        val cfg = createFile("site/ansible.cfg.off", "[defaults]\n")
        val template = "site/files/motd.j2"
        createFile(template, "{{ motd }}\n")
        val before = viewProvider(template)
        assertFalse(before is AnsibleJinjaFileViewProvider)
        val changes = fileTypesChanges

        WriteAction.runAndWait<Throwable> { cfg.rename(this, "ansible.cfg") }
        settle()
        val claimed = viewProvider(template)
        assertInstanceOf(claimed, AnsibleJinjaFileViewProvider::class.java)

        WriteAction.runAndWait<Throwable> { cfg.rename(this, "ansible.cfg.off") }
        settle()
        assertFalse(viewProvider(template) is AnsibleJinjaFileViewProvider)
        assertEquals("no global file types change", changes, fileTypesChanges)
    }

    /**
     * A directory event carries the `ansible.cfg` inside it, which gets no event of its own. The platform re-creates
     * the cached PSI of a file whose type changed after a directory event, but its index input stays stale until the
     * file is re-parsed.
     */
    fun testMovedOrDeletedRootDirectoryReparsesTheComposePlaybooksNextToIt() = withPlaybookPatternsAs(JsonFileType.INSTANCE) {
        createFile("staging/ansible/ansible.cfg", "[defaults]\n")
        val staging = "staging/docker-compose.ansible-playbook.yaml"
        val falcon = "repos/falcon/docker-compose.ansible-playbook.yaml"
        createFile(staging, "services: {}\n")
        createFile(falcon, "services: {}\n")
        settle()
        assertTrue(isIndexedAs(staging, YAMLFileType.YML))
        assertTrue(isIndexedAs(falcon, JsonFileType.INSTANCE))
        val changes = fileTypesChanges

        WriteAction.runAndWait<Throwable> { vf("staging/ansible").move(this, vf("repos/falcon")) }
        settle()
        assertTrue("no longer next to a root", isIndexedAs(staging, JsonFileType.INSTANCE))
        assertTrue("now next to a root", isIndexedAs(falcon, YAMLFileType.YML))
        assertSame(YAMLLanguage.INSTANCE, viewProvider(falcon).baseLanguage)

        WriteAction.runAndWait<Throwable> { vf("repos/falcon/ansible").delete(this) }
        settle()
        assertTrue("the root next to it is gone", isIndexedAs(falcon, JsonFileType.INSTANCE))
        assertSame(JsonLanguage.INSTANCE, viewProvider(falcon).baseLanguage)
        assertEquals("no global file types change", changes, fileTypesChanges)
    }

    /** A role marker re-parses every file of the role whose type depends on it: playbooks too, not only templates. */
    fun testNewRoleMarkerReparsesThePlaybooksOfTheRole() = withPlaybookPatternsAs(JsonFileType.INSTANCE) {
        val playbook = "lib/roles/web/playbooks/deploy-playbook.yml"
        createFile(playbook, "- hosts: all\n")
        settle()
        assertTrue(isIndexedAs(playbook, JsonFileType.INSTANCE))
        val changes = fileTypesChanges

        createFile("lib/roles/web/tasks/main.yml", "- ansible.builtin.debug:\n    msg: x\n")
        settle()
        assertTrue(isIndexedAs(playbook, YAMLFileType.YML))
        assertSame(YAMLLanguage.INSTANCE, viewProvider(playbook).baseLanguage)
        assertEquals("no global file types change", changes, fileTypesChanges)
    }

    /**
     * A file indexed before a restart need not be loaded in this session: the walk reads the children the VFS has
     * persisted (here listed without being loaded), not only those loaded in memory.
     */
    fun testWalkReadsPersistedChildren() {
        val (site, files) = persistedTemplateTree()
        val reparsed = recordReparsed()

        WriteAction.runAndWait<Throwable> { site.createChildData(this, AnsibleLayout.ANSIBLE_CFG) }
        settle()

        val template = files.findChild("motd.j2") ?: error("no motd.j2")
        assertTrue("re-parsed: $reparsed", template in reparsed)
    }

    /** A walk that visits more entries than the limit for the VFS write action is redone in a background read action. */
    fun testLargeWalkRunsAfterTheWriteAction() {
        lowerVisitsInWriteAction(0)
        val (site, files) = persistedTemplateTree()
        val reparsed = recordReparsed()
        val changes = fileTypesChanges

        WriteAction.runAndWait<Throwable> {
            site.createChildData(this, AnsibleLayout.ANSIBLE_CFG)
            assertEmpty("nothing loaded by a walk in the VFS write action", files.cachedChildren)
        }
        settle()

        val template = files.findChild("motd.j2") ?: error("no motd.j2")
        assertTrue("re-parsed: $reparsed", template in reparsed)
        assertEquals("no global file types change", changes, fileTypesChanges)
    }

    /**
     * Role templates without `.j2` depend on an `ansible.cfg` too: in a role library without one, their paths are
     * relative to the library; a new `ansible.cfg` above it makes them relative to its directory.
     */
    fun testNewAnsibleCfgChangesTheOuterLanguageOfRoleTemplates() {
        val template = "site/golden/roles/web/templates/conf/app.cfg"
        createFile("site/golden/roles/web/tasks/main.yml", "- ansible.builtin.debug:\n    msg: x\n")
        createFile(template, "listen: {{ port }}\n")
        withJinjaSettings({ copy(outerLanguageRules = listOf(OuterLanguageRule("golden/roles/*/templates/conf/*", "yaml"))) }) {
            val before = viewProvider(template) as AnsibleJinjaFileViewProvider
            assertEquals("roles/web/templates/conf/app.cfg", AnsibleTemplatePaths.relativePath(vf(template)))
            assertSame(PlainTextLanguage.INSTANCE, before.templateDataLanguage)
            val changes = fileTypesChanges

            createFile("site/ansible.cfg", "[defaults]\n")
            settle()

            val after = viewProvider(template) as AnsibleJinjaFileViewProvider
            assertNotSame("re-parsed", before, after)
            assertSame(YAMLLanguage.INSTANCE, after.templateDataLanguage)
            assertEquals("no global file types change", changes, fileTypesChanges)
        }
    }

    /** `build/` and `vendor/` may hold the user's own templates (unlike dependency and tool directories). */
    fun testNewAnsibleCfgReparsesTemplatesInBuildAndVendor() {
        val templates = listOf("$ROOT/build/nginx.conf.j2", "$ROOT/vendor/motd.j2")
        templates.forEach { createFile(it, "{{ motd }}\n") }
        assertTrue(templates.none { viewProvider(it) is AnsibleJinjaFileViewProvider })
        val changes = fileTypesChanges

        createFile(CFG, "[defaults]\n")
        settle()

        templates.forEach { assertInstanceOf(viewProvider(it), AnsibleJinjaFileViewProvider::class.java) }
        assertEquals("no global file types change", changes, fileTypesChanges)
    }

    /** A name the user mapped to Ansible Jinja2 takes its outer language from the path relative to the nearest root. */
    fun testUserMappedTemplateFollowsANestedAnsibleCfg() = withPatternAsAnsibleJinja("*.tmpl") {
        val template = "site/web/conf/app.tmpl"
        createFile("site/ansible.cfg", "[defaults]\n")
        createFile(template, "listen: {{ port }}\n")
        withJinjaSettings({ copy(outerLanguageRules = listOf(OuterLanguageRule("conf/*", "yaml"))) }) {
            val before = viewProvider(template) as AnsibleJinjaFileViewProvider
            assertSame(PlainTextLanguage.INSTANCE, before.templateDataLanguage)
            val changes = fileTypesChanges

            createFile("site/web/ansible.cfg", "[defaults]\n")
            settle()

            val after = viewProvider(template) as AnsibleJinjaFileViewProvider
            assertNotSame("re-parsed", before, after)
            assertSame(YAMLLanguage.INSTANCE, after.templateDataLanguage)
            assertEquals("no global file types change", changes, fileTypesChanges)
        }
    }

    /** A directory renamed into a role marker, or into `roles/`, re-parses the playbooks of the role. */
    fun testRenamedDirectoryReparsesThePlaybooksOfTheRole() = withPlaybookPatternsAs(JsonFileType.INSTANCE) {
        val marker = "lib/roles/web/playbooks/deploy-playbook.yml"
        val roles = "other/roles/db/playbooks/deploy-playbook.yml"
        createFile("lib/roles/web/task/main.yml", "- ansible.builtin.debug:\n    msg: x\n")
        createFile(marker, "- hosts: all\n")
        createFile("other/roles2/db/tasks/main.yml", "- ansible.builtin.debug:\n    msg: x\n")
        createFile("other/roles2/db/playbooks/deploy-playbook.yml", "- hosts: all\n")
        settle()
        assertTrue(isIndexedAs(marker, JsonFileType.INSTANCE))
        assertTrue(isIndexedAs("other/roles2/db/playbooks/deploy-playbook.yml", JsonFileType.INSTANCE))
        val changes = fileTypesChanges

        WriteAction.runAndWait<Throwable> {
            vf("lib/roles/web/task").rename(this, "tasks")
            vf("other/roles2").rename(this, "roles")
        }
        settle()

        assertTrue("a role marker by rename", isIndexedAs(marker, YAMLFileType.YML))
        assertSame(YAMLLanguage.INSTANCE, viewProvider(marker).baseLanguage)
        assertTrue("a roles directory by rename", isIndexedAs(roles, YAMLFileType.YML))
        assertSame(YAMLLanguage.INSTANCE, viewProvider(roles).baseLanguage)
        assertEquals("no global file types change", changes, fileTypesChanges)
    }

    fun testMoreFilesThanTheLimitReTypeEverything() {
        lowerReparseLimit(2)
        val small = listOf("small/files/a.j2", "small/files/b.j2")
        val large = listOf("large/files/a.j2", "large/files/b.j2", "large/files/c.j2")
        (small + large).forEach { createFile(it, "{{ motd }}\n") }
        val before = providers(*(small + large).toTypedArray())
        assertTrue(before.values.none { it is AnsibleJinjaFileViewProvider })
        val changes = fileTypesChanges

        createFile("small/ansible.cfg", "[defaults]\n")
        settle()
        assertEquals("at the limit the files are re-parsed", changes, fileTypesChanges)
        small.forEach { assertTrue(it, viewProvider(it) is AnsibleJinjaFileViewProvider) }

        createFile("large/ansible.cfg", "[defaults]\n")
        settle()
        assertTrue("over the limit everything is re-typed", fileTypesChanges > changes)
        large.forEach { assertTrue(it, viewProvider(it) is AnsibleJinjaFileViewProvider) }
    }

    /** The view providers of [paths]; holding them keeps a provider that was not re-parsed the same object. */
    private fun providers(vararg paths: String): Map<String, FileViewProvider> = paths.associateWith { viewProvider(it) }

    /** Whether the file type index holds [path] as [type] (it keeps the old type of a file that was not re-parsed). */
    private fun isIndexedAs(path: String, type: FileType): Boolean =
        FileTypeIndex.containsFileOfType(type, GlobalSearchScope.fileScope(project, vf(path)))

    /** The files [FileContentUtilCore.reparseFiles] re-parses from now on (it fires their `FORCE_RELOAD` events). */
    private fun recordReparsed(): List<VirtualFile> {
        val reparsed = Collections.synchronizedList(mutableListOf<VirtualFile>())
        ApplicationManager.getApplication().messageBus.connect(testRootDisposable).subscribe(
            VirtualFileManager.VFS_CHANGES,
            object : BulkFileListener {
                override fun after(events: List<VFileEvent>) {
                    events.filter { it.requestor == FileContentUtilCore.FORCE_RELOAD_REQUESTOR }.mapNotNullTo(reparsed) { it.file }
                }
            },
        )
        return reparsed
    }

    /**
     * A real directory `site/files/motd.j2` whose `site/` and `site/files/` children the VFS has persisted (as an earlier
     * session would have) without loading those of `site/files/` in memory; returns `site` and `site/files`.
     */
    private fun persistedTemplateTree(): Pair<VirtualFile, NewVirtualFile> {
        val temp = FileUtil.createTempDirectory("ansibility-refresh", null, true).toPath().toRealPath()
        Files.createDirectories(temp.resolve("site/files"))
        Files.writeString(temp.resolve("site/files/motd.j2"), "{{ motd }}\n")
        val site = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(temp.resolve("site")) ?: error("no site")
        val files = site.findChild("files") as? NewVirtualFile ?: error("no files")
        runReadActionBlocking { listOf(site, files).forEach { ManagingFS.getInstance().list(it) } }
        assertEmpty("persisted, not loaded", files.cachedChildren)
        return site to files
    }

    /** Runs [action] with [pattern] associated to Ansible Jinja2 (a user's File Types mapping). */
    private fun withPatternAsAnsibleJinja(pattern: String, action: () -> Unit) {
        val manager = FileTypeManager.getInstance()
        WriteAction.runAndWait<Throwable> { manager.associatePattern(AnsibleJinjaFileType, pattern) }
        try {
            action()
        } finally {
            WriteAction.runAndWait<Throwable> { manager.removeAssociation(AnsibleJinjaFileType, FileTypeManager.parseFromString(pattern)) }
        }
    }

    /** Sets the limit of entries a walk visits in the VFS write action to [limit] for this test. */
    private fun lowerVisitsInWriteAction(limit: Int) {
        AnsibleJinjaFileTypeRefresh.maxVisitsInWriteActionForTests = limit
        Disposer.register(testRootDisposable) { AnsibleJinjaFileTypeRefresh.maxVisitsInWriteActionForTests = null }
    }

    /** Sets the re-parse limit to [limit] for this test (a lambda in a test method would be taken for a test). */
    private fun lowerReparseLimit(limit: Int) {
        AnsibleJinjaFileTypeRefresh.maxReparseForTests = limit
        Disposer.register(testRootDisposable) { AnsibleJinjaFileTypeRefresh.maxReparseForTests = null }
    }

    /** Waits for the walk the VFS listener started, runs the re-parse it scheduled (`invokeLater`) and waits for the indexes. */
    private fun settle() = dispatchEvents()

    private companion object {
        const val ROOT = "repos/falcon/ansible"
        const val CFG = "$ROOT/ansible.cfg"
        const val TEMPLATE = "$ROOT/files/motd.j2"
        const val PLAYBOOK = "$ROOT/site-playbook.yml"
        const val COMPOSE = "repos/falcon/docker-compose.ansible-playbook.yaml"
        const val NESTED_TEMPLATE = "$ROOT/sub/files/motd.j2"
        const val ELSEWHERE = "web/files/motd.j2"
    }
}
