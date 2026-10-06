# Synthetic role-drift tree (WS3, plan amendment R9 F9.5)

Written by a one-off generator; every file is synthetic. `golden/` is the role library (the drift reference), each
`repos/<team>/ansible` is a project root with a copy of `web`:

| Copy | Differs from golden in | Tier |
|---|---|---|
| same | nothing | identical |
| mol, mol2 | `molecule/default/verify.yml` (the same edit, so one variant) | molecule-only |
| spec | `meta/argument_specs.yml` | spec-defaults |
| specmol | `defaults/main.yml` and `molecule/default/verify.yml` | spec-defaults |
| tasks | `tasks/main.yml` | tasks-templates |
| missing | 3 changed, 9 only in golden (no `meta/argument_specs.yml`) | tasks-templates |

`base` is identical in golden, same and mol; `solo` exists in same and tasks only (two variants, no golden copy);
`app-same` exists only in same. Skip-list files, sensitive files and whole-file vaults are created by the tests at
runtime, so nothing secret-looking is committed.
