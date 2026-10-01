package de.terletzkiy.ansibility.semantics.coerce

import de.terletzkiy.ansibility.semantics.value.IndeterminateValueException
import de.terletzkiy.ansibility.semantics.value.PyException
import de.terletzkiy.ansibility.semantics.value.PyValue

/** The outcome of one ported ansible-core check on a known value. */
sealed interface CheckResult {
    /** The check passed; [coerced] is what ansible-core continues with. */
    data class Accepted(val coerced: PyValue) : CheckResult

    /**
     * The check raised an exception that ansible-core turns into a validation error
     * ([errorClass] is `TypeError` or `ValueError`; [message] is the exception text).
     */
    data class Rejected(val message: String, val errorClass: String = "TypeError") : CheckResult

    /**
     * The check raised an exception ansible-core does not catch (e.g. `OverflowError` from `int(Decimal('inf'))`):
     * the task crashes with a traceback instead of printing a validation message.
     */
    data class Crash(val message: String, val exceptionClass: String) : CheckResult

    /** The outcome depends on a value this layer cannot see (vault content, a template result, an unloadable file). */
    data class Indeterminate(val reason: String) : CheckResult

    companion object {
        /** Exceptions `_validate_argument_types` and `_validate_elements` catch. */
        internal val CAUGHT: Set<String> = setOf("TypeError", "ValueError")

        /** Runs [block] and classifies the Python exception it raises, if any. */
        internal fun of(caught: Set<String> = CAUGHT, block: () -> PyValue): CheckResult = try {
            Accepted(block())
        } catch (e: PyException) {
            if (e.pyClass in caught) Rejected(e.message.orEmpty(), e.pyClass) else Crash(e.message.orEmpty(), e.pyClass)
        } catch (e: IndeterminateValueException) {
            Indeterminate(e.reason)
        }
    }
}
