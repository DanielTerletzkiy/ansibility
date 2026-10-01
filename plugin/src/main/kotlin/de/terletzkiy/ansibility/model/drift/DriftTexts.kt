package de.terletzkiy.ansibility.model.drift

import org.jetbrains.annotations.Nls

/**
 * The user-visible wording of drift tiers (plan amendment R9, F9.2 and F9.3): the badge of a role row and its
 * tooltip. It always says "differs from <reference>", never "outdated".
 *
 * [libraryName] names the reference root (the role library, e.g. `golden`); for a role without a library copy the
 * caller passes the workspace's role library, and the wording falls back to `golden`.
 */
object DriftTexts {
    /**
     * The badge of [copy] within [drift]: `= golden`, `≈ golden (molecule only)`, `Δ spec/defaults`,
     * `Δ tasks/templates`, `reference`, `falcon only` (a single copy without a reference) or `no golden copy`.
     */
    @Nls
    fun badge(drift: RoleDrift, copy: CopyDrift, libraryName: String? = drift.reference?.root?.displayName): String {
        val library = libraryName ?: fallback()
        return when (copy.tier) {
            DriftTier.REFERENCE -> AnsibilityDriftBundle.message("drift.badge.reference")
            DriftTier.IDENTICAL -> AnsibilityDriftBundle.message("drift.badge.identical", library)
            DriftTier.MOLECULE_ONLY -> AnsibilityDriftBundle.message("drift.badge.molecule", library)
            DriftTier.SPEC_DEFAULTS -> AnsibilityDriftBundle.message("drift.badge.spec")
            DriftTier.BEHAVIOUR -> AnsibilityDriftBundle.message("drift.badge.behaviour")
            DriftTier.NO_REFERENCE ->
                if (drift.copies.size == 1) AnsibilityDriftBundle.message("drift.badge.only", copy.copy.root.displayName)
                else AnsibilityDriftBundle.message("drift.badge.noReference", library)
        }
    }

    /** What a row shows while the drift of its role is not known yet. */
    @Nls
    fun pending(): String = AnsibilityDriftBundle.message("drift.badge.pending")

    /** The tooltip that explains [tier] relative to [libraryName]. */
    @Nls
    fun tooltip(tier: DriftTier, libraryName: String?): String {
        val library = libraryName ?: fallback()
        return when (tier) {
            DriftTier.REFERENCE -> AnsibilityDriftBundle.message("drift.tooltip.reference", library)
            DriftTier.IDENTICAL -> AnsibilityDriftBundle.message("drift.tooltip.identical", library)
            DriftTier.MOLECULE_ONLY -> AnsibilityDriftBundle.message("drift.tooltip.molecule", library)
            DriftTier.SPEC_DEFAULTS -> AnsibilityDriftBundle.message("drift.tooltip.spec", library)
            DriftTier.BEHAVIOUR -> AnsibilityDriftBundle.message("drift.tooltip.behaviour", library)
            DriftTier.NO_REFERENCE -> AnsibilityDriftBundle.message("drift.tooltip.noReference", library)
        }
    }

    private fun fallback(): String = AnsibilityDriftBundle.message("drift.reference.fallback")
}
