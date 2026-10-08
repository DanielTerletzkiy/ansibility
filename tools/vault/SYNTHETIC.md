# Synthetic vault material

Every vault envelope and every password in this repository is **synthetic**: invented for tests, never derived from,
copied from or tested against a real vault (DEV.md hard rule 2, plan amendment R7/R8). This file is the one register
of that material:

- **Passwords** lists every synthetic secret. `tools/vault/vectors.sh` reads the table below (the `Hex` and
  `Source bytes` columns) and generates nothing else; a password that is not listed here cannot appear in a vector.
- **Allowed paths** lists every place where `$ANSIBLE_VAULT` may appear in the repository. The amended M0 check 4 and
  the vault security suite fail on any occurrence outside these paths.

Rules:

1. Tests never decrypt real vault data. Corpus tests on the infra repository are structural only (headers, styles,
   indentation), and the vault services refuse to decrypt in corpus mode.
2. Synthetic password **files** are never committed. Tests write them at runtime into a temporary directory, from the
   `Source bytes` column, and never under the name `.vault-pass`.
3. A new synthetic secret or a new copy of an envelope needs a row here first (passwords) or a line in "Allowed paths"
   (copies), in the same change.
4. Plaintexts of the vectors are fixed test strings (`hello world`, …). The security suite generates its own
   sentinel values at test time (`ANSIBILITY-SENTINEL-NN`) and encrypts them with these passwords; those values are
   never committed.

## Passwords

`Hex` is the effective secret (what Ansible derives the key from). `Source bytes` is what the source delivers before
Ansible's strip rules: the password file content, the script's stdout or the prompt input.

| Id | Label | Password | Hex | Source | Source bytes (hex) | Used by |
|---|---|---|---|---|---|---|
| `pw1` | `default` | `test-pass-1` | `746573742d706173732d31` | file | `746573742d706173732d310a` | v01, v04–v07, v09–v11; tolerance matrices; fixture; `default` in multi-vault rows; the wrapped shapes of v01 (`shapes/`, R21) |
| `dev` | `dev` | `dev-pass-2` | `6465762d706173732d32` | file | `6465762d706173732d320a` | v02, v08; `dev` in multi-vault rows |
| `prod` | `prod` | `prod-pass-3` | `70726f642d706173732d33` | file | `70726f642d706173732d330a` | v03, v13 (mislabelled `dev`), v14 (unlabelled); `prod` in multi-vault rows |
| `spaced` | `default` | `spaced pass 4` | `73706163656420706173732034` | file | `202073706163656420706173732034200d0a` | v12 (surrounding whitespace and CRLF stripped) |
| `nonutf8` | `default` | (raw bytes, not UTF-8) | `ff706173732de9` | file | `ff706173732de90a` | v15 (PBKDF2 over raw bytes) |
| `utf8` | `ops` | `pässwörd-✓` | `70c3a4737377c3b672642de29c93` | file | `70c3a4737377c3b672642de29c930a` | v16; path-resolution rows |
| `ws` | `default` | `ws pass` | `77732070617373` | file | `090b777320706173730c0a0a` | strip rows (TAB, VT, FF, several LF) |
| `inner` | `prod` | `  inner pass ` | `2020696e6e6572207061737320` | vaulted file | `2020696e6e65722070617373200d0a` | vaulted password file rows (encrypted with `pw1`; only CR/LF are stripped after decryption) |
| `script` | `dev` | ` script pass ` | `20736372697074207061737320` | script | `207363726970742070617373200d0a` | plain password script rows (stdout: only CR/LF are stripped) |
| `prompt` | `dev` | `pässwörd-✓` | `70c3a4737377c3b672642de29c93` | prompt | `202070c3a4737377c3b672642de29c932009` | prompt rows (UTF-8, then all ASCII whitespace stripped) |
| `fixture` | `default` | `fixture-pass-d15` | `666978747572652d706173732d643135` | file | `666978747572652d706173732d6431350a` | the infra fixture (D15 as amended, V2b): every `!vault` value and whole-file vault in `plugin/src/test/testData/infra/` is a `1.1` envelope of `dummy` padded with `-`, written by `tools/fixtures/vaultfixture.py` |

## Allowed paths

Paths are relative to the repository root; `**` matches any number of path segments.

```paths
semantics/src/test/resources/vault/**
plugin/src/test/testData/infra/**
```

When a work unit adds a copy of a vector (for example the D15 fixture envelope under `plugin/src/test/testData/infra/`,
or R9's drift copy of v09 under `plugin/src/test/testData/toolwindow/drift/`), it adds the path to the block above and
names the vector and password it uses.

- `semantics/src/test/resources/vault/shapes/**` (R21, ANS-V107): vector v01's envelope (password `pw1`, plaintext
  `hello world`) saved the ways a vault file goes wrong (a `!vault |` line first, `key: !vault |`, a preamble,
  indentation, a byte order mark, quotes, other text), the whole-file vaults they unwrap to and `shapes.json`, written by
  `tools/vault/shapes.sh` with what ansible-core 2.21.4 and 2.18.8 do with each. Plugin tests build the same shapes at
  run time around envelopes they encrypt themselves.
- `plugin/src/test/testData/infra/**` (V2b): the D15 fixture envelopes, password `fixture`, plaintext `dummy` padded with
  `-` to keep each value's line count and line lengths. `tools/fixtures/sync.py` writes them (`--envelopes-only`
  rewrites just the envelopes of an existing fixture) and `tools/fixtures/verify.py` refuses any envelope there that does
  not authenticate with this password and decrypt to the dummy.

## Regenerating

```bash
tools/vault/vectors.sh            # keep the committed envelopes; recompute every table with 2.21.4 (local) and 2.18.8 (docker)
tools/vault/vectors.sh --fresh    # new envelopes from the local ansible-vault 2.21.4 (random salts, except v10)
tools/vault/vectors.sh --seed DIR # take the envelopes from DIR/raw (for example the research vectors), then recompute
tools/vault/shapes.sh             # the wrapped shapes of v01 and their oracle (2.21.4 local, 2.18.8 docker), after vectors.sh
```

The docker run uses `--network none`, no volume mounts, and the script on stdin (DEV.md rule 1). Never edit the
generated files by hand.
