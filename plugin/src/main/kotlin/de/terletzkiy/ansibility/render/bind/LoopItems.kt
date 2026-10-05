package de.terletzkiy.ansibility.render.bind

import de.terletzkiy.ansibility.semantics.render.Binding
import de.terletzkiy.ansibility.semantics.render.LookupResolver
import de.terletzkiy.ansibility.semantics.render.RValue
import de.terletzkiy.ansibility.semantics.render.RValues
import de.terletzkiy.ansibility.semantics.render.RenderMode
import de.terletzkiy.ansibility.semantics.render.RenderOptions
import de.terletzkiy.ansibility.semantics.render.RenderScope
import de.terletzkiy.ansibility.semantics.render.TemplateRenderer
import de.terletzkiy.ansibility.semantics.yaml.YScalar
import de.terletzkiy.ansibility.semantics.yaml.YValue
import de.terletzkiy.ansibility.typeflow.LexerJinjaTokenizer

/** The items of a rendering task's loop (golden 13) and the loop variables each item binds. */
internal object LoopItems {
    class Item(val index: Int, val value: RValue, val label: String, val locals: Map<String, RValue>)

    sealed interface Result {
        /** [total]: the loop's length, also when [items] keeps only some of them. */
        class Items(val items: List<Item>, val total: Int = items.size) : Result

        /** The sequence is not provable: a hole, an error or a `with_<lookup>` the renderer does not emulate. */
        class Unknown(val reason: String) : Result
    }

    const val MAX_ITEMS: Int = 1000
    private const val LABEL_WIDTH = 80
    private const val LOOP_VALUE = "__ansibility_loop__"

    fun evaluate(site: TaskSite, base: RenderScope, options: RenderOptions, lookups: LookupResolver): Result? {
        val loop = site.loop ?: return null
        val value = render(loop.value, base, options, lookups) ?: return Result.Unknown("the loop value has an unknown part")
        val sequence: List<RValue> = when (val keyword = loop.keyword) {
            "loop", "with_list" -> RValues.sequence(value) ?: return Result.Unknown("the loop value is no list")
            "with_items" -> (RValues.sequence(value) ?: listOf(value)).flatMap { RValues.sequence(it) ?: listOf(it) }
            "with_dict" -> (value as? RValue.Dict)?.entries?.map { (k, v) -> RValue.Dict.of(listOf(RValue.Str("key") to k, RValue.Str("value") to v)) }
                ?: return Result.Unknown("with_dict needs a dict")
            "with_indexed_items" -> (RValues.sequence(value) ?: return Result.Unknown("with_indexed_items needs a list"))
                .mapIndexed { i, item -> RValue.List(mutableListOf(RValues.int(i), item)) }
            "with_fileglob" -> {
                val terms = RValues.sequence(value) ?: listOf(value)
                RValues.sequence(lookups.lookup("fileglob", terms, emptyMap()) ?: return Result.Unknown("fileglob")) ?: return Result.Unknown("fileglob")
            }
            else -> return Result.Unknown("$keyword is not emulated")
        }
        if (sequence.size > MAX_ITEMS) return Result.Unknown("more than $MAX_ITEMS items")
        val control = site.loopControl
        val loopVar = site.loopVar
        val indexVar = control?.indexVar?.text
        val extended = (control?.extended as? YScalar)?.text?.lowercase() in setOf("true", "yes", "on")
        val items = sequence.mapIndexed { i, item ->
            val locals = LinkedHashMap<String, RValue>()
            locals[loopVar] = item
            locals["ansible_loop_var"] = RValue.Str(loopVar)
            indexVar?.let { locals[it] = RValues.int(i) }
            if (extended) locals["ansible_loop"] = loopInfo(sequence, i)
            val label = control?.label?.let { label -> render(label, scopeWith(base, locals), options, lookups)?.let { RValues.str(it, options.tuplesAsLists) } }
                ?: RValues.str(item, options.tuplesAsLists)
            Item(i, item, label.take(LABEL_WIDTH), locals)
        }
        return Result.Items(items)
    }

    /** [value] rendered as a templated YAML value of the task, or null when the result is not known. */
    fun render(value: YValue, base: RenderScope, options: RenderOptions, lookups: LookupResolver): RValue? {
        val scope = RenderScope { name -> if (name == LOOP_VALUE) Binding.Yaml(value, "task") else base.lookup(name) }
        val rendered = TemplateRenderer(LexerJinjaTokenizer, scope, options.copy(mode = RenderMode.YAML_VALUE), lookups).renderValue("{{ $LOOP_VALUE }}")
        val result = rendered.value ?: return null
        return result.takeIf { rendered.complete && RValues.holeIn(it) == null && it !is RValue.Undefined }
    }

    fun scopeWith(base: RenderScope, locals: Map<String, RValue>): RenderScope =
        RenderScope { name -> locals[name]?.let { Binding.Computed(it) } ?: base.lookup(name) }

    private fun loopInfo(all: List<RValue>, i: Int): RValue = RValue.Dict.of(
        listOfNotNull(
            RValue.Str("allitems") to RValue.List(all.toMutableList()),
            RValue.Str("index") to RValues.int(i + 1),
            RValue.Str("index0") to RValues.int(i),
            RValue.Str("revindex") to RValues.int(all.size - i),
            RValue.Str("revindex0") to RValues.int(all.size - i - 1),
            RValue.Str("first") to RValue.Bool(i == 0),
            RValue.Str("last") to RValue.Bool(i == all.size - 1),
            RValue.Str("length") to RValues.int(all.size),
            if (i > 0) RValue.Str("previtem") to all[i - 1] else null,
            if (i < all.size - 1) RValue.Str("nextitem") to all[i + 1] else null,
        ),
    )
}
