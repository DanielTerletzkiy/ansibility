"""Line-preserving sanitiser for the Ansibility test fixtures (python3, stdlib only).

Every transformation here rewrites the *body* of a line and never touches line
terminators, so a sanitised file has exactly the same number of lines as its
source and every ``path:line`` reference stays valid. :func:`sanitise_text`
asserts this before it returns.

Three passes run in this order:

1. :func:`scrub_vault` replaces every vault envelope (inline ``!vault |``
   blocks and whole-file vaults) with the synthetic fixture envelope of
   ``vaultfixture.py``: a valid ``$ANSIBLE_VAULT;1.1;AES256`` envelope that
   decrypts with the synthetic fixture password, with the same indentation,
   the same number of payload lines and (for ansible-vault shaped payloads)
   the same line lengths. The tag stays.
2. :func:`redact_secret_keys` replaces plaintext values of ``vault_*`` keys,
   and random-looking literal values of secret-named keys, with ``REDACTED``.
3. :meth:`IpMapper.rewrite` maps IPv4 addresses to TEST-NET addresses.
"""

from __future__ import annotations

import ipaddress
import re
from dataclasses import dataclass, fields
from typing import Iterable, List, Optional, Set, Tuple

import rules
import vaultfixture

_LINE_SPLIT_RX = re.compile(r"(\r\n|\n|\r)")
_PROPERTIES_RX = re.compile(r"^((?:[!&][^\s]*[ \t]+)*)(.*)$")
_TEMPLATE_SEGMENT_RX = re.compile(r"\{\{.*?\}\}", re.S)


class SanitiseError(Exception):
    """Raised when a file cannot be sanitised safely (unknown vault shape, exhausted TEST-NET)."""


@dataclass
class Stats:
    """What the sanitiser changed in one file (or, summed, in the whole fixture set)."""

    vault_blocks: int = 0
    vault_payload_lines: int = 0
    vault_keys_redacted: int = 0
    secret_keys_redacted: int = 0
    ip_occurrences: int = 0

    def add(self, other: "Stats") -> None:
        """Add ``other`` into this instance."""
        for f in fields(self):
            setattr(self, f.name, getattr(self, f.name) + getattr(other, f.name))

    def changed(self) -> bool:
        """True when anything was rewritten."""
        return any(getattr(self, f.name) for f in fields(self))

    def summary(self) -> str:
        """Compact ``name=count`` list of the non-zero counters (for the manifest)."""
        short = {
            "vault_blocks": "vault",
            "vault_payload_lines": "vault_lines",
            "vault_keys_redacted": "vault_key",
            "secret_keys_redacted": "secret_key",
            "ip_occurrences": "ip",
        }
        parts = [f"{short[f.name]}={getattr(self, f.name)}" for f in fields(self) if getattr(self, f.name)]
        return ",".join(parts) if parts else "-"


# ---------------------------------------------------------------------------
# Lines
# ---------------------------------------------------------------------------


def split_lines(text: str) -> Tuple[List[str], List[str]]:
    """Split ``text`` into line bodies and their terminators (``\\n``, ``\\r\\n``, ``\\r`` or ``""``).

    ``"".join(b + t for b, t in zip(bodies, terminators)) == text`` always holds.
    """
    parts = _LINE_SPLIT_RX.split(text)
    bodies = parts[0::2]
    terminators = parts[1::2] + [""]
    return bodies, terminators


def join_lines(bodies: List[str], terminators: List[str]) -> str:
    """Inverse of :func:`split_lines`."""
    return "".join(b + t for b, t in zip(bodies, terminators))


def count_lines(text: str) -> int:
    """Number of lines as an editor shows them (a trailing terminator does not start a new line)."""
    bodies, _ = split_lines(text)
    return len(bodies) - 1 if bodies and bodies[-1] == "" else len(bodies)


def _indent_of(body: str) -> int:
    return len(body) - len(body.lstrip(" \t"))


