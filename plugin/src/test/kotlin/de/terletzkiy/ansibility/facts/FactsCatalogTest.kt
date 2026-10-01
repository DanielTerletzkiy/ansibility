package de.terletzkiy.ansibility.facts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The bundled facts schema and special-variable table (plan A8, X10). */
class FactsCatalogTest {
    @Test
    fun factsTheRepositoryReadsAreDocumented() {
        // ansible_facts keys used in the infra repo (research: 174 distribution_release, 81 os_family, 75 service_mgr …)
        for (name in listOf("distribution", "distribution_release", "distribution_major_version", "os_family", "service_mgr",
            "hostname", "fqdn", "architecture", "python", "default_ipv4", "all_ipv4_addresses", "user_id", "packages", "services",
            "getent_passwd", "getent_group")) {
            assertNotNull(name, FactsCatalog.facts[name])
        }
        assertEquals("str", FactsCatalog.facts.getValue("distribution_release").typeText)
        assertEquals("list[str]", FactsCatalog.facts.getValue("all_ipv4_addresses").typeText)
        assertEquals("list[dict]", FactsCatalog.facts.getValue("mounts").typeText)
        assertTrue(FactsCatalog.facts.values.all { it.description.isNotBlank() })
    }

    @Test
    fun nestedKeysAndListElements() {
        assertEquals("str", FactsCatalog.fact(listOf("default_ipv4", "address"))?.type)
        assertEquals("int", FactsCatalog.fact(listOf("default_ipv4", "mtu"))?.type)
        assertEquals("int", FactsCatalog.fact(listOf("python", "version", "major"))?.type)
        assertEquals("the index addresses an element", "str", FactsCatalog.fact(listOf("mounts", "0", "fstype"))?.type)
        assertNull(FactsCatalog.fact(listOf("default_ipv4", "bogus")))
        assertNull(FactsCatalog.fact(emptyList()))
    }

    @Test
    fun injectedNamesCoverTheSetupFactsOnly() {
        assertEquals("distribution_release", FactsCatalog.injected["ansible_distribution_release"]?.name)
        assertNotNull(FactsCatalog.injected["ansible_default_ipv4"])
        assertNotNull(FactsCatalog.injected["ansible_service_mgr"])
        assertFalse("module facts are reached through ansible_facts", "ansible_packages" in FactsCatalog.injected)
        assertEquals("ansible.builtin.package_facts", FactsCatalog.facts.getValue("packages").setBy)
    }

    @Test
    fun specialVariables() {
        val magic = FactsCatalog.magicVars
        for (name in listOf("hostvars", "groups", "group_names", "inventory_hostname", "playbook_dir", "role_path", "omit",
            "ansible_check_mode", "ansible_managed", "ansible_facts", "ansible_version", "ansible_host", "ansible_connection")) {
            assertNotNull(name, magic[name])
        }
        assertTrue(magic.getValue("ansible_managed").templateOnly)
        assertFalse(magic.getValue("inventory_hostname").templateOnly)
        assertTrue(magic.getValue("ansible_host").connection)
        assertEquals("Use ansible_play_batch.", magic.getValue("play_hosts").deprecated)
        assertEquals(setOf("full", "major", "minor", "revision", "string"), magic.getValue("ansible_version").keys.keys)
        assertEquals("list[str]", magic.getValue("group_names").typeText)
    }
}
