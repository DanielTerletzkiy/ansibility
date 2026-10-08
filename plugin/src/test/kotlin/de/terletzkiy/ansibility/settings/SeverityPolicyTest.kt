package de.terletzkiy.ansibility.settings

import de.terletzkiy.ansibility.semantics.diagnostics.DiagnosticCode
import de.terletzkiy.ansibility.semantics.diagnostics.DiagnosticCode.K002_UNKNOWN_KEYWORD
import de.terletzkiy.ansibility.semantics.diagnostics.DiagnosticCode.M001_UNKNOWN_MODULE_OPTION
import de.terletzkiy.ansibility.semantics.diagnostics.DiagnosticCode.M002_MISSING_MODULE_OPTION
import de.terletzkiy.ansibility.semantics.diagnostics.DiagnosticCode.M003_DEPRECATED_MODULE
import de.terletzkiy.ansibility.semantics.diagnostics.DiagnosticCode.R001_UNRESOLVED_REFERENCE
import de.terletzkiy.ansibility.semantics.diagnostics.DiagnosticCode.S001_ARG_SPEC_LINT
import de.terletzkiy.ansibility.semantics.diagnostics.DiagnosticCode.S003_SPEC_DEFAULT_MISMATCH
import de.terletzkiy.ansibility.semantics.diagnostics.DiagnosticCode.S004_SPEC_DEFAULT_NOT_APPLIED
import de.terletzkiy.ansibility.semantics.diagnostics.DiagnosticCode.S005_SPEC_DEFAULT_UNDOCUMENTED
import de.terletzkiy.ansibility.semantics.diagnostics.DiagnosticCode.T001_VALUE_REJECTED
import de.terletzkiy.ansibility.semantics.diagnostics.DiagnosticCode.T004_CHOICE_MISMATCH
import de.terletzkiy.ansibility.semantics.diagnostics.DiagnosticCode.T010_SHAPE_CONTRADICTION
import de.terletzkiy.ansibility.semantics.diagnostics.DiagnosticCode.T011_COERCED_SCALAR_TO_STR
import de.terletzkiy.ansibility.semantics.diagnostics.DiagnosticCode.T012B_UNLOADABLE_SCALAR
import de.terletzkiy.ansibility.semantics.diagnostics.DiagnosticCode.T012_YAML_SCALAR_HAZARD
import de.terletzkiy.ansibility.semantics.diagnostics.DiagnosticCode.T013_SCALAR_TYPE_MISMATCH
import de.terletzkiy.ansibility.semantics.diagnostics.DiagnosticCode.T015_NULL_FOR_OPTIONAL
import de.terletzkiy.ansibility.semantics.diagnostics.DiagnosticCode.T016_STRING_FOR_NUMBER_OR_BOOL
import de.terletzkiy.ansibility.semantics.diagnostics.DiagnosticCode.T020_TEMPLATED_VALUE_TYPE
import de.terletzkiy.ansibility.semantics.diagnostics.DiagnosticCode.V001_UNDEFINED_VARIABLE
import de.terletzkiy.ansibility.semantics.diagnostics.DiagnosticCode.V003_POSSIBLY_UNDEFINED
import de.terletzkiy.ansibility.semantics.diagnostics.DiagnosticCode.V105_LABEL_SECRET_MISMATCH
import de.terletzkiy.ansibility.semantics.diagnostics.DiagnosticCode.V108_PLAINTEXT_PRIVATE_KEY
import de.terletzkiy.ansibility.semantics.diagnostics.Level
import de.terletzkiy.ansibility.semantics.diagnostics.Level.ERROR
import de.terletzkiy.ansibility.semantics.diagnostics.Level.INFO
import de.terletzkiy.ansibility.semantics.diagnostics.Level.OFF
import de.terletzkiy.ansibility.semantics.diagnostics.Level.WARNING
import de.terletzkiy.ansibility.semantics.diagnostics.Level.WEAK_WARNING
import de.terletzkiy.ansibility.semantics.diagnostics.Preset
import de.terletzkiy.ansibility.semantics.diagnostics.Preset.DOCUMENTED_TYPES
import de.terletzkiy.ansibility.semantics.diagnostics.Preset.RUNTIME_FAITHFUL
import de.terletzkiy.ansibility.semantics.diagnostics.Preset.STRICT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The severity matrix of plan A.6 and decisions D4–D7, on the pure policy. */
class SeverityPolicyTest {
    private fun level(
        code: DiagnosticCode,
        preset: Preset = DOCUMENTED_TYPES,
        context: FindingContext = FindingContext.DEFAULT,
        settings: RootSettings.() -> RootSettings = { this },
    ): Level = SeverityPolicy.levelFor(code, RootSettings(preset = preset).settings(), context)

