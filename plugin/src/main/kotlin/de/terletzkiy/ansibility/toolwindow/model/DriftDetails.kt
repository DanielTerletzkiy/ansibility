package de.terletzkiy.ansibility.toolwindow.model

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import de.terletzkiy.ansibility.api.PlayGraph
import de.terletzkiy.ansibility.api.RoleRef
import de.terletzkiy.ansibility.api.RootKind
import de.terletzkiy.ansibility.context.MoleculeView
import de.terletzkiy.ansibility.context.MoleculeVisibility
import de.terletzkiy.ansibility.golden.history.KnownLastChange
import de.terletzkiy.ansibility.golden.history.LastChange
import de.terletzkiy.ansibility.golden.history.LastChangeLookup
import de.terletzkiy.ansibility.golden.history.LastChangeTexts
import de.terletzkiy.ansibility.golden.history.LastChanges
import de.terletzkiy.ansibility.model.drift.AnsibilityDriftBundle.message
import de.terletzkiy.ansibility.model.drift.CopyDrift
import de.terletzkiy.ansibility.model.drift.DriftTexts
import de.terletzkiy.ansibility.model.drift.DriftTier
import de.terletzkiy.ansibility.model.drift.RoleDrift
import de.terletzkiy.ansibility.model.role.RoleLayout
import org.jetbrains.annotations.Nls

/** One differing file in the details pane (plan amendment R24, D180): its group, path, kind and whether its content is never shown. */
data class DriftFileRow(@Nls val group: String, val relPath: String, val kind: DriftFileKind, val sensitive: Boolean)

/**
 * A last change the details would show once it is known, or known again (plan amendment R24, D180): the role
 * directory ([directory]) or file of one side, and what the details show for it now ([shown]: the last known answer
 * while it is revalidated, else null). The details read only the cache of [LastChanges] in their read action; the
 * tool window looks these up in the background, outside it, and computes the details again only when the cache then
 * holds another answer than [shown].
 */
data class LastChangeRequest(val file: VirtualFile, val directory: Boolean, val shown: LastChange? = null)

/**
 * The drift part of a copy's details (plan amendment R24, D180), rendered by the details view below the sections: the
 * differing [files] with a [Compare] link each, and the [actions] as buttons. The view offers a link or button only
 * while its action is registered, and runs it with the tool window's data context ([copyDir] as the copy, the row's
 * path as the file). [lastChanges] are the sides whose last change is not cached yet.
 */
data class DriftDetailsContent(
    val copyDir: VirtualFile,
    /** The golden root's name, for the section title ("Differences from golden"). */
    @Nls val golden: String,
    val files: List<DriftFileRow>,
    val actions: List<String>,
    val lastChanges: List<LastChangeRequest> = emptyList(),
) : DetailsContent

/**
 * The details of drift rows (plan amendment R24, D180). They run in the details' background read action and read only
 * [de.terletzkiy.ansibility.model.drift.RoleDriftService.cached] and [LastChanges.known] (never a VCS lookup); without a golden root they are null (D178). Sensitive files (D181) are listed as "differs
 * (content not shown)"; nothing here reads a file's content.
 *
 * "Last changed" names each side's last commit (`golden: 2026-09-12 · alice · fix verify`) and, for a copy that
 * differs, which side changed later (X122); the Variant section says which content most copies share. Facts only,
 * never "outdated". Without VCS support (no `lastChangeLookup` extension) there is no "Last changed".
 */
object DriftDetails {
    private const val MAX_VARIANT_NAMES = 8

    /** One side of a comparison for "Last changed": the root's name and the role directory or file. */
    private class Side(val name: String, val file: VirtualFile, val directory: Boolean)

    /** The "Last changed" lines that are known, and the sides still to look up. */
    private class LastChanged(val items: List<DetailItem>, val requests: List<LastChangeRequest>) {
        companion object {
            val NONE = LastChanged(emptyList(), emptyList())
        }
    }

