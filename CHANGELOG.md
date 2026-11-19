# Ansibility Changelog

## Unreleased

## 0.0.6 - 2026-11-19

### Added

- **Variable source hints**: after a variable reference in tasks, handlers, playbooks and Jinja2 templates, an inline hint shows what the hosts get there, per environment: `default 1.2.0 · prod 1.4.0 · test 1.3.0`.
  - Each entry is the value the environment's hosts actually get at that usage, by Ansible's precedence, counting only definitions that reach the hosts that run the role or play. Hosts that disagree show both values (`prod 1.4.1 / 1.4.2-hotfix`), environments with the same value share an entry (`stage, test 2.0.0`), and the task's own `vars:` or an earlier `set_fact` are named where they win. Where the value depends on the path that runs the task, there is no hint.
  - Quiet by default: only where the values differ, one hint per name and line, shortened with `+N` (the tooltip lists every value and the file it comes from). Vault values and variables a spec marks `no_log` read `🔒`; nothing is decrypted.
  - With an environment or host selected as the Ansible context: `→ 1.4.1 (prod) · default 1.2.0`.
  - Options: also where every environment gets the same value, and also in vars and inventory files. A click opens the Ctrl+B list.
  - One switch for every project: "Ansibility: Toggle Variable Source Hints" (Find Action, assignable to a shortcut, the editor's right-click menu in Ansible files), or Settings › Editor › Inlay Hints › Values › "Ansibility: Variable sources".
- **ansible-lint**: Ansibility runs ansible-lint itself, so the separate Ansible Lint plugin is no longer needed.
  - Files are linted when opened and after each save. Findings stay on their lines while you edit.
  - Runs in the repository's Compose service with "lint" in its name when one mounts the root (the team's pinned versions), else with a local `ansible-lint`. The config (`ansible-lint.yml`, `.ansible-lint`, `.config/ansible-lint.yml`) is found automatically.
  - ansible-lint errors show as warnings, everything else as weak warnings; they can also show as ansible-lint reports them, or all as weak warnings.
  - Quick fixes: `# noqa: <rule>` on the line, add to `.ansible-lint-ignore` or `skip_list`, Fix with ansible-lint (one undo step), open the rule documentation, copy the message.

  - Settings › Ansibility › Lint, per root. An optional commit check (off by default) stops commits with ansible-lint errors in the changed files, with Commit Anyway.
  - Offers once to disable the Ansible Lint plugin, so findings are not shown twice.
- **Regenerate Secrets…** (Ansibility Vault menu of files, folders, roots and repository folders; Tools; Find Action): new random values for chosen `!vault` values, encrypted directly with each value's vault id, password and header (`1.2;AES256;default` stays exactly as written). No plaintext reaches the disk.
  - A dialog lists the values as repository › root › file › key, all checked, so "everything in this file" or "everything in this repo" is one click. It never shows a value. Values that do not look like a password (several lines, a PEM block, JSON or YAML, spaces) start unchecked with the reason; whole-file vaults are never regenerated.
  - A new value follows the root's generation rule, else the current value's shape (alphanumeric, hex, base64, URL-safe base64, with its length), else 32 alphanumeric characters, always from `SecureRandom`. Values that are equal now get one new value together, so a database password stored for the app and for the database stays in sync.
  - Plaintext `vault_*` placeholders get their first envelope, with the vault id Ansible would pick.
  - All files are written in one undoable step, all or nothing. The summary names the roles and templates that use each variable, so you know what to deploy.
  - Intentions on a value: "Regenerate secret" and, on a plaintext `vault_*` value, "Generate and encrypt".
