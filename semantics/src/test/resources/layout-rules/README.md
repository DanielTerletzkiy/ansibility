# Layout rules oracle (R10-2)

Measured records for the pure layout rules in `semantics.layout` (`CfgSyntax`, `CfgPathList`, `IgnoreExtensions`).
Every case was run with ansible-core 2.21.4 (local) and 2.18.8 (docker image `ansibility-docgen:2.18.8`,
`--network none`, the case piped in as a tar, no mounts), under `env -i` with an empty `HOME` and stdin `/dev/null`,
from a neutral empty cwd. Paths are normalised to `{project}`, `{cwd}` and `{home}`. All content is synthetic.

| Directory | Source | Records |
|---|---|---|
| `cfg-inventory-value-forms/` | the R10 research oracle (`docs/research/flat-layouts.md` §12), unchanged | its 25 `ansible-config dump` runs |
| `cfg-empty-inventory/` | the same oracle | the 2 dump runs (`dump-empty`, `dump-trailing-comma`); the graph runs live in `layout-oracle/` |
| `cfg-multi-source/` | the same oracle | `config-dump`; the host and list runs live in `layout-oracle/` |
| `probe-cfg-syntax/` | this unit's probe | 34 cfg files: configparser errors (rc 5) and accepted oddities |
| `probe-cfg-paths/` | this unit's probe | 34 cfg files: `roles_path`, `collections_path[s]`, `playbook_dir`, more `inventory` spellings, ignore lists |
| `probe-ignore-walk/` | this unit's probe | 3 `ansible-inventory --list` runs over one directory source with empty, default and custom ignore lists |

The three oracle cases keep their `oracle.json`; only the `ansible-config` runs and the cfg files they read were
copied. The probe records are reduced: `settings` keeps `value` and `origin` of `DEFAULT_HOST_LIST`,
`DEFAULT_ROLES_PATH`, `COLLECTIONS_PATHS`, `PLAYBOOK_DIR`, `INVENTORY_IGNORE_EXTS`, `DEFAULT_FORKS` and
`DEFAULT_TIMEOUT` from the full `ansible-config dump --format json`, and `stderr` ends before any Python traceback.

To regenerate a probe case, put its `oracle.json` and `project/` next to the layout-oracle generator
(`tools/docgen/layout-oracle/gen.py`, which reads cases from its own directory), run `gen.py --target both <case>`,
and reduce the records as described above.
