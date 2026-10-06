"""Shared rules for the Ansibility test fixtures (python3, stdlib only).

Both ``sync.py`` (which writes the sanitised copy) and ``verify.py`` (which
checks it) import this module, so the two can never disagree about where a
vault envelope is, what a forbidden file or an allowed IPv4 address is. The
synthetic envelope itself lives in ``vaultfixture.py``.

Nothing in here reads or writes files.
"""

from __future__ import annotations

import ipaddress
import re
from typing import Optional

# ---------------------------------------------------------------------------
# Vault
# ---------------------------------------------------------------------------

#: Marker that starts every Ansible vault envelope.
VAULT_MARK = "$ANSIBLE_VAULT"

#: A vault header line, e.g. ``  $ANSIBLE_VAULT;1.1;AES256`` (optionally with a
#: vault-id label ``;label``). The sanitiser replaces it with the synthetic
#: fixture header (``vaultfixture.FIXTURE_HEADER``) at the same indentation.
VAULT_HEADER_RX = re.compile(
    r"^(?P<indent>[ \t]*)\$ANSIBLE_VAULT;\d+\.\d+;AES256(?:;[^\s;]+)?[ \t]*$"
)

#: A vault payload line: indentation, a hex run, optional trailing blanks.
HEX_LINE_RX = re.compile(r"^(?P<indent>[ \t]*)(?P<hex>[0-9A-Fa-f]+)(?P<trail>[ \t]*)$")

#: A ``!vault`` tag followed by a block scalar indicator (``key: !vault |``,
#: ``- !vault |-``). Used to check that every tagged block has a header.
VAULT_TAG_RX = re.compile(r"(?:^|[\s:\-\[{,])!vault[ \t]+[|>][0-9+-]*[ \t]*(?:#.*)?$")


# ---------------------------------------------------------------------------
# Plaintext secrets
# ---------------------------------------------------------------------------

#: Replacement for any plaintext secret value.
REDACTED = "REDACTED"

#: One YAML ``key: value`` line (block style). Groups: ``lead`` (indentation
#: and any ``- `` sequence markers), ``q`` (key quote), ``key``, ``colon``
#: (optional blanks plus ``:``), ``sp`` and ``value`` (raw rest of the line,
#: possibly with a trailing comment; ``None`` when the key has no inline value).
KEY_LINE_RX = re.compile(
    r"""^(?P<lead>[ \t]*(?:-[ \t]+)*)(?P<q>["']?)(?P<key>[A-Za-z_][A-Za-z0-9_.-]*)(?P=q)"""
    r"""(?P<colon>[ \t]*:)(?:(?P<sp>[ \t]+)(?P<value>.*?))?[ \t]*$"""
)

#: Keys that must never carry a plaintext value (``vault_*``, D15 / DEV rule 2).
VAULT_KEY_RX = re.compile(r"^vault_[A-Za-z0-9_]*$")

#: Non-vault keys whose literal values are treated as secrets when they look
#: random (see :func:`looks_like_literal_secret`).
SECRET_KEY_RX = re.compile(
    r"(?i)(?:^|[_.-])(?:secret|password|passwd|passphrase|token"
    r"|(?:api|app|access|secret|private|signing|encryption|master|license|client)_?key)(?:[_.-]|$)"
)

