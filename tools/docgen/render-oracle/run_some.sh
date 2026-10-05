#!/bin/sh
# Regenerates the expected outputs of the named render-oracle cases with the real tools, then their case.json.
# Inputs are not rebuilt here (build_inputs.py writes them).
#
#   run_some.sh <case> ...
#
# Versions: $RENDER_ORACLE_VERSIONS (default: the primary 2.21 and 2.18, which rebuild expected/). The extra versions
# of spike S-R1 are laid over the committed primary data: RENDER_ORACLE_VERSIONS="2.19 2.20" run_some.sh <case>.
# Per case: 04n runs with ANSIBLE_JINJA2_NATIVE=True; 14 runs twice, with PYTHONHASHSEED=1 and =2, into
# expected_hashseed_1/ and expected_hashseed_2/; 15 runs regex/regen.sh (primary versions only; $JAVA25 = a JDK 25 java).
# Raw runs go to a temp directory ($TMPDIR) that is removed afterwards.
set -u
T=$(cd "$(dirname "$0")" && pwd)
CASES=$(cd "$T/../../../semantics/src/test/resources/render-oracle" && pwd)
PRIMARY=$(python3 -c 'import json,sys; print(" ".join(json.load(open(sys.argv[1]))["primary"]))' "$T/versions.json")
VERSIONS=${RENDER_ORACLE_VERSIONS:-$PRIMARY}
export RENDER_ORACLE_VERSIONS="$VERSIONS"
status=0
run() { # case-dir raw expected-name [ENV=VALUE ...]
  c=$1; raw=$2; exp=$3; shift 3
  "$T/run_golden.sh" "$c" "$raw" "$@" && python3 "$T/finalize.py" "$c" "$raw" --expected "$exp" || status=1
}
for n in "$@"; do
  c="$CASES/$n"
  [ -d "$c/input" ] || { echo "no case $n under $CASES" >&2; exit 2; }
  raw=$(mktemp -d "${TMPDIR:-/tmp}/ansibility-render-oracle.XXXXXX")
  case "$n" in
    15_*)
      if [ "$VERSIONS" = "$PRIMARY" ]; then "$T/regex/regen.sh" || status=1; else echo "$n: per Python, not per core; skipped"; fi ;;
    04n_*) run "$c" "$raw" expected ANSIBLE_JINJA2_NATIVE=True ;;
    14_*) for seed in 1 2; do run "$c" "$raw/seed$seed" "expected_hashseed_$seed" PYTHONHASHSEED=$seed; done ;;
    *) run "$c" "$raw" expected ;;
  esac
  rm -rf "$raw"
done
python3 "$T/write_case_json.py" "$@" || status=1
exit $status
