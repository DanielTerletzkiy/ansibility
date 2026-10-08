package de.terletzkiy.ansibility.inspections.undefined

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.HtmlChunk
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.CardContext
import de.terletzkiy.ansibility.api.CardPlacement
import de.terletzkiy.ansibility.api.CardSection
import de.terletzkiy.ansibility.api.CardSubject
import de.terletzkiy.ansibility.api.FileKind
import de.terletzkiy.ansibility.api.HostKey
import de.terletzkiy.ansibility.api.RoleInfo
import de.terletzkiy.ansibility.api.RoleRegistry
import de.terletzkiy.ansibility.api.TemplateContextService
import de.terletzkiy.ansibility.api.VarService
import de.terletzkiy.ansibility.context.MoleculeView
import de.terletzkiy.ansibility.context.host.witness.DefinitionWitnesses
import de.terletzkiy.ansibility.index.PathFacts
import de.terletzkiy.ansibility.resolve.include.IncludeBindings
import de.terletzkiy.ansibility.resolve.include.IncludeCoverage
import de.terletzkiy.ansibility.vars.VarLocations

/**
 * The variable card's ANS-V003 rows (plan amendment R7/R8, F8.2 / F8.12; registered as `ansibilityUndefined`,
 * SECTION, after `ansibilityVault`): for a variable that a role runs without a runtime default,
 * - **Not set for:** the hosts of the role's reach without any definition, per environment ("env test (hosts
 *   test-test1)"), at most [UndefinedRules.MAX_LISTED_HOSTS] per line;
 * - **No runtime default:** "unguarded uses fail where it is not set", naming the roles.
 *
 * In a file that include tasks run ([IncludeBindings.coverageAt], Molecule includers not for production files): no rows
 * when every include path sets the variable (its `vars:`, a looping include's loop variable); when only some do and the
 * witness still finds hosts without it, "Not set" names the includers (and rendering tasks, and a direct run of a role
 * entry file by a play) that do not, instead of the environments. On a key of an include task's own `vars:` (a
 * definition card) the files that include runs decide, since the variable is used there.
 *
 * The roles are those of the card's file (a role file's role; a template's role and the roles of its render contexts),
 * or, from any other file, the roles of the root whose argument specs declare the variable. A role is shown when it has
 * no `defaults/`/`vars/` key for the variable (nor its dependencies), the variable is not `required` in its spec (ANS-P003
 * covers those), and either its spec declares the variable or some reachable host lacks it. Locals, special variables
 * and facts get no rows. Selection-free (D32): the whole reach is evaluated. Reads the background summary through
 * [DefinitionWitnesses]; never decrypts.
 */
class UndefinedCardSection : CardSection {
    override val placement: CardPlacement get() = CardPlacement.SECTION

