package de.terletzkiy.ansibility.semantics.secrets

import java.util.Base64

/** The format of a private key [PrivateKeySignatures] recognised. User-facing names come from the plugin's bundles. */
enum class KeyFormat {
    /** PEM `RSA PRIVATE KEY` (PKCS#1). */
    RSA,

    /** PEM `EC PRIVATE KEY` (SEC1). */
    EC,

    /** PEM `DSA PRIVATE KEY`. */
    DSA,

    /** PEM `PRIVATE KEY` or `ENCRYPTED PRIVATE KEY` (PKCS#8). */
    PKCS8,

    /** PEM `OPENSSH PRIVATE KEY` (`openssh-key-v1`). */
    OPENSSH,

    /** A PuTTY key file (`PuTTY-User-Key-File-2` or `-3`). */
    PUTTY,

    /** An OpenPGP armored `PGP PRIVATE KEY BLOCK`. */
    PGP,

    /** `---- BEGIN SSH2 ENCRYPTED PRIVATE KEY ----` (the ssh.com format). */
    SSH2,

    /** Any other `-----BEGIN … PRIVATE KEY-----` block. */
    PEM,

    /** A PKCS#12 keystore (`.p12`, `.pfx`). */
    PKCS12,

    /** A Java keystore (JKS or JCEKS) with a key entry. */
    JAVA_KEYSTORE,

    /** A DER-encoded private key: PKCS#8 (also encrypted), PKCS#1, SEC1 or DSA. */
    DER,
}

/** How a recognised key is protected. */
enum class KeyProtection {
    /** In plaintext: whoever has the file has the key. */
    NONE,

    /** Encrypted with a passphrase or a keystore password (still attackable offline). */
    PASSPHRASE,

    /** A keystore whose entries cannot be read without its password: it may hold a key. */
    UNKNOWN,
}

/**
 * One private key found by [PrivateKeySignatures]: its format, its protection and where its marker is. Never any of
 * the key's content: the range is the marker line's (`-----BEGIN RSA PRIVATE KEY-----`, `PuTTY-User-Key-File-2:`).
 */
data class KeyHit(
    val format: KeyFormat,
    val protection: KeyProtection,
    /** The 0-based line of the marker; -1 for binary formats. */
    val line: Int,
    /** The marker's offset in the text; 0 for binary formats. */
    val offset: Int,
    /** The marker's length; 0 for binary formats. */
    val length: Int,
    /** The key is written with `\n` escapes inside a string (a JSON value, a double-quoted YAML value). */
    val escaped: Boolean = false,
    /**
     * The key is base64-encoded text (a Kubernetes Secret's `data` value, `LS0tLS1CRUdJTi…`): [offset] and [length]
     * cover the encoding of its BEGIN marker only.
     */
    val encoded: Boolean = false,
)

/**
 * Private-key signatures (plan amendment R21, D160/D161: ANS-V108, the `ansibility.secrets` index and the commit check).
 *
 * Text ([scanText]): complete PEM blocks of private keys (PKCS#1, SEC1, DSA, PKCS#8, encrypted PKCS#8, `Proc-Type:
 * 4,ENCRYPTED`), OpenSSH keys (cipher `none` or not, read from the `openssh-key-v1` header), PuTTY v2/v3 keys
 * (`Encryption`), OpenPGP private key blocks (the secret-key packet's S2K usage) and ssh.com SSH2 keys, also inside
 * `\n`-escaped strings, flattened onto one line, or base64-encoded as a whole (a Kubernetes Secret's `data` value); a
 * block needs its BEGIN marker, at least [MIN_BODY] base64 characters and its END marker (or the end of a cut window),
 * and the body of an unencrypted PEM key must be one DER SEQUENCE whose declared length is the decoded body's, so
 * truncated examples and placeholders never match, nor do certificates, public keys, CSRs and parameters. The scan
 * goes on past the first block (a certificate chain followed by its key, EC parameters before an EC key).
 *
 * Binary ([scanBinary], only for keystore-like names): PKCS#12 (`keyBag` = plaintext, `pkcs8ShroudedKeyBag` =
 * protected; no visible key bag in a file read completely = a truststore, nothing, because keytool and OpenSSL keep
 * key bags in plain safes and encrypt only the certificates; cut before a key bag shows = "may hold a key"), JKS/JCEKS
 * (key entries vs. a truststore) and DER keys (PKCS#8, encrypted PKCS#8, PKCS#1, SEC1, DSA).
 *
 * Reads at most [TEXT_WINDOW] characters or [BINARY_WINDOW] bytes, keeps no content (decoded header bytes live only
 * inside one call) and returns verdicts only: format, protection, line and the marker's range. Pure: no I/O.
 */
