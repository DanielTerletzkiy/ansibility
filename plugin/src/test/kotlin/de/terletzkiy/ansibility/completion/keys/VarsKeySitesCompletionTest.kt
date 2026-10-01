package de.terletzkiy.ansibility.completion.keys

import com.intellij.codeInsight.lookup.LookupElement

/**
 * Where key completion runs and how it ranks, on the small `mini` root (roles `web` and `db`; `hosts.yml` groups
 * `webservers` → web1 and `dbservers` → db1; `playbook-site.yml` applies `web` to webservers and `db` to
 * dbservers; a molecule scenario of `web`).
 */
class VarsKeySitesCompletionTest : KeyCompletionTestCase() {
    override fun setUp() {
        super.setUp()
        copyKeysData(MINI)
    }

    private fun roleOf(item: LookupElement): String? = com.intellij.codeInsight.lookup.LookupElementPresentation.renderElement(item).tailText?.trim()

    fun testRolesThatThePlaysApplyToTheGroupComeFirst() {
        val web = completeInsertingLine("$MINI/environments/prod/group_vars/webservers/vars.yml", 3, CARET)
        val webNames = names(web)
        assertTrue("web options before db options: $webNames", webNames.indexOf("web_mode") < webNames.indexOf("db_name"))
        assertDoesntContain("already set", webNames, "web_port")
    }

    fun testTheOtherGroupRanksItsOwnRoleFirst() {
        val db = names(completeInsertingLine("$MINI/environments/prod/group_vars/dbservers/vars.yml", 3, CARET))
        assertTrue("db options before web options: $db", db.indexOf("db_port") < db.indexOf("web_mode"))
        assertDoesntContain("already set", db, "db_name")
    }

    fun testHostVarsRankTheRolesOfTheHostsPlays() {
        val host = names(completeInsertingLine("$MINI/environments/prod/host_vars/db1/vars.yml", 3, CARET))
        assertTrue("db1 is a dbserver: $host", host.indexOf("db_name") < host.indexOf("web_mode"))
    }

    fun testRequiredWithoutDefaultIsBoldAndRanksFirstAmongItsRole() {
        val items = completeInsertingLine("$MINI/environments/prod/group_vars/dbservers/vars.yml", 2, "web_$CARET")
        // web_port: required by web, optional in db; web_mode: default auto. The applied role (db) is named first.
        val port = presentation(items, "web_port")
        assertTrue(port.isItemTextBold)
        assertEquals(" db, web", port.tailText)
        assertFalse(presentation(items, "web_mode").isItemTextBold)
    }

    fun testDefaultsOnlyKeysNameTheirRoleAndValueType() {
        val items = completeInsertingLine("$MINI/environments/prod/group_vars/webservers/vars.yml", 3, "web_$CARET")

        val root = presentation(items, "web_root")
        assertEquals(" web (defaults)", root.tailText)
        assertEquals("str", root.typeText)
        assertEquals("dict", presentation(items, "web_extra").typeText)
    }

    fun testDeprecatedOptionsAreStruckOut() {
        val items = completeInsertingLine("$MINI/environments/prod/group_vars/webservers/vars.yml", 3, "web_leg$CARET")
        assertTrue(presentation(items, "web_legacy").isStrikeout)
    }

    fun testInventoryOnlyNamesAreOffered() {
        val items = completeInsertingLine("$MINI/environments/prod/group_vars/webservers/vars.yml", 3, "mini_$CARET")
        assertEquals(listOf("mini_inventory_only"), names(items))
    }

    fun testAnEmptyVarsFileOffersTopLevelKeys() {
        val items = completeIn("$MINI/environments/prod/group_vars/webservers/extra.yml", "web_s$CARET")
        assertContainsElements(names(items), "web_sites")
    }

    fun testAnEmptyVarsFileAfterADocumentStart() {
        val items = completeIn("$MINI/environments/prod/group_vars/webservers/extra.yml", "---\nweb_s$CARET\n")
        assertContainsElements(names(items), "web_sites")
    }

    fun testRoleDefaultsOfferTheRolesOwnOptionsFirst() {
        val items = completeInsertingLine("$MINI/roles/web/defaults/main.yml", 2, CARET)
        val names = names(items)
        assertEquals("web_port", names.first())
        assertDoesntContain("already in defaults", names, "web_mode", "web_enabled")
    }