    override fun section(subject: CardSubject, context: CardContext): HtmlChunk? {
        val variable = subject as? CardSubject.Variable ?: return null
        if (variable.local || UndefinedRules.isAlwaysDefined(variable.name)) return null
        val project = context.project
        val root = variable.root
        val rules = UndefinedRules(project, root, context.file)
        if (rules.isLoopVariable(variable.name)) return null
        // The include tasks that run the file (or the template's rendering tasks) may set it (as ANS-V003 sees them).
        val coverage = coverage(project, context, variable)
        if (coverage.everywhere) return null
        val witnesses = DefinitionWitnesses.getInstance(project)
        val missing = LinkedHashSet<HostKey>()
        val withoutDefault = ArrayList<String>()
        for (role in rolesFor(context, variable)) {
            ProgressManager.checkCanceled()
            if (rules.hasRuntimeDefault(role, variable.name) || rules.setByRoleTasks(role, variable.name)) continue
            val specs = rules.specOptions(role, variable.name)
            if (specs.any { it.required }) continue
            val report = witnesses.ofRole(root, role.ref.name).report(variable.name)
            if (specs.isEmpty() && report.missingOn.isEmpty()) continue
            missing += report.missingHosts
            withoutDefault += role.ref.name
        }
        if (withoutDefault.isEmpty()) return null
        val rows = ArrayList<HtmlChunk>()
        if (coverage.provided) {
            // Some include paths set it: where it is not set are the others, not the environments (only while some host
            // of the role's reach lacks it; an inventory value everywhere leaves nothing unset).
            val lines = coverage.missing.map {
                HtmlChunk.text(AnsibilityUndefinedBundle.message("card.not.set.included", VarLocations.label(root, it.location)))
            } + coverage.unprovidedRenders.map {
                HtmlChunk.text(AnsibilityUndefinedBundle.message("card.not.set.rendered", VarLocations.label(root, it)))
            } + listOfNotNull(HtmlChunk.text(AnsibilityUndefinedBundle.message("card.not.set.direct")).takeIf { coverage.direct })
            if (missing.isNotEmpty() && lines.isNotEmpty()) rows += CardSection.row(AnsibilityUndefinedBundle.message("card.not.set.when"), joinLines(lines))
        } else if (missing.isNotEmpty()) {
            val lines = missing.groupBy({ it.environment }, { it.host }).map { (environment, hosts) ->
                val shown = hosts.take(UndefinedRules.MAX_LISTED_HOSTS).joinToString(", ")
                val more = hosts.size - UndefinedRules.MAX_LISTED_HOSTS
                val list = if (more > 0) "$shown ${AnsibilityUndefinedBundle.message("hosts.more", more)}" else shown
                HtmlChunk.text(AnsibilityUndefinedBundle.message("card.not.set.environment", environment, list))
            }
            rows += CardSection.row(AnsibilityUndefinedBundle.message("card.not.set.for"), joinLines(lines))
        }
        val text = if (context.file.let { AnsibleWorkspace.getInstance(project).contextOf(it)?.roleName } == withoutDefault.singleOrNull()) {
            AnsibilityUndefinedBundle.message("card.no.runtime.default.text")
        } else {
            AnsibilityUndefinedBundle.message("card.no.runtime.default.roles", withoutDefault.joinToString(", "))
        }
        rows += CardSection.row(AnsibilityUndefinedBundle.message("card.no.runtime.default"), HtmlChunk.text(text))
        return HtmlChunk.fragment(*rows.toTypedArray())
    }

    /**
     * What the include tasks give the variable where the card is about it: the card's file ([IncludeBindings.coverageAt]),
     * or, on a key of an include task's own `vars:`, the files that include runs (where the variable is used).
     */
    private fun coverage(project: Project, context: CardContext, variable: CardSubject.Variable): IncludeCoverage {
        val reached = variable.definition?.let { IncludeBindings.filesRunByVarsKey(project, it) }.orEmpty()
        if (reached.isNotEmpty()) {
            return IncludeCoverage.union(reached.map { IncludeBindings.coverageAt(project, it, variable.name, MoleculeView.forAnalysis(project, it)) })
        }
        return IncludeBindings.coverageAt(project, context.file, variable.name, MoleculeView.forAnalysis(project, context.file))
    }

    /** The roles the card's file runs the variable in, else the roles whose specs declare it (same root, at most a few). */
    private fun rolesFor(context: CardContext, variable: CardSubject.Variable): List<RoleInfo> {
        val project = context.project
        val root = variable.root
        val registry = RoleRegistry.getInstance(project)
        val fileContext = AnsibleWorkspace.getInstance(project).contextOf(context.file)
        val roles = LinkedHashMap<String, RoleInfo>()
        registry.roleOf(context.file)?.takeIf { it.ref.rootDir == root.dir }?.let { roles[it.ref.dir.url] = it }
        if (fileContext?.kind == FileKind.ROLE_TEMPLATE || PathFacts.isJ2(context.file.name)) {
            for (render in TemplateContextService.getInstance(project).renderContexts(context.file)) {
                val ref = render.role?.takeIf { it.rootDir == root.dir } ?: continue
                registry.role(root, ref.name)?.let { roles.putIfAbsent(it.ref.dir.url, it) }
            }
        }
        if (roles.isEmpty()) {
            for (binding in VarService.getInstance(project).symbol(root, variable.name).specBindings) {
                if (binding.role.rootDir != root.dir) continue
                registry.role(root, binding.role.name)?.let { roles.putIfAbsent(it.ref.dir.url, it) }
            }
        }
        return roles.values.take(MAX_ROLES)
    }

    private fun joinLines(lines: List<HtmlChunk>): HtmlChunk {
        val children = ArrayList<HtmlChunk>()
        lines.forEachIndexed { index, line ->
            if (index > 0) children += HtmlChunk.br()
            children += line
        }
        return HtmlChunk.fragment(*children.toTypedArray())
    }

    private companion object {
        const val MAX_ROLES = 5
    }
}
