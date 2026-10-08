package de.terletzkiy.ansibility.semantics.secrets

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import java.util.Base64

/**
 * The password-hash recogniser of plan amendment R21 (D161 as built after the user report of 2026-10-08): hash-only
 * key-like files hold no plaintext secret. Every vector is synthetic: made from the synthetic passwords
 * `synthetic-alice-pw` / `synthetic-bob-pw` with libxcrypt (`crypt_gensalt` + `crypt` in a Debian container without
 * network: yescrypt, gost-yescrypt, scrypt, sha1crypt, Sun MD5), passlib 1.7.4 (in a container without network; the
 * `$bcrypt-sha256$` vectors with its builtin bcrypt), `argon2`, `openssl passwd`, or at test time with the JDK; the
 * MySQL `caching_sha2_password` vectors are hand-built strings of the documented shape.
 */
class PasswordHashesTest {
    // ------------------------------------------------------------------------------------------------ vectors

    private val crypt = mapOf(
        "md5crypt" to "\$1\$6oqSgbEa\$6wdg46jkNEbGoSNlUjW7T0",
        "md5crypt openssl" to "\$1\$bobsalt\$KLyRUmZakHJJW7B1m6Ozp0",
        "apr1" to "\$apr1\$TeIsracM\$pGZNgdEKPE8ACaAHpXoAg0",
        "apr1 openssl" to "\$apr1\$bobsalt\$zhdmCqqkWoYyn8JBjmLLC0",
        "sha256crypt" to "\$5\$ggjUMjXZKNyiB.u/\$RgtY69GB1S32TkqUHY2fq94RD7GS9MCr41L2wuIWFo0",
        "sha256crypt rounds" to "\$5\$rounds=10000\$T.Its2wLuhXp.SBy\$sGamiFKxPEe5KJVvtzGnHj2X9GzKWAp1dzUtstA4KS0",
        "sha512crypt" to SHA512_CRYPT,
        "sha512crypt openssl" to "\$6\$alicesalt\$cVH1TVOYJ4jDrlyuSfYSX8UppLi5n4NG0d3e8N/g5Zlnn8cusMEq5aiJCbDelFm1df/pY29vK8y5tm27WpN97.",
        "sha512crypt rounds" to "\$6\$rounds=10000\$fgIhX3koprt3yCpy\$ZlQAvP2w3HIlin2MJuSYFymP2CHdBl74teP4A8Mnv0W2phJjlKBEp4QdMld.eBSR8Odna6UHSBDysL3NKJcN9/",
        "bcrypt 2a" to "\$2a\$05\$7/CMh3XMwi7.CstP9PM3H.qqBTfuXPemeEAoQELgVtszxxmelzU56",
        "bcrypt 2b" to BCRYPT,
        "bcrypt 2y" to "\$2y\$05\$EejxWbKUIm77gWfYUmtM/O7VN8RhNmLgKJ3dQ.K0YoNftRnZjCc06",
        "bcrypt 2x" to "\$2x\$05\$EejxWbKUIm77gWfYUmtM/O7VN8RhNmLgKJ3dQ.K0YoNftRnZjCc06",
        "yescrypt" to "\$y\$j9T\$tyjZXgPBIgdgM0Nl4miOM0\$9RgYA5Hp.TWXaiSuqK6eU0WptGUf8nirn880piJiRx8",
        "gost-yescrypt" to "\$gy\$j9T\$JetUM1i5ShBvfQXE0zzQm.\$moL0f78i0bkqe3H81BZLXiPFBLnfUOAbDj.C1h6h0B6",
        "scrypt" to "\$7\$CU..../....qVqrDhYgcQ2w8a0yuPxCA1\$TdPs6o3/oPVUJ2Ucaxpjt7S.HpOhAE6sCF1DsR4b980",
        "sha1crypt" to "\$sha1\$232157\$aX27W3FybKmwwmJr.Lce\$34aZLWSFVQPS6e63jy4feH20KeSj",
        "sha1crypt passlib" to "\$sha1\$480000\$sttvfvHE\$Vaa61ozJOHg3bQMdBPsPsaHqWQzK",
        "Sun MD5 rounds" to "\$md5,rounds=44417\$OwAUxXzA\$\$sz5b6WHNq68uKV3GUoNHe/",
        "Sun MD5" to "\$md5\$4qesR9vw\$\$btyrdm.315IVAWg8SvIzj0",
    )

