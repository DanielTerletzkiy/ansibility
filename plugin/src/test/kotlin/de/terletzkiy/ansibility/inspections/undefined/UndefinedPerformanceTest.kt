package de.terletzkiy.ansibility.inspections.undefined

import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileVisitor
import com.intellij.psi.PsiManager
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.terletzkiy.ansibility.api.AnsibleContextService
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.FileKind
import de.terletzkiy.ansibility.context.AnsibleWorkspaceImpl
import de.terletzkiy.ansibility.fixtures.InfraTestData
import de.terletzkiy.ansibility.index.PathFacts

/**
 * The warm cost of ANS-V003 (acceptance 5: under 200 ms) on the largest template of the sanitised fixture that sits
 * inside an Ansible root, and on the template with the most variable uses among those that run on hosts (its analysis
 * also evaluates the witnesses over the reach of its role).
 */
class UndefinedPerformanceTest : BasePlatformTestCase() {
    override fun getTestDataPath(): String = InfraTestData.testDataPath.toString()

    fun testLargestFixtureTemplateIsAnalysedWarmInUnder200Ms() {
        myFixture.copyDirectoryToProject(InfraTestData.INFRA, "")
        AnsibleWorkspaceImpl.getInstance(project)!!.structureChanged()
        val workspace = AnsibleWorkspace.getInstance(project)
        val templates = ArrayList<VirtualFile>()
        VfsUtilCore.visitChildrenRecursively(
            myFixture.tempDirFixture.getFile("")!!,
            object : VirtualFileVisitor<Unit>() {
                override fun visitFile(file: VirtualFile): Boolean {
                    if (file.isDirectory) return file.name != ".git"
                    val context = runReadActionBlocking { workspace.contextOf(file) } ?: return true
                    if (!context.root.detached && (context.kind == FileKind.ROLE_TEMPLATE || PathFacts.isJ2(file.name))) templates += file
                    return true
                }
            },
        )
        val largest = templates.maxBy { it.length }
        // the template with the most variable uses among those that run on hosts: its analysis also evaluates the
        // witnesses over the reach of its role
        val busiest = templates.filter { file ->
            runReadActionBlocking { AnsibleContextService.getInstance(project).allHostsScope(file).targets.isNotEmpty() }
        }.maxBy(::useCount)
        for (template in listOf(largest, busiest).distinct()) {
            val median = warmMedian(template)
            println(
                "UndefinedPerformanceTest: ${template.path.substringAfter("/src/")} (${template.length} bytes, ${useCount(template)} uses, " +
                    "${templates.size} templates): warm median $median ms",
            )
            assertTrue("median $median ms for ${template.name}", median < 200.0)
        }
    }

    private fun useCount(template: VirtualFile): Int = runReadActionBlocking {
        UseSites.template(PsiManager.getInstance(project).findFile(template)!!, GuardRules.of(null)).size
    }

    /** The median of seven warm analyses of [template] (after two warm-up runs), in milliseconds. */
    private fun warmMedian(template: VirtualFile): Double {
        val workspace = AnsibleWorkspace.getInstance(project)
        val analyse = {
            runReadActionBlocking {
                val psi = PsiManager.getInstance(project).findFile(template)!!
                PossiblyUndefined(project, psi, workspace.contextOf(template)!!).findings()
            }
        }
        repeat(2) { analyse() }
        val times = (1..7).map {
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            val start = System.nanoTime()
            analyse()
            (System.nanoTime() - start) / 1_000_000.0
        }.sorted()
        return times[times.size / 2]
    }
}
