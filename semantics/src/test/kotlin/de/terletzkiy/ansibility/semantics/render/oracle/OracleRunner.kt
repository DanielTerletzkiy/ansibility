package de.terletzkiy.ansibility.semantics.render.oracle

/**
 * Renders the checks of one oracle case for one core with the pure renderer (`semantics.render`). A WU that makes a
 * case render adds a runner class (a Kotlin `object` or a class with a no-argument constructor) in its own test
 * package and names it in `render-oracle/supported.json`; [RenderOracleTest] then compares every outcome.
 *
 * A runner reads what it needs from [OracleCase.inputDir] (the playbook, templates and vars that ran) and from each
 * check's [OracleCheck.subject]; it never reads the expected values. It returns one outcome per check id it
 * attempted; a check without an outcome counts as not attempted.
 */
fun interface OracleRunner {
    fun run(case: OracleCase, core: OracleCore, checks: List<OracleCheck>): Map<String, Outcome>
}

/** What the renderer produced for one check. */
sealed interface Outcome {
    /**
     * A proven result: a [String] (or [ByteArray]) for files, texts and names, a JSON-shaped value (Long, Double,
     * String, Boolean, null, List, Map) for values. [order] says the order of a collection is not reproduced (D79);
     * only its members are then compared.
     */
    class Known(val value: Any?, val order: OrderMark? = null) : Outcome {
        override fun toString(): String = "known $value" + (order?.let { " ($it)" } ?: "")
    }

    /** Text with placeholders: the known [parts] are compared in order, a [Part.Hole] stands for any text. */
    class Partial(val parts: List<Part>) : Outcome {
        override fun toString(): String = parts.joinToString("", prefix = "partial ") { if (it is Part.Text) it.text else "⟨…⟩" }
    }

    /** The error ansible-core would raise; a non-null [message] must occur in ansible-core's message. */
    class Failed(val message: String? = null) : Outcome {
        override fun toString(): String = "failed $message"
    }

    /** The task would succeed (for [TaskField.RESULT] checks). */
    data object Succeeded : Outcome

    /** The task would be skipped (for [TaskField.RESULT] checks). */
    data object Skipped : Outcome

    /** Nothing proven: never compared, counted as a coverage gap. */
    class Placeholder(val reason: String) : Outcome {
        override fun toString(): String = "placeholder ($reason)"
    }
}

/** A piece of a [Outcome.Partial]. */
sealed interface Part {
    data class Text(val text: String) : Part

    data object Hole : Part
}

/** The members of a collection whose order the renderer does not reproduce, and why ("order varies per run"). */
class OrderMark(val members: List<Any?>, val reason: String) {
    override fun toString(): String = "order not reproduced: $reason"
}
