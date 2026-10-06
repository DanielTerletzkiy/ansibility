#!/usr/bin/env python3
"""Fail if a fixture tree contains anything that must not be in the plugin repo.

    python3 tools/fixtures/verify.py [DIR]     # default: plugin/src/test/testData/infra

Checks every file for:

* a ``$ANSIBLE_VAULT`` envelope that is not the synthetic fixture envelope
  (header ``$ANSIBLE_VAULT;1.1;AES256``, a payload that authenticates with the
  synthetic fixture password of ``tools/vault/SYNTHETIC.md`` and decrypts to the
  dummy plaintext; see ``vaultfixture.py``), or the marker anywhere other than a
  header line;
* a plaintext ``vault_*`` value, or a random-looking literal under a
  secret-named key (running the redaction pass again would change the file);
* an IPv4 address outside TEST-NET that is not loopback, ``0.0.0.0`` or a netmask;
* forbidden files (``.vault-pass``, ``.env*``, ``files/ssl``, ``files/ssh``,
  ``*.key``, ``*.pem``, ``*.crt``, ``*.password`` ...), ``.git`` directories,
  ``.git`` files other than the synthetic links, and binary files;
* a missing synthetic ``.git`` link (one per ``repos/<name>`` and one for the
  detached worktree); git cannot track files named ``.git``, so a fresh clone
  needs ``sync.py --links-only``.

Problems are printed as ``path:line: reason`` and never include the value.
Exit code 0 when clean, 1 otherwise.
"""

from __future__ import annotations

import os
import re
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import List, Optional, Sequence

sys.dont_write_bytecode = True  # keep tools/fixtures free of __pycache__
sys.path.insert(0, str(Path(__file__).resolve().parent))

import rules  # noqa: E402
import sanitise  # noqa: E402
import vaultfixture  # noqa: E402

DEFAULT_ROOT = Path(__file__).resolve().parent.parent.parent / "plugin" / "src" / "test" / "testData" / "infra"

_SUBMODULE_LINK_PATH_RX = re.compile(r"^repos/(?P<name>[A-Za-z0-9_-]+)/\.git$")
_WORKTREE_LINK_PATH_RX = re.compile(r"^\.claude/worktrees/(?P<name>[A-Za-z0-9_-]+)/\.git$")


@dataclass(frozen=True)
class Problem:
    """One finding. ``line`` is 1-based, or 0 for a file-level problem."""

    path: str
    line: int
    reason: str

    def __str__(self) -> str:
        return f"{self.path}:{self.line}: {self.reason}" if self.line else f"{self.path}: {self.reason}"


def verify_text(rel: str, text: str) -> List[Problem]:
    """All problems in one file's text."""
    problems: List[Problem] = []
    bodies, _ = sanitise.split_lines(text)
    n = len(bodies)

    vault_lines = set()
    i = 0
    while i < n:
        body = bodies[i]
        if rules.VAULT_MARK not in body:
            i += 1
            continue
        header = rules.VAULT_HEADER_RX.match(body)
        if header is None:
            problems.append(Problem(rel, i + 1, f"'{rules.VAULT_MARK}' outside a vault header line"))
            i += 1
            continue
        vault_lines.add(i)
        indent = header.group("indent")
        payload_lines: List[str] = []
        j = i + 1
        while j < n:
            payload = rules.HEX_LINE_RX.match(bodies[j])
            if payload is None or payload.group("indent") != indent:
                break
            vault_lines.add(j)
            payload_lines.append(bodies[j][len(indent):])
            j += 1
        reason = vaultfixture.check_envelope(body[len(indent):], payload_lines)
        if reason is not None:
            problems.append(Problem(rel, i + 1, reason))
        i = j

    for i, body in enumerate(bodies):
        if rules.VAULT_TAG_RX.search(body):
            nxt = next((k for k in range(i + 1, n) if bodies[k].strip()), None)
            if nxt is None or rules.VAULT_HEADER_RX.match(bodies[nxt]) is None:
                problems.append(Problem(rel, i + 1, f"'!vault' block without a '{rules.VAULT_MARK}' header"))

    for idx, kind in sorted(set(sanitise.redact_secret_keys(list(bodies), sanitise.Stats(), vault_lines))):
        what = "plaintext vault_* value" if kind == "vault_key" else "plaintext secret value"
        problems.append(Problem(rel, idx + 1, what))

    for i, body in enumerate(bodies):
        for m in rules.IPV4_RX.finditer(body):
            ip = rules.parse_ipv4(m)
            if ip is not None and not rules.is_allowed_ipv4(ip):
                problems.append(Problem(rel, i + 1, "IPv4 address outside TEST-NET"))
    return problems


