package de.terletzkiy.ansibility.semantics.inventory

import de.terletzkiy.ansibility.semantics.testutil.YamlText

/**
 * An in-memory [InventoryFileSystem] for tests: files by `/`-separated path (directories are implied by their
 * files, or listed in [directories]); [executable] marks files with an execute permission. Counts YAML loads.
 */
class MemoryInventoryFiles(
    private val files: Map<String, String>,
    private val executable: Set<String> = emptySet(),
    directories: Set<String> = emptySet(),
) : InventoryFileSystem<String> {
    private val dirs: Set<String> = buildSet {
        addAll(directories)
        for (path in files.keys + directories) {
            var p = path
            while ('/' in p) {
                p = p.substringBeforeLast('/')
                add(p)
            }
        }
    }

    var yamlLoads = 0
        private set

    override fun name(file: String): String = file.substringAfterLast('/')

    override fun isDirectory(file: String): Boolean = file in dirs

    override fun children(dir: String): List<String> =
        (files.keys + dirs).filter { it.startsWith("$dir/") && '/' !in it.removePrefix("$dir/") }.shuffled(java.util.Random(7))

    override fun exists(file: String): Boolean = file in files || file in dirs

    override fun text(file: String): String? = files[file]

    override fun yaml(file: String): YamlLoad {
        yamlLoads++
        return try {
            YamlLoad.Loaded(YamlText.parse(files.getValue(file)))
        } catch (e: Exception) {
            YamlLoad.Failed(e.message ?: "not YAML", (e as? org.yaml.snakeyaml.error.MarkedYAMLException)?.problemMark?.index)
        }
    }

    override fun isExecutable(file: String): Boolean = file in executable
}