    fun testNestedListItemAndDictLevels() {
        val path = "$MINI/environments/prod/group_vars/webservers/vars.yml"
        assertEquals(listOf("name", "listen", "aliases"), names(completeInsertingLine(path, 3, "web_sites:\n  - $CARET")))
    }

    fun testNestedDictBelowAListItem() {
        val path = "$MINI/environments/prod/group_vars/webservers/vars.yml"
        val items = completeInsertingLine(path, 3, "web_sites:\n  - name: a\n    listen:\n      tls: true\n      $CARET")
        assertEquals(listOf("port"), names(items))
    }

    fun testADictOptionOffersItsKeysRequiredFirst() {
        val path = "$MINI/environments/prod/group_vars/webservers/vars.yml"
        assertEquals(listOf("burst", "rate"), names(completeInsertingLine(path, 3, "web_limits:\n  $CARET")))
    }

    fun testAListOptionTakesNoKeysOutsideItsItems() {
        val path = "$MINI/environments/prod/group_vars/webservers/vars.yml"
        assertEquals(emptyList<String>(), names(completeInsertingLine(path, 3, "web_sites:\n  $CARET")))
    }

    fun testNoNestedKeysForVariablesWithoutSpecOptions() {
        val path = "$MINI/environments/prod/group_vars/webservers/vars.yml"
        assertEquals(emptyList<String>(), names(completeInsertingLine(path, 3, "web_extra:\n  $CARET")))
    }

    fun testNothingInsideJinja() {
        val path = "$MINI/environments/prod/group_vars/webservers/vars.yml"
        assertEquals(emptyList<String>(), names(completeInsertingLine(path, 3, "web_mode: \"{{ web_$CARET }}\"")))
    }

    fun testHostsYmlHostEntriesAndGroupVars() {
        val hosts = "$MINI/environments/prod/hosts.yml"
        assertLine(hosts, 6, "    web1:")
        val host = names(completeInsertingLine(hosts, 8, "      web_$CARET"))
        assertContainsElements("web1's own keys are variables", host, "web_port", "web_mode")

        val group = completeInsertingLine(hosts, 5, "    db_$CARET")
        assertContainsElements("all.vars", names(group), "db_name")
    }

    fun testHostsYmlStructuralKeysGetNothing() {
        val hosts = "$MINI/environments/prod/hosts.yml"
        assertEquals("a host name under hosts:", emptyList<String>(), names(completeInsertingLine(hosts, 8, "    web_$CARET")))
        assertEquals("a group name", emptyList<String>(), names(completeInsertingLine(hosts, 1, "web_$CARET")))
    }

    fun testPlayVarsRankThePlaysRoles() {
        val items = completeIn(
            "$MINI/playbook-keys.yml",
            "- name: Db\n  hosts: dbservers\n  vars:\n    $CARET\n  roles:\n    - role: db\n",
        )
        val names = names(items)
        assertTrue("db first: $names", names.indexOf("db_name") < names.indexOf("web_mode"))
    }

    fun testRoleParametersRankTheirRole() {
        val items = completeIn(
            "$MINI/playbook-params.yml",
            "- name: Db\n  hosts: dbservers\n  roles:\n    - role: web\n      tags: [web]\n      $CARET\n",
        )
        val names = names(items)
        assertTrue("web first: $names", names.indexOf("web_mode") < names.indexOf("db_name"))
    }

    fun testIncludeRoleVarsRankTheIncludedRole() {
        val items = completeIn(
            "$MINI/roles/db/tasks/include.yml",
            "- name: Include web\n  ansible.builtin.include_role:\n    name: web\n  vars:\n    $CARET\n",
        )
        val names = names(items)
        assertTrue("web first: $names", names.indexOf("web_mode") < names.indexOf("db_port"))
    }

    fun testSetFactArguments() {
        val items = completeIn("$MINI/roles/web/tasks/facts.yml", "- name: Facts\n  ansible.builtin.set_fact:\n    web_$CARET\n")
        assertContainsElements(names(items), "web_mode")
    }

