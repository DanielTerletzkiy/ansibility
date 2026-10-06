#!/usr/bin/env python3
"""Writes the layout oracle cases (inputs + oracle.json) under semantics/src/test/resources/layout-oracle/<case>/.

Every case is a directory:

    oracle.json   {"description", "notes"?, "runs": [{"name", "tool"?, "cfg"?, "cwd"?, "env"?, "args"}]}
    project/      the synthetic project tree (copied verbatim to a temp dir before every run)

Run fields:
    tool  ansible-inventory (default) or ansible-config
    cfg   path of the ANSIBLE_CONFIG file relative to the case dir (e.g. "project/ansible.cfg"),
          or null for an empty temp cfg (no project cfg in effect)
    cwd   "cwd" (an empty neutral directory, the default) or "project"
    env   extra environment variables; "{project}", "{cwd}", "{home}" are substituted
    args  argv after the tool; the same placeholders are substituted

gen.py (next to this script) runs every case on the local ansible-core and in the docker image and writes
expected/<core>/<run>.json; LayoutOracleTest (:semantics) compares the parser and the engine with those records.
Rewriting a case deletes its expected/ directory, so run gen.py afterwards.
Synthetic content only: TEST-NET addresses, example.test names, generic groups (demo, web, db).

Usage: tools/docgen/layout-oracle/build_oracle.py [case ...]
"""
import json
import pathlib
import shutil

REPO = pathlib.Path(__file__).resolve().parents[3]
ORACLE = REPO / "semantics" / "src" / "test" / "resources" / "layout-oracle"

CASES = {}


def case(name, description, files, runs, notes=None):
    CASES[name] = (description, files, runs, notes)


# ---------------------------------------------------------------- 1
case(
    "flat-ini-basic",
    "Flat layout: ansible.cfg, one INI inventory, group_vars/ and host_vars/ all in the project root, which is "
    "also the playbook dir. Covers ungrouped hosts, ranges, ports, :children, :vars, a directory-form group_vars "
    "entry, and the precedence quirks of a flat root (group_vars/all beats [web:vars]; inline host vars beat "
    "group_vars; host_vars beat inline host vars).",
    {
        "project/ansible.cfg": "[defaults]\ninventory = hosts.ini\n",
        "project/hosts.ini": """\
# flat layout: one INI inventory next to the playbook
loose1 ansible_host=192.0.2.5

[web]
web[01:03].example.test
web-[a:b] http_port=8080
db1:2222

[web:vars]
http_port=80
tier=frontend

[db]
db1 role_hint=primary
db2 ansible_host=192.0.2.21 ansible_port=2200

[dc1:children]
web
db

[dc1:vars]
datacenter=dc1

[monitoring]
db2

[all:vars]
ntp_server=ntp.example.test
""",
        "project/group_vars/all.yml": "ntp_server: ntp1.example.test\ntier: base\n",
        "project/group_vars/web.yml": "http_port: 8000\n",
        "project/group_vars/dc1/main.yml": "datacenter: dc1-from-group_vars\n",
        "project/host_vars/db1.yml": "role_hint: replica\n",
        "project/site.yml": "- hosts: web\n  gather_facts: false\n  tasks: []\n",
    },
    [
        {"name": "list-export", "cfg": "project/ansible.cfg", "args": ["--list", "--export"]},
        {"name": "list", "cfg": "project/ansible.cfg", "args": ["--list"]},
        {"name": "list-pbdir", "cfg": "project/ansible.cfg", "args": ["--list", "--playbook-dir", "{project}"]},
        {"name": "host-web-a", "cfg": "project/ansible.cfg", "args": ["--host", "web-a", "--playbook-dir", "{project}"]},
        {"name": "host-db1", "cfg": "project/ansible.cfg", "args": ["--host", "db1", "--playbook-dir", "{project}"]},
        {"name": "graph", "cfg": "project/ansible.cfg", "args": ["--graph"]},
    ],
    notes=[
        "list and list-pbdir are identical: the inventory dir is the playbook dir, so the same group_vars load at "
        "both the inventory and the playbook level.",
        "tier: group_vars/all.yml (all_plugins_inventory) is applied after [web:vars] (groups_inventory), so web "
        "hosts get 'base', not 'frontend'.",
        "db1 keeps ansible_port 2222 from its first appearance (web) although it is listed again under [db].",
    ],
)

