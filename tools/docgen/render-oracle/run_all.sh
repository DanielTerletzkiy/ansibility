#!/bin/sh
# Regenerates every render-oracle case: build_inputs.py, then run_some.sh over all cases (see there for the versions,
# $RENDER_ORACLE_VERSIONS, and the per-case rules). Needs the local ansible-core 2.21.4, the docker images of
# versions.json (build_images.sh builds them) and, for case 15, a JDK 25 java in $JAVA25. About 40 minutes per two
# versions. The outputs are committed, so a normal build needs neither docker nor Python.
set -u
T=$(cd "$(dirname "$0")" && pwd)
CASES=$(cd "$T/../../../semantics/src/test/resources/render-oracle" && pwd)
python3 "$T/build_inputs.py" || exit 1
# shellcheck disable=SC2046
exec "$T/run_some.sh" $(cd "$CASES" && ls -d [0-9]*/ | tr -d /)