    /** A copy: tier, path, argument_specs, the plays that apply it, the variant group, the differing files and the actions. */
    fun copy(node: AnsibleTreeNode, root: RootSnapshot, role: RoleRef): NodeDetails? {
        val project = node.project ?: return null
        if (node.snapshot?.golden?.isSet != true) return null
        val drift = DriftLookup.drift(node, role.name)
        val copy = drift?.copyOf(role.dir)
        val golden = DriftLookup.goldenName(node, drift) ?: DriftTexts.fallback()
        val details = DetailsBuilder(
            message("drift.details.copy.title", role.name, root.root.displayName),
            if (drift != null && copy != null) DriftTexts.rowBadge(drift, copy, golden) else DriftTexts.pending(),
        )
        val spec = RoleLayout.specFile(role.dir)
        details.section(
            message("drift.details.section.drift"),
            tierItem(drift, copy, golden),
            DetailItem(message("drift.details.path", role.dir.presentableUrl), target = NavigationTarget(role.dir)),
            DetailItem(message(if (spec != null) "drift.details.spec.present" else "drift.details.spec.missing"), target = spec?.let { NavigationTarget(it) }),
        )
        // R25 (D199): no play applies the external golden root's copy (it is no root of the project).
        if (!root.external) details.section(message("drift.details.section.plays"), plays(project, root, role))
        val reference = drift?.reference?.takeIf { it.dir != role.dir }
        val sides = listOfNotNull(reference?.let { Side(golden, it.dir, directory = true) }, Side(root.root.displayName, role.dir, directory = true))
        val lastChanged = lastChanged(project, sides, file = false, hint = copy?.tier?.differs == true)
        details.section(message("drift.details.section.lastChanged"), lastChanged.items)
        if (drift != null && copy != null && drift.copies.size > 1) {
            val majority = DriftTexts.variantMajority(drift, copy, golden).map { DetailItem(it) }
            details.section(message("drift.details.section.variant"), listOf(variantItem(drift, copy)) + majority)
        }
        val files = copy?.let { rows(it, golden) }.orEmpty()
        val externalGolden = node.snapshot?.golden?.external != null
        return details.build(DriftDetailsContent(role.dir, golden, files, actionsFor(copy, externalGolden, root.external), lastChanged.requests))
    }

    /**
     * The buttons of a copy: all of [GoldenActionIds.COPY_ACTIONS] while its drift is not known or when it differs from
     * golden; only Push to Repos for the golden copy itself, an identical copy and a role without a golden copy (there
     * is nothing to compare, align, merge or copy as a patch). With an external golden root (plan amendment R25, D198:
     * read-only) never Merge into Golden; its own copy only Push.
     */
    private fun actionsFor(copy: CopyDrift?, externalGolden: Boolean = false, isExternal: Boolean = false): List<String> = when {
        isExternal -> listOf(GoldenActionIds.PUSH_TO_REPOS)
        copy == null || copy.tier.differs -> GoldenActionIds.COPY_ACTIONS.filter { !externalGolden || it != GoldenActionIds.MERGE_INTO_GOLDEN }
        else -> listOf(GoldenActionIds.PUSH_TO_REPOS)
    }

    /**
     * "Last changed" over [sides] (golden first): one line per side whose last change is known, and when both are
     * known and [hint] is set, which side changed later (X122). Cache only ([LastChanges.known]): an answer the VCS
     * reported a change for is shown until it is revalidated; the sides not known yet, or not fresh, come back as
     * requests. Nothing at all without a [LastChangeLookup] (no VCS support).
     */
    private fun lastChanged(project: Project, sides: List<Side>, file: Boolean, hint: Boolean): LastChanged {
        if (sides.isEmpty() || LastChangeLookup.EP_NAME.extensionList.isEmpty()) return LastChanged.NONE
        val cache = LastChanges.getInstance(project)
        val known: List<Pair<Side, KnownLastChange?>> = sides.map { side ->
            ProgressManager.checkCanceled()
            side to cache.known(side.file, side.directory)
        }
        // R25: the golden mirror's side may be its fetched commit or "older than the fetched history" (D200, X127).
        val items = known.mapNotNull { (side, change) -> change?.value?.let { DetailItem(LastChangeTexts.line(side.name, it)) } }
        val direction = if (hint && known.size == 2) {
            val firstChange = known[0].second?.value
            val secondChange = known[1].second?.value
            if (firstChange != null && secondChange != null) LastChangeTexts.direction(known[0].first.name, firstChange, known[1].first.name, secondChange, file) else null
        } else {
            null
        }
        val requests = known.filter { it.second?.fresh != true }.map { (side, change) -> LastChangeRequest(side.file, side.directory, change?.value) }
        return LastChanged(items + listOfNotNull(direction?.let { DetailItem(it) }), requests)
    }

