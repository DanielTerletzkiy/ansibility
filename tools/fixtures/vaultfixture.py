"""The synthetic vault envelope of the Ansibility test fixtures (python3, stdlib only; D15 as amended for R7).

Every ``!vault`` value and every whole-file vault that ``sync.py`` copies gets a **valid** ansible-vault ``1.1``
envelope (label ``default``) in place of its real payload. The envelope is encrypted with the synthetic fixture
password registered in ``tools/vault/SYNTHETIC.md`` (row ``fixture``) and decrypts to ``dummy`` padded with ``-``.
It is never derived from, copied from or tested against a real vault (DEV.md hard rule 2).

Line numbers stay stable, and so do line lengths: ansible-vault always writes a 32-byte salt and a 32-byte HMAC, so
a payload of ``L`` hex digits holds ``k = (L - 260) / 64`` ciphertext blocks. The fixture plaintext is chosen with
the same ``k`` (``16 (k - 1) + 5`` bytes, inside the original's plaintext length range), so the new payload has
exactly ``L`` digits and is cut into the original line lengths. A payload that does not have this shape (a salt of
another length, a re-wrapped or damaged body) falls back to the ``k`` nearest to its length, at least one block,
spread over the original number of payload lines (see :func:`payload_lines`); the line count is kept either way.

The salt is fixed per value (a hash of the file's path with IPv4 addresses blanked, and the header's line number),
so a re-sync writes byte-identical output. The format is ansible-core's ``VaultAES256``: PBKDF2-HMAC-SHA256 with
10,000 iterations over the raw password gives an AES-256 key, an HMAC key and the initial CTR counter; the plaintext
is PKCS7-padded and encrypted with AES-256-CTR; the HMAC-SHA256 covers the ciphertext; the payload is
``hex(hex(salt) LF hex(hmac) LF hex(ciphertext))``. The AES block cipher below is FIPS-197, checked against its
AES-256 known-answer vector and against the committed vault vectors by ``test_fixtures.py``.
"""

from __future__ import annotations

import binascii
import hashlib
import hmac
import re
from typing import List, Optional, Sequence

#: The synthetic fixture password (``tools/vault/SYNTHETIC.md``, row ``fixture``, label ``default``). Synthetic: it
#: protects nothing and only lets tests prove that every fixture envelope is well formed and decrypts.
FIXTURE_PASSWORD = b"fixture-pass-d15"

#: The header every fixture envelope carries: version 1.1, which Ansible matches against the ``default`` id.
FIXTURE_HEADER = "$ANSIBLE_VAULT;1.1;AES256"

#: The plaintext every fixture envelope decrypts to: ``dummy`` followed by ``-`` padding (possibly none).
FIXTURE_PLAINTEXT_RX = re.compile(rb"^dummy-*$")

#: Hex digits of a payload with the minimum of one ciphertext block (64 + 1 + 64 + 1 + 32 inner digits, doubled).
_FIXED_DIGITS = 2 * (64 + 1 + 64 + 1)
_DIGITS_PER_BLOCK = 2 * 32
_LINE_WIDTH = 80
_ITERATIONS = 10_000
_IPV4_RX = re.compile(r"(?<![\d.])\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}(?!\d|\.\d)")


# ---------------------------------------------------------------------------
# AES-256 block encryption (FIPS-197); CTR mode only needs the forward cipher
# ---------------------------------------------------------------------------


def _rotl8(x: int, shift: int) -> int:
    return ((x << shift) | (x >> (8 - shift))) & 0xFF


def _build_sbox() -> List[int]:
    """The AES S-box, computed (multiplicative inverse in GF(2^8), then the affine map) instead of typed in."""
    sbox = [0] * 256
    p = q = 1
    while True:
        p = (p ^ (p << 1) ^ (0x1B if p & 0x80 else 0)) & 0xFF  # p * 3
        q ^= q << 1
        q ^= q << 2
        q ^= q << 4
        q &= 0xFF
        if q & 0x80:
            q ^= 0x09  # q / 3
        sbox[p] = q ^ _rotl8(q, 1) ^ _rotl8(q, 2) ^ _rotl8(q, 3) ^ _rotl8(q, 4) ^ 0x63
        if p == 1:
            break
    sbox[0] = 0x63
    return sbox


_SBOX = _build_sbox()


