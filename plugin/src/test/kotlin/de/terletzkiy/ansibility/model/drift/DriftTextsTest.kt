package de.terletzkiy.ansibility.model.drift

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.time.LocalDate
import java.time.ZoneId

/**
 * The wording of plan amendment R24's facts ([DriftTexts]): the Roles header's drifting count while the worker runs,
 * "Last changed" lines (D180), the direction hint (X122) and the variant group rows (X123). Facts only, never
 * "outdated".
 */
class DriftTextsTest : BasePlatformTestCase() {
    private fun day(year: Int, month: Int, day: Int) = LocalDate.of(year, month, day).atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant()

    fun testTheDriftingCountIsALowerBoundUntilEveryNameIsKnown() {
        assertEquals("12 drifting", DriftTexts.drifting(12, complete = true))
        assertEquals("0 drifting", DriftTexts.drifting(0, complete = true))
        assertEquals("12+ drifting", DriftTexts.drifting(12, complete = false))
        assertEquals("… drifting", DriftTexts.drifting(0, complete = false))
    }

    fun testALastChangeLineCutsTheSubjectAt60Characters() {
        assertEquals("golden: 2026-09-12 · alice · fix verify", DriftTexts.lastChange("golden", day(2026, 9, 12), "alice", "fix verify"))
        val long = "tune the handlers of web so that every restart waits for the health check to pass"
        val line = DriftTexts.lastChange("falcon", day(2025, 3, 1), "bob", long)
        val subject = line.substringAfter("falcon: 2025-03-01 · bob · ")
        assertEquals(DriftTexts.MAX_SUBJECT, subject.length)
        assertTrue(subject, subject.endsWith("…") && long.startsWith(subject.dropLast(1)))
        assertEquals("exactly 60 characters stay whole", "a".repeat(60), DriftTexts.lastChange("falcon", day(2025, 3, 1), "bob", "a".repeat(60)).substringAfterLast(" · "))
        assertEquals("empty parts are left out", "heron: 2025-03-01", DriftTexts.lastChange("heron", day(2025, 3, 1), " ", ""))
    }

    fun testTheDirectionHintNamesTheSideThatChangedLater() {
        val golden = day(2026, 9, 12)
        val heron = day(2025, 3, 1)
        assertEquals("golden changed this role more recently (2026-09-12) than heron (2025-03-01)", DriftTexts.direction("golden", golden, "heron", heron, file = false))
        assertEquals("golden changed this role more recently (2026-09-12) than heron (2025-03-01)", DriftTexts.direction("heron", heron, "golden", golden, file = false))
        assertEquals("heron changed this file more recently (2026-09-12) than golden (2025-03-01)", DriftTexts.direction("golden", heron, "heron", golden, file = true))
        assertNull("the same moment: no direction", DriftTexts.direction("golden", golden, "heron", golden, file = false))
        for (text in listOf(DriftTexts.direction("golden", golden, "heron", heron, file = true)!!)) {
            assertFalse(text, text.contains("outdated", ignoreCase = true) || text.contains("newer version", ignoreCase = true))
        }
    }

    fun testVariantGroupRows() {
        assertEquals(listOf("A", "B", "Z", "AA", "AB", "AZ", "BA"), listOf(0, 1, 25, 26, 27, 51, 52).map(DriftTexts::variantLetter))
        assertEquals("Variant A: golden, raven (2)", DriftTexts.variantGroup("A", listOf("golden", "raven")))
        assertEquals(
            "Variant B: falcon, heron, tern, … (7)",
            DriftTexts.variantGroup("B", listOf("falcon", "heron", "tern", "wren", "thrush", "web", "db")),
        )
        assertEquals("Byte-identical copies: falcon, heron", DriftTexts.variantGroupTooltip(listOf("falcon", "heron")))
    }

    fun testWithMoleculeIgnoredNothingClaimsByteIdentity() {
        assertEquals("Byte-identical to the copy in golden", DriftTexts.tooltip(DriftTier.IDENTICAL, "golden"))
        assertEquals("Identical to the copy in golden (molecule/ ignored)", DriftTexts.tooltip(DriftTier.IDENTICAL, "golden", ignoreMolecule = true))
        assertEquals("Identical copies (molecule/ ignored): falcon, heron", DriftTexts.variantGroupTooltip(listOf("falcon", "heron"), ignoreMolecule = true))
        assertEquals(
            "Differs from golden in meta/argument_specs.yml, defaults/ or vars/ (molecule/ ignored)",
            DriftTexts.tooltip(DriftTier.SPEC_DEFAULTS, "golden", ignoreMolecule = true),
        )
        for (tier in DriftTier.entries) {
            val text = DriftTexts.tooltip(tier, "golden", ignoreMolecule = true)
            assertFalse(text, text.contains("byte", ignoreCase = true) || text.contains("possibly molecule", ignoreCase = true))
        }
        val groupBy = AnsibilityDriftBundle.message("drift.action.groupByVariant.description")
        assertTrue("Group by Variant says what it groups with molecule/ ignored: $groupBy", groupBy.contains("molecule/"))
    }
}