    private val argon2id = "\$argon2id\$v=19\$m=4096,t=2,p=1\$YWxpY2VzYWx0c2FsdA\$8mYJkChdQcejU1gzOcHgevNUB3sEUIel5LcZqknhipU"

    private val other = mapOf(
        "argon2id" to argon2id,
        "argon2i" to "\$argon2i\$v=19\$m=4096,t=2,p=1\$YWxpY2VzYWx0c2FsdA\$CL2H+vuW7DUBR79RPxidDgRUH/F5dzUw0zAYgYo93E4",
        "argon2d" to "\$argon2d\$v=19\$m=4096,t=2,p=1\$YWxpY2VzYWx0c2FsdA\$5dyO8iOQXavxAZ5cTxyJM+urwtsXKY9hKt0kYEbodoM",
        "argon2 without version" to "\$argon2i\$m=4096,t=2,p=1\$YWxpY2VzYWx0c2FsdA\$CL2H+vuW7DUBR79RPxidDgRUH/F5dzUw0zAYgYo93E4",
        "passlib pbkdf2-sha256" to "\$pbkdf2-sha256\$29000\$8z7H.N.7l7J2zvmfk1LqnQ\$AwmXUXk27/Qol21p4uCAwktX.FIDHpb8Sb/bwacrrEI",
        "passlib pbkdf2-sha512" to "\$pbkdf2-sha512\$25000\$zVlLiXGOEQJgjLH2Xqv13g\$9HU540aK1rGjNjdEvRiADGZP3UrESvMuDSe03oYQeO9IoEmVVvsIPAIHmO8lsBCxzJzprcJU7Ktri8avCIUUdw",
        "passlib pbkdf2 (sha1)" to "\$pbkdf2\$131000\$eC/l3JvzXuv93zuHUIpxjg\$VWJoISU25gj28aqWXN9LgdxquTs",
        "passlib bcrypt-sha256 v2" to "\$bcrypt-sha256\$v=2,t=2b,r=5\$fGS1d5dWDLiCEtV/46UPe.\$eJgIijICZTuubrx4UWScqs/8K51vak.",
        "passlib bcrypt-sha256 v1" to "\$bcrypt-sha256\$2b,5\$9EWloTUbPLIBSHnp1oEQX.\$flqXjW/c7oWQa7Joh6mz6xV0oRvVMzO",
        "Django pbkdf2_sha256" to "pbkdf2_sha256\$29000\$MoNBE7cyvkXH\$fhORyYIUNySM18yQTqTN7oLRWv462WO4vESJPsjlURI=",
        "Django pbkdf2_sha1" to "pbkdf2_sha1\$131000\$qKjyEBQkNTDj\$521xCwSrw2M0+r+EeP+9uf5g0p8=",
        "Django bcrypt_sha256" to "bcrypt_sha256\$\$2b\$12\$kbPYaZ3tTCvFVkTZcEyDbOHFS8nBEM.0Vm5EBdMxyc6WWWWUAKmnK",
        "Django bcrypt" to "bcrypt\$\$2b\$12\$41xIROSPze1RxhOC62bPtuHXazozDUz4HVGfhatrXcTomwF3KwtPm",
        "Django argon2" to "argon2" + argon2id,
        "MySQL native" to MYSQL_NATIVE,
        "MySQL native lower case" to MYSQL_NATIVE.lowercase(),
        "MySQL caching_sha2" to cachingSha2(),
        "MySQL caching_sha2 hex" to "0x" + cachingSha2().toByteArray(Charsets.ISO_8859_1).joinToString("") { "%02X".format(it) },
        "MySQL caching_sha2 hex without 0x" to cachingSha2().toByteArray(Charsets.ISO_8859_1).joinToString("") { "%02x".format(it) },
        "PostgreSQL SCRAM" to "SCRAM-SHA-256\$4096:XbDONE2rUHwex8XAcVCj/g==\$m4o9i3DHlMt73Zc3YIJcAY999PFGj/rBPmW7wzYhir4=:DYVcajAlzoL7NUSDGvknvdGiecAV7XTTcw4Tt5swDdE=",
        "LDAP SSHA" to "{SSHA}Ur4JjHy51qniWzwEKnuSuppzDys7Zwxh",
        "LDAP SHA" to "{SHA}NF7E0JcVbtegiFHUB+hNXwZ8WHA=",
        "LDAP SHA at test time" to "{SHA}" + base64("SHA-1", "synthetic-bob-pw"),
        "LDAP ssha lower case" to "{ssha}Ur4JjHy51qniWzwEKnuSuppzDys7Zwxh",
        "LDAP MD5" to "{MD5}MLWeYo6rtoYorlHb7UkWfw==",
        "LDAP SMD5" to "{SMD5}9KeoGrjYouPoq+xS5E0e2/8fw3g=",
        "LDAP SSHA256" to "{SSHA256}rX/kwq2KJRidINKG8kGfTKhPDXGpAnKdgD/2Qt9L4mfrXQuhdA7BeA==",
        "LDAP SSHA512" to "{SSHA512}qdZ0VhuSvuXKxX73YfESh8s+RQ4Km9aEEdndHBDeIc6+lyP62sn9jhqh0+9RSUnIE9VjyZ3eE1RLxcSvIxfD9KLU2ntvbW2N",
        "LDAP SHA256 at test time" to "{SHA256}" + base64("SHA-256", "synthetic-bob-pw"),
        "LDAP SHA512 at test time" to "{SHA512}" + base64("SHA-512", "synthetic-bob-pw"),
        "LDAP CRYPT sha512crypt" to "{CRYPT}\$6\$rounds=656000\$/su4bUl7FUfZ/s79\$fNqPG01S0f5AriS5aNXxZ.BDJBPS1KiK5hrx95F8YK/IdF..hKtFhFvfVU.he5Et7LuooanOMqyZVSzcO15QV/",
        "Dovecot SHA512-CRYPT" to "{SHA512-CRYPT}\$6\$.Q8vdRTOqO9Qy5za\$VXGLzqbEg6zt9E84RF.KNT4tvSMLs0rptIlN5DhNWtkq1uwvElOgkG7SigUszg0YU5BhjhSZRcdZYRKX1zLLt0",
        "Dovecot SHA256-CRYPT" to "{SHA256-CRYPT}\$5\$udaiXgeuXSWnU337\$yFYNip.IcqURdAYpRTJMPx5vKHQvTy5ZI61t1JLzc/B",
        "Dovecot MD5-CRYPT" to "{MD5-CRYPT}\$1\$5KljJlVT\$9HKT8Jgjp6SIZQVD/KGEV0",
        "Dovecot BLF-CRYPT" to "{BLF-CRYPT}\$2y\$05\$XWwVSuuL73BT09vorqNl5uLM4lYgDbKr31TL8J3OcBOFB6WVVAKca",
        "Dovecot ARGON2ID" to "{ARGON2ID}$argon2id",
        "Dovecot ARGON2I" to "{ARGON2I}\$argon2i\$v=19\$m=4096,t=2,p=1\$YWxpY2VzYWx0c2FsdA\$CL2H+vuW7DUBR79RPxidDgRUH/F5dzUw0zAYgYo93E4",
    )

