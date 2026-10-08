# Ansibility Changelog

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
