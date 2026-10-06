#!/usr/bin/env python3
"""Copy a curated, sanitised subset of the infra repo into the plugin's test data.

    python3 tools/fixtures/sync.py                 # sync, verify, report
    python3 tools/fixtures/sync.py --dry-run       # show what would be copied
    python3 tools/fixtures/sync.py --links-only    # only (re)write the synthetic .git files
    python3 tools/fixtures/sync.py --envelopes-only  # only rewrite the vault envelopes of the existing fixture

The source repo is only ever read (DEV.md rule 1). The output is built in a
staging directory next to the destination, verified with ``verify.py`` and
only then swapped in, so a failed run never leaves a half-written fixture set.
Sanitising preserves every line number; see ``sanitise.py`` and README.md.
Vault envelopes become the synthetic fixture envelope of ``vaultfixture.py``
(valid ``1.1``, synthetic password in ``tools/vault/SYNTHETIC.md``).
"""

from __future__ import annotations

import argparse
import fnmatch
import os
import re
import shutil
import sys
import tempfile
from dataclasses import dataclass, field
from pathlib import Path
from typing import Dict, Iterable, List, Optional, Sequence, Set, Tuple

sys.dont_write_bytecode = True  # keep tools/fixtures free of __pycache__
sys.path.insert(0, str(Path(__file__).resolve().parent))

import rules  # noqa: E402
import sanitise  # noqa: E402
import verify  # noqa: E402
from names import IDENTITY, LOCAL_MAPPING, Names, load as load_names  # noqa: E402

HERE = Path(__file__).resolve().parent
PROJECT = HERE.parent.parent
DEFAULT_DEST = PROJECT / "plugin" / "src" / "test" / "testData" / "infra"
DEFAULT_MANIFEST = HERE / "manifest.tsv"
SIZE_TARGET = 8 * 1024 * 1024
YAML_OR_JINJA_SUFFIXES = (".yml", ".yaml", ".j2")

#: The curated subset (PLAN.md, Testing strategy 3). Paths are relative to the
#: infra repo; ``{a,b}`` alternatives and ``*`` wildcards are expanded, a
#: trailing ``/**`` (or naming a directory) copies the whole directory.
SUBSET: Tuple[str, ...] = (
    # Golden role library: whole role directories including molecule/default.
    "golden/roles/{haproxy,grafana,loki,keycloak,redis,chronod,coolify,alloy,postfix,docker,system,"
    "iptables,jenkins-controller,deployment-target,totp-token,percona}",
    "golden/playbooks/**",
    "golden/docker/*/Dockerfile",
    "golden/docker/*/requirements.yml",
    # falcon: a complete root with inventory, playbook-level vars and drifted roles.
    "repos/falcon/ansible/{ansible.cfg,playbook-*.yml,group_vars/**,environments/{prod,ops,test}/**}",
    "repos/falcon/ansible/roles/{postfix,haproxy,totp-token,loki,grafana,system,jenkins-agent-docker,docker}",
    "repos/falcon/ansible/docker/*/Dockerfile",
    # heron: the ANS-T002 corpus file and a drifted keycloak role.
    "repos/heron/ansible/environments/prod/group_vars/keycloak/vars.yml",
    "repos/heron/ansible/{ansible.cfg,roles/keycloak}",
    # platform: flat group_vars files, mixed vault, non-standard host_vars names.
    "repos/platform/ansible/{ansible.cfg,environments/prod/hosts.yml,environments/ops/group_vars/**,"
    "environments/prod/group_vars/**,environments/prod/host_vars/prod-training1/**,group_vars/*.yml,"
    "roles/mysql-databases/molecule/default/verify.yml,roles/puppet-migration/meta/**,"
    "playbook-puppet-migration.yml}",
    "repos/platform/ansible/roles/grafana/templates/provisioning/alerting/notification-policies.yml.j2",
    # pelican: the nested danger_zone playbook root.
    "repos/pelican/ansible/{ansible.cfg,danger_zone/database/**,playbook-setup-replisync.yml,environments/prod/hosts.yml}",
    # wren, raven, thrush: Jinja-heavy templates.
    "repos/wren/ansible/{ansible.cfg,environments/prod/group_vars/all/vars.yml,roles/app-wren-mono/tasks/nginx.yml,"
    "roles/app-wren-mono/templates/nginx/**}",
    "repos/raven/ansible/roles/app-raven-mono/templates/deployment/docker-compose.yml.j2",
    "repos/thrush/ansible/roles/app-thrush-mono/templates/deployment/**",
)