# ---------------------------------------------------------------- 2
case(
    "flat-ini-typing",
    "INI value typing. Host-line values are shlex-split (quotes removed) and then ast.literal_eval'd; :vars values "
    "are split at the first '=', stripped, and ast.literal_eval'd with their quotes intact. Neither path is "
    "'always a string'.",
    {
        "project/ansible.cfg": "[defaults]\ninventory = hosts.ini\n",
        "project/hosts.ini": """\
typed i_int=5 i_dq="5" i_sq='5' i_nested="'5'" i_yes=yes i_True=True i_true=true i_None=None i_none=none i_float=1.5 i_exp=1e3 i_hex=0x10 i_lead0=010 i_neg=-3 i_us=1_000 i_list=[1,2] i_dict={'a':1} i_dict_dq="{'a': 1}" i_list_str="['a', 'b']" i_tuple=(1,2) i_bytes=b'abc' i_space="hello world" i_jinja="{{ x }}" i_empty= i_eq=a=b i_colon=a:b i_hash_quoted="a#b" i_semicolon=a;b i_backslash=a\\b i_padded=" padded " i_bool_list=[True,false]

[holder]
h1

[holder:vars]
v_int=5
v_dq="5"
v_sq='5'
v_yes=yes
v_True=True
v_FALSE=FALSE
v_None=None
v_float=1.5
v_list=[1, 2]
v_dict={'a': 1}
v_space=hello world
v_spaced_eq = spaced
v_eq=a=b
v_empty=
v_hash_str=abc # not a comment
v_hash_int=5 # a python comment
v_semicolon=abc ; not a comment
v_jinja={{ x }}
v_dq_space="hello world"
v_colon=a:b
v_lead0=010
""",
    },
    [
        {"name": "list-export", "cfg": "project/ansible.cfg", "args": ["--list", "--export"]},
        {"name": "host-typed", "cfg": "project/ansible.cfg", "args": ["--host", "typed"]},
        {"name": "host-h1", "cfg": "project/ansible.cfg", "args": ["--host", "h1"]},
    ],
    notes=[
        "Set, Ellipsis and complex literals are left out: 2.18.8 crashes on a set ('Object of type set is not JSON "
        "serializable'), 2.21.4 turns a set into a list and '...'/'1j' into strings (see the research note).",
    ],
)

# ---------------------------------------------------------------- 3
case(
    "split-inventory-dir",
    "Inventory file in inventory/ with its own group_vars/host_vars, playbook-level group_vars/host_vars in the "
    "project root (the playbook dir). One variable per precedence pair: L4<L5, L5<L6, L7<L9, L9<L10.",
    {
        "project/ansible.cfg": "[defaults]\ninventory = inventory/hosts.ini\n",
        "project/inventory/hosts.ini": "[web]\nweb1\n",
        "project/inventory/group_vars/all.yml": "l4_vs_l5: inventory/group_vars/all.yml (L4)\nonly_inventory_all: L4\n",
        "project/inventory/group_vars/web.yml": "l5_vs_l6: inventory/group_vars/web.yml (L6)\n",
        "project/inventory/host_vars/web1.yml": "l7_vs_l9: inventory/host_vars/web1.yml (L9)\nl9_vs_l10: inventory/host_vars/web1.yml (L9)\n",
        "project/group_vars/all.yml": "l4_vs_l5: group_vars/all.yml (L5)\nl5_vs_l6: group_vars/all.yml (L5)\n",
        "project/group_vars/web.yml": "l7_vs_l9: group_vars/web.yml (L7)\nonly_playbook_web: L7\n",
        "project/host_vars/web1.yml": "l9_vs_l10: host_vars/web1.yml (L10)\n",
        "project/site.yml": "- hosts: web\n  gather_facts: false\n  tasks: []\n",
    },
    [
        {"name": "host-no-pbdir", "cfg": "project/ansible.cfg", "args": ["--host", "web1"]},
        {"name": "host-pbdir", "cfg": "project/ansible.cfg", "args": ["--host", "web1", "--playbook-dir", "{project}"]},
        {"name": "host-cwd-is-project", "cfg": "project/ansible.cfg", "cwd": "project", "args": ["--host", "web1"]},
        {"name": "list-export", "cfg": "project/ansible.cfg", "args": ["--list", "--export"]},
        {"name": "list-export-pbdir", "cfg": "project/ansible.cfg", "args": ["--list", "--export", "--playbook-dir", "{project}"]},
        {"name": "list-pbdir", "cfg": "project/ansible.cfg", "args": ["--list", "--playbook-dir", "{project}"]},
    ],
    notes=[
        "host-cwd-is-project equals host-pbdir: without --export, ansible-inventory loads the cwd's group_vars/"
        "host_vars at the playbook level even when --playbook-dir is not given (the loader basedir defaults to the "
        "cwd). With --export only --playbook-dir adds them.",
        "ansible-playbook site.yml gives the same values as host-pbdir (verified in the research probe 07).",
    ],
)

# ---------------------------------------------------------------- 4
case(
    "cfg-multi-source",
    "ansible.cfg lists two sources (comma-separated, resolved against the cfg dir) in different directories, each "
    "with its own group_vars. Later sources win for host vars, group vars and adjacent group_vars; an INI port "
    "suffix only applies when the host is created.",
    {
        "project/ansible.cfg": "[defaults]\ninventory = a/hosts.ini, b/hosts.yml\n",
        "project/reversed.cfg": "[defaults]\ninventory = b/hosts.yml,a/hosts.ini\n",
        "project/a/hosts.ini": """\
[web]
web1:2201 x=from-a only_a=1
shared1

[web:vars]
g=from-a-web-vars

[db]
db1
""",
        "project/a/group_vars/all.yml": "adj: a/group_vars/all.yml\nadj_a: 1\n",
        "project/b/hosts.yml": """\
web:
  hosts:
    web1:
      x: from-b
      only_b: 1
    web2:
  vars:
    g: from-b-web-vars
  children:
    db:
""",
        "project/b/group_vars/all.yml": "adj: b/group_vars/all.yml\nadj_b: 1\n",
        "project/b/group_vars/web.yml": "gv_web_b: b/group_vars/web.yml\n",
    },
    [
        {"name": "host-web1", "cfg": "project/ansible.cfg", "args": ["--host", "web1"]},
        {"name": "host-web1-reversed", "cfg": "project/reversed.cfg", "args": ["--host", "web1"]},
        {"name": "list-export", "cfg": "project/ansible.cfg", "args": ["--list", "--export"]},
        {"name": "list-export-reversed", "cfg": "project/reversed.cfg", "args": ["--list", "--export"]},
        {"name": "config-dump", "tool": "ansible-config", "cfg": "project/ansible.cfg", "args": ["dump", "--only-changed", "--format", "json"]},
    ],
)