    @Test
    fun `every recognised format is a hash, alone and as a file`() {
        for ((name, value) in crypt + other) {
            assertTrue(PasswordHashes.isHash(value), name)
            assertTrue(PasswordHashes.isHashOnly(value), "$name as a file")
            assertTrue(PasswordHashes.isHashOnly("$value\n"), "$name with a newline")
            assertTrue(PasswordHashes.isHashOnly("  $value \r\n"), "$name with blanks and CRLF")
            assertTrue(PasswordHashes.isHashOnly("﻿$value\n"), "$name after a byte order mark")
            assertTrue(PasswordHashes.isHashOnly("$value\n".toByteArray(Charsets.ISO_8859_1)), "$name as bytes")
        }
    }

    @Test
    fun `htpasswd and shadow lines, blank lines and locked accounts`() {
        val htpasswd = "alice:$BCRYPT\nbob:\$apr1\$bobsalt\$zhdmCqqkWoYyn8JBjmLLC0\n\ncarol:{SHA}NF7E0JcVbtegiFHUB+hNXwZ8WHA=\n"
        assertTrue(PasswordHashes.isHashOnly(htpasswd))
        assertTrue(PasswordHashes.isHashOnly("alice:$SHA512_CRYPT:19000:0:99999:7:::\n"), "a shadow line")
        assertTrue(PasswordHashes.isHashOnly("alice:$SHA512_CRYPT:::::::\n"), "empty shadow fields")
        assertTrue(PasswordHashes.isHashOnly("alice@example.test:$MYSQL_NATIVE\n"), "a name with @ and dots")
        assertTrue(PasswordHashes.isHashOnly("alice+web_1-x@example.test:$BCRYPT\n"), "every character of a name")
        assertTrue(PasswordHashes.isHashOnly("alice:SCRAM-SHA-256\$4096:XbDONE2rUHwex8XAcVCj/g==\$m4o9i3DHlMt73Zc3YIJcAY999PFGj/rBPmW7wzYhir4=:DYVcajAlzoL7NUSDGvknvdGiecAV7XTTcw4Tt5swDdE="))
        assertTrue(PasswordHashes.isHashOnly(cachingSha2() + "\n"), "a raw caching_sha2 salt may hold ':' and blanks")
        assertTrue(PasswordHashes.isHashOnly("alice:" + cachingSha2()))
        assertTrue(PasswordHashes.isHashOnly((1..PasswordHashes.MAX_LINES).joinToString("\n") { "user$it:$BCRYPT" }), "64 lines")

        assertTrue(PasswordHashes.isHashOnly("!$SHA512_CRYPT\n"), "a locked account (usermod -L)")
        assertTrue(PasswordHashes.isHashOnly("!!$SHA512_CRYPT\n"), "a locked account (passwd -l)")
        assertTrue(PasswordHashes.isHashOnly("alice:!$SHA512_CRYPT:19000:0:99999:7:::\n"), "a locked shadow line")
        assertTrue(PasswordHashes.isHashOnly("alice:!$BCRYPT\n"), "a locked htpasswd-style line")
        assertFalse(PasswordHashes.isHash("!$SHA512_CRYPT"), "isHash takes the hash alone")
    }

