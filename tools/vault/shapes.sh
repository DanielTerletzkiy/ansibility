#!/usr/bin/env bash
# Regenerates the wrapped-shape vectors of ANS-V107 "Not a whole-file vault" (plan amendment R21, D159) in
# semantics/src/test/resources/vault/shapes/ from the committed vector v01 (password pw1 of tools/vault/SYNTHETIC.md),
# and records what ansible-core's own readers do with each shape and with its unwrapped whole-file vault.
#
#   tools/vault/shapes.sh
#
#   2.21.4: the local Homebrew ansible-core (LOCAL_PYTHON=... to override)
#   2.18.8: the docgen image (IMAGE=... to override), --network none, no mounts, script and input on stdin
#           (DEV.md rule 1)
#
# Writes shapes/<file> (each shape), shapes/<id>.unwrapped (what the Convert fix must write) and shapes/shapes.json
# (the table and both oracles). Never edit them by hand.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
out="$here/../../semantics/src/test/resources/vault"
image="${IMAGE:-ansibility-docgen:2.18.8}"
local_python="${LOCAL_PYTHON:-/opt/homebrew/Cellar/ansible/14.4.0/libexec/bin/python}"

"$local_python" -c 'import sys, ansible.release as r; sys.exit(r.__version__ != "2.21.4")' \
    || { echo "LOCAL_PYTHON is not ansible-core 2.21.4" >&2; exit 1; }

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

"$local_python" "$here/shapes.py" build "$out" "$work/input.json"
"$local_python" "$here/shapes.py" oracle "$work/input.json" > "$work/oracle-2.21.json"
{
    printf 'SHAPES_INPUT = "%s"\n' "$(base64 < "$work/input.json" | tr -d '\n')"
    cat "$here/shapes.py"
} | docker run --rm -i --network none --entrypoint sh "$image" -c 'python3 - oracle' > "$work/oracle-2.18.json"
"$local_python" "$here/shapes.py" merge "$out" "$work/input.json" "$work/oracle-2.21.json" "$work/oracle-2.18.json"

ls -l "$out/shapes" >&2
