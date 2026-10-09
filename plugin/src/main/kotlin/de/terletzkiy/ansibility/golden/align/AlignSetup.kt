package de.terletzkiy.ansibility.golden.align

import com.intellij.openapi.application.readAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import de.terletzkiy.ansibility.api.VaultHeaderInfo
import de.terletzkiy.ansibility.api.WorkspaceScopeService
import de.terletzkiy.ansibility.golden.AnsibilityGoldenBundle.message
import de.terletzkiy.ansibility.golden.compare.CompareChoice
import de.terletzkiy.ansibility.golden.sync.PlanKind
import de.terletzkiy.ansibility.golden.sync.PlanOptions
import de.terletzkiy.ansibility.golden.sync.RoleFilePlan
import de.terletzkiy.ansibility.golden.sync.RoleFiles
import de.terletzkiy.ansibility.golden.sync.RoleLinks
import de.terletzkiy.ansibility.model.drift.DriftTexts
import de.terletzkiy.ansibility.model.drift.RoleDriftService
import de.terletzkiy.ansibility.model.role.RoleCatalog
import de.terletzkiy.ansibility.model.role.RoleCopy
import org.jetbrains.annotations.Nls
import java.io.IOException

/**
 * The counts the opening step of Align Role… shows for a pair of copies (plan amendment R24, D184/D185), from the
 * plan: changed, only in source, only in target (the files that take part) and left out (key and vault files without
 * "Include key and vault files"); plus a warning when the whole-file vaults of the two copies carry different vault
 * ids, because a repo may not be able to decrypt the other's vault.
 */
class AlignPreview internal constructor(
    val changed: Int,
    val onlyInSource: Int,
    val onlyInTarget: Int,
    val leftOut: Int,
    /** "Vault ids differ: …", or null. */
    @get:Nls val vaultWarning: String?,
) {
    /** Nothing takes part. */
    val isEmpty: Boolean get() = changed + onlyInSource + onlyInTarget == 0

    /** "3 changed · 9 only in source · 1 only in target · 2 left out". */
    @get:Nls
    val text: String get() = message("align.setup.counts", changed, onlyInSource, onlyInTarget, leftOut)

    override fun toString(): String = "AlignPreview($changed, $onlyInSource, $onlyInTarget, $leftOut${vaultWarning?.let { ", vault ids differ" }.orEmpty()})"
}

/**
 * The opening step of Align Role… (plan amendment R24, D184): every copy of the role with its drift badge (only with a
 * golden root, U7: a badge says how a copy differs from golden), in-scope copies first and never filtered (D44); by
 * default the golden copy is the target (else the selected copy) and the selected copy the source (else the first
 * other copy). A copy that is (or lies below) a symbolic link is never the default target and cannot be the target
 * ([linked], D191).
 */
