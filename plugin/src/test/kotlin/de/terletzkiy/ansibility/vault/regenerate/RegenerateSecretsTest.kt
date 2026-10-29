package de.terletzkiy.ansibility.vault.regenerate

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.vfs.VirtualFile
import de.terletzkiy.ansibility.semantics.vault.GenerationRule
import de.terletzkiy.ansibility.semantics.vault.GeneratorKind
import de.terletzkiy.ansibility.semantics.vault.NotPasswordReason
import de.terletzkiy.ansibility.semantics.vault.SecretGenerator
import de.terletzkiy.ansibility.vault.VaultVectors
import de.terletzkiy.ansibility.vault.identity.VaultProjectSettings

/**
 * Plan amendment R29, Part 2: Regenerate Secrets… over files, folders and roots (D215–D222): new values with each
 * value's id and header, values that are no password unchecked, vault files never touched, equal values sharing one
 * new value (and split groups not), placeholders with the mapped id, anchors and indentation kept, rules before shape,
 * one undo, the X130 lines, the menus.
 */
class RegenerateSecretsTest : RegenerateTestCase() {
    private val vaultPath get() = "$root/group_vars/all/vault.yml"

    fun testAFileGetsNewValuesWithTheIdAndHeaderEachValueHadAndOneUndoRestoresThem() {
        write(
            vaultPath,
            VaultVectors.inline("vault_db_password", VaultVectors.encrypt("Old0Db0Password0Value0001", VaultVectors.PW1)) +
                // Labelled "new" but opened by the default password: it keeps "new" (D207/D220).
                VaultVectors.inline("vault_api_token", VaultVectors.encrypt("0f1e2d3c4b5a69788796a5b4c3d2e1f0", VaultVectors.PW1, "new")) +
                VaultVectors.inline("vault_vrrp_password", VaultVectors.encrypt("Ab3dEf7h", VaultVectors.DEV, "dev")),
        )
        ready()
        val before = text(vaultPath)

        regenerate(vf(vaultPath))

        val request = regeneratePrompts.valueRequests.single()
        assertEquals(listOf("vault_db_password", "vault_api_token", "vault_vrrp_password"), request.rows.map { it.keyPath })
        assertEquals(listOf("default", "default", "dev"), request.rows.map { it.identity })
        assertEquals("same as now: 25 alphanumeric", RegenerateTexts.source(row("vault_db_password")))
        assertEquals("same as now: 32 hex", RegenerateTexts.source(row("vault_api_token")))
        assertTrue("an 8-character value is short and keeps its length", row("vault_vrrp_password").short)
        assertEquals(SecretGenerator(GeneratorKind.ALPHANUMERIC, 8), row("vault_vrrp_password").generator)
        assertTrue(request.rows.all { it.checked })

        val after = text(vaultPath)
        assertTrue(after, after.contains("vault_db_password: !vault |\n  \$ANSIBLE_VAULT;1.1;AES256\n"))
        assertTrue(after, after.contains("vault_api_token: !vault |\n  \$ANSIBLE_VAULT;1.2;AES256;new\n"))
        assertTrue(after, after.contains("vault_vrrp_password: !vault |\n  \$ANSIBLE_VAULT;1.2;AES256;dev\n"))
        val db = valueOf(vaultPath, "vault_db_password")!!
        assertTrue(db, db.length == 25 && db.all { it.isLetterOrDigit() } && db != "Old0Db0Password0Value0001")
        val token = valueOf(vaultPath, "vault_api_token")!!
        assertTrue(token, token.length == 32 && token.all { it in "0123456789abcdef" } && token != "0f1e2d3c4b5a69788796a5b4c3d2e1f0")
        val vrrp = valueOf(vaultPath, "vault_vrrp_password", VaultVectors.DEV)!!
        assertTrue(vrrp, vrrp.length == 8 && vrrp.all { it.isLetterOrDigit() })
        val report = feedback().single()
        assertTrue(report, report.startsWith("3 secrets regenerated in 1 file. They take effect with the next deploy."))

        undoOnce()
        assertEquals("one undo restores every old envelope", before, text(vaultPath))
    }

