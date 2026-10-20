package de.terletzkiy.ansibility.vars.usages

/**
 * Find Usages from a `group_vars` key lists only what can meet it on a host (plan amendment R27): `group_vars/alias`
 * and `group_vars/platform` next to the playbook belong to groups that never share a host, so neither lists the
 * other's definition or reads; role templates read the name for every host and always stay.
 */
class VarUsageReachTest : UsagesTestCase() {
    override fun setUp() {
        super.setUp()
        myFixture.addFileToProject("ansible.cfg", "[defaults]\n")
        myFixture.addFileToProject("environments/alias/hosts.yml", "all:\n  children:\n    alias:\n      hosts:\n        a1:\n")
        myFixture.addFileToProject("environments/platform/hosts.yml", "all:\n  children:\n    platform:\n      hosts:\n        p1:\n")
        myFixture.addFileToProject("group_vars/alias/all.yml", "department_key: alias\nprompt: \"{{ department_key }}\"\n")
        myFixture.addFileToProject("group_vars/platform/all.yml", "department_key: platform\nprompt: \"{{ department_key }}\"\n")
        myFixture.addFileToProject("roles/web/tasks/main.yml", "- ansible.builtin.template:\n    src: t.j2\n    dest: /t\n")
        myFixture.addFileToProject("roles/web/templates/t.j2", "{{ department_key }}\n")
        myFixture.addFileToProject("site.yml", "- hosts: all\n  roles:\n    - web\n")
        refreshRoots()
        settle()
    }

    fun testUsagesFromAGroupVarsKeyStayWithTheHostsItMeets() {
        at("group_vars/alias/all.yml", 1, "department_key")
        val usages = describeInfos(findUsagesOf(targetAtCaret()!!))
        assertTrue(usages.toString(), usages.any { it.startsWith("group_vars/alias/all.yml:1:") })
        assertTrue(usages.toString(), usages.any { it.startsWith("group_vars/alias/all.yml:2:") })
        assertTrue(usages.toString(), usages.any { it.startsWith("roles/web/templates/t.j2:1:") })
        assertFalse(usages.toString(), usages.any { it.startsWith("group_vars/platform/") })
    }

    fun testFromARoleTemplateEveryDefinitionStays() {
        at("roles/web/templates/t.j2", 1, "department_key", delta = 4)
        val usages = describeInfos(findUsagesOf(targetAtCaret()!!))
        assertTrue(usages.toString(), usages.any { it.startsWith("group_vars/alias/all.yml:1:") })
        assertTrue(usages.toString(), usages.any { it.startsWith("group_vars/platform/all.yml:1:") })
    }
}
