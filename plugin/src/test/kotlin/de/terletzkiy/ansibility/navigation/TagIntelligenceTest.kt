package de.terletzkiy.ansibility.navigation

import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.psi.impl.FakePsiElement
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.terletzkiy.ansibility.api.SiteClassifier
import de.terletzkiy.ansibility.api.TagSite
import de.terletzkiy.ansibility.context.AnsibleWorkspaceImpl
import de.terletzkiy.ansibility.navigation.tags.TagDocumentation
import de.terletzkiy.ansibility.navigation.tags.TagNavigation

/** Plan X70: tags complete from the root, hover counts who carries them, Ctrl+B lists the other places. */
class TagIntelligenceTest : BasePlatformTestCase() {
    override fun setUp() {
        super.setUp()
        myFixture.addFileToProject("ansible.cfg", "[defaults]\n")
        myFixture.addFileToProject("inventory/hosts.yml", "all:\n  hosts:\n    h1:\n")
        myFixture.addFileToProject(
            "site.yml",
            "- name: Web servers\n  hosts: web\n  tags: [web, base]\n  roles:\n    - role: nginx\n      tags: web\n",
        )
        myFixture.addFileToProject(
            "roles/nginx/tasks/main.yml",
            "- name: Install nginx\n  ansible.builtin.apt:\n    name: nginx\n  tags:\n    - web\n    - packages\n" +
                "- name: Configure\n  ansible.builtin.template:\n    src: a.j2\n    dest: /etc/a\n    tags: notatag\n  tags: config\n",
        )
        AnsibleWorkspaceImpl.getInstance(project)!!.structureChanged()
    }

    fun testCompletionOffersTheRootsTagsButNotTheOnesAlreadyListed() {
        myFixture.configureFromTempProjectFile("roles/nginx/tasks/main.yml")
        val offset = myFixture.editor.document.text.indexOf("tags: config") + "tags: ".length
        myFixture.editor.caretModel.moveToOffset(offset)
        myFixture.type("web, ")
        val items = myFixture.completeBasic()?.map { it.lookupString }.orEmpty()
        assertTrue(items.toString(), "base" in items && "packages" in items && "always" in items)
        assertFalse(items.toString(), "web" in items)
        assertFalse("module arguments named tags are not tags", "notatag" in items)
    }

    fun testCtrlBListsTheOtherPlacesWithTheirOwners() {
        val rows = runReadActionBlocking {
            val (psi, site) = siteAt("roles/nginx/tasks/main.yml", "    - web", 6)
            TagNavigation().targets(site, psi).map { (it as FakePsiElement).presentableText + " | " + it.locationString }
        }
        assertEquals(
            listOf("Web servers | play \u00B7 site.yml:3", "nginx | role entry \u00B7 site.yml:6"),
            rows.sorted(),
        )
    }

    fun testHoverCountsTheOwners() {
        val html = runReadActionBlocking {
            val (psi, site) = siteAt("site.yml", "web, base", 0)
            TagDocumentation().documentation(site, psi)!!.computeDocumentation().toString()
        }
        assertTrue(html, "1 play, 1 role entry, no blocks, 1 task" in html)
        assertTrue(html, "In roles: nginx" in html)
    }

    /**
     * Plan amendment R20, D153: from production files, while "Show Molecule in navigation and search" is off, Ctrl+B,
     * hover and completion leave Molecule playbooks out; from a Molecule file they see everything.
     */
    fun testMoleculePlaybooksOnlyWhereMoleculeIsShown() {
        myFixture.addFileToProject("roles/nginx/molecule/default/molecule.yml", "---\ndriver:\n  name: default\n")
        myFixture.addFileToProject(
            "roles/nginx/molecule/default/verify.yml",
            "- name: Verify\n  hosts: all\n  tasks:\n    - name: Check\n      ansible.builtin.command: \"true\"\n      tags: [web, molecule_only]\n",
        )
        AnsibleWorkspaceImpl.getInstance(project)!!.structureChanged()
        fun targets(path: String, anchor: String, delta: Int): List<String> = runReadActionBlocking<List<String>> {
            val (psi, site) = siteAt(path, anchor, delta)
            TagNavigation().targets(site, psi).map { (it as FakePsiElement).locationString.orEmpty() }.sorted()
        }
        assertFalse("off, from the role's tasks", targets("roles/nginx/tasks/main.yml", "    - web", 6).any { "verify.yml" in it })
        val hover = runReadActionBlocking {
            val (psi, site) = siteAt("site.yml", "web, base", 0)
            TagDocumentation().documentation(site, psi)!!.computeDocumentation().toString()
        }
        assertTrue("off: the hover counts no Molecule task: $hover", "1 play, 1 role entry, no blocks, 1 task" in hover)
        assertTrue("from verify.yml: everything", targets("roles/nginx/molecule/default/verify.yml", "[web", 1).any { "tasks/main.yml" in it })
        myFixture.configureFromTempProjectFile("roles/nginx/tasks/main.yml")
        myFixture.editor.caretModel.moveToOffset(myFixture.editor.document.text.indexOf("tags: config") + "tags: ".length)
        myFixture.type("web, ")
        val items = myFixture.completeBasic()?.map { it.lookupString }.orEmpty()
        assertTrue("the root's tags: $items", "base" in items && "packages" in items)
        assertFalse("off: no tag only a Molecule playbook writes: $items", "molecule_only" in items)
        de.terletzkiy.ansibility.context.MoleculeNavigationFixture.showInNavigationUntil(project, testRootDisposable)
        assertTrue("on", targets("roles/nginx/tasks/main.yml", "    - web", 6).any { "verify.yml" in it })
    }

    fun testModuleArgumentsAreNotTags() {
        runReadActionBlocking {
            val file = myFixture.findFileInTempDir("roles/nginx/tasks/main.yml")
            val psi = myFixture.psiManager.findFile(file)!!
            val offset = psi.text.indexOf("notatag") + 2
            assertFalse(SiteClassifier.EP_NAME.extensionList.firstNotNullOfOrNull { it.classify(psi, offset) } is TagSite)
        }
    }

    private fun siteAt(path: String, anchor: String, delta: Int): Pair<com.intellij.psi.PsiFile, TagSite> {
        val psi = myFixture.psiManager.findFile(myFixture.findFileInTempDir(path))!!
        val offset = psi.text.indexOf(anchor) + delta
        val site = SiteClassifier.EP_NAME.extensionList.firstNotNullOf { it.classify(psi, offset) }
        assertTrue(site.toString(), site is TagSite)
        return psi to site as TagSite
    }
}
