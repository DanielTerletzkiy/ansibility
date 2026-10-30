package de.terletzkiy.ansibility.vault.rotate

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.util.ui.UIUtil
import de.terletzkiy.ansibility.api.VaultSourceKind
import de.terletzkiy.ansibility.api.VaultUnlockResult
import de.terletzkiy.ansibility.semantics.vault.GeneratorKind
import de.terletzkiy.ansibility.vault.VaultVectors
import de.terletzkiy.ansibility.vault.identity.ExplicitIdentity
import de.terletzkiy.ansibility.vault.identity.PasswordManagerReader
import de.terletzkiy.ansibility.vault.identity.VaultProjectSettings
import de.terletzkiy.ansibility.vault.monitor.SecretHealthPanel
import de.terletzkiy.ansibility.vault.secrets.VaultUserState
import de.terletzkiy.ansibility.vault.ui.VaultUiTimings
import de.terletzkiy.ansibility.vault.ui.settings.VaultConfigurable
import java.awt.datatransfer.DataFlavor
import java.security.SecureRandom
import javax.swing.JButton
import javax.swing.JLabel

/**
 * Plan amendment R29, Part 1: password sources and where rotation starts (D204, D206, D209, D211–D214): roots sharing a
 * password file are one group; equal passwords of unselected unlocked roots are reported; password manager sources are
 * not written and Check reads them again; the previous password is kept, replaced and forgettable on the settings
 * page; Rotate Password… there preselects root and id; rotate and regenerate in one pass; the action's registration
 * and texts by place.
 */
class RotationSourcesTest : RotationTestCase() {
    private fun rootKey(relative: String): String = registry.discovery(root(relative)).rootKey

    fun testRootsReadingOnePasswordFileAreOneGroupAndRotateTogether() {
        val shared = write("shared/vault.pw", "${VaultVectors.PW1}\n")
        val heron = projectRoot("heron")
        write(vaultPath, VaultVectors.inline("vault_db_password", VaultVectors.encrypt("Falcon0Old0Value01", VaultVectors.PW1)))
        write("$heron/group_vars/all/vault.yml", VaultVectors.inline("vault_db_password", VaultVectors.encrypt("Heron0Old0Value001", VaultVectors.PW1)))
        ready()
        for (relative in listOf(root, heron)) {
            VaultProjectSettings.getInstance(project).update(rootKey(relative)) {
                it.copy(identities = listOf(ExplicitIdentity("default", VaultSourceKind.PASSWORD_FILE, shared.toString())))
            }
        }

        rotate(vf(root))

        val group = rotationPrompts.requests.single().identity.groups.single()
        assertEquals("both roots read the same file: one row", 2, group.roots.size)
        assertTrue("checked together, although only falcon was selected", group.checked)
        assertEquals("file:${shared.toRealPath()}", group.source.key)
        assertEquals(newPassword, String(java.nio.file.Files.readAllBytes(shared)))
        assertEquals("Falcon0Old0Value01", valueOf(vaultPath, "vault_db_password", newPassword))
        assertEquals("Heron0Old0Value001", valueOf("$heron/group_vars/all/vault.yml", "vault_db_password", newPassword))
    }

    fun testAnUnselectedUnlockedRootWithTheSamePasswordIsReportedAndKeepsIt() {
        val heron = defaultRoot("heron")
        write(vaultPath, VaultVectors.inline("vault_db_password", VaultVectors.encrypt("Falcon0Old0Value01", VaultVectors.PW1)))
        write("$heron/group_vars/all/vault.yml", VaultVectors.inline("vault_db_password", VaultVectors.encrypt("Heron0Old0Value001", VaultVectors.PW1)))
        ready()
        val heronRoot = root(heron)
        assertTrue(await { secrets.unlock(heronRoot) } is VaultUnlockResult.Unlocked)

        rotate(vf(root))

        val groups = rotationPrompts.requests.single().identity.groups
        assertEquals("two password files, two rows", 2, groups.size)
        assertFalse("heron is not selected", groups.single { group -> group.roots.any { it.root == heronRoot } }.checked)
        val notes = rotationPrompts.reviews.single().equalPasswords
        assertEquals(listOf("${heronRoot.displayName}'s default has the same password and is not included; it keeps the old one."), notes)
        assertEquals("Heron0Old0Value001", valueOf("$heron/group_vars/all/vault.yml", "vault_db_password", VaultVectors.PW1))
        assertEquals(VaultVectors.PW1, text("$heron/.vault-pass").trim())
        assertTrue(service.lastSummary!!.text, service.lastSummary!!.text.contains("has the same password and is not included"))
    }

