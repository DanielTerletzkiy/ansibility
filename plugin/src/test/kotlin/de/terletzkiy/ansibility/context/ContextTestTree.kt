package de.terletzkiy.ansibility.context

import com.intellij.testFramework.fixtures.CodeInsightTestFixture

/**
 * A synthetic tree mirroring the target repo's layout (research: roles.md, inventory.md, platform.md):
 * - two submodule PROJECT roots `repos/falcon/ansible` and `repos/platform/ansible` (their `.git` files point into
 *   `.git/modules/`), plus `repos/pelican/ansible` with a `danger_zone/database` NESTED_PLAYBOOK root;
 * - the `golden/` ROLE_LIBRARY with roles, playbooks and Docker images;
 * - a `.claude/worktrees/wt-1` copy at the project root, which the 2026.2 platform excludes automatically
 *   (registry key `ide.workspace.model.relative.paths.to.exclude.automatically`), so it must stay invisible;
 * - a `checkouts/.claude/worktrees/wt-2` copy that the platform does not exclude (detached by its path only)
 *   and a plain git worktree `sandbox/feature-x` (detached by its `.git` file only);
 * - `role-state` ghosts, a directory under `roles/` with only `handlers/` and `templates/` (not a role),
 *   `patches/`, `.ansible/` caches, a directory called `requirements.yml` and a `node_modules/` tree that must be
 *   ignored.
 */
object ContextTestTree {
    const val FALCON = "repos/falcon/ansible"
    const val PLATFORM = "repos/platform/ansible"
    const val PELICAN = "repos/pelican/ansible"
    const val DANGER_ZONE = "repos/pelican/ansible/danger_zone/database"
    const val GOLDEN = "golden"
    const val EXCLUDED_WT_FALCON = ".claude/worktrees/wt-1/repos/falcon/ansible"
    const val WT_GOLDEN = "checkouts/.claude/worktrees/wt-2/golden"
    const val WT_FALCON = "checkouts/.claude/worktrees/wt-2/repos/falcon/ansible"
    const val SANDBOX_SITE = "sandbox/feature-x/site"

    private const val CFG = "[defaults]\ninterpreter_python = auto_silent\nhost_key_checking = False\n"
    private const val PLAY = "---\n- name: Setup\n  hosts: all\n  roles:\n    - role: system\n"
    private const val TASKS = "---\n- name: Install\n  ansible.builtin.apt:\n    name: haproxy\n"
    private const val VARS = "---\nfoo: bar\n"
    private const val ROLE_SPEC = "argument_specs:\n  main:\n    short_description: x\n    options: {}\n"
    private const val CORE_2188 = "FROM python:3.13-alpine\nRUN python3 -m pip install \"ansible-core==2.18.8\" passlib\n"

