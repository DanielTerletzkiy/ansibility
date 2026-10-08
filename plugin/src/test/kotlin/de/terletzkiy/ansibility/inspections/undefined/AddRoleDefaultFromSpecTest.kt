package de.terletzkiy.ansibility.inspections.undefined

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.terletzkiy.ansibility.context.AnsibleWorkspaceImpl
import de.terletzkiy.ansibility.settings.SettingsTestSupport

/**
 * ANS-V003's "Add to defaults" fix after plan amendment R23 (D175): it copies the argument spec's documented default
 * (Ansible never applies it, so the role default should be that value) and appends to the last file of a
 * `defaults/main/` directory instead of creating a `defaults/main.yml` that would hide the directory. It never writes into
 * a whole-file vault or a JSON file, and never copies an alias or an anchor.
 */
class AddRoleDefaultFromSpecTest : BasePlatformTestCase() {
    override fun setUp() {
        super.setUp()
        myFixture.setCaresAboutInjection(false)
        file("site/ansible.cfg", "[defaults]\nroles_path = roles\n")
        file("site/environments/prod/hosts.yml", "all:\n  hosts:\n    web1:\n")
        file("site/site.yml", "- hosts: all\n  roles:\n    - web\n    - db\n    - vaulted\n    - jsonly\n    - anchored\n")
        file(
            "$WEB/meta/argument_specs.yml",
            "argument_specs:\n  main:\n    options:\n      web_version:\n        type: str\n        default: \"1.5\"\n" +
                "      web_list:\n        type: list\n        default:\n          - a\n          - b\n",
        )
        file("$WEB/defaults/main.yml", "web_other: 1\n")
        file("$WEB/tasks/main.yml", "- name: Use\n  ansible.builtin.debug:\n    msg: \"{{ web_version }} {{ web_list }}\"\n")
        file("$DB/meta/argument_specs.yml", "argument_specs:\n  main:\n    options:\n      db_port: {type: int}\n")
        file("$DB/defaults/main/10-a.yml", "db_a: 1\n")
        file("$DB/defaults/main/20-b.yml", "db_b: 2\n")
        file("$DB/tasks/main.yml", "- name: Use\n  ansible.builtin.debug:\n    msg: \"{{ db_port }}\"\n")
        file("$VAULTED/defaults/main/10-main.yml", "vaulted_a: 1\n")
        file("$VAULTED/defaults/main/20-secret.yml", "\$ANSIBLE_VAULT;1.1;AES256\n61626364\n")
        file("$VAULTED/tasks/main.yml", "- name: Use\n  ansible.builtin.debug:\n    msg: \"{{ vaulted_new }}\"\n")
        file("$JSONLY/defaults/main.json", "{\"jsonly_a\": 1}\n")
        file("$JSONLY/tasks/main.yml", "- name: Use\n  ansible.builtin.debug:\n    msg: \"{{ jsonly_new }}\"\n")
        file(
            "$ANCHORED/meta/argument_specs.yml",
            "argument_specs:\n  main:\n    options:\n      anchored_base:\n        type: list\n        default: &anchored_list [a, b]\n" +
                "      anchored_list:\n        type: list\n        default: *anchored_list\n",
        )
        file("$ANCHORED/defaults/main.yml", "anchored_other: 1\n")
        file("$ANCHORED/tasks/main.yml", "- name: Use\n  ansible.builtin.debug:\n    msg: \"{{ anchored_list }}\"\n")
        AnsibleWorkspaceImpl.getInstance(project)!!.structureChanged()
        IndexingTestUtil.waitUntilIndexesAreReady(project)
    }

