package de.terletzkiy.ansibility.semantics.secrets

import java.util.Base64

/**
 * Password hashes (plan amendment R21, D161 as built after the user report of 2026-10-08): a key-like file
 * ([KeyLikeNames], e.g. `files/mysql/users/alice.password`) whose content is only password hashes holds no plaintext
 * secret, so ANS-V108's "not vaulted" signal, the `ansibility.secrets` index and the Vault tab leave it alone. The commit
 * check does not ask: a file that was a vault and is plaintext now stops a commit whatever it holds (D167). Pure: no
 * I/O, verdicts only.
 *
 * A text is hash-only ([isHashOnly]) when it is small (at most [MAX_CHARS] characters and [MAX_LINES] lines) and every
 * line that is not blank is one hash, either alone, as `name:hash` (htpasswd) or as `name:hash:<numeric shadow fields>`
 * (`/etc/shadow`), where the name is a user name (letters, digits and `._@+-`, at most 64); at least one hash is
 * needed. A crypt hash may carry the `!` or `!!` of a locked account. Recognised ([isHash]):
 * - crypt: MD5 `$1$`, Apache `$apr1$`, SHA-256 `$5$` and SHA-512 `$6$` (also with `rounds=`), bcrypt `$2a$`/`$2b$`/
 *   `$2x$`/`$2y$` (60 characters), yescrypt `$y$`, gost-yescrypt `$gy$`, scrypt `$7$`, sha1crypt `$sha1$`, Sun MD5
 *   `$md5`;
 * - argon2 `$argon2i$`/`$argon2d$`/`$argon2id$`; PBKDF2 (passlib `$pbkdf2$`, `$pbkdf2-sha256$`, `$pbkdf2-sha512$`;
 *   Django `pbkdf2_sha256$`, `pbkdf2_sha1$`); passlib `$bcrypt-sha256$` (both versions); Django `bcrypt_sha256$`,
 *   `bcrypt$` and `argon2$`;
 * - MySQL `mysql_native_password` (`*` and 40 hex digits) and `caching_sha2_password` (`$A$005$`, a 20-character salt
 *   and 43 characters, or its 140-digit hex form as `mysqldump --hex-blob` writes it); the raw salt may hold a line
 *   break, so the whole text is also tried as that one value;
 * - PostgreSQL `SCRAM-SHA-256$<iterations>:<salt>$<stored key>:<server key>`;
 * - LDAP and Dovecot schemes: `{SHA}`, `{SSHA}`, `{SHA256}`, `{SSHA256}`, `{SHA512}`, `{SSHA512}`, `{MD5}`, `{SMD5}`
 *   (base64 of the digest's size, salted ones longer), `{CRYPT}` and `{<name>-CRYPT}` (`{SHA512-CRYPT}`,
 *   `{BLF-CRYPT}` …) with a crypt hash above, `{ARGON2I}`/`{ARGON2ID}` with an argon2 hash.
 *
 * Anything else is no hash. Deliberately among it, because a plaintext secret has the same shape: any bare hex string,
 * also of a digest's length (`openssl rand -hex` and Ansible's `password` lookup with `chars=hexdigits` write plaintext
 * passwords like that, and an NT hash logs in as it is); PostgreSQL `md5` hashes (with md5 authentication the stored
 * hash logs in without the password); `#` comments (free text that may hold the plaintext); traditional DES crypt (13
 * characters any short password can have, also after `{CRYPT}`). Also no hash: plain words, passphrases, base64 without
 * a scheme, YAML (`key: value`), quoted hashes, a hash and a plaintext line together. The checks are anchored shapes
 * (scheme, alphabet and length), never a guess from entropy.
 */
object PasswordHashes {
    /** The longest text that can be hash-only (a few dozen htpasswd or shadow lines). */
    const val MAX_CHARS: Int = 16 * 1024

    /** The most lines a hash-only text has. */
    const val MAX_LINES: Int = 64

    /** A literal `$` in a pattern. */
    private const val D = "\\$"

    /** One character of the crypt alphabet (`./0-9A-Za-z`). */
    private const val C = "[./0-9A-Za-z]"

    /** One character of the standard base64 alphabet. */
    private const val B = "[A-Za-z0-9+/]"

    private const val BYTE_ORDER_MARK = '﻿'

