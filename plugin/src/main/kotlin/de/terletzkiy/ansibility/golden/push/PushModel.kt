package de.terletzkiy.ansibility.golden.push

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.readAction
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightVirtualFile
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.VaultHeaderInfo
import de.terletzkiy.ansibility.api.WorkspaceScopeService
import de.terletzkiy.ansibility.golden.AnsibilityGoldenBundle.message
import de.terletzkiy.ansibility.golden.history.LocalChangesLookup
import de.terletzkiy.ansibility.golden.sync.PlanKind
import de.terletzkiy.ansibility.golden.sync.PlanOptions
import de.terletzkiy.ansibility.golden.sync.RoleFilePlan
import de.terletzkiy.ansibility.golden.sync.RoleFiles
import de.terletzkiy.ansibility.golden.sync.RoleLinks
import de.terletzkiy.ansibility.model.drift.DriftTexts
import de.terletzkiy.ansibility.model.drift.RoleDriftService
import de.terletzkiy.ansibility.model.role.RoleCatalog
import de.terletzkiy.ansibility.model.role.RoleCatalogSnapshot
import de.terletzkiy.ansibility.model.role.RoleCopy
import de.terletzkiy.ansibility.settings.AnsibilityProjectSettings
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext
import org.jetbrains.annotations.Nls
import java.io.IOException

/** Where a row of the Push dialog stands (plan amendment R24, D188). */
enum class PushSection {
    /** A copy of the role inside the workspace scope (listed first). */
    IN_SCOPE,

    /** A copy of the role outside the workspace scope ("Outside scope (n)"). */
    OUTSIDE_SCOPE,

    /** A root with a roles directory but no copy of the role ("Roots without web (n)"): a push creates the role. */
    WITHOUT_ROLE,
}

/** The options of a push (D189 mirror, D191 sensitive files). */
data class PushOptions(
    /** "Delete files the source does not have" (on by default: the copies really match). */
    val deleteExtra: Boolean = true,
    /** "Include key and vault files" (off by default: such files are left as they are, D191). */
    val includeSensitive: Boolean = false,
)

/** What a push does to one root, with [PushOptions]. */
data class PushCounts(val changed: Int, val added: Int, val deleted: Int, val excluded: Int) {
    /** Whether the push changes nothing there. */
    val isEmpty: Boolean get() = changed + added + deleted == 0
}

/** A whole-file vault whose header names another vault id in the source than in the target (header lines only). */
data class VaultIdMismatch(val relPath: String, val sourceId: String, val targetId: String)

/**
 * One root the Push dialog offers (plan amendment R24, D188): a copy of the role, or a root without one. Facts only;
 * the plan holds no content.
 */
class PushRow internal constructor(
    val section: PushSection,
    val root: AnsibleRoot,
    /** The copy of the role in [root], or null for [PushSection.WITHOUT_ROLE]. */
    val copy: RoleCopy?,
    /** [PushSection.WITHOUT_ROLE]: the roles directory the role is created in (`AnsibleRoot.rolesDirs.first()`). */
    val rolesDir: VirtualFile?,
    /** The role name. */
    val roleName: String,
    /** The drift badge of the copy ("Δ tasks/templates", "= golden") when the drift is known, else null. */
    @get:Nls val badge: String?,
    /** The plan from the source to the copy, or to an empty target for a root without the role (creations only). */
    val plan: RoleFilePlan,
    /** The copy has local VCS changes (they would be overwritten); false when unknown. */
    val uncommitted: Boolean,
    /** Whole-file vaults whose header carries another vault id than the source's. */
    val vaultIdMismatches: List<VaultIdMismatch>,
    /**
     * Where the copy (or the roles directory of a root without the role) really is when it is, or lies below, a
     * symbolic link (`RoleLinks.linkedTo`); null for a plain directory. Such a row is shown "linked to …" and cannot be
     * ticked: a push never writes through a link (D191).
     */
    val linkedTo: String? = null,
) {
    /** The root's display name ("falcon"). */
    val name: String get() = root.displayName

    /** Whether a push creates the role here. */
    val createsRole: Boolean get() = copy == null

    /** `roles/web`: where a push to a root without the role creates it, relative to the root. */
    val createdPath: String
        get() = rolesDir?.let { dir -> VfsUtilCore.getRelativePath(dir, root.dir, '/')?.let { "$it/$roleName" } } ?: roleName

    /** What a push with [options] does here. */
    fun counts(options: PushOptions): PushCounts {
        var changed = 0
        var added = 0
        var deleted = 0
        var excluded = 0
        for (entry in plan.entries) {
            if (entry.excluded && !options.includeSensitive) {
                excluded++
                continue
            }
            when (entry.kind) {
                PlanKind.CHANGED -> changed++
                PlanKind.ONLY_IN_SOURCE -> added++
                PlanKind.ONLY_IN_TARGET -> if (options.deleteExtra) deleted++
            }
        }
        return PushCounts(changed, added, deleted, excluded)
    }

    /** Whether the row can be ticked: no link on the way to it ([linkedTo]). */
    val pushable: Boolean get() = linkedTo == null

    /**
     * Whether this is a copy a push with [options] would change ("Select differing"; roots without the role and
     * linked copies never).
     */
    fun differs(options: PushOptions): Boolean = copy != null && pushable && !counts(options).isEmpty

    /** "3 changed · 2 added · 1 deleted", "nothing to do", or for a root without the role "creates roles/web, 12 files". */
    @Nls
    fun countsText(options: PushOptions): String {
        val counts = counts(options)
        val main = when {
            createsRole -> message("push.counts.create", createdPath, counts.added)
            counts.isEmpty -> message("push.counts.nothing")
            else -> listOfNotNull(
                counts.changed.takeIf { it > 0 }?.let { message("writer.summary.written", it) },
                counts.added.takeIf { it > 0 }?.let { message("writer.summary.created", it) },
                counts.deleted.takeIf { it > 0 }?.let { message("writer.summary.deleted", it) },
            ).joinToString(SEPARATOR)
        }
        val withExcluded = if (counts.excluded > 0) main + SEPARATOR + message("push.counts.excluded", counts.excluded) else main
        val conflicts = plan.caseConflicts.size
        return if (conflicts > 0) withExcluded + SEPARATOR + message("push.counts.caseConflicts", conflicts) else withExcluded
    }

    override fun toString(): String = "PushRow($name, $section${if (createsRole) ", creates $createdPath" else ""})"

    private companion object {
        const val SEPARATOR = " · "
    }
}