    private val onModuleOption = FindingContext(onModuleOption = true)

    @Test
    fun presetMatrix() {
        // code to (Documented, Runtime-faithful, Strict)
        val expected = mapOf(
            T001_VALUE_REJECTED to Triple(ERROR, ERROR, ERROR),
            T004_CHOICE_MISMATCH to Triple(ERROR, ERROR, ERROR),
            T010_SHAPE_CONTRADICTION to Triple(ERROR, WARNING, ERROR),
            T011_COERCED_SCALAR_TO_STR to Triple(ERROR, WARNING, ERROR),
            T013_SCALAR_TYPE_MISMATCH to Triple(ERROR, WARNING, ERROR),
            T016_STRING_FOR_NUMBER_OR_BOOL to Triple(ERROR, WARNING, ERROR),
            T020_TEMPLATED_VALUE_TYPE to Triple(ERROR, WARNING, ERROR),
            T015_NULL_FOR_OPTIONAL to Triple(WEAK_WARNING, WEAK_WARNING, WARNING),
            T012_YAML_SCALAR_HAZARD to Triple(WARNING, WARNING, WARNING),
            T012B_UNLOADABLE_SCALAR to Triple(ERROR, ERROR, ERROR),
            R001_UNRESOLVED_REFERENCE to Triple(ERROR, ERROR, ERROR),
            S001_ARG_SPEC_LINT to Triple(WARNING, WARNING, WARNING),
            S003_SPEC_DEFAULT_MISMATCH to Triple(ERROR, ERROR, ERROR),
            S004_SPEC_DEFAULT_NOT_APPLIED to Triple(WARNING, WARNING, WARNING),
            S005_SPEC_DEFAULT_UNDOCUMENTED to Triple(INFO, INFO, INFO),
            M001_UNKNOWN_MODULE_OPTION to Triple(ERROR, ERROR, ERROR),
            M003_DEPRECATED_MODULE to Triple(WEAK_WARNING, WEAK_WARNING, WARNING),
            V001_UNDEFINED_VARIABLE to Triple(WEAK_WARNING, WEAK_WARNING, WARNING),
        )
        for ((code, levels) in expected) {
            assertEquals("$code documented", levels.first, level(code, DOCUMENTED_TYPES))
            assertEquals("$code runtime-faithful", levels.second, level(code, RUNTIME_FAITHFUL))
            assertEquals("$code strict", levels.third, level(code, STRICT))
        }
    }

    @Test
    fun theRequestedSpecDefaultMismatchIsRedWhateverTheCertainFailureToggle() {
        // Plan amendment R23 (D169): a requested check, not one of Claude's certain-failure checks.
        assertFalse(S003_SPEC_DEFAULT_MISMATCH in SeverityPolicy.CERTAIN_FAILURE_CODES)
        assertEquals(ERROR, level(S003_SPEC_DEFAULT_MISMATCH) { copy(redForClaudeCertainFailures = false) })
    }

    @Test
    fun defaultPresetIsDocumentedTypes() {
        assertEquals(ERROR, SeverityPolicy.levelFor(T011_COERCED_SCALAR_TO_STR, RootSettings.DEFAULT))
    }

    @Test
    fun weakWarningsNeverBecomeRed() {
        for (preset in Preset.entries) {
            assertTrue(preset.name, level(V001_UNDEFINED_VARIABLE, preset) != ERROR)
        }
    }

    @Test
    fun moduleOptionCoercionsAreSilentByDefault() {
        for (code in SeverityPolicy.MODULE_OPTION_COERCION_CODES) {
            assertEquals("$code documented", OFF, level(code, DOCUMENTED_TYPES, onModuleOption))
            assertEquals("$code runtime-faithful", OFF, level(code, RUNTIME_FAITHFUL, onModuleOption))
            assertEquals("$code strict makes them red", ERROR, level(code, STRICT, onModuleOption))
        }
    }