- **Rotate Vault Password…** (Ansibility Vault menu of roots, repository folders, folders and files; Tools; Find Action; the Vault tab; Settings › Ansibility › Vault): gives a vault id a new password, generated (64 letters and digits) or typed, across the roots you choose. Every vault the current password opens is encrypted again with the new one, headers kept: indexed vaults, whole-file vaults of any name, vars files without a YAML extension and vaulted password files. Roots that read the same password file or password manager entry are rotated together.
  - Everything is decrypted, encrypted with the new password and checked in memory first. A review lists what will be rewritten, what is left alone and why. "Also regenerate secrets…" gives chosen values new random values in the same pass.
  - The current password is proven, never assumed. Nothing is written, and a message says why, when it cannot tell which password is current, when a file changed or a new vault file appeared during the review, when a password file or password store entry it would overwrite holds another password, or when a decrypted tab has unsaved edits.
  - The new password is stored in the IDE password store before any vault file changes, and every old password stays there as a dated "before rotation" entry until you forget it. A password file is replaced atomically with mode 0600. Password managers (1Password, Bitwarden, KeePassXC, Proton Pass), environment variables and scripts are never written: Copy New Password, then Check reads the source again. Until the source has the new password, both passwords open your vaults and new envelopes use the new one.
  - The vault files are written at once, each atomically, all or nothing. Undo does not revert a rotation; Local History has a label before and after. If the IDE stops in the middle, the next start says so and offers Copy New Password.
  - The summary lists what the IDE cannot update (CI credentials, teammates' password files, the team's password manager), notes that git history keeps the old envelopes, and offers Regenerate Secrets… for the same roots.
- **Rotated password hint**: when a vault action fails for a vault whose id's password opened other vaults of the root before, a notification says the password may have been rotated and which source to update.
- **Vault password files stay out of the IDE's indexes**: `.vault-pass`, `.vault_pass`, `.vault-password` and `.vault_password` are excluded in every project, at any depth. Their content no longer shows up in Find in Files or Search Everywhere, and Local History keeps no copies. They still open, and every vault feature reads them as before.
  - Only files are kept out, never a folder, a vars or vault file, or a password script.
  - Settings › Ansibility › Vault › "Keep out of the IDE's indexes": more exact paths and file names (per user, for every project), optionally `.env.local`, and the project's password files that are not kept out yet, with Add. A change applies when a project is reopened.
  - Password files the IDE indexed before lose their index data when a project opens. A one-time notification explains how to clear older Local History.
  - The Vault tab lists "Password files the IDE indexes", with "Keep Out of the IDE's Indexes" for exactly that file.
  - The editor no longer shows ANS-V108 on a committed `.vault-pass`; the Vault tab and the commit check still report it.
- **Secret generation rules** in Settings › Ansibility › Vault, per root: key pattern (`vault_*_cookie_secret`), generator and length, first match wins, shared in `.idea/ansibility-vault.xml`. "Import from Script…" reads the `case` patterns of a rotation script such as `bin/rotate-secrets.sh` without running it.

### Changed

- Ctrl+B from a variable reference lists every definition in one popup: the role's spec option; with an Ansible context selected, the definition that wins for it (`✓ wins for prod-web1`); the role's `defaults/` and `vars/`; then every override that can reach the role's hosts (each environment's `group_vars`, `host_vars` and `hosts.yml`, playbook-level `group_vars`/`host_vars`, play vars, `vars_files`, params) by precedence and environment; then the other roles. Each row names the layer, environment, `path:line` and the value as written (`group_vars web · prod · environments/prod/group_vars/web.yml:12 = 1.4.0`; vault values `🔒 vault`), and speed search filters by environment or path. Before, environment and playbook overrides were listed only for variables no role declares.
- Rekey… and Change Vault Id… run on the new re-encryption engine, which also checks that every new envelope decrypts again before anything is written.

### Fixed