#: Later additions to the subset, in the order they were added. They are kept
#: apart from :data:`SUBSET` because IPv4 addresses are mapped in numeric order:
#: addresses that only these files bring are mapped after those of
#: :data:`SUBSET` (see :class:`sanitise.IpMapper`), so adding files never
#: changes a file that an earlier sync wrote. Append new entries at the end.
SUBSET_ADDITIONS: Tuple[str, ...] = (
    # Wave 5: the wren prod inventory, the app-wren-mono handlers, and the roles that
    # molecule prepare plays reference (closes the subset gaps of the reference sweep).
    "repos/wren/ansible/environments/prod/hosts.yml",
    "repos/wren/ansible/roles/app-wren-mono/handlers/main.yml",
    "golden/roles/{nginx,java21-jre}",
    "repos/falcon/ansible/roles/nginx",
)

_SYNTHETIC_HEADER = (
    "# Synthetic fixture written by tools/fixtures/sync.py: a detached worktree copy of\n"
    "# golden/roles/haproxy. Lookups from outside this worktree must never return it.\n"
)

#: Files of the synthetic detached worktree, relative to :data:`rules.WORKTREE_DIR`.
#: It duplicates the ``haproxy`` role name and the ``haproxy_stats_http_port``
#: variable with a value (9999) that differs from the real role (8404).
WORKTREE_FILES: Dict[str, str] = {
    ".git": rules.WORKTREE_GIT_LINK,
    "golden/roles/haproxy/defaults/main.yml": "---\n" + _SYNTHETIC_HEADER + "haproxy_stats_http_port: 9999\n",
    "golden/roles/haproxy/meta/argument_specs.yml": "---\n"
    + _SYNTHETIC_HEADER
    + "argument_specs:\n"
    "  main:\n"
    "    short_description: Worktree duplicate of the haproxy role.\n"
    "    options:\n"
    "      haproxy_stats_http_port:\n"
    "        type: int\n"
    "        description: Port of the HAProxy stats page.\n"
    "        default: 9999\n",
    "golden/roles/haproxy/tasks/main.yml": "---\n"
    + _SYNTHETIC_HEADER
    + "- name: Show the stats port\n"
    "  ansible.builtin.debug:\n"
    '    msg: "{{ haproxy_stats_http_port }}"\n',
}


class SyncError(Exception):
    """A problem that stops the sync before anything is swapped in."""


# ---------------------------------------------------------------------------
# Subset resolution
# ---------------------------------------------------------------------------


def expand_braces(pattern: str) -> List[str]:
    """Expand ``{a,b}`` alternatives (nesting allowed), preserving order and dropping duplicates."""
    m = re.search(r"\{([^{}]*)\}", pattern)
    if m is None:
        return [pattern]
    head, tail = pattern[: m.start()], pattern[m.end() :]
    out: List[str] = []
    for alt in m.group(1).split(","):
        for expanded in expand_braces(head + alt + tail):
            if expanded not in out:
                out.append(expanded)
    return out


def match_pattern(source: Path, pattern: str) -> List[Path]:
    """Paths under ``source`` matching one brace-free ``pattern`` (files or directories).

    Segments may use ``*``, ``?`` and ``[...]``. ``**`` is only allowed as the
    last segment and means "the directory itself, recursively". Symbolic links
    are never followed.
    """
    segs = [s for s in pattern.split("/") if s]
    if segs and segs[-1] == "**":
        segs = segs[:-1]
    if "**" in segs:
        raise ValueError(f"'**' is only supported as the last segment: {pattern}")
    current = [source]
    for seg in segs:
        nxt: List[Path] = []
        for base in current:
            if base.is_symlink() or not base.is_dir():
                continue
            if any(ch in seg for ch in "*?["):
                nxt.extend(sorted(p for p in base.iterdir() if fnmatch.fnmatchcase(p.name, seg)))
            elif (base / seg).exists() or (base / seg).is_symlink():
                nxt.append(base / seg)
        current = nxt
    return current if segs else []


