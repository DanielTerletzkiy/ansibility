#!/bin/sh
# Regenerates render-oracle case 15 (Python re versus java.util.regex) from input/cases.json:
#   - the Python of the local ansible-core 2.21.4 ($LOCAL_ANSIBLE_PYTHON, default: the Homebrew one),
#   - the Python of the 2.18.8 image (docker --network none, no mounts; harness and cases piped in on stdin),
#   - a JDK 25 java ($JAVA25, or the first argument, default: java on PATH), running JRegex.java as a source file,
# writing expected/python-<version>.json, expected/java-<version>.json and, through compare.py, comparison.json.
#   regen.sh [java]
set -eu
D=$(cd "$(dirname "$0")" && pwd)
C=$(cd "$D/../../../../semantics/src/test/resources/render-oracle/15_regex_python_vs_java" && pwd)
PY=${LOCAL_ANSIBLE_PYTHON:-/opt/homebrew/Cellar/ansible/14.4.0/libexec/bin/python}
JAVA=${1:-${JAVA25:-java}}
IMAGE=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["versions"]["2.18"]["image"])' "$D/../versions.json")
WORK=$(mktemp -d "${TMPDIR:-/tmp}/ansibility-render-oracle-regex.XXXXXX")
trap 'rm -rf "$WORK"' EXIT

"$PY" "$D/py_regex.py" "$C/input/cases.json" </dev/null >"$WORK/py-local.json"
{ cat "$D/py_regex.py"; printf '\n# --cases--\n'; cat "$C/input/cases.json"; } | docker run --rm -i --network none --entrypoint sh "$IMAGE" -c '
  cat > /tmp/in; sed "/^# --cases--$/,\$d" /tmp/in > /tmp/p.py; sed "1,/^# --cases--$/d" /tmp/in > /tmp/c.json
  python3 /tmp/p.py /tmp/c.json </dev/null' >"$WORK/py-image.json"
"$JAVA" "$D/JRegex.java" "$C/input/cases.json" </dev/null >"$WORK/java.json"

name() { python3 -c 'import json,sys; d=json.load(open(sys.argv[1])); print(sys.argv[2] + "-" + d[sys.argv[2]] + ".json")' "$1" "$2"; }
rm -f "$C"/expected/python-*.json "$C"/expected/java-*.json
for f in py-local py-image; do cp "$WORK/$f.json" "$C/expected/$(name "$WORK/$f.json" python)"; done
cp "$WORK/java.json" "$C/expected/$(name "$WORK/java.json" java)"
python3 "$D/compare.py" "$C/input/cases.json" "$WORK/py-local.json" "$WORK/py-image.json" "$WORK/java.json" \
  >"$C/expected/comparison.json"
ls "$C/expected"