/** What the Push dialog returns: the ticked rows (in dialog order) and the options. */
class PushChoice(val rows: List<PushRow>, val options: PushOptions) {
    override fun toString(): String = "PushChoice(${rows.map { it.name }}, $options)"
}

/**
 * The Push dialog's model (plan amendment R24, D188): the source copy and its rows, computed in the background
 * ([rows] completes when they are known; the dialog shows a loading state until then).
 */
class PushModel internal constructor(val project: Project, val source: RoleCopy, val rows: Deferred<List<PushRow>>) {
    /** The role name ("web"). */
    val roleName: String get() = source.name

    /** The source's root ("golden"). */
    val sourceName: String get() = source.root.displayName

    /** The rows when computed, else null (still loading, failed or cancelled). */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun rowsIfReady(): List<PushRow>? = if (rows.isCompleted && rows.getCompletionExceptionOrNull() == null) rows.getCompleted() else null
}

/**
 * The rows of the Push dialog (plan amendment R24, D188): every other copy of the role (in-scope first, then outside
 * the scope, each in catalog order), then the non-detached roots that have a roles directory but no copy of the role.
 * Detached worktrees never (the catalog skips them), the source's own copy never.
 *
 * Plans and badges are computed when the dialog opens, from the files and unsaved documents as they are then, so a
 * push right after Align (its notification's Push to Repos…) offers the other copies with counts and badges that
 * already include what Align wrote.
 */
object PushRows {
    private val LOG = logger<PushRows>()

    /**
     * An empty target for the plan of a root without the role: a file that is no directory, which the walk of
     * `RoleFiles` reads as "no files", so every source file is [PlanKind.ONLY_IN_SOURCE]. Never written.
     */
    internal val EMPTY_TARGET: VirtualFile = LightVirtualFile("ansibility-push-empty-role")

    /** The rows of [source]; on a background dispatcher, in read actions, cancellable. */
    suspend fun compute(project: Project, source: RoleCopy): List<PushRow> = withContext(Dispatchers.Default) {
        val catalog = readAction { RoleCatalog.getInstance(project).snapshot() }
        // R25, D198: the external golden root is never a target (pushing FROM it works).
        val others = catalog.copies(source.name).filter { it.dir != source.dir && it.dir.isValid && !it.isExternal }
        val scope = WorkspaceScopeService.getInstance(project).current()
        val inScope = readAction { others.map { scope.contains(it.dir) } }
        // U4: a push mirrors molecule/ too, whatever "Ignore molecule/ in drift" says (that setting is drift's only).
        val options = PlanOptions(ignoreMolecule = false, includeSensitive = false)
        // The badges as of now, like the counts: the cached drift may predate the edits just made (a push right after
        // Align, from its notification). Only when the source is the golden copy (U12): a badge says how a copy
        // differs from golden, the counts how it differs from the source, and the two must say the same thing.
        val drift = if (catalog.golden != null && source.isReference) RoleDriftService.getInstance(project).drift(source.name) else null
        val copies = others.mapIndexed { index, copy ->
            val plan = RoleFilePlan.compute(project, source, copy, options)
            val badge = drift?.let { d -> d.copyOf(copy.dir)?.let { DriftTexts.badge(d, it) } }
            val uncommitted = withContext(Dispatchers.IO) { LocalChangesLookup.hasLocalChanges(project, copy.dir) } == true
            val section = if (inScope[index]) PushSection.IN_SCOPE else PushSection.OUTSIDE_SCOPE
            val linkedTo = RoleLinks.linkedTo(project, copy.dir)
            PushRow(section, copy.root, copy, null, source.name, badge, plan, uncommitted, vaultIdMismatches(plan), linkedTo)
        }
        val without = readAction { rootsWithout(project, catalog, source.name) }
        val creation = if (without.isEmpty()) null else RoleFilePlan.compute(project, source.dir, EMPTY_TARGET, options)
        val created = without.map { (root, rolesDir) ->
            PushRow(PushSection.WITHOUT_ROLE, root, null, rolesDir, source.name, null, creation!!, false, emptyList(), RoleLinks.linkedTo(project, rolesDir))
        }
        copies.filter { it.section == PushSection.IN_SCOPE } + copies.filter { it.section == PushSection.OUTSIDE_SCOPE } + created
    }