@dataclass
class Plan:
    """The files a sync would copy, and everything it would skip."""

    files: List[str] = field(default_factory=list)
    #: The files named by the base subset (the others come only from additions).
    base: Set[str] = field(default_factory=set)
    missing: List[str] = field(default_factory=list)
    forbidden: List[Tuple[str, str]] = field(default_factory=list)
    junk: List[str] = field(default_factory=list)
    symlinks: List[str] = field(default_factory=list)

    def submodules(self) -> List[str]:
        """Names ``<n>`` of the ``repos/<n>`` submodule roots that receive files."""
        names = {rel.split("/")[1] for rel in self.files if rel.startswith("repos/") and rel.count("/") >= 2}
        return sorted(names)


def plan_copy(
    source: Path,
    subset: Sequence[str] = SUBSET,
    additions: Sequence[str] = SUBSET_ADDITIONS,
    names: Names = IDENTITY,
) -> Plan:
    """Resolve ``subset`` and then ``additions`` against ``source`` and classify every file they name.

    The rules use aliases; ``names`` maps them to the source repo's real names. Plan paths are source paths.

    Files that ``subset`` names are recorded in :attr:`Plan.base`, also when an addition names them too.
    """
    plan = Plan()
    seen = set()
    in_base = True

    def consider(path: Path) -> None:
        rel = path.relative_to(source).as_posix()
        if rel in seen:
            return
        seen.add(rel)
        if in_base:
            plan.base.add(rel)
        alias = names.to_alias(rel)
        if path.is_symlink():
            plan.symlinks.append(rel)
        elif rules.junk_reason(alias):
            plan.junk.append(rel)
        elif rules.forbidden_reason(alias):
            plan.forbidden.append((rel, rules.forbidden_reason(alias)))
        else:
            plan.files.append(rel)

    for entry in [*subset, None, *additions]:
        if entry is None:
            in_base = False
            continue
        for pattern in expand_braces(names.to_real(entry)):
            matches = match_pattern(source, pattern)
            if not matches:
                plan.missing.append(pattern)
            for path in matches:
                if path.is_dir() and not path.is_symlink():
                    for dirpath, dirnames, filenames in os.walk(path):
                        base = Path(dirpath)
                        kept = []
                        for d in sorted(dirnames):
                            sub = base / d
                            rel = sub.relative_to(source).as_posix()
                            if sub.is_symlink():
                                consider(sub)
                            elif d == ".git":
                                plan.forbidden.append((rel + "/", ".git directory"))
                            elif rules.junk_reason(names.to_alias(rel)):
                                plan.junk.append(rel + "/")
                            else:
                                kept.append(d)
                        dirnames[:] = kept
                        for f in sorted(filenames):
                            consider(base / f)
                else:
                    consider(path)
    plan.files.sort()
    return plan


# ---------------------------------------------------------------------------
# Build
# ---------------------------------------------------------------------------


@dataclass
class Entry:
    """One manifest row."""

    path: str
    source_lines: Optional[int]
    fixture_lines: int
    size: int
    changes: str


@dataclass
class Result:
    """Outcome of :func:`build`."""

    entries: List[Entry] = field(default_factory=list)
    binaries: List[str] = field(default_factory=list)
    totals: sanitise.Stats = field(default_factory=sanitise.Stats)
    mapped_addresses: int = 0

    @property
    def total_size(self) -> int:
        return sum(e.size for e in self.entries)

    def line_mismatches(self) -> List[Entry]:
        return [e for e in self.entries if e.source_lines is not None and e.source_lines != e.fixture_lines]

    def yaml_or_jinja(self) -> List[Entry]:
        return [e for e in self.entries if e.source_lines is not None and e.path.endswith(YAML_OR_JINJA_SUFFIXES)]


def _write(path: Path, data: bytes, mode: Optional[int] = None) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(data)
    if mode is not None:
        os.chmod(path, mode)


def write_synthetic(dest: Path, submodules: Iterable[str]) -> List[Entry]:
    """Write the synthetic submodule ``.git`` links and the detached worktree into ``dest``."""
    entries = []
    for name in submodules:
        rel = f"repos/{name}/.git"
        data = rules.submodule_git_link(name).encode()
        _write(dest / rel, data)
        entries.append(Entry(rel, None, 1, len(data), "synthetic"))
    for sub, text in WORKTREE_FILES.items():
        rel = f"{rules.WORKTREE_DIR}/{sub}"
        data = text.encode()
        _write(dest / rel, data)
        entries.append(Entry(rel, None, sanitise.count_lines(text), len(data), "synthetic"))
    return entries