object PrivateKeySignatures {
    /** The characters of a text that are scanned (the first 64 KiB). */
    const val TEXT_WINDOW: Int = 64 * 1024

    /** The bytes of a binary file that are scanned (a keystore is parsed up to 1 MiB). */
    const val BINARY_WINDOW: Int = 1024 * 1024

    /**
     * The fewest base64 characters a PEM body needs to count as a key: the shortest real keys are a SEC1 P-256 key
     * without parameters (52) and a PKCS#8 Ed25519 key (64); a truncated `MIIE…` example has fewer.
     */
    const val MIN_BODY: Int = 48

    private const val PKCS12_WINDOW = 64 * 1024
    private const val DER_WINDOW = 64 * 1024

    /** Base64 characters decoded from the start of a body to read a format header (OpenSSH, SSH2, OpenPGP). */
    private const val DECODE_CHARS = 2048

    /** A PuTTY file's header lines before `Private-Lines` (public lines included). */
    private const val PUTTY_SCAN_LINES = 400

    private const val PRIVATE_KEY = "PRIVATE KEY"
    private const val PUTTY_MARKER = "PuTTY-User-Key-File-"
    private const val SSH2_BEGIN = "---- BEGIN SSH2 ENCRYPTED PRIVATE KEY ----"
    private const val SSH2_END = "---- END SSH2 ENCRYPTED PRIVATE KEY ----"
    private const val PGP_LABEL = "PGP PRIVATE KEY BLOCK"

    /** `-----BEGIN` base64-encoded at the start of a token: a whole PEM file encoded (a Kubernetes Secret's value). */
    private const val ENCODED_BEGIN = "LS0tLS1CRUdJTi"

    /** The base64 characters of one encoded token that are decoded (a 4096-bit RSA key file needs about 4.5 KiB). */
    private const val ENCODED_CHARS = 16 * 1024

    private val BEGIN = Regex("""-----BEGIN ([A-Z0-9]+(?: [A-Z0-9]+)*)-----""")
    private val PUTTY_BEGIN = Regex("""PuTTY-User-Key-File-\d+:""")
    private val HEADER = Regex("""[A-Za-z][A-Za-z0-9-]*:.*""")

    private val KEYSTORE_EXTENSIONS = setOf("p12", "pfx", "jks", "keystore", "truststore", "jceks", "bks", "bcfks", "der", "key", "p8", "pk8")

    /** True when a text may hold a key (a cheap test before [scanText]; within [TEXT_WINDOW]). */
    fun mayHoldKey(text: CharSequence): Boolean {
        val window = window(text)
        return window.contains(PRIVATE_KEY) || window.contains(PUTTY_MARKER) || window.contains(ENCODED_BEGIN)
    }

    /**
     * D161's one rule for a key's severity: a weaker signal (at most WARNING) is a passphrase-protected key or keystore,
     * a keystore whose keys cannot be read, and every Java keystore; a key in plaintext (also an unprotected PKCS#12 key
     * bag or an unencrypted DER key) is ERROR.
     */
    fun isWeakSignal(format: KeyFormat, protection: KeyProtection): Boolean =
        protection != KeyProtection.NONE || format == KeyFormat.JAVA_KEYSTORE

    /** True for names whose binary content [scanBinary] reads: keystores, DER keys, `*.key` and extensionless `id_*`. */
    fun isKeystoreName(fileName: String): Boolean {
        val lower = fileName.lowercase()
        val extension = lower.substringAfterLast('.', "")
        return extension in KEYSTORE_EXTENSIONS || (extension.isEmpty() && lower.startsWith("id_"))
    }

    // ------------------------------------------------------------------------------------------------ text

    /**
     * Every private key in [text], in order. With [complete] false (or a text longer than [TEXT_WINDOW]) the text is a
     * head of a longer file: a block that runs to the end of the window counts when its body is long enough.
     */
    fun scanText(text: CharSequence, complete: Boolean = true): List<KeyHit> {
        if (!mayHoldKey(text)) return emptyList()
        val window = window(text)
        val cut = !complete || text.length > TEXT_WINDOW
        val hits = scanPlain(window, cut)
        if (!window.contains(ENCODED_BEGIN)) return hits
        hits += scanEncoded(window, cut)
        return hits.sortedBy { it.offset }
    }

