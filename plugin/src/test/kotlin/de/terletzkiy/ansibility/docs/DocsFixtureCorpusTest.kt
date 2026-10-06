package de.terletzkiy.ansibility.docs

import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileVisitor
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import de.terletzkiy.ansibility.api.AnsibleDocService
import de.terletzkiy.ansibility.api.AnsibleSite
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.fixtures.RequiresInfraFixture
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLScalar

/**
 * Every key of every task-like file of the fixture's `golden/` tree (the role library, its molecule scenarios and
 * playbooks): classification never fails, module keys are documented by the bundled 2.18.8 line, documented
 * option and keyword keys get a target, and every documentation page renders.
 */
@RequiresInfraFixture
class DocsFixtureCorpusTest : DocsTestCase() {

    fun testEveryKeyOfTheGoldenTree() {
        copyInfra("golden/roles", "golden/playbooks")
        val workspace = AnsibleWorkspace.getInstance(project)
        val files = ArrayList<VirtualFile>()
        VfsUtilCore.visitChildrenRecursively(vf("golden"), object : VirtualFileVisitor<Unit>() {
            override fun visitFile(file: VirtualFile): Boolean {
                if (!file.isDirectory && workspace.contextOf(file)?.kind in TaskSiteClassifier.TASK_FILE_KINDS) files += file
                return true
            }
        })
        assertTrue("task-like files in golden: ${files.size}", files.size > 100)

        val classifier = TaskSiteClassifier()
        val documentation = TaskSiteDocumentation()
        val counts = HashMap<String, Int>()
        val modules = sortedSetOf<String>()
        val undocumentedKeywords = sortedSetOf<String>()
        inBackgroundReadAction {
            for (file in files) {
                val psi = PsiManager.getInstance(project).findFile(file) ?: continue
                for (keyValue in PsiTreeUtil.findChildrenOfType(psi, YAMLKeyValue::class.java)) {
                    val offset = keyValue.key?.textRange?.startOffset ?: continue
                    val site = classifier.classify(psi, offset) ?: continue
                    counts.merge(site.javaClass.simpleName, 1, Int::plus)
                    when (site) {
                        is AnsibleSite.ModuleKey -> modules += site.fqcn
                        is AnsibleSite.KeywordKey ->
                            if (documentation.documentation(site, psi) == null) undocumentedKeywords += "${site.keyword}@${site.level}"
                        is AnsibleSite.ModuleOptionKey -> documentation.documentation(site, psi)?.computeDocumentation()
                        else -> Unit
                    }
                }
                // Values: every character of every scalar (plain, quoted, block), so each escaper path is exercised.
                for (scalar in PsiTreeUtil.findChildrenOfType(psi, YAMLScalar::class.java)) {
                    val range = scalar.textRange
                    for (offset in range.startOffset..range.endOffset) {
                        val site = classifier.classify(psi, offset) ?: continue
                        if (site is AnsibleSite.JinjaFilter || site is AnsibleSite.JinjaTest) {
                            counts.merge(site.javaClass.simpleName, 1, Int::plus)
                            val text = psi.text.substring(site.range.startOffset, site.range.endOffset)
                            val name = if (site is AnsibleSite.JinjaFilter) site.name else (site as AnsibleSite.JinjaTest).name
                            assertEquals("the site range holds the name at $file:$offset", name.substringAfterLast('.'), text.substringAfterLast('.'))
                        }
                    }
                }
            }
        }
        assertTrue(counts.toString(), (counts["JinjaFilter"] ?: 0) > 1000)
        assertTrue(counts.toString(), (counts["JinjaTest"] ?: 0) > 100)
        assertTrue(counts.toString(), (counts["ModuleKey"] ?: 0) > 900)
        assertTrue(counts.toString(), (counts["ModuleOptionKey"] ?: 0) > 2000)
        assertTrue(counts.toString(), (counts["KeywordKey"] ?: 0) > 2000)
        assertEquals("every keyword key the classifier reports is documented at its level", emptySet<String>(), undocumentedKeywords)

        val root = root("golden/roles")
        val docs = AnsibleDocService.getInstance(project)
        val undocumented = inBackgroundReadAction { modules.filter { docs.moduleDoc(root, it)?.doc == null } }
        assertEquals("modules of golden without docs in the bundled snapshots", emptyList<String>(), undocumented)
        for (module in modules) {
            val html = inBackgroundReadAction { ModuleDocumentationTarget(project, root, module).computeDocumentation() }
            assertNotNull(module, html)
        }
    }
}
