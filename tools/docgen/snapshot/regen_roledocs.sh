#!/usr/bin/env bash
# Refreshes the expected output of the ArgSpecParser parity test in semantics/src/test/resources:
#   argspecs/<role>/meta/argument_specs.yml (or meta/main.yml)  synthetic role specs written for the test (edited by hand)
#   roledocs/<role>.json  `ansible-doc -t role -r argspecs <role> -j`, unmodified except that "path" is made relative
#                         to the repository root (semantics/src/test/resources/argspecs/<role>)
# Only the synthetic roles are read: collections are switched off so that no installed role of the same name shows up.
# Environment override: ANSIBLE_DOC (the committed fixtures come from ansible-core 2.21.4).
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../../.." && pwd)"
ANSIBLE_DOC="${ANSIBLE_DOC:-ansible-doc}"
REL_SPECS="semantics/src/test/resources/argspecs"
SPECS="$ROOT/$REL_SPECS"
DOCS="$ROOT/semantics/src/test/resources/roledocs"

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
mkdir -p "$work/no-collections" "$work/out" "$DOCS"
cd "$work"
count=0
for dir in "$SPECS"/*/; do
  role="$(basename "$dir")"
  if ! ANSIBLE_NOCOLOR=1 ANSIBLE_COLLECTIONS_PATH="$work/no-collections" ANSIBLE_COLLECTIONS_SCAN_SYS_PATH=false \
      ANSIBLE_ROLES_PATH="$SPECS" "$ANSIBLE_DOC" -t role -r "$SPECS" "$role" -j </dev/null >"$work/$role.raw.json" 2>>"$work/stderr.log"; then
    cat "$work/stderr.log" >&2
    exit 1
  fi
  python3 - "$work/$role.raw.json" "$work/out/$role.json" "$role" "$SPECS/$role" "$REL_SPECS/$role" <<'PY'
import json, os, sys

src, dst, role, abs_path, rel_path = sys.argv[1:]
text = open(src, encoding="utf-8").read()
doc = json.loads(text)
if list(doc) != [role]:
    sys.exit(f"{role}: expected exactly this role in the ansible-doc output, got {sorted(doc)}")
path = doc[role]["path"]
if os.path.realpath(path) != os.path.realpath(abs_path):
    sys.exit(f"{role}: ansible-doc read the role from {path}, not from {abs_path}")
old, new = '"path": ' + json.dumps(path), '"path": ' + json.dumps(rel_path)
if text.count(old) != 1:
    sys.exit(f"{role}: expected one path entry in the ansible-doc output")
with open(dst, "w", encoding="utf-8") as out:
    out.write(text.replace(old, new))
PY
  count=$((count + 1))
done
rm -f "$DOCS"/*.json
cp "$work/out/"*.json "$DOCS/"
echo "refreshed $count roles ($("$ANSIBLE_DOC" --version </dev/null 2>/dev/null | head -1))" >&2
