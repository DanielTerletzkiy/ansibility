package de.terletzkiy.ansibility.inspections.undefined

import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileVisitor
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.FileKind
import de.terletzkiy.ansibility.context.AnsibleWorkspaceImpl
import de.terletzkiy.ansibility.fixtures.InfraTestData
import de.terletzkiy.ansibility.fixtures.RequiresInfraFixture
import de.terletzkiy.ansibility.index.PathFacts
import de.terletzkiy.ansibility.lang.jinja.AnsibleJinjaLanguage
import de.terletzkiy.ansibility.lang.jinja.lexer.JinjaLexMode
import de.terletzkiy.ansibility.lang.jinja.psi.AnsibleJinjaFile
import de.terletzkiy.ansibility.semantics.CoreVersion
import org.jetbrains.yaml.psi.YAMLFile

/**
 * The PSI path of the V003 use analysis ([PsiJinjaUses], on template trees and injected fragments) and its text-level
 * fallback ([TextJinjaUses] on `JinjaRefs`) report the same uses with the same guards, on every template and every
 * Jinja-bearing YAML scalar of the whole sanitised fixture, under the 2.18 and the 2.19+ rules.
 */
@RequiresInfraFixture
class JinjaUsesParityTest : BasePlatformTestCase() {
    override fun getTestDataPath(): String = InfraTestData.testDataPath.toString()

    private val ruleSets = listOf(GuardRules.of(CoreVersion(2, 18, 8)), GuardRules.of(CoreVersion(2, 21, 4)))

    private fun key(use: SiteUse) = listOf(use.name, use.nameRange, use.pathRange, use.appendOffset, use.raw.guarded, use.raw.mandatory, use.raw.iterable)

    private fun key(use: RawUse) = listOf(use.name, use.nameRange, use.pathRange, use.chainEnd, use.guarded, use.mandatory, use.iterable)

    fun testWholeFixture() {
        myFixture.setCaresAboutInjection(false)
        myFixture.copyDirectoryToProject(InfraTestData.INFRA, "")
        AnsibleWorkspaceImpl.getInstance(project)!!.structureChanged()
        val differences = ArrayList<String>()
        var templates = 0
        var yamlFiles = 0
        var psiUses = 0
        var uses = 0
        runReadActionBlocking {
            val workspace = AnsibleWorkspace.getInstance(project)
            VfsUtilCore.visitChildrenRecursively(
                myFixture.tempDirFixture.getFile("")!!,
                object : VirtualFileVisitor<Unit>() {
                    override fun visitFile(file: VirtualFile): Boolean {
                        if (file.isDirectory) return file.name != ".git"
                        val context = workspace.contextOf(file) ?: return true
                        val psi = PsiManager.getInstance(project).findFile(file) ?: return true
                        for (rules in ruleSets) {
                            val (psiSide, textSide) = when {
                                context.kind == FileKind.ROLE_TEMPLATE || PathFacts.isJ2(file.name) -> {
                                    if (rules === ruleSets.first()) templates++
                                    UseSites.template(psi, rules, usePsi = true) to UseSites.template(psi, rules, usePsi = false)
                                }
                                psi is YAMLFile && context.kind != FileKind.OTHER -> {
                                    if (rules === ruleSets.first()) yamlFiles++
                                    UseSites.yaml(psi, rules, usePsi = true) to UseSites.yaml(psi, rules, usePsi = false)
                                }
                                else -> continue
                            }
                            if (rules === ruleSets.first()) {
                                uses += psiSide.size
                                psiUses += psiSide.count { it.fromPsi }
                            }
                            val a = psiSide.map(::key)
                            val b = textSide.map(::key)
                            if (a != b) differences += "${file.path.substringAfter("/src/")}: PSI-only ${a - b.toSet()}, text-only ${b - a.toSet()}"
                        }
                        return true
                    }
                },
            )
        }
        println("JinjaUsesParityTest: $templates templates, $yamlFiles YAML files, $uses uses ($psiUses from PSI), ${differences.size} differences")
        assertTrue("the PSI path must be exercised", psiUses > uses / 2)
        assertEmpty(differences.take(20).joinToString("\n"), differences)
    }

    fun testGuardTable() {
        val texts = ProbeRole.FILES.filterKeys { it.endsWith(".j2") }.values.map { it.trimIndent() } + listOf(
            "{% if (x) is defined %}{{ x }}{% endif %}",
            "{{ a | default(b | upper) }} {{ c | d(d) }}",
            "{% for i in xs if i %}{{ i }}{% else %}{{ xs }}{% endfor %}",
            "{{ x.y['z'][0].w | default('') }} {{ x[k] }} {{ x.f() }}",
            "{% set y = x %}{{ y }}",
            "{{ x | ansible.builtin.mandatory }} {{ x is not ansible.builtin.none }}",
        )
        for (rules in ruleSets) {
            for (text in texts) {
                val file = PsiFileFactory.getInstance(project).createFileFromText("t.j2", AnsibleJinjaLanguage, text)
                val jinja = file.viewProvider.getPsi(AnsibleJinjaLanguage) as AnsibleJinjaFile
                val psi = runReadActionBlocking { PsiJinjaUses.collect(jinja, rules) }.map(::key)
                val plain = TextJinjaUses.collect(text, JinjaLexMode.TEMPLATE, rules).map(::key)
                assertEquals("$rules: $text", plain, psi)
            }
        }
    }
}
