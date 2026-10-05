#!/bin/sh
# Builds the docker images of versions.json that do not exist yet (spike S-R1: the 2.19.x and 2.20.x lines), from the
# doc-snapshot Dockerfile (tools/docgen/snapshot) with its ANSIBLE_CORE_VERSION build argument. Only the build uses
# the network; run_golden.sh always runs the images with --network none.
#   build_images.sh [label ...]      (default: every docker version of versions.json)
set -eu
T=$(cd "$(dirname "$0")" && pwd)
LABELS=${*:-$(python3 -c 'import json,sys; d=json.load(open(sys.argv[1]))["versions"]; print(" ".join(k for k, v in d.items() if v["runner"] == "docker"))' "$T/versions.json")}
for label in $LABELS; do
  set -- $(python3 -c 'import json,sys; v=json.load(open(sys.argv[1]))["versions"][sys.argv[2]]; print(v["image"], v["core"])' "$T/versions.json" "$label")
  if docker image inspect "$1" >/dev/null 2>&1; then echo "$1: present"; continue; fi
  docker build --build-arg "ANSIBLE_CORE_VERSION=$2" -t "$1" "$T/../snapshot"
done
