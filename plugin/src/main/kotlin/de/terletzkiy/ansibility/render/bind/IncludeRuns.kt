package de.terletzkiy.ansibility.render.bind

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import de.terletzkiy.ansibility.context.MoleculeView
import de.terletzkiy.ansibility.render.AnsibilityRenderBundle
import de.terletzkiy.ansibility.resolve.include.IncludeBindings
import de.terletzkiy.ansibility.resolve.include.IncludePath
import de.terletzkiy.ansibility.resolve.include.Includer
import de.terletzkiy.ansibility.semantics.render.Binding
import de.terletzkiy.ansibility.semantics.render.LookupResolver
import de.terletzkiy.ansibility.semantics.render.Placeholder
import de.terletzkiy.ansibility.semantics.render.RValue
import de.terletzkiy.ansibility.semantics.render.RenderOptions
import de.terletzkiy.ansibility.semantics.render.RenderScope
import de.terletzkiy.ansibility.semantics.yaml.YSeq
import de.terletzkiy.ansibility.semantics.yaml.YValue
import de.terletzkiy.ansibility.vars.VarLocations

/**
 * How the include tasks that run a task file take part in rendering a value of that file (or a template one of its
 * tasks renders): one [Variant] per include path ([IncludeBindings.paths]; a role entry file a play also applies directly
 * has a variant without includes), each with the `vars:` of its includers and, per host, the [Run]s of its looping
 * includers (one per item, the product over nested looping includes, outermost first, inner names shadow outer ones).
 *
 * Precedence as ansible-core applies it: the `vars:` of dynamic includes (`include_tasks`, `include_role`) are include
 * params ([Variant.params]), above the included task's own `vars:`, `include_vars`, `set_fact` and role params; those of
 * imports ([Variant.vars]) are task vars below the task's own. An includer's loop is evaluated where the include runs:
 * with the includer's own `vars:` and what the includes outside it give, never the included task's `vars:`.
 *
 * An includer loop or item that cannot be evaluated never fails the render: its names, or the unknown parts of an item,
 * become placeholders. A path whose includers do not give a name keeps the real "is undefined" error, labelled with
 * that path. Call in a read action in smart mode.
 */
internal object IncludeRuns {
    /**
     * One include path of a file as the binder sees it. [label] names it (`rules.yml:10`, extended outward,
     * `rules.yml:8 ← main.yml:2`, while two paths share their direct includer; "run directly by a play" for a direct
     * run), null when nothing needs a label. [vars] are the `vars:` of its imports, [params] those of its dynamic
     * includes, each outer to inner.
     */
    class Variant(val path: IncludePath?, val label: String?, val vars: Map<String, YValue>, val params: Map<String, YValue> = emptyMap())

    /** One run of the included file: the item labels of the looping includers (outermost first) and the names they bind. */
    class Run(val labels: List<String>, val locals: Map<String, RValue>)

    /**
     * The runs of one variant on one host: [total] is the number of runs of every item (before [runs] was capped), null
     * when no includer loops; [unknown] says why an includer loop was not evaluated.
     */
    class Runs(val runs: List<Run>, val total: Int?, val unknown: String?)

    private val SINGLE = Runs(listOf(Run(emptyList(), emptyMap())), null, null)

    /** The loop keywords whose literal list is evaluated item by item (an unknown item part stays a placeholder). */
    private val LITERAL_LOOPS = setOf("loop", "with_list")

    /** Includers a label names at most before it stops growing. */
    private const val MAX_LABEL_DEPTH = 4

    /**
     * The include variants of the task file [file] as a request with [view] sees them: one per include path, or one
     * without includes when nothing includes [file]. Paths label themselves only when there are several or one gives
     * the file something (vars or a loop).
     */
    fun variants(project: Project, file: VirtualFile, view: MoleculeView): List<Variant> {
        val paths = IncludeBindings.paths(project, file, view)
        if (paths.isEmpty()) return listOf(Variant(null, null, emptyMap()))
        val labelled = paths.size > 1 || paths.any { path -> path.includers.any { it.loops || it.vars.isNotEmpty() } }
        val labels = if (labelled) labels(paths) else paths.map { null }
        return paths.mapIndexed { index, path ->
            Variant(path, labels[index], path.importVars().mapValues { it.value.value }, path.params().mapValues { it.value.value })
        }
    }

    /** `rules.yml:10`: the file name and 1-based line of an include task. */
    fun where(includer: Includer): String = "${includer.file.name}:${VarLocations.line(includer.file, includer.task.range.startOffset)}"

