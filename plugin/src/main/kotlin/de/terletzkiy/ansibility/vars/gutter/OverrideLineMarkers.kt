package de.terletzkiy.ansibility.vars.gutter

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProviderDescriptor
import com.intellij.codeInsight.navigation.NavigationGutterIconBuilder
import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.util.NotNullLazyValue
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.psi.PsiElement
import com.intellij.psi.util.elementType
import com.intellij.openapi.project.Project
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.FileKind
import de.terletzkiy.ansibility.api.VarDefKind
import de.terletzkiy.ansibility.api.VarDefinition
import de.terletzkiy.ansibility.api.VarService
import de.terletzkiy.ansibility.api.VarsLayer
import de.terletzkiy.ansibility.dispatch.SitePresentation
import de.terletzkiy.ansibility.vars.AnsibilityVarsBundle.message
import de.terletzkiy.ansibility.vars.VarLabels
import de.terletzkiy.ansibility.vars.VarLocations
import de.terletzkiy.ansibility.vars.VarSubject
import de.terletzkiy.ansibility.vars.VarTargetElement
import org.jetbrains.yaml.YAMLTokenTypes
import org.jetbrains.yaml.psi.YAMLKeyValue
import javax.swing.Icon

/**
 * Override gutter icons in both directions (plan X42): on a role default or spec option "Overridden in 5 files", on a
 * vars-file key "Overrides role default of postfix". Precedence is by [VarsLayer.level]; a spec option ranks with the
 * role defaults. Role defaults and role vars only meet definitions of the same role or of no role; inventory-level
 * definitions only meet those that reach a common host of the same environment ([HostReach]); molecule files only
 * each other. Slow markers: they read the root's variable index.
 */
class OverrideLineMarkers : LineMarkerProviderDescriptor() {
    override fun getName(): String = message("gutter.override.name")

    override fun getIcon(): Icon = AllIcons.Gutter.OverridenMethod

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? = null

    override fun collectSlowLineMarkers(elements: List<PsiElement>, result: MutableCollection<in LineMarkerInfo<*>>) {
        val first = elements.firstOrNull() ?: return
        if (DumbService.isDumb(first.project)) return
        for (element in elements) {
            ProgressManager.checkCanceled()
            if (element.elementType != YAMLTokenTypes.SCALAR_KEY) continue
            val keyValue = element.parent as? YAMLKeyValue ?: continue
            val overrides = overridesOf(keyValue) ?: continue
            if (overrides.higher.isNotEmpty()) result += marker(element, AllIcons.Gutter.OverridenMethod, overriddenText(overrides.higher), overrides.root, overrides.higher)
            if (overrides.lower.isNotEmpty()) result += marker(element, AllIcons.Gutter.OverridingMethod, overridesText(overrides.lower), overrides.root, overrides.lower)
        }
    }

    private fun marker(element: PsiElement, icon: Icon, tooltip: String, root: AnsibleRoot, targets: List<VarDefinition>): LineMarkerInfo<PsiElement> {
        val project = element.project
        val lazyTargets: NotNullLazyValue<Collection<PsiElement>> = NotNullLazyValue.lazy { targets.mapNotNull { target(project, root, it) } }
        return NavigationGutterIconBuilder.create(icon)
            .setTargets(lazyTargets)
            .setTooltipText(tooltip)
            .setPopupTitle(tooltip)
            .setAlignment(GutterIconRenderer.Alignment.RIGHT)
            .createLineMarkerInfo(element)
    }

    private fun overriddenText(higher: List<VarDefinition>): String {
        val envs = higher.mapNotNull { it.environment?.substringAfterLast('/') }.distinct().sorted()
        return if (envs.isEmpty()) message("gutter.overridden", higher.size) else message("gutter.overridden.envs", higher.size, envs.joinToString(", "))
    }

    private fun overridesText(lower: List<VarDefinition>): String {
        val single = lower.singleOrNull()
        if (single != null) {
            val kind = SitePresentation.definitionKindName(single.kind)
            return single.roleName?.let { message("gutter.overrides.role", kind, it) } ?: message("gutter.overrides.one", kind)
        }
        return message("gutter.overrides", lower.size)
    }

    /** The definitions that beat a key ([higher]) and that it beats ([lower]), each lowest precedence first. */
    class Overrides(val root: AnsibleRoot, val higher: List<VarDefinition>, val lower: List<VarDefinition>)