    fun testAPasswordManagerIsNotWrittenAndCheckReadsItAgain() {
        var managed = VaultVectors.PW1
        secrets.setPasswordManagersForTests(PasswordManagerReader { _, _, _ -> managed.toByteArray() })
        write(vaultPath, VaultVectors.inline("vault_db_password", VaultVectors.encrypt("Db0Old0Value0001", VaultVectors.PW1)))
        ready()
        VaultProjectSettings.getInstance(project).update(rootKey(root)) {
            it.copy(identities = listOf(ExplicitIdentity("default", VaultSourceKind.ONE_PASSWORD, "op://Private/vault/password")))
        }

        rotate()

        val group = rotationPrompts.requests.single().identity.groups.single()
        assertEquals(Destination.YOURSELF, group.source.destination)
        assertEquals("Update op://Private/vault/password yourself (Copy, then Check)", RotationTexts.destination(group))
        assertEquals("the conventional file is not this id's source here", VaultVectors.PW1, text("$root/.vault-pass").trim())
        assertEquals("Db0Old0Value0001", valueOf(vaultPath, "vault_db_password", newPassword))
        val summary = service.lastSummary!!
        assertTrue(summary.text, summary.text.contains("Update now: op://Private/vault/password (Copy New Password, then Check)."))
        feedback()

        fun check(): String {
            service.check(summary)
            awaitRotation()
            return feedback().single()
        }
        assertEquals("op://Private/vault/password: still the old password.", check())
        managed = newPassword
        assertEquals("op://Private/vault/password: matches the new password.", check())
        managed = "an-unrelated-value"
        assertEquals("op://Private/vault/password: a different password.", check())

        service.copyNewPassword(summary)
        awaitRotation()
        assertEquals(newPassword, CopyPasteManager.getInstance().contents?.getTransferData(DataFlavor.stringFlavor))
        scheduler.advance(VaultUiTimings.CLIPBOARD_MILLIS)
        assertFalse(newPassword == CopyPasteManager.getInstance().contents?.getTransferData(DataFlavor.stringFlavor))
    }

    fun testAPasswordFileThatIsAVaultItselfIsNotWritten() {
        val kite = projectRoot("kite", "[defaults]\nvault_identity_list = dev@dev.pw\n")
        write("$kite/dev.pw", "${VaultVectors.DEV}\n")
        // The default password, vaulted with dev's: Ansible decrypts it with the secrets loaded before it.
        val vaulted = VaultVectors.encrypt(VaultVectors.PW1, VaultVectors.DEV, "dev").formatBytes()
        write("$kite/.vault-pass", vaulted)
        write("$kite/group_vars/all/vault.yml", VaultVectors.inline("vault_db_password", VaultVectors.encrypt("Kite0Old0Value001", VaultVectors.PW1)))
        root = kite
        ready()
        val kiteRoot = root(kite)
        assertTrue(await { secrets.unlock(kiteRoot, "dev") } is VaultUnlockResult.Unlocked)

        rotate(vf(kite))

        val destinations = rotationPrompts.reviews.single().destinations
        assertTrue(destinations.toString(), destinations.any { it.endsWith(".vault-pass is a vault itself and is not written: update it yourself (Copy, then Check).") })
        assertTrue("never written", vaulted.contentEquals(bytes("$kite/.vault-pass")))
        assertEquals("Kite0Old0Value001", valueOf("$kite/group_vars/all/vault.yml", "vault_db_password", newPassword))
        val summary = service.lastSummary!!
        assertTrue(summary.text, summary.text.contains(".vault-pass (Copy New Password, then Check)."))
        feedback()
        service.check(summary)
        awaitRotation()
        assertTrue(feedback().single().endsWith(".vault-pass: still the old password."))
    }