    /** A role name: its copies with their tiers, the golden copy first. */
    fun name(node: RoleNameNode): NodeDetails? {
        if (node.snapshot?.golden?.isSet != true) return null
        val drift = DriftLookup.drift(node, node.name)
        val golden = DriftLookup.goldenName(node, drift)
        val summary = drift?.let { DriftTexts.nameSummary(it, golden) } ?: DriftTexts.pending()
        val details = DetailsBuilder(node.name, message("drift.details.name.subtitle", node.copies.size, summary))
        details.section(message("drift.details.section.copies"), node.copies.map { (root, role) ->
            ProgressManager.checkCanceled()
            val copy = drift?.copyOf(role.dir)
            val badge = if (drift != null && copy != null) DriftTexts.rowBadge(drift, copy, golden) else DriftTexts.pending()
            DetailItem(root.copyLabel, badge, NavigationTarget(role.dir))
        })
        return details.build()
    }

    /** A differing file: how it differs, each side's last change of it, and Compare for it alone. */
    fun file(node: DriftFileNode): NodeDetails? =
        fileDetails(node, node.roleName, node.copyDir, node.relPath, node.kind, node.sensitive, node.target, node.existingFile)

    /**
     * A file of a copy's listing (D180): the details of a differing file ([file]) when it differs from golden, else
     * whether it is the same as golden's and each side's last change of it. Directories have none.
     */
    fun roleFile(node: RoleFileNode): NodeDetails? {
        val relPath = node.rolePath ?: return null
        if (node.snapshot?.golden?.isSet != true) return null
        val paths = DriftLookup.copy(node, node.roleName, node.copyDir)?.paths
        val kind = when {
            paths == null -> null
            relPath in paths.changed -> DriftFileKind.CHANGED
            relPath in paths.onlyHere -> DriftFileKind.ONLY_HERE
            else -> null
        }
        val sensitive = kind != null && relPath in paths!!.sensitive
        return fileDetails(node, node.roleName, node.copyDir, relPath, kind, sensitive, node.target.takeIf { !sensitive }, node.existingFile)
    }

