# Infra fixtures (WU0.4)

`sync.py` copies a curated subset of the infra repo
(your local checkout, named by `$ANSIBLE_INFRA_REPO`)
into `plugin/src/test/testData/infra/`. Relative paths stay the same and **every line number is
preserved**, so acceptance checks can cite `path:line` of the real repo and a platform test can
open the same line in the fixture.

The fixture is shared and read-only for all work units. Regenerate it with `sync.py`; never edit
it by hand.

## Commands

```bash
export ANSIBLE_INFRA_REPO=/path/to/your/infra-repo
python3 tools/fixtures/sync.py --names tools/fixtures/local/names.json   # sync + verify + report, then write manifest.tsv
python3 tools/fixtures/sync.py --dry-run --list # what would be copied or skipped; writes nothing
python3 tools/fixtures/sync.py --links-only     # rewrite only the synthetic .git files (after a git clone)
python3 tools/fixtures/sync.py --envelopes-only # rewrite only the vault envelopes of the existing fixture (no infra repo)
python3 tools/fixtures/verify.py                # check the fixture; exit 1 on any problem
python3 -m unittest discover -s tools/fixtures -p 'test_*.py'   # tests for the tools (stdlib only)
./gradlew :plugin:test --tests 'de.terletzkiy.ansibility.fixtures.*'  # the same checks in CI
```

Python 3.9 or later (tested with 3.9.6 and 3.14), standard library only.

`--envelopes-only` applies the vault pass alone to an existing fixture and reads no source repo. Its envelopes depend
only on each file's structure (header positions, indentation, payload line lengths) and its path, so they are exactly
what a full sync of the same snapshot writes, and `manifest.tsv` stays valid. Use it when only the envelope rule changes
and the infra repo has moved on since the last sync (a full sync would also rewrite unrelated files).

The source repo is only ever read (DEV.md rule 1). `sync.py` builds the new tree in a staging
directory next to the destination, runs `verify.py` on it and only then swaps it in, so a failed
run leaves the old fixture untouched. It refuses to replace a non-empty destination that is not a
previous sync (no `.claude/worktrees/wt-demo/.git`) unless you pass `--force`, and refuses a
destination inside the source.

## What is copied

The subset is the `SUBSET` tuple in `sync.py` (from PLAN.md, Testing strategy 3):

- `golden/roles/{haproxy,grafana,loki,keycloak,redis,chronod,coolify,alloy,postfix,docker,system,iptables,jenkins-controller,deployment-target,totp-token,percona}` (whole role directories, including `molecule/default`), `golden/playbooks/**`, and `golden/docker/*/{Dockerfile,requirements.yml}`
- falcon: `ansible.cfg`, `playbook-*.yml`, `group_vars/**`, `environments/{prod,ops,test}/**`, `roles/{postfix,haproxy,totp-token,loki,grafana,system,jenkins-agent-docker,docker}` and `docker/*/Dockerfile`
- heron: `environments/prod/group_vars/keycloak/vars.yml`, `ansible.cfg` and `roles/keycloak`
- platform: `ansible.cfg`, `environments/prod/hosts.yml`, `environments/{ops,prod}/group_vars/**`, `environments/prod/host_vars/prod-training1/**`, `group_vars/*.yml`, `roles/mysql-databases/molecule/default/verify.yml`, `roles/puppet-migration/meta/**`, `playbook-puppet-migration.yml` and `roles/grafana/templates/provisioning/alerting/notification-policies.yml.j2`
- pelican: `ansible.cfg`, `danger_zone/database/**` (its playbooks and roles), `playbook-setup-replisync.yml` and `environments/prod/hosts.yml`
- wren: `ansible.cfg`, `environments/prod/group_vars/all/vars.yml`, `roles/app-wren-mono/tasks/nginx.yml` and `roles/app-wren-mono/templates/nginx/**`
- raven: `roles/app-raven-mono/templates/deployment/docker-compose.yml.j2`
- thrush: `roles/app-thrush-mono/templates/deployment/**`

