package de.terletzkiy.ansibility.vault.rotate

import com.intellij.find.ngrams.TrigramIndex
import com.intellij.history.LocalHistory
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.impl.cache.impl.id.IdIndex
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.util.indexing.FileBasedIndex
import de.terletzkiy.ansibility.api.VaultDecryptResult
import de.terletzkiy.ansibility.api.VaultOperations
import de.terletzkiy.ansibility.api.VaultPurpose
import de.terletzkiy.ansibility.api.VaultSourceKind
import de.terletzkiy.ansibility.api.VaultSourceOrigin
import de.terletzkiy.ansibility.api.VaultUnlockResult
import de.terletzkiy.ansibility.vault.VaultVectors
import java.nio.file.Files

/**
 * Plan amendment R30, "vault keeps working": every vault feature with a `.vault-pass` the IDE keeps out of its indexes
 * (the built-in names apply to the test project too). Vault code reads password files through `java.nio`, never through
 * an index; these tests prove the parts that do rely on the VFS: discovery's invalidation by VFS events when the
 * excluded file changes, is deleted or created on disk, the unlock (consent, read), and Rotate Vault Password…'s
 * rewrite of the excluded file (atomic replacement, consent renewal, the VFS event, still no index data afterwards).
 */
class ExcludedPasswordFileVaultTest : RotationTestCase() {
    private val passwordPath: String get() = "$root/.vault-pass"

    private fun assertKeptOut(file: VirtualFile) {
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        val index = FileBasedIndex.getInstance()
        runReadActionBlocking {
            assertTrue("excluded", ProjectFileIndex.getInstance(project).isExcluded(file))
            assertFalse("not in the content", ProjectFileIndex.getInstance(project).isInContent(file))
            assertEmpty("no word index data", index.getFileData(IdIndex.NAME, file, project).keys)
            assertEmpty("no trigram data", index.getFileData(TrigramIndex.INDEX_ID, file, project).keys)
            assertFalse("not under Local History", LocalHistory.getInstance().isUnderControl(file))
        }
    }

    private fun refreshRoot() {
        val dir = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(base.resolve(root)) ?: error("no root dir")
        VfsUtil.markDirtyAndRefresh(false, true, true, dir)
    }

    fun testDiscoveryFollowsAnExcludedVaultPassOnDisk() {
        val falcon = root(root)
        assertKeptOut(vf(passwordPath))
        val identity = registry.discovery(falcon).identities.single()
        assertEquals(VaultSourceKind.PASSWORD_FILE, identity.source.kind)
        assertEquals(VaultSourceOrigin.CONVENTIONAL_NAME, identity.source.origin)

        var stamp = registry.modificationTracker.modificationCount
        Files.writeString(base.resolve(passwordPath), "${VaultVectors.DEV}-longer\n")
        refreshRoot()
        assertTrue("a change of the excluded file reaches the registry", registry.modificationTracker.modificationCount != stamp)

        stamp = registry.modificationTracker.modificationCount
        Files.delete(base.resolve(passwordPath))
        refreshRoot()
        assertTrue("so does its deletion", registry.modificationTracker.modificationCount != stamp)
        assertEquals(VaultSourceKind.PROMPT, registry.discovery(falcon).identities.single().source.kind)

        stamp = registry.modificationTracker.modificationCount
        write(passwordPath, "${VaultVectors.PW1}\n")
        refreshRoot()
        assertTrue("and its creation", registry.modificationTracker.modificationCount != stamp)
        assertEquals(VaultSourceOrigin.CONVENTIONAL_NAME, registry.discovery(falcon).identities.single().source.origin)
        assertKeptOut(vf(passwordPath))
    }

    fun testUnlockAndDecryptReadTheExcludedVaultPass() {
        write(vaultPath, VaultVectors.inline("vault_db_password", VaultVectors.encrypt("Db0Old0Value0001", VaultVectors.PW1)))
        ready()
        val falcon = root(root)
        assertKeptOut(vf(passwordPath))

        assertEquals(VaultUnlockResult.Unlocked(listOf("default")), await { VaultOperations.getInstance(project).unlock(falcon) })
        assertEquals("one consent for the excluded file", 1, prompter.consentRequests.size)
        val decrypted = await { VaultOperations.getInstance(project).decrypt(location(vaultPath, "vault_db_password"), VaultPurpose.REVEAL) }
        assertTrue("$decrypted", decrypted is VaultDecryptResult.Decrypted)
        (decrypted as VaultDecryptResult.Decrypted).plaintext.close()
    }

    fun testRotationRewritesTheExcludedVaultPassWhichStaysOut() {
        write(vaultPath, VaultVectors.inline("vault_db_password", VaultVectors.encrypt("Db0Old0Value0001", VaultVectors.PW1)))
        ready()
        val falcon = root(root)
        assertKeptOut(vf(passwordPath))
        val stamp = registry.modificationTracker.modificationCount

        rotate()

        assertEquals("written without a trailing newline", newPassword, text(passwordPath))
        assertEquals("Db0Old0Value0001", valueOf(vaultPath, "vault_db_password", newPassword))
        assertTrue("the VFS learnt of the rewrite: discovery was invalidated", registry.modificationTracker.modificationCount != stamp)
        val file = vf(passwordPath)
        assertEquals(newPassword, String(file.contentsToByteArray(), Charsets.UTF_8))
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        assertKeptOut(file)

        secrets.lock(falcon)
        val consents = prompter.consentRequests.size
        assertTrue(await { VaultOperations.getInstance(project).unlock(falcon) } is VaultUnlockResult.Unlocked)
        assertEquals("the renewed consent holds for the rewritten excluded file", consents, prompter.consentRequests.size)
    }
}
