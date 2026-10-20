package de.terletzkiy.ansibility.settings.nested

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.JDOMUtil
import com.intellij.openapi.util.ModificationTracker
import com.intellij.openapi.util.SimpleModificationTracker
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import de.terletzkiy.ansibility.settings.RootKeys
import com.intellij.util.xmlb.Accessor
import com.intellij.util.xmlb.SerializationFilter
import com.intellij.util.xmlb.XmlSerializer
import org.jdom.Element
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-root settings committed inside a sub-repository (plan amendment R27): when a root lives in a directory below the
 * project directory that has its own `.idea/` (a submodule opened in the super-repo, `repos/platform`), that
 * directory's `.idea/ansibility-layout.xml` and `.idea/ansibility-vault.xml` hold the root's shared settings with keys
 * relative to the sub-repository (`ansible`), exactly as when the sub-repository is opened on its own. This service
 * finds that directory ([repoOf]), reads a component of such a file with its keys re-based to the project's
 * [RootKeys] (`repos/platform/ansible`) and writes one back, keeping every other component of the file.
 *
 * The project's own `.idea` is never a sub-repository: the platform stores it. Reads are cached by file stamp; any
 * change to such a file (also from git) bumps [modificationTracker] and notifies [listeners].
 */
@Service(Service.Level.PROJECT)
class NestedRepoSettings(private val project: Project) : Disposable {
    /** A sub-repository directory and its key relative to the project directory (`repos/platform`). */
    data class Repo(val dir: VirtualFile, val prefix: String) {
        /** `.idea/<name>` of this repository, as the project shows it (`repos/platform/.idea/ansibility-layout.xml`). */
        fun display(fileName: String): String = "$prefix/$IDEA/$fileName"
    }

    private class Parsed(val stamp: Long, val component: Map<String, Element>)

    private val tracker = SimpleModificationTracker()
    private val cache = ConcurrentHashMap<String, Parsed>()
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<(fileName: String) -> Unit>()

    val modificationTracker: ModificationTracker get() = tracker