    /** The keys written as text in [window] (not base64-encoded as a whole). */
    private fun scanPlain(window: CharSequence, cut: Boolean): MutableList<KeyHit> {
        if (!window.contains(PRIVATE_KEY) && !window.contains(PUTTY_MARKER)) return ArrayList()
        val pieces = piecesOf(window)
        val hits = ArrayList<KeyHit>()
        var i = 0
        while (i < pieces.size) {
            val piece = pieces[i]
            val pem = BEGIN.find(piece.text)?.takeIf { isPrivateLabel(it.groupValues[1]) }
            val next = when {
                pem != null && isFlattened(piece, pem) -> {
                    flattenedBlock(piece, pem, hits)
                    i + 1
                }
                pem != null -> pemBlock(pieces, i, pem.groupValues[1], pem.range.first, pem.value.length, cut, hits)
                piece.text.contains(SSH2_BEGIN) -> pemBlock(pieces, i, null, piece.text.indexOf(SSH2_BEGIN), SSH2_BEGIN.length, cut, hits)
                else -> PUTTY_BEGIN.find(piece.text)?.takeIf { isPuttyStart(piece, it.range.first) }
                    ?.let { puttyBlock(pieces, i, it.range.first, it.value.length, cut, hits) }
                    ?: (i + 1)
            }
            i = maxOf(next, i + 1)
        }
        return hits
    }

    /**
     * A PuTTY file starts its line, or (inside a `\n`-escaped string) follows the string's opening quote, as in
     * `"key": "PuTTY-User-Key-File-3: …`; prose that names the marker never counts.
     */
    private fun isPuttyStart(piece: Piece, at: Int): Boolean {
        val prefix = piece.text.substring(0, at)
        return prefix.isBlank() || (piece.escaped && prefix.trimEnd().let { it.endsWith('"') || it.endsWith('\'') })
    }

    /** A PEM block written on one line: its BEGIN marker, the body (blanks between the parts) and its END marker. */
    private fun isFlattened(piece: Piece, begin: MatchResult): Boolean =
        piece.text.indexOf("-----END ${begin.groupValues[1]}-----", begin.range.last + 1) >= 0

    /** [pemBlock] on a block flattened onto [piece]: the parts between the markers are its body lines. */
    private fun flattenedBlock(piece: Piece, begin: MatchResult, hits: MutableList<KeyHit>) {
        val label = begin.groupValues[1]
        val end = "-----END $label-----"
        val text = piece.text
        val inner = text.substring(begin.range.last + 1, text.indexOf(end, begin.range.last + 1)).let { if (piece.escaped) it.replace("\\/", "/") else it }
        val lines = ArrayList<Piece>()
        lines += piece
        inner.split(' ', '\t').filter { it.isNotEmpty() }.forEach { lines += Piece(it, piece.start, piece.line, escaped = false) }
        lines += Piece(end, piece.start, piece.line, escaped = false)
        pemBlock(lines, 0, label, begin.range.first, begin.value.length, cut = false, hits = hits)
    }

    /**
     * The keys of whole PEM files encoded as one base64 token in [window] (`LS0tLS1CRUdJTi…`, a Kubernetes Secret's
     * `data` value): each token's first [ENCODED_CHARS] characters are decoded and scanned as text; a hit is reported at
     * the token with the length of the encoded BEGIN marker. The decoded text lives only inside this call.
     */
    private fun scanEncoded(window: CharSequence, cut: Boolean): List<KeyHit> {
        val hits = ArrayList<KeyHit>()
        var from = 0
        while (true) {
            val at = indexOf(window, ENCODED_BEGIN, from)
            if (at < 0) return hits
            var end = at
            while (end < window.length && isBase64Char(window[end])) end++
            from = maxOf(end, at + 1)
            if (at > 0 && isBase64Char(window[at - 1])) continue
            val length = end - at
            val decoded = decodePrefix(window.subSequence(at, at + minOf(length, ENCODED_CHARS))) ?: continue
            val tokenCut = length > ENCODED_CHARS || (cut && end == window.length)
            val inner = scanPlain(String(decoded, Charsets.ISO_8859_1), tokenCut)
            if (inner.isEmpty()) continue
            val line = lineAt(window, at)
            for (hit in inner) hits += KeyHit(hit.format, hit.protection, line, at, ENCODED_BEGIN.length, encoded = true)
        }
    }

    private fun isBase64Char(c: Char): Boolean = c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '+' || c == '/' || c == '='

    private fun indexOf(text: CharSequence, what: String, from: Int): Int {
        var i = from
        while (i <= text.length - what.length) {
            if (text[i] == what[0] && (1 until what.length).all { text[i + it] == what[it] }) return i
            i++
        }
        return -1
    }

