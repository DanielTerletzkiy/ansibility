package de.terletzkiy.ansibility.context.host.card

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.terletzkiy.ansibility.api.HostKey

/** The shared wording of the host-aware card parts: host lists never merge environments, and long lists are cut. */
class HostCardTextsTest : BasePlatformTestCase() {
    private fun host(environment: String, name: String) = HostKey("repos/falcon/ansible", environment, name)

    fun testHostListsPerEnvironment() {
        assertEquals("prod-prod1, prod-prod2", HostCardTexts.hostList(listOf(host("prod", "prod-prod1"), host("prod", "prod-prod2"))))
        assertEquals(
            "ops: ops-ops1 · prod: prod-prod1, prod-prod2",
            HostCardTexts.hostList(listOf(host("ops", "ops-ops1"), host("prod", "prod-prod1"), host("prod", "prod-prod2"), host("ops", "ops-ops1"))),
        )
        assertEquals(
            "prod: prod-prod1 · molecule postfix/default: postfix-deb12",
            HostCardTexts.hostList(listOf(host("prod", "prod-prod1"), host(HostKey.moleculeEnvironment("postfix", "default"), "postfix-deb12"))),
        )
        val many = (1..11).map { host("test", "test-$it") }
        assertEquals("test-1, test-2, test-3, test-4, test-5, test-6, test-7, test-8 +3 more", HostCardTexts.hostList(many))
        assertEquals("", HostCardTexts.hostList(emptyList()))
    }

    fun testHostAndEnvironmentLabels() {
        assertEquals("prod › prod-prod1", HostCardTexts.hostLabel(host("prod", "prod-prod1")))
        assertEquals("molecule haproxy/default › haproxy_deb12", HostCardTexts.hostLabel(host(HostKey.moleculeEnvironment("haproxy", "default"), "haproxy_deb12")))
        assertEquals("molecule default", HostCardTexts.environmentLabel(HostKey.moleculeEnvironment(null, "default")))
        assertNull("inventory-only evaluation names no play", HostCardTexts.plays(emptyList()))
    }
}