    fun testTaskStructuralKeysGetNothing() {
        assertEquals(
            "a task keyword or module position",
            emptyList<String>(),
            names(completeIn("$MINI/roles/web/tasks/structural.yml", "- name: Run\n  $CARET\n  ansible.builtin.debug:\n    msg: hi\n")),
        )
        assertEquals(
            "a module option",
            emptyList<String>(),
            names(completeIn("$MINI/roles/web/tasks/options.yml", "- name: Run\n  ansible.builtin.debug:\n    $CARET\n")),
        )
    }

    fun testPlayStructuralKeysGetNothing() {
        assertEquals(emptyList<String>(), names(completeIn("$MINI/playbook-structural.yml", "- name: Web\n  hosts: webservers\n  $CARET\n")))
    }

    fun testMoleculeInventoryGroupVars() {
        val path = "$MINI/roles/web/molecule/default/molecule.yml"
        assertLine(path, 11, "        web_port: 8080")
        val items = completeInsertingLine(path, 12, "        web_$CARET")
        val names = names(items)
        assertContainsElements(names, "web_mode")
        assertDoesntContain(names, "web_port")
    }

    fun testMoleculeVarsAndRoleVarsFiles() {
        val molecule = "$MINI/roles/web/molecule/default/vars.yml"
        assertEquals(listOf("web_mode"), names(completeIn(molecule, "---\nweb_mo$CARET\n")))
        assertEquals(
            de.terletzkiy.ansibility.api.FileKind.MOLECULE_VARS,
            de.terletzkiy.ansibility.api.AnsibleWorkspace.getInstance(project).contextOf(myFixture.findFileInTempDir(molecule)!!)?.kind,
        )
        val names = names(completeIn("$MINI/roles/db/vars/main.yml", "---\n$CARET\n"))
        assertEquals("the role's own required option first", "db_name", names.first())
    }

    fun testArgumentSpecsAreNotVarsFiles() {
        val path = "$MINI/roles/web/meta/argument_specs.yml"
        assertEquals(emptyList<String>(), names(completeInsertingLine(path, 6, "      web_$CARET")))
    }

    fun testASpecEditIsSeenByTheNextCompletion() {
        val path = "$MINI/environments/prod/group_vars/webservers/vars.yml"
        assertDoesntContain(names(completeInsertingLine(path, 3, "web_ad$CARET")), "web_added")
        com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().saveAllDocuments()

        // An unsaved edit: no VFS event, no structure change; only the spec document's stamp moves.
        editDocument("$MINI/roles/web/meta/argument_specs.yml", "    options:\n", "      web_added:\n        type: str\n        description: Added.\n")

        // Line 2 this time: two stray `web_ad` lines in a row would parse as one multi-line scalar.
        assertContainsElements(names(completeInsertingLine(path, 2, "web_ad$CARET", save = false)), "web_added")
    }

    fun testAPlaybookEditChangesTheRanking() {
        val path = "$MINI/environments/prod/group_vars/webservers/vars.yml"
        val before = names(completeInsertingLine(path, 3, CARET))
        assertTrue(before.indexOf("web_mode") < before.indexOf("db_name"))

        com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().saveAllDocuments()

        // The web play now targets the database servers (unsaved): no role is applied to webservers any more.
        editDocument("$MINI/playbook-site.yml", "hosts: webservers", "hosts: dbservers", replace = true)

        val after = names(completeInsertingLine(path, 3, CARET, save = false))
        assertTrue("alphabetical without applied roles: $after", after.indexOf("db_name") < after.indexOf("web_mode"))
    }

    /** Inserts [text] after the first [marker] of [path]'s document (or replaces the marker), unsaved, and commits. */
    private fun editDocument(path: String, marker: String, text: String, replace: Boolean = false) {
        val file = myFixture.findFileInTempDir(path) ?: error("missing $path")
        val document = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(file) ?: error("no document for $path")
        val index = document.text.indexOf(marker)
        check(index >= 0) { "'$marker' not in $path" }
        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) {
            if (replace) document.replaceString(index, index + marker.length, text) else document.insertString(index + marker.length, text)
        }
        com.intellij.psi.PsiDocumentManager.getInstance(project).commitAllDocuments()
    }

    fun testTailsOfTopLevelItemsNameRoles() {
        val items = completeInsertingLine("$MINI/environments/prod/group_vars/webservers/vars.yml", 3, "db_n$CARET")
        assertEquals("db", roleOf(item(items, "db_name")))
    }
}