    @Test
    fun moduleOptionCoercionToggleUsesThePresetLevel() {
        val enabled: RootSettings.() -> RootSettings = { copy(moduleOptionCoercions = true) }
        assertEquals(ERROR, level(T011_COERCED_SCALAR_TO_STR, DOCUMENTED_TYPES, onModuleOption, enabled))
        assertEquals(WARNING, level(T011_COERCED_SCALAR_TO_STR, RUNTIME_FAITHFUL, onModuleOption, enabled))
        assertEquals(ERROR, level(T016_STRING_FOR_NUMBER_OR_BOOL, STRICT, onModuleOption, enabled))
    }

    @Test
    fun moduleOptionRejectionsAndShapeContradictionsStayRed() {
        assertEquals(ERROR, level(T001_VALUE_REJECTED, DOCUMENTED_TYPES, onModuleOption))
        assertEquals(ERROR, level(T004_CHOICE_MISMATCH, RUNTIME_FAITHFUL, onModuleOption))
        assertEquals("a dict for a str option is no scalar coercion", ERROR, level(T010_SHAPE_CONTRADICTION, DOCUMENTED_TYPES, onModuleOption))
        assertEquals(ERROR, level(M001_UNKNOWN_MODULE_OPTION, DOCUMENTED_TYPES, onModuleOption))
    }

    @Test
    fun rolesVarsAreNotAffectedByTheModuleOptionRule() {
        assertEquals(ERROR, level(T011_COERCED_SCALAR_TO_STR, DOCUMENTED_TYPES))
        assertEquals(WARNING, level(T011_COERCED_SCALAR_TO_STR, RUNTIME_FAITHFUL))
    }

    @Test
    fun certainFailureChecksAreRedUnlessTurnedOff() {
        val off: RootSettings.() -> RootSettings = { copy(redForClaudeCertainFailures = false) }
        val internalError = FindingContext(certainFailure = true)
        assertEquals(ERROR, level(R001_UNRESOLVED_REFERENCE))
        assertEquals(ERROR, level(T012B_UNLOADABLE_SCALAR))
        assertEquals("an argument_specs internal error is red (D7)", ERROR, level(S001_ARG_SPEC_LINT, context = internalError))
        assertEquals("other spec lint stays a warning", WARNING, level(S001_ARG_SPEC_LINT))

        assertEquals(WARNING, level(R001_UNRESOLVED_REFERENCE, settings = off))
        assertEquals(WARNING, level(T012B_UNLOADABLE_SCALAR, STRICT, settings = off))
        assertEquals(WARNING, level(S001_ARG_SPEC_LINT, context = internalError, settings = off))
        assertEquals("requested checks are not affected", ERROR, level(T001_VALUE_REJECTED, settings = off))
        assertEquals("the toggle never raises", WARNING, level(T012_YAML_SCALAR_HAZARD, settings = off))
    }

    @Test
    fun reachabilityChangesNothingUnlessRequired() {
        val unreachable = FindingContext(reachable = false)
        assertEquals("D6: red without a play", ERROR, level(T010_SHAPE_CONTRADICTION, context = unreachable))

        val required: RootSettings.() -> RootSettings = { copy(requireReachablePlayForRed = true) }
        assertEquals(WARNING, level(T010_SHAPE_CONTRADICTION, context = unreachable, settings = required))
        assertEquals(WARNING, level(T001_VALUE_REJECTED, context = unreachable, settings = required))
        assertEquals(ERROR, level(T010_SHAPE_CONTRADICTION, context = FindingContext(reachable = true), settings = required))
        assertEquals("unknown reachability keeps red", ERROR, level(T010_SHAPE_CONTRADICTION, settings = required))
        assertEquals(WEAK_WARNING, level(T015_NULL_FOR_OPTIONAL, context = unreachable, settings = required))
    }