Later additions live in `SUBSET_ADDITIONS` (append new entries there, never to `SUBSET`):

- wave 5: wren `environments/prod/hosts.yml` and `roles/app-wren-mono/handlers/main.yml`, `golden/roles/{nginx,java21-jre}`
  and falcon `roles/nginx` (the roles the molecule prepare plays reference)

IPv4 addresses are mapped in numeric order, so a new address could shift the replacements of every later address.
Addresses that only `SUBSET_ADDITIONS` files bring are therefore mapped after those of `SUBSET`: adding files never
changes a file an earlier sync wrote. Check it after a re-sync: `tools/fixtures/manifest.tsv` may only gain rows.

If a listed path does not exist, it is skipped and reported under "missing subset paths".

`tools/fixtures/manifest.tsv` lists every fixture file with its line count in the source and in
the fixture, its size and what was changed (`vault=`, `vault_lines=`, `vault_key=`, `secret_key=`,
`ip=` counts). It never contains values or source addresses. Review it like code when you re-sync.

## Sanitising rules

The rules live in `rules.py` and are shared by `sync.py` and `verify.py`. `sanitise.py` rewrites
only line bodies and never touches line terminators, so the line count is preserved. It checks
this for every file.

| What | Rule |
|---|---|
| `!vault \|` blocks and whole-file vaults | The tag stays. The envelope becomes a **valid synthetic** `$ANSIBLE_VAULT;1.1;AES256` envelope (D15 as amended for R7, `vaultfixture.py`) at the same indentation: encrypted with the synthetic fixture password (`tools/vault/SYNTHETIC.md`, row `fixture`), it decrypts to `dummy` padded with `-`. The plaintext length is chosen so the payload keeps the original's number of ciphertext blocks, so every payload line keeps its length (ansible-vault always writes a 32-byte salt; a payload of another shape falls back to the nearest block count, spread over the same number of lines). The salt is a hash of the path (IPv4 addresses blanked) and the header line, so re-syncs are byte-identical. Trailing blanks on payload lines are dropped. If `$ANSIBLE_VAULT` appears anywhere other than a header line, a `!vault` tag or a header has no payload, or hex lines continue after a blank line, the sync fails rather than guessing. |
| `vault_*` keys with a plaintext value | The value becomes `REDACTED` on the same line, keeping its quote style (`"REDACTED"`, `'REDACTED'`). Block scalars keep their indicator, and each content line becomes `REDACTED`. Continuation lines of multi-line scalars become empty lines. Nulls, empty strings, aliases, pure `{{ … }}` references and nested mappings (for example option names in `argument_specs`) are kept. |
| Secret-named keys (`*password*`, `*secret*`, `*token*`, `api_key`, `access_key`, `private_key`, …) | Same replacement, but only for literal values that look random. Templates, `${ENV}` or `${readFile:…}` references, paths, URLs without credentials, numbers, booleans, short enum words (`on_create`) and values containing blanks are kept. This goes beyond the WU spec, because real inventories sometimes keep secrets under keys that do not start with `vault_`. |
| IPv4 addresses | Each is mapped to TEST-NET (`192.0.2.x`, then `198.51.100.x`, then `203.0.113.x`) in numeric order over the whole fixture set. The same address always gets the same replacement, and two addresses never share one, so shared-IP facts stay true. CIDR suffixes are kept. Loopback (`127/8`), `0.0.0.0` (so `0.0.0.0/0` keeps meaning "anywhere") and netmasks (`255.…`) are kept. Addresses in file paths are mapped the same way. The mapping is never written anywhere. |
| Never copied | `.vault-pass`, `.initial-root-pass`, `.env*`, anything under `files/ssl/` or `files/ssh/`, `*.key`, `*.pem`, `*.crt`, `*.password` (plus `*.p12`, `*.pfx`, `*.jks`, `*.keystore`), `.git` directories and files, symbolic links, and binary files. Caches (`__pycache__`, `.ansible`, `.DS_Store`, …) are skipped silently. |

