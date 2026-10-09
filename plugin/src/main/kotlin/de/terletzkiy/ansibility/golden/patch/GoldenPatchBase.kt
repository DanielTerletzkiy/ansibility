package de.terletzkiy.ansibility.golden.patch

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.toNioPathOrNull
import de.terletzkiy.ansibility.golden.remote.GoldenMirrorState
import de.terletzkiy.ansibility.golden.remote.GoldenMirrors
import de.terletzkiy.ansibility.model.role.RoleCopy
import java.io.IOException
import java.nio.file.Path
import kotlin.io.path.invariantSeparatorsPathString

/**
 * Where a patch for golden is applied (plan amendment R25, X126): the directory its paths are relative to, and the
 * golden role directory's path inside it ([prefix], "roles/web"), so a patch line reads `a/roles/web/tasks/main.yml`.
 *
 * [location] names the repository for the user, in the dialog and in the patch's note: the golden root's name
 * ("golden"; never a local path, since a patch is shared), or the mirrored repository ("git@…:infra/golden.git (main)",
 * the mirror's own UI text, never with credentials).
 */
data class GoldenPatchBase(val prefix: String, val location: String) {
    /** [relPath] (inside the role) as a path of the golden repository. */
    fun pathOf(relPath: String): String = if (prefix.isEmpty()) relPath else "$prefix/$relPath"

    companion object {
        /**
         * The golden base directory of a role copy whose golden copy is [golden]:
         * - an external golden root (a git mirror or a folder outside the project, R25: [GoldenMirrors.state]) when the
         *   golden copy lies below its top directory (`baseDir`, the repository's top), so the paths are the
         *   repository's own;
         * - else the golden copy's root directory ([RoleCopy.root]): a local golden root.
         *
         * The mirror's state is a snapshot; the disk is read only to resolve links when the plain paths do not nest.
         * Background thread (the action's `update` does not call it).
         */
        fun of(project: Project, golden: RoleCopy): GoldenPatchBase {
            external(project, golden)?.let { return it }
            val prefix = VfsUtilCore.getRelativePath(golden.dir, golden.root.dir, '/').orEmpty()
            return GoldenPatchBase(prefix, golden.root.displayName)
        }

        /** The external golden root's base when [golden] lies below it, else null. */
        private fun external(project: Project, golden: RoleCopy): GoldenPatchBase? {
            if (project.isDisposed) return null
            val state = GoldenMirrors.getInstance(project).state() ?: return null
            val base = state.baseDir ?: return null
            val dir = golden.dir.toNioPathOrNull() ?: return null
            val prefix = relative(base, dir) ?: return null
            // A folder's source is a local path: the note names the folder only (a patch is shared).
            return GoldenPatchBase(prefix, if (state.kind == GoldenMirrorState.Kind.GIT) state.source else state.name)
        }

        /** [dir]'s path below [base] with `/` separators, also when one of them is reached through a link; null outside. */
        internal fun relative(base: Path, dir: Path): String? {
            val plain = base.normalize()
            val normalized = dir.normalize()
            if (normalized.startsWith(plain)) return plain.relativize(normalized).invariantSeparatorsPathString
            return try {
                val realBase = plain.toRealPath()
                val realDir = normalized.toRealPath()
                if (realDir.startsWith(realBase)) realBase.relativize(realDir).invariantSeparatorsPathString else null
            } catch (_: IOException) {
                null
            }
        }
    }
}
