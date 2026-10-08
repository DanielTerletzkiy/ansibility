package de.terletzkiy.ansibility.vault.envelope

import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import de.terletzkiy.ansibility.index.PathFacts
import de.terletzkiy.ansibility.index.PathHint
import de.terletzkiy.ansibility.index.vault.VaultIndex
import de.terletzkiy.ansibility.semantics.vault.ShapeContext
import de.terletzkiy.ansibility.semantics.vault.VaultFileShape
import java.io.IOException

/**
 * The IDE side of ANS-V107 (plan amendment R21, D159): what a file's path says about how Ansible reads it
 * ([contextOf]), and the shape of a file from its first bytes ([ofHead]) for callers without a document (Encrypt File's
 * guard). The verdicts are `semantics.vault.VaultFileShape`'s; nothing here decrypts or keeps any content.
 */
object WholeFileShapes {
    /** The bytes [ofHead] reads: enough for the envelope's header and the first payload lines of any wrapped vault. */
    const val HEAD_BYTES: Int = 16 * 1024

    /** Names Ansible reads as they are (copied, rendered, read by a lookup): keys, certificates, vaults, templates. */
    private val RAW_SUFFIXES = listOf(".key", ".pem", ".crt", ".vault", ".password", ".j2")

    /** Documentation Ansible never loads or decrypts: an envelope in it is an example (`ShapeContext.documentation`). */
    private val DOCUMENTATION_SUFFIXES = listOf(".md", ".markdown", ".rst", ".adoc", ".asciidoc")

    /** Directories whose files Ansible copies or renders as they are (`copy`, `template` sources). */
    private val RAW_DIRECTORIES = setOf("files", "templates")

    private const val MAX_DEPTH = 40

    /**
     * How Ansible reads [file], from its path: as YAML (`.yml`/`.yaml`, also a `.yml.j2` template, vars files without
     * one) and as it is (below `files` or `templates` inside its content root, a template, a key-like name).
     * [byteOrderMark] tells whether the text the shape is computed from lost a byte order mark (an IDE document's
     * text); pass false for raw bytes.
     */
    fun contextOf(project: Project, file: VirtualFile, byteOrderMark: Boolean = file.bom != null): ShapeContext {
        val facts = PathFacts.of(file)
        return ShapeContext(
            yamlInput = isYamlInput(file, facts), readRaw = readsRaw(project, file, facts), byteOrderMark = byteOrderMark,
            documentation = isDocumentation(file.name),
        )
    }

    /**
     * The path-only [ShapeContext] of [file] for indexers (plan amendment R21, D164; DEV.md rule 5): as [contextOf], but
     * `files` and `templates` directories count at any depth, not only below the content root, so
     * [ShapeContext.readRaw] is a superset of [readsRaw]. Only the QUOTED, MIXED and YAML_VALUE shapes depend on it: the
     * monitoring confirms them with [readsRaw] at query time. No byte order mark: the raw bytes an indexer reads carry
     * their own.
     */
    fun pathContextOf(file: VirtualFile): ShapeContext {
        val facts = PathFacts.of(file)
        return ShapeContext(
            yamlInput = isYamlInput(file, facts), readRaw = readsRawByPath(file, facts), byteOrderMark = false,
            documentation = isDocumentation(file.name),
        )
    }

    /**
     * [pathContextOf] for an absolute `/`-separated [path] that may have no file in the working tree (the commit check:
     * a file staged and then deleted). Pure.
     */
    fun pathContextOf(path: String): ShapeContext {
        val segments = path.split('/').filter { it.isNotEmpty() }
        val name = segments.lastOrNull().orEmpty()
        val facts = PathFacts.of(path)
        val raw = readsRawByName(name, facts) || segments.dropLast(1).takeLast(MAX_DEPTH).any { it in RAW_DIRECTORIES }
        return ShapeContext(yamlInput = isYamlInput(name, facts), readRaw = raw, byteOrderMark = false, documentation = isDocumentation(name))
    }

    /** True for a documentation file name (Markdown, reStructuredText, AsciiDoc). */
    private fun isDocumentation(name: String): Boolean {
        val lower = name.lowercase()
        return DOCUMENTATION_SUFFIXES.any(lower::endsWith)
    }

    /** True when Ansible loads [file] as YAML: a YAML input of the indexes, or a `.yml.j2` / `.yaml.j2` template. */
    private fun isYamlInput(file: VirtualFile, facts: PathFacts): Boolean = isYamlInput(file.name, facts)

    private fun isYamlInput(name: String, facts: PathFacts): Boolean =
        VaultIndex.isYamlInput(facts) || PathFacts.isYamlName(name.removeSuffix(".j2"))

    /** True when Ansible uses [file] as it is; directories count up to the file's content root, never above it. */
    fun readsRaw(project: Project, file: VirtualFile, facts: PathFacts = PathFacts.of(file)): Boolean {
        if (readsRawByName(file, facts)) return true
        val stop = ProjectFileIndex.getInstance(project).getContentRootForFile(file)
        var dir = file.parent
        var depth = 0
        while (dir != null && dir != stop && depth++ < MAX_DEPTH) {
            if (dir.name in RAW_DIRECTORIES) return true
            dir = dir.parent
        }
        return false
    }

    /** [readsRaw] from the path alone: `files` and `templates` directories at any depth (pure, for indexers). */
    private fun readsRawByPath(file: VirtualFile, facts: PathFacts): Boolean {
        if (readsRawByName(file, facts)) return true
        var dir = file.parent
        var depth = 0
        while (dir != null && depth++ < MAX_DEPTH) {
            if (dir.name in RAW_DIRECTORIES) return true
            dir = dir.parent
        }
        return false
    }

    private fun readsRawByName(file: VirtualFile, facts: PathFacts): Boolean = readsRawByName(file.name, facts)

    private fun readsRawByName(name: String, facts: PathFacts): Boolean {
        if (facts.hint == PathHint.FILES || facts.hint == PathHint.TEMPLATE) return true
        val lower = name.lowercase()
        return RAW_SUFFIXES.any(lower::endsWith)
    }

    /**
     * The shape of [file] from its first [HEAD_BYTES] bytes (a byte order mark is read from them), or null when it holds
     * no envelope ANS-V107 reports or cannot be read. A verdict only: no unwrapped text, no envelope parse. Read action.
     */
    fun ofHead(project: Project, file: VirtualFile): VaultFileShape? {
        if (file.isDirectory || !file.isValid || file.length == 0L) return null
        val head = try {
            VfsUtilCore.loadNBytes(file, HEAD_BYTES)
        } catch (_: IOException) {
            return null
        }
        val complete = head.size.toLong() >= file.length
        return VaultFileShape.classify(head, contextOf(project, file, byteOrderMark = false), complete = complete, withEnvelope = false)
    }
}