def _xtime(a: int) -> int:
    return ((a << 1) ^ 0x1B) & 0xFF if a & 0x80 else a << 1


def _round_keys(key: bytes) -> List[List[int]]:
    """The 15 round keys of AES-256 (Nk = 8, Nr = 14)."""
    if len(key) != 32:
        raise ValueError("AES-256 needs a 32-byte key")
    words = [list(key[4 * i : 4 * i + 4]) for i in range(8)]
    rcon = 1
    for i in range(8, 60):
        t = list(words[i - 1])
        if i % 8 == 0:
            t = [_SBOX[b] for b in t[1:] + t[:1]]
            t[0] ^= rcon
            rcon = _xtime(rcon)
        elif i % 8 == 4:
            t = [_SBOX[b] for b in t]
        words.append([a ^ b for a, b in zip(words[i - 8], t)])
    return [sum(words[4 * r : 4 * r + 4], []) for r in range(15)]


def _encrypt_block(round_keys: List[List[int]], block: bytes) -> bytes:
    """One AES-256 block encryption; the state is column-major (``state[row + 4 * column]``)."""
    s = [b ^ k for b, k in zip(block, round_keys[0])]
    for rnd in range(1, 15):
        s = [_SBOX[b] for b in s]
        s = [s[r + 4 * ((c + r) % 4)] for c in range(4) for r in range(4)]  # ShiftRows
        if rnd != 14:
            mixed: List[int] = []
            for c in range(4):
                a0, a1, a2, a3 = s[4 * c : 4 * c + 4]
                mixed += [
                    _xtime(a0) ^ _xtime(a1) ^ a1 ^ a2 ^ a3,
                    a0 ^ _xtime(a1) ^ _xtime(a2) ^ a2 ^ a3,
                    a0 ^ a1 ^ _xtime(a2) ^ _xtime(a3) ^ a3,
                    _xtime(a0) ^ a0 ^ a1 ^ a2 ^ _xtime(a3),
                ]
            s = mixed
        s = [b ^ k for b, k in zip(s, round_keys[rnd])]
    return bytes(s)


def aes256_encrypt_block(key: bytes, block: bytes) -> bytes:
    """AES-256 of one 16-byte block (for the known-answer test)."""
    return _encrypt_block(_round_keys(key), block)


def _ctr(key: bytes, counter_block: bytes, data: bytes) -> bytes:
    """AES-256-CTR with the whole 128-bit counter incrementing big-endian, as ``cryptography`` does."""
    round_keys = _round_keys(key)
    counter = int.from_bytes(counter_block, "big")
    out = bytearray()
    for i in range(0, len(data), 16):
        stream = _encrypt_block(round_keys, counter.to_bytes(16, "big"))
        out += bytes(a ^ b for a, b in zip(data[i : i + 16], stream))
        counter = (counter + 1) % (1 << 128)
    return bytes(out)


# ---------------------------------------------------------------------------
# The vault 1.1 envelope
# ---------------------------------------------------------------------------


def _derive(password: bytes, salt: bytes):
    derived = hashlib.pbkdf2_hmac("sha256", password, salt, _ITERATIONS, 80)
    return derived[:32], derived[32:64], derived[64:80]


def encrypt_payload(plaintext: bytes, password: bytes, salt: bytes) -> str:
    """The payload hex (without header, unwrapped) ansible-vault writes for ``plaintext`` under ``password`` and ``salt``."""
    if not salt:
        raise ValueError("empty salt")
    cipher_key, hmac_key, iv = _derive(password, salt)
    pad = 16 - len(plaintext) % 16
    ciphertext = _ctr(cipher_key, iv, plaintext + bytes([pad]) * pad)
    tag = hmac.new(hmac_key, ciphertext, hashlib.sha256).digest()
    inner = binascii.hexlify(salt) + b"\n" + binascii.hexlify(tag) + b"\n" + binascii.hexlify(ciphertext)
    return binascii.hexlify(inner).decode("ascii")