    fun testValuesThatAreNoPasswordStayUncheckedAndVaultFilesAreNeverTouched() {
        val pem = "-----BEGIN CERTIFICATE-----\nMIIBszCCAVmgAwIBAgIUQ0FBQ0FBQ0FBQ0FBQ0FBQ0FBQ0Ew\n-----END CERTIFICATE-----\n"
        write(
            vaultPath,
            VaultVectors.inline("vault_tls_cert", VaultVectors.encrypt(pem, VaultVectors.PW1)) +
                VaultVectors.inline("vault_settings", VaultVectors.encrypt("""{"user":"app"}""", VaultVectors.PW1)) +
                VaultVectors.inline("vault_phrase", VaultVectors.encrypt("correct horse battery staple", VaultVectors.PW1)) +
                VaultVectors.inline("vault_db_password", VaultVectors.encrypt("Old0Db0Password0Value0001", VaultVectors.PW1)),
        )
        write("$root/files/ssl/demo.key", VaultVectors.encrypt("synthetic key material\n", VaultVectors.PW1).formatBytes())
        ready()
        val vaultFile = bytes("$root/files/ssl/demo.key")
        val certBefore = envelopeOf(text(vaultPath), "vault_tls_cert")

        regenerate(vf(root))

        assertEquals(NotPasswordReason.PEM_BLOCK, row("vault_tls_cert").notPassword)
        assertEquals(NotPasswordReason.STRUCTURED, row("vault_settings").notPassword)
        assertEquals(NotPasswordReason.SPACES, row("vault_phrase").notPassword)
        assertFalse(row("vault_tls_cert").checked || row("vault_settings").checked || row("vault_phrase").checked)
        assertTrue(row("vault_db_password").checked)
        val vaultRow = row("demo.key")
        assertEquals(RowKind.VAULT_FILE, vaultRow.kind)
        assertFalse(vaultRow.enabled || vaultRow.checked)
        assertEquals("vault files hold keys and certificates", vaultRow.unavailable)

        assertEquals("unchecked: untouched", certBefore, envelopeOf(text(vaultPath), "vault_tls_cert"))
        assertEquals(pem, valueOf(vaultPath, "vault_tls_cert"))
        assertEquals("correct horse battery staple", valueOf(vaultPath, "vault_phrase"))
        assertFalse("Old0Db0Password0Value0001" == valueOf(vaultPath, "vault_db_password"))
        assertTrue("never regenerated", vaultFile.contentEquals(bytes("$root/files/ssl/demo.key")))
        assertTrue(feedback().single().startsWith("1 secret regenerated in 1 file."))
    }

