#!/bin/sh
# Re-shoots every scenario into out/ (needs ./gradlew :plugin:runIdeForScreenshots --args=<demo project> running).
cd "$(dirname "$0")"
for s in s0[1-9]-*.js v[0-9][0-9]-*.js g[0-9][0-9]-*.js; do
  echo "== $s"; (echo "var OUT='$PWD/out';"; cat "$s") | python3 shoot.py - --raw || exit 1
done
