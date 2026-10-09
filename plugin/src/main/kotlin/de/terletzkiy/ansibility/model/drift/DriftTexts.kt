package de.terletzkiy.ansibility.model.drift

import org.jetbrains.annotations.Nls
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The user-visible wording of drift tiers (plan amendments R9, F9.2 and F9.3, and R24, D179): the badge of a role row,
 * its counts, the summary of a role name and the tooltips. It always says "differs from <golden>", never "outdated":
 * the direction of a difference is unknown.
 *
 * [libraryName] names the golden root (e.g. `golden`, or `heron` when heron is the golden root: `= heron`,
 * `no heron copy`). It defaults to [RoleDrift.goldenName] (the reference's root, else the golden root the drift was
 * computed against), and the wording falls back to `golden` only when neither is known.
 *
 * Also the facts of plan amendment R24's details (D180 "Last changed", X122 direction hints, X123 variant groups):
 * facts only, never "outdated" or "newer version".
 */
object DriftTexts {
    /**
     * The badge of [copy] within [drift]: `golden root` (the golden copy itself), `= golden`, `≈ molecule only`,
     * `Δ spec/defaults`, `Δ tasks/templates`, `falcon only` (a single copy without a golden copy) or `no golden copy`.
     */
    @Nls
    fun badge(drift: RoleDrift, copy: CopyDrift, libraryName: String? = drift.goldenName): String {
        val library = libraryName ?: fallback()
        return when (copy.tier) {
            DriftTier.REFERENCE -> AnsibilityDriftBundle.message("drift.badge.reference")
            DriftTier.IDENTICAL -> AnsibilityDriftBundle.message("drift.badge.identical", library)
            DriftTier.MOLECULE_ONLY -> AnsibilityDriftBundle.message("drift.badge.molecule")
            DriftTier.SPEC_DEFAULTS -> AnsibilityDriftBundle.message("drift.badge.spec")
            DriftTier.BEHAVIOUR -> AnsibilityDriftBundle.message("drift.badge.behaviour")
            DriftTier.NO_REFERENCE ->
                if (drift.copies.size == 1) AnsibilityDriftBundle.message("drift.badge.only", copy.copy.root.displayName)
                else AnsibilityDriftBundle.message("drift.badge.noReference", library)
        }
    }

    /**
     * The badge of a role row (D179): [badge], and for a copy that differs its counts ([counts]):
     * `≈ molecule only · 1 file`, `Δ tasks/templates · 3 changed · 9 only in golden · 1 only here`.
     */
    @Nls
    fun rowBadge(drift: RoleDrift, copy: CopyDrift, libraryName: String? = drift.goldenName): String {
        val badge = badge(drift, copy, libraryName)
        return if (copy.tier.differs) AnsibilityDriftBundle.message("drift.join", badge, counts(copy.paths, libraryName)) else badge
    }

    /**
     * How many paths differ: `1 file` / `3 files` when every differing file exists on both sides, else the non-zero
     * parts of `3 changed · 9 only in golden · 1 only here`.
     */
    @Nls
    fun counts(paths: DriftPaths, libraryName: String? = null): String {
        if (paths.onlyInReference.isEmpty() && paths.onlyHere.isEmpty()) {
            return AnsibilityDriftBundle.message("drift.count.files", paths.changed.size)
        }
        val library = libraryName ?: fallback()
        return listOfNotNull(
            paths.changed.size.takeIf { it > 0 }?.let { AnsibilityDriftBundle.message("drift.count.changed", it) },
            paths.onlyInReference.size.takeIf { it > 0 }?.let { AnsibilityDriftBundle.message("drift.count.onlyInReference", it, library) },
            paths.onlyHere.size.takeIf { it > 0 }?.let { AnsibilityDriftBundle.message("drift.count.onlyHere", it) },
        ).joinToString(SEPARATOR)
    }

    /**
     * What a role name says about its copies (D179): `identical everywhere`, `7 differ from golden (molecule only)` (the
     * tier in brackets when every differing copy has the same one), `golden copy only` or `no golden copy`.
     */
    @Nls
    fun nameSummary(drift: RoleDrift, libraryName: String? = drift.goldenName): String {
        val library = libraryName ?: fallback()
        val differing = drift.copies.filter { it.tier.differs }
        return when {
            drift.reference == null -> AnsibilityDriftBundle.message("drift.name.noReference", library)
            drift.identicalEverywhere -> AnsibilityDriftBundle.message("drift.name.identical")
            differing.isEmpty() -> AnsibilityDriftBundle.message("drift.name.referenceOnly", library)
            else -> {
                val tiers = differing.map { it.tier }.distinct()
                val text = AnsibilityDriftBundle.message("drift.name.differ", differing.size, library)
                if (tiers.size == 1) AnsibilityDriftBundle.message("drift.name.differ.tier", text, tierName(tiers.single())) else text
            }
        }
    }

