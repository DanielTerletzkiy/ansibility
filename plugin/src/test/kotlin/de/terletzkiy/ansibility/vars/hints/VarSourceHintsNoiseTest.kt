package de.terletzkiy.ansibility.vars.hints

import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import de.terletzkiy.ansibility.fixtures.RequiresInfraFixture
import de.terletzkiy.ansibility.vars.VarsTestCase
import java.util.Locale

/**
 * How quiet the variable source hints are on the real (aliased) fixture repositories (the R31 review's noise measurement,
 * kept by the R31 fixes as an opt-in test): with the default options no hint in a vars or inventory file and none that
 * only shows locks (decisions 4 and 5), no name hinted twice on one template line, and short texts. Prints the
 * statistics the review measured, for comparison.
 */
@RequiresInfraFixture
class VarSourceHintsNoiseTest : VarsTestCase() {
    fun testNoiseOnTheFixture() {
        for (repo in listOf("repos/falcon", "repos/platform")) {
            copyInfra(repo)
            val base = myFixture.tempDirFixture.getFile(repo)!!
            val files = ArrayList<VirtualFile>()
            VfsUtilCore.iterateChildrenRecursively(base, null) { f ->
                if (!f.isDirectory && f.extension in setOf("yml", "yaml", "j2")) files += f
                true
            }
            var hints = 0
            var hintedFiles = 0
            var lines = 0
            var multiLines = 0
            var maxPerLine = 0
            var totalLength = 0L
            var maxLength = 0
            var over45 = 0
            var lockOnly = 0
            var repeatsSameNameInFile = 0
            var repeatsSameNameOnLine = 0
            var inVarsFiles = 0
            var inTemplates = 0
            var inRoleFiles = 0
            var inPlaybooks = 0
            val byName = HashMap<String, Int>()
            val samples = ArrayList<String>()
            for (file in files) {
                val list = inBackgroundReadAction {
                    val psi = PsiManager.getInstance(project).findFile(file) ?: return@inBackgroundReadAction emptyList()
                    VarSourceHints.collect(project, psi, false)
                }
                if (list.isEmpty()) continue
                hintedFiles++
                hints += list.size
                val text = runReadActionBlocking { VfsUtilCore.loadText(file) }
                val perLine = list.groupBy { StringUtil.offsetToLineNumber(text, it.offset) }
                lines += perLine.size
                multiLines += perLine.values.count { it.size >= 2 }
                maxPerLine = maxOf(maxPerLine, perLine.values.maxOf { it.size })
                repeatsSameNameInFile += list.groupBy { it.name }.values.sumOf { it.size - 1 }
                repeatsSameNameOnLine += perLine.values.sumOf { onLine -> onLine.groupBy { it.name }.values.sumOf { it.size - 1 } }
                val path = file.path
                for ((line, onLine) in perLine.entries.take(2)) {
                    if (samples.size >= 16) break
                    val start = StringUtil.lineColToOffset(text, line, 0)
                    val end = text.indexOf('\n', start).let { if (it < 0) text.length else it }
                    val out = StringBuilder()
                    var at = start
                    for (h in onLine.sortedBy { it.offset }) {
                        out.append(text, at, h.offset).append('‹').append(h.text).append('›')
                        at = h.offset
                    }
                    samples += VfsUtilCore.getRelativePath(file, base) + ": " + out.append(text, at, end).toString().trim()
                }
                for (hint in list) {
                    val t = hint.text
                    totalLength += t.length
                    maxLength = maxOf(maxLength, t.length)
                    if (t.length > 45) over45++
                    if (t.replace(Regex("[a-z_0-9, -]+ 🔒( / 🔒)*"), "").replace(" · ", "").isBlank()) lockOnly++
                    byName.merge(hint.name, 1, Int::plus)
                    when {
                        "/group_vars/" in path || "/host_vars/" in path || path.endsWith("hosts.yml") || "/defaults/" in path || "/vars/" in path -> inVarsFiles++
                        path.endsWith(".j2") -> inTemplates++
                        "/roles/" in path -> inRoleFiles++
                        else -> inPlaybooks++
                    }
                }
            }
            println("R31F NOISE SAMPLES $repo:\n  " + samples.joinToString("\n  "))
            val top = byName.entries.sortedByDescending { it.value }.take(8).joinToString { "${it.key}=${it.value}" }
            println(
                String.format(
                    Locale.ROOT,
                    "R31F NOISE %s: %d yml/j2 files, %d with hints; %d hints on %d lines (%d lines with 2+, max %d per line); " +
                        "avg length %.0f, max %d, %d over 45 chars; %d lock-only; %d repeats of a name already hinted in the file, " +
                        "%d on the same line; in vars/inventory files %d, templates %d, other role files %d, playbooks %d; top names: %s",
                    repo, files.size, hintedFiles, hints, lines, multiLines, maxPerLine, if (hints == 0) 0.0 else totalLength.toDouble() / hints, maxLength, over45,
                    lockOnly, repeatsSameNameInFile, repeatsSameNameOnLine, inVarsFiles, inTemplates, inRoleFiles, inPlaybooks, top,
                ),
            )
            assertEquals("$repo: hints in vars and inventory files need their option", 0, inVarsFiles)
            assertEquals("$repo: a hint that only shows locks", 0, lockOnly)
            assertTrue("$repo: the longest hint has $maxLength characters", maxLength <= MAX_LENGTH)
        }
    }

    private companion object {
        /** About 45 visible characters; a first entry is never cut away (labels and values are cut at 24 each). */
        const val MAX_LENGTH = 80
    }
}
