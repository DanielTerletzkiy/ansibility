package de.terletzkiy.ansibility.settings

import com.intellij.codeHighlighting.HighlightDisplayLevel
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.semantics.diagnostics.DiagnosticCode
import de.terletzkiy.ansibility.semantics.diagnostics.Level
import de.terletzkiy.ansibility.semantics.diagnostics.Preset

/**
 * What a checker knows about one finding beyond its [DiagnosticCode], as far as severity depends on it (plan A.6).
 */
data class FindingContext(
    /** The value is a module option (modules receive coerced values by contract, D5). */
    val onModuleOption: Boolean = false,
    /** The finding is a guaranteed runtime failure; raises an ANS-S001 argument_specs internal error to red (D7). */
    val certainFailure: Boolean = false,
    /** Whether some play applies the declaring role: true, false, or null when not applicable or unknown (D6). */
    val reachable: Boolean? = null,
    /** The module or keyword docs come from another ansible-core version than the root's target. */
    val docsDifferFromTarget: Boolean = false,
    /** The root turns `vault_id_match` on (`VaultStatusService.config(root).idMatch`); raises ANS-V105 to WARNING. */
    val vaultIdMatch: Boolean = false,
    /**
     * ANS-V108 (plan amendment R21, D161): a weaker secret signal: a passphrase-protected key, a keystore, or a key-like
     * name without a readable key. Caps ANS-V108 at WARNING.
     */
    val weakSecretSignal: Boolean = false,
    /** ANS-V108 (D162): the file is not committed yet (untracked, not ignored by the VCS). Caps ANS-V108 at WARNING. */
    val uncommitted: Boolean = false,
) {
    companion object {
        val DEFAULT = FindingContext()
    }
}

/**
 * Decides the severity of every Ansibility diagnostic (plan A.6, D4–D7) from the root's [RootSettings]:
 * 1. the preset level, [DiagnosticCode.levelFor] (Documented types by default; Strict turns weak warnings into warnings);
 * 2. an ANS-S001 [FindingContext.certainFailure] is ERROR;
 * 3. scalar coercions on module options ([MODULE_OPTION_COERCION_CODES]) are silent, unless the root enables
 *    "Module-option scalar coercions" (then the preset level applies) or uses the Strict preset (then ERROR);
 * 4. with "Red for Claude's certain-failure checks" off, [CERTAIN_FAILURE_CODES] are at most WARNING;
 * 5. with "Require a reachable play for red" on, an ERROR without a reachable play becomes WARNING;
 * 6. a finding whose docs differ from the target is capped by [capForDocsMismatch];
 * 7. ANS-V003 is ERROR with a witness host ([FindingContext.certainFailure]) or when the root enables
 *    "Unguarded optional variables without a default are always errors"; ANS-V105 is WARNING under `vault_id_match`;
 * 8. ANS-V108 is at most WARNING for a weaker secret signal ([FindingContext.weakSecretSignal]) or a file that is not
 *    committed yet ([FindingContext.uncommitted]) (plan amendment R21, D161/D162).
 *
 * Reachability never changes severity unless that toggle is on (D6). Safe to call from any thread.
 */
@Service(Service.Level.PROJECT)
class SeverityPolicy(private val project: Project) {

    /** The level of [code] in [root] (the defaults when [root] is null), for a finding without extra context. */
    fun level(code: DiagnosticCode, root: AnsibleRoot?): Level = level(code, root, FindingContext.DEFAULT)

    /** The level of [code] in [root] for a finding described by [context]. */
    fun level(code: DiagnosticCode, root: AnsibleRoot?, context: FindingContext): Level =
        levelFor(code, settingsOf(root), context)

    /**
     * Caps [level] for a module or keyword finding whose docs come from another ansible-core than the target:
     * ANS-M001 follows the root's "Unknown module option when docs ≠ target" (WARNING or OFF), everything else is
     * at most WARNING. Callers that do not pass [FindingContext.docsDifferFromTarget] apply it themselves.
     */
    fun capForDocsMismatch(code: DiagnosticCode, level: Level, root: AnsibleRoot?): Level =
        capForDocsMismatch(code, level, settingsOf(root))

    private fun settingsOf(root: AnsibleRoot?): RootSettings =
        root?.let { AnsibilityProjectSettings.getInstance(project).rootSettings(it) } ?: RootSettings.DEFAULT