# ---------------------------------------------------------------- 5
case(
    "inventory-directory",
    "A directory as the inventory source. Files are read in sorted (byte) order, recursing into subdirectories; "
    "hidden entries, group_vars/, host_vars/, vars_plugins/ and the ignored extensions are skipped. Only the source "
    "directory's own group_vars/host_vars load (not those of nested directories). VERSION-DEPENDENT: 2.18.8 "
    "ignores '.ini' files in directory walks (its default inventory_ignore_extensions contains '.ini'), 2.21.4 "
    "does not.",
    {
        "project/ansible.cfg": "[defaults]\ninventory = inventory\n",
        "project/custom-ignore.cfg": "[defaults]\ninventory = inventory\ninventory_ignore_extensions = .md, .bak\n",
        "project/inventory/05-dup.ini": "[demo]\ndup v=from-05-dup.ini\n",
        "project/inventory/10-static.ini": "[demo]\nini-ext src=10-static.ini\n",
        "project/inventory/20-more.yml": "demo:\n  hosts:\n    yml-ext:\n      src: 20-more.yml\n",
        "project/inventory/30-data.json": '{"demo": {"hosts": {"json-ext": {"src": "30-data.json"}}}}\n',
        "project/inventory/50-dup.yml": "demo:\n  hosts:\n    dup:\n      v: from-50-dup.yml\n",
        "project/inventory/Zeta": "[demo]\nzeta-upper src=Zeta\n",
        "project/inventory/hosts": "[demo]\nnoext src=hosts\n",
        "project/inventory/README.md": "[demo]\nmd src=README.md\n",
        "project/inventory/notes.txt": "[demo]\ntxt src=notes.txt\n",
        "project/inventory/old.bak": "[demo]\nbak src=old.bak\n",
        "project/inventory/x.retry": "[demo]\nretry src=x.retry\n",
        "project/inventory/.hidden": "[demo]\nhidden src=.hidden\n",
        "project/inventory/nested/40-nested.ini": "[demo]\nnested src=nested/40-nested.ini\n",
        "project/inventory/nested/41-nested.yml": "demo:\n  hosts:\n    nested-yml:\n",
        "project/inventory/nested/group_vars/all.yml": "from_nested_group_vars: never loaded\n",
        "project/inventory/vars_plugins/x.ini": "[demo]\nvars-plugins-dir\n",
        "project/inventory/group_vars/all.yml": "from_dir_group_vars: inventory/group_vars/all.yml\n",
        "project/inventory/group_vars/demo.yml": "demo_from_dir_group_vars: inventory/group_vars/demo.yml\n",
        "project/inventory/host_vars/noext.yml": "from_dir_host_vars: inventory/host_vars/noext.yml\n",
    },
    [
        {"name": "graph", "cfg": "project/ansible.cfg", "args": ["--graph"]},
        {"name": "list-export", "cfg": "project/ansible.cfg", "args": ["--list", "--export"]},
        {"name": "list", "cfg": "project/ansible.cfg", "args": ["--list"]},
        {"name": "graph-custom-ignore", "cfg": "project/custom-ignore.cfg", "args": ["--graph"]},
        {"name": "list-export-custom-ignore", "cfg": "project/custom-ignore.cfg", "args": ["--list", "--export"]},
        {"name": "graph-nested-dir-as-source", "cfg": None, "args": ["-i", "{project}/inventory/nested", "--graph"]},
        {"name": "graph-explicit-md-file", "cfg": None, "args": ["-i", "{project}/inventory/README.md", "--graph"]},
    ],
    notes=[
        "With an explicit inventory_ignore_extensions both versions agree (the setting replaces the whole default "
        "list, so .txt, .retry and .ini are read again).",
        "An ignored extension only affects directory walks: '-i inventory/README.md' parses the file.",
    ],
)