# ---------------------------------------------------------------------------
# Pass 1: vault payloads
# ---------------------------------------------------------------------------


def scrub_vault(bodies: List[str], stats: Stats, salt_key: str = "") -> Set[int]:
    """Replace vault envelopes in place; return the indices of all vault header and payload lines.

    A header line (``$ANSIBLE_VAULT;1.1;AES256``, or ``1.2`` with a label)
    becomes :data:`vaultfixture.FIXTURE_HEADER` at the same indentation. The
    consecutive hex lines that follow it at the same indentation are its
    payload and become :func:`vaultfixture.payload_lines` for the same line
    lengths (trailing blanks are dropped, so the envelope stays valid).
    ``salt_key`` (the file's relative path) makes each value's salt distinct
    and the output deterministic.

    Any other occurrence of ``$ANSIBLE_VAULT`` (for example inside a quoted
    scalar with ``\\n`` escapes), a ``!vault |`` tag not followed by a
    header, and a header without payload lines or with more hex lines after a
    blank line raise :class:`SanitiseError`, because the payload could not be
    located safely.
    """
    touched: Set[int] = set()
    n = len(bodies)
    i = 0
    while i < n:
        body = bodies[i]
        if rules.VAULT_MARK not in body:
            i += 1
            continue
        header = rules.VAULT_HEADER_RX.match(body)
        if header is None:
            raise SanitiseError(f"line {i + 1}: '{rules.VAULT_MARK}' outside a vault header line")
        indent = header.group("indent")
        j = i + 1
        lengths: List[int] = []
        while j < n:
            payload = rules.HEX_LINE_RX.match(bodies[j])
            if payload is None or payload.group("indent") != indent:
                break
            lengths.append(len(payload.group("hex")))
            j += 1
        if not lengths:
            raise SanitiseError(f"line {i + 1}: vault header without payload lines")
        after = next((k for k in range(j, n) if bodies[k].strip()), None)
        if after is not None and after > j:
            more = rules.HEX_LINE_RX.match(bodies[after])
            if more is not None and more.group("indent") == indent:
                raise SanitiseError(f"line {after + 1}: vault payload continues after a blank line")
        bodies[i] = indent + vaultfixture.FIXTURE_HEADER
        touched.add(i)
        for k, line in enumerate(vaultfixture.payload_lines(f"{salt_key}:{i + 1}", lengths)):
            bodies[i + 1 + k] = indent + line
            touched.add(i + 1 + k)
        stats.vault_blocks += 1
        stats.vault_payload_lines += len(lengths)
        i = j

    for i, body in enumerate(bodies):
        if rules.VAULT_TAG_RX.search(body):
            nxt = next((k for k in range(i + 1, n) if bodies[k].strip()), None)
            if nxt is None or rules.VAULT_HEADER_RX.match(bodies[nxt]) is None:
                raise SanitiseError(f"line {i + 1}: '!vault' block without a '{rules.VAULT_MARK}' header")
    return touched


# ---------------------------------------------------------------------------
# Pass 2: plaintext secrets
# ---------------------------------------------------------------------------


def is_pure_template(content: str) -> bool:
    """True when ``content`` consists only of ``{{ ... }}`` expressions and blanks."""
    return "{{" in content and _TEMPLATE_SEGMENT_RX.sub("", content).strip() == ""


def _closing_quote(text: str, start: int, quote: str) -> Optional[int]:
    """Index of the quote that closes a scalar whose opening quote precedes ``start``."""
    i = start
    while i < len(text):
        c = text[i]
        if quote == '"' and c == "\\":
            i += 2
            continue
        if c == quote:
            if quote == "'" and i + 1 < len(text) and text[i + 1] == "'":
                i += 2
                continue
            return i
        i += 1
    return None


def _strip_comment(plain: str) -> str:
    """Plain scalar text without a trailing ``  # comment``."""
    m = re.search(r"[ \t]#", plain)
    return (plain[: m.start()] if m else plain).rstrip()


