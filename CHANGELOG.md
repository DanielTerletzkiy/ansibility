# Ansibility Changelog

## Unreleased

### Added

- **ansible-lint**: Ansibility runs ansible-lint itself, so the separate Ansible Lint plugin is no longer needed.
  - Files are linted when opened and after each save. Findings stay on their lines while you edit.
  - Runs in the repository's Compose service with "lint" in its name when one mounts the root (the team's pinned versions), else with a local `ansible-lint`. The config is found automatically: `ansible-lint.yml`, `.ansible-lint`, `.config/ansible-lint.yml`, next to the root or above it.
  - ansible-lint errors show as warnings, everything else as weak warnings. They can also show as ansible-lint reports them, or all as weak warnings.
  - Quick fixes: `# noqa: <rule>` on the line, add to `.ansible-lint-ignore`, add to `skip_list`, Fix with ansible-lint (for its auto-fixable rules, as one undo step), open the rule documentation, copy the message.
  - Settings › Ansibility › Lint, per root: on/off, where it runs, executable, config, extra arguments, offline, how findings show. Where it runs and the executable stay on this machine; the rest is shared in `.idea/ansibility.xml`.
  - Optional commit check (off by default): ansible-lint errors in the changed files stop the commit, with Commit Anyway.
  - Offers once to disable the Ansible Lint plugin, so findings are not shown twice.
- **Regenerate Secrets…** (Ansibility Vault menu of files, folders, roots and repository folders; Tools; Find Action): new random values for chosen `!vault` values, encrypted directly with the vault id, the password and the header each value has now (a header such as `1.2;AES256;default` stays exactly as written). No plaintext reaches the disk at any moment.
  - Every value is decrypted in memory first. A dialog lists them as repository › root › file › key, all checked, so "everything in this file" or "everything in this repo" is one click. It never shows a value. A git worktree checked out inside a repository folder is left out unless you select the worktree itself.
  - Values that do not look like a password (several lines, a PEM block, JSON or YAML, spaces, longer than 512 characters) start unchecked with the reason. A row whose new value would be shorter than 16 characters is marked "short", whether the length comes from the current value, a rule or your choice; a short current value keeps its length. Whole-file vaults are listed but never regenerated.
  - How a new value is made: a rule of the root first, then "same as now" (the current alphabet and length: alphanumeric, hex, base64 with its byte count, URL-safe base64, or alphanumeric with only the symbols the value has now), then 32 alphanumeric. Generator and length can be changed for selected rows; Apply to Selected changes only what you changed and keeps the hex case and padding. Always `SecureRandom`.
  - Values that are equal now get one new value together (for example a database password stored for the app and for the database host); a group can be split.
  - Plaintext `vault_*` values (placeholders) get their first envelope, with the vault id Ansible's rule picks (environment mapping included; asked once per root when there is a choice).
  - Values inside a flow mapping or sequence (`{…}`, `[…]`) are written as one double-quoted line, `!vault "$ANSIBLE_VAULT;…"`, so the file stays valid YAML.
  - All files are written in one undoable step, all or nothing, with Local History labels. A file that changed after the values were decrypted stops the write and is named. A root that locked while the dialog was open is unlocked again.
  - The summary names, per variable, the roles and templates that use it, so you know what to deploy.
  - Intentions on a value: "Regenerate secret" (one `!vault` value, with a small generator chooser) and "Generate and encrypt" (a plaintext `vault_*` value). Both save the file first, so unsaved edits do not stop them.
