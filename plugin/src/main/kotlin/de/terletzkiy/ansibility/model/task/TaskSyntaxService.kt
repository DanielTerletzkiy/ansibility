package de.terletzkiy.ansibility.model.task

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProcessCanceledException
import de.terletzkiy.ansibility.runtime.BundledLine
import de.terletzkiy.ansibility.runtime.DocSnapshotStore
import de.terletzkiy.ansibility.semantics.CoreVersion
import java.util.EnumMap

/**
 * Application service that serves the [TaskSyntax] of a target ansible-core version from the bundled doc
 * snapshots (`/ansible-data/core-2.18.8.json.gz` and `/ansible-data/core-latest.json.gz`).
 *
 * The snapshots are not parsed here: each line is taken from [DocSnapshotStore], which the documentation service
 * shares, so a line is parsed once for the whole plugin. Only the compact facts the task model needs are kept, one
 * [TaskSyntax] per line, extracted on first use. The line follows [DocSnapshotStore.lineFor]: the pinned line up to
 * 2.18, the latest line from 2.19, the pinned line for an unknown version (the version every root of the target
 * repo pins). When a snapshot cannot be read (a broken build) the built-in 2.18 keyword table is used.
 */
@Service(Service.Level.APP)
class TaskSyntaxService {
    private val syntaxes = EnumMap<BundledLine, TaskSyntax>(BundledLine::class.java)

    /** The syntax facts for [version] (null: unknown target). */
    fun forVersion(version: CoreVersion?): TaskSyntax = forLine(DocSnapshotStore.lineFor(version))

    /** The syntax facts of the bundled [line]. */
    fun forLine(line: BundledLine): TaskSyntax {
        synchronized(syntaxes) { syntaxes[line] }?.let { return it }
        val loaded = load(line)
        return synchronized(syntaxes) { syntaxes.getOrPut(line) { loaded } }
    }

    private fun load(line: BundledLine): TaskSyntax = try {
        TaskSyntax.fromSnapshot(DocSnapshotStore.getInstance().snapshot(line))
    } catch (e: ProcessCanceledException) {
        throw e
    } catch (e: Exception) {
        LOG.error("Cannot read bundled doc snapshot ${line.resource}; the task model falls back to the built-in keyword table", e)
        TaskSyntax.fallback()
    }

    companion object {
        val PINNED_RESOURCE: String = BundledLine.PINNED.resource
        val LATEST_RESOURCE: String = BundledLine.LATEST.resource
        private val LOG = logger<TaskSyntaxService>()

        fun getInstance(): TaskSyntaxService = service()
    }
}