def _following_indented(bodies: List[str], start: int, key_col: int, skip: Set[int]) -> List[int]:
    """Indices after ``start`` that belong to a value of a key at column ``key_col``.

    These are blank lines and lines indented deeper than ``key_col``; trailing
    blank lines are not included.
    """
    extent: List[int] = []
    j = start + 1
    while j < len(bodies) and j not in skip:
        body = bodies[j]
        if body.strip() == "":
            extent.append(j)
        elif _indent_of(body) > key_col:
            extent.append(j)
        else:
            break
        j += 1
    while extent and bodies[extent[-1]].strip() == "":
        extent.pop()
    return extent


def _should_redact(vault_key: bool, content: str, quoted: bool) -> bool:
    if rules.is_redacted_scalar(content):
        return False
    if vault_key:
        if content == "" or (not quoted and content in {"~", "null", "Null", "NULL"}):
            return False
        return not is_pure_template(content)
    return rules.looks_like_literal_secret(content, quoted)


def redact_secret_keys(bodies: List[str], stats: Stats, skip: Set[int] = frozenset()) -> List[Tuple[int, str]]:
    """Redact plaintext secrets in place; return ``(line index, kind)`` for every rewritten line.

    ``kind`` is ``"vault_key"`` or ``"secret_key"``.

    Two kinds of block-style ``key: value`` entries are rewritten:

    * ``vault_*`` keys whose value is not ``!vault``, null, empty or a pure
      Jinja expression (the value itself would be the secret);
    * keys named like a secret (``*_password``, ``*_secret``, ``*_token``,
      ``api_key`` ...) whose literal value looks random, see
      :func:`rules.looks_like_literal_secret`.

    A single-line scalar becomes ``REDACTED`` (keeping its quote style and any
    non-comment trailer such as a JSON comma). A block scalar keeps its
    indicator line and every content line becomes ``REDACTED`` at its own
    indentation. Continuation lines of multi-line plain or quoted scalars
    become empty lines. Keys without an inline value (nested collections,
    nulls) are left alone; their nested keys are handled on their own lines.
    """
    rewritten: List[Tuple[int, str]] = []
    n = len(bodies)
    i = 0
    while i < n:
        if i in skip:
            i += 1
            continue
        body = bodies[i]
        m = rules.KEY_LINE_RX.match(body)
        if m is None or m.group("value") is None or m.group("value").startswith("#"):
            i += 1
            continue
        key = m.group("key")
        vault_key = bool(rules.VAULT_KEY_RX.match(key))
        if not vault_key and not rules.SECRET_KEY_RX.search(key):
            i += 1
            continue
        kind = "vault_key" if vault_key else "secret_key"
        key_col = len(m.group("lead"))
        props, rest = _PROPERTIES_RX.match(m.group("value")).groups()
        if "!vault" in props.split() or rest.startswith("*") or rest == "":
            i += 1
            continue
        prefix = body[: m.start("value")] + props

        if rest[0] in "|>":
            extent = _following_indented(bodies, i, key_col, skip)
            content = [bodies[j] for j in extent if bodies[j].strip()]
            if not content or all(rules.is_redacted_scalar(c.strip()) for c in content):
                i = (extent[-1] if extent else i) + 1
                continue
            if vault_key or not all(rules.is_template(c) for c in content):
                for j in extent:
                    if bodies[j].strip():
                        bodies[j] = bodies[j][: _indent_of(bodies[j])] + rules.REDACTED
                        rewritten.append((j, kind))
                _count(stats, vault_key)
            i = (extent[-1] if extent else i) + 1
            continue

        if rest[0] in "\"'":
            quote = rest[0]
            close = _closing_quote(rest, 1, quote)
            if close is not None:
                content = rest[1:close]
                trailer = rest[close + 1 :].strip()
                keep_trailer = "" if trailer.startswith("#") or trailer == "" else trailer
                if _should_redact(vault_key, content, quoted=True):
                    bodies[i] = prefix + quote + rules.REDACTED + quote + keep_trailer
                    rewritten.append((i, kind))
                    _count(stats, vault_key)
                i += 1
                continue
            # Multi-line quoted scalar: the continuation runs to the closing quote.
            extent = _following_indented(bodies, i, key_col, skip)
            parts, end = [rest[1:]], None
            for j in extent:
                pos = _closing_quote(bodies[j], 0, quote)
                parts.append(bodies[j] if pos is None else bodies[j][:pos])
                if pos is not None:
                    end = j
                    break
            first = next((p.strip() for p in parts if p.strip()), "")
            if _should_redact(vault_key, first, quoted=True):
                bodies[i] = prefix + quote + rules.REDACTED + quote
                rewritten.append((i, kind))
                # Unterminated (end is None): leave the already broken rest alone.
                for j in extent if end is not None else []:
                    if j > end:
                        break
                    bodies[j] = ""
                    rewritten.append((j, kind))
                _count(stats, vault_key)
            i = (end if end is not None else i) + 1
            continue

        if rest[0] in "[{":
            if vault_key and not is_pure_template(rest):
                bodies[i] = prefix + rules.REDACTED
                rewritten.append((i, kind))
                _count(stats, vault_key)
            i += 1
            continue

        # Plain scalar, judged on its first line; deeper-indented lines that
        # follow are its continuation and are cleared with it.
        content = _strip_comment(rest)
        trailer = ""
        if content.endswith(","):
            content, trailer = content[:-1].rstrip(), ","
        if _should_redact(vault_key, content, quoted=False):
            bodies[i] = prefix + rules.REDACTED + trailer
            rewritten.append((i, kind))
            extent = _following_indented(bodies, i, key_col, skip)
            for j in extent:
                if bodies[j] != "":
                    bodies[j] = ""
                    rewritten.append((j, kind))
            _count(stats, vault_key)
            i = (extent[-1] if extent else i) + 1
            continue
        i += 1
    return rewritten