Not replaced (open D15 question): host names, domains and e-mail addresses are copied unchanged.
Molecule placeholders under secret names (`molecule-…`) are redacted in `molecule.yml` and
`vars.yml`, but where a `verify.yml` compares against the same placeholder inside an expression,
the comparison keeps the literal. This is harmless, because those values are test placeholders.

## Synthetic git links and the detached worktree

- `repos/<name>/.git` is a file containing `gitdir: ../../.git/modules/repos/<name>`, like a real
  submodule. There is one for every copied submodule (falcon, heron, pelican, platform, raven, thrush, wren).
- `.claude/worktrees/wt-demo/` is a synthetic detached worktree. Its `.git` file contains
  `gitdir: /tmp/fake/.git/worktrees/wt-demo`, and it holds a minimal duplicate of the
  `golden/roles/haproxy` role (defaults, argument spec, one task). The duplicate sets
  `haproxy_stats_http_port` to `9999`, while the real role uses `8404`, so a test can tell which
  copy a lookup returned. Lookups from outside the worktree must never return it (DEV.md rule 6).

**Git cannot track files named `.git`.** They are ignored silently. After a fresh clone, run
`python3 tools/fixtures/sync.py --links-only`. This needs no infra repo. `FixtureSanityTest`
fails with that hint when a link is missing. `myFixture.copyDirectoryToProject("infra", "")` does
copy the `.git` files into the test project; `InfraFixtureCopyTest` checks this.

## Using the fixture in tests

```kotlin
class MyFeatureTest : BasePlatformTestCase() {
    override fun getTestDataPath(): String = InfraTestData.testDataPath.toString()

    fun testSomething() {
        myFixture.copyDirectoryToProject(InfraTestData.INFRA, "")  // or a sub-path, e.g. "infra/repos/falcon"
        // ...
    }
}
```

`InfraTestData` (in `plugin/src/test/kotlin/.../fixtures`) resolves the test data directory
whatever the working directory is. You can override it with `-Dansibility.testData=<abs path>`.
Copying the whole tree takes a few seconds, so copy a sub-tree when a test needs only one root.

## Checks in CI

`FixtureSanityTest` (plain JUnit 4) re-implements the hard rules in Kotlin, independently of
`verify.py`:

- every vault envelope is the synthetic `1.1` fixture envelope: it parses with the plugin's codec, authenticates with the
  synthetic fixture password and decrypts to the dummy;
- no plaintext `vault_*` value;
- only TEST-NET or special-purpose IPv4 addresses;
- no forbidden files;
- the synthetic `.git` links are present, and there are no others;
- the anchor files named in PLAN.md exist.

With `ANSIBLE_INFRA_REPO` set, it also compares the line-break count of every fixture file with
the repo (read-only). `InfraFixtureCopyTest` (platform) checks `copyDirectoryToProject`, and that
every sanitised `.yml`/`.yaml` file parses without PSI errors.

## Names (local mapping)

The subset rules, the fixture and the tests use neutral aliases for the product repositories and domains of the
source repository. The real-name mapping lives only in `tools/fixtures/local/names.json` (git-ignored, see
`names.py` for the format); pass it with `--names` so a sync reads the real paths and writes aliased paths and
contents. Without `--names` every name maps to itself (the tools' own unit tests run that way). The fixture itself,
`manifest.tsv`, `docs/local/` and `tools/fixtures/local/` are git-ignored.

## Opt-in corpus tests

The corpus tests pin the aliases too. Run them against an aliased mirror of the infra repo, which applies only the
name mapping (paths and contents; secret files become empty placeholders and are never read):

```
python3 tools/fixtures/alias_mirror.py --source "$ANSIBLE_INFRA_REPO_REAL" --names tools/fixtures/local/names.json --dest ~/.cache/ansibility/infra-mirror
ANSIBLE_INFRA_REPO=~/.cache/ansibility/infra-mirror ./gradlew :semantics:test :plugin:test
```