def build(source: Path, staging: Path, plan: Plan, names: Names = IDENTITY) -> Result:
    """Sanitise every planned file from ``source`` into ``staging`` (real names mapped to aliases by ``names``, in
    paths and contents) and add the synthetic files."""
    result = Result()
    redacted: Dict[str, Tuple[str, str, sanitise.Stats, int]] = {}
    for source_rel in plan.files:
        src = source / source_rel
        rel = names.to_alias(source_rel)
        raw = src.read_bytes()
        try:
            text = raw.decode("utf-8")
        except UnicodeDecodeError:
            result.binaries.append(rel)
            continue
        try:
            out, stats = sanitise.redact_text(names.to_alias(text), salt_key=rel)
        except sanitise.SanitiseError as e:
            raise SyncError(f"{rel}: {e}") from None
        redacted[rel] = (text, out, stats, src.stat().st_mode & 0o777)

    addresses = set()
    later = set()
    for rel, (_, out, _, _) in redacted.items():
        found = sanitise.collect_ipv4(out) | sanitise.collect_ipv4(rel)
        if rel in plan.base:
            addresses |= found
        else:
            later |= found
    try:
        mapper = sanitise.IpMapper(addresses, later)
    except sanitise.SanitiseError as e:
        raise SyncError(str(e)) from None
    result.mapped_addresses = len(mapper)

    for rel, (text, out, stats, mode) in sorted(redacted.items()):
        final = sanitise.map_ips(out, mapper, stats)
        target = mapper.rewrite(rel, stats)  # host_vars directories may be named after an address
        data = final.encode("utf-8")
        _write(staging / target, data, mode)
        result.totals.add(stats)
        result.entries.append(
            Entry(target, sanitise.count_lines(text), sanitise.count_lines(final), len(data), stats.summary())
        )
    result.entries.extend(write_synthetic(staging, [names.to_alias(s) for s in plan.submodules()]))
    result.entries.sort(key=lambda e: e.path)
    return result


def write_manifest(path: Path, result: Result) -> None:
    """Write the reviewable manifest (paths, line counts, sizes, what changed; never values)."""
    lines = [
        "# Generated by tools/fixtures/sync.py - do not edit. One row per fixture file.",
        "# path\tsource_lines\tfixture_lines\tbytes\tchanges",
    ]
    for e in result.entries:
        src = "-" if e.source_lines is None else str(e.source_lines)
        lines.append(f"{e.path}\t{src}\t{e.fixture_lines}\t{e.size}\t{e.changes}")
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def rewrite_envelopes(dest: Path) -> Tuple[List[Tuple[Path, str]], sanitise.Stats, List[verify.Problem]]:
    """The vault pass alone over an existing fixture ``dest``, without reading the source repo.

    Returns the files whose text changes (with the new text), the vault counts and the problems
    :func:`verify.verify_text` finds in the new texts. Nothing is written here. Because
    :func:`sanitise.scrub_vault_text` depends only on each file's own structure and path, the new envelopes are the
    ones a full sync of the same source snapshot writes, so the manifest does not change.
    """
    changes: List[Tuple[Path, str]] = []
    totals = sanitise.Stats()
    problems: List[verify.Problem] = []
    for dirpath, dirnames, filenames in os.walk(dest):
        dirnames[:] = sorted(d for d in dirnames if d != ".git" and not rules.junk_reason(d))
        for name in sorted(filenames):
            path = Path(dirpath) / name
            rel = path.relative_to(dest).as_posix()
            if path.is_symlink() or name == ".git" or rules.junk_reason(rel) or rules.forbidden_reason(rel):
                continue
            try:
                text = path.read_bytes().decode("utf-8")
            except UnicodeDecodeError:
                continue
            if rules.VAULT_MARK not in text:
                continue
            try:
                out, stats = sanitise.scrub_vault_text(text, salt_key=rel)
            except sanitise.SanitiseError as e:
                raise SyncError(f"{rel}: {e}") from None
            totals.add(stats)
            problems.extend(verify.verify_text(rel, out))
            if out != text:
                changes.append((path, out))
    return changes, totals, problems