# ---------------------------------------------------------------- 6
case(
    "cfg-empty-inventory",
    "'inventory =' (empty) and 'inventory = localhost,' in ansible.cfg. The value is a pathlist: '' resolves to the "
    "cfg's own directory, which then becomes a directory source (every file in the project root is tried as an "
    "inventory; playbooks and other YAML produce warnings). A host list does not work in ansible.cfg. "
    "VERSION-DEPENDENT through the '.ini' directory rule.",
    {
        "project/ansible.cfg": "[defaults]\ninventory =\n",
        "project/trailing-comma.cfg": "[defaults]\ninventory = localhost,\n",
        "project/host-list.cfg": "[defaults]\ninventory = web1.example.test,web2.example.test\n",
        "project/hosts.ini": "[demo]\nfrom-hosts-ini\n",
        "project/extra.yml": "demo:\n  hosts:\n    from-extra-yml:\n",
        "project/site.yml": "- hosts: demo\n  gather_facts: false\n  tasks: []\n",
        "project/README.md": "# demo project\n",
    },
    [
        {"name": "graph-empty", "cfg": "project/ansible.cfg", "args": ["--graph"]},
        {"name": "list-export-empty", "cfg": "project/ansible.cfg", "args": ["--list", "--export"]},
        {"name": "dump-empty", "tool": "ansible-config", "cfg": "project/ansible.cfg", "args": ["dump", "--only-changed", "--format", "json"]},
        {"name": "graph-trailing-comma", "cfg": "project/trailing-comma.cfg", "args": ["--graph"]},
        {"name": "dump-trailing-comma", "tool": "ansible-config", "cfg": "project/trailing-comma.cfg", "args": ["dump", "--only-changed", "--format", "json"]},
        {"name": "graph-host-list", "cfg": "project/host-list.cfg", "args": ["--graph"]},
        {"name": "graph-env-host-list", "cfg": None, "env": {"ANSIBLE_INVENTORY": "web1.example.test,web2.example.test"}, "args": ["--graph"]},
        {"name": "graph-cli-host-list", "cfg": None, "args": ["-i", "web1.example.test,web2.example.test", "--graph"]},
    ],
)

# ---------------------------------------------------------------- 7
case(
    "flat-multi-inventory",
    "Flat multi-inventory layout: production.ini and staging.ini in the root share group_vars/ and host_vars/ "
    "(each source's directory is the root). No ansible.cfg: the inventory is chosen with -i.",
    {
        "project/production.ini": "[web]\nprod-web1\n[db]\nprod-db1\n",
        "project/staging.ini": "[web]\nstage-web1\n",
        "project/group_vars/all.yml": "v_all: group_vars/all.yml\n",
        "project/group_vars/web.yml": "v_web: group_vars/web.yml\n",
        "project/group_vars/production.yml": "v_env_group: a group_vars file named like an inventory file is not an environment\n",
        "project/host_vars/prod-web1.yml": "v_host: host_vars/prod-web1.yml\n",
        "project/site.yml": "- hosts: all\n  gather_facts: false\n  tasks: []\n",
    },
    [
        {"name": "list-production", "cfg": None, "args": ["-i", "{project}/production.ini", "--list", "--playbook-dir", "{project}"]},
        {"name": "list-staging", "cfg": None, "args": ["-i", "{project}/staging.ini", "--list", "--playbook-dir", "{project}"]},
        {"name": "list-both", "cfg": None, "args": ["-i", "{project}/production.ini", "-i", "{project}/staging.ini", "--list", "--playbook-dir", "{project}"]},
        {"name": "graph-no-inventory", "cfg": None, "cwd": "project", "args": ["--graph"]},
    ],
    notes=["graph-no-inventory: without ansible.cfg and -i the default /etc/ansible/hosts is used (absent here), so "
           "only the implicit localhost exists."],
)

# ---------------------------------------------------------------- 8
case(
    "orphan-root-group-vars",
    "inventory/ and playbooks/ subdirectories: group_vars/ in the project root is next to neither the inventory "
    "source nor the playbook, so ansible-playbook never loads it (verified with ansible-playbook in probe 10). "
    "ansible-inventory shows it only when --playbook-dir points at the root.",
    {
        "project/ansible.cfg": "[defaults]\ninventory = inventory/hosts.ini\n",
        "project/inventory/hosts.ini": "[web]\nweb1\n",
        "project/inventory/group_vars/all.yml": "v_inv: inventory/group_vars/all.yml\n",
        "project/group_vars/all.yml": "v_root: group_vars/all.yml\n",
        "project/playbooks/group_vars/all.yml": "v_pb: playbooks/group_vars/all.yml\n",
        "project/playbooks/site.yml": "- hosts: web\n  gather_facts: false\n  tasks: []\n",
    },
    [
        {"name": "host-pbdir-playbooks", "cfg": "project/ansible.cfg", "args": ["--host", "web1", "--playbook-dir", "{project}/playbooks"]},
        {"name": "host-pbdir-root", "cfg": "project/ansible.cfg", "args": ["--host", "web1", "--playbook-dir", "{project}"]},
        {"name": "host-no-pbdir", "cfg": "project/ansible.cfg", "args": ["--host", "web1"]},
    ],
)