    fun testEqualValuesShareOneNewValueAndASplitGroupGetsTwo() {
        val same = "Same0Secret0Value0123456789"
        write("$root/group_vars/app/vault.yml", VaultVectors.inline("vault_app_db_password", VaultVectors.encrypt(same, VaultVectors.PW1)))
        write("$root/group_vars/db/vault.yml", VaultVectors.inline("vault_db_password", VaultVectors.encrypt(same, VaultVectors.DEV, "dev")))
        write("$root/group_vars/web/vault.yml", VaultVectors.inline("vault_web_password", VaultVectors.encrypt("Other0Secret0Value012345678", VaultVectors.PW1)))
        ready()

        regenerate(vf("$root/group_vars"))

        val app = row("vault_app_db_password")
        val db = row("vault_db_password")
        assertNotNull("equal values are one group", app.group)
        assertEquals(app.group, db.group)
        assertNull(row("vault_web_password").group)
        val newApp = valueOf("$root/group_vars/app/vault.yml", "vault_app_db_password")!!
        val newDb = valueOf("$root/group_vars/db/vault.yml", "vault_db_password", VaultVectors.DEV)!!
        assertEquals("one new value shared, each encrypted with its own id", newApp, newDb)
        assertFalse(newApp == same)
        assertFalse(newApp == valueOf("$root/group_vars/web/vault.yml", "vault_web_password"))
        assertTrue(text("$root/group_vars/db/vault.yml").contains("\$ANSIBLE_VAULT;1.2;AES256;dev\n"))

        // They are equal again now: split the group this time.
        regeneratePrompts.onValues = { request ->
            request.groups.keys.forEach(request::split)
            true
        }
        regenerate(vf("$root/group_vars"))
        assertNull(row("vault_app_db_password").group)
        val splitApp = valueOf("$root/group_vars/app/vault.yml", "vault_app_db_password")!!
        val splitDb = valueOf("$root/group_vars/db/vault.yml", "vault_db_password", VaultVectors.DEV)!!
        assertFalse("a split group gets a value per row", splitApp == splitDb)
        assertFalse(splitApp == newApp)
    }

    fun testCheckingOneValueOfAGroupChecksTheGroupAndAGeneratorAppliesToAllOfIt() {
        val same = "Same0Secret0Value0123456789"
        write(vaultPath, VaultVectors.inline("vault_a_password", VaultVectors.encrypt(same, VaultVectors.PW1)) +
            VaultVectors.inline("vault_b_password", VaultVectors.encrypt(same, VaultVectors.PW1)))
        ready()
        regeneratePrompts.onValues = { request ->
            val a = request.rows.single { it.keyPath == "vault_a_password" }
            request.setChecked(a, false)
            assertFalse("the group unchecks together", request.rows.any { it.checked })
            request.setChecked(a, true)
            request.setGenerator(listOf(a), SecretGenerator(GeneratorKind.HEX, 40))
            assertTrue(request.rows.all { it.checked && it.generator == SecretGenerator(GeneratorKind.HEX, 40) && it.source == GeneratorSource.CHOSEN })
            true
        }

        regenerate(vf(vaultPath))

        val a = valueOf(vaultPath, "vault_a_password")!!
        assertEquals(a, valueOf(vaultPath, "vault_b_password"))
        assertTrue(a, a.length == 40 && a.all { it in "0123456789abcdef" })
    }

    fun testPlaceholdersGetTheMappedIdAndAnchorsAndIndentationStay() {
        write("$root/environments/prod/hosts.yml", "all:\n  hosts:\n    web1:\n")
        val path = "$root/environments/prod/group_vars/all/vault.yml"
        val nested = VaultVectors.encrypt("Old0Nested0Password0001", VaultVectors.PW1)
        write(
            path,
            "app:\n    vault_nested_password: &pw !vault |\n" + nested.formatLines().joinToString("") { "          $it\n" } +
                "    vault_new_password: &ph CHANGEME\n" +
                "    vault_empty_password: \"\"\n" +
                "    plain: 1\n" +
                "copy: *pw\n" +
                "other: *ph\n",
        )
        ready()
        val rootKey = registry.discovery(root(root)).rootKey
        VaultProjectSettings.getInstance(project).update(rootKey) { it.copy(environmentIdentities = mapOf("prod" to "prod")) }

        regenerate(vf("$root/environments"))

        assertEquals(RowKind.PLAINTEXT, row("app.vault_new_password").kind)
        assertEquals("the environment's id (Ansible's rule plus the mapping)", "prod", row("app.vault_new_password").identity)
        assertEquals("32 alphanumeric", RegenerateTexts.source(row("app.vault_new_password")))
        assertTrue("an empty placeholder is regenerated too", row("app.vault_empty_password").checked)
        assertEquals("default", row("app.vault_nested_password").identity)

        val after = text(path)
        assertTrue(after, after.startsWith("app:\n    vault_nested_password: &pw !vault |\n          \$ANSIBLE_VAULT;1.1;AES256\n          "))
        assertTrue(after, after.contains("\n    vault_new_password: &ph !vault |\n      \$ANSIBLE_VAULT;1.2;AES256;prod\n      "))
        assertTrue(after, after.contains("\n    vault_empty_password: !vault |\n      \$ANSIBLE_VAULT;1.2;AES256;prod\n"))
        assertTrue(after, after.contains("\n    plain: 1\ncopy: *pw\nother: *ph\n"))
        val created = valueOf(path, "vault_new_password", VaultVectors.PROD)!!
        assertTrue(created, created.length == 32 && created.all { it.isLetterOrDigit() })
        val nestedNew = valueOf(path, "vault_nested_password")!!
        assertTrue(nestedNew, nestedNew.length == 23 && nestedNew != "Old0Nested0Password0001")
        assertFalse("CHANGEME" in after)
    }

