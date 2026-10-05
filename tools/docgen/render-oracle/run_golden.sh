#!/bin/sh
# Runs one render-oracle case with the real tools and leaves the raw results for finalize.py.
#
#   run_golden.sh <case-dir> <raw-dir> [ENV=VALUE ...]     (extra environment for every version, e.g. PYTHONHASHSEED=1)
#
# Versions: $RENDER_ORACLE_VERSIONS, labels of versions.json (default: the primary ones, "2.21 2.18").
#   local   the local ansible-playbook ($LOCAL_ANSIBLE_PLAYBOOK, default: the one on PATH), which must report the core
#           that versions.json names. It runs in <raw-dir>/work.<label>, a copy of <case>/input, with the caller's
#           ANSIBLE_* variables removed, a private ANSIBLE_HOME and ANSIBLE_CONFIG set to the case's own ansible.cfg
#           or to an empty file.
#   docker  the image that versions.json names, with --network none and no mounts: the input is piped in as a tar
#           stream and the results come back as one on stdout.
# Every run has stdin /dev/null, the json callback and explicit gathering; the inventories use ansible_connection=local.
# Raw results per version: <raw-dir>/<label>/{log.txt, err.txt, rc.txt, version.txt, workdir.txt, out/}.
set -u
T=$(cd "$(dirname "$0")" && pwd)
[ $# -ge 2 ] || { echo "usage: $0 <case-dir> <raw-dir> [ENV=VALUE ...]" >&2; exit 2; }
CASE=$(cd "$1" && pwd); RAW=$2; shift 2
EXTRA="$*"
[ -d "$CASE/input" ] || { echo "$CASE has no input/" >&2; exit 2; }
mkdir -p "$RAW"; RAW=$(cd "$RAW" && pwd)
VERSIONS=${RENDER_ORACLE_VERSIONS:-$(python3 -c 'import json,sys; print(" ".join(json.load(open(sys.argv[1]))["primary"]))' "$T/versions.json")}
PLAYBOOK=${LOCAL_ANSIBLE_PLAYBOOK:-ansible-playbook}
ENVV="ANSIBLE_NOCOLOR=1 ANSIBLE_FORCE_COLOR=0 ANSIBLE_RETRY_FILES_ENABLED=0 ANSIBLE_LOCALHOST_WARNING=0 ANSIBLE_INVENTORY_UNPARSED_WARNING=0 ANSIBLE_STDOUT_CALLBACK=json ANSIBLE_PYTHON_INTERPRETER=auto_silent ANSIBLE_GATHERING=explicit ANSIBLE_DEPRECATION_WARNINGS=1"

# "runner image core" of a version label.
info() {
  python3 -c 'import json,sys; v=json.load(open(sys.argv[1]))["versions"][sys.argv[2]]; print(v["runner"], v.get("image", "-"), v["core"])' \
    "$T/versions.json" "$1"
}

run_local() { # label core
  out=$RAW/$1; wd=$RAW/work.$1
  rm -rf "$out" "$wd"; mkdir -p "$out" "$wd"
  cp -R "$CASE/input/." "$wd/"; mkdir -p "$wd/out" "$wd/.ah"
  if [ -f "$wd/ansible.cfg" ]; then cfg=$wd/ansible.cfg; else cfg=$wd/.ah/empty.cfg; : >"$cfg"; fi
  unset_ansible=$(env | sed -n 's/^\(ANSIBLE_[A-Za-z0-9_]*\)=.*/-u \1/p')
  # shellcheck disable=SC2086
  env $unset_ansible "$PLAYBOOK" --version </dev/null 2>/dev/null | head -1 >"$out/version.txt"
  grep -q "core $2]" "$out/version.txt" || { echo "local $PLAYBOOK is '$(cat "$out/version.txt")', expected core $2" >&2; exit 1; }
  # shellcheck disable=SC2086
  ( cd "$wd" && env $unset_ansible $ENVV ANSIBLE_CONFIG="$cfg" ANSIBLE_HOME="$wd/.ah" ANSIBLE_LOCAL_TEMP="$wd/.ah/tmp" \
      ANSIBLE_REMOTE_TEMP="$wd/.ah/rtmp" $EXTRA "$PLAYBOOK" -i inventory site.yml </dev/null >"$out/log.txt" 2>"$out/err.txt"
    echo "rc=$?" >"$out/rc.txt" )
  cp -R "$wd/out" "$out/out"; echo "$wd" >"$out/workdir.txt"
}

run_docker() { # label image core
  out=$RAW/$1; rm -rf "$out"; mkdir -p "$out"
  tar -C "$CASE/input" -cf - . | docker run --rm -i --network none --entrypoint sh "$2" -c "
    mkdir -p /ansibility_golden_wd && cd /ansibility_golden_wd && tar xf - >/dev/null 2>&1 && mkdir -p out .ah &&
    ansible-playbook --version </dev/null 2>/dev/null | head -1 >/tmp/version.txt;
    env $ENVV ANSIBLE_HOME=/ansibility_golden_wd/.ah ANSIBLE_LOCAL_TEMP=/ansibility_golden_wd/.ah/tmp ANSIBLE_REMOTE_TEMP=/ansibility_golden_wd/.ah/rtmp $EXTRA \
      ansible-playbook -i inventory site.yml </dev/null >/tmp/log.txt 2>/tmp/err.txt; echo rc=\$? >/tmp/rc.txt;
    mkdir -p /res && cp /tmp/log.txt /tmp/err.txt /tmp/rc.txt /tmp/version.txt /res/ && cp -R out /res/out && tar -C /res -cf - ." \
    | tar -C "$out" -xf -
  grep -q "core $3]" "$out/version.txt" 2>/dev/null || { echo "image $2 is not ansible-core $3" >&2; exit 1; }
  echo /ansibility_golden_wd >"$out/workdir.txt"
}

line=""
for label in $VERSIONS; do
  inf=$(info "$label") || exit 1
  # shellcheck disable=SC2086
  set -- $inf
  case "$1" in
    local) run_local "$label" "$3" ;;
    docker) run_docker "$label" "$2" "$3" ;;
    *) echo "unknown runner $1 for $label" >&2; exit 2 ;;
  esac
  line="$line $label $(cat "$RAW/$label/rc.txt")"
done
echo "$(basename "$CASE"):$line"