# ---------------------------------------------------------------- 9
ERRORS = {
    "child-cycle.ini": "[a:children]\nb\n[b:children]\na\n",
    "child-undefined.ini": "[parent:children]\nnosuchgroup\n",
    "vars-undefined.ini": "[nosuchgroup:vars]\nx=1\n",
    "section-space.ini": "[ web ]\nh1\n",
    "section-semicolon-comment.ini": "[web]\nh1\n[web:children] ; only '#' comments may follow a section header\n",
    "host-semicolon-comment.ini": "[web]\nh1 ; comment\n",
    "host-token-without-eq.ini": "[web]\nh1 x\n",
    "child-semicolon-comment.ini": "[web]\nh1\n[p:children]\nweb ; comment\n",
    "child-with-vars.ini": "[web]\nh1\n[p:children]\nweb x=1\n",
    "vars-without-eq.ini": "[web]\nh1\n[web:vars]\njustaword\n",
    "unbalanced-quote.ini": "[web]\nh1 x=\"open\n",
    "host-trailing-colon.ini": "[web]\nh1:\n",
    "yaml-in-ini.ini": "all:\n  hosts:\n    h1:\n",
    "yaml-doc-start-in-ini.ini": "---\nall:\n  hosts:\n    h1:\n",
    "unknown-modifier.ini": "[web:foo]\nh1\n",
}
ACCEPTED = {
    "section-hash-comments.ini": "[web] # comment after a section header\nh1\n[web:vars] # also here\nk=v\n[p:children]   # and here\nweb # and after a child\n",
    "section-hosts-tag.ini": "[web:hosts]\nh1\n",
    "indented.ini": "  [web]\n    h1   x=1\n  [web:vars]\n    k = v\n",
    "group-named-like-host.ini": "[web]\nweb\n",
    "all-section.ini": "[all]\nlisted-under-all\n[ungrouped]\nexplicit-ungrouped\n[ungrouped:vars]\nu=1\n[all:children]\nweb\n[web]\nh1\n",
    "late-declarations.ini": "[parent:children]\nlate\n[pre:vars]\ndeclared_later=yes\n[late]\nlate1\n[pre]\npre1\n[empty]\n",
    "ranges.ini": "[web]\nweb[01:03].example.test\nweb-[a:c]\nweb[8:10:2]\nweb[001:002]\nweb[1:02]\nbackwards[3:1]\n[2001:db8::1]:2201\n2001:db8::2\n",
    "dup-host-ports.ini": "[a]\nh1:2201 v=a\n[b]\nh1:2202 v=b\n[c]\nh1 v=c\n",
    "group-names.ini": "[Case]\ncase1\n[case]\ncase2\n[dash-group]\ndash1\n[group.dot]\ndot1\n",
    "empty.ini": "",
    "comments-only.ini": "# hash\n; semicolon\n",
    "hash-mid-token.ini": "[web]\nh1 x=a#b y=1\nh2 x=a #b y=2\nh3 x=\"a b\"#c y=3\nh4 x=\"a#b\" y=4\n",
    "duplicate-keys.ini": "[web]\nh1 ansible_host=192.0.2.1 ansible_host=192.0.2.2\n[web:vars]\nk=1\nk=2\nKey=3\n",
    "host-and-child.ini": "[web]\nh1 x=1\n[db]\nh1\n[web:children]\ndb\n",
}
files9 = {f"project/errors/{k}": v for k, v in ERRORS.items()}
files9.update({f"project/accepted/{k}": v for k, v in ACCEPTED.items()})
files9["project/partial/partial.ini"] = "[early]\nearly1 kept=yes\n[bad section]\n"
files9["project/partial/good.ini"] = "[demo]\ngood1\n"
runs9 = [{"name": f"error-{k[:-4]}", "cfg": None, "args": ["-i", f"{{project}}/errors/{k}", "--list", "--export"]} for k in ERRORS]
runs9 += [{"name": f"accepted-{k[:-4]}", "cfg": None, "args": ["-i", f"{{project}}/accepted/{k}", "--list", "--export"]} for k in ACCEPTED]
runs9 += [
    {"name": "partial-alone", "cfg": None, "args": ["-i", "{project}/partial/partial.ini", "--list", "--export"]},
    {"name": "partial-then-good", "cfg": None, "args": ["-i", "{project}/partial/partial.ini", "-i", "{project}/partial/good.ini", "--list", "--export"]},
]
case(
    "ini-grammar-edges",
    "INI grammar edge cases. errors/*: files the ini plugin rejects (stdout is the empty inventory, stderr warns; "
    "rc stays 0 unless unparsed_is_failed). accepted/*: unusual but valid files. partial/*: a file that fails "
    "half-way still leaks the groups and hosts it added before the error once any other source parses.",
    files9,
    runs9,
    notes=[
        "Error texts differ between versions; compare rc and stdout, and treat stderr as informative.",
        "backwards[3:1] expands to no host (no error); web[1:02] is not zero-padded (the start decides).",
        "hash-mid-token: host lines are split with shlex(comments=True), so an unquoted '#' starts a comment even "
        "inside a token: 'h1 x=a#b y=1' gives x='a' and drops y; a quoted '#' is kept.",
        "duplicate-keys: the last assignment wins (inline and :vars); variable names are case-sensitive.",
        "partial-then-good: the reconcile step after a successful source also adopts the groups of the failed file.",
    ],
)

# ---------------------------------------------------------------- 10
case(
    "vars-plugin-disabled",
    "[defaults] vars_plugins_enabled = (empty) disables host_group_vars: no group_vars/host_vars file loads at any "
    "level, only the inventory's own vars remain.",
    {
        "project/ansible.cfg": "[defaults]\ninventory = hosts.ini\nvars_plugins_enabled =\n",
        "project/hosts.ini": "[web]\nweb1 inline=1\n[web:vars]\ngroup_inline=1\n",
        "project/group_vars/all.yml": "never: loaded\n",
        "project/host_vars/web1.yml": "never_host: loaded\n",
    },
    [
        {"name": "host-web1", "cfg": "project/ansible.cfg", "args": ["--host", "web1", "--playbook-dir", "{project}"]},
        {"name": "list-export", "cfg": "project/ansible.cfg", "args": ["--list", "--export", "--playbook-dir", "{project}"]},
    ],
)


# ---------------------------------------------------------------- 11
PREC_PLAY_ALL_LAST = ("all_inventory,groups_inventory,all_plugins_inventory,groups_plugins_inventory,"
                      "groups_plugins_play,all_plugins_play")