    companion object {
        /** Codes that report a value ansible-core would coerce (not reject); silent on module options by default (D5). */
        val MODULE_OPTION_COERCION_CODES: Set<DiagnosticCode> = setOf(
            DiagnosticCode.T011_COERCED_SCALAR_TO_STR,
            DiagnosticCode.T013_SCALAR_TYPE_MISMATCH,
            DiagnosticCode.T014_LEGACY_COERCION,
            DiagnosticCode.T016_STRING_FOR_NUMBER_OR_BOOL,
            DiagnosticCode.T020_TEMPLATED_VALUE_TYPE,
        )

        /** 🟣 CLAUDE's certain-failure checks, red only while "Red for Claude's certain-failure checks" is on (D7). */
        val CERTAIN_FAILURE_CODES: Set<DiagnosticCode> = setOf(
            DiagnosticCode.R001_UNRESOLVED_REFERENCE,
            DiagnosticCode.S001_ARG_SPEC_LINT,
            DiagnosticCode.T012B_UNLOADABLE_SCALAR,
        )

        fun getInstance(project: Project): SeverityPolicy = project.service()

        /** The pure policy: the level of [code] under [settings] for a finding described by [context]. */
        fun levelFor(code: DiagnosticCode, settings: RootSettings, context: FindingContext = FindingContext.DEFAULT): Level {
            var level = code.levelFor(settings.preset)
            if (code == DiagnosticCode.S001_ARG_SPEC_LINT && context.certainFailure) level = Level.ERROR
            if (code == DiagnosticCode.V003_POSSIBLY_UNDEFINED && level != Level.OFF &&
                (context.certainFailure || settings.unguardedOptionalAlwaysError)
            ) level = Level.ERROR
            if (code == DiagnosticCode.V105_LABEL_SECRET_MISMATCH && context.vaultIdMatch && level != Level.OFF) {
                if (Level.WARNING.isMoreSevereThan(level)) level = Level.WARNING
            }
            if (code == DiagnosticCode.V108_PLAINTEXT_PRIVATE_KEY && (context.weakSecretSignal || context.uncommitted)) {
                level = level.atMost(Level.WARNING)
            }
            if (context.onModuleOption && code in MODULE_OPTION_COERCION_CODES) {
                level = when {
                    settings.preset == Preset.STRICT -> Level.ERROR
                    settings.moduleOptionCoercions -> level
                    else -> Level.OFF
                }
            }
            if (code in CERTAIN_FAILURE_CODES && !settings.redForClaudeCertainFailures) level = level.atMost(Level.WARNING)
            if (settings.requireReachablePlayForRed && context.reachable == false) level = level.atMost(Level.WARNING)
            if (context.docsDifferFromTarget) level = capForDocsMismatch(code, level, settings)
            return level
        }

        /** The docs-mismatch cap under [settings]; see the instance method. */
        fun capForDocsMismatch(code: DiagnosticCode, level: Level, settings: RootSettings): Level =
            if (code == DiagnosticCode.M001_UNKNOWN_MODULE_OPTION && settings.unknownModuleOptionWhenDocsDiffer == DocsMismatchSeverity.OFF) {
                Level.OFF
            } else {
                level.atMost(Level.WARNING)
            }
    }
}

/** True when this level is more severe than [other] (ERROR > WARNING > WEAK_WARNING > INFO > OFF). */
fun Level.isMoreSevereThan(other: Level): Boolean = ordinal < other.ordinal

/** This level, lowered to [max] when it is more severe. */
fun Level.atMost(max: Level): Level = if (isMoreSevereThan(max)) max else this

/**
 * The inspection-profile level for this level: INFO is "no highlighting, fix only"
 * ([HighlightDisplayLevel.CONSIDERATION_ATTRIBUTES]), OFF is [HighlightDisplayLevel.DO_NOT_SHOW].
 */
fun Level.toHighlightDisplayLevel(): HighlightDisplayLevel = when (this) {
    Level.ERROR -> HighlightDisplayLevel.ERROR
    Level.WARNING -> HighlightDisplayLevel.WARNING
    Level.WEAK_WARNING -> HighlightDisplayLevel.WEAK_WARNING
    Level.INFO -> HighlightDisplayLevel.CONSIDERATION_ATTRIBUTES
    Level.OFF -> HighlightDisplayLevel.DO_NOT_SHOW
}

/**
 * The highlight type to register a problem with, or null for OFF (do not report). INFO is reported without a
 * highlight: it shows only in the popup and the problems view (plan severity words).
 */
fun Level.toProblemHighlightType(): ProblemHighlightType? = when (this) {
    Level.ERROR -> ProblemHighlightType.GENERIC_ERROR
    Level.WARNING -> ProblemHighlightType.WARNING
    Level.WEAK_WARNING -> ProblemHighlightType.WEAK_WARNING
    Level.INFO -> ProblemHighlightType.INFORMATION
    Level.OFF -> null
}

/** The annotator severity, or null for OFF. */
fun Level.toHighlightSeverity(): HighlightSeverity? = when (this) {
    Level.ERROR -> HighlightSeverity.ERROR
    Level.WARNING -> HighlightSeverity.WARNING
    Level.WEAK_WARNING -> HighlightSeverity.WEAK_WARNING
    Level.INFO -> HighlightSeverity.INFORMATION
    Level.OFF -> null
}