- A playbook run from the gutter or the Run Playbook dialog no longer changes a saved run configuration. The same settings run it as it is; other settings go to a temporary configuration; "Save as run configuration" (now off by default) adds a new one under a name of its own. Change a saved configuration in its own editor.
- The Ansible Playbook run configuration editor completes environments, limits (the environment's groups and hosts) and tags, like the Run Playbook dialog.
- Changing the golden root's git repository now always deletes the old mirror from the IDE's cache; it could stay on disk until the weekly clean-up when the golden state was read right after the change.
- Rekey…, Change Vault Id… and Encrypt value write a `!vault` value inside a flow mapping or sequence as one double-quoted line. Before, they wrote a `!vault |` block there, which YAML does not allow.
- A quoted `!vault` value with an anchor (`&pw !vault "…"`) is read correctly by Reveal, the vault actions and the vault index.
- Variable in All Repos no longer reports IDE errors in 2026.3 while it draws its list.
- Deleting a role folder while one of its templates is open no longer reports an IDE error.

## 0.0.5 - 2026-10-20

### Added

- **Playbook Vars per Environment**: each custom inventory in Settings › Ansibility › Layout has a "Playbook vars" cell that limits which `group_vars`/`host_vars` next to the playbooks the environment loads.
  - Blank loads all of them, as Ansible does (the default, so existing layouts are unchanged); `none` loads none.
  - Otherwise, list paths relative to the playbook directory, comma-separated, e.g. `group_vars/platform, group_vars/all.yml`. A directory takes every file below it.
  - The scope applies to effective variables, undefined-variable checks, hover cards, rendering, override gutter icons, per-file host scopes and the tool window. Show Detected Layout lists it per environment.
- **Settings from Sub-Repositories**: when a root lives in a sub-repository with its own `.idea` (e.g. a submodule at `repos/platform`), its committed `.idea/ansibility-layout.xml` and `.idea/ansibility-vault.xml` now apply when you open the super-repo, with paths re-based automatically.
  - Layout settings get a third storage choice, "Repository — repos/platform/.idea/ansibility-layout.xml", which writes back into the sub-repository's file. The same settings then apply whether the sub-repository is opened alone or inside the super-repo.
  - Precedence per field: Only me, then the opened project's `.idea`, then the sub-repository's file.
  - Vault ids and environment mappings that exist only in the sub-repository's file are edited there. The Vault page shows where they are stored.

### Changed

- Find Usages and Show Usages started in a playbook-level `group_vars`/`host_vars` file list only the definitions and vars-file reads that can meet it on a host: usages of `department_key` in `group_vars/alias/all.yml` no longer list the one in `group_vars/platform/all.yml`. Templates, roles and playbooks are always listed; searches from an environment's own vars files still list every environment's definitions; Rename still changes every occurrence.
- The status bar's file segment names the environments the file narrows to (`file: alias → 5 hosts in alias test, alias prod`).
- "Shared (playbook-level) vars" in the Repos tab shows which environments each file actually applies to (`L7 · applies to platform build, platform ops`). Labels no longer claim playbook vars beat "every env": a `group_vars/<group>` file only reaches hosts of that group.

### Fixed

- Navigate › Back and Forward now work after jumping with Go to Declaration, gutter icons, Find Usages, Go to Symbol, the "in all repos" panel and the tool window.
- With the project opened on a git worktree, its checked-out submodules were all shown as "Detached worktree" and could not be configured (empty root list in the Layout settings). They are regular roots again; worktrees inside the project stay detached.
- The Layout settings page no longer grows wider than the dialog when a root has many detected environments. The detected list sits below "Auto (detected)", capped at six lines, with the rest in a tooltip.

## 0.0.4 - 2026-10-09

### Added

- **Golden Root & Role Drift**:
  - Optional golden root in Settings › Ansibility › Role drift: none (default), the first role library, any root of the project, a git repository, or a folder outside the project.
  - Drift badges for every role copy in the Roles tab (`= golden`, `≈ molecule only`, `Δ spec/defaults`, `Δ tasks/templates`), VCS-style file colours and a "Differences from golden" group per drifting copy.
  - Details pane with the differing files, the last change on each side, direction hints and how many copies share each variant.
  - "Drifted Only" and "Group by Variant" toggles in the Roles tab.
  - Editor banner on role files that differ from golden, with Compare, Take Golden's Version and Align with Golden.
- **Compare with Golden**:
  - Diff of only the differing files, golden on the left and the copy on the right (editable), with Next/Previous Difference across files.
  - Annotate with Git Blame on both sides, plus last-change dates in the diff titles.
  - Compare with Other Copy… for any two copies of a role.
- **Align (Git-Style Merge)**:
  - Merge into Golden…, Align with Golden… and Align Role… open the IDE's Conflicts dialog with Accept Target / Accept Source / Merge….
  - The target is the merge base, so every difference arrives as an incoming change.
  - Files that exist on one side only are accepted whole; key and vault files are left out unless included explicitly.
  - Every write gets a Local History label and can be undone.
- **Push Role to Repos…**:
  - Pick the repositories to receive a role; each row has a preview of the changes, a Compare link and an uncommitted-changes warning.
  - Mirrors the role into the selected copies, creates it in repositories that don't have it yet, and deletes extra files unless that option is unticked.
  - Undo for the whole push with one confirmation, plus Run Molecule Tests and Commit… in the result notification.
  - After a push that changes `defaults/` or `argument_specs`, the argument-spec checks of the pushed copies are reported in the notification.
- **Single-File Takes**: Take Golden's Version and Take This into Golden for one file, each as one undoable step.
- **Golden Root from a Git Repository**:
  - Shallow, sparse (roles-only) mirror in the IDE cache that refreshes every 30 minutes (configurable, or on demand only), with Test Connection and Fetch Now.
  - Uses the IDE's Git integration for credentials. Background refreshes never show a dialog, and pause after an authentication failure.
  - Read-only mirror: its files open with a banner, and nothing writes into it.
  - Per-file last change on the golden side when the history depth is raised.
  - "Golden moved" notification when a new golden commit changes how your copies compare.
  - Asks once per machine before fetching from a URL that came from shared project settings. Optional per-machine switch to refresh in the background through an SSH agent that may ask for approval.
- **Copy as Patch for Golden…**: a unified diff that turns the golden copy into yours, ready for `git apply` in the golden repository, to the clipboard or as a `.patch` file.

### Changed

- The Roles tab's role directories have their own icons (tasks, handlers, defaults/vars, meta, templates, files, molecule/tests, plugins), and the drift folders are yellow.
- Golden actions are grouped in an "Ansibility Golden" submenu in the editor and Project view context menus.

### Fixed

- The Jinja2 template preview's status line no longer keeps the preview pane from being made narrower in split view.
- Locked vault values and the Encrypt actions use the full-size lock icon.

## 0.0.3 - 2026-10-09

### Added

- **Molecule Testing & Scenario Integration**:
  - Gutter run icons on Molecule scenario directories and `molecule.yml` files.
  - Run Molecule commands (`test`, `converge`, `verify`, `idempotence`, `destroy`) with automatic test instance cleanup.
  - Scenario discovery, visibility indexing, and navigation across role structures.
  - Dedicated run model with structured step logging and real-time execution progress.
- **Ansible Runner & Playbook Execution**:
  - Run full playbooks, single plays, or individual roles directly from editor gutter icons.
  - Execute plays locally or inside Docker Compose services.
  - Configurable run options: inventory environment, limit (`-l`), tags (`--tags`/`--skip-tags`), extra variables (`-e`), check mode (`--check`), and diffs (`--diff`).
  - Pre-flight checks for branch freshness and production target confirmations.
  - Become password support with prompting, secure storage, and external password manager CLI integration (1Password, Bitwarden, KeePassXC, Proton Pass).
  - Structured execution tool window displaying real-time play/task/host hierarchy, recap tables, diff views, and a "Rerun Failed Hosts" action.
  - Desktop notifications on run completion with detailed outcome summaries.
- **Vault & Secret Health Indexing**:
  - Secret indexer and health analysis service tracking encrypted vs unencrypted vault files.
  - File permission inspections (`SecretFileModeInspection`) warning about insecure permissions on secret files.
  - VCS status tracking to prevent committing unencrypted secrets.
- **Jinja2 & Variable Navigation**:
  - Loop variable resolution across nested task includes (`loop_var`, `index_var`).
  - Improved Jinja member completion for dictionaries, facts, and role defaults.
  - Gutter navigation to Molecule scenarios and role definitions.

### Fixed

- Fixed run view scroll bar positioning and chip height calculations on platforms with overlay scroll bars.
- Improved test runner stability and blocking read actions during file cleanup.

## 0.0.2 - 2026-10-08

### Added

- External password manager integration for Ansible Vault (1Password `op`, Bitwarden `bw`, KeePassXC `keepassxc-cli`, Proton Pass `pass`).
- Tag classification, indexing, navigation, and completion in playbooks and tasks.
- Host symbol classification, documentation hover cards, and completion for inventory targets.
- Vault secret inspections, inline encryption/decryption, and key completion.
- Automated release workflow with JetBrains Marketplace signing and publishing integration.

### Fixed

- Fixed `NioFiles` imports and file deletion cleanup in test harnesses.

## 0.0.1 - 2026-10-08

### Added

- Initial release of Ansibility for PyCharm, WebStorm, PhpStorm, and IntelliJ IDEA.
- Host awareness and context switching with status bar widget.
- Variable precedence resolution with interactive "Explain Precedence" view.
- Ansible Vault editor integration (inline reveal/edit/encrypt/decrypt/rekey and dedicated tabs).
- Role argument specifications (`meta/argument_specs.yml`) validation, hover docs, and type checking.
- Jinja2 expression injection, syntax highlighting, and live split preview.
- Module and keyword documentation hover and completion.
- Ansible tool window with environment, inventory, and effective variables explorer.