_NULL_WORDS = {"", "~", "null", "Null", "NULL"}
_YAML11_BOOL_WORDS = {
    w
    for base in ("y", "yes", "n", "no", "true", "false", "on", "off")
    for w in (base, base.capitalize(), base.upper())
}
# YAML 1.1 int and float spellings (decimal, octal, hex, binary, sexagesimal,
# plain and exponent floats, infinities, NaN). Only used to *skip* redaction of
# secret-named keys, so a slightly generous match is harmless.
_NUMBER_RX = re.compile(
    r"^[-+]?(?:[0-9][0-9_]*(?::[0-5]?[0-9])*|0x[0-9a-fA-F_]+|0b[01_]+"
    r"|[0-9][0-9_]*\.[0-9_]*(?:[eE][-+]?[0-9]+)?|\.[0-9][0-9_]*(?:[eE][-+]?[0-9]+)?"
    r"|\.(?:inf|Inf|INF))$|^\.(?:nan|NaN|NAN)$"
)
# ``${VAR}``, ``${VAR:-default}``, JCasC ``${readFile:/path}``.
_ENV_REF_RX = re.compile(r"^\$\{[A-Za-z_][A-Za-z0-9_]*(?:[:?=+-][^}]*)?\}$")
# Enum-like words (``on_create``, ``roundrobin``, ``sha512``): lowercase letters and
# underscores with optional trailing digits, at most 20 characters.
_IDENTIFIER_RX = re.compile(r"^(?=.{1,20}$)[a-z][a-z_]*[0-9]*$")
_PATH_PREFIXES = ("/", "~/", "./", "../", "files/", "templates/", "vars/", "tasks/")
_URL_RX = re.compile(r"^[A-Za-z][A-Za-z0-9+.-]*://")
_URL_CREDENTIALS_RX = re.compile(r"^[A-Za-z][A-Za-z0-9+.-]*://[^/\s:@]+:[^/\s@]+@")


def is_template(value: str) -> bool:
    """True when ``value`` contains a Jinja expression or statement."""
    return "{{" in value or "{%" in value


def is_null_scalar(value: str) -> bool:
    """True for YAML null spellings and empty quoted strings (``""``, ``''``)."""
    return value in _NULL_WORDS or value in ('""', "''")


def is_plain_non_string(value: str) -> bool:
    """True for unquoted YAML 1.1 nulls, booleans and numbers."""
    return value in _NULL_WORDS or value in _YAML11_BOOL_WORDS or bool(_NUMBER_RX.match(value))


def looks_like_literal_secret(content: str, quoted: bool) -> bool:
    """Decide whether the scalar ``content`` of a secret-named key is a literal secret.

    ``content`` is the scalar text without quotes. Values that are templated,
    environment references, paths, credential-free URLs, enum-like lowercase
    identifiers (``on_create``), YAML nulls/booleans, numbers (quoted or not,
    e.g. ``'5.0'`` for a timeout) or contain blanks are *not* secrets.
    Everything else is.
    """
    if content == "":
        return False
    if not quoted and is_plain_non_string(content):
        return False
    if _NUMBER_RX.match(content):
        return False
    if is_template(content) or _ENV_REF_RX.match(content):
        return False
    if content.startswith(_PATH_PREFIXES):
        return False
    if _URL_RX.match(content):
        return bool(_URL_CREDENTIALS_RX.match(content))
    if _IDENTIFIER_RX.match(content):
        return False
    if any(ch.isspace() for ch in content):
        return False
    return True


def is_redacted_scalar(content: str) -> bool:
    """True when ``content`` is the redaction marker."""
    return content == REDACTED


# ---------------------------------------------------------------------------
# IPv4
# ---------------------------------------------------------------------------

#: A dotted-quad candidate that is not part of a longer dotted number
#: (``1.2.3.4.5``) or a longer digit run. Octet range is checked separately.
IPV4_RX = re.compile(r"(?<![\d.])(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})(?!\d|\.\d)")

#: RFC 5737 documentation networks, in allocation order.
TEST_NETS = (
    ipaddress.IPv4Network("192.0.2.0/24"),
    ipaddress.IPv4Network("198.51.100.0/24"),
    ipaddress.IPv4Network("203.0.113.0/24"),
)


def parse_ipv4(match: "re.Match[str]") -> Optional[ipaddress.IPv4Address]:
    """Return the address for an :data:`IPV4_RX` match, or ``None`` if an octet exceeds 255."""
    octets = [int(g) for g in match.groups()]
    if any(o > 255 for o in octets):
        return None
    return ipaddress.IPv4Address(".".join(str(o) for o in octets))


def is_test_net(ip: ipaddress.IPv4Address) -> bool:
    """True for addresses inside one of the :data:`TEST_NETS`."""
    return any(ip in net for net in TEST_NETS)


def is_netmask(ip: ipaddress.IPv4Address) -> bool:
    """True for contiguous netmasks with a first octet of 255 (``255.255.255.0``)."""
    value = int(ip)
    if value >> 24 != 0xFF:
        return False
    inverted = (~value) & 0xFFFFFFFF
    return inverted & (inverted + 1) == 0


