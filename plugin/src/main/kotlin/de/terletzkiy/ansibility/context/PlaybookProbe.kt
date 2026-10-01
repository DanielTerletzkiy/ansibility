package de.terletzkiy.ansibility.context

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.vfs.VirtualFile
import java.io.IOException

/**
 * The cheap content probe used when a path alone cannot tell a playbook from another YAML file (plan A.5):
 * a playbook is a top-level sequence whose items carry `hosts:` or `import_playbook:` at item level.
 *
 * It is a text scan, not a YAML parse, over the first [MAX_BYTES] of the file. Task files (also top-level
 * sequences) never carry these keys at item level, and vars files are mappings.
 */
object PlaybookProbe {
    const val MAX_BYTES: Int = 16 * 1024

    private val PLAY_KEYS = setOf("hosts", "import_playbook", "ansible.builtin.import_playbook")
    private val KEY = Regex("""^([A-Za-z_][\w.]*)\s*:(\s|$)""")
    private val LOG = logger<PlaybookProbe>()

    /** Whether [text] looks like a list of plays. */
    fun looksLikePlaybook(text: CharSequence): Boolean {
        var itemKeyColumn = -1
        for (line in text.lineSequence()) {
            val trimmed = line.trimStart()
            if (trimmed.isEmpty() || trimmed.startsWith('#')) continue
            if (itemKeyColumn < 0) {
                if (trimmed.startsWith("---") || trimmed.startsWith("%")) continue
                if (!line.startsWith("-")) return false
                itemKeyColumn = 1 + line.drop(1).takeWhile { it == ' ' }.length
            }
            val key = when {
                line.startsWith("-") -> line.drop(1).trimStart(' ')
                line.length > itemKeyColumn && line.take(itemKeyColumn).isBlank() && line[itemKeyColumn] != ' ' ->
                    line.substring(itemKeyColumn)
                else -> continue
            }
            val name = KEY.find(key)?.groupValues?.get(1) ?: continue
            if (name in PLAY_KEYS) return true
        }
        return false
    }

    /** Reads at most [MAX_BYTES] of [file] from the VFS and probes them; unreadable files are not playbooks. */
    fun looksLikePlaybook(file: VirtualFile): Boolean {
        if (file.isDirectory || !file.isValid) return false
        return try {
            val bytes = file.inputStream.use { it.readNBytes(MAX_BYTES) }
            looksLikePlaybook(String(bytes, Charsets.UTF_8))
        } catch (e: IOException) {
            LOG.debug("Cannot probe ${file.path}", e)
            false
        }
    }
}
