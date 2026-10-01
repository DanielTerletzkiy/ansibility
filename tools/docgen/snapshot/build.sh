#!/usr/bin/env bash
# Regenerates the bundled documentation snapshots in plugin/src/main/resources/ansible-data/.
#
#   tools/docgen/snapshot/build.sh [pinned|latest|all]      (default: all)
#
# pinned  core-2.18.8.json.gz: docker build (needs network) of python:3.13-alpine + ansible-core 2.18.8 + the
#         collections in requirements.yml, then the generator runs with --network none and no mounts; the gzipped
#         snapshot comes back on stdout.
# latest  core-latest.json.gz: the local Homebrew Ansible (ansible-core 2.21.x), with ANSIBLE_COLLECTIONS_PATH set to
#         its own site-packages so the older copies in ~/.ansible/collections do not shadow the bundled collections.
#
# Environment overrides: INFRA_REPO (read-only; used to check the pins), LOCAL_ANSIBLE_PYTHON, LOCAL_SITE_PACKAGES.
# Nothing is written outside this repository's ansible-data directory and a temp directory.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../../.." && pwd)"
OUT="$ROOT/plugin/src/main/resources/ansible-data"
INFRA_REPO="${INFRA_REPO:-${ANSIBLE_INFRA_REPO:-}}"
LOCAL_ANSIBLE_PYTHON="${LOCAL_ANSIBLE_PYTHON:-/opt/homebrew/Cellar/ansible/14.4.0/libexec/bin/python}"
LOCAL_SITE_PACKAGES="${LOCAL_SITE_PACKAGES:-$("$LOCAL_ANSIBLE_PYTHON" -c 'import ansible, os; print(os.path.dirname(os.path.dirname(ansible.__file__)))' 2>/dev/null || true)}"
IMAGE="ansibility-docgen:2.18.8"
TARGET="${1:-all}"

mkdir -p "$OUT"

pins() { grep -E '^\s*-?\s*(name|version):' "$1" | tr -d ' "-' | paste - - ; }

check_pins() {
  local molecule="$INFRA_REPO/golden/docker/ansible-molecule/requirements.yml"
  local lint="$INFRA_REPO/golden/docker/ansible-lint/requirements.yml"
  if [[ -f "$molecule" && -f "$lint" ]]; then
    if ! diff <(pins "$HERE/requirements.yml") <(pins "$molecule") >/dev/null || ! diff <(pins "$molecule") <(pins "$lint") >/dev/null; then
      echo "requirements.yml differs from the pins in $INFRA_REPO/golden/docker/*/requirements.yml" >&2
      exit 1
    fi
    echo "collection pins match $INFRA_REPO/golden/docker/{ansible-molecule,ansible-lint}/requirements.yml" >&2
  else
    echo "note: $INFRA_REPO not found; pins not re-checked" >&2
  fi
}

pinned() {
  check_pins
  docker build -t "$IMAGE" "$HERE"
  docker run --rm --network none "$IMAGE" --gzip --label "ansible-core 2.18.8 + pinned collections (bundled)" \
    > "$OUT/core-2.18.8.json.gz.tmp"
  mv "$OUT/core-2.18.8.json.gz.tmp" "$OUT/core-2.18.8.json.gz"
  ls -l "$OUT/core-2.18.8.json.gz" >&2
}

latest() {
  local work
  work="$(mktemp -d)"
  trap 'rm -rf "$work"' RETURN
  local version
  version="$("$LOCAL_ANSIBLE_PYTHON" -c 'from ansible.release import __version__; print(__version__)')"
  (cd "$work" && ANSIBLE_COLLECTIONS_PATH="$LOCAL_SITE_PACKAGES" ANSIBLE_NOCOLOR=1 \
    "$LOCAL_ANSIBLE_PYTHON" "$HERE/generate.py" --gzip --label "ansible-core $version + bundled collections (local)" \
    --output "$OUT/core-latest.json.gz" </dev/null)
  ls -l "$OUT/core-latest.json.gz" >&2
}

case "$TARGET" in
  pinned) pinned ;;
  latest) latest ;;
  all) pinned; latest ;;
  *) echo "usage: $0 [pinned|latest|all]" >&2; exit 2 ;;
esac
