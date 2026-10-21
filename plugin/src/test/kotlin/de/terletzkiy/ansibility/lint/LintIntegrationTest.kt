package de.terletzkiy.ansibility.lint

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.context.AnsibleWorkspaceImpl
import de.terletzkiy.ansibility.settings.RootKeys
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

/**
 * ansible-lint end to end on disk with a fake executable (plan amendment R28): the command it gets, findings in the
 * editor after open, the Compose plan and the quick fixes.
 */
class LintIntegrationTest : BasePlatformTestCase() {
    private lateinit var base: Path
    private lateinit var args: Path
    private var contentRoot: VirtualFile? = null

    override fun setUp() {
        super.setUp()
        base = FileUtil.createTempDirectory("ansibility-lint", null, true).toPath().toRealPath()
        VfsRootAccess.allowRootAccess(testRootDisposable, base.toString())
        args = base.resolve("args.txt")
        write("repo/ansible/ansible.cfg", "[defaults]\n")
        write("repo/ansible/ansible-lint.yml", "---\nprofile: production\nskip_list:\n  - role-name\n")
        write("repo/ansible/roles/web/tasks/main.yml", "- name: install\n  shell: echo hi\n- command: ls\n")
        write("repo/ansible/site.yml", "- hosts: all\n  roles:\n    - web\n")
        val fake = write("bin/ansible-lint", FAKE)
        Files.setPosixFilePermissions(fake, PosixFilePermissions.fromString("rwxr-xr-x"))
        LintRunner.executableForTests = { fake }
        val dir = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(base)!!
        VfsUtil.markDirtyAndRefresh(false, true, true, dir)
        PsiTestUtil.addContentRoot(module, dir)
        contentRoot = dir
        AnsibleWorkspaceImpl.getInstance(project)!!.structureChanged()
    }

