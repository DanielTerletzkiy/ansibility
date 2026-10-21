# Marketplace screenshots

1. `docker build` a host image `acme-demo-host` (Debian 12 + python3, python3-apt, sudo, user `deploy` with NOPASSWD sudo) — used by shots 7 and 8.
2. Copy `demo/` to `~/Projects/acme-infra`, `git init` + commit it, and add `.vault-pass` (`demo-vault-password`) and `.vault-pass-prod` (`prod-vault-password-demo`).
3. Shot 7 needs two containers: `docker run -d --name stg-web-0N.acme.internal acme-demo-host` (N = 1, 2).
4. `./gradlew :plugin:runIdeForScreenshots --args="$HOME/Projects/acme-infra"` (sandbox PyCharm + Robot Server on :8082).
5. `./shoot-all.sh` → `out/*.png` (2880×1800, the IDE window rendered offscreen at 2×).