    /**
     * The short name of a tier: `molecule only`, `spec/defaults`, `tasks/templates`, `identical`, `golden root`,
     * `no golden copy` (`no heron copy` with [libraryName] heron).
     */
    @Nls
    fun tierName(tier: DriftTier, libraryName: String? = null): String = when (tier) {
        DriftTier.MOLECULE_ONLY -> AnsibilityDriftBundle.message("drift.tier.molecule")
        DriftTier.SPEC_DEFAULTS -> AnsibilityDriftBundle.message("drift.tier.spec")
        DriftTier.BEHAVIOUR -> AnsibilityDriftBundle.message("drift.tier.behaviour")
        DriftTier.REFERENCE -> AnsibilityDriftBundle.message("drift.badge.reference")
        DriftTier.IDENTICAL -> AnsibilityDriftBundle.message("drift.tier.identical")
        DriftTier.NO_REFERENCE -> AnsibilityDriftBundle.message("drift.badge.noReference", libraryName ?: fallback())
    }

    /** The name of a [DriftCategory] as the Differences group lists it: `Tasks/templates`, `Spec/defaults`, `Molecule`. */
    @Nls
    fun categoryName(category: DriftCategory): String = when (category) {
        DriftCategory.BEHAVIOUR -> AnsibilityDriftBundle.message("drift.category.behaviour")
        DriftCategory.SPEC_DEFAULTS -> AnsibilityDriftBundle.message("drift.category.spec")
        DriftCategory.MOLECULE -> AnsibilityDriftBundle.message("drift.category.molecule")
    }

    /** What a sensitive file's row says instead of anything about its content (D181). */
    @Nls
    fun contentNotShown(): String = AnsibilityDriftBundle.message("drift.sensitive")

    /** What a row shows while the drift of its role is not known yet. */
    @Nls
    fun pending(): String = AnsibilityDriftBundle.message("drift.badge.pending")

    /**
     * The tooltip that explains [tier] relative to [libraryName]. With [ignoreMolecule] (the drift was computed with
     * "Ignore molecule/ in drift", [RoleDrift.options]) an identical copy is "identical (molecule/ ignored)", never
     * "byte-identical": its `molecule/` may differ.
     */
    @Nls
    fun tooltip(tier: DriftTier, libraryName: String?, ignoreMolecule: Boolean = false): String {
        val library = libraryName ?: fallback()
        return when (tier) {
            DriftTier.REFERENCE -> AnsibilityDriftBundle.message("drift.tooltip.reference", library)
            DriftTier.IDENTICAL ->
                AnsibilityDriftBundle.message(if (ignoreMolecule) "drift.tooltip.identical.moleculeIgnored" else "drift.tooltip.identical", library)
            DriftTier.MOLECULE_ONLY -> AnsibilityDriftBundle.message("drift.tooltip.molecule", library)
            DriftTier.SPEC_DEFAULTS ->
                AnsibilityDriftBundle.message(if (ignoreMolecule) "drift.tooltip.spec.moleculeIgnored" else "drift.tooltip.spec", library)
            DriftTier.BEHAVIOUR -> AnsibilityDriftBundle.message("drift.tooltip.behaviour", library)
            DriftTier.NO_REFERENCE -> AnsibilityDriftBundle.message("drift.tooltip.noReference", library)
        }
    }

    /** `golden`: the wording when no golden root can be named. */
    @Nls
    fun fallback(): String = AnsibilityDriftBundle.message("drift.reference.fallback")

    // ---------------------------------------------------------------- R24: the Roles header (D179)

    /**
     * How many role names drift (D179): `12 drifting` once every name is known, `12+ drifting` while the worker has
     * not computed every name yet ([complete] false), `… drifting` while none drifts so far.
     */
    @Nls
    fun drifting(count: Int, complete: Boolean): String = when {
        complete -> AnsibilityDriftBundle.message("drift.header.drifting", count)
        count > 0 -> AnsibilityDriftBundle.message("drift.header.drifting.partial", count)
        else -> AnsibilityDriftBundle.message("drift.header.drifting.pending")
    }

    // ---------------------------------------------------------------- R24: last change and direction (D180, X122)

    /** A date as the details and diff titles show it: `2026-09-12`, in the local time zone. */
    fun date(instant: Instant): String = DATE.format(instant)

    /**
     * One side's last change (D180): `golden: 2026-09-12 · alice · fix verify`, the subject cut at
     * [MAX_SUBJECT] characters (`…` marks the cut); empty parts are left out.
     */
    @Nls
    fun lastChange(rootName: String, at: Instant, author: String, subject: String): String {
        val parts = listOf(date(at), author.trim(), cut(subject.trim(), MAX_SUBJECT)).filter { it.isNotEmpty() }
        return AnsibilityDriftBundle.message("drift.details.lastChange", rootName, parts.joinToString(SEPARATOR))
    }