    @Test
    fun `a raw caching_sha2 value may break a line inside its salt`() {
        for (salt in listOf("Mq:7\nx!`R#'{Fz~|0;^w", "Mq:7\rx!`R#'{Fz~|0;^w", "\nq:7 x!`R#'{Fz~|0;^\n")) {
            val value = cachingSha2(salt)
            assertTrue(PasswordHashes.isHashOnly(value), "line break in the salt")
            assertTrue(PasswordHashes.isHashOnly("$value\n"), "line break in the salt, newline at the end")
            assertTrue(PasswordHashes.isHashOnly("$value\n".toByteArray(Charsets.ISO_8859_1)), "as bytes")
        }
        val controls = cachingSha2("Mq\u0001\u0002 x!`R\u001b'{Fz~|0;\u007f\n")
        assertTrue(PasswordHashes.isHashOnly(controls.toByteArray(Charsets.ISO_8859_1)), "control characters, as a binary file holds them")
        assertFalse(PasswordHashes.isHashOnly(cachingSha2("Mq:7\nx!`R#'{Fz~|0;^w") + "\nsynthetic-alice-pw\n"), "and a plaintext line")
    }

    @Test
    fun `bytes are read as single-byte characters and bounded`() {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        assertTrue(PasswordHashes.isHashOnly(bom + "$SHA512_CRYPT\n".toByteArray()), "a UTF-8 byte order mark")
        assertTrue(PasswordHashes.isHashOnly("$MYSQL_NATIVE\r\n".toByteArray()), "CRLF")
        assertFalse(PasswordHashes.isHashOnly("$SHA512_CRYPT\nsynthetic-älice-pw\n".toByteArray(Charsets.UTF_8)), "non-ASCII plaintext")
        assertFalse(PasswordHashes.isHashOnly("synthetic-alice-pw\n".toByteArray()))
        assertFalse(PasswordHashes.isHashOnly(ByteArray(0)))
        val large = "$SHA512_CRYPT\n".toByteArray() + ByteArray(PasswordHashes.MAX_CHARS) { '\n'.code.toByte() }
        assertFalse(PasswordHashes.isHashOnly(large), "more than 16 KiB")
    }

