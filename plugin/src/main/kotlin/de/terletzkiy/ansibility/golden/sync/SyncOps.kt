package de.terletzkiy.ansibility.golden.sync

import com.intellij.openapi.application.readAction
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Builds [FileOp]s from a [RoleFilePlan] (plan amendment R24): the content is read in the background, so
 * [RoleWriter] gets everything in memory on the EDT.
 */
object SyncOps {
    /** The source of an entry changed since the plan: the operations were not built. */
    class SourceChanged(val relPaths: List<String>) : Exception("source changed: ${relPaths.size} files") {
        override fun fillInStackTrace(): Throwable = this
    }

    /**
     * The source bytes read by one operation over several plans with the same source (Push to many repos, P5): each
     * source file is read once and its bytes are shared by the operations of every root. Never logged; dropped with
     * the operations.
     */
    class SourceBytes {
        internal val bytes = ConcurrentHashMap<Pair<VirtualFile, FileStamp>, ByteArray>()
    }

    /**
     * The operations that make [plan]'s target byte-identical to its source for [RoleFilePlan.included] entries (D189,
     * "mirror"): [PlanKind.CHANGED] → [FileOp.Write], [PlanKind.ONLY_IN_SOURCE] → [FileOp.Create] and, with
     * [deleteExtra], [PlanKind.ONLY_IN_TARGET] → [FileOp.Delete]. Written and created files take the source's
     * executable bit. Each operation carries the entry's target stamp, so a target edited since the plan aborts the
     * write. The source content is what the user sees (an unsaved document as saving would write it); with [shared],
     * a source file another plan of the same operation read already is not read again.
     *
     * Runs on a background dispatcher, one short read action per file. Throws [SourceChanged] when a source file
     * changed since the plan (its stamp differs): compute a new plan.
     */
    suspend fun mirror(project: Project, plan: RoleFilePlan, deleteExtra: Boolean = true, shared: SourceBytes? = null): List<FileOp> =
        withContext(Dispatchers.Default) {
            val changed = ArrayList<String>()
            val ops = ArrayList<FileOp>()
            for (entry in plan.included) {
                ProgressManager.checkCanceled()
                when (entry.kind) {
                    PlanKind.CHANGED, PlanKind.ONLY_IN_SOURCE -> {
                        val bytes = readAction { sourceBytes(project, entry, shared) }
                        if (bytes == null) {
                            changed += entry.relPath
                            continue
                        }
                        ops += if (entry.kind == PlanKind.CHANGED) {
                            FileOp.Write(entry.relPath, OpContent.Bytes(bytes), entry.sourceExecutable, entry.targetStamp)
                        } else {
                            FileOp.Create(entry.relPath, OpContent.Bytes(bytes), entry.sourceExecutable, FileStamp.ABSENT)
                        }
                    }
                    PlanKind.ONLY_IN_TARGET -> if (deleteExtra) ops += FileOp.Delete(entry.relPath, entry.targetStamp)
                }
            }
            if (changed.isNotEmpty()) throw SourceChanged(changed)
            ops
        }

    /** The bytes of [entry]'s source file when it is unchanged since the plan, else null. Read action. */
    private fun sourceBytes(project: Project, entry: PlanEntry, shared: SourceBytes?): ByteArray? {
        val source = entry.sourceFile ?: return null
        if (FileStamp.of(source) != entry.sourceStamp) return null
        val key = source to entry.sourceStamp
        shared?.bytes?.get(key)?.let { return it }
        val bytes = RoleFiles.bytes(project, source) ?: return null
        shared?.bytes?.put(key, bytes)
        return bytes
    }
}
