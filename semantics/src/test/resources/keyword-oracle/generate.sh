#!/usr/bin/env bash
# Regenerates the keyword oracle cases used by semantics' KeywordOracleTest from real ansible-core.
#   2.18.8: the target repo's pinned image, script on stdin, no network, no mounts (DEV.md rule 1)
#   2.21.4: the local Homebrew ansible
# Override with IMAGE=... / LOCAL_PYTHON=... . Never edit the JSON by hand.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
image="${IMAGE:-ansible-infrastructure-ansible-lint:latest}"
local_python="${LOCAL_PYTHON:-/opt/homebrew/Cellar/ansible/14.4.0/libexec/bin/python}"

docker run --rm -i --network none --entrypoint sh "$image" -c 'python3 - 2>/dev/null' \
    < "$here/gen_keywords.py" > "$here/2.18.8.json.tmp"
grep -q '"ansible_core": "2.18.8"' "$here/2.18.8.json.tmp" || { echo "image is not ansible-core 2.18.8" >&2; exit 1; }
mv "$here/2.18.8.json.tmp" "$here/2.18.8.json"

"$local_python" "$here/gen_keywords.py" 2>/dev/null > "$here/2.21.4.json.tmp"
grep -q '"ansible_core": "2.21.4"' "$here/2.21.4.json.tmp" || { echo "local python is not ansible-core 2.21.4" >&2; exit 1; }
mv "$here/2.21.4.json.tmp" "$here/2.21.4.json"

wc -l "$here/2.18.8.json" "$here/2.21.4.json"
