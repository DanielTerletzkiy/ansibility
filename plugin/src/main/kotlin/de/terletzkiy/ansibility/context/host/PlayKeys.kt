package de.terletzkiy.ansibility.context.host

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.PlayGraph
import de.terletzkiy.ansibility.api.PlayRef

/**
 * The stored form of a selected play (plan amendment R7/R8, "Selection rules"): `<playbook path relative to the
 * root>#<play index>`, e.g. `playbook-setup-system.yml#4`. A play imported into a nested root from its parent keeps
 * its own file, so its path may climb out of the root (`../../playbook-setup-replisync.yml#0`).
 */
object PlayKeys {
    private const val SEPARATOR = '#'

    /** The key of [play] as stored for [root]. */
    fun of(root: AnsibleRoot, play: PlayRef): String {
        val path = FileUtil.getRelativePath(root.dir.path, play.file.path, '/') ?: play.file.path
        return "$path$SEPARATOR${play.playIndex}"
    }

    /** The play [key] names in [root], or null when the file or the play no longer exists (or [key] is malformed). */
    fun resolve(project: Project, root: AnsibleRoot, key: String): PlayRef? {
        val at = key.lastIndexOf(SEPARATOR)
        if (at <= 0) return null
        val index = key.substring(at + 1).toIntOrNull()?.takeIf { it >= 0 } ?: return null
        val path = key.substring(0, at)
        val file = root.dir.findFileByRelativePath(path)?.takeIf { it.isValid && !it.isDirectory } ?: return null
        return PlayGraph.getInstance(project).playsOf(file).getOrNull(index)?.ref
    }
}