    /** Path → content of every file in the tree. */
    val FILES: Map<String, String> = linkedMapOf(
        // falcon: submodule PROJECT root
        "repos/falcon/.git" to "gitdir: ../../.git/modules/repos/falcon\n",
        "$FALCON/ansible.cfg" to CFG,
        "$FALCON/ansible-lint.yml" to "profile: production\n",
        "$FALCON/playbook-setup-system.yml" to PLAY,
        "$FALCON/site.yml" to "- ansible.builtin.import_playbook: playbook-setup-system.yml\n",
        "$FALCON/notes.yml" to VARS,
        "$FALCON/docker/ansible-playbook/Dockerfile" to CORE_2188,
        "$FALCON/docker/ansible-molecule/requirements.yml" to "collections:\n  - name: community.general\n",
        "$FALCON/environments/prod/hosts.yml" to "all:\n  hosts:\n    prod-prod1:\n",
        "$FALCON/environments/prod/group_vars/all/vars.yml" to VARS,
        "$FALCON/environments/prod/group_vars/all/vault.yml" to VARS,
        "$FALCON/environments/prod/group_vars/keycloak/vars.yml" to VARS,
        "$FALCON/environments/test/hosts.yml" to "all:\n  hosts:\n    test-test1:\n",
        "$FALCON/environments/test/host_vars/preview-dev1.bike.example.de/vars.yml" to VARS,
        "$FALCON/environments/test/host_vars/preview-dev2.bike.example.de" to VARS,
        "$FALCON/environments/test/host_vars/test-test1.yml" to VARS,
        "$FALCON/environments/test/group_vars/all/.gitkeep" to "",
        "$FALCON/environments/test/group_vars/all/notes.md" to "# notes\n",
        "$FALCON/group_vars/all/vars.yml" to VARS,
        "$FALCON/roles/postfix/tasks/main.yml" to TASKS,
        "$FALCON/roles/postfix/defaults/main.yml" to VARS,
        "$FALCON/roles/postfix/vars/main.yml" to VARS,
        "$FALCON/roles/postfix/meta/argument_specs.yml" to ROLE_SPEC,
        "$FALCON/roles/postfix/templates/main.cf.j2" to "relayhost = {{ postfix_relayhost }}\n",
        "$FALCON/roles/postfix/templates/deployment/docker-compose.yml" to "services: {}\n",
        "$FALCON/roles/role-state/callback_plugins/__pycache__/role_state.cpython-314.pyc" to "",
        "$FALCON/.ansible/roles/cached/tasks/main.yml" to TASKS,

        // platform: submodule PROJECT root with flat group_vars files and odd names
        "repos/platform/.git" to "gitdir: ../../.git/modules/repos/platform\n",
        "$PLATFORM/ansible.cfg" to "[defaults]\nremote_user = provisioner ; inline comment\nremote_port = 2222\n",
        "$PLATFORM/group_vars/all.yml" to VARS,
        "$PLATFORM/group_vars/monitoring_client.yml" to VARS,
        "$PLATFORM/environments/prod/hosts.yaml" to "all:\n",
        "$PLATFORM/environments/prod/group_vars/all.yml" to VARS,
        "$PLATFORM/environments/prod/group_vars/keycloak.yml" to VARS,
        "$PLATFORM/environments/prod/group_vars/contracting/mysql_users.yml" to VARS,
        "$PLATFORM/environments/prod/host_vars/prod-training1/mysql.yml" to VARS,
        "$PLATFORM/roles/docker-registry/tasks/main.yml" to TASKS,
        "$PLATFORM/roles/docker-registry/molecule/cleanup/molecule.yml" to "driver:\n  name: docker\n",
        "$PLATFORM/roles/docker-registry/molecule/cleanup/converge.yml" to PLAY,
        "$PLATFORM/roles/docker-registry/molecule/cleanup/verify_per_repo_tasks.yml" to TASKS,
        "$PLATFORM/docker/ansible-molecule/requirements.yml/placeholder.txt" to "",

        // pelican: PROJECT root with a danger-zone NESTED_PLAYBOOK root
        "$PELICAN/ansible.cfg" to CFG,
        "$PELICAN/playbook-setup-replisync.yml" to PLAY,
        "$PELICAN/environments/prod/hosts.yml" to "all:\n  hosts:\n    prod-db1:\n",
        "$PELICAN/docker/ansible-playbook/Dockerfile" to "RUN pip install ansible==11.1.0\n",
        "$PELICAN/danger_zone/README.md" to "# danger\n",
        "$DANGER_ZONE/playbook-clone-to-replisync.yml" to
            "- name: Clone\n  hosts: database\n- ansible.builtin.import_playbook: ../../playbook-setup-replisync.yml\n",
        "$DANGER_ZONE/roles/xtrabackup/tasks/main.yml" to TASKS,

        // golden: ROLE_LIBRARY
        "$GOLDEN/ansible-lint.yml" to "profile: production\n",
        "$GOLDEN/docker/ansible-lint/Dockerfile" to CORE_2188,
        "$GOLDEN/playbooks/playbook-setup-system.yml" to PLAY,
        "$GOLDEN/roles/haproxy/tasks/apt.yml" to TASKS,
        "$GOLDEN/roles/haproxy/meta/argument_specs.yml" to ROLE_SPEC,
        "$GOLDEN/roles/certs-client/tasks/main.yml" to TASKS,
        "$GOLDEN/roles/certs-client/handlers/main.yaml" to TASKS,
        // not a role (no tasks/, argument specs or defaults/main): its files have no context
        "$GOLDEN/roles/nginx-snippets/handlers/main.yml" to TASKS,
        "$GOLDEN/roles/nginx-snippets/templates/site.conf.j2" to "server {}\n",
        "$GOLDEN/roles/grafana/handlers/molecule.yml" to TASKS,
        "$GOLDEN/roles/grafana/meta/main.yml" to "dependencies: []\n",
        "$GOLDEN/roles/grafana/files/dashboards/keycloak.json" to "{}\n",
        "$GOLDEN/roles/grafana/tasks/main.yml" to TASKS,
        "$GOLDEN/roles/chronod/tasks/main.yml" to TASKS,
        "$GOLDEN/roles/chronod/molecule/vars/vars.yml" to VARS,
        "$GOLDEN/roles/chronod/molecule/default/molecule.yml" to "driver:\n  name: docker\n",
        "$GOLDEN/roles/chronod/molecule/default/prepare.yml" to PLAY,
        "$GOLDEN/roles/chronod/molecule/default/Dockerfile.j2" to "FROM {{ image }}\n",
        "$GOLDEN/roles/role-state/callback_plugins/__pycache__/role_state.cpython-314.pyc" to "",
        "$GOLDEN/.ansible/roles/.keep" to "",

        // outside every root
        "patches/roles/nginx/tasks/main.yml.patch" to "--- a\n+++ b\n",
        "patches/roles/nginx/defaults/main.yml" to VARS,
        "node_modules/pkg/ansible.cfg" to CFG,
        "docs/README.md" to "# docs\n",

        // detached: the .claude/worktrees copy of the real repo (its .git file points into .git/worktrees)
        ".claude/worktrees/wt-1/.git" to "gitdir: /Users/dev/infra/.git/worktrees/wt-1\n",
        "$EXCLUDED_WT_FALCON/ansible.cfg" to CFG,
        "$EXCLUDED_WT_FALCON/roles/postfix/tasks/main.yml" to TASKS,

        // detached by path only: a .claude/worktrees copy below the project root
        "$WT_FALCON/ansible.cfg" to CFG,
        "$WT_FALCON/roles/postfix/tasks/main.yml" to TASKS,
        "$WT_GOLDEN/roles/haproxy/tasks/main.yml" to TASKS,

        // detached: a plain git worktree elsewhere in the project
        "sandbox/feature-x/.git" to "gitdir: /Users/dev/infra/.git/worktrees/feature-x\n",
        "$SANDBOX_SITE/ansible.cfg" to CFG,
        "$SANDBOX_SITE/playbook-deploy.yml" to PLAY,
    )

    fun create(fixture: CodeInsightTestFixture) {
        for ((path, text) in FILES) fixture.tempDirFixture.createFile(path, text)
    }
}
