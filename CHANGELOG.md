# Ansibility Changelog

## Unreleased

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