    @Test
    fun `plaintext passwords and other text are no hashes`() {
        val texts = listOf(
            "synthetic-alice-pw",
            "hunter2",
            "correct horse battery staple",
            "alice:synthetic-alice-pw",
            "alice:hunter2:19000:0:99999:7:::",
            "alice smith:$SHA512_CRYPT",
            "synthetic-alice-pw!:$SHA512_CRYPT",
            "synthetic/alice#pw:$SHA512_CRYPT:19000:0:99999:7:::",
            "${"a".repeat(65)}:$SHA512_CRYPT",
            "password: $SHA512_CRYPT",
            "\"$SHA512_CRYPT\"",
            "'$MYSQL_NATIVE'",
            "$SHA512_CRYPT trailing words",
            "$SHA512_CRYPT\nsynthetic-alice-pw\n",
            "alice:$BCRYPT\nbob:synthetic-bob-pw\n",
            "alice:$SHA512_CRYPT:19000:x:99999",
            "!!!$SHA512_CRYPT",
            "!synthetic-alice-pw",
            "!$MYSQL_NATIVE",
            "deadbeef",
            "0123456789abcdef0123456789abcdef0",
            "0123456789abcdef0123456789abcde",
            "c3ludGhldGljLWFsaWNlLXB3", // base64 without a scheme
            "c3ludGhldGljLWFsaWNlLXB3c3ludGhldGljLWFsaWNlLXB3",
            "\$6\$alicesalt\$tooShort",
            SHA512_CRYPT.dropLast(1),
            SHA512_CRYPT + "x",
            "\$2b\$05\$" + "a".repeat(52),
            "\$2b\$99\$" + BCRYPT.substring(7),
            "\$2c\$05\$" + BCRYPT.substring(7),
            "\$bcrypt-sha256\$v=2,t=2b,r=5\$fGS1d5dWDLiCEtV/46UPe.\$short",
            "*" + "A".repeat(39),
            "*" + "G".repeat(40),
            "{SHA}" + Base64.getEncoder().encodeToString(ByteArray(19)),
            "{SSHA}" + Base64.getEncoder().encodeToString(ByteArray(20)), // salted, but no salt
            "{FOO}NF7E0JcVbtegiFHUB+hNXwZ8WHA=",
            "{SHA}not base64!",
            "{CRYPT}hunter2",
            "{SHA512-CRYPT}synthetic-alice-pw",
            "{ARGON2ID}synthetic-alice-pw",
            "SCRAM-SHA-256\$4096:XbDONE2rUHwex8XAcVCj/g==\$short=:short=",
            "\$argon2id\$v=19\$m=4096,t=2,p=1\$c2FsdA\$aGFzaA",
            "\$A\$005\$" + "x".repeat(19),
            "0x244124" + "0".repeat(10),
            "bcrypt_sha256\$synthetic-alice-pw",
            "argon2\$synthetic-alice-pw",
            "---\nalice: $SHA512_CRYPT\n",
            "-----BEGIN OPENSSH ${KeyMaterial.PRIVATE}-----",
        )
        for (text in texts) assertFalse(PasswordHashes.isHashOnly(text), text)
    }

