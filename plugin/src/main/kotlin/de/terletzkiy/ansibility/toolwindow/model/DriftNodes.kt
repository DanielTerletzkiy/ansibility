package de.terletzkiy.ansibility.toolwindow.model

import com.intellij.openapi.vfs.VirtualFile
import de.terletzkiy.ansibility.api.RoleRef
import de.terletzkiy.ansibility.model.drift.AnsibilityDriftBundle
import de.terletzkiy.ansibility.model.drift.CopyDrift
import de.terletzkiy.ansibility.model.drift.DriftCategory
import de.terletzkiy.ansibility.model.drift.DriftPaths
import de.terletzkiy.ansibility.model.drift.DriftRules
import de.terletzkiy.ansibility.model.drift.DriftTexts
import de.terletzkiy.ansibility.model.drift.RoleDrift
import de.terletzkiy.ansibility.model.drift.RoleDriftService
import org.jetbrains.annotations.Nls

/**
 * The ids of the golden actions (plan amendment R24, D180–D190). Other work units register them; the tool window offers
 * an action only while it is registered (`ActionManager.getAction(id) != null`) and runs it with the tree's data
 * context ([de.terletzkiy.ansibility.golden.GoldenDataKeys]).
 */
object GoldenActionIds {
    const val COMPARE_WITH_GOLDEN: String = "Ansibility.Golden.CompareWithGolden"
    const val ALIGN_WITH_GOLDEN: String = "Ansibility.Golden.AlignWithGolden"
    const val MERGE_INTO_GOLDEN: String = "Ansibility.Golden.MergeIntoGolden"
    const val PUSH_TO_REPOS: String = "Ansibility.Golden.PushToRepos"

    /** The buttons of a copy's details, in order. */
    val COPY_ACTIONS: List<String> = listOf(COMPARE_WITH_GOLDEN, ALIGN_WITH_GOLDEN, MERGE_INTO_GOLDEN, PUSH_TO_REPOS)
}

/**
 * A row that stands for a role copy, and maybe a file in it (plan amendment R24): what the panel offers golden actions
 * as `GoldenDataKeys.ROLE_COPY` and `ROLE_PATH`, and as `CommonDataKeys.VIRTUAL_FILE` where the file exists.
 */
interface RoleCopyData {
    /** The role directory of the copy. */
    val copyDir: VirtualFile

    /** The selected file's path inside the copy ("tasks/main.yml"), also for a file only the golden copy has; null for the copy itself. */
    val rolePath: String? get() = null

    /** The selected file in this copy, when it exists. */
    val existingFile: VirtualFile? get() = null
}

/**
 * Reads the drift of the tree's rows: only with a golden root ([WorkspaceSnapshot.golden]), only from
 * [RoleDriftService.cached] (never computes, never blocks), so rows and details stay cheap in the tree's background
 * read action.
 */
internal object DriftLookup {
    /** The known drift of [name], or null (no golden root, not computed yet, no project). */
    fun drift(node: AnsibleTreeNode, name: String): RoleDrift? {
        if (node.snapshot?.golden?.isSet != true) return null
        val project = node.project ?: return null
        return RoleDriftService.getInstance(project).cached(name)
    }

    /** The known drift of the copy of [name] in [dir], or null. */
    fun copy(node: AnsibleTreeNode, name: String, dir: VirtualFile): CopyDrift? = drift(node, name)?.copyOf(dir)

    /** The name the wording uses for golden: the reference's root, else the golden root. */
    fun goldenName(node: AnsibleTreeNode, drift: RoleDrift?): String? = drift?.reference?.root?.displayName ?: node.snapshot?.golden?.name
}

/** How a differing file differs (plan amendment R24, D179), with its VCS status colour. */
enum class DriftFileKind(val color: NodeColor) {
    /** In both copies, with other content: modified. */
    CHANGED(NodeColor.MODIFIED),

    /** Only in the golden copy: deleted, and struck through. */
    ONLY_IN_GOLDEN(NodeColor.DELETED),

    /** Only in this copy: added. */
    ONLY_HERE(NodeColor.ADDED),
}

/** The groups under "Differences from golden", in order: changed files by category, then files on one side only. */
enum class DriftGroup {
    TASKS_TEMPLATES, SPEC_DEFAULTS, MOLECULE, ONLY_IN_GOLDEN, ONLY_HERE;

    /** The paths of [paths] that belong to this group, sorted. */
    fun pathsOf(paths: DriftPaths): List<String> = when (this) {
        TASKS_TEMPLATES -> paths.changed.filter { DriftRules.categoryOf(it) == DriftCategory.BEHAVIOUR }
        SPEC_DEFAULTS -> paths.changed.filter { DriftRules.categoryOf(it) == DriftCategory.SPEC_DEFAULTS }
        MOLECULE -> paths.changed.filter { DriftRules.categoryOf(it) == DriftCategory.MOLECULE }
        ONLY_IN_GOLDEN -> paths.onlyInReference
        ONLY_HERE -> paths.onlyHere
    }