    /**
     * Which side changed later (X122), a fact and nothing more: `golden changed this role more recently (2026-09-12)
     * than heron (2025-03-01)`, or `… this file …` when [file]; null when both were changed at the same moment.
     */
    @Nls
    fun direction(first: String, firstDate: Instant, second: String, secondDate: Instant, file: Boolean): String? {
        if (firstDate == secondDate) return null
        val newer = if (firstDate.isAfter(secondDate)) first to firstDate else second to secondDate
        val older = if (firstDate.isAfter(secondDate)) second to secondDate else first to firstDate
        val key = if (file) "drift.details.direction.file" else "drift.details.direction.role"
        return AnsibilityDriftBundle.message(key, newer.first, date(newer.second), older.first, date(older.second))
    }

    // ---------------------------------------------------------------- R24: variants (X122, X123)

    /**
     * The variant majority of [drift] seen from [copy] (X122), facts only, next to the details' "Same as …" line (which
     * already names [copy]'s own variant):
     * - the variant most copies share, unless it is [copy]'s: `7 of 9 copies share falcon's variant` (named after its
     *   first copy), or `golden's variant is shared by 5 of 9 copies` when it is golden's;
     * - golden's, when it is neither the majority nor [copy]'s: `golden's variant is shared by 2 of 9 copies`, or
     *   `No other copy shares golden's variant`.
     *
     * Empty with fewer than three copies, a single variant or no two copies alike.
     */
    fun variantMajority(drift: RoleDrift, copy: CopyDrift?, libraryName: String? = drift.goldenName): List<String> {
        val total = drift.copies.size
        if (total < 3) return emptyList()
        val majority = drift.majorityVariant ?: return emptyList()
        val golden = drift.referenceVariant
        val goldenName = libraryName ?: fallback()
        fun goldenShare(variant: RoleVariant): String =
            if (variant.size == 1) AnsibilityDriftBundle.message("drift.details.variant.golden.alone", goldenName)
            else AnsibilityDriftBundle.message("drift.details.variant.golden.shared", goldenName, variant.size, total)
        val own = copy?.variant
        val lines = ArrayList<String>()
        if (majority.index != own) {
            lines += if (golden?.index == majority.index) goldenShare(majority)
            else AnsibilityDriftBundle.message("drift.details.variant.majority", majority.size, total, majority.copies.first().root.displayName)
        }
        if (golden != null && golden.index != majority.index && golden.index != own) lines += goldenShare(golden)
        return lines
    }

    /** The letter of the [position]th variant group (X123): `A` … `Z`, then `AA`, `AB`, …. */
    fun variantLetter(position: Int): String {
        require(position >= 0)
        val letters = StringBuilder()
        var rest = position + 1
        while (rest > 0) {
            rest -= 1
            letters.append('A' + rest % 26)
            rest /= 26
        }
        return letters.reverse().toString()
    }

    /**
     * A variant group row (X123): `Variant A: golden, raven (2)`, `Variant B: falcon, heron, tern, … (7)`; [names] are
     * the roots of its copies, in catalog order, and at most [MAX_GROUP_NAMES] of them are named.
     */
    @Nls
    fun variantGroup(letter: String, names: List<String>): String {
        val shown = names.take(MAX_GROUP_NAMES) + if (names.size > MAX_GROUP_NAMES) listOf(ELLIPSIS) else emptyList()
        return AnsibilityDriftBundle.message("drift.variant.group", letter, shown.joinToString(", "), names.size)
    }

    /**
     * The tooltip of a variant group row: every copy in it; "Identical copies (molecule/ ignored)" when the drift was
     * computed with [ignoreMolecule], since their `molecule/` may differ.
     */
    @Nls
    fun variantGroupTooltip(names: List<String>, ignoreMolecule: Boolean = false): String = AnsibilityDriftBundle.message(
        if (ignoreMolecule) "drift.variant.group.tooltip.moleculeIgnored" else "drift.variant.group.tooltip",
        names.joinToString(", "),
    )

    private fun cut(text: String, max: Int): String = if (text.length <= max) text else text.take(max - 1).trimEnd() + ELLIPSIS

    /** Subjects longer than this are cut (D180). */
    const val MAX_SUBJECT: Int = 60

    /** A variant group names at most this many copies (X123). */
    const val MAX_GROUP_NAMES: Int = 3

    private const val SEPARATOR = " · "
    private const val ELLIPSIS = "…"
    private val DATE: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneId.systemDefault())
}
