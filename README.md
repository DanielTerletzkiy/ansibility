# Ansibility

> # AI-ASSISTED DEVELOPMENT DISCLAIMER
> **Ansibility has been developed with extensive use of Artificial Intelligence (Large Language Models / LLMs).**
>
> While this plugin has been rigorously engineered, strictly typed, and backed by comprehensive unit and integration test suites adhering to authentic `ansible-core` semantics and specification models, we believe in complete openness and do not want to mislead anyone about how the codebase was constructed.
>
> If you encounter any bugs, hallucinations, edge-case quirks, or unexpected behavior while using the plugin, please report them via the project issue tracker!

---

**Ansibility** brings deep, native Ansible support to JetBrains IDEs (**PyCharm**, **IntelliJ IDEA**, **WebStorm**, and **PhpStorm**).

Instead of treating Ansible as plain YAML, Ansibility understands your entire Ansible ecosystem: inventories, host and group hierarchies, variable precedence, playbooks, role reaches, Jinja2 templates, Ansible Vault secrets, and `meta/argument_specs.yml` definitions.

---

## Key Features

### 1. Host Awareness & Context Switching
* **Full Inventory Hierarchy:** Automatically resolves hosts, parent/child groups, `group_vars`, and `host_vars` across multiple environments.
* **Status-Bar Context Switcher:** Switch the active host/environment context directly from the status bar widget or quick action to immediately inspect how variables evaluate for a specific target host.
* **Per-File Scope Inference:** Automatically infers which hosts and plays a file applies to (e.g. playbook-level vs environment-level vars, role tasks, or Molecule scenarios).
* **Molecule & Golden Role Support:** Understands Molecule scenario directories and pseudo-inventories when developing standalone roles.

### 2. Variable Resolution & "Explain Precedence"
* **Effective Variables:** Computes exact variable values according to Ansible’s 22-step precedence order.
* **Interactive Documentation Cards:** Hover over any variable in YAML or Jinja2 to view its resolved value, definition origin, data type, and usage context.
* **Explain Precedence Breakdown:** Click "Explain precedence" to view an interactive card showing every file, inventory level, role default, and play variable that defines or overrides that variable.

### 3. Comprehensive Ansible Vault Integration
* **Gutter Lock Markers:** Visual indicators in the editor gutter for encrypted blocks and files.
* **Inline Vault Operations:**
  * Timed and masked plaintext reveal popups.
  * In-place edit, copy plaintext, encrypt, decrypt, rekey, and change Vault ID.
  * Automatic folding for `!vault |` YAML blocks.
* **Whole-File Vault Editing:** Seamlessly open and edit whole-file encrypted vaults in dedicated, color-coded editor tabs with automatic background sync protection (vetoing accidental plaintext writes to disk).
* **Password-Free Envelope Linting:** Static inspections detecting malformed vault envelopes (ANS-V101), folded values (ANS-V102), and trailing whitespace (ANS-V103) without requiring vault passwords.
* **Safe Coexistence:** Gracefully integrates alongside existing plugins like `ansible-vault-editor-idea-plugin`.

### 4. Role Argument Specs & Strict Type Checking
* **JSDoc for Ansible Roles:** Parses role `meta/argument_specs.yml` to provide structured documentation cards, completion, and Ctrl+B navigation for role input parameters.
* **ansible-core Type Verification:** Statically validates role variables against documented types (`str`, `int`, `bool`, `list`, `dict`, `path`, etc.) matching Ansible's exact coercion and validation behavior.
* **Rich Diagnostic Inspections:**
  * `ANS-T001` - Value rejected by spec
  * `ANS-T002` / `ANS-T003` - Unsupported or missing required sub-options
  * `ANS-T004` - Choice mismatch
  * `ANS-T020` - Templated value type violations
  * `ANS-S002` - Role input not defined in argument spec
  * `ANS-V003` - Variable possibly undefined (with witness hosts)

### 5. Jinja2 Intelligence & YAML Language Injection
* **Dedicated File Type:** `AnsibleJinja` file type for `*.j2` and role templates with full syntax highlighting, bracket matching, folding, and commenting.
* **YAML Injection:** Automatically injects Jinja2 expressions into YAML template values and conditional directives (`when:`, `changed_when:`, `failed_when:`, `until:`, `assert.that:`, `debug.var:`).
* **Smart Typing & Completion:** Auto-closes `{{ ... }}`, `{% ... %}`, and `{# ... #}` blocks, with context-aware completion for variables, loop variables (`item`), `ansible_facts`, `hostvars`, filters, and tests.

### 6. Live Template Split Preview
* **In-Editor Split View:** Open a split editor for any Jinja2 template (`*.j2`) to see the live rendered output evaluated against the currently active host and playbook context.

### 7. Ansible Modules, Options & Keywords
* **Instant Documentation:** Hover tooltips for modules, options, and playbook/task keywords with quick links to official documentation.
* **Smart Completion:** Intelligent auto-completion for module names, parameters, choices, and boolean values.
* **Module Inspections:**
  * `ANS-M001` - Unknown module option
  * `ANS-M002` - Missing required module option
  * `ANS-K001` / `ANS-K002` - Playbook and task keyword validation

### 8. Navigation, Find Usages & Refactoring
* **Go to Definition (Ctrl+B / Cmd+B):** Jump directly from variable usages, task references, `include_tasks`, `import_playbook`, and role references to their declarations.
* **Find Usages (Alt+F7):** Search for variable usages across your entire repository, categorized into Read, Set, and Spec definitions.
* **Safe Rename Refactoring (Shift+F6):** Rename variables and roles with automatic, project-wide reference updates.
* **Registered Variables (`register:`):** Type-aware completion and documentation for task return objects and facts.

### 9. Dedicated Ansible Tool Window & Workspace Scopes
* **Ansible Tool Window:** Browse your project's Ansible structure in a tree hierarchy (roots, environments, groups, hosts, playbooks, roles, and vars files).
* **Effective Variables Inspector:** Inspect the complete effective variable table for any selected host with play-level filtering.
* **Workspace Scopes:** Filter tool window views and inspections using custom named workspace scopes.

---

## Compatibility

Ansibility is verified and tested against the following JetBrains IDEs:
* **PyCharm** (Professional & Community)
* **IntelliJ IDEA** (Ultimate & Community)
* **WebStorm**
* **PhpStorm**

*(Other IntelliJ Platform IDEs with bundled YAML and JSON modules may also run the plugin, but verification specifically targets the IDEs listed above).*

**Minimum IDE Build:** `2026.2` (build `262+`)

---

## Building from Source

### Prerequisites
* **Java Development Kit (JDK):** Version 25 or higher
* **Gradle:** Wrapper included (`./gradlew`)

### Build Steps
```bash
# Clone the repository
git clone https://github.com/danielterletzkiy/ansibility.git
cd ansibility

# Compile and package the plugin
./gradlew assemble

# Run the plugin in a sandbox PyCharm instance
./gradlew runIde
```

The packaged plugin ZIP distribution will be available in `plugin/build/distributions/`.

---

## License & Legal

* **License:** This project is licensed under the **GNU General Public License v3.0** (GPL-3.0). See the [LICENSE](LICENSE) file for details.
* **Author:** Daniel Terletzkiy ([ansibility.terletzkiy.de](https://ansibility.terletzkiy.de))
* **Trademarks:** Ansible is a registered trademark of Red Hat, Inc. Ansibility is an independent open-source project and is not affiliated with, sponsored by, or endorsed by Red Hat, Inc.