    @Test
    fun `shapes a plaintext secret can have are no hashes`() {
        // A bare hex string of a digest's length: also what `openssl rand -hex 16|20|32|64` writes, and an NT hash.
        for ((algorithm, length) in listOf("MD5" to 32, "SHA-1" to 40, "SHA-256" to 64, "SHA-512" to 128)) {
            val digest = hex(algorithm, "synthetic-alice-pw")
            assertEquals(length, digest.length)
            for (text in listOf(digest, "$digest\n", digest.uppercase(), "alice:$digest\n")) {
                assertFalse(PasswordHashes.isHashOnly(text), "$algorithm-length hex: $text")
            }
            assertFalse(PasswordHashes.isHash(digest), algorithm)
        }
        // PostgreSQL md5: with md5 authentication the stored value logs in without the password.
        for (text in listOf("md51a04bf56c6bb0b12aac309c6dbc3fbc0", "md5" + hex("MD5", "synthetic-bob-pw" + "bob"), "md5" + "a".repeat(31))) {
            assertFalse(PasswordHashes.isHashOnly(text), text)
        }
        // Comments are free text: the plaintext may sit next to its hash.
        for (text in listOf("# pw: synthetic-alice-pw\n$SHA512_CRYPT\n", "# web users\nalice:$BCRYPT\n", "$SHA512_CRYPT\n# synthetic-alice-pw\n")) {
            assertFalse(PasswordHashes.isHashOnly(text), text)
        }
        // Traditional DES crypt: 13 characters of the crypt alphabet, as many short passwords are.
        for (text in listOf("{CRYPT}Q7bHWy7.dgwTY", "{CRYPT}SyntheticPw12", "Q7bHWy7.dgwTY")) {
            assertFalse(PasswordHashes.isHashOnly(text), text)
        }
    }

    @Test
    fun `the text is bounded and needs at least one hash`() {
        for (text in listOf("", "\n\n", "   \n", "﻿", "# only a comment\n", "#$SHA512_CRYPT\n")) assertFalse(PasswordHashes.isHashOnly(text), text)
        val lines = (1..PasswordHashes.MAX_LINES + 1).joinToString("\n") { "user$it:$BCRYPT" }
        assertFalse(PasswordHashes.isHashOnly(lines), "more than 64 lines")
        val padded = SHA512_CRYPT + "\n" + " ".repeat(PasswordHashes.MAX_CHARS)
        assertFalse(PasswordHashes.isHashOnly(padded), "more than 16 KiB")
        val blanks = SHA512_CRYPT + "\n".repeat(PasswordHashes.MAX_LINES)
        assertTrue(PasswordHashes.isHashOnly(blanks), "trailing newlines within the bound")
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private companion object {
        /** `synthetic-alice-pw` with libxcrypt's default sha512crypt salt. */
        const val SHA512_CRYPT = "\$6\$Pa2YhKpEuJJEr44e\$LfzVCkDD/7nrZ9TdxxpG4sMC1v1dhu.wkf4nsBi4SUtm3LtAqwXNn.qMKspg77qMg9PGWrjDHuXVOd6xm4Wj./"

        /** `synthetic-alice-pw`, bcrypt cost 5 (passlib). */
        const val BCRYPT = "\$2b\$05\$.Z2PQ0VP8BM6TZ9mI/EIweFnNlVuWYGSxf4meI9sHKkiyOTIaCRru"

        /** `synthetic-alice-pw`, `mysql_native_password` (passlib mysql41). */
        const val MYSQL_NATIVE = "*7C6E2D875572CE988D4F21654426F15C69097935"

        /**
         * A hand-built `caching_sha2_password` value of the documented shape: `$A$005$`, a 20-character [salt] of 7-bit
         * characters (by default with a blank, `:`, quotes and `#`) and a 43-character crypt-alphabet digest.
         */
        fun cachingSha2(salt: String = "Mq:7 x!`R#'{Fz~|0;^w"): String {
            val digest = "Gv3oQe8LkR2yTnW5bZ1cXuA9sD7fH0jK4mP6rV.iE/1"
            check(salt.length == 20 && digest.length == 43)
            return "\$A\$005\$$salt$digest"
        }

        fun hex(algorithm: String, text: String): String =
            MessageDigest.getInstance(algorithm).digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

        fun base64(algorithm: String, text: String): String =
            Base64.getEncoder().encodeToString(MessageDigest.getInstance(algorithm).digest(text.toByteArray()))
    }
}