    fun testAPlaceholderWhoseRootLeavesAChoiceAsksOncePerRoot() {
        write(vaultPath, "vault_one_password: CHANGEME\nvault_two_password: CHANGEME\n")
        ready()
        prompts.identity = { "dev" }

        regenerate(vf(vaultPath))

        assertNull("asked when you regenerate", row("vault_one_password").identity)
        assertNull("placeholders are never grouped", row("vault_one_password").group)
        assertEquals(1, prompts.identityRequests.size)
        for (key in listOf("vault_one_password", "vault_two_password")) {
            assertTrue(envelopeOf(text(vaultPath), key).startsWith("\$ANSIBLE_VAULT;1.2;AES256;dev\n"))
        }
        assertFalse("two placeholders get two values", valueOf(vaultPath, "vault_one_password", VaultVectors.DEV) == valueOf(vaultPath, "vault_two_password", VaultVectors.DEV))
    }

    fun testARuleOfTheRootWinsOverTheShape() {
        write(vaultPath, VaultVectors.inline("vault_app_cookie_secret", VaultVectors.encrypt("Alnum0Cookie0Value00", VaultVectors.PW1)) +
            VaultVectors.inline("vault_db_password", VaultVectors.encrypt("Old0Db0Password0Value0001", VaultVectors.PW1)))
        ready()
        val rootKey = registry.discovery(root(root)).rootKey
        VaultProjectSettings.getInstance(project).update(rootKey) {
            it.copy(generationRules = listOf(GenerationRule("vault_*_cookie_secret", GeneratorKind.HEX, 32)))
        }

        regenerate(vf(vaultPath))

        assertEquals(GeneratorSource.RULE, row("vault_app_cookie_secret").source)
        assertEquals("rule vault_*_cookie_secret: 32 hex", RegenerateTexts.source(row("vault_app_cookie_secret")))
        assertEquals(GeneratorSource.SAME_AS_NOW, row("vault_db_password").source)
        val cookie = valueOf(vaultPath, "vault_app_cookie_secret")!!
        assertTrue(cookie, cookie.length == 32 && cookie.all { it in "0123456789abcdef" })
    }

    fun testCancellingTheDialogWritesNothing() {
        write(vaultPath, VaultVectors.inline("vault_db_password", VaultVectors.encrypt("Old0Db0Password0Value0001", VaultVectors.PW1)))
        ready()
        val before = bytes(vaultPath)
        regeneratePrompts.onValues = { false }

        regenerate(vf(vaultPath))

        assertTrue(before.contentEquals(bytes(vaultPath)))
        assertEmpty(feedback())
    }