    /**
     * The details of the file [relPath] of the copy [copyDir]: how it differs ([kind]; null when it is the same as
     * golden's or not known yet), each side's last change of it, and Compare for a differing one.
     */
    private fun fileDetails(
        node: AnsibleTreeNode,
        roleName: String,
        copyDir: VirtualFile,
        relPath: String,
        kind: DriftFileKind?,
        sensitive: Boolean,
        target: NavigationTarget?,
        existing: VirtualFile?,
    ): NodeDetails? {
        val project = node.project ?: return null
        if (node.snapshot?.golden?.isSet != true) return null
        val drift = DriftLookup.drift(node, roleName)
        val copy = drift?.copyOf(copyDir)
        val golden = DriftLookup.goldenName(node, drift) ?: DriftTexts.fallback()
        val owner = copy?.copy?.root?.displayName ?: copyDir.parent?.name.orEmpty()
        val details = DetailsBuilder(relPath, message("drift.details.copy.title", roleName, owner))
        val text = when (kind) {
            DriftFileKind.CHANGED -> message("drift.details.file.changed", golden)
            DriftFileKind.ONLY_IN_GOLDEN -> message("drift.details.file.onlyInReference", golden)
            DriftFileKind.ONLY_HERE -> message("drift.details.file.onlyHere")
            null -> when {
                drift == null || copy == null -> message("drift.details.pending")
                copy.tier == DriftTier.REFERENCE -> message("drift.details.tier.reference")
                drift.reference == null -> DriftTexts.badge(drift, copy, golden)
                else -> message("drift.details.file.same", golden)
            }
        }
        details.section(message("drift.details.section.drift"), DetailItem(text, if (sensitive) DriftTexts.contentNotShown() else null, target))
        val reference = drift?.reference?.dir?.takeIf { it.isValid && it != copyDir && kind != DriftFileKind.ONLY_HERE }
        val goldenFile = reference?.findFileByRelativePath(relPath)?.takeIf { it.isValid && !it.isDirectory }
        val sides = listOfNotNull(goldenFile?.let { Side(golden, it, directory = false) }, existing?.let { Side(owner, it, directory = false) })
        val lastChanged = lastChanged(project, sides, file = true, hint = kind == DriftFileKind.CHANGED)
        details.section(message("drift.details.section.lastChanged"), lastChanged.items)
        val rows = kind?.let { listOf(DriftFileRow(DriftGroup.of(it, relPath).title(golden), relPath, it, sensitive)) }.orEmpty()
        val actions = if (kind != null) listOf(GoldenActionIds.COMPARE_WITH_GOLDEN) else emptyList()
        return details.build(DriftDetailsContent(copyDir, golden, rows, actions, lastChanged.requests))
    }

    /** The differing files of [copy] in group order: changed by category, then only in [golden], then only here. */
    fun rows(copy: CopyDrift, golden: String): List<DriftFileRow> = DriftGroup.entries.flatMap { group ->
        group.pathsOf(copy.paths).map { DriftFileRow(group.title(golden), it, group.kind, it in copy.paths.sensitive) }
    }

    private fun tierItem(drift: RoleDrift?, copy: CopyDrift?, golden: String): DetailItem = when {
        drift == null || copy == null -> DetailItem(message("drift.details.pending"))
        copy.tier == DriftTier.REFERENCE -> DetailItem(message("drift.details.tier.reference"))
        else -> DetailItem(message("drift.details.tier", DriftTexts.badge(drift, copy, golden)), DriftTexts.tooltip(copy.tier, golden, drift.options.ignoreMolecule))
    }

    /** "Same as falcon, heron; 3 of 9 copies", or that no other copy has this content. */
    private fun variantItem(drift: RoleDrift, copy: CopyDrift): DetailItem {
        val others = drift.variants.getOrNull(copy.variant)?.copies.orEmpty().filter { it.dir != copy.copy.dir }
        if (others.isEmpty()) return DetailItem(message("drift.details.variant.unique", drift.copies.size))
        val names = ToolWindowTexts.joinCapped(others.map { it.root.displayName }, MAX_VARIANT_NAMES)
        return DetailItem(message("drift.details.variant.same", names, others.size + 1, drift.copies.size))
    }

    /** The plays of [root] that apply the role (cached by the play graph), as links; Molecule plays as navigation shows them. */
    private fun plays(project: Project, root: RootSnapshot, role: RoleRef): List<DetailItem> {
        val graph = PlayGraph.getInstance(project)
        val applying = MoleculeVisibility.playsInView(project, MoleculeView.of(project, null), graph.playsApplying(root.root, role.name))
        if (applying.isEmpty()) {
            return if (root.kind == RootKind.ROLE_LIBRARY) emptyList() else listOf(DetailItem(message("drift.details.plays.none", root.root.displayName)))
        }
        return applying.map { play ->
            ProgressManager.checkCanceled()
            val name = play.name ?: message("drift.details.play.unnamed", play.playIndex + 1)
            val location = graph.play(play)?.location
            DetailItem(message("drift.details.play", root.relativePath(play.file), name), target = location?.let { NavigationTarget(it.file, it.offset) } ?: NavigationTarget(play.file))
        }
    }
}
