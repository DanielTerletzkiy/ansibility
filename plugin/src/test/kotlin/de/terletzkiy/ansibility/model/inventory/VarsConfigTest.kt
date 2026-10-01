package de.terletzkiy.ansibility.model.inventory

import de.terletzkiy.ansibility.context.AnsibleCfg
import de.terletzkiy.ansibility.fixtures.InfraTestData
import de.terletzkiy.ansibility.semantics.precedence.HashBehaviour
import de.terletzkiy.ansibility.semantics.precedence.PrecedenceEntry
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files

/** [VarsConfig]: the `ansible.cfg` settings that change inventory variable loading. */
class VarsConfigTest {
    private fun config(text: String) = VarsConfig.from(AnsibleCfg.parse(text))

    @Test
    fun defaultsWithoutConfigOrKeys() {
        assertEquals(VarsConfig.DEFAULT, VarsConfig.from(null))
        assertEquals(VarsConfig.DEFAULT, config("[defaults]\nhost_key_checking = False\n"))
        assertEquals(PrecedenceEntry.DEFAULT, VarsConfig.DEFAULT.precedence)
        assertEquals(HashBehaviour.REPLACE, VarsConfig.DEFAULT.hashBehaviour)
        assertEquals("top", VarsConfig.DEFAULT.playbookVarsRoot)
        assertEquals(listOf(".yml", ".yaml", ".json"), VarsConfig.DEFAULT.extensions)
    }

    @Test
    fun theInfraRepoConfigsUseTheDefaults() {
        for (repo in listOf("falcon", "platform", "pelican")) {
            val text = String(Files.readAllBytes(InfraTestData.root.resolve("repos/$repo/ansible/ansible.cfg")))
            assertEquals(repo, VarsConfig.DEFAULT, config(text))
        }
    }

    @Test
    fun precedenceKeepsKnownEntriesAndReportsTheRest() {
        val cfg = config("[defaults]\nprecedence = groups_plugins_play, all_inventory, plugins_by_group, bogus\n")
        assertEquals(listOf(PrecedenceEntry.GROUPS_PLUGINS_PLAY, PrecedenceEntry.ALL_INVENTORY), cfg.precedence)
        assertEquals(listOf("plugins_by_group", "bogus"), cfg.ignoredPrecedenceEntries)
        assertEquals("an empty value disables every group level", emptyList<PrecedenceEntry>(), config("[defaults]\nprecedence =\n").precedence)
    }

    @Test
    fun hashBehaviourAndPlaybookVarsRoot() {
        assertEquals(HashBehaviour.MERGE, config("[defaults]\nhash_behaviour = merge\n").hashBehaviour)
        assertEquals(HashBehaviour.MERGE, config("[defaults]\nhash_behaviour = MERGE ; inline comment\n").hashBehaviour)
        assertEquals("an unknown value falls back", HashBehaviour.REPLACE, config("[defaults]\nhash_behaviour = deep\n").hashBehaviour)
        assertEquals("all", config("[defaults]\nplaybook_vars_root = All\n").playbookVarsRoot)
        assertEquals("only [defaults] counts", HashBehaviour.REPLACE, config("[other]\nhash_behaviour = merge\n").hashBehaviour)
    }

    @Test
    fun yamlValidExtensions() {
        assertEquals(listOf(".yml", ".json"), config("[defaults]\nyaml_valid_extensions = '.yml', \".json\"\n").extensions)
    }
}
