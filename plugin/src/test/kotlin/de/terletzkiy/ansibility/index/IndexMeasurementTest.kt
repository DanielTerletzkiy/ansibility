package de.terletzkiy.ansibility.index

import com.intellij.openapi.project.Project
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.io.DataExternalizer
import com.intellij.util.io.IOUtil
import de.terletzkiy.ansibility.context.AnsibleLayout
import de.terletzkiy.ansibility.fixtures.InfraTestData
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.lang.management.ManagementFactory
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.name

/**
 * Measures what the six Ansible indexers cost (plan A.7 "Budget": at most 10 s of extra indexing on the corpus): the
 * wall time of running every indexer over every accepted file, PSI parsing included, and the serialised size of all
 * keys and values. Prints one report line per run to the test output.
 *
 * The fixture run always executes; the real repo run is opt-in via `ANSIBLE_INFRA_REPO` (read-only).
 */
class IndexMeasurementTest : BasePlatformTestCase() {
    fun testIndexingCostOfTheFixture() {
        measure(project, InfraTestData.root) // warm-up: class loading and JIT
        val report = measure(project, InfraTestData.root)
        println("Ansible indexes on the fixture: $report")
        assertTrue(report.toString(), report.files > 400)
        assertTrue(report.toString(), report.cpuMillis < BUDGET_MILLIS)
        assertTrue("every index has data: $report", report.perIndex.values.all { it.keys > 0 })
    }

    fun testIndexingCostOfTheRealRepo() {
        val repo = System.getenv(InfraTestData.INFRA_REPO_ENV)?.let { Paths.get(it) }?.takeIf(Files::isDirectory) ?: return
        measure(project, InfraTestData.root) // warm-up: class loading and JIT
        val report = measure(project, repo)
        println("Ansible indexes on ${repo.name}: $report")
        assertTrue(report.toString(), report.cpuMillis < BUDGET_MILLIS)
    }

    /** Totals of one index over one tree. */
    class IndexStats {
        var keys = 0
        var values = 0
        var bytes = 0L
        var nanos = 0L

        override fun toString() = "$keys keys/$values values/${bytes / 1024} KB/${nanos / 1_000_000} ms"
    }

    /**
     * [cpuMillis] is the CPU time of the measuring thread, which other processes on the machine do not inflate (the
     * budget is checked against it); [millis] is wall time.
     */
    class Report(val files: Int, val millis: Long, val cpuMillis: Long, val parseMillis: Long, val perIndex: Map<String, IndexStats>) {
        override fun toString(): String {
            val total = perIndex.values.sumOf { it.bytes }
            return "$files files, $millis ms wall, $cpuMillis ms CPU ($parseMillis ms wall YAML parsing and loading), ${total / 1024} KB; " +
                perIndex.entries.joinToString("; ") { "${it.key}: ${it.value}" }
        }
    }

    private class Indexer<V>(
        val name: String,
        val templates: Boolean,
        val index: (IndexInput) -> Map<String, V>,
        val externalizer: DataExternalizer<V>,
    )

    companion object {
        private const val BUDGET_MILLIS = 10_000L

        private val INDEXERS: List<Indexer<*>> by lazy {
            listOf(
                Indexer(VarDefIndex.NAME.name, false, VarDefIndexer::index, VarDefIndex.EXTERNALIZER),
                Indexer(VarUseIndex.NAME.name, true, VarUseIndexer::index, VarUseIndex.EXTERNALIZER),
                Indexer(TemplateUseIndex.NAME.name, true, TemplateUseIndexer::index, TemplateUseIndex.EXTERNALIZER),
                Indexer(HandlerIndex.NAME.name, false, HandlerIndexer::index, HandlerIndex.EXTERNALIZER),
                Indexer(ModuleUseIndex.NAME.name, false, ModuleUseIndexer::index, ModuleUseIndex.EXTERNALIZER),
                Indexer(PlayIndex.NAME.name, false, PlayIndexer::index, PlayIndex.EXTERNALIZER),
            )
        }

        fun measure(project: Project, root: Path): Report {
            val files = Files.walk(root).use { stream ->
                stream.filter { Files.isRegularFile(it) && !skipped(root, it) && Files.size(it) <= AnsibleIndexInputFilter.MAX_FILE_SIZE }
                    .sorted()
                    .toList()
            }
            val inputs = files.mapNotNull { file ->
                val path = file.toAbsolutePath().toString()
                val facts = PathFacts.of(path)
                if (!AnsibleIndexInputFilter.accepts(facts, templates = true)) return@mapNotNull null
                val text = readText(file) ?: return@mapNotNull null
                path to text
            }
            val stats = INDEXERS.associate { it.name to IndexStats() }
            val threads = ManagementFactory.getThreadMXBean()
            val cpuStart = threads.currentThreadCpuTime
            val start = System.nanoTime()
            var parseNanos = 0L
            val results = inputs.map { (path, text) ->
                val input = IndexInput.of(path, text, project)
                val parseStart = System.nanoTime()
                input.document
                parseNanos += System.nanoTime() - parseStart
                INDEXERS.map { indexer ->
                    val t = System.nanoTime()
                    val map = if (AnsibleIndexInputFilter.accepts(input.facts, indexer.templates)) indexer.index(input) else emptyMap()
                    stats.getValue(indexer.name).nanos += System.nanoTime() - t
                    map
                }
            }
            val millis = (System.nanoTime() - start) / 1_000_000
            val cpuMillis = (threads.currentThreadCpuTime - cpuStart) / 1_000_000
            for (perFile in results) {
                perFile.forEachIndexed { i, map ->
                    @Suppress("UNCHECKED_CAST")
                    val indexer = INDEXERS[i] as Indexer<Any?>
                    val s = stats.getValue(indexer.name)
                    for ((key, value) in map) {
                        s.keys++
                        s.values += (value as? List<*>)?.size ?: 1
                        s.bytes += serialisedSize(key, value, indexer.externalizer)
                    }
                }
            }
            return Report(inputs.size, millis, cpuMillis, parseNanos / 1_000_000, stats)
        }

        private fun <V> serialisedSize(key: String, value: V, externalizer: DataExternalizer<V>): Int {
            val bytes = ByteArrayOutputStream()
            DataOutputStream(bytes).use { out ->
                IOUtil.writeUTF(out, key)
                externalizer.save(out, value)
            }
            return bytes.size()
        }

        /** VCS metadata and tool caches, which the IDE does not index either. */
        private fun skipped(root: Path, file: Path): Boolean =
            root.relativize(file).parent?.any { it.name in AnsibleLayout.SKIPPED_DIRS } == true

        /** UTF-8 text, or null for binary content. */
        private fun readText(file: Path): String? = try {
            StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(Files.readAllBytes(file))).toString().takeIf { '\u0000' !in it }
        } catch (_: CharacterCodingException) {
            null
        }
    }
}
