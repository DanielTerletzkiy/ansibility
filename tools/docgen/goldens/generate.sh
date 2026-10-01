#!/usr/bin/env bash
# Regenerates the golden tables used by semantics' GoldenTablesTest from real ansible-core.
#   2.18.8: the target repo's pinned image, script on stdin, no network, no mounts (DEV.md rule 1)
#   2.21.4: the local Homebrew ansible
# Override with IMAGE=... / LOCAL_PYTHON=... . Never edit the JSON by hand.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
out="$here/../../../semantics/src/test/resources/goldens"
image="${IMAGE:-ansibility-docgen:2.18.8}"
local_python="${LOCAL_PYTHON:-/opt/homebrew/Cellar/ansible/14.4.0/libexec/bin/python}"

mkdir -p "$out"

docker run --rm -i --network none --entrypoint sh "$image" -c 'python3 -' \
    < "$here/gen_goldens.py" > "$out/2.18.8.json.tmp"
grep -q '"ansible_core": "2.18.8"' "$out/2.18.8.json.tmp" || { echo "image is not ansible-core 2.18.8" >&2; exit 1; }
mv "$out/2.18.8.json.tmp" "$out/2.18.8.json"

"$local_python" "$here/gen_goldens.py" > "$out/2.21.4.json.tmp"
grep -q '"ansible_core": "2.21.4"' "$out/2.21.4.json.tmp" || { echo "local python is not ansible-core 2.21.4" >&2; exit 1; }
mv "$out/2.21.4.json.tmp" "$out/2.21.4.json"

wc -l "$out/2.18.8.json" "$out/2.21.4.json"
