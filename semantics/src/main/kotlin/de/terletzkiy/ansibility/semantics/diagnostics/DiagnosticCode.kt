package de.terletzkiy.ansibility.semantics.diagnostics

/** Severity levels, mapped to IntelliJ highlight types in :plugin. */
enum class Level { ERROR, WARNING, WEAK_WARNING, INFO, OFF }

/** The strictness presets (plan A.6). */
enum class Preset { DOCUMENTED_TYPES, RUNTIME_FAITHFUL, STRICT }

/**
 * The diagnostics contract (plan A.6). Each code is one inspection in :plugin.
 * [documented] / [runtimeFaithful] are the default levels per preset; STRICT upgrades further in [levelFor].
 */
enum class DiagnosticCode(
    val id: String,
    val documented: Level,
    val runtimeFaithful: Level,
    /** True for checks the user asked for; false for Claude-proposed extras (🟣 CLAUDE). */
    val requested: Boolean,
) {
    T001_VALUE_REJECTED("ANS-T001", Level.ERROR, Level.ERROR, true),
    T002_UNSUPPORTED_SUB_OPTION("ANS-T002", Level.ERROR, Level.ERROR, true),
    T003_MISSING_REQUIRED_SUB_OPTION("ANS-T003", Level.ERROR, Level.ERROR, true),
    T004_CHOICE_MISMATCH("ANS-T004", Level.ERROR, Level.ERROR, true),
    T005_NULL_FOR_TYPED_OPTION("ANS-T005", Level.ERROR, Level.ERROR, true),
    T010_SHAPE_CONTRADICTION("ANS-T010", Level.ERROR, Level.WARNING, true),
    T011_COERCED_SCALAR_TO_STR("ANS-T011", Level.ERROR, Level.WARNING, true),
    T013_SCALAR_TYPE_MISMATCH("ANS-T013", Level.ERROR, Level.WARNING, true),
    T014_LEGACY_COERCION("ANS-T014", Level.ERROR, Level.WARNING, true),
    T015_NULL_FOR_OPTIONAL("ANS-T015", Level.WEAK_WARNING, Level.WEAK_WARNING, true),
    T016_STRING_FOR_NUMBER_OR_BOOL("ANS-T016", Level.ERROR, Level.WARNING, true),
    T020_TEMPLATED_VALUE_TYPE("ANS-T020", Level.ERROR, Level.WARNING, true),
    M001_UNKNOWN_MODULE_OPTION("ANS-M001", Level.ERROR, Level.ERROR, true),
    M002_MISSING_MODULE_OPTION("ANS-M002", Level.ERROR, Level.ERROR, true),
    K001_KEYWORD_VALUE_REJECTED("ANS-K001", Level.ERROR, Level.ERROR, true),
    K002_UNKNOWN_KEYWORD("ANS-K002", Level.ERROR, Level.ERROR, true),

    // R7 Ansible Vault (plan amendment R7/R8, "R7 diagnostics"); V001/V002 are the variable codes, so vault uses V1xx.
    /** Malformed envelope: no leading `$ANSIBLE_VAULT`, < 3 header fields, unknown cipher, non-hex, odd length, TAB, missing separators. */
    V101_MALFORMED_ENVELOPE("ANS-V101", Level.ERROR, Level.ERROR, true),

    /** A `!vault` value that YAML folds or flattens (folded `>`, plain one-line scalar). */
    V102_FOLDED_VAULT_VALUE("ANS-V102", Level.ERROR, Level.ERROR, true),

    /** Trailing whitespace on a hex line (ansible-vault fails with "Odd-length string"). */
    V103_TRAILING_WHITESPACE("ANS-V103", Level.ERROR, Level.ERROR, true),

    /** No unlocked id of the root decrypts the value (read from the verification cache; only with an unlocked id). */
    V104_NO_ID_DECRYPTS("ANS-V104", Level.WARNING, Level.WARNING, true),

    /**
     * The label's id does not decrypt the value, but another unlocked id does. WEAK by default (`vault_id_match` off,
     * Ansible's default, where every secret is tried); the policy raises it to WARNING when the root turns
     * `vault_id_match` on, because Ansible then fails. Never ERROR: the IDE's ids may differ from the deployment's.
     */
    V105_LABEL_SECRET_MISMATCH("ANS-V105", Level.WEAK_WARNING, Level.WEAK_WARNING, true),

    /** A `1.2` label that no configured id of the root has. */
    V106_UNKNOWN_VAULT_LABEL("ANS-V106", Level.INFO, Level.INFO, true),

    /**
     * Not a whole-file vault (plan amendment R21, D159; allocated as X94 "pasted `!vault |` block"): a `!vault |` tag
     * line, a preamble, indentation, a byte order mark, quotes or other text before `$ANSIBLE_VAULT`, so Ansible uses
     * the file as it is (copy, template and lookups deliver the envelope text; vars files fail to load). ERROR since
     * the user asked to mark these files as errors.
     */
    V107_NOT_WHOLE_FILE_VAULT("ANS-V107", Level.ERROR, Level.ERROR, true),

    /**
     * Plaintext private key (plan amendment R21, D160–D162; allocated as X95 "key-like file not vaulted"): a complete
     * unencrypted private key in a file (PEM, OpenSSH, PuTTY, OpenPGP, also inside a YAML or JSON value), or a vault
     * password file of a root under version control. ERROR since the user asked for it; the policy caps it at WARNING
     * per finding (`FindingContext`) for passphrase-protected keys, keystores, key-like names without a readable key
     * and files that are not committed yet.
     */
    V108_PLAINTEXT_PRIVATE_KEY("ANS-V108", Level.ERROR, Level.ERROR, true),

    // R8 inventory and host awareness (plan amendment R7/R8, "R8 diagnostics"): requested since the amendment.
    /** Ineffective override: never wins for any reachable (play, playbook dir, env, host); structurally shadowed. */
    P001_INEFFECTIVE_OVERRIDE("ANS-P001", Level.WARNING, Level.WARNING, true),

    /** Fallback only: shadowed only because every current host overrides it in `host_vars` (D34). */
    P001B_FALLBACK_ONLY_OVERRIDE("ANS-P001b", Level.INFO, Level.INFO, true),

    /** Redundant override: the same value as the definition that wins (vault values excluded). */
    P002_REDUNDANT_OVERRIDE("ANS-P002", Level.WEAK_WARNING, Level.WEAK_WARNING, true),

    /** A required spec variable with no value for a reachable (env, host, play). */
    P003_REQUIRED_VAR_UNREACHABLE("ANS-P003", Level.WARNING, Level.WARNING, true),

    /**
     * Possibly undefined (F8.12): an unguarded Jinja use of a variable with no runtime default. WARNING by default;
     * the policy raises it to ERROR when a reachable host has no definition (a witness) or the root's
     * "Unguarded optional variables without a default are always errors" is on.
     */
    V003_POSSIBLY_UNDEFINED("ANS-V003", Level.WARNING, Level.WARNING, true),

    // R23 argument_specs default vs role default (plan amendment R23, D169–D172).
    /**
     * The role's argument_specs documents a `default:` that differs from the role default in `defaults/` (after the
     * option type's conversion). Ansible never applies the documented default, so the documentation is wrong. Requested.
     */
    S003_SPEC_DEFAULT_MISMATCH("ANS-S003", Level.ERROR, Level.ERROR, true),

    // 🟣 CLAUDE extras
    T012_YAML_SCALAR_HAZARD("ANS-T012", Level.WARNING, Level.WARNING, false),
    T012B_UNLOADABLE_SCALAR("ANS-T012b", Level.ERROR, Level.ERROR, false),
    R001_UNRESOLVED_REFERENCE("ANS-R001", Level.ERROR, Level.ERROR, false),
    M003_DEPRECATED_MODULE("ANS-M003", Level.WEAK_WARNING, Level.WEAK_WARNING, false),
    S001_ARG_SPEC_LINT("ANS-S001", Level.WARNING, Level.WARNING, false),
    S002_SPEC_DEFAULTS_SYNC("ANS-S002", Level.WARNING, Level.WARNING, false),

    /** 🟣 CLAUDE (R23): a documented `default:` no defaults file sets, so Ansible never applies it. */
    S004_SPEC_DEFAULT_NOT_APPLIED("ANS-S004", Level.WARNING, Level.WARNING, false),

    /** 🟣 CLAUDE (R23): a role default the argument_specs option does not document. */
    S005_SPEC_DEFAULT_UNDOCUMENTED("ANS-S005", Level.INFO, Level.INFO, false),
    V001_UNDEFINED_VARIABLE("ANS-V001", Level.WEAK_WARNING, Level.WEAK_WARNING, false),
    V002_UNUSED_INVENTORY_VAR("ANS-V002", Level.WEAK_WARNING, Level.WEAK_WARNING, false),
    X001_PLAINTEXT_VAULT_VALUE("ANS-X001", Level.WARNING, Level.WARNING, false),

    /**
     * 🟣 CLAUDE (plan amendment R21, D163): a YAML value that holds a vault envelope without the `!vault` tag, so Ansible
     * passes the envelope text on as a string instead of decrypting it.
     */
    V114_UNTAGGED_VAULT_VALUE("ANS-V114", Level.WARNING, Level.WARNING, false),
    ;

    fun levelFor(preset: Preset): Level = when (preset) {
        Preset.DOCUMENTED_TYPES -> documented
        Preset.RUNTIME_FAITHFUL -> runtimeFaithful
        Preset.STRICT -> if (documented == Level.WEAK_WARNING) Level.WARNING else documented
    }

    companion object {
        fun byId(id: String): DiagnosticCode? = entries.firstOrNull { it.id == id }
    }
}

/**
 * A finding produced by a PSI-free checker. [path] locates the offending value inside the checked tree
 * (e.g. `["haproxy_servers", "0", "port"]`); :plugin maps it back to PSI through the YValue ranges.
 */
data class Finding(
    val code: DiagnosticCode,
    val message: String,
    val range: de.terletzkiy.ansibility.semantics.yaml.SourceRange?,
    val path: List<String> = emptyList(),
    /** Machine-readable hints for quick fixes, e.g. "quote", "nearest-choice=roundrobin". */
    val fixHints: List<String> = emptyList(),
)