    val kind: DriftFileKind
        get() = when (this) {
            ONLY_IN_GOLDEN -> DriftFileKind.ONLY_IN_GOLDEN
            ONLY_HERE -> DriftFileKind.ONLY_HERE
            else -> DriftFileKind.CHANGED
        }

    companion object {
        /** The group of a file that differs as [kind] at [relPath]. */
        fun of(kind: DriftFileKind, relPath: String): DriftGroup = when (kind) {
            DriftFileKind.ONLY_IN_GOLDEN -> ONLY_IN_GOLDEN
            DriftFileKind.ONLY_HERE -> ONLY_HERE
            DriftFileKind.CHANGED -> when (DriftRules.categoryOf(relPath)) {
                DriftCategory.BEHAVIOUR -> TASKS_TEMPLATES
                DriftCategory.SPEC_DEFAULTS -> SPEC_DEFAULTS
                DriftCategory.MOLECULE -> MOLECULE
            }
        }
    }

    /** `Tasks/templates`, `Spec/defaults`, `Molecule`, `Only in golden`, `Only here`. */
    @Nls
    fun title(golden: String?): String = when (this) {
        TASKS_TEMPLATES -> DriftTexts.categoryName(DriftCategory.BEHAVIOUR)
        SPEC_DEFAULTS -> DriftTexts.categoryName(DriftCategory.SPEC_DEFAULTS)
        MOLECULE -> DriftTexts.categoryName(DriftCategory.MOLECULE)
        ONLY_IN_GOLDEN -> AnsibilityDriftBundle.message("drift.group.onlyInReference", golden ?: DriftTexts.fallback())
        ONLY_HERE -> AnsibilityDriftBundle.message("drift.group.onlyHere")
    }
}

/**
 * "Differences from golden (n)", the first child of a copy that differs from the golden copy (plan amendment R24, D179):
 * its groups ([DriftGroupNode]) with the differing files. Everything is read from the cached drift of [roleName] when
 * rendered, so a refresh of the name's rows shows the current paths.
 */
class DifferencesNode(parent: AnsibleTreeNode, val roleName: String, override val copyDir: VirtualFile) :
    AnsibleTreeNode(parent, "differences"), RoleCopyData {
    private fun copyDrift(): CopyDrift? = DriftLookup.copy(this, roleName, copyDir)

    override fun presentation(): NodePresentation {
        val drift = DriftLookup.drift(this, roleName)
        val golden = DriftLookup.goldenName(this, drift) ?: DriftTexts.fallback()
        val size = drift?.copyOf(copyDir)?.paths?.size ?: 0
        return NodePresentation(AnsibilityDriftBundle.message("drift.node.differences", golden, size), icon = NodeIcon.DRIFT_FOLDER)
    }

    override fun children(context: TreeContext): List<AnsibleTreeNode> {
        val paths = copyDrift()?.paths ?: return emptyList()
        return DriftGroup.entries.filter { it.pathsOf(paths).isNotEmpty() }.map { DriftGroupNode(this, roleName, copyDir, it) }
    }

    override fun details(): NodeDetails? = (parent as? RoleNode)?.details()
}

/** One group under "Differences from golden": `Molecule  1`, `Only in golden  9` (plan amendment R24, D179). */
class DriftGroupNode(parent: AnsibleTreeNode, val roleName: String, override val copyDir: VirtualFile, val group: DriftGroup) :
    AnsibleTreeNode(parent, "group:${group.name}"), RoleCopyData {
    override fun presentation(): NodePresentation {
        val drift = DriftLookup.drift(this, roleName)
        val count = drift?.copyOf(copyDir)?.paths?.let(group::pathsOf)?.size ?: 0
        return NodePresentation(group.title(DriftLookup.goldenName(this, drift)), count.toString(), icon = NodeIcon.DRIFT_FOLDER)
    }

    override fun children(context: TreeContext): List<AnsibleTreeNode> {
        val paths = DriftLookup.copy(this, roleName, copyDir)?.paths ?: return emptyList()
        return group.pathsOf(paths).map { DriftFileNode(this, roleName, copyDir, it, group.kind, it in paths.sensitive) }
    }

    override fun details(): NodeDetails? = generateSequence(parent) { it.parent }.filterIsInstance<RoleNode>().firstOrNull()?.details()
}

/**
 * One differing file (plan amendment R24, D179/D181): its path inside the role in the VCS status colour of [kind]
 * (struck through when only the golden copy has it). A sensitive file says "differs (content not shown)" and has no
 * target: it is never opened by itself.
 *
 * Double-click, Enter and F4 run Compare with Golden when that action is registered; otherwise they open the file of
 * this copy (nothing for a file only golden has, or a sensitive one).
 */