    /**
     * D212 and review round 2, decision 2: every rotation keeps the password before it as an entry of its own, never
     * overwritten (the first build replaced it); Settings › Vault lists each with its date and forgets them one by one.
     */
    fun testEveryPreviousPasswordIsKeptAndForgottenOneByOne() {
        write(vaultPath, VaultVectors.inline("vault_db_password", VaultVectors.encrypt("Db0Old0Value0001", VaultVectors.PW1)))
        ready()
        val falcon = root(root)

        rotate()
        val first = newPassword
        assertEquals(VaultVectors.PW1, previous(falcon))
        val record = VaultUserState.getInstance().previousPasswords().single()
        assertEquals("default", record.label)
        assertEquals(registry.discovery(falcon).canonicalRootPath, record.root)

        newPassword = NewPassword.generate(SecureRandom()).use { String(it.chars()) }
        rotate()
        assertEquals("the newest is the first rotation's password", first, previous(falcon))
        assertEquals("the one before it is kept too", listOf(first, VaultVectors.PW1), previousAll(falcon))
        assertEquals(2, VaultUserState.getInstance().previousPasswords().size)
        assertEquals("Db0Old0Value0001", valueOf(vaultPath, "vault_db_password", newPassword))

        val page = VaultConfigurable(project)
        try {
            val component = page.createComponent()!!
            val roots = UIUtil.findComponentsOfType(component, ComboBox::class.java).first()
            PlatformTestUtil.waitWithEventsDispatching("the roots did not load", { roots.itemCount > 0 }, 60)
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            val labels = UIUtil.findComponentsOfType(component, JLabel::class.java).mapNotNull { it.text }
            assertEquals(labels.toString(), 2, labels.count { it.startsWith("default: previous password kept (") })
            val oldest = VaultUserState.getInstance().previousPasswords().last()
            val forget = UIUtil.findComponentsOfType(component, JButton::class.java).single { it.name == VaultConfigurable.forgetComponentName(oldest) }

            forget.doClick()

            waitFor("the previous password was not forgotten") { VaultUserState.getInstance().previousPasswords().size == 1 }
            assertEquals("only the oldest is forgotten", listOf(first), previousAll(falcon))
            assertEquals("the id's own entry stays", newPassword, stored(falcon))
        } finally {
            page.disposeUIResources()
        }
    }

    fun testRotatePasswordOnTheSettingsPagePreselectsTheRootAndTheId() {
        val heron = defaultRoot("heron")
        write(vaultPath, VaultVectors.inline("vault_db_password", VaultVectors.encrypt("Falcon0Old0Value01", VaultVectors.PW1)))
        write("$heron/group_vars/all/vault.yml", VaultVectors.inline("vault_db_password", VaultVectors.encrypt("Heron0Old0Value001", VaultVectors.PW1)))
        ready()
        val heronRoot = root(heron)
        rotationPrompts.onRotation = { null }
        val page = VaultConfigurable(project)
        try {
            val component = page.createComponent()!!
            @Suppress("UNCHECKED_CAST")
            val roots = UIUtil.findComponentsOfType(component, ComboBox::class.java).first() as ComboBox<Any>
            PlatformTestUtil.waitWithEventsDispatching("the roots did not load", { roots.itemCount > 0 }, 60)
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            roots.selectedIndex = (0 until roots.itemCount).single { (roots.getItemAt(it) as VaultConfigurable.RootEntry).name == heronRoot.displayName }
            val button = UIUtil.findComponentsOfType(component, JButton::class.java).single { it.name == VaultConfigurable.ROTATE_COMPONENT }

            button.doClick()
            awaitRotation()
        } finally {
            page.disposeUIResources()
        }

        val request = rotationPrompts.requests.single()
        assertEquals("default", request.identity.label)
        assertTrue("heron is preselected", request.identity.groups.single { group -> group.roots.any { it.root == heronRoot } }.checked)
        assertFalse("falcon is not", request.identity.groups.single { group -> group.roots.none { it.root == heronRoot } }.checked)
    }

    fun testRotateAndRegenerateInOnePass() {
        write(
            vaultPath,
            VaultVectors.inline("vault_db_password", VaultVectors.encrypt("Db0Old0Value0001", VaultVectors.PW1)) +
                VaultVectors.inline("vault_app_cookie_secret", VaultVectors.encrypt("0f1e2d3c4b5a69788796a5b4c3d2e1f0", VaultVectors.PW1, "new")) +
                VaultVectors.inline("vault_api_token", VaultVectors.encrypt("Api0Old0Value0001", VaultVectors.PW1)),
        )
        ready()
        rotationPrompts.onReview = { review ->
            assertTrue(review.canRegenerate)
            assertFalse("unchecked unless you check it", review.alsoRegenerate)
            review.alsoRegenerate = true
            true
        }
        regeneratePrompts.onValues = { request ->
            request.setChecked(request.rows.single { it.keyPath == "vault_api_token" }, false)
            true
        }

        rotate()

        val rows = regeneratePrompts.valueRequests.single().rows
        assertEquals(listOf("vault_db_password", "vault_app_cookie_secret", "vault_api_token"), rows.map { it.keyPath })
        assertEquals(listOf("default", "default", "default"), rows.map { it.identity })
        val db = valueOf(vaultPath, "vault_db_password", newPassword)!!
        assertTrue(db, db.length == 16 && db.all { it.isLetterOrDigit() } && db != "Db0Old0Value0001")
        val cookie = valueOf(vaultPath, "vault_app_cookie_secret", newPassword)!!
        assertTrue(cookie, cookie.length == 32 && cookie.all { it in "0123456789abcdef" } && cookie != "0f1e2d3c4b5a69788796a5b4c3d2e1f0")
        assertTrue(text(vaultPath).contains("vault_app_cookie_secret: !vault |\n  \$ANSIBLE_VAULT;1.2;AES256;new\n"))
        assertEquals("not chosen: rotated, value kept", "Api0Old0Value0001", valueOf(vaultPath, "vault_api_token", newPassword))
        assertNull(valueOf(vaultPath, "vault_db_password", VaultVectors.PW1))
        val summary = service.lastSummary!!
        assertEquals(2, summary.regenerated)
        assertTrue(summary.text, summary.text.contains("2 secrets got new values in the same pass."))
        assertEquals(GeneratorKind.HEX, rows[1].generator.kind)
    }