    companion object {
        /** The overrides of [keyValue] when it is a variable definition of a vars-bearing file, else null. Needs smart mode. */
        fun overridesOf(keyValue: YAMLKeyValue): Overrides? {
            val project = keyValue.project
            val file = keyValue.containingFile?.originalFile?.virtualFile ?: return null
            val context = AnsibleWorkspace.getInstance(project).contextOf(file) ?: return null
            if (context.kind !in KINDS) return null
            val key = keyValue.key ?: return null
            val definitions = VarService.getInstance(project).symbol(context.root, keyValue.keyText).definitions
            val own = definitions.firstOrNull { it.location.file == file && it.location.offset in keyValue.textRange.startOffset..key.textRange.endOffset }
                ?: return null
            val level = levelOf(own) ?: return null
            val workspace = AnsibleWorkspace.getInstance(project)
            val ownMolecule = isMolecule(workspace, context.root, own)
            val reach = HostReach(project, context.root)
            val related = definitions.filter {
                it !== own && it.location != own.location && sameRoleScope(own, it) &&
                    (ownMolecule || !isMolecule(workspace, context.root, it)) && reach.overlaps(own, it)
            }.let(::withoutDuplicateSpecs)
            val ordered = related.sortedWith(compareBy<VarDefinition> { levelOf(it) ?: -1 }.thenBy { it.location.file.path }.thenBy { it.location.offset })
            return Overrides(
                root = context.root,
                higher = ordered.filter { (levelOf(it) ?: -1) > level },
                lower = ordered.filter { other -> levelOf(other)?.let { it < level } == true },
            )
        }

        /**
         * A chooser row for [definition]: `environments/prod/group_vars/all/vars.yml:471` in front, then the layer, the
         * role and the (vault-safe) value preview, since every row names the same variable.
         */
        fun target(project: Project, root: AnsibleRoot, definition: VarDefinition): PsiElement? {
            val anchor = VarLocations.elementAt(project, definition.location) ?: return null
            val where = VarLocations.label(root, definition.location)
            val details = listOfNotNull(
                VarLabels.layerWithLevel(definition),
                definition.roleName?.let { message("gutter.target.role", it) },
                definition.preview?.let { message("gutter.target.value", it) },
            ).joinToString(" \u00B7 ")
            return VarTargetElement(anchor, definition.location, where, details, VarSubject.definition(project, root, definition.name, emptyList(), definition.location))
        }

        private val KINDS = setOf(
            FileKind.ROLE_DEFAULTS, FileKind.ROLE_VARS, FileKind.ROLE_ARGSPEC, FileKind.GROUP_VARS, FileKind.HOST_VARS, FileKind.INVENTORY,
        )
        private val ROLE_KINDS = setOf(VarDefKind.ROLE_DEFAULT, VarDefKind.ROLE_VAR, VarDefKind.SPEC_OPTION)

        /** The precedence of [definition]; a spec option ranks with the role defaults, locals and runtime kinds have none. */
        fun levelOf(definition: VarDefinition): Int? = when (definition.kind) {
            VarDefKind.SPEC_OPTION -> VarsLayer.ROLE_DEFAULTS.level
            VarDefKind.JINJA_LOCAL, VarDefKind.LOOP_VAR, VarDefKind.INDEX_VAR, VarDefKind.TEMPLATE_VARS, VarDefKind.REGISTER -> null
            else -> definition.layer?.level
        }

        /** A spec option and the default of the same role are one declaration. */
        private fun withoutDuplicateSpecs(definitions: List<VarDefinition>): List<VarDefinition> {
            val defaults = definitions.filter { it.kind == VarDefKind.ROLE_DEFAULT }.mapTo(HashSet()) { it.roleName }
            return definitions.filter { it.kind != VarDefKind.SPEC_OPTION || it.roleName !in defaults }
        }

        /** A molecule pseudo-inventory or any definition in a file below a `molecule/` directory (converge, verify, scenario vars). */
        private fun isMolecule(workspace: AnsibleWorkspace, root: AnsibleRoot, definition: VarDefinition): Boolean {
            if (definition.kind == VarDefKind.MOLECULE_INVENTORY) return true
            if (workspace.contextOf(definition.location.file)?.kind in MOLECULE_KINDS) return true
            val relative = VfsUtilCore.getRelativePath(definition.location.file, root.dir) ?: return false
            return MOLECULE_DIR in relative.split('/')
        }

        private const val MOLECULE_DIR = "molecule"
        private val MOLECULE_KINDS = setOf(FileKind.MOLECULE_CONFIG, FileKind.MOLECULE_PLAYBOOK, FileKind.MOLECULE_TASKS, FileKind.MOLECULE_VARS)

        private fun sameRoleScope(own: VarDefinition, other: VarDefinition): Boolean {
            return own.kind !in ROLE_KINDS || other.kind !in ROLE_KINDS || own.roleName == other.roleName
        }
    }
}