# ---------------------------------------------------------------------------
# Destination handling
# ---------------------------------------------------------------------------


def _is_within(child: Path, parent: Path) -> bool:
    try:
        child.relative_to(parent)
        return True
    except ValueError:
        return False


def check_locations(source: Path, dest: Path, force: bool) -> None:
    """Refuse destinations that overlap the source or hold something other than a previous sync."""
    if not source.is_dir():
        raise SyncError(f"source repo not found: {source}")
    if _is_within(dest, source) or _is_within(source, dest):
        raise SyncError("source and destination must not contain each other")
    if dest.exists() and not force:
        if not dest.is_dir():
            raise SyncError(f"destination is not a directory: {dest}")
        previous = (dest / rules.WORKTREE_DIR / ".git").is_file()
        if any(dest.iterdir()) and not previous:
            raise SyncError(f"destination is not empty and not a previous sync (use --force): {dest}")


def swap_in(staging: Path, dest: Path) -> None:
    """Replace ``dest`` with ``staging`` (both on the same file system)."""
    old = None
    if dest.exists():
        old = Path(tempfile.mkdtemp(prefix=".infra.old-", dir=dest.parent))
        old.rmdir()
        dest.rename(old)
    staging.rename(dest)
    if old is not None:
        shutil.rmtree(old)


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------


def _mb(size: int) -> str:
    return f"{size / (1024 * 1024):.2f} MB"


def report(plan: Plan, result: Result, problems: List[verify.Problem], out=sys.stdout) -> None:
    """Print the human-readable sync report (counts only; never a secret or a source address)."""
    t = result.totals
    yj = result.yaml_or_jinja()
    mismatches = result.line_mismatches()
    reasons: Dict[str, int] = {}
    for _, reason in plan.forbidden:
        reasons[reason] = reasons.get(reason, 0) + 1
    p = lambda s="": print(s, file=out)  # noqa: E731
    p("Ansibility fixture sync")
    p(f"  files written:        {len(result.entries)} "
      f"({len(result.entries) - sum(1 for e in result.entries if e.source_lines is None)} copied, "
      f"{sum(1 for e in result.entries if e.source_lines is None)} synthetic)")
    p(f"  total size:           {_mb(result.total_size)} (target < {_mb(SIZE_TARGET)})"
      + ("" if result.total_size < SIZE_TARGET else "  OVER TARGET"))
    p(f"  missing subset paths: {len(plan.missing)}")
    for m in plan.missing:
        p(f"    - {m}")
    p(f"  skipped forbidden:    {len(plan.forbidden)}"
      + (" (" + ", ".join(f"{k}: {v}" for k, v in sorted(reasons.items())) + ")" if reasons else ""))
    p(f"  skipped junk:         {len(plan.junk)}")
    p(f"  skipped symlinks:     {len(plan.symlinks)}")
    p(f"  skipped binary:       {len(result.binaries)}")
    for b in result.binaries:
        p(f"    - {b}")
    p(f"  vault blocks:         {t.vault_blocks} ({t.vault_payload_lines} payload lines -> synthetic 1.1 envelope)")
    p(f"  vault_* redacted:     {t.vault_keys_redacted}")
    p(f"  secret keys redacted: {t.secret_keys_redacted}")
    p(f"  IPv4 -> TEST-NET:     {result.mapped_addresses} distinct addresses, {t.ip_occurrences} occurrences")
    copied = sum(1 for e in result.entries if e.source_lines is not None)
    p(f"  line counts:          {len(yj)} YAML/j2 files (of {copied} copied), "
      + ("all equal to source" if not mismatches else f"{len(mismatches)} MISMATCHES"))
    for e in mismatches:
        p(f"    - {e.path}: {e.source_lines} -> {e.fixture_lines}")
    p(f"  verify:               {'OK' if not problems else f'{len(problems)} PROBLEMS'}")
    for pr in problems[:50]:
        p(f"    - {pr}")