def _count(stats: Stats, vault_key: bool) -> None:
    if vault_key:
        stats.vault_keys_redacted += 1
    else:
        stats.secret_keys_redacted += 1


# ---------------------------------------------------------------------------
# Pass 3: IPv4
# ---------------------------------------------------------------------------


def collect_ipv4(text: str) -> Set[ipaddress.IPv4Address]:
    """All valid IPv4 addresses that :data:`rules.IPV4_RX` finds in ``text``."""
    found: Set[ipaddress.IPv4Address] = set()
    for m in rules.IPV4_RX.finditer(text):
        ip = rules.parse_ipv4(m)
        if ip is not None:
            found.add(ip)
    return found


class IpMapper:
    """Stable, collision-free mapping from IPv4 addresses to TEST-NET addresses.

    Built once from every address in the fixture set, so the same source
    address gets the same replacement in every file and two different
    addresses never share one; CIDR suffixes are untouched because only the
    dotted quad is rewritten. Addresses are assigned in numeric order from
    ``192.0.2.1`` over ``198.51.100.1`` to ``203.0.113.254``. TEST-NET
    addresses already present in the source keep their value and are taken
    out of the pool; special-purpose addresses (:func:`rules.is_kept_special`)
    are kept. No mapping table is ever written anywhere.

    ``later`` holds the addresses that only later additions to the subset
    bring. They are mapped after ``addresses``, in numeric order, to the next
    free pool entries, so adding files never changes the replacement of an
    address the earlier subset already had (and never rewrites earlier output).
    A TEST-NET address in ``later`` that equals a replacement already handed
    out would make two addresses look alike, so it is refused.
    """

    def __init__(self, addresses: Iterable[ipaddress.IPv4Address], later: Iterable[ipaddress.IPv4Address] = ()):
        distinct = sorted(set(addresses))
        present = {ip for ip in distinct if rules.is_test_net(ip)}
        to_map = [ip for ip in distinct if not rules.is_allowed_ipv4(ip)]
        pool = [ip for net in rules.TEST_NETS for ip in net.hosts() if ip not in present]
        if len(to_map) > len(pool):
            raise SanitiseError(
                f"{len(to_map)} distinct IPv4 addresses need mapping but TEST-NET has only {len(pool)} free"
            )
        self._map = dict(zip(to_map, pool))

        extra = sorted(set(later) - set(distinct))
        extra_present = {ip for ip in extra if rules.is_test_net(ip)}
        clashes = extra_present & set(self._map.values())
        if clashes:
            raise SanitiseError(
                f"{len(clashes)} TEST-NET address(es) of later subset entries equal replacements of earlier ones"
            )
        extra_to_map = [ip for ip in extra if not rules.is_allowed_ipv4(ip)]
        free = [ip for ip in pool[len(to_map):] if ip not in extra_present]
        if len(extra_to_map) > len(free):
            raise SanitiseError(
                f"{len(to_map) + len(extra_to_map)} distinct IPv4 addresses need mapping but TEST-NET has too few free"
            )
        self._map.update(zip(extra_to_map, free))

    def __len__(self) -> int:
        return len(self._map)

    def replacement(self, ip: ipaddress.IPv4Address) -> ipaddress.IPv4Address:
        """The TEST-NET (or kept) address for ``ip``."""
        if rules.is_allowed_ipv4(ip):
            return ip
        try:
            return self._map[ip]
        except KeyError:
            raise SanitiseError("IPv4 address not collected before mapping") from None

    def rewrite(self, body: str, stats: Stats) -> str:
        """Return ``body`` with every mapped address replaced."""

        def sub(m: "re.Match[str]") -> str:
            ip = rules.parse_ipv4(m)
            if ip is None or rules.is_allowed_ipv4(ip):
                return m.group(0)
            stats.ip_occurrences += 1
            return str(self.replacement(ip))

        return rules.IPV4_RX.sub(sub, body)


