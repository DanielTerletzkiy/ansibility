package de.terletzkiy.ansibility.render.lookup

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import de.terletzkiy.ansibility.semantics.render.Loaded
import de.terletzkiy.ansibility.semantics.render.LookupResolver
import de.terletzkiy.ansibility.semantics.render.RValue
import de.terletzkiy.ansibility.semantics.render.RValues
import de.terletzkiy.ansibility.semantics.render.TemplateLoader
import java.io.IOException

/**
 * The search path of one render (golden 12): the running role's directory, then the playbook directory. Files are
 * read through the VFS only and only inside [fence] (the root's directories), never a vault file, never above
 * [MAX_BYTES]; open documents give their unsaved text.
 */
internal class SearchPath(
    private val roleDir: VirtualFile?,
    private val playbookDir: VirtualFile?,
    private val fence: List<VirtualFile>,
) {
    /** `fileglob`/`first_found` bases: `files/` before the directory itself, role before playbook. */
    private val fileBases: List<VirtualFile> = listOfNotNull(
        roleDir?.findChild("files"), roleDir, roleDir?.findFileByRelativePath("tasks/files"), roleDir?.findChild("tasks"),
        playbookDir?.findChild("files"), playbookDir,
    ).filter { it.isDirectory }

    private val templateBases: List<VirtualFile> = listOfNotNull(
        roleDir?.findChild("templates"), roleDir, playbookDir?.findChild("templates"), playbookDir,
    ).filter { it.isDirectory }

    fun inside(file: VirtualFile): Boolean = fence.any { VfsUtilCore.isAncestor(it, file, false) }

    /** `fileglob`: the absolute local paths of the regular files matching one pattern, sorted. */
    fun fileglob(pattern: String): List<String> {
        val slash = pattern.lastIndexOf('/')
        val dirPart = if (slash < 0) null else pattern.substring(0, slash)
        val namePart = pattern.substring(slash + 1)
        val regex = Glob.regex(namePart)
        fun matches(dir: VirtualFile): List<String> = dir.children
            .filter { !it.isDirectory && regex.matches(it.name) && (namePart.startsWith(".") || !it.name.startsWith(".")) && inside(it) }
            .map { it.path }
            .sorted()
        if (pattern.startsWith("/")) {
            val dir = local(dirPart.orEmpty().ifEmpty { "/" }) ?: return emptyList()
            return if (inside(dir)) matches(dir) else emptyList()
        }
        if (dirPart != null) {
            val dir = fileBases.firstNotNullOfOrNull { it.findFileByRelativePath(dirPart)?.takeIf(VirtualFile::isDirectory) } ?: return emptyList()
            return matches(dir)
        }
        return fileBases.asSequence().map(::matches).firstOrNull { it.isNotEmpty() }.orEmpty()
    }

    /** `first_found`: the first existing file of [names] (each tried on [paths] when given, else on the search path). */
    fun firstFound(names: List<String>, paths: List<String>): String? {
        for (name in names) {
            if (name.startsWith("/")) local(name)?.takeIf { !it.isDirectory && inside(it) }?.let { return it.path }
            val bases = if (paths.isEmpty()) fileBases else paths.mapNotNull { path -> if (path.startsWith("/")) local(path) else fileBases.firstNotNullOfOrNull { it.findFileByRelativePath(path) } }
            for (base in bases) {
                base.findFileByRelativePath(name)?.takeIf { !it.isDirectory && inside(it) }?.let { return it.path }
            }
        }
        return null
    }

    /** The template [name] for `include`/`import`, searched from the template [from] (a file URL; null: the rendered template's own directory first). */
    fun template(name: String, from: VirtualFile?): VirtualFile? {
        if (name.startsWith("/")) return local(name)?.takeIf { !it.isDirectory }
        val own = from?.parent
        return (listOfNotNull(own) + templateBases).firstNotNullOfOrNull { base -> base.findFileByRelativePath(name)?.takeIf { !it.isDirectory } }
    }

    fun resolver(): LookupResolver = LookupResolver { plugin, terms, kwargs ->
        val strings = terms.flatMap { RValues.sequence(it) ?: listOf(it) }.map { (it as? RValue.Str)?.value ?: return@LookupResolver null }
        when (plugin) {
            "fileglob" -> RValue.List(strings.flatMap(::fileglob).mapTo(ArrayList()) { RValue.Str(it) })
            "first_found" -> {
                val files = (kwargs["files"]?.let { RValues.sequence(it) ?: listOf(it) }.orEmpty()).mapNotNull { (it as? RValue.Str)?.value }
                val paths = (kwargs["paths"]?.let { RValues.sequence(it) ?: listOf(it) }.orEmpty()).mapNotNull { (it as? RValue.Str)?.value }
                firstFound(strings + files, paths)?.let { RValue.List(mutableListOf(RValue.Str(it))) }
            }
            else -> null
        }
    }

    fun loader(top: VirtualFile): TemplateLoader = TemplateLoader { name, from ->
        val origin = from?.let { VirtualFileManager.getInstance().findFileByUrl(it) } ?: top
        val file = template(name, origin) ?: return@TemplateLoader Loaded.Missing(listOf(name))
        read(file)
    }

    companion object {
        const val MAX_BYTES: Int = 256 * 1024
        private const val VAULT_HEADER = "\$ANSIBLE_VAULT"

        private fun local(path: String): VirtualFile? = VirtualFileManager.getInstance().findFileByUrl("file://$path")

        /** The text of [file] for rendering: unsaved text when open; refused when too large or a vault file. */
        fun read(file: VirtualFile): Loaded {
            if (file.length > MAX_BYTES) return Loaded.Refused("larger than ${MAX_BYTES / 1024} KiB")
            val text = FileDocumentManager.getInstance().getCachedDocument(file)?.text ?: try {
                VfsUtilCore.loadText(file)
            } catch (e: IOException) {
                return Loaded.Refused(e.message ?: "unreadable")
            }
            if (text.startsWith(VAULT_HEADER)) return Loaded.Refused("a vault file")
            return Loaded.Found(file.url, text)
        }
    }
}

/** Python `fnmatch` for one path component: `*`, `?`, `[...]` and `[!...]`. */
internal object Glob {
    fun regex(pattern: String): Regex {
        val out = StringBuilder()
        var i = 0
        while (i < pattern.length) {
            when (val c = pattern[i]) {
                '*' -> out.append("[^/]*")
                '?' -> out.append("[^/]")
                '[' -> {
                    val close = pattern.indexOf(']', i + 2)
                    if (close < 0) {
                        out.append("\\[")
                    } else {
                        var body = pattern.substring(i + 1, close)
                        val negate = body.startsWith("!")
                        if (negate) body = body.substring(1)
                        out.append('[').append(if (negate) "^" else "").append(body.replace("\\", "\\\\").replace("[", "\\[")).append(']')
                        i = close
                    }
                }
                else -> out.append(Regex.escape(c.toString()))
            }
            i++
        }
        return Regex(out.toString())
    }
}