def run(argv: Optional[Sequence[str]] = None, out=sys.stdout) -> int:
    """Entry point; returns the process exit code."""
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    env_source = os.environ.get("ANSIBLE_INFRA_REPO")
    ap.add_argument("--source", type=Path, default=Path(env_source) if env_source else None,
                    help="infra repo to read (default: $ANSIBLE_INFRA_REPO)")
    ap.add_argument("--names", type=Path, default=None,
                    help=f"real-name mapping to apply (aliases in rules and fixture), e.g. {LOCAL_MAPPING}; default: none")
    ap.add_argument("--dest", type=Path, default=DEFAULT_DEST, help="fixture directory to replace (default: %(default)s)")
    ap.add_argument("--manifest", type=Path, default=None,
                    help="manifest to write (default: tools/fixtures/manifest.tsv when --dest is the default)")
    ap.add_argument("--dry-run", action="store_true", help="resolve the subset and print the plan; write nothing")
    ap.add_argument("--list", action="store_true", help="with --dry-run: also list every file")
    ap.add_argument("--links-only", action="store_true",
                    help="only (re)write the synthetic .git files and worktree into an existing --dest")
    ap.add_argument("--envelopes-only", action="store_true",
                    help="only rewrite the vault envelopes of an existing --dest in place (reads no source repo; "
                         "the result equals a full sync of the same snapshot, so the manifest stays valid)")
    ap.add_argument("--force", action="store_true", help="replace a non-empty --dest that is not a previous sync")
    args = ap.parse_args(argv)
    names = load_names(args.names)
    dest = args.dest.resolve()
    source = args.source.resolve() if args.source is not None else None
    manifest = args.manifest or (DEFAULT_MANIFEST if dest == DEFAULT_DEST.resolve() else None)

    try:
        if args.links_only:
            if not dest.is_dir():
                raise SyncError(f"destination not found: {dest}")
            repos = sorted(p.name for p in (dest / "repos").iterdir() if p.is_dir()) if (dest / "repos").is_dir() else []
            for e in write_synthetic(dest, repos):
                print(f"wrote {e.path}", file=out)
            return 0

        if args.envelopes_only:
            if not (dest / rules.WORKTREE_DIR / ".git").is_file():
                raise SyncError(f"destination is not a previous sync: {dest}")
            changes, totals, problems = rewrite_envelopes(dest)
            print(f"vault blocks: {totals.vault_blocks} ({totals.vault_payload_lines} payload lines), "
                  f"{len(changes)} files to rewrite", file=out)
            if problems:
                for pr in problems[:50]:
                    print(f"  - {pr}", file=out)
                print("envelopes FAILED: destination left unchanged", file=out)
                return 1
            for path, text in changes:
                mode = path.stat().st_mode & 0o777
                _write(path, text.encode("utf-8"), mode)
            print(f"fixtures: {dest}", file=out)
            return 0

        if source is None:
            raise SyncError("no source repo: pass --source or set ANSIBLE_INFRA_REPO")
        check_locations(source, dest, args.force)
        plan = plan_copy(source, names=names)
        if args.dry_run:
            print(f"would copy {len(plan.files)} files for submodules {', '.join(plan.submodules())}", file=out)
            print(f"missing: {len(plan.missing)}, forbidden: {len(plan.forbidden)}, junk: {len(plan.junk)}", file=out)
            for m in plan.missing:
                print(f"  missing {m}", file=out)
            if args.list:
                for rel in plan.files:
                    print(f"  {rel}", file=out)
                for rel, reason in plan.forbidden:
                    print(f"  skip {rel} ({reason})", file=out)
            return 0

        dest.parent.mkdir(parents=True, exist_ok=True)
        staging = Path(tempfile.mkdtemp(prefix=".infra.staging-", dir=dest.parent))
        os.chmod(staging, 0o755)  # mkdtemp creates 0700
        try:
            result = build(source, staging, plan, names)
            problems = verify.verify_tree(staging)
            report(plan, result, problems, out)
            if problems or result.line_mismatches():
                print("sync FAILED: destination left unchanged", file=out)
                return 1
            swap_in(staging, dest)
            staging = None
        finally:
            if staging is not None and staging.exists():
                shutil.rmtree(staging)
        if manifest is not None:
            write_manifest(manifest, result)
            print(f"manifest: {manifest}", file=out)
        print(f"fixtures: {dest}", file=out)
        return 0
    except SyncError as e:
        print(f"sync FAILED: {e}", file=out)
        return 2


if __name__ == "__main__":
    sys.exit(run())
