package de.terletzkiy.ansibility.vars.gutter

import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.terletzkiy.ansibility.context.AnsibleWorkspaceImpl
import de.terletzkiy.ansibility.settings.AnsibilityProjectSettings
import org.jetbrains.yaml.psi.YAMLKeyValue

/** Plan X42: overrides only between definitions that can meet on a host, and never with molecule files. */
class OverrideScopingTest : BasePlatformTestCase() {
    override fun setUp() {
        super.setUp()
        myFixture.addFileToProject("ansible.cfg", "[defaults]\n")
        myFixture.addFileToProject("site.yml", "- hosts: all\n  roles: [jenkins]\n")
        myFixture.addFileToProject("environments/prod/hosts.yml", "all:\n  children:\n    web:\n      hosts:\n        p1:\n    db:\n      hosts:\n        p2:\n")
        myFixture.addFileToProject("environments/build/hosts.yml", "all:\n  hosts:\n    b1:\n")
        myFixture.addFileToProject("environments/prod/group_vars/all/vault.yml", "api_key: prod\n")
        myFixture.addFileToProject("environments/prod/group_vars/web.yml", "web_only: 1\n")
        myFixture.addFileToProject("environments/prod/host_vars/p2.yml", "web_only: 2\n")
        myFixture.addFileToProject("environments/build/host_vars/b1.yml", "api_key: build\n")
        myFixture.addFileToProject("roles/jenkins/tasks/main.yml", "- ansible.builtin.debug:\n    msg: '{{ credentials }} {{ api_key }} {{ web_only }}'\n")
        myFixture.addFileToProject("roles/jenkins/defaults/main.yml", "credentials: []\n")
        myFixture.addFileToProject("roles/jenkins/molecule/default/converge.yml", "- hosts: all\n  vars:\n    credentials: [a]\n  roles: [jenkins]\n")
        AnsibleWorkspaceImpl.getInstance(project)!!.structureChanged()
    }

    fun testOtherEnvironmentsDoNotOverride() {
        assertEquals(Pair(emptyList<String>(), emptyList<String>()), overrides("environments/build/host_vars/b1.yml", "api_key"))
        assertEquals(Pair(emptyList<String>(), emptyList<String>()), overrides("environments/prod/group_vars/all/vault.yml", "api_key"))
    }

    fun testHostVarsOfAHostOutsideTheGroupDoNotOverrideIt() {
        assertEquals(Pair(emptyList<String>(), emptyList<String>()), overrides("environments/prod/group_vars/web.yml", "web_only"))
    }

    fun testMoleculePlaysDoNotOverrideTheRoleDefault() {
        assertEquals(Pair(emptyList<String>(), emptyList<String>()), overrides("roles/jenkins/defaults/main.yml", "credentials"))
    }

    fun testMoleculeSupportOffDropsMoleculeDefinitions() {
        val settings = AnsibilityProjectSettings.getInstance(project)
        val before = settings.settings
        settings.update { it.copy(paths = it.paths.copy(moleculeSupport = false)) }
        try {
            AnsibleWorkspaceImpl.getInstance(project)!!.structureChanged()
            val files = runReadActionBlocking {
                val root = de.terletzkiy.ansibility.api.AnsibleWorkspace.getInstance(project).roots().single()
                de.terletzkiy.ansibility.api.VarService.getInstance(project).symbol(root, "credentials").definitions.map { it.location.file.path.substringAfter("/src/") }
            }
            assertEquals(listOf("roles/jenkins/defaults/main.yml"), files)
        } finally {
            settings.update { before }
        }
    }

    /** Paths of the definitions that beat the key and that it beats. */
    private fun overrides(path: String, key: String): Pair<List<String>, List<String>> = runReadActionBlocking {
        val psi = myFixture.psiManager.findFile(myFixture.findFileInTempDir(path))!!
        val keyValue = PsiTreeUtil.findChildrenOfType(psi, YAMLKeyValue::class.java).first { it.keyText == key }
        val result = OverrideLineMarkers.overridesOf(keyValue)!!
        fun paths(list: List<de.terletzkiy.ansibility.api.VarDefinition>) = list.map { it.location.file.path.substringAfter("/src/") }
        paths(result.higher) to paths(result.lower)
    }
}