    private val UTF8_BYTE_ORDER_MARK = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())

    private val BCRYPT = Regex("""${D}2[abxy]${D}(?:0[4-9]|[12]\d|3[01])${D}$C{53}""")

    private val CRYPT: List<Regex> = listOf(
        Regex("""${D}1${D}$C{0,8}${D}$C{22}"""),
        Regex("""${D}apr1${D}$C{0,8}${D}$C{22}"""),
        Regex("""${D}5${D}(?:rounds=\d{1,9}${D})?$C{0,16}${D}$C{43}"""),
        Regex("""${D}6${D}(?:rounds=\d{1,9}${D})?$C{0,16}${D}$C{86}"""),
        BCRYPT,
        Regex("""${D}g?y${D}$C{1,64}${D}$C{1,86}${D}$C{43}"""),
        Regex("""${D}7${D}$C{11}$C{0,64}${D}$C{43}"""),
        Regex("""${D}sha1${D}\d{1,10}${D}$C{1,64}${D}$C{28}"""),
        Regex("""${D}md5(?:,rounds=\d{1,10})?${D}$C{0,8}${D}(?:${D})?$C{22}"""),
    )

    private val ARGON2 = Regex("""${D}argon2(?:id|i|d)${D}(?:v=\d{1,3}${D})?m=\d{1,10},t=\d{1,10},p=\d{1,3}${D}$B{11,}={0,2}${D}$B{16,}={0,2}""")

    /**
     * `caching_sha2_password`: `$A$`, the rounds/1000 as three hex digits, 20 salt characters (any 7-bit character but
     * NUL and `$`, as MySQL's `generate_user_salt` makes them) and the 43-character digest.
     */
    private val CACHING_SHA2 = Regex("""${D}A${D}[0-9A-Fa-f]{3}${D}[\x01-\x23\x25-\x7F]{20}$C{43}""")

    private val OTHER: List<Regex> = listOf(
        ARGON2,
        Regex("""${D}pbkdf2${D}\d{1,10}${D}$C{1,128}${D}$C{27}"""),
        Regex("""${D}pbkdf2-sha256${D}\d{1,10}${D}$C{1,128}${D}$C{43}"""),
        Regex("""${D}pbkdf2-sha512${D}\d{1,10}${D}$C{1,128}${D}$C{86}"""),
        Regex("""${D}bcrypt-sha256${D}(?:v=2,t=2[ab],r=|2[ab],)\d{1,2}${D}$C{22}${D}$C{31}"""),
        Regex("""pbkdf2_sha256${D}\d{1,10}${D}[A-Za-z0-9]{1,128}${D}$B{43}="""),
        Regex("""pbkdf2_sha1${D}\d{1,10}${D}[A-Za-z0-9]{1,128}${D}$B{27}="""),
        Regex("""\*[0-9A-Fa-f]{40}"""),
        CACHING_SHA2,
        Regex("""SCRAM-SHA-256${D}\d{1,10}:$B{4,}={0,2}${D}$B{43}=:$B{43}="""),
    )

    /** `caching_sha2_password` in hex (`$A$` is `244124`): 70 bytes, 140 digits. */
    private val CACHING_SHA2_HEX = Regex("""(?:0[xX])?(244124[0-9A-Fa-f]{134})""")

    /** `{SCHEME}value` (LDAP `userPassword`, Dovecot). */
    private val SCHEME = Regex("""\{([A-Za-z0-9-]{2,16})\}(.+)""")

    private val BASE64 = Regex("""$B+={0,2}""")

    /** An htpasswd or shadow user name (also an e-mail address): a password with other characters is no name. */
    private val NAME = Regex("""[A-Za-z0-9._@+-]{1,64}""")

    /** The numeric fields after the hash of a shadow line (`:19000:0:99999:7:::`). */
    private val SHADOW_TAIL = Regex("""(?::\d{0,10}){1,7}""")

    /** The `!` (`usermod -L`) or `!!` (`passwd -l` on some systems) before the crypt hash of a locked account. */
    private const val LOCK = '!'

    /** The digest sizes of the LDAP schemes (the salted `S…` ones hold the digest and a salt). */
    private val LDAP_DIGESTS = mapOf("SHA" to 20, "MD5" to 16, "SHA256" to 32, "SHA512" to 64)

    /** The most salt bytes a salted LDAP scheme carries. */
    private const val MAX_LDAP_SALT = 64

    private val ARGON2_SCHEMES = setOf("ARGON2I", "ARGON2ID")

    /**
     * True when [text] is a small text of password hashes only (see the class comment). Reads at most [MAX_CHARS]
     * characters; a leading byte order mark is skipped.
     */
    fun isHashOnly(text: CharSequence): Boolean {
        if (text.length > MAX_CHARS) return false
        val body = if (text.isNotEmpty() && text[0] == BYTE_ORDER_MARK) text.subSequence(1, text.length) else text
        // A raw caching_sha2 salt may hold a line break: then the whole text is the one value.
        if (CACHING_SHA2.matches(body.trim())) return true
        var lines = 0
        var hashes = 0
        var start = 0
        while (start < body.length) {
            val end = body.indexOf('\n', start).let { if (it < 0) body.length else it }
            if (++lines > MAX_LINES) return false
            val line = body.subSequence(start, end).toString().trim()
            start = end + 1
            if (line.isEmpty()) continue
            if (!isHashLine(line)) return false
            hashes++
        }
        return hashes > 0
    }

    /**
     * [isHashOnly] for the [bytes] of a file the IDE reads as binary (a file-type mapping, or a raw caching_sha2 salt
     * with control characters): a UTF-8 byte order mark is skipped and every other byte is one character, so no
     * non-ASCII byte can be part of a hash. Reads at most [MAX_CHARS] bytes.
     */
    fun isHashOnly(bytes: ByteArray): Boolean {
        val start = if (bytes.size >= UTF8_BYTE_ORDER_MARK.size && UTF8_BYTE_ORDER_MARK.indices.all { bytes[it] == UTF8_BYTE_ORDER_MARK[it] }) UTF8_BYTE_ORDER_MARK.size else 0
        if (bytes.size - start > MAX_CHARS) return false
        return isHashOnly(String(bytes, start, bytes.size - start, Charsets.ISO_8859_1))
    }

    /** A hash alone, as `name:hash` or as a shadow line `name:hash:<numeric fields>`. */
    private fun isHashLine(line: String): Boolean {
        if (isStoredHash(line)) return true
        val colon = line.indexOf(':')
        if (colon <= 0 || !NAME.matches(line.substring(0, colon))) return false
        val rest = line.substring(colon + 1)
        if (isStoredHash(rest)) return true
        val tail = rest.indexOf(':')
        return tail > 0 && SHADOW_TAIL.matches(rest.substring(tail)) && isStoredHash(rest.substring(0, tail))
    }

    /** [isHash], or a crypt hash behind the `!` or `!!` of a locked account. */
    private fun isStoredHash(value: String): Boolean {
        if (isHash(value)) return true
        val unlocked = value.trimStart(LOCK)
        return value.length - unlocked.length in 1..2 && CRYPT.any { it.matches(unlocked) }
    }

    /** True when [value] is exactly one password hash of a recognised format (no name, no lock mark). */
    fun isHash(value: String): Boolean {
        if (value.isEmpty()) return false
        if (CRYPT.any { it.matches(value) } || OTHER.any { it.matches(value) }) return true
        when {
            value.startsWith("bcrypt_sha256$") -> return BCRYPT.matches(value.removePrefix("bcrypt_sha256$"))
            value.startsWith("bcrypt$") -> return BCRYPT.matches(value.removePrefix("bcrypt$"))
            value.startsWith("argon2$") -> return ARGON2.matches(value.removePrefix("argon2"))
            value.startsWith("{") -> return isScheme(value)
        }
        val hex = CACHING_SHA2_HEX.matchEntire(value)?.groupValues?.get(1) ?: return false
        val bytes = ByteArray(hex.length / 2) { hex.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
        return CACHING_SHA2.matches(String(bytes, Charsets.ISO_8859_1))
    }

    /**
     * `{SCHEME}value`: a crypt hash after `{CRYPT}` or `{<name>-CRYPT}`, an argon2 hash after `{ARGON2I}`/`{ARGON2ID}`,
     * else base64 of the scheme's digest size (more for a salted one).
     */
    private fun isScheme(value: String): Boolean {
        val match = SCHEME.matchEntire(value) ?: return false
        val scheme = match.groupValues[1].uppercase()
        val payload = match.groupValues[2]
        if (scheme == "CRYPT" || scheme.endsWith("-CRYPT")) return CRYPT.any { it.matches(payload) }
        if (scheme in ARGON2_SCHEMES) return ARGON2.matches(payload)
        val salted = scheme.startsWith("S") && scheme.drop(1) in LDAP_DIGESTS
        val digest = LDAP_DIGESTS[if (salted) scheme.drop(1) else scheme] ?: return false
        if (!BASE64.matches(payload)) return false
        val size = try {
            Base64.getDecoder().decode(payload).size
        } catch (_: IllegalArgumentException) {
            return false
        }
        return if (salted) size in digest + 1..digest + MAX_LDAP_SALT else size == digest
    }
}