case(
    "same-dir-double-load",
    "Flat root: the inventory source's directory is also the playbook dir, so host_group_vars loads the same "
    "group_vars/ files twice, once at the inventory stage (L4/L6) and once at the play stage (L5/L7). With the "
    "default precedence the double load is invisible; a custom precedence that applies all_plugins_play after "
    "groups_plugins_inventory exposes it (group_vars/all.yml then beats group_vars/web.yml).",
    {
        "project/hosts.ini": "[web]\nweb1\n",
        "project/group_vars/all.yml": "d: {from_all: 1, shared: all}\nl: [all]\ns: all\n",
        "project/group_vars/web.yml": "d: {from_web: 1, shared: web}\nl: [web]\ns: web\n",
        "project/site.yml": "- hosts: web\n  gather_facts: false\n  tasks: []\n",
    },
    [
        {"name": "host-default", "cfg": None, "args": ["-i", "{project}/hosts.ini", "--host", "web1", "--playbook-dir", "{project}"]},
        {"name": "host-merge", "cfg": None, "env": {"ANSIBLE_HASH_BEHAVIOUR": "merge"},
         "args": ["-i", "{project}/hosts.ini", "--host", "web1", "--playbook-dir", "{project}"]},
        {"name": "host-custom-precedence", "cfg": None, "env": {"ANSIBLE_PRECEDENCE": PREC_PLAY_ALL_LAST},
         "args": ["-i", "{project}/hosts.ini", "--host", "web1", "--playbook-dir", "{project}"]},
        {"name": "host-custom-precedence-no-pbdir", "cfg": None, "env": {"ANSIBLE_PRECEDENCE": PREC_PLAY_ALL_LAST},
         "args": ["-i", "{project}/hosts.ini", "--host", "web1"]},
        {"name": "list-export-pbdir", "cfg": None,
         "args": ["-i", "{project}/hosts.ini", "--list", "--export", "--playbook-dir", "{project}"]},
    ],
    notes=[
        "host-custom-precedence: s = 'all' (the play-stage load of group_vars/all.yml is applied last); "
        "host-custom-precedence-no-pbdir: s = 'web' (no play stage from a neutral cwd). ansible-playbook site.yml "
        "agrees (research probe 12).",
        "list-export-pbdir shows each group's vars once: --export merges the stages per group and cannot show the "
        "double load.",
    ],
)

# ---------------------------------------------------------------- 12
FORM_WEB = ["d_a", "d_sub_c", "d_b_noext", "d_hidden", "d_txt", "d_bak", "d_tilde", "d_json", "d_yaml",
            "d_dotdir", "f_yml", "f_yaml", "f_json"]
FORM_APP = ["noext", "yml", "yaml", "json", "txt"]


def pairs(me, labels):
    items = [f'"seen_{me}": true']
    for o in labels:
        if o == me:
            continue
        a, b = sorted([me, o])
        items.append(f'"pair_{a}__{b}": "{me}"')
    return "{" + ", ".join(items) + "}\n"


files12 = {
    "project/hosts.ini": "[web]\nweb1\n[app]\nweb1\n",
    "project/group_vars/web/a.yml": pairs("d_a", FORM_WEB),
    "project/group_vars/web/sub/c.yml": pairs("d_sub_c", FORM_WEB),
    "project/group_vars/web/b": pairs("d_b_noext", FORM_WEB),
    "project/group_vars/web/.hidden.yml": pairs("d_hidden", FORM_WEB),
    "project/group_vars/web/d.txt": pairs("d_txt", FORM_WEB),
    "project/group_vars/web/e.yml.bak": pairs("d_bak", FORM_WEB),
    "project/group_vars/web/f.yml~": pairs("d_tilde", FORM_WEB),
    "project/group_vars/web/g.json": pairs("d_json", FORM_WEB),
    "project/group_vars/web/h.yaml": pairs("d_yaml", FORM_WEB),
    "project/group_vars/web/0.d/x.yml": pairs("d_dotdir", FORM_WEB),
    "project/group_vars/web.yml": pairs("f_yml", FORM_WEB),
    "project/group_vars/web.yaml": pairs("f_yaml", FORM_WEB),
    "project/group_vars/web.json": pairs("f_json", FORM_WEB),
    "project/group_vars/app": pairs("noext", FORM_APP),
    "project/group_vars/app.yml": pairs("yml", FORM_APP),
    "project/group_vars/app.yaml": pairs("yaml", FORM_APP),
    "project/group_vars/app.json": pairs("json", FORM_APP),
    "project/group_vars/app.txt": pairs("txt", FORM_APP),
}
case(
    "group-vars-file-forms",
    "Which group_vars entries host_group_vars reads and in which order. For a group it probes <name> (file or "
    "directory), <name>.yml, <name>.yaml, <name>.json in that order and stops at the FIRST that exists: a "
    "group_vars/web/ directory hides web.yml/web.yaml/web.json, and an extensionless group_vars/app file hides "
    "app.yml. A directory is walked in sorted order, recursing into subdirectories without an extension at their "
    "sorted position; hidden entries, names ending in '~', files with another extension (.txt, .bak) and "
    "directories with an extension (0.d/) are skipped; extensionless files are read. Every file defines "
    "pair_<a>__<b> for each other file, so the winner of a pair is the file loaded later; seen_<file> marks a "
    "loaded file.",
    files12,
    [
        {"name": "host-web1", "cfg": None, "args": ["-i", "{project}/hosts.ini", "--host", "web1"]},
        {"name": "list-export", "cfg": None, "args": ["-i", "{project}/hosts.ini", "--list", "--export"]},
    ],
    notes=[
        "Load order for web (later wins): web/a.yml, web/b, web/g.json, web/h.yaml, web/sub/c.yml.",
        "Not in this case: group file names are matched by the filesystem, so group_vars/DB.yml is read for group "
        "'db' on a case-insensitive filesystem (macOS default, 2.21.4 probe) and not on Linux (2.18.8 docker).",
    ],
)

