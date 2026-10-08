package de.terletzkiy.ansibility.semantics.secrets

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** The key-like names of ANS-V108 (plan amendment R21, D161): the path decides, at any depth below the conventions. */
class KeyLikeNamesTest {
    @Test
    fun `keys below files ssl and ssh and passwords below files are key-like`() {
        val expected = mapOf(
            "roles/web/files/ssl/web.key" to KeyLikeName.SSL_KEY,
            "roles/web/files/ssl/falcon/2026/web.key" to KeyLikeName.SSL_KEY,
            "files/ssl/web.key" to KeyLikeName.SSL_KEY,
            "repos/thrush/ansible/roles/db/files/ssh/id_tern.key" to KeyLikeName.SSH_KEY,
            "roles/db/files/db.password" to KeyLikeName.PASSWORD,
            "roles/db/files/conf/deep/db.password" to KeyLikeName.PASSWORD,
            "files/admin.password" to KeyLikeName.PASSWORD,
        )
        for ((path, name) in expected) assertEquals(name, KeyLikeNames.of(path), path)
    }

    @Test
    fun `other names and places are not key-like`() {
        val paths = listOf(
            "roles/web/files/web.key", // a key below files, but not below ssl or ssh: the content decides
            "roles/web/ssl/web.key", // no files directory
            "roles/web/files/tls/web.key",
            "roles/web/files/ssl/web.crt",
            "roles/web/files/ssl/web.key.j2",
            "roles/web/files/ssl/.key", // no stem
            "roles/web/templates/db.password",
            "roles/web/files/.password",
            "roles/web/files/db.passwords",
            "roles/web/filesx/ssl/web.key",
            "web.key",
            "",
        )
        for (path in paths) assertEquals(null, KeyLikeNames.of(path), path)
    }

    @Test
    fun `the directory list form matches the path form`() {
        assertEquals(KeyLikeName.SSL_KEY, KeyLikeNames.of(listOf("roles", "web", "files", "ssl"), "web.key"))
        assertEquals(KeyLikeName.SSL_KEY, KeyLikeNames.of(listOf("files", "ssl", "files", "ssh"), "x.key"), "the outermost convention wins")
        assertEquals(null, KeyLikeNames.of(emptyList(), "db.password"))
    }
}
