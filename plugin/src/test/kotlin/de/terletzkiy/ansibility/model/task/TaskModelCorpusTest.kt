package de.terletzkiy.ansibility.model.task

import com.intellij.psi.PsiFileFactory
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.terletzkiy.ansibility.fixtures.InfraTestData
import de.terletzkiy.ansibility.semantics.CoreVersion
import de.terletzkiy.ansibility.yaml.PsiYValueAdapter
import org.jetbrains.yaml.YAMLFileType
import org.jetbrains.yaml.psi.YAMLFile
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Opt-in check of the task model against the real infra repo (read-only): set `ANSIBLE_INFRA_REPO` to its path.
 * Without the variable every test returns immediately. The expected numbers are the research counts
 * (tasks.md §2, roles.md §5–6), measured with ansible-core's own keyword sets.
 */
class TaskModelCorpusTest : BasePlatformTestCase() {
    private val repo: Path? by lazy { System.getenv(InfraTestData.INFRA_REPO_ENV)?.let { Paths.get(it) }?.takeIf { Files.isDirectory(it) } }

    private enum class Area { ROLE_TASKS, HANDLERS, MOLECULE, PLAYBOOK }

    private class Parsed(val path: String, val area: Area, val model: TaskFileModel)

    private fun corpus(repo: Path): List<Parsed> {
        val builder = TaskModelBuilder(TaskSyntaxService.getInstance().forVersion(CoreVersion.PINNED))
        val factory = PsiFileFactory.getInstance(project)
        val files = Files.walk(repo).use { stream ->
            stream.filter { Files.isRegularFile(it) }
                .map { repo.relativize(it).joinToString("/") }
                .filter { it.endsWith(".yml") || it.endsWith(".yaml") }
                .filter { rel -> rel.split('/').none { it in SKIPPED } }
                .sorted()
                .toList()
        }
        return files.mapNotNull { rel ->
            val area = areaOf(rel) ?: return@mapNotNull null
            val text = String(Files.readAllBytes(repo.resolve(rel)), StandardCharsets.UTF_8)
            val psi = factory.createFileFromText(rel.substringAfterLast('/'), YAMLFileType.YML, text) as YAMLFile
            val kind = when (area) {
                Area.PLAYBOOK -> TaskFileKind.PLAYBOOK
                Area.HANDLERS -> TaskFileKind.HANDLERS
                Area.ROLE_TASKS -> TaskFileKind.TASKS
                Area.MOLECULE -> if (MOLECULE_TASKS.containsMatchIn(rel)) TaskFileKind.TASKS else TaskFileKind.PLAYBOOK
            }
            Parsed(rel, area, builder.build(PsiYValueAdapter.documentValue(psi), kind))
        }
    }

    /** Where a YAML file sits in the repo's layout; null for vars, inventories, specs and other non-task files. */
    private fun areaOf(rel: String): Area? {
        val inAnsible = rel.startsWith("golden/") || Regex("""^repos/[^/]+/ansible/""").containsMatchIn(rel)
        if (!inAnsible) return null
        val segments = rel.split('/')
        val name = segments.last()
        return when {
            "molecule" in segments -> if (MOLECULE_TASKS.containsMatchIn(rel) || name.substringBeforeLast('.') in MOLECULE_PLAYBOOKS) Area.MOLECULE else null
            "roles" in segments && segments.getOrNull(segments.indexOf("roles") + 2) == "tasks" -> Area.ROLE_TASKS
            "roles" in segments && segments.getOrNull(segments.indexOf("roles") + 2) == "handlers" -> Area.HANDLERS
            "roles" in segments -> null
            rel.startsWith("golden/playbooks/") -> Area.PLAYBOOK
            name.startsWith("playbook-") -> Area.PLAYBOOK
            else -> null
        }
    }

    fun testEveryTaskHasExactlyOneModule() {
        val repo = repo ?: return
        val parsed = corpus(repo)
        val byArea = parsed.groupBy { it.area }.mapValues { (_, files) -> files.sumOf { it.model.tasks().size } }
        assertEquals("tasks in roles/*/tasks (research: 6138)", 6138, byArea[Area.ROLE_TASKS])
        assertEquals("handlers (research: 748)", 748, byArea[Area.HANDLERS])
        assertEquals("molecule tasks (research: 8108)", 8108, byArea[Area.MOLECULE])
        assertEquals("tasks in top-level playbooks (research: 140)", 140, byArea[Area.PLAYBOOK])
        assertEquals("playbooks (research: 82)", 82, parsed.count { it.area == Area.PLAYBOOK })

        val problems = parsed.flatMap { file ->
            file.model.tasks().filter { it.module == null || it.unknownKeys.isNotEmpty() }
                .map { "${file.path}: ${it.name?.text} module=${it.module?.name} unknown=${it.unknownKeys.map { k -> k.key.text }}" }
        }
        assertEquals("every task has one module key and no stray keys", emptyList<String>(), problems)
    }

    fun testKeywordAndLoopCounts() {
        val repo = repo ?: return
        val tasks = corpus(repo).flatMap { it.model.tasks() }
        val loops = tasks.mapNotNull { it.loop?.keyword }.groupingBy { it }.eachCount()
        assertEquals("research: loop 1422", 1422, loops["loop"])
        assertEquals("research: with_items 74", 74, loops["with_items"])
        assertEquals("research: with_dict 45", 45, loops["with_dict"])
        assertEquals("research: with_nested 9", 9, loops["with_nested"])
        assertEquals("research: with_fileglob 9", 9, loops["with_fileglob"])
        assertEquals("research: register 3150", 3150, tasks.count { it.register != null })
        assertEquals("research: listen 39", 39, tasks.count { it.listen.isNotEmpty() })
        assertEquals("research: include_role 234", 234, tasks.count { it.roleInclude?.kind == IncludeKind.INCLUDE })
        assertEquals("research: loop_var 72", 72, tasks.count { it.loopControl?.loopVar != null })
        val jenkinsPlugin = tasks.filter { it.module?.canonical == "community.general.jenkins_plugin" }
        assertTrue(jenkinsPlugin.isNotEmpty())
        assertTrue("with_dependencies stays an option", jenkinsPlugin.none { it.loop?.keyword == "with_dependencies" })
    }

    private companion object {
        val SKIPPED = setOf(".git", ".claude", ".ansible", "patches", "node_modules", ".idea")
        val MOLECULE_TASKS = Regex("""_tasks\.ya?ml$""")
        val MOLECULE_PLAYBOOKS = setOf("converge", "verify", "prepare", "cleanup", "side_effect", "create", "destroy")
    }
}