    override fun tearDown() {
        try {
            LintRunner.executableForTests = null
            LintRunner.dockerForTests = null
            FileDocumentManager.getInstance().saveAllDocuments()
            contentRoot?.let { PsiTestUtil.removeContentEntry(module, it) }
            AnsibleWorkspaceImpl.getInstance(project)?.structureChanged()
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    fun testTheLocalCommandRunsFromTheRootWithItsConfig() {
        val outcome = runBlocking { LintRunner.run(project, root(), listOf(vf("repo/ansible/roles/web/tasks/main.yml"))) }
        assertTrue(outcome.toString(), outcome is LintOutcome.Findings)
        val recorded = Files.readAllLines(args)
        assertEquals("cwd ${base.resolve("repo/ansible")}", recorded.first())
        assertEquals(listOf("-f", "sarif", "--nocolor", "--offline", "-c", "ansible-lint.yml", "roles/web/tasks/main.yml"), recorded.drop(1))
        val findings = (outcome as LintOutcome.Findings).findings
        assertEquals(listOf("name[casing]", "fqcn[action-core]"), findings.map { it.rule })
        assertEquals(base.resolve("repo/ansible/roles/web/tasks/main.yml"), findings.first().file)
    }

    fun testFindingsShowInTheEditorAfterOpening() {
        myFixture.openFileInEditor(vf("repo/ansible/roles/web/tasks/main.yml"))
        myFixture.doHighlighting()
        awaitLint()
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        val lint = myFixture.doHighlighting().filter { it.description?.startsWith("ansible-lint:") == true }
        assertEquals(lint.map(HighlightInfo::getDescription).toString(), 2, lint.size)
        val casing = lint.single { it.description.startsWith("ansible-lint: name[casing]") }
        assertEquals(HighlightSeverity.WARNING, casing.severity)
        assertEquals("- name: install", myFixture.editor.document.getText(com.intellij.openapi.util.TextRange(casing.startOffset, casing.endOffset)))
        myFixture.editor.caretModel.moveToOffset(casing.startOffset + 2)
        val fixes = myFixture.availableIntentions.map { it.text }
        assertTrue(fixes.toString(), "Suppress name[casing] on this line (# noqa)" in fixes)
        assertTrue(fixes.toString(), "Fix name[casing] with ansible-lint (also reformats the file)" in fixes)
        myFixture.launchAction(myFixture.findSingleIntention("Suppress name[casing] on this line (# noqa)"))
        assertEquals("- name: install  # noqa: name[casing]", myFixture.editor.document.text.lines().first())
    }

    fun testDisabledRootsAreNotLinted() {
        val key = RootKeys.keyOf(project, root().dir)
        LintSettings.getInstance(project).update(key, LintRootSettings(enabled = false))
        try {
            myFixture.openFileInEditor(vf("repo/ansible/roles/web/tasks/main.yml"))
            myFixture.doHighlighting()
            awaitLint()
            assertFalse(Files.exists(args))
        } finally {
            LintSettings.getInstance(project).update(key, LintRootSettings.DEFAULT)
        }
    }

    fun testTheComposePlanUsesTheLintServiceAndMapsPaths() {
        write("repo/docker-compose.ansible-lint.yaml", "services:\n  ansible-lint:\n    build: .\n    volumes:\n      - ./ansible:/ansible\n")
        val docker = write("bin/docker", "#!/bin/sh\n")
        LintRunner.dockerForTests = { docker }
        VfsUtil.markDirtyAndRefresh(false, true, true, contentRoot!!)
        AnsibleWorkspaceImpl.getInstance(project)!!.structureChanged()
        val file = vf("repo/ansible/roles/web/tasks/main.yml")
        val plan = com.intellij.openapi.application.runReadActionBlocking { LintRunner.plan(project, root(), listOf(file)) } as LintRunner.Plan
        assertEquals(
            listOf(docker.toString(), "compose", "-f", base.resolve("repo/docker-compose.ansible-lint.yaml").toString(), "run", "--rm", "-T", "-w", "/ansible",
                "--entrypoint", "ansible-lint", "ansible-lint", "-f", "sarif", "--nocolor", "--offline", "-c", "/ansible/ansible-lint.yml", "/ansible/roles/web/tasks/main.yml"),
            plan.command,
        )
        assertEquals(base.resolve("repo/ansible/roles/x.yml"), plan.resolve("/ansible/roles/x.yml"))
    }

    /** Waits for pending lint runs, dispatching EDT events (a run ends by restarting highlighting there). */
    private fun awaitLint() {
        val deadline = System.currentTimeMillis() + 60_000
        while (LintService.getInstance(project).busy()) {
            check(System.currentTimeMillis() < deadline) { "lint runs still pending" }
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            Thread.sleep(20)
        }
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    }

    private fun root() = com.intellij.openapi.application.runReadActionBlocking {
        AnsibleWorkspace.getInstance(project).roots().single { it.dir.name == "ansible" }
    }

    private fun write(relative: String, text: String): Path {
        val path = base.resolve(relative)
        Files.createDirectories(path.parent)
        Files.writeString(path, text)
        return path
    }

    private fun vf(relative: String): VirtualFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(base.resolve(relative))!!

    /** Records its working directory and arguments next to itself and prints two findings for the linted file. */
    private val FAKE: String
        get() = """
            #!/bin/sh
            out="${'$'}(dirname "${'$'}0")/../args.txt"
            echo "cwd ${'$'}PWD" > "${'$'}out"
            for a in "${'$'}@"; do echo "${'$'}a" >> "${'$'}out"; done
            for last; do :; done
            cat <<JSON
            {"version": "2.1.0", "runs": [{"tool": {"driver": {"name": "ansible-lint", "rules": [
             {"id": "name[casing]", "shortDescription": {"text": "All names should start with an uppercase letter."}, "helpUri": "https://ansible.readthedocs.io/projects/lint/rules/name/"},
             {"id": "fqcn[action-core]", "shortDescription": {"text": "Use FQCN for builtin module actions."}}]}},
             "results": [
              {"ruleId": "name[casing]", "level": "error", "message": {"text": "Task/Handler: install"}, "locations": [{"physicalLocation": {"artifactLocation": {"uri": "${'$'}last", "uriBaseId": "SRCROOT"}, "region": {"startLine": 1, "startColumn": 9}}}]},
              {"ruleId": "fqcn[action-core]", "level": "warning", "message": {"text": "Use ansible.builtin.shell"}, "locations": [{"physicalLocation": {"artifactLocation": {"uri": "${'$'}last", "uriBaseId": "SRCROOT"}, "region": {"startLine": 2, "startColumn": 3}}}]}],
             "originalUriBaseIds": {"SRCROOT": {"uri": "file://${'$'}PWD/"}}}]}
            JSON
        """.trimIndent() + "\n"
}