class AlignSetup internal constructor(
    val project: Project,
    /** The role name. */
    val name: String,
    /** Every copy of the role, in-scope first (catalog order within each group). */
    val choices: List<CompareChoice>,
    val defaultTarget: CompareChoice,
    val defaultSource: CompareChoice,
    /** Where each linked copy's directory really is, by its directory (`RoleLinks.linkedTo`). */
    val linked: Map<VirtualFile, String> = emptyMap(),
) {
    /** Where [copy] really is when it is (or lies below) a symbolic link, else null: such a copy cannot be the target. */
    fun linkedTo(copy: RoleCopy): String? = linked[copy.dir]

    /** The counts and the vault warning for [target] ← [source]. Background; cancellable. */
    suspend fun preview(target: RoleCopy, source: RoleCopy, includeSensitive: Boolean): AlignPreview =
        AlignSetup.preview(project, target, source, includeSensitive)

    companion object {
        /** The setup for the copy [selected]; null when its role has no other copy. Background (computes drift badges). */
        suspend fun load(project: Project, selected: RoleCopy): AlignSetup? {
            val catalog = readAction { RoleCatalog.getInstance(project).snapshot() }
            val copies = catalog.copies(selected.name)
            if (copies.size < 2 || copies.none { it.dir == selected.dir }) return null
            // U7: without a golden root there is nothing a badge could be relative to (D178): no drift is computed.
            val drift = if (catalog.golden != null) RoleDriftService.getInstance(project).drift(selected.name) else null
            val workspace = WorkspaceScopeService.getInstance(project).current()
            val inScope = readAction { copies.map { workspace.contains(it.dir) } }
            val linked = copies.mapNotNull { copy -> RoleLinks.linkedTo(project, copy.dir)?.let { copy.dir to it } }.toMap()
            val choices = copies.mapIndexed { index, copy ->
                val tier = drift?.let { d -> d.copyOf(copy.dir)?.let { DriftTexts.badge(d, it) } }
                CompareChoice(copy, tier, inScope[index])
            }.sortedBy { !it.inScope }
            val selectedChoice = choices.first { it.copy.dir == selected.dir }
            val writable = choices.filter { it.copy.dir !in linked }
            val target = writable.firstOrNull { it.copy.isReference } ?: selectedChoice.takeIf { it in writable } ?: writable.firstOrNull() ?: selectedChoice
            val source = selectedChoice.takeIf { it !== target } ?: choices.first { it !== target }
            return AlignSetup(project, selected.name, choices, target, source, linked)
        }

        /** [AlignSetup.preview] for any two copies. */
        suspend fun preview(project: Project, target: RoleCopy, source: RoleCopy, includeSensitive: Boolean): AlignPreview {
            // U4: Align always includes molecule/ ("Ignore molecule/ in drift" is drift's setting only).
            val plan = RoleFilePlan.compute(project, source, target, PlanOptions(ignoreMolecule = false, includeSensitive = includeSensitive))
            val included = plan.included
            val warning = readAction { AlignVaults.warning(plan, source, target) }
            return AlignPreview(
                changed = included.count { it.kind == PlanKind.CHANGED },
                onlyInSource = included.count { it.kind == PlanKind.ONLY_IN_SOURCE },
                onlyInTarget = included.count { it.kind == PlanKind.ONLY_IN_TARGET },
                leftOut = plan.excluded.size,
                vaultWarning = warning,
            )
        }
    }
}

/**
 * The vault ids of whole-file vaults (plan amendment R24, D185): read from the header line only
 * (`$ANSIBLE_VAULT;1.2;AES256;<id>`), never the body, never decrypted; an unlabelled vault has Ansible's default id.
 */
internal object AlignVaults {
    /** At most this many bytes are read to find the header line. */
    private const val HEADER_LIMIT = 512

    /**
     * "Vault ids differ: falcon uses prod, golden uses default. …" when the whole-file vaults [plan] lists carry
     * different ids in [source] and [target] (both sides having some); else null. Read action.
     */
    @Nls
    fun warning(plan: RoleFilePlan, source: RoleCopy, target: RoleCopy): String? {
        val vaults = plan.entries.filter { it.wholeFileVault }
        if (vaults.isEmpty()) return null
        val sourceIds = vaults.mapNotNull { it.sourceFile?.let(::vaultId) }.toSortedSet()
        val targetIds = vaults.mapNotNull { it.targetFile?.let(::vaultId) }.toSortedSet()
        if (sourceIds.isEmpty() || targetIds.isEmpty() || sourceIds == targetIds) return null
        return message(
            "align.setup.vaultIds",
            source.root.displayName, sourceIds.joinToString(", "),
            target.root.displayName, targetIds.joinToString(", "),
        )
    }

    /** The vault id of the whole-file vault [file] from its header line, or null when it is no vault. Read action. */
    fun vaultId(file: VirtualFile): String? {
        val line = headerLine(file) ?: return null
        if (!line.startsWith(VaultHeaderInfo.MAGIC + ";")) return null
        val fields = line.trim().split(';')
        return fields.getOrNull(3)?.trim()?.takeIf { it.isNotEmpty() } ?: VaultHeaderInfo.DEFAULT_IDENTITY
    }

    /** The first line of [file] (an unsaved document's first line), at most [HEADER_LIMIT] bytes. */
    private fun headerLine(file: VirtualFile): String? {
        if (!file.isValid || file.isDirectory) return null
        RoleFiles.unsavedDocument(file)?.let { document ->
            if (document.lineCount == 0) return ""
            return document.immutableCharSequence.subSequence(0, minOf(document.getLineEndOffset(0), HEADER_LIMIT)).toString()
        }
        val head = try {
            file.inputStream.use { it.readNBytes(HEADER_LIMIT) }
        } catch (_: IOException) {
            return null
        }
        val end = head.indexOf('\n'.code.toByte()).takeIf { it >= 0 } ?: head.size
        return String(head, 0, end, Charsets.US_ASCII)
    }
}