# ---------------------------------------------------------------------------
# Whole file
# ---------------------------------------------------------------------------


def redact_text(text: str, salt_key: str = "") -> Tuple[str, Stats]:
    """Passes 1 and 2 (vault envelopes and plaintext secrets) on a whole file; ``salt_key`` is its relative path."""
    stats = Stats()
    bodies, terminators = split_lines(text)
    vault_lines = scrub_vault(bodies, stats, salt_key)
    redact_secret_keys(bodies, stats, vault_lines)
    return _checked_join(text, bodies, terminators), stats


def scrub_vault_text(text: str, salt_key: str = "") -> Tuple[str, Stats]:
    """Pass 1 alone (vault envelopes) on a whole file; ``salt_key`` is its relative path.

    The result depends only on the file's own structure (header positions, indentation and payload line lengths,
    and the path with IPv4 addresses blanked), so running it on an already sanitised file gives the same envelopes as
    running it on the source. ``sync.py --envelopes-only`` relies on this.
    """
    stats = Stats()
    bodies, terminators = split_lines(text)
    scrub_vault(bodies, stats, salt_key)
    return _checked_join(text, bodies, terminators), stats


def map_ips(text: str, mapper: IpMapper, stats: Stats) -> str:
    """Pass 3 (IPv4 to TEST-NET) on a whole file, adding to ``stats``."""
    bodies, terminators = split_lines(text)
    bodies = [mapper.rewrite(b, stats) for b in bodies]
    return _checked_join(text, bodies, terminators)


def sanitise_text(text: str, mapper: IpMapper, salt_key: str = "") -> Tuple[str, Stats]:
    """All three passes on one file. The result has exactly as many lines as ``text``."""
    redacted, stats = redact_text(text, salt_key)
    return map_ips(redacted, mapper, stats), stats


def _checked_join(original: str, bodies: List[str], terminators: List[str]) -> str:
    if any("\n" in b or "\r" in b for b in bodies):
        raise SanitiseError("a rewritten line contains a line break")
    result = join_lines(bodies, terminators)
    if count_lines(result) != count_lines(original):
        raise SanitiseError("line count changed")
    return result