- **Rotate Vault Password…** (Ansibility Vault menu of roots, repository folders, folders and files; Tools; Find Action; the Vault tab; a "Rotate Password…" button in Settings › Ansibility › Vault): gives a vault id a new password. Every vault the id's current password opens in the chosen roots is encrypted again with the new one, and the new password goes where the id's password comes from.
  - The dialog shows the vault ids with their counts, the roots that hold vaults of the chosen id with their password source, and the new password: generated (64 letters and digits) or typed twice, masked, with Show and Copy. Roots that read the same password file, password manager entry or password store entry are one row and are rotated together, also a root that holds no vault of the id. A password file that is a link shows where it points, and one outside the repository says so.
  - What is rotated: the vaults the index lists, whole-file vaults of any name or size in the roots (a vaulted template, a large bundle; `.git` and ignored folders are skipped), values in vars files without a YAML extension (`group_vars/web`), and the vaulted password files of the roots' vault ids. Vaults with a label that has no password of its own in the root are rotated when the password opens them; vaults of another id that happens to have the same password stay with that id, and the review says so. Headers stay as they are (`1.1` stays `1.1`).
  - Everything is decrypted, encrypted with the new password and checked in memory first, with Cancel. A review lists what will be rewritten, what is left alone and why, and other roots whose id turned out to have the same password. Vaults of the id that only a password kept from an earlier rotation opens (a teammate added them with the old password) are rotated too, and the review lists them.
  - Before grouping the roots, it asks to read every `.env.local` it has not read yet (only its password-file key), so it knows which password file each one names.
  - The current password is proven, never assumed: it must open the id's vaults and come from the source the dialog shows. Nothing is written or stored, and a message says why and what to do, when it does not; when two different passwords of the id open vaults; when the password store entry or the password file the rotation would overwrite holds another password; when a password file of the root was never read; when a file changed or a new vault file appeared during the review (also on disk, for example by `git pull`); when a decrypted tab has unsaved edits; when another rotation is running; or when an unread `.env.local` may name a password file it would write (you can choose to rotate without writing password files instead).
  - When the IDE password store keeps passwords only until restart (Settings › Passwords) and no password file is written, the review offers Copy, and Rotate needs "I have stored the new password somewhere safe"; the summary repeats it.
  - "Also regenerate secrets…" in the review gives chosen values new random values in the same pass (Regenerate Secrets…'s selection).
  - The new password is stored in the IDE password store before any vault file changes (in the root's own entry unless the id's source is a password store entry); every old password is kept there as "before rotation", one entry per rotation with its date, until you forget it in Settings › Vault, also when it is no text. The rotation is recorded before the first vault file changes: if the IDE stops in the middle, the next start says so, with Copy New Password. All vault files are then written at once, each atomically, all or nothing, and not undoable with Undo (Local History has a label before and after). If a file cannot be put back after a failed write, the message names the files that hold the new envelopes and the new password stays stored and unlocked (Copy New Password). A failed rotation puts a password store entry back only while it still holds the new password. A password file (also the one `.env.local` names) is replaced atomically with mode 0600 and no trailing newline. 1Password, Bitwarden, KeePassXC, Proton Pass, environment variables and scripts are never written: Copy New Password, then Check reads the source again and says whether it holds the new password. Until it does, the IDE unlocks both the source's password and the new one from its password store, so old and new vaults open, and encrypts with the new one (also after the idle lock or a restart; a password store that cannot be read once changes nothing). When the source gives yet another password (rotated again elsewhere), the source wins and a notification says so.
  - The id stays unlocked with the new password. The summary says what the IDE cannot update (CI credentials, teammates' password files, the team's password manager), that git history keeps the old envelopes, and offers Regenerate Secrets… for the same roots.
  - On the settings page Rotate Password… is off while the page has unapplied changes ("Apply your changes first"), and the page reloads after a rotation. Forgetting or replacing an id's stored password while its source does not have the rotated one yet asks first.
- **Rotated password hint**: when Reveal, Copy, Edit or a file action fails for a vault whose id's password opened other vaults of the root before (also in an earlier session), a notification says the password may have been rotated and which source to update (once per root and id).
- **Secret generation rules** in Settings › Ansibility › Vault, per root: key pattern (`vault_*_cookie_secret`, with `*`, `?` and `[...]` as in a shell), generator and length, first match wins. Shared in `.idea/ansibility-vault.xml`. The table warns about rules shorter than 16 characters and refuses patterns it cannot read. "Import from Script…" reads the `case` patterns of a rotation script such as `bin/rotate-secrets.sh` (without running it), lists the arms it did not understand, and leaves out the script's default arm (`*`), so "same as now" keeps working.

### Changed

- Rekey… and Change Vault Id… run on the new re-encryption engine, which also checks that every new envelope decrypts again with its secret before anything is written.

### Fixed

- Rekey…, Change Vault Id… and Encrypt value write a `!vault` value inside a flow mapping or sequence as one double-quoted line. Before, they wrote a `!vault |` block there, which YAML does not allow.
- A quoted `!vault` value with an anchor (`&pw !vault "…"`) is read correctly by Reveal, the vault actions and the vault index.

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