    /** The 0-based line of [offset] in [text], with the line breaks [piecesOf] counts (LF, CR, CRLF). */
    private fun lineAt(text: CharSequence, offset: Int): Int {
        var line = 0
        var i = 0
        while (i < offset) {
            val c = text[i]
            if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < offset && text[i + 1] == '\n') i++
                line++
            }
            i++
        }
        return line
    }

    private fun window(text: CharSequence): CharSequence = if (text.length > TEXT_WINDOW) text.subSequence(0, TEXT_WINDOW) else text

    private fun isPrivateLabel(label: String): Boolean = label.endsWith(PRIVATE_KEY) || label == PGP_LABEL

    /**
     * One logical line: a real line, or the part of it between `\n` escapes (a key inside a JSON or double-quoted YAML
     * string). [start] is its offset in the text, [line] the 0-based real line.
     */
    private class Piece(val text: String, val start: Int, val line: Int, val escaped: Boolean) {
        /** The text as a line of a key body: JSON's `\/` escape undone inside an escaped string. */
        val body: String get() = if (escaped) text.replace("\\/", "/") else text
    }

    private fun piecesOf(text: CharSequence): List<Piece> {
        val pieces = ArrayList<Piece>()
        var line = 0
        var start = 0
        var i = 0
        while (i <= text.length) {
            val atEnd = i == text.length
            val c = if (atEnd) '\n' else text[i]
            if (c == '\n' || c == '\r') {
                if (!atEnd || start < text.length) splitEscapes(text, start, i, line, pieces)
                if (!atEnd && c == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                start = i + 1
                line++
            }
            i++
        }
        return pieces
    }

    private fun splitEscapes(text: CharSequence, from: Int, to: Int, line: Int, pieces: MutableList<Piece>) {
        var escape = indexOfEscape(text, from, to)
        if (escape < 0) {
            pieces += Piece(text.substring(from, to), from, line, escaped = false)
            return
        }
        var start = from
        while (true) {
            val end = if (escape < 0) to else escape
            var pieceEnd = end
            if (pieceEnd - start >= 2 && text[pieceEnd - 2] == '\\' && text[pieceEnd - 1] == 'r') pieceEnd -= 2
            pieces += Piece(text.substring(start, pieceEnd), start, line, escaped = true)
            if (escape < 0) return
            start = escape + 2
            escape = indexOfEscape(text, start, to)
        }
    }

    /** The offset of the next `\n` escape (a backslash and `n`) in [from, to), or -1. */
    private fun indexOfEscape(text: CharSequence, from: Int, to: Int): Int {
        for (i in from until to - 1) if (text[i] == '\\' && text[i + 1] == 'n') return i
        return -1
    }

    /**
     * A PEM block whose BEGIN marker is in [pieces] at [index] ([label] null: an SSH2 block): adds its hit, if it is a
     * complete private key, and returns the index to go on from.
     */
    private fun pemBlock(pieces: List<Piece>, index: Int, label: String?, markerAt: Int, markerLength: Int, cut: Boolean, hits: MutableList<KeyHit>): Int {
        val end = if (label == null) SSH2_END else "-----END $label-----"
        var procEncrypted = false
        var inHeaders = true
        var continuation = false
        var bodyChars = 0
        var padding = 0
        val decode = StringBuilder()
        var complete = false
        var j = index + 1
        while (j < pieces.size) {
            val content = pieces[j].body.trim()
            if (content.contains(end)) {
                complete = true
                break
            }
            if (content.contains("-----BEGIN ") || content.contains(SSH2_BEGIN)) return j
            when {
                content.isEmpty() -> Unit
                continuation || (inHeaders && HEADER.matches(content)) -> {
                    if (content.startsWith("Proc-Type:") && content.contains("ENCRYPTED")) procEncrypted = true
                    continuation = content.endsWith("\\")
                }
                isBase64(content) -> {
                    inHeaders = false
                    // An OpenPGP armor checksum (`=XXXX`) closes the body; it is no key material.
                    if (!(label == PGP_LABEL && content.startsWith("="))) {
                        bodyChars += content.length
                        padding = content.length - content.trimEnd('=').length
                        if (decode.length < DECODE_CHARS) decode.append(content, 0, minOf(content.length, DECODE_CHARS - decode.length))
                    }
                }
                else -> return j
            }
            j++
        }
        if (!complete && !(cut && j >= pieces.size)) return j
        if (bodyChars < MIN_BODY) return j + 1
        val head = decodePrefix(decode)
        // An unencrypted PEM key is DER: a SEQUENCE. Placeholders (`AAAA…`, `XXXX…`) decode to anything else; a complete
        // block's SEQUENCE spans exactly its decoded body, so a truncated or padded-out `MIIE…` example does not count.
        val der = label != null && label != "OPENSSH PRIVATE KEY" && label != PGP_LABEL && !procEncrypted
        if (der && (head == null || head.isEmpty() || head[0] != 0x30.toByte())) return j + 1
        if (der && complete && !spansBody(head!!, bodyChars, padding)) return j + 1
        val verdict: Pair<KeyFormat, KeyProtection> = when (label) {
            null -> KeyFormat.SSH2 to ssh2Protection(head)
            "PRIVATE KEY" -> KeyFormat.PKCS8 to KeyProtection.NONE
            "ENCRYPTED PRIVATE KEY" -> KeyFormat.PKCS8 to KeyProtection.PASSPHRASE
            "RSA PRIVATE KEY" -> KeyFormat.RSA to procType(procEncrypted)
            "EC PRIVATE KEY" -> KeyFormat.EC to procType(procEncrypted)
            "DSA PRIVATE KEY" -> KeyFormat.DSA to procType(procEncrypted)
            "OPENSSH PRIVATE KEY" -> KeyFormat.OPENSSH to (opensshProtection(head) ?: return j + 1)
            PGP_LABEL -> KeyFormat.PGP to pgpProtection(head)
            else -> KeyFormat.PEM to procType(procEncrypted)
        }
        val piece = pieces[index]
        hits += KeyHit(verdict.first, verdict.second, piece.line, piece.start + markerAt, markerLength, piece.escaped)
        return j + 1
    }

    private fun procType(encrypted: Boolean): KeyProtection = if (encrypted) KeyProtection.PASSPHRASE else KeyProtection.NONE

    /**
     * True when the DER element at the start of [head] (a SEQUENCE with a definite length) ends exactly where a body of
     * [bodyChars] base64 characters with [padding] `=` ends.
     */
    private fun spansBody(head: ByteArray, bodyChars: Int, padding: Int): Boolean {
        if (bodyChars % 4 != 0 || head.size < 2) return false
        val decoded = bodyChars / 4 * 3 - padding
        val first = head[1].toInt() and 0xFF
        if (first < 0x80) return 2 + first == decoded
        val octets = first - 0x80
        if (octets !in 1..4 || head.size < 2 + octets) return false
        var length = 0L
        for (k in 0 until octets) length = (length shl 8) or (head[2 + k].toLong() and 0xFF)
        return 2 + octets + length == decoded.toLong()
    }

    /** A PuTTY key whose first line is in [pieces] at [index]: needs `Encryption` and a non-empty `Private-Lines` part. */
    private fun puttyBlock(pieces: List<Piece>, index: Int, markerAt: Int, markerLength: Int, cut: Boolean, hits: MutableList<KeyHit>): Int {
        var encryption: String? = null
        var j = index + 1
        while (j < pieces.size && j - index <= PUTTY_SCAN_LINES) {
            val content = pieces[j].body.trim()
            if (content.startsWith(PUTTY_MARKER) || content.contains("-----BEGIN ")) return j
            when {
                content.startsWith("Encryption:") -> encryption = content.substringAfter(':').trim()
                content.startsWith("Private-Lines:") -> {
                    val count = content.substringAfter(':').trim().toIntOrNull() ?: return j + 1
                    var chars = 0
                    var k = j + 1
                    while (k < pieces.size && k <= j + count) {
                        val line = pieces[k].body.trim()
                        if (!isBase64(line)) break
                        chars += line.length
                        k++
                    }
                    val enough = count >= 1 && chars >= 16 && (k == j + count + 1 || (cut && k >= pieces.size))
                    if (!enough || encryption == null) return k
                    val protection = if (encryption == "none") KeyProtection.NONE else KeyProtection.PASSPHRASE
                    val piece = pieces[index]
                    hits += KeyHit(KeyFormat.PUTTY, protection, piece.line, piece.start + markerAt, markerLength, piece.escaped)
                    return k
                }
            }
            j++
        }
        return j
    }

    private fun isBase64(content: CharSequence): Boolean =
        content.isNotEmpty() && content.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '+' || it == '/' || it == '=' }

    /** The bytes of the first whole base64 quanta of [chars], or null when they do not decode. */
    private fun decodePrefix(chars: CharSequence): ByteArray? {
        val usable = chars.length - chars.length % 4
        if (usable == 0) return null
        return try {
            Base64.getDecoder().decode(chars.subSequence(0, usable).toString())
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    /** `openssh-key-v1\0`, then the cipher name: `none` is a plaintext key; null when the magic is missing (no key). */
    private fun opensshProtection(head: ByteArray?): KeyProtection? {
        head ?: return null
        val magic = "openssh-key-v1\u0000".toByteArray(Charsets.US_ASCII)
        if (head.size < magic.size || magic.indices.any { head[it] != magic[it] }) return null
        val cipher = Bytes(head, magic.size).string() ?: return KeyProtection.NONE
        return if (cipher == "none") KeyProtection.NONE else KeyProtection.PASSPHRASE
    }

    /** ssh.com's format: magic `3f6ff9eb`, total length, key type, cipher; `none` is plaintext. The label says encrypted. */
    private fun ssh2Protection(head: ByteArray?): KeyProtection {
        val bytes = Bytes(head ?: return KeyProtection.PASSPHRASE, 0)
        if (bytes.u32() != 0x3f6ff9ebL) return KeyProtection.PASSPHRASE
        bytes.u32() ?: return KeyProtection.PASSPHRASE
        bytes.string() ?: return KeyProtection.PASSPHRASE
        return if (bytes.string() == "none") KeyProtection.NONE else KeyProtection.PASSPHRASE
    }

    /**
     * The S2K usage octet of the first OpenPGP packet, a secret key (tag 5): 0 is a plaintext key. A packet this
     * reader does not understand counts as plaintext (an armored private key block is reported either way).
     */
    private fun pgpProtection(head: ByteArray?): KeyProtection {
        val bytes = Bytes(head ?: return KeyProtection.NONE, 0)
        val first = bytes.u8() ?: return KeyProtection.NONE
        if (first and 0x80 == 0) return KeyProtection.NONE
        val tag = if (first and 0x40 != 0) {
            val length = bytes.u8() ?: return KeyProtection.NONE
            when {
                length < 192 -> Unit
                length < 224 -> bytes.skip(1)
                length == 255 -> bytes.skip(4)
                else -> Unit
            }
            first and 0x3F
        } else {
            when (first and 3) {
                0 -> bytes.skip(1)
                1 -> bytes.skip(2)
                2 -> bytes.skip(4)
                else -> Unit
            }
            (first shr 2) and 0x0F
        }
        if (tag != 5) return KeyProtection.NONE
        val version = bytes.u8() ?: return KeyProtection.NONE
        bytes.skip(4)
        if (version == 3) bytes.skip(2)
        val algorithm = bytes.u8() ?: return KeyProtection.NONE
        val skipped = if (version == 5 || version == 6) {
            val count = bytes.u32() ?: return KeyProtection.NONE
            bytes.skip(count.toInt())
        } else {
            skipPublicKey(bytes, algorithm)
        }
        if (!skipped) return KeyProtection.NONE
        val usage = bytes.u8() ?: return KeyProtection.NONE
        return if (usage == 0) KeyProtection.NONE else KeyProtection.PASSPHRASE
    }

    /** Skips the public key material of a v3/v4 key of [algorithm]; false when it is unknown or cut. */
    private fun skipPublicKey(bytes: Bytes, algorithm: Int): Boolean = when (algorithm) {
        1, 2, 3 -> bytes.mpis(2)
        16, 20 -> bytes.mpis(3)
        17 -> bytes.mpis(4)
        18 -> bytes.oid() && bytes.mpis(1) && bytes.lengthPrefixed()
        19, 22 -> bytes.oid() && bytes.mpis(1)
        25 -> bytes.skip(32)
        26 -> bytes.skip(56)
        27 -> bytes.skip(32)
        28 -> bytes.skip(57)
        else -> false
    }

    /** A cursor over decoded header bytes; every read returns null (or false) past the end. */
    private class Bytes(private val data: ByteArray, private var pos: Int) {
        fun u8(): Int? = if (pos < data.size) data[pos++].toInt() and 0xFF else null

        fun u32(): Long? {
            if (pos + 4 > data.size) return null
            var value = 0L
            repeat(4) { value = (value shl 8) or (data[pos++].toLong() and 0xFF) }
            return value
        }

        fun skip(count: Int): Boolean {
            if (count < 0 || pos + count > data.size) {
                pos = data.size
                return false
            }
            pos += count
            return true
        }

        /** An SSH string (a 32-bit length, then the bytes) as ASCII, or null. */
        fun string(): String? {
            val length = u32() ?: return null
            if (length > data.size - pos) return null
            val value = String(data, pos, length.toInt(), Charsets.ISO_8859_1)
            pos += length.toInt()
            return value
        }

        /** [count] OpenPGP multiprecision integers (a 16-bit bit count, then the bytes). */
        fun mpis(count: Int): Boolean {
            repeat(count) {
                val high = u8() ?: return false
                val low = u8() ?: return false
                if (!skip(((high shl 8) or low) + 7 shr 3)) return false
            }
            return true
        }

        /** An OpenPGP curve OID: one length octet, then the bytes. */
        fun oid(): Boolean = lengthPrefixed()

        fun lengthPrefixed(): Boolean {
            val length = u8() ?: return false
            return skip(length)
        }
    }

    // ------------------------------------------------------------------------------------------------ binary

    /**
     * The private key in a binary file's [bytes] (the first [BINARY_WINDOW] are read), for a keystore-like
     * [fileName] ([isKeystoreName]) only; null for anything else, for a truststore without keys and for a whole-file
     * vault. With [complete] false [bytes] is a head of a longer file. PEM text in a `.key` file is [scanText]'s.
     */
    fun scanBinary(bytes: ByteArray, fileName: String, complete: Boolean = true): KeyHit? {
        if (!isKeystoreName(fileName) || bytes.size < 4) return null
        if (startsWith(bytes, "\$ANSIBLE_VAULT".toByteArray(Charsets.US_ASCII))) return null
        val whole = complete && bytes.size <= BINARY_WINDOW
        return pkcs12(bytes, whole) ?: javaKeystore(bytes, whole) ?: der(bytes)
    }

    private fun binaryHit(format: KeyFormat, protection: KeyProtection) = KeyHit(format, protection, -1, 0, 0)

    /** DER of the OIDs (tag 06, length, content) the PKCS#12 scan looks for. */
    private val KEY_BAG = oid("2A864886F70D010C0A0101")
    private val SHROUDED_KEY_BAG = oid("2A864886F70D010C0A0102")

    /** A PKCS#12 PFX: `SEQUENCE { INTEGER 3, SEQUENCE …`. */
    private fun pkcs12(bytes: ByteArray, whole: Boolean): KeyHit? {
        if (bytes[0] != 0x30.toByte()) return null
        val length = bytes[1].toInt() and 0xFF
        val at = 2 + when {
            length <= 0x80 -> 0
            length <= 0x84 -> length - 0x80
            else -> return null
        }
        val version = byteArrayOf(0x02, 0x01, 0x03, 0x30)
        if (at + version.size > bytes.size || version.indices.any { bytes[at + it] != version[it] }) return null
        val window = minOf(bytes.size, PKCS12_WINDOW)
        return when {
            indexOf(bytes, KEY_BAG, window) >= 0 -> binaryHit(KeyFormat.PKCS12, KeyProtection.NONE)
            indexOf(bytes, SHROUDED_KEY_BAG, window) >= 0 -> binaryHit(KeyFormat.PKCS12, KeyProtection.PASSPHRASE)
            // Read completely and no key bag in sight: a truststore. keytool (PKCS#12 is its default store type) and
            // OpenSSL encrypt the certificate bags (EncryptedData) but keep every key bag in a plain safe.
            whole && bytes.size <= PKCS12_WINDOW -> null
            else -> binaryHit(KeyFormat.PKCS12, KeyProtection.UNKNOWN)
        }
    }

    /**
     * JKS (`FEEDFEED`) or JCEKS (`CECECECE`): the entries up to the first key entry (tag 1 private key, 3 secret key:
     * always password-protected). Only trusted certificates: a truststore, nothing. Cut before the end: "may hold a key".
     */
    private fun javaKeystore(bytes: ByteArray, whole: Boolean): KeyHit? {
        val data = Bytes(if (bytes.size > BINARY_WINDOW) bytes.copyOf(BINARY_WINDOW) else bytes, 0)
        val magic = data.u32()
        if (magic != 0xFEEDFEEDL && magic != 0xCECECECEL) return null
        val version = data.u32() ?: return null
        if (version != 1L && version != 2L) return null
        val count = data.u32() ?: return null
        var entries = 0L
        while (entries < count) {
            val tag = data.u32() ?: break
            val alias = data.u16() ?: break
            if (!data.skip(alias) || !data.skip(8)) break
            when (tag) {
                1L, 3L -> return binaryHit(KeyFormat.JAVA_KEYSTORE, KeyProtection.PASSPHRASE)
                2L -> {
                    if (version == 2L) {
                        val type = data.u16() ?: break
                        if (!data.skip(type)) break
                    }
                    val length = data.u32() ?: break
                    if (length > Int.MAX_VALUE || !data.skip(length.toInt())) break
                }
                else -> return null
            }
            entries++
        }
        if (entries == count) return null
        return if (whole) null else binaryHit(KeyFormat.JAVA_KEYSTORE, KeyProtection.UNKNOWN)
    }

    private fun Bytes.u16(): Int? {
        val high = u8() ?: return null
        val low = u8() ?: return null
        return (high shl 8) or low
    }

    private val KEY_ALGORITHMS: List<ByteArray> = listOf(
        "2A864886F70D010101", // rsaEncryption
        "2A864886F70D01010A", // RSASSA-PSS
        "2A8648CE3D0201", // ecPublicKey
        "2A8648CE380401", // dsa
        "2A864886F70D010301", // dhKeyAgreement
        "2B656E", "2B656F", "2B6570", "2B6571", // X25519, X448, Ed25519, Ed448
    ).map(::hex)

    /** PBES1 / PBES2 (1.2.840.113549.1.5.x) and PKCS#12 PBE (1.2.840.113549.1.12.1.x) prefixes. */
    private val PBE_PREFIXES: List<ByteArray> = listOf("2A864886F70D0105", "2A864886F70D010C01").map(::hex)

    /** An unencrypted or encrypted DER private key; a certificate, CSR or CRL never matches. */
    private fun der(bytes: ByteArray): KeyHit? {
        val limit = minOf(bytes.size, DER_WINDOW)
        val top = Der(bytes, 0, limit).next() ?: return null
        if (top.tag != 0x30) return null
        val children = Der(bytes, top.start, top.end)
        val first = children.next() ?: return null
        if (first.tag == 0x30) {
            // EncryptedPrivateKeyInfo: AlgorithmIdentifier { PBE OID, … }, OCTET STRING.
            val algorithm = Der(bytes, first.start, first.end).next() ?: return null
            if (algorithm.tag != 0x06 || PBE_PREFIXES.none { startsWith(bytes, algorithm.start, algorithm.end, it) }) return null
            return if (children.next()?.tag == 0x04) binaryHit(KeyFormat.DER, KeyProtection.PASSPHRASE) else null
        }
        if (first.tag != 0x02 || first.length != 1) return null
        val version = bytes[first.start].toInt()
        val second = children.next() ?: return null
        return when {
            // PKCS#8 PrivateKeyInfo / OneAsymmetricKey: version 0 or 1, AlgorithmIdentifier, OCTET STRING.
            (version == 0 || version == 1) && second.tag == 0x30 -> {
                val algorithm = Der(bytes, second.start, second.end).next() ?: return null
                val known = algorithm.tag == 0x06 && KEY_ALGORITHMS.any { it.size == algorithm.length && startsWith(bytes, algorithm.start, algorithm.end, it) }
                if (known && children.next()?.tag == 0x04) binaryHit(KeyFormat.DER, KeyProtection.NONE) else null
            }
            // PKCS#1 RSAPrivateKey (nine INTEGERs, a long modulus) or OpenSSL's DSA key (six INTEGERs).
            version == 0 && second.tag == 0x02 -> {
                var integers = 2
                while (true) {
                    val next = children.next() ?: break
                    if (next.tag != 0x02) return null
                    integers++
                }
                if ((integers == 9 && second.length >= 64) || integers == 6) binaryHit(KeyFormat.DER, KeyProtection.NONE) else null
            }
            // SEC1 ECPrivateKey: version 1, OCTET STRING key, then [0] parameters and [1] public key, both optional.
            version == 1 && second.tag == 0x04 && second.length in 20..72 -> {
                val rest = children.next()
                if (rest == null || rest.tag == 0xA0 || rest.tag == 0xA1) binaryHit(KeyFormat.DER, KeyProtection.NONE) else null
            }
            else -> null
        }
    }

    /** One DER element: [tag], its content [start] and [length]. */
    private class Tlv(val tag: Int, val start: Int, val length: Int) {
        val end: Int get() = start + length
    }

    /** Reads DER elements in [from, to): definite lengths of at most four octets only. */
    private class Der(private val bytes: ByteArray, private var pos: Int, private val to: Int) {
        fun next(): Tlv? {
            if (pos + 2 > to) return null
            val tag = bytes[pos].toInt() and 0xFF
            if (tag and 0x1F == 0x1F) return null
            var length = bytes[pos + 1].toInt() and 0xFF
            var at = pos + 2
            if (length >= 0x80) {
                val octets = length - 0x80
                if (octets !in 1..4 || at + octets > to) return null
                length = 0
                repeat(octets) { length = (length shl 8) or (bytes[at++].toInt() and 0xFF) }
                if (length < 0) return null
            }
            if (length > to - at) return null
            pos = at + length
            return Tlv(tag, at, length)
        }
    }

    private fun hex(digits: String): ByteArray = ByteArray(digits.length / 2) { digits.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    /** The DER of an OID whose content is [contentHex]: tag 06, length, content. */
    private fun oid(contentHex: String): ByteArray = hex(contentHex).let { byteArrayOf(0x06, it.size.toByte()) + it }

    private fun startsWith(bytes: ByteArray, prefix: ByteArray): Boolean = startsWith(bytes, 0, bytes.size, prefix)

    private fun startsWith(bytes: ByteArray, from: Int, to: Int, prefix: ByteArray): Boolean =
        to - from >= prefix.size && prefix.indices.all { bytes[from + it] == prefix[it] }

    private fun indexOf(bytes: ByteArray, what: ByteArray, limit: Int): Int {
        for (i in 0..(limit - what.size)) if (startsWith(bytes, i, limit, what)) return i
        return -1
    }
}