# ---------------------------------------------------------------- 13
case(
    "root-as-inventory-dir",
    "'inventory = .' in a project root: the whole project tree is the inventory directory. Every non-ignored file "
    "is tried as an inventory, recursively (roles/, vars/ ...): a YAML mapping becomes groups (its top-level keys), "
    "playbooks and task lists fail with warnings, README.md and ansible.cfg are skipped by extension.",
    {
        "project/ansible.cfg": "[defaults]\ninventory = .\n",
        "project/hosts": "[web]\nweb1\n",
        "project/site.yml": "- hosts: web\n  roles: [r]\n",
        "project/requirements.yml": "collections:\n  - name: demo.util\n",
        "project/roles/r/defaults/main.yml": "r_port: 80\nr_users:\n  alice: {}\n",
        "project/roles/r/meta/main.yml": "galaxy_info:\n  author: demo\ndependencies: []\n",
        "project/roles/r/tasks/main.yml": "- ansible.builtin.debug: msg=hi\n",
        "project/vars/common.yml": "common_users:\n  bob:\n    uid: 1001\n",
        "project/README.md": "# demo\n",
    },
    [
        {"name": "graph", "cfg": "project/ansible.cfg", "args": ["--graph"]},
        {"name": "list-export", "cfg": "project/ansible.cfg", "args": ["--list", "--export"]},
    ],
    notes=["Groups r_users, galaxy_info and common_users appear (empty) from role defaults, role meta and vars/."],
)

# ---------------------------------------------------------------- 14
case(
    "flat-playbook-subdir",
    "Flat root inventory (hosts.ini, group_vars/, host_vars/ at the root) with playbooks in playbooks/ that have "
    "their own group_vars/. The root group_vars are inventory-adjacent (L4/L6) for every playbook; playbooks/"
    "group_vars are playbook-level (L5/L7) only for playbooks in playbooks/.",
    {
        "project/ansible.cfg": "[defaults]\ninventory = hosts.ini\n",
        "project/hosts.ini": "[web]\nweb1 inline=hosts.ini\n",
        "project/group_vars/all.yml": "v_all: group_vars/all.yml (L4)\nv_l4_vs_l5: group_vars/all.yml (L4)\n",
        "project/group_vars/web.yml": "v_web: group_vars/web.yml (L6)\nv_l6_vs_l7: group_vars/web.yml (L6)\nv_l5_vs_l6: group_vars/web.yml (L6)\n",
        "project/host_vars/web1.yml": "inline: host_vars/web1.yml (L9)\n",
        "project/playbooks/group_vars/all.yml": "v_l4_vs_l5: playbooks/group_vars/all.yml (L5)\nv_l5_vs_l6: playbooks/group_vars/all.yml (L5)\n",
        "project/playbooks/group_vars/web.yml": "v_l6_vs_l7: playbooks/group_vars/web.yml (L7)\n",
        "project/playbooks/site.yml": "- hosts: web\n  gather_facts: false\n  tasks: []\n",
        "project/site.yml": "- hosts: web\n  gather_facts: false\n  tasks: []\n",
    },
    [
        {"name": "host-pbdir-playbooks", "cfg": "project/ansible.cfg", "args": ["--host", "web1", "--playbook-dir", "{project}/playbooks"]},
        {"name": "host-pbdir-root", "cfg": "project/ansible.cfg", "args": ["--host", "web1", "--playbook-dir", "{project}"]},
        {"name": "list-export-pbdir-playbooks", "cfg": "project/ansible.cfg", "args": ["--list", "--export", "--playbook-dir", "{project}/playbooks"]},
    ],
    notes=["ansible-playbook playbooks/site.yml gives host-pbdir-playbooks (research probe 07, layout A)."],
)

# ---------------------------------------------------------------- 15
case(
    "cfg-playbook-dir",
    "[defaults] playbook_dir in ansible.cfg (type path): a relative value resolves against the cfg file's "
    "directory and acts as --playbook-dir for ansible-inventory, including --export. --playbook-dir and "
    "ANSIBLE_PLAYBOOK_DIR resolve against the cwd. ansible-config dump --only-changed does not list PLAYBOOK_DIR; "
    "the full dump does.",
    {
        "project/ansible.cfg": "[defaults]\ninventory = hosts.ini\nplaybook_dir = pb\n",
        "project/hosts.ini": "[web]\nweb1\n",
        "project/pb/group_vars/all.yml": "from_pb: pb/group_vars/all.yml\n",
        "project/other/group_vars/all.yml": "from_pb: other/group_vars/all.yml\n",
    },
    [
        {"name": "host", "cfg": "project/ansible.cfg", "args": ["--host", "web1"]},
        {"name": "list-export", "cfg": "project/ansible.cfg", "args": ["--list", "--export"]},
        {"name": "host-cli-overrides", "cfg": "project/ansible.cfg", "args": ["--host", "web1", "--playbook-dir", "{project}/other"]},
        {"name": "host-env-overrides", "cfg": "project/ansible.cfg", "env": {"ANSIBLE_PLAYBOOK_DIR": "{project}/other"}, "args": ["--host", "web1"]},
    ],
)

