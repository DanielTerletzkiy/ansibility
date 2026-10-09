package de.terletzkiy.ansibility.model.drift

import com.intellij.openapi.vfs.VirtualFile
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.model.role.RoleCopy

// The role-drift model (plan amendments R9, F9.5, and R24). It describes how role copies differ from the reference copy,
// the golden root's copy (the setting of R24, D177), by path only: no content, and no hash, ever leaves `RoleDriftService`. The wording is always
// "differs from golden", never "outdated": the direction of a difference is unknown (the haproxy repo copies fix
// golden's molecule check).

/**
 * The drift tier of a role copy: the highest [DriftCategory] that any path differing from the reference touches.
 */
enum class DriftTier {
    /** The copy is the reference itself (the golden root's copy). */
    REFERENCE,

    /** No path differs from the reference. */
    IDENTICAL,

    /** Every differing path is below `molecule/`. */
    MOLECULE_ONLY,

    /** Differences in `meta/argument_specs.yml`, `defaults/` or `vars/`, possibly also below `molecule/`. */
    SPEC_DEFAULTS,

    /** A difference anywhere else: `tasks/`, `handlers/`, `templates/`, `files/`, `meta/main.yml`, … */
    BEHAVIOUR,

    /** The golden root has no copy of the role name (or no golden root is set): nothing to compare against (variants only). */
    NO_REFERENCE,
    ;

    /** Whether the copy differs from an existing reference. */
    val differs: Boolean get() = this == MOLECULE_ONLY || this == SPEC_DEFAULTS || this == BEHAVIOUR
}

/** What part of a role a path belongs to, ordered by how much a difference there matters. */
enum class DriftCategory(val tier: DriftTier) {
    /** Below `molecule/`: test scenarios only. */
    MOLECULE(DriftTier.MOLECULE_ONLY),

    /** `meta/argument_specs.y(a)ml`, `defaults/` and `vars/`: the role's interface and values. */
    SPEC_DEFAULTS(DriftTier.SPEC_DEFAULTS),

    /** Everything else: what the role does. */
    BEHAVIOUR(DriftTier.BEHAVIOUR),
}

/** Options of the drift comparison (plan amendment R9, D41). */
data class DriftOptions(
    /** "Ignore `molecule/` in drift": molecule files take no part in tiers or variants. Off by default (byte identity). */
    val ignoreMolecule: Boolean = false,
)

/**
 * The paths in which two copies differ, relative to the role directory and sorted. For a copy compared with its
 * reference, the reference is the first side.
 */
data class DriftPaths(
    /** Present in both copies with different content. */
    val changed: List<String>,
    /** Present only in the first side (the reference). */
    val onlyInReference: List<String>,
    /** Present only in the second side (the copy). */
    val onlyHere: List<String>,
    /**
     * The differing paths whose content must never be shown, only "differs (content not shown)": names that indicate
     * key material or secrets (`*.key`, `*.password`, `files/ssl/`, `.env*`, `vault*`, …) and whole-file vaults.
     */
    val sensitive: Set<String>,
) {
    /** Every differing path, sorted. */
    val all: List<String> get() = (changed + onlyInReference + onlyHere).sorted()

    /** The number of differing paths (the length of a compare chain over them). */
    val size: Int get() = changed.size + onlyInReference.size + onlyHere.size

    val isEmpty: Boolean get() = size == 0

    companion object {
        val NONE: DriftPaths = DriftPaths(emptyList(), emptyList(), emptyList(), emptySet())
    }
}

/** The drift of one role copy. */
data class CopyDrift(
    val copy: RoleCopy,
    val tier: DriftTier,
    /** The paths that differ from the reference; empty for the reference, identical copies and names without one. */
    val paths: DriftPaths,
    /** The index of the copy's variant in [RoleDrift.variants]. */
    val variant: Int,
    /** The number of files that take part in the comparison (after the skip list and [DriftOptions]). */
    val fileCount: Int,
)

/** Copies with byte-identical content (the same relative paths, lengths and content hashes). */
data class RoleVariant(val index: Int, val copies: List<RoleCopy>) {
    /** The number of copies with this content. */
    val size: Int get() = copies.size
}

/** The drift of one role name across all its copies. */
data class RoleDrift(
    val name: String,
    /** The reference copy (the golden root's), or null when the golden root has no copy of the role or none is set. */
    val reference: RoleCopy?,
    /** Every copy in catalog order (reference first). */
    val copies: List<CopyDrift>,
    /** The variants in order of their first copy; the reference's variant comes first. */
    val variants: List<RoleVariant>,
    /** The options the drift was computed with. */
    val options: DriftOptions,
    /**
     * The golden root the drift was computed against (plan amendment R24, D177), also when it has no copy of this role;
     * null when none is set.
     */
    val goldenRoot: AnsibleRoot? = null,
) {
    /** The drift of the copy whose role directory is [dir]. */
    fun copyOf(dir: VirtualFile): CopyDrift? = copies.firstOrNull { it.copy.dir == dir }

    /**
     * The name the wording uses for golden (`= heron`, `no heron copy`): the reference's root, else the golden root;
     * null when neither is known.
     */
    val goldenName: String? get() = reference?.root?.displayName ?: goldenRoot?.displayName

    /** The variant of [copy] (one of [variants]). */
    fun variantOf(copy: CopyDrift): RoleVariant? = variants.firstOrNull { it.index == copy.variant }

    /** The variant of the reference copy, or null without one. */
    val referenceVariant: RoleVariant? get() = reference?.let { ref -> variants.firstOrNull { variant -> variant.copies.any { it.dir == ref.dir } } }

    /**
     * The variants in the order Group by Variant lists them (plan amendment R24, X123): the reference's variant first,
     * then the larger before the smaller, then by the root name of their first copy (copies keep catalog order).
     */
    val rankedVariants: List<RoleVariant>
        get() {
            val referenceIndex = referenceVariant?.index
            return variants.sortedWith(
                compareBy<RoleVariant> { it.index != referenceIndex }
                    .thenByDescending { it.size }
                    .thenBy { it.copies.firstOrNull()?.root?.displayName?.lowercase().orEmpty() }
                    .thenBy { it.index },
            )
        }

    /**
     * The variant most copies share (X122's "7 of 9 copies share falcon's variant"); on a tie the reference's, then the
     * first in [rankedVariants]. Null when no two copies are byte-identical or every copy is (nothing to tell).
     */
    val majorityVariant: RoleVariant?
        get() {
            if (variants.size < 2) return null
            val largest = rankedVariants.maxByOrNull { it.size } ?: return null
            return largest.takeIf { it.size > 1 }
        }

    /** Whether there is more than one copy and all are byte-identical. */
    val identicalEverywhere: Boolean get() = copies.size > 1 && variants.size == 1

    /** How many copies differ from the reference. */
    val differingCount: Int get() = copies.count { it.tier.differs }
}