def is_kept_special(ip: ipaddress.IPv4Address) -> bool:
    """Special-purpose addresses that identify nothing and are kept verbatim.

    Loopback (``127.0.0.0/8``), the unspecified address ``0.0.0.0`` (so
    ``0.0.0.0/0`` keeps meaning "anywhere"), and netmasks including the
    limited broadcast ``255.255.255.255``.
    """
    return ip.is_loopback or ip.is_unspecified or is_netmask(ip)


def is_allowed_ipv4(ip: ipaddress.IPv4Address) -> bool:
    """True when ``ip`` may appear in the sanitised fixtures."""
    return is_test_net(ip) or is_kept_special(ip)


# ---------------------------------------------------------------------------
# Files
# ---------------------------------------------------------------------------

#: File names that are never copied because they hold secrets.
FORBIDDEN_NAMES = {".vault-pass", ".vault_pass", ".initial-root-pass", ".vault-password"}

#: File name suffixes that are never copied (key material, password files).
FORBIDDEN_SUFFIXES = (".key", ".pem", ".crt", ".password", ".p12", ".pfx", ".jks", ".keystore")

#: Directory pairs whose whole subtree is never copied.
FORBIDDEN_DIR_PAIRS = (("files", "ssl"), ("files", "ssh"))

#: Junk that is skipped silently (caches, IDE state, OS metadata).
JUNK_NAMES = {".DS_Store", "__pycache__", ".ansible", ".idea", ".pytest_cache", ".cache", ".venv", ".tox"}
JUNK_SUFFIXES = (".pyc", ".pyo", ".swp")


def forbidden_reason(rel_path: str) -> Optional[str]:
    """Why ``rel_path`` (POSIX, relative) must never be copied, or ``None`` if it may be.

    A ``.git`` *directory* anywhere, ``.git`` files (only the synthetic links
    written by ``sync.py`` are allowed, see :data:`SYNTHETIC_GIT_LINK_RX`),
    ``.vault-pass`` and friends, ``.env*``, anything under ``files/ssl`` or
    ``files/ssh``, and key, certificate and password files.
    """
    parts = [p for p in rel_path.split("/") if p]
    if not parts:
        return None
    name = parts[-1]
    if ".git" in parts[:-1]:
        return ".git directory"
    if name == ".git":
        return ".git link"
    for a, b in FORBIDDEN_DIR_PAIRS:
        for i in range(len(parts) - 2):
            if parts[i] == a and parts[i + 1] == b:
                return f"{a}/{b} subtree"
    if name in FORBIDDEN_NAMES:
        return "vault or root password file"
    if name.startswith(".env"):
        return ".env file"
    if name.lower().endswith(FORBIDDEN_SUFFIXES):
        return "key, certificate or password file"
    return None


def junk_reason(rel_path: str) -> Optional[str]:
    """Why ``rel_path`` is junk that is skipped without being reported as forbidden."""
    parts = [p for p in rel_path.split("/") if p]
    if any(p in JUNK_NAMES for p in parts):
        return "cache or IDE state"
    if parts and parts[-1].endswith(JUNK_SUFFIXES):
        return "compiled or swap file"
    return None


# ---------------------------------------------------------------------------
# Synthetic git links
# ---------------------------------------------------------------------------

#: Worktree name of the synthetic detached worktree.
WORKTREE_NAME = "wt-demo"

#: Directory of the synthetic detached worktree, relative to the fixture root.
WORKTREE_DIR = f".claude/worktrees/{WORKTREE_NAME}"

#: Content of the synthetic worktree ``.git`` file.
WORKTREE_GIT_LINK = f"gitdir: /tmp/fake/.git/worktrees/{WORKTREE_NAME}\n"


def submodule_git_link(name: str) -> str:
    """Content of the synthetic ``.git`` file of submodule ``repos/<name>``."""
    return f"gitdir: ../../.git/modules/repos/{name}\n"


#: The only ``.git`` file contents allowed in the fixtures.
SYNTHETIC_GIT_LINK_RX = re.compile(
    r"^gitdir: (?:\.\./\.\./\.git/modules/repos/[A-Za-z0-9_-]+|/tmp/fake/\.git/worktrees/[A-Za-z0-9_-]+)\n$"
)