def _verify_git_link(rel: str, path: Path) -> Optional[Problem]:
    try:
        content = path.read_text(encoding="utf-8")
    except (OSError, UnicodeDecodeError):
        return Problem(rel, 0, "unreadable .git file")
    if not rules.SYNTHETIC_GIT_LINK_RX.match(content):
        return Problem(rel, 0, ".git file is not a synthetic link")
    sub = _SUBMODULE_LINK_PATH_RX.match(rel)
    if sub and content == rules.submodule_git_link(sub.group("name")):
        return None
    wt = _WORKTREE_LINK_PATH_RX.match(rel)
    if wt and content == f"gitdir: /tmp/fake/.git/worktrees/{wt.group('name')}\n":
        return None
    return Problem(rel, 0, ".git link does not match its location")


def verify_tree(root: Path) -> List[Problem]:
    """All problems under ``root``; an empty list means the fixture set is clean."""
    root = Path(root)
    if not root.is_dir():
        return [Problem(str(root), 0, "fixture directory not found")]
    problems: List[Problem] = []
    repos = root / "repos"
    expected_links = [f"repos/{d.name}/.git" for d in sorted(repos.iterdir()) if d.is_dir()] if repos.is_dir() else []
    expected_links.append(f"{rules.WORKTREE_DIR}/.git")
    for rel in expected_links:
        if not (root / rel).is_file():
            problems.append(Problem(rel, 0, "synthetic .git link missing (run sync.py --links-only)"))
    for dirpath, dirnames, filenames in os.walk(root):
        base = Path(dirpath)
        for d in list(dirnames):
            rel = (base / d).relative_to(root).as_posix()
            if d == ".git":
                problems.append(Problem(rel + "/", 0, ".git directory"))
                dirnames.remove(d)
            elif (base / d).is_symlink():
                problems.append(Problem(rel, 0, "symbolic link"))
                dirnames.remove(d)
        dirnames.sort()
        for f in sorted(filenames):
            path = base / f
            rel = path.relative_to(root).as_posix()
            if rules.junk_reason(rel):
                continue
            for m in rules.IPV4_RX.finditer(rel):
                ip = rules.parse_ipv4(m)
                if ip is not None and not rules.is_allowed_ipv4(ip):
                    problems.append(Problem(rel, 0, "IPv4 address outside TEST-NET in the path"))
            if path.is_symlink():
                problems.append(Problem(rel, 0, "symbolic link"))
                continue
            if f == ".git":
                p = _verify_git_link(rel, path)
                if p:
                    problems.append(p)
                continue
            reason = rules.forbidden_reason(rel)
            if reason:
                problems.append(Problem(rel, 0, f"forbidden file ({reason})"))
                continue
            try:
                text = path.read_bytes().decode("utf-8")
            except UnicodeDecodeError:
                problems.append(Problem(rel, 0, "binary file (cannot be verified)"))
                continue
            problems.extend(verify_text(rel, text))
    return problems


def run(argv: Optional[Sequence[str]] = None, out=sys.stdout) -> int:
    """Entry point; returns the process exit code."""
    args = list(sys.argv[1:] if argv is None else argv)
    root = Path(args[0]) if args else DEFAULT_ROOT
    problems = verify_tree(root)
    files = sum(len(fs) for _, _, fs in os.walk(root)) if root.is_dir() else 0
    for p in problems:
        print(p, file=out)
    print(f"{'FAILED' if problems else 'OK'}: {files} files checked, {len(problems)} problems in {root}", file=out)
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(run())