    init {
        project.messageBus.connect(this).subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                val changed = events.mapNotNullTo(HashSet()) { event -> event.path.takeIf(::isNestedSettingsPath)?.substringAfterLast('/') }
                if (changed.isEmpty()) return
                cache.clear()
                tracker.incModificationCount()
                for (name in changed) listeners.forEach { it(name) }
            }
        })
    }

    /** Calls [listener] with the file name (`ansibility-layout.xml`) whenever such a file of a sub-repository changes. */
    fun addListener(listener: (fileName: String) -> Unit) {
        listeners += listener
    }

    /** The sub-repository of the root stored under [rootKey]: the nearest directory with `.idea/`, below the project directory. */
    fun repoOf(rootKey: String): Repo? {
        if (rootKey == RootKeys.PROJECT_DIR || rootKey.startsWith("/")) return null
        val base = RootKeys.projectDir(project) ?: return null
        val segments = rootKey.split('/')
        for (n in segments.size downTo 1) {
            val prefix = segments.take(n).joinToString("/")
            val dir = base.findFileByRelativePath(prefix) ?: continue
            if (dir.isDirectory && dir.findChild(IDEA)?.isDirectory == true) return Repo(dir, prefix)
        }
        return null
    }

    /** The key [rootKey] has in its sub-repository's files (`ansible`; `.` for the sub-repository itself). */
    fun localKey(repo: Repo, rootKey: String): String =
        if (rootKey == repo.prefix) RootKeys.PROJECT_DIR else rootKey.removePrefix(repo.prefix + "/")

    /** The project key of [localKey] in [repo]'s files. */
    fun projectKey(repo: Repo, localKey: String): String =
        if (localKey == RootKeys.PROJECT_DIR || localKey.isEmpty()) repo.prefix else "${repo.prefix}/$localKey"

    /** The `<component name=[component]>` element of [repo]'s `.idea/[fileName]`, or null when there is none. */
    fun read(repo: Repo, fileName: String, component: String): Element? {
        val file = repo.dir.findChild(IDEA)?.findChild(fileName) ?: return null
        val stamp = file.modificationStamp + file.timeStamp
        val parsed = cache[file.path]?.takeIf { it.stamp == stamp } ?: parse(file, stamp).also { cache[file.path] = it }
        return parsed.component[component]
    }

    /**
     * Replaces the `<component name=[component]>` of [repo]'s `.idea/[fileName]` with [state] (its children and
     * attributes), or removes it when [state] is null; creates the file when needed, deletes nothing else. Runs in a
     * write action on the EDT (waits for it from another thread).
     */
    fun write(repo: Repo, fileName: String, component: String, state: Element?) {
        val action = Runnable {
            val idea = repo.dir.findChild(IDEA) ?: return@Runnable
            val existing = idea.findChild(fileName)
            val root = existing?.let { runCatching { JDOMUtil.load(it.inputStream) }.getOrNull() } ?: Element("project").setAttribute("version", "4")
            val old = root.getChildren("component").firstOrNull { it.getAttributeValue("name") == component }
            val replacement = state?.let { s ->
                Element("component").setAttribute("name", component).also { e ->
                    s.attributes.forEach { a -> if (a.name != "name") e.setAttribute(a.name, a.value) }
                    s.children.toList().forEach { child -> e.addContent(child.clone()) }
                }
            }
            when {
                old != null && replacement != null -> root.setContent(root.indexOf(old), replacement)
                old != null -> root.removeContent(old)
                replacement != null -> root.addContent(replacement)
                else -> return@Runnable
            }
            if (existing == null && root.children.isEmpty()) return@Runnable
            val text = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" + JDOMUtil.write(root, "\n") + "\n"
            val target = existing ?: idea.createChildData(this, fileName)
            VfsUtil.saveText(target, text)
        }
        val app = ApplicationManager.getApplication()
        try {
            if (app.isDispatchThread) WriteAction.run<RuntimeException> { action.run() } else WriteAction.runAndWait<RuntimeException> { action.run() }
        } catch (e: java.io.IOException) {
            LOG.warn("Cannot write ${repo.display(fileName)}", e)
        }
        cache.clear()
        tracker.incModificationCount()
    }

    private fun parse(file: VirtualFile, stamp: Long): Parsed {
        val root = runCatching { JDOMUtil.load(file.inputStream) }.getOrNull() ?: return Parsed(stamp, emptyMap())
        val components = root.getChildren("component").mapNotNull { e -> e.getAttributeValue("name")?.let { it to e } }.toMap()
        return Parsed(stamp, components)
    }

    private fun isNestedSettingsPath(path: String): Boolean {
        val name = path.substringAfterLast('/')
        if (name !in FILES) return false
        if (path.substringBeforeLast('/').substringAfterLast('/') != IDEA) return false
        val base = project.basePath ?: return true
        return path != "$base/$IDEA/$name"
    }

    override fun dispose() = Unit

    companion object {
        const val IDEA: String = ".idea"
        const val LAYOUT_FILE: String = "ansibility-layout.xml"
        const val VAULT_FILE: String = "ansibility-vault.xml"
        private val FILES = setOf(LAYOUT_FILE, VAULT_FILE)
        private val LOG = logger<NestedRepoSettings>()

        fun getInstance(project: Project): NestedRepoSettings = project.service()

        /**
         * [bean] as XML without the properties that hold their default value (as the platform writes `.idea` files),
         * so a file written here looks like one the IDE wrote when the sub-repository is opened alone.
         */
        fun serialize(bean: Any): Element = XmlSerializer.serialize(bean, SkipDefaults())

        /** Skips a property whose value equals the one of a new instance of its bean class (public-API filter). */
        private class SkipDefaults : SerializationFilter {
            private val defaults = HashMap<Class<*>, Any?>()

            override fun accepts(accessor: Accessor, bean: Any): Boolean {
                val default = defaults.getOrPut(bean.javaClass) { runCatching { bean.javaClass.getDeclaredConstructor().newInstance() }.getOrNull() }
                    ?: return true
                return accessor.read(bean) != accessor.read(default)
            }
        }
    }
}