    /**
     * The non-detached roots without a copy of [name] that can take one: their first roles directory exists, belongs
     * to them (not a parent's or a library's `roles_path`) and is not an ignored path, and none of their roles
     * directories has [name] (a role they reach elsewhere is not shadowed). With that roles directory, in the catalog's
     * root order. VFS only; takes a read lock when the caller holds none.
     */
    fun rootsWithout(project: Project, catalog: RoleCatalogSnapshot, name: String): List<Pair<AnsibleRoot, VirtualFile>> = readLocked {
        val workspace = AnsibleWorkspace.getInstance(project)
        val ignored = AnsibilityProjectSettings.getInstance(project).ignoredPathMatcher()
        val owners = catalog.copies(name).map { it.root.dir }.toSet()
        workspace.roots()
            .filter { !it.detached && it.dir !in owners }
            .mapNotNull { root ->
                val rolesDir = root.rolesDirs.firstOrNull()?.takeIf { it.isValid && it.isDirectory && !ignored(it) } ?: return@mapNotNull null
                if (workspace.rootFor(rolesDir)?.dir != root.dir) return@mapNotNull null
                if (root.rolesDirs.any { it.isValid && it.findChild(name) != null }) return@mapNotNull null
                root to rolesDir
            }
            .sortedWith(compareBy(RoleCatalogSnapshot.ROOT_ORDER) { it.first })
    }

    /** The whole-file vaults of [plan] whose header line names different vault ids on the two sides. Header lines only. */
    private suspend fun vaultIdMismatches(plan: RoleFilePlan): List<VaultIdMismatch> {
        val vaults = plan.entries.filter { it.wholeFileVault && it.kind == PlanKind.CHANGED }
        if (vaults.isEmpty()) return emptyList()
        return readAction {
            vaults.mapNotNull { entry ->
                val sourceId = entry.sourceFile?.let(PushVaultIds::idOf) ?: return@mapNotNull null
                val targetId = entry.targetFile?.let(PushVaultIds::idOf) ?: return@mapNotNull null
                if (sourceId == targetId) null else VaultIdMismatch(entry.relPath, sourceId, targetId)
            }
        }
    }

    private fun <T> readLocked(action: () -> T): T =
        if (ApplicationManager.getApplication().isReadAccessAllowed) action() else runReadActionBlocking(action)

    /** Logs that the rows could not be computed: the error class only, never a path's content. */
    internal fun logFailure(e: Throwable) = LOG.warn("The push rows could not be computed (${e.javaClass.name})")
}

/**
 * The vault id of a whole-file vault from its header line only (`$ANSIBLE_VAULT;1.2;AES256;<id>`; Ansible's default
 * id for a header without one). Never reads past the first line, never the body; nothing is decrypted or logged.
 */
internal object PushVaultIds {
    /** The longest header line read. */
    private const val MAX_HEADER = 256

    /** The vault id of [file], or null when its first line is no vault header. Read action. */
    fun idOf(file: VirtualFile): String? {
        if (!file.isValid || file.isDirectory) return null
        val document = RoleFiles.unsavedDocument(file)
        val line = if (document != null) {
            val text = document.immutableCharSequence
            text.subSequence(0, minOf(text.length, MAX_HEADER)).toString().lineSequence().firstOrNull()
        } else {
            firstLine(file)
        }
        return parse(line)
    }

    /** The id of a header [line]: its fourth field, or the default id; null when it is no vault header. */
    fun parse(line: String?): String? {
        val header = line?.trim() ?: return null
        if (!header.startsWith(VaultHeaderInfo.MAGIC)) return null
        val fields = header.split(';').map { it.trim() }
        return VaultHeaderInfo(fields.getOrNull(1).orEmpty(), fields.getOrNull(2).orEmpty(), fields.getOrNull(3)?.takeIf { it.isNotEmpty() }).labelOrDefault()
    }

    private fun firstLine(file: VirtualFile): String? = try {
        file.inputStream.use { stream ->
            val bytes = ByteArray(MAX_HEADER)
            var length = 0
            while (length < MAX_HEADER) {
                val b = stream.read()
                if (b < 0 || b == '\n'.code || b == '\r'.code) break
                bytes[length++] = b.toByte()
            }
            String(bytes, 0, length, Charsets.US_ASCII)
        }
    } catch (_: IOException) {
        null
    }
}