    /**
     * One label per path: the direct includer (`rules.yml:10`), extended outward (`rules.yml:8 ← main.yml:2`) while two
     * paths would read the same; a direct run reads "run directly by a play".
     */
    fun labels(paths: List<IncludePath>): List<String> {
        val depth = IntArray(paths.size) { 1 }
        fun labelOf(index: Int): String {
            val path = paths[index]
            if (path.direct) return AnsibilityRenderBundle.message("include.direct")
            return path.includers.take(depth[index]).joinToString(" ← ") { where(it) }
        }
        var labels = paths.indices.map(::labelOf)
        repeat(MAX_LABEL_DEPTH) {
            val shared = labels.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
            if (shared.isEmpty()) return labels
            var grew = false
            for (index in paths.indices) {
                if (labels[index] in shared && depth[index] < paths[index].includers.size) {
                    depth[index]++
                    grew = true
                }
            }
            if (!grew) return labels
            labels = paths.indices.map(::labelOf)
        }
        return labels
    }

    /**
     * The runs of [variant]: the product of its looping includers' items, outermost first, at most [max]. Each
     * includer's loop is evaluated in [scope] of that includer (the host's binder where the include runs, over the
     * locals the outer looping includers bound). [itemLabel] names one item (`item 1 (/etc/a)`); with several looping
     * includers each item label says whose it is (`rules.yml:8 · item 1 (/etc/a)`).
     */
    fun runs(
        variant: Variant,
        scope: (Includer, Map<String, RValue>) -> RenderScope,
        options: RenderOptions,
        lookups: LookupResolver,
        max: Int,
        itemLabel: (LoopItems.Item) -> String,
    ): Runs {
        val looping = variant.path?.includers?.asReversed()?.filter { it.loops }.orEmpty()
        if (looping.isEmpty()) return SINGLE
        var runs = listOf(Run(emptyList(), emptyMap()))
        var total = 1
        var unknown: String? = null
        for (includer in looping) {
            ProgressManager.checkCanceled()
            val site = TaskSite(null, includer.task, emptyMap())
            val where = where(includer)
            val next = ArrayList<Run>()
            var count = 0
            for (run in runs) {
                when (val result = evaluate(site, lenient(scope(includer, run.locals), where), options, lookups, where)) {
                    is LoopItems.Result.Items -> {
                        count = maxOf(count, result.total)
                        for (item in result.items) {
                            if (next.size >= max) break
                            val label = if (looping.size > 1) "$where · ${itemLabel(item)}" else itemLabel(item)
                            next += Run(run.labels + label, run.locals + item.locals)
                        }
                    }
                    is LoopItems.Result.Unknown -> {
                        count = maxOf(count, 1)
                        if (unknown == null) unknown = result.reason
                        val holes = includer.loopNames.associateWith { name ->
                            RValue.Hole(Placeholder.unknown(AnsibilityRenderBundle.message("include.placeholder.loop", name, where, result.reason))) as RValue
                        }
                        if (next.size < max) next += Run(run.labels, run.locals + holes)
                    }
                    null -> if (next.size < max) next += run
                }
            }
            total *= count
            runs = next
        }
        return Runs(runs, total, unknown)
    }

    /**
     * An includer's loop: a literal list item by item, keeping unknown parts as placeholders (ansible-core templates the
     * list where the include runs; a part only the run knows must not fail the included tasks here); any other loop
     * through [LoopItems.evaluate] on a scope where undefined names are placeholders.
     */
    private fun evaluate(site: TaskSite, base: RenderScope, options: RenderOptions, lookups: LookupResolver, where: String): LoopItems.Result? {
        val loop = site.loop ?: return null
        val value = loop.value
        if (value is YSeq && loop.keyword in LITERAL_LOOPS) {
            if (value.items.size > LoopItems.MAX_ITEMS) return LoopItems.Result.Unknown("more than ${LoopItems.MAX_ITEMS} items")
            val sequence = value.items.map { LoopItems.renderLenient(it, base, options, lookups, where) }
            return LoopItems.itemsOf(site, sequence, base, options, lookups)
        }
        return LoopItems.evaluate(site, base, options, lookups)
    }

    /** [base] where a name nothing defines is an unknown value: an includer's loop is never "the run fails here". */
    private fun lenient(base: RenderScope, where: String): RenderScope = RenderScope { name ->
        when (val binding = base.lookup(name)) {
            is Binding.Undefined -> Binding.Unknown(Placeholder.unknown(AnsibilityRenderBundle.message("include.placeholder.unset", name, where)))
            else -> binding
        }
    }
}