    fun testTheActionIsRegisteredAndNamedByPlace() {
        val actions = ActionManager.getInstance()
        val action = actions.getAction("Ansibility.Vault.RotatePassword")
        assertEquals("Ansibility Vault: Rotate Vault Password…", action.templateText)
        assertEquals(listOf("Rotate Vault Password"), action.synonyms.map { it.get() })
        assertFalse(action.templatePresentation.description.isNullOrBlank())
        val rotation = actions.getAction("Ansibility.Vault.Rotation") as DefaultActionGroup
        val children = rotation.getChildren(null).toList()
        assertTrue(children.indexOf(action) > children.indexOf(actions.getAction("Ansibility.Vault.RegenerateSecrets")))
        for (menu in listOf("Ansibility.Vault.FileMenu", "Ansibility.Vault.ToolsMenu")) {
            assertTrue(menu, (actions.getAction(menu) as DefaultActionGroup).getChildren(null).contains(rotation))
        }

        write(vaultPath, VaultVectors.inline("vault_db_password", VaultVectors.encrypt("x", VaultVectors.PW1)))
        write("elsewhere/readme.txt", "r\n")
        ready()
        fun update(target: AnAction, place: String, kind: ActionUiKind, vararg files: VirtualFile): AnActionEvent {
            val builder = SimpleDataContext.builder().add(CommonDataKeys.PROJECT, project)
            if (files.isNotEmpty()) builder.add(CommonDataKeys.VIRTUAL_FILE_ARRAY, arrayOf(*files)).add(CommonDataKeys.VIRTUAL_FILE, files.first())
            val event = AnActionEvent.createEvent(target, builder.build(), target.templatePresentation.clone(), place, kind, null)
            runReadActionBlocking { target.update(event) }
            return event
        }
        fun text(place: String, kind: ActionUiKind, vararg files: VirtualFile): String? =
            update(action, place, kind, *files).presentation.let { if (it.isEnabledAndVisible) it.text else null }
        assertEquals("Rotate Vault Password…", text(ActionPlaces.PROJECT_VIEW_POPUP, ActionUiKind.POPUP, vf(vaultPath)))
        assertEquals("Rotate Vault Password…", text(ActionPlaces.MAIN_MENU, ActionUiKind.MAIN_MENU))
        assertEquals("Ansibility Vault: Rotate Vault Password…", text(ActionPlaces.ACTION_SEARCH, ActionUiKind.SEARCH_POPUP))
        assertEquals("Ansibility Vault: Rotate Vault Password…", text(SecretHealthPanel.TOOLBAR_PLACE, ActionUiKind.TOOLBAR))
        // Tools › Ansibility Vault shows without a selection (and for a file outside every root) for this action.
        val tools = actions.getAction("Ansibility.Vault.ToolsMenu")
        assertTrue(update(tools, ActionPlaces.MAIN_MENU, ActionUiKind.MAIN_MENU).presentation.isVisible)
        assertTrue(update(tools, ActionPlaces.MAIN_MENU, ActionUiKind.MAIN_MENU, vf("elsewhere/readme.txt")).presentation.isVisible)
        assertFalse("the context menus still need a selection in a root", update(actions.getAction("Ansibility.Vault.FileMenu"), ActionPlaces.PROJECT_VIEW_POPUP, ActionUiKind.POPUP, vf("elsewhere/readme.txt")).presentation.isVisible)

        // The Vault tab's toolbar.
        val panel = SecretHealthPanel(project)
        try {
            val toolbar = panel.toolbar as ActionToolbar
            assertTrue((toolbar.actionGroup as DefaultActionGroup).getChildren(null).contains(action))
        } finally {
            Disposer.dispose(panel)
        }
    }
}