class DriftFileNode(
    parent: AnsibleTreeNode,
    val roleName: String,
    override val copyDir: VirtualFile,
    val relPath: String,
    val kind: DriftFileKind,
    val sensitive: Boolean,
) : AnsibleTreeNode(parent, "path:$relPath"), RoleCopyData {
    override val rolePath: String get() = relPath

    override val existingFile: VirtualFile?
        get() = if (kind == DriftFileKind.ONLY_IN_GOLDEN) null else copyDir.findFileByRelativePath(relPath)?.takeIf { it.isValid && !it.isDirectory }

    override fun presentation(): NodePresentation {
        val golden = DriftLookup.goldenName(this, DriftLookup.drift(this, roleName)) ?: DriftTexts.fallback()
        val tooltip = when (kind) {
            DriftFileKind.CHANGED -> AnsibilityDriftBundle.message("drift.file.tooltip.changed", golden)
            DriftFileKind.ONLY_IN_GOLDEN -> AnsibilityDriftBundle.message("drift.file.tooltip.onlyInReference", golden)
            DriftFileKind.ONLY_HERE -> AnsibilityDriftBundle.message("drift.file.tooltip.onlyHere", golden)
        }
        return NodePresentation(
            relPath,
            if (sensitive) DriftTexts.contentNotShown() else null,
            listOf(tooltip),
            NodeIcon.FILE,
            style = if (kind == DriftFileKind.ONLY_IN_GOLDEN) NodeStyle.STRUCK else NodeStyle.NORMAL,
            color = kind.color,
        )
    }

    /** This copy's file, unless it is sensitive (never opened by itself, D181) or only golden has it. */
    override val target: NavigationTarget? get() = if (sensitive) null else existingFile?.let { NavigationTarget(it) }
    override val navigatesOnDoubleClick: Boolean get() = true
    override val isLeaf: Boolean get() = true

    override fun children(context: TreeContext): List<AnsibleTreeNode> = emptyList()

    override fun activate(activation: NodeActivation): Boolean {
        if (activation.runAction(GoldenActionIds.COMPARE_WITH_GOLDEN)) return true
        target?.let(activation::navigate)
        return true
    }

    override fun details(): NodeDetails? = DriftDetails.file(this)
}

/**
 * One variant group of a role name under Group by Variant (plan amendment R24, X123): `Variant A: golden, raven (2)`,
 * `Variant B: falcon, heron, tern, … (7)  ≈ molecule only · 1 file`. Its rows are the same copy rows ([RoleNode]) as
 * without grouping, with the same keys ([childKeyBase] is the name's), badges, Differences children and data keys.
 *
 * The group's own key is its first copy's directory, not its letter, so it keeps its expansion while groups grow,
 * shrink or change places. The details are the role name's.
 */
class VariantGroupNode(
    parent: RoleNameNode,
    /** `A`, `B`, …: the position in [de.terletzkiy.ansibility.model.drift.RoleDrift.rankedVariants]. */
    val letter: String,
    /** The copies of this variant, in the role name's order (golden first). */
    val copies: List<Pair<RootSnapshot, RoleRef>>,
) : AnsibleTreeNode(parent, "variant:${copies.first().second.dir.path}") {
    private val nameNode: RoleNameNode get() = parent as RoleNameNode

    override val childKeyBase: String get() = parent!!.childKeyBase

    override fun presentation(): NodePresentation {
        val names = copies.map { it.first.root.displayName }
        val drift = DriftLookup.drift(this, nameNode.name)
        // A variant other than golden's differs from it the same way in every copy: its first copy's badge says how.
        val badge = drift?.copyOf(copies.first().second.dir)?.takeIf { it.tier.differs }?.let { DriftTexts.rowBadge(drift, it, DriftLookup.goldenName(this, drift)) }
        return NodePresentation(DriftTexts.variantGroup(letter, names), tooltip = listOf(DriftTexts.variantGroupTooltip(names, drift?.options?.ignoreMolecule == true)), icon = NodeIcon.FOLDER, badge = badge)
    }

    override fun children(context: TreeContext): List<AnsibleTreeNode> = copies.map { (root, role) -> RoleNameNode.copyRow(this, root, role) }

    override fun details(): NodeDetails? = nameNode.details()

    companion object {
        /**
         * The variant groups of [name] in [RoleDrift.rankedVariants][de.terletzkiy.ansibility.model.drift.RoleDrift.rankedVariants]
         * order (golden's first, then by size, then by name), lettered after that order; copies the drift does not know
         * yet (a root added since) follow ungrouped. Null while the drift of the name is not known: the copies then list
         * as usual.
         */
        fun groupsOf(name: RoleNameNode): List<AnsibleTreeNode>? {
            val drift = DriftLookup.drift(name, name.name) ?: return null
            val grouped = HashSet<VirtualFile>()
            val groups = ArrayList<AnsibleTreeNode>()
            for (variant in drift.rankedVariants) {
                val dirs = variant.copies.map { it.dir }.toSet()
                val members = name.copies.filter { it.second.dir in dirs }
                if (members.isEmpty()) continue
                grouped += dirs
                groups += VariantGroupNode(name, DriftTexts.variantLetter(groups.size), members)
            }
            val rest = name.copies.filter { it.second.dir !in grouped }.map { (root, role) -> RoleNameNode.copyRow(name, root, role) }
            return groups + rest
        }
    }
}
