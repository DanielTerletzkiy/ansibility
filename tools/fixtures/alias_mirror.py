#!/usr/bin/env python3
"""Write an aliased mirror of the infra repo for the opt-in corpus tests.

The corpus tests (``ANSIBLE_INFRA_REPO=… ./gradlew …``) pin neutral aliases (``repos/falcon/…``), like the rest of
the repository. This tool copies the infra repo with only the real-name mapping applied (paths and contents, see
``names.py``) and nothing else changed, so line numbers, vault envelopes and counts stay those of the real repo.
Point ``ANSIBLE_INFRA_REPO`` at the mirror::

    python3 tools/fixtures/alias_mirror.py --source /path/to/your/infra-repo \\
        --names tools/fixtures/local/names.json --dest ~/.cache/ansibility/infra-mirror
    ANSIBLE_INFRA_REPO=~/.cache/ansibility/infra-mirror ./gradlew :plugin:test --tests '*CorpusTest*'

Secret files (``rules.forbidden_reason``: password and vault-pass files, ``.env*``, keys, ``files/ssl``,
``files/ssh``) are never read: the mirror gets an empty placeholder under the aliased name, so tests that only check
that such a file exists keep working. ``.git`` directories are skipped; ``.git`` link files (submodules, worktrees)
are copied with the mapping applied. Junk (caches, IDE state) is skipped. The destination is replaced as a whole.
"""
import argparse
import os
import shutil
import sys
from pathlib import Path
from typing import Optional, Sequence

sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parent))

import rules  # noqa: E402
from names import load as load_names  # noqa: E402

MARKER = ".ansibility-alias-mirror"


def mirror(source: Path, dest: Path, names) -> dict:
    counts = {"copied": 0, "placeholders": 0, "binary": 0}
    for dirpath, dirnames, filenames in os.walk(source):
        base = Path(dirpath)
        rel_dir = base.relative_to(source).as_posix()
        dirnames[:] = sorted(
            d for d in dirnames
            if d != ".git" and not rules.junk_reason(f"{rel_dir}/{d}" if rel_dir != "." else d)
        )
        for name in sorted(filenames):
            path = base / name
            rel = path.relative_to(source).as_posix()
            if rules.junk_reason(rel) or path.is_symlink():
                continue
            target = dest / names.to_alias(rel)
            target.parent.mkdir(parents=True, exist_ok=True)
            reason = rules.forbidden_reason(rel)
            if reason and reason != ".git link":
                target.write_bytes(b"")  # existence only: the content is never read
                counts["placeholders"] += 1
                continue
            data = path.read_bytes()
            try:
                text = data.decode("utf-8")
            except UnicodeDecodeError:
                shutil.copyfile(path, target)
                counts["binary"] += 1
                continue
            target.write_text(names.to_alias(text), encoding="utf-8")
            shutil.copymode(path, target)
            counts["copied"] += 1
    (dest / MARKER).write_text("aliased mirror written by tools/fixtures/alias_mirror.py\n", encoding="utf-8")
    return counts


def run(argv: Optional[Sequence[str]] = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--source", type=Path, required=True, help="the real infra repo (read only)")
    ap.add_argument("--names", type=Path, required=True, help="the real-name mapping (tools/fixtures/local/names.json)")
    ap.add_argument("--dest", type=Path, required=True, help="mirror directory to (re)write")
    args = ap.parse_args(argv)
    source, dest = args.source.resolve(), args.dest.expanduser().resolve()
    if not source.is_dir():
        print(f"source not found: {source}", file=sys.stderr)
        return 2
    if dest == source or source in dest.parents or dest in source.parents:
        print("the mirror must lie outside the source repo", file=sys.stderr)
        return 2
    if dest.exists():
        if not (dest / MARKER).is_file():
            print(f"refusing to replace {dest}: not a previous mirror", file=sys.stderr)
            return 2
        shutil.rmtree(dest)
    dest.mkdir(parents=True)
    counts = mirror(source, dest, load_names(args.names))
    print(f"mirror: {dest} ({counts['copied']} text files, {counts['binary']} binary, {counts['placeholders']} secret placeholders)")
    return 0


if __name__ == "__main__":
    sys.exit(run())
