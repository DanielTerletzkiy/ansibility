package de.terletzkiy.ansibility.layout

import de.terletzkiy.ansibility.settings.layout.LayoutConfigurable
import de.terletzkiy.ansibility.settings.layout.LayoutInventory
import de.terletzkiy.ansibility.vault.identity.VaultRootSettings
import junit.framework.TestCase

/** The vault env → id mapping on files of several environments, and rename detection in the Layout page (R10-10/11). */
class LayoutVaultAndRenameTest : TestCase() {
    private val mapping = VaultRootSettings(environmentIdentities = mapOf("prod" to "dev", "staging" to "prod", "qa" to "dev"))

    fun testOneSharedIdApplies() {
        assertEquals("dev", mapping.identityForEnvironments(listOf("prod", "qa")))
        assertFalse(mapping.mappingsDiffer(listOf("prod", "qa")))
    }

    fun testDifferingIdsGiveNone() {
        assertNull(mapping.identityForEnvironments(listOf("prod", "staging")))
        assertTrue(mapping.mappingsDiffer(listOf("prod", "staging")))
    }

    fun testUnmappedEnvironmentGivesNoneUnlessWildcard() {
        assertNull(mapping.identityForEnvironments(listOf("prod", "other")))
        val wildcard = mapping.copy(environmentIdentities = mapping.environmentIdentities + ("*" to "dev"))
        assertEquals("dev", wildcard.identityForEnvironments(listOf("prod", "other")))
    }

    fun testRenameMovesTheMapping() {
        val renamed = mapping.renameEnvironment("prod", "production")
        assertEquals(mapOf("staging" to "prod", "qa" to "dev", "production" to "dev"), renamed.environmentIdentities)
        assertSame(mapping, mapping.renameEnvironment("missing", "x"))
    }

    fun testRenamesMatchBySources() {
        val before = listOf(LayoutInventory("prod", listOf("inventory/prod.yml")), LayoutInventory("staging", listOf("inventory/staging.ini")))
        val after = listOf(LayoutInventory("production", listOf("inventory/prod.yml")), LayoutInventory("staging", listOf("inventory/staging.ini")))
        assertEquals(listOf("prod" to "production"), LayoutConfigurable.renames(before, after))
        assertEquals(emptyList<Pair<String, String>>(), LayoutConfigurable.renames(before, before))
    }
}