    fun testTheSummaryNamesTheRolesAndTemplatesThatUseTheValues() {
        write(vaultPath, VaultVectors.inline("vault_db_password", VaultVectors.encrypt("Old0Db0Password0Value0001", VaultVectors.PW1)) +
            VaultVectors.inline("vault_api_token", VaultVectors.encrypt("Old0Api0Token0Value000001", VaultVectors.PW1)))
        write("$root/group_vars/all/vars.yml", "api_token: \"{{ vault_api_token }}\"\n")
        write("$root/roles/web/tasks/main.yml", "- name: Configure\n  ansible.builtin.template:\n    src: app.conf.j2\n    dest: /etc/app.conf\n    mode: \"0600\"\n")
        write("$root/roles/web/templates/app.conf.j2", "password={{ vault_db_password }}\n")
        write("$root/roles/api/tasks/main.yml", "- name: Call\n  ansible.builtin.debug:\n    msg: \"{{ api_token | length }}\"\n")
        write("$root/templates/site.conf.j2", "db={{ vault_db_password }}\n")
        ready()

        regenerate(vf(vaultPath))

        val report = feedback().single()
        assertTrue(report, report.contains("Used by:"))
        assertTrue(report, report.contains("vault_db_password: role web, template site.conf.j2"))
        assertTrue("followed through api_token: $report", report.contains("vault_api_token: role api"))
        assertFalse(report, report.contains("Old0"))
    }

    fun testRegenerateSecretsIsInTheMenusAfterChangeVaultIdAndShowsForRepositoryFolders() {
        val actions = ActionManager.getInstance()
        val action = actions.getAction("Ansibility.Vault.RegenerateSecrets")
        assertEquals("Ansibility Vault: Regenerate Secrets…", action.templateText)
        assertEquals(listOf("Regenerate Vault Secrets"), action.synonyms.map { it.get() })
        for (menu in listOf("Ansibility.Vault.FileMenu", "Ansibility.Vault.ToolsMenu")) {
            val children = (actions.getAction(menu) as DefaultActionGroup).getChildren(null).toList()
            val rotation = actions.getAction("Ansibility.Vault.Rotation")
            assertTrue(menu, children.indexOf(rotation) > children.indexOf(actions.getAction("Ansibility.Vault.ChangeFileId")))
            assertTrue((rotation as DefaultActionGroup).getChildren(null).contains(action))
        }

        write(vaultPath, VaultVectors.inline("vault_db_password", VaultVectors.encrypt("x", VaultVectors.PW1)))
        write("$root/files/ssl/demo.key", VaultVectors.encrypt("k\n", VaultVectors.PW1).formatBytes())
        write("elsewhere/plain.yml", "a: 1\n")
        ready()
        fun text(place: String, kind: ActionUiKind, vararg files: VirtualFile): String? {
            val context = SimpleDataContext.builder().add(CommonDataKeys.PROJECT, project).add(CommonDataKeys.VIRTUAL_FILE_ARRAY, arrayOf(*files))
                .add(CommonDataKeys.VIRTUAL_FILE, files.first()).build()
            val event = AnActionEvent.createEvent(action, context, action.templatePresentation.clone(), place, kind, null)
            runReadActionBlocking { action.update(event) }
            return event.presentation.text.takeIf { event.presentation.isEnabledAndVisible }
        }
        assertEquals("Regenerate Secrets…", text(ActionPlaces.PROJECT_VIEW_POPUP, ActionUiKind.POPUP, vf(vaultPath)))
        assertEquals("Ansibility Vault: Regenerate Secrets…", text(ActionPlaces.ACTION_SEARCH, ActionUiKind.SEARCH_POPUP, vf(vaultPath)))
        assertEquals("a repository folder holding the root", "Regenerate Secrets…", text(ActionPlaces.PROJECT_VIEW_POPUP, ActionUiKind.POPUP, vf("repos/falcon")))
        assertNull("a vault file alone", text(ActionPlaces.PROJECT_VIEW_POPUP, ActionUiKind.POPUP, vf("$root/files/ssl/demo.key")))
        assertNull("outside every root", text(ActionPlaces.PROJECT_VIEW_POPUP, ActionUiKind.POPUP, vf("elsewhere/plain.yml")))
    }
}
