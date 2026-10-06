#!/usr/bin/env bash
# Regenerates the synthetic Ansible Vault vectors and oracle tables used by semantics.vault's tests
# (semantics/src/test/resources/vault/), from the passwords registered in tools/vault/SYNTHETIC.md.
#
#   tools/vault/vectors.sh             keep the committed envelopes, recompute every table
#   tools/vault/vectors.sh --fresh     new envelopes from the local ansible-vault 2.21.4 (random salts, except v10)
#   tools/vault/vectors.sh --seed DIR  take the envelopes from DIR/raw, then recompute every table
#
#   2.21.4: the local Homebrew ansible-core (LOCAL_PYTHON=... to override)
#   2.18.8: the docgen image (IMAGE=... to override), --network none, no mounts, script and input on stdin
#           (DEV.md rule 1)
#
# Writes raw/ (the exact ansible-vault outputs), vectors/vNN.json, index.json, multivault-fixture.expected.json,
# tol-2.18.json / tol-2.21.json (envelope tolerance and multi-vault matching) and config-2.18.json /
# config-2.21.json (secret order, path resolution, strip rules, scripts, encrypt id, edit). Never edit them by hand.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
out="$here/../../semantics/src/test/resources/vault"
image="${IMAGE:-ansibility-docgen:2.18.8}"
local_python="${LOCAL_PYTHON:-/opt/homebrew/Cellar/ansible/14.4.0/libexec/bin/python}"

mode=()
case "${1:-}" in
    "") ;;
    --fresh) mode=(--fresh) ;;
    --seed) mode=(--seed "$(cd "${2:?--seed needs a directory}" && pwd)") ;;
    *) sed -n '2,9p' "$0" >&2; exit 2 ;;
esac

"$local_python" -c 'import sys, ansible.release as r; sys.exit(r.__version__ != "2.21.4")' \
    || { echo "LOCAL_PYTHON is not ansible-core 2.21.4" >&2; exit 1; }

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
mkdir -p "$out"

"$local_python" "$here/vaultgen.py" vectors "$here/SYNTHETIC.md" "$out" "$work/input.json" ${mode[@]+"${mode[@]}"}

"$local_python" "$here/vaultgen.py" oracle "$work/input.json" > "$work/oracle-2.21.json"

{
    printf 'VAULTGEN_INPUT = "%s"\n' "$(base64 < "$work/input.json" | tr -d '\n')"
    cat "$here/vaultgen.py"
} | docker run --rm -i --network none --entrypoint sh "$image" -c 'python3 - oracle' > "$work/oracle-2.18.json"

"$local_python" "$here/vaultgen.py" split "$work/oracle-2.21.json" "$out" 2.21.4
"$local_python" "$here/vaultgen.py" split "$work/oracle-2.18.json" "$out" 2.18.8

ls -l "$out" "$out/raw" "$out/vectors" | sed -n '1,200p' >&2