    override fun tearDown() {
        try {
            SettingsTestSupport.resetAll(project)
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    private fun file(path: String, text: String): VirtualFile = myFixture.tempDirFixture.createFile(path, text)

    private fun vf(path: String): VirtualFile = myFixture.findFileInTempDir(path) ?: error("missing $path")

    private fun text(path: String): String {
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        FileDocumentManager.getInstance().saveAllDocuments()
        return VfsUtil.loadText(vf(path))
    }

    /** The "Add … to …" fix at the V003 highlight of [name] in [tasks]. */
    private fun addFix(tasks: String, name: String): IntentionAction {
        val fixes = addFixActions(tasks, name)
        return fixes.firstOrNull() ?: error("no add fix: ${myFixture.availableIntentions.map { it.text }}")
    }

    private fun addFixes(tasks: String, name: String): List<String> = addFixActions(tasks, name).map { it.text }

    private fun addFixActions(tasks: String, name: String): List<IntentionAction> {
        myFixture.enableInspections(AnsiblePossiblyUndefinedInspection::class.java)
        myFixture.configureFromExistingVirtualFile(vf(tasks))
        val info = myFixture.doHighlighting().firstOrNull { it.description?.contains("ANS-V003") == true && myFixture.file.text.substring(it.startOffset, it.endOffset) == name }
            ?: error("no V003 on $name in $tasks")
        myFixture.editor.caretModel.moveToOffset(info.startOffset)
        return myFixture.availableIntentions.filter { it.text.startsWith("Add '$name' to ") }
    }

    fun testTheDocumentedDefaultIsCopiedAsWritten() {
        val fix = addFix("$WEB/tasks/main.yml", "web_version")
        assertEquals("every entry point documents it already", "Add 'web_version' to web/defaults/main.yml", fix.text)
        myFixture.launchAction(fix)
        myFixture.launchAction(addFix("$WEB/tasks/main.yml", "web_list"))
        assertEquals("web_other: 1\nweb_version: \"1.5\"\nweb_list:\n  - a\n  - b\n", text("$WEB/defaults/main.yml"))
    }

    fun testADefaultsDirectoryGetsTheValueInItsLastFile() {
        val fix = addFix("$DB/tasks/main.yml", "db_port")
        assertEquals("Add 'db_port' to db/defaults/main/20-b.yml and the argument spec", fix.text)
        myFixture.launchAction(fix)
        assertEquals("db_b: 2\ndb_port: null\n", text("$DB/defaults/main/20-b.yml"))
        assertNull("a defaults/main.yml would hide the directory", vf("$DB/defaults").findChild("main.yml"))
        assertEquals("a flow mapping keeps its form", "argument_specs:\n  main:\n    options:\n      db_port: {type: int, default: null}\n", text("$DB/meta/argument_specs.yml"))
    }

    fun testAWholeFileVaultSortedLastIsSkipped() {
        val fix = addFix("$VAULTED/tasks/main.yml", "vaulted_new")
        assertEquals("Add 'vaulted_new' to vaulted/defaults/main/10-main.yml", fix.text)
        myFixture.launchAction(fix)
        assertEquals("vaulted_a: 1\nvaulted_new: ''\n", text("$VAULTED/defaults/main/10-main.yml"))
        assertEquals("\$ANSIBLE_VAULT;1.1;AES256\n61626364\n", text("$VAULTED/defaults/main/20-secret.yml"))
    }

    fun testNoFixAppendsYamlToAJsonDefaultsFile() {
        assertEquals(emptyList<String>(), addFixes("$JSONLY/tasks/main.yml", "jsonly_new"))
    }

    fun testAnAliasedDocumentedDefaultIsNotCopied() {
        myFixture.launchAction(addFix("$ANCHORED/tasks/main.yml", "anchored_list"))
        assertEquals("the empty value of the type instead of an undefined alias", "anchored_other: 1\nanchored_list: []\n", text("$ANCHORED/defaults/main.yml"))
    }

    private companion object {
        const val WEB = "site/roles/web"
        const val DB = "site/roles/db"
        const val VAULTED = "site/roles/vaulted"
        const val JSONLY = "site/roles/jsonly"
        const val ANCHORED = "site/roles/anchored"
    }
}