def decrypt_payload(payload_hex: str, password: bytes) -> Optional[bytes]:
    """The plaintext of a payload (all hex lines concatenated), or ``None`` when it is malformed or the HMAC fails."""
    try:
        inner = binascii.unhexlify(payload_hex)
        salt_hex, tag_hex, ct_hex = inner.split(b"\n", 2)
        salt, tag, ciphertext = (binascii.unhexlify(x) for x in (salt_hex, tag_hex, ct_hex))
    except (binascii.Error, ValueError):
        return None
    cipher_key, hmac_key, iv = _derive(password, salt)
    if not hmac.compare_digest(hmac.new(hmac_key, ciphertext, hashlib.sha256).digest(), tag):
        return None
    if not ciphertext or len(ciphertext) % 16:
        return None
    padded = _ctr(cipher_key, iv, ciphertext)
    pad = padded[-1]
    if not 1 <= pad <= 16 or padded[-pad:] != bytes([pad]) * pad:
        return None
    return padded[:-pad]


# ---------------------------------------------------------------------------
# Fixture envelopes
# ---------------------------------------------------------------------------


def ciphertext_blocks(digits: int) -> Optional[int]:
    """The ciphertext blocks of an ansible-vault payload of ``digits`` hex digits, or ``None`` for another shape."""
    if digits < _FIXED_DIGITS + _DIGITS_PER_BLOCK or (digits - _FIXED_DIGITS) % _DIGITS_PER_BLOCK:
        return None
    return (digits - _FIXED_DIGITS) // _DIGITS_PER_BLOCK


def fixture_plaintext(blocks: int) -> bytes:
    """``dummy`` padded with ``-`` to ``16 (blocks - 1) + 5`` bytes: exactly ``blocks`` ciphertext blocks."""
    if blocks < 1:
        raise ValueError("an envelope has at least one ciphertext block")
    return b"dummy" + b"-" * (16 * (blocks - 1))


def fixture_salt(salt_key: str) -> bytes:
    """The fixed 32-byte salt of one value: a hash of ``salt_key`` (path and line) with IPv4 addresses blanked."""
    blanked = _IPV4_RX.sub("x.x.x.x", salt_key)
    return hashlib.sha256(b"ansibility-fixture-salt\0" + blanked.encode("utf-8")).digest()


def payload_lines(salt_key: str, line_lengths: Sequence[int]) -> List[str]:
    """The fixture payload for a value whose payload lines had ``line_lengths`` digits, one string per line.

    With an ansible-vault shaped original (:func:`ciphertext_blocks`), the result has the same line lengths. Otherwise
    the nearest block count (at least one) is used and its digits are spread evenly over the same number of lines,
    so the line count still matches; with more lines than digits (impossible for a real envelope) it raises.
    """
    if not line_lengths:
        raise ValueError("a vault payload has at least one line")
    digits = sum(line_lengths)
    blocks = ciphertext_blocks(digits)
    exact = blocks is not None
    if blocks is None:
        blocks = max(1, round((digits - _FIXED_DIGITS) / _DIGITS_PER_BLOCK))
    payload = encrypt_payload(fixture_plaintext(blocks), FIXTURE_PASSWORD, fixture_salt(salt_key))
    if exact:
        lengths = list(line_lengths)
    else:
        n = len(line_lengths)
        if n > len(payload):
            raise ValueError(f"{n} payload lines cannot hold a {len(payload)}-digit envelope")
        base, extra = divmod(len(payload), n)
        lengths = [base + 1 if i < extra else base for i in range(n)]
    out, start = [], 0
    for length in lengths:
        out.append(payload[start : start + length])
        start += length
    assert start == len(payload) and all(out), "fixture payload split"
    return out


def check_envelope(header: str, payload: Sequence[str]) -> Optional[str]:
    """Why a header (without indentation) and its payload lines are not a fixture envelope, or ``None`` when they are.

    The header must be :data:`FIXTURE_HEADER`, the payload must authenticate with :data:`FIXTURE_PASSWORD` (which
    proves it was written by this module and holds no real ciphertext) and decrypt to the dummy plaintext.
    """
    if header.rstrip(" \t") != FIXTURE_HEADER:
        return "vault header is not the synthetic 1.1 header"
    if not payload or any(line != line.rstrip(" \t") for line in payload):
        return "vault payload is empty or has trailing whitespace"
    plaintext = decrypt_payload("".join(payload), FIXTURE_PASSWORD)
    if plaintext is None:
        return "vault payload is not the synthetic fixture envelope"
    if not FIXTURE_PLAINTEXT_RX.match(plaintext):
        return "vault payload does not decrypt to the fixture dummy"
    return None