    @Test
    fun docsMismatchCapsModuleAndKeywordFindings() {
        val mismatch = FindingContext(docsDifferFromTarget = true)
        assertEquals(WARNING, level(M001_UNKNOWN_MODULE_OPTION, context = mismatch))
        assertEquals(WARNING, level(M002_MISSING_MODULE_OPTION, context = mismatch))
        assertEquals(WARNING, level(K002_UNKNOWN_KEYWORD, context = mismatch))
        assertEquals("the cap never raises", WEAK_WARNING, level(M003_DEPRECATED_MODULE, context = mismatch))
        assertEquals(WARNING, level(T011_COERCED_SCALAR_TO_STR, STRICT, FindingContext(onModuleOption = true, docsDifferFromTarget = true)))

        val off: RootSettings.() -> RootSettings = { copy(unknownModuleOptionWhenDocsDiffer = DocsMismatchSeverity.OFF) }
        assertEquals(OFF, level(M001_UNKNOWN_MODULE_OPTION, context = mismatch, settings = off))
        assertEquals("only unknown options are switched off", WARNING, level(M002_MISSING_MODULE_OPTION, context = mismatch, settings = off))
        assertEquals("without a mismatch the toggle does nothing", ERROR, level(M001_UNKNOWN_MODULE_OPTION, settings = off))
    }

    @Test
    fun docsMismatchHelperForCallers() {
        assertEquals(WARNING, SeverityPolicy.capForDocsMismatch(M001_UNKNOWN_MODULE_OPTION, ERROR, RootSettings.DEFAULT))
        assertEquals(WEAK_WARNING, SeverityPolicy.capForDocsMismatch(M003_DEPRECATED_MODULE, WEAK_WARNING, RootSettings.DEFAULT))
        assertEquals(OFF, SeverityPolicy.capForDocsMismatch(M001_UNKNOWN_MODULE_OPTION, ERROR, RootSettings(unknownModuleOptionWhenDocsDiffer = DocsMismatchSeverity.OFF)))
    }

    @Test
    fun levelOrdering() {
        assertTrue(ERROR.isMoreSevereThan(WARNING))
        assertTrue(WEAK_WARNING.isMoreSevereThan(INFO))
        assertFalse(OFF.isMoreSevereThan(INFO))
        assertEquals(WARNING, ERROR.atMost(WARNING))
        assertEquals(WEAK_WARNING, WEAK_WARNING.atMost(WARNING))
        assertEquals(OFF, OFF.atMost(ERROR))
    }

    @Test
    fun possiblyUndefinedIsRedWithAWitnessOrTheSetting() {
        assertEquals(WARNING, level(V003_POSSIBLY_UNDEFINED))
        assertEquals(WARNING, level(V003_POSSIBLY_UNDEFINED, RUNTIME_FAITHFUL))
        assertEquals(ERROR, level(V003_POSSIBLY_UNDEFINED, context = FindingContext(certainFailure = true)))
        assertEquals(ERROR, level(V003_POSSIBLY_UNDEFINED) { copy(unguardedOptionalAlwaysError = true) })
        assertEquals(
            "the reachable-play toggle still caps red without a reachable play (D6)",
            WARNING,
            level(V003_POSSIBLY_UNDEFINED, context = FindingContext(certainFailure = true, reachable = false)) {
                copy(requireReachablePlayForRed = true)
            },
        )
    }

    @Test
    fun labelMismatchIsAWarningUnderVaultIdMatch() {
        assertEquals(WEAK_WARNING, level(V105_LABEL_SECRET_MISMATCH))
        assertEquals(WARNING, level(V105_LABEL_SECRET_MISMATCH, context = FindingContext(vaultIdMatch = true)))
        assertEquals("never red: the IDE's ids may differ from the deployment's", WARNING, level(V105_LABEL_SECRET_MISMATCH, STRICT, FindingContext(vaultIdMatch = true)))
    }

    @Test
    fun plaintextKeysAreErrorsUnlessTheSignalIsWeakerOrTheFileIsNotCommitted() {
        // R21 (D160–D162): a complete unencrypted key in a committed file (or without VCS) is red in every preset.
        for (preset in Preset.entries) assertEquals(preset.name, ERROR, level(V108_PLAINTEXT_PRIVATE_KEY, preset))
        assertEquals("protected key, keystore, key-like name", WARNING, level(V108_PLAINTEXT_PRIVATE_KEY, context = FindingContext(weakSecretSignal = true)))
        assertEquals("untracked: encrypt it before you commit", WARNING, level(V108_PLAINTEXT_PRIVATE_KEY, STRICT, FindingContext(uncommitted = true)))
        assertEquals(WARNING, level(V108_PLAINTEXT_PRIVATE_KEY, context = FindingContext(weakSecretSignal = true, uncommitted = true)))
        assertEquals("other codes ignore the secret context", ERROR, level(T001_VALUE_REJECTED, context = FindingContext(weakSecretSignal = true, uncommitted = true)))
    }
}
