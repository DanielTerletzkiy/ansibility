package de.terletzkiy.ansibility.inspections.vault

import java.security.MessageDigest

/**
 * Synthetic password hashes for the ANS-V108 plugin tests (plan amendment R21, the hash-only exemption of key-like
 * files): made from the synthetic password `synthetic-alice-pw` with libxcrypt (in a Debian container without network)
 * and passlib 1.7.4, computed at test time, or hand-built of the documented shape. No real password or hash;
 * `semantics.secrets.PasswordHashesTest` covers every format.
 */
object TestHashes {
    /** sha512crypt (`$6$`), as Ansible's `password_hash('sha512')` and `/etc/shadow` write it. */
    const val SHA512_CRYPT: String = "\$6\$Pa2YhKpEuJJEr44e\$LfzVCkDD/7nrZ9TdxxpG4sMC1v1dhu.wkf4nsBi4SUtm3LtAqwXNn.qMKspg77qMg9PGWrjDHuXVOd6xm4Wj./"

    /** MySQL `mysql_native_password` (`*` and 40 hex digits), as `mysql_user` takes it with `encrypted: true`. */
    const val MYSQL_NATIVE: String = "*7C6E2D875572CE988D4F21654426F15C69097935"

    /** bcrypt (`$2b$`, cost 5). */
    const val BCRYPT: String = "\$2b\$05\$.Z2PQ0VP8BM6TZ9mI/EIweFnNlVuWYGSxf4meI9sHKkiyOTIaCRru"

    /** Apache `$apr1$` (htpasswd -m). */
    const val APR1: String = "\$apr1\$TeIsracM\$pGZNgdEKPE8ACaAHpXoAg0"

    /**
     * A hand-built raw MySQL `caching_sha2_password` value (`$A$005$`, 20 salt characters, 43 digest characters) whose
     * salt holds control characters and a line break, as MySQL's salts may: the IDE reads such a file as binary.
     */
    fun cachingSha2Raw(): ByteArray {
        val salt = "Mq\u0001\u0002 x!`R\u001b'{Fz~|0;\u007f\n"
        val digest = "Gv3oQe8LkR2yTnW5bZ1cXuA9sD7fH0jK4mP6rV.iE/1"
        check(salt.length == 20 && digest.length == 43)
        return "\$A\$005\$$salt$digest\n".toByteArray(Charsets.ISO_8859_1)
    }

    /**
     * A PostgreSQL `md5` hash of `synthetic-bob-pw` for `bob`: logs in without the password where `pg_hba` says md5, so
     * it counts as a plaintext secret.
     */
    fun postgresMd5(): String = "md5" + hex("MD5", "synthetic-bob-pw" + "bob")

    /** A SHA-256 hex digest of [text] (64 digits): the shape of `openssl rand -hex 32`, so it counts as plaintext. */
    fun sha256Hex(text: String): String = hex("SHA-256", text)

    private fun hex(algorithm: String, text: String): String =
        MessageDigest.getInstance(algorithm).digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
}
