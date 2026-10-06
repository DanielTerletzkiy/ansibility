package de.terletzkiy.ansibility.vars

import com.intellij.openapi.application.runReadActionBlocking
import de.terletzkiy.ansibility.api.AnsibleSite
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.SiteClassifier
import de.terletzkiy.ansibility.api.VarDefKind
import de.terletzkiy.ansibility.api.VarService
import de.terletzkiy.ansibility.api.VarsLayer

/** Variables only a task makes definitions: `include_vars` files outside vars dirs and `import_playbook` vars. */
class IncludedVarsDefinitionsTest : VarsTestCase() {
    private fun setUpProject() {
        myFixture.addFileToProject("site.yml", "- hosts: all\n  roles:\n    - app\n- import_playbook: other.yml\n  vars:\n    imported_flag: true\n")
        myFixture.addFileToProject("other.yml", "- hosts: all\n  tasks:\n    - debug: msg=\"{{ imported_flag }}\"\n")
        myFixture.addFileToProject(
            "roles/app/tasks/main.yml",
            "- name: load\n  include_vars: files/extra.yml\n- name: use\n  debug:\n    msg: \"{{ extra_port }}\"\n",
        )
        myFixture.addFileToProject("roles/app/files/extra.yml", "extra_port: 8443\nextra_name: app\n")
        refreshRoots()
    }

    fun testIncludeVarsKeysAndImportVarsAreDefinitions() {
        setUpProject()
        val root = runReadActionBlocking { AnsibleWorkspace.getInstance(project).roots().single() }
        val port = inBackgroundReadAction { VarService.getInstance(project).symbol(root, "extra_port") }.definitions.single()
        assertEquals(VarDefKind.INCLUDE_VARS, port.kind)
        assertEquals(VarsLayer.INCLUDE_VARS, port.layer)
        assertEquals("app", port.roleName)
        assertEquals("roles/app/files/extra.yml:1", describe(port.location))
        assertEquals("8443", port.preview)

        val flag = inBackgroundReadAction { VarService.getInstance(project).symbol(root, "imported_flag") }.definitions.single()
        assertEquals(VarDefKind.PLAY_VARS, flag.kind)
        assertEquals("site.yml:6", describe(flag.location))

        val names = inBackgroundReadAction { VarService.getInstance(project).allNames(root) }
        assertTrue(names.containsAll(listOf("extra_port", "extra_name", "imported_flag")))
    }

    fun testCtrlBFromAUseGoesToTheIncludedFile() {
        setUpProject()
        val file = myFixture.findFileInTempDir("roles/app/tasks/main.yml")
        val offset = String(file.contentsToByteArray()).indexOf("extra_port }}") + 1
        val targets = inBackgroundReadAction {
            val psi = psiManager.findFile(file)!!
            val site = SiteClassifier.EP_NAME.extensionList.firstNotNullOf { it.classify(psi, offset) } as AnsibleSite.VarRef
            VarNavigation().targets(site, psi).map { (it as VarTargetElement).location }
        }
        assertEquals(listOf("roles/app/files/extra.yml:1"), targets.map(::describe))
    }
}