# ---------------------------------------------------------------- 16
CFGS = {
    "list": "inventory = hosts.ini, extra.yml",
    "colon-separator": "inventory = hosts.ini:extra.yml",
    "tilde": "inventory = ~/inv/hosts.ini",
    "envvar": "inventory = $INVDIR/hosts.ini",
    "magic-cwd": "inventory = {{CWD}}/hosts.ini",
    "dotdot": "inventory = ../outside.ini",
    "quoted": 'inventory = "hosts.ini"',
    "semicolon-comment": "inventory = hosts.ini ; comment",
    "hash-comment": "inventory = hosts.ini # comment",
    "empty": "inventory =",
    "trailing-comma": "inventory = localhost,",
    "host-list": "inventory = web1.example.test,web2.example.test",
    "continuation": "inventory = hosts.ini,\n  extra.yml",
    "uppercase-key": "INVENTORY = hosts.ini",
    "colon-delimiter": "inventory: hosts.ini",
    "percent": "inventory = %(here)s/hosts.ini",
    "legacy-hostfile": "hostfile = hosts.ini",
    "dir": "inventory = inv",
    "duplicate-key": "inventory = hosts.ini\ninventory = extra.yml",
}
files16 = {f"project/cfg/{k}.cfg": f"[defaults]\n{v}\n" for k, v in CFGS.items()}
files16["project/cfg/no-section.cfg"] = "inventory = hosts.ini\n"
files16["project/cfg/uppercase-section.cfg"] = "[DEFAULTS]\ninventory = hosts.ini\n"
files16["project/README.md"] = "cfg/*.cfg are read by ansible-config dump; relative inventory entries resolve against cfg/.\n"
runs16 = [{"name": f"dump-{k}", "tool": "ansible-config", "cfg": f"project/cfg/{k}.cfg",
           "env": {"INVDIR": "{project}/sub"} if k == "envvar" else {},
           "args": ["dump", "--only-changed", "--format", "json"]} for k in list(CFGS) + ["no-section", "uppercase-section"]]
runs16 += [
    {"name": "dump-env-overrides-cfg", "tool": "ansible-config", "cfg": "project/cfg/list.cfg",
     "env": {"ANSIBLE_INVENTORY": "a.ini, b.yml"}, "args": ["dump", "--only-changed", "--format", "json"]},
    {"name": "dump-env-empty", "tool": "ansible-config", "cfg": "project/cfg/list.cfg",
     "env": {"ANSIBLE_INVENTORY": ""}, "args": ["dump", "--only-changed", "--format", "json"]},
    {"name": "dump-env-host-list", "tool": "ansible-config", "cfg": None,
     "env": {"ANSIBLE_INVENTORY": "web1.example.test,web2.example.test"}, "args": ["dump", "--only-changed", "--format", "json"]},
    {"name": "dump-no-cfg-default", "tool": "ansible-config", "cfg": None, "args": ["dump", "--format", "json"]},
]
case(
    "cfg-inventory-value-forms",
    "How ansible-config resolves [defaults] inventory (DEFAULT_HOST_LIST, type pathlist) for many spellings. Each "
    "run is 'ansible-config dump --only-changed --format json' with one cfg from project/cfg/ (the cwd is a "
    "neutral empty dir); compare the DEFAULT_HOST_LIST entry (value and origin) or the error rc. Entries split on "
    "',' and are stripped; '~', $VAR and {{CWD}} expand; relative entries resolve against the cfg file's "
    "directory; an empty entry becomes the cfg directory itself; ':' is no separator; quotes are kept; ';' starts "
    "an inline comment, '#' does not; ANSIBLE_INVENTORY replaces the cfg value and resolves against the cwd.",
    files16,
    runs16,
    notes=[
        "dump-no-cfg-default is the full dump with an empty cfg: DEFAULT_HOST_LIST = ['/etc/ansible/hosts'].",
        "duplicate-key, no-section: rc 5 (configparser errors abort every ansible command). uppercase-section: "
        "[DEFAULTS] is a different section, so the key is not read. legacy-hostfile: the old 'hostfile' key is "
        "not read.",
    ],
)

def main():
    import sys
    ORACLE.mkdir(parents=True, exist_ok=True)
    only = set(sys.argv[1:])
    for name, (description, files, runs, notes) in CASES.items():
        if only and name not in only:
            continue
        d = ORACLE / name
        if d.exists():
            shutil.rmtree(d)
        for rel, text in files.items():
            p = d / rel
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_text(text)
        meta = {"description": description, "runs": runs}
        if notes:
            meta["notes"] = notes
        (d / "oracle.json").write_text(json.dumps(meta, indent=2) + "\n")
        print(f"{name}: {len(files)} files, {len(runs)} runs")


if __name__ == "__main__":
    main()
