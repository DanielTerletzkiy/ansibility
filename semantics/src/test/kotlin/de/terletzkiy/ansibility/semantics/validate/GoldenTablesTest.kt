package de.terletzkiy.ansibility.semantics.validate

import de.terletzkiy.ansibility.semantics.CoreVersion
import de.terletzkiy.ansibility.semantics.coerce.Booleans
import de.terletzkiy.ansibility.semantics.coerce.CheckResult
import de.terletzkiy.ansibility.semantics.coerce.CheckType
import de.terletzkiy.ansibility.semantics.coerce.CoreSemantics
import de.terletzkiy.ansibility.semantics.coerce.HumanToBytes
import de.terletzkiy.ansibility.semantics.coerce.PathEnvironment
import de.terletzkiy.ansibility.semantics.schema.OptionType
import de.terletzkiy.ansibility.semantics.testutil.YamlText
import de.terletzkiy.ansibility.semantics.value.PyRepr
import de.terletzkiy.ansibility.semantics.value.PyValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * The Kotlin port must agree 100% with the golden tables recorded from real ansible-core by
 * `tools/docgen/goldens/gen_goldens.py` (2.18.8 in the target repo's Docker image, 2.21.4 locally): acceptance,
 * error classes in order, crashes, and the Python repr of every coerced value. A mismatch is a port bug; the
 * tables are never edited by hand.
 */
class GoldenTablesTest {
    /** The environment gen_goldens.py pins before calling `check_type_path`. */
    private val environment = PathEnvironment(
        variables = mapOf("HOME" to "/home/golden", "GOLDEN_VAR" to "gv", "GOLDEN_EMPTY" to ""),
        home = "/home/golden",
    )

    @ParameterizedTest
    @ValueSource(strings = ["2.18.8", "2.21.4"])
    fun `port agrees with ansible-core`(version: String) {
        val table = GoldenJson.parse(javaClass.getResource("/goldens/$version.json")!!.readText()) as Map<*, *>
        assertEquals(version, table["ansible_core"])
        val semantics = CoreSemantics(CoreVersion.parse(version)!!)
        val cases = (table["cases"] as List<*>).map { it as Map<*, *> }
        assertTrue(cases.size > 5000, "golden table looks truncated: ${cases.size} cases")

        val mismatches = cases.mapNotNull { case ->
            val problem = try {
                compare(case, semantics)
            } catch (e: Exception) {
                "threw ${e::class.simpleName}: ${e.message}"
            }
            problem?.let { "${case["case"]}\n      $it" }
        }
        val byKind = cases.groupingBy { it["kind"] }.eachCount()
        assertTrue(
            mismatches.isEmpty(),
            "${mismatches.size} of ${cases.size} cases disagree with ansible-core $version ($byKind):\n  " +
                mismatches.take(60).joinToString("\n  "),
        )
    }

    private fun compare(case: Map<*, *>, semantics: CoreSemantics): String? {
        val yaml = case["yaml"] as String?
        val value = yaml?.let(::load)
        return when (case["kind"]) {
            "check_type" -> compareResult(case, CheckType(semantics, environment).check(OptionType.parse(case["type"] as String), value!!))
            "human_to_bytes" -> compareResult(case, HumanToBytes.convert(value!!, case["default_unit"] as String?, case["isbits"] as Boolean))
            "boolean" -> compareResult(case, Booleans.boolean(value!!, case["strict"] as Boolean, semantics))
            "validate" -> compareValidation(case, semantics, value)
            else -> "unknown kind ${case["kind"]}"
        }
    }

    private fun load(yaml: String): PyValue = PyValue.fromYValue(YamlText.map("v: $yaml")["v"]!!)

    private fun compareResult(case: Map<*, *>, actual: CheckResult): String? {
        val expectedAccepted = case["accepted"] as Boolean
        val expectedCrash = case["crash"] as Boolean
        val expectedClass = case["error_class"] as String?
        val expectedRepr = case["coerced_repr"] as String?
        val outOfModel = case["out_of_model"] == true
        return when (actual) {
            is CheckResult.Accepted -> when {
                !expectedAccepted -> "expected rejection ($expectedClass), got Accepted(${PyRepr.display(actual.coerced)})"
                outOfModel -> "expected Indeterminate (value outside the model), got Accepted(${PyRepr.display(actual.coerced)})"
                PyRepr.repr(actual.coerced) != expectedRepr -> "coerced repr ${PyRepr.repr(actual.coerced)} != expected $expectedRepr"
                else -> null
            }
            is CheckResult.Rejected -> when {
                expectedAccepted -> "expected Accepted($expectedRepr), got Rejected(${actual.errorClass}: ${actual.message})"
                expectedCrash -> "expected crash $expectedClass, got Rejected(${actual.errorClass}: ${actual.message})"
                actual.errorClass != expectedClass -> "error class ${actual.errorClass} != $expectedClass"
                else -> null
            }
            is CheckResult.Crash -> when {
                !expectedCrash -> "expected ${if (expectedAccepted) "Accepted($expectedRepr)" else "rejection $expectedClass"}, " +
                    "got Crash(${actual.exceptionClass}: ${actual.message})"
                actual.exceptionClass != expectedClass -> "crash class ${actual.exceptionClass} != $expectedClass"
                else -> null
            }
            is CheckResult.Indeterminate -> if (outOfModel) null else "unexpected Indeterminate(${actual.reason})"
        }
    }

    private fun compareValidation(case: Map<*, *>, semantics: CoreSemantics, value: PyValue?): String? {
        val option = SpecText.option("v", case["spec"] as String)
        val params = if (value == null) PyValue.Dict.EMPTY else PyValue.Dict.of("v" to value)
        val result = ArgumentSpecValidator(semantics, environment).validate(mapOf("v" to option), params)
        val expectedErrors = (case["errors"] as List<*>).map { it as String }
        if (case["crash"] as Boolean) {
            val crash = result.crash ?: return "expected crash ${case["error_class"]}, got errors ${result.errors.map { it.errorClass }}"
            return if (crash.exceptionClass == case["error_class"]) null else "crash ${crash.exceptionClass} != ${case["error_class"]}"
        }
        result.crash?.let { return "unexpected crash ${it.exceptionClass}: ${it.message} at ${it.path}" }
        val actualErrors = result.errors.map { it.errorClass }
        if (actualErrors != expectedErrors) {
            return "errors $actualErrors != expected $expectedErrors (${result.errors.joinToString(" | ") { it.message }})"
        }
        if (case["out_of_model"] == true) return if (result.indeterminate.isNotEmpty()) null else "expected indeterminate paths"
        val coerced = result.validated!!["v"]?.let(PyRepr::repr)
        return if (coerced == case["coerced_repr"]) null else "coerced $coerced != expected ${case["coerced_repr"]}"
    }
}
