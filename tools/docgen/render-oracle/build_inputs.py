#!/usr/bin/env python3
"""Write the input/ directory of the generated render-oracle cases.

    tools/docgen/render-oracle/build_inputs.py [case ...]      (default: every generated case)

The cases live in semantics/src/test/resources/render-oracle/<case>/. Their content is synthetic: role and host names
are invented or the neutral aliases of the plugin's test fixture. Two kinds of input exist:

- generated (written here, from the literals below and from the expression modules in expr/ through gen_expr.py):
  01, 03, 04, 04n, 05, 06, 09, 10, 11, 11b, 13, 14;
- curated (the committed input/ is the source and is never rewritten): 02, 07, 08 and 12, which came from the
  exploratory probes of the research note (plan/research/rendering.md), and 15, whose cases.json is the regex table.

After writing inputs, run run_some.sh <case> (or run_all.sh) to regenerate the expected outputs with the real tools.
"""
import json
import os
import pathlib
import shutil
import subprocess
import sys

HERE = pathlib.Path(__file__).resolve().parent
REPO = HERE.parents[2]
CASES = REPO / "semantics" / "src" / "test" / "resources" / "render-oracle"
EXPR = HERE / "expr"
LOCAL = "localhost ansible_connection=local\n"
CURATED = ("02_template_env_defaults", "07_undefined", "08_control_structures", "12_lookup_search_paths",
           "15_regex_python_vs_java")
BUILDERS = {}


def case(name):
    """Registers the decorated function as the input builder of [name]."""
    def register(fn):
        BUILDERS[name] = fn
        return fn
    return register


def w(name, rel, data):
    p = CASES / name / "input" / rel
    p.parent.mkdir(parents=True, exist_ok=True)
    if isinstance(data, bytes):
        p.write_bytes(data)
    else:
        with open(p, "w", encoding="utf-8", newline="") as fh:
            fh.write(data)


def fresh(name):
    d = CASES / name / "input"
    if d.is_dir():
        shutil.rmtree(d)
    d.mkdir(parents=True)
    return d


def gen(name, module):
    """An expression-oracle case: input/ is written by gen_expr.py from expr/<module>."""
    d = fresh(name)
    shutil.rmtree(d)
    subprocess.run([sys.executable, str(HERE / "gen_expr.py"), str(EXPR / module), str(d)],
                   check=True, stdout=subprocess.DEVNULL, stdin=subprocess.DEVNULL)


# ---------------------------------------------------------------- 01 the user's example (fileglob loop + dest expr)
@case("01_user_example_fileglob")
def _01(c):
    fresh(c)
    w(c, "inventory", "[agents]\nhost-a ansible_connection=local\nhost-b ansible_connection=local alloy_log_level=debug\n")
    w(c, "site.yml", """- name: Agents
  hosts: agents
  gather_facts: false
  pre_tasks:
    - name: mkdir
      ansible.builtin.file: {path: "{{ playbook_dir }}/out/{{ inventory_hostname }}/etc/alloy", state: directory}
  roles:
    - alloy
""")
    w(c, "roles/alloy/defaults/main.yml", """alloy_log_level: info
alloy_http_port: 12345
alloy_scrape_targets:
  - {name: node, port: 9100}
  - {name: app, port: 8080}
alloy_journal_matches: []
""")
    w(c, "roles/alloy/templates/config-base.alloy.j2", """// {{ ansible_managed }}
logging {
  level  = "{{ alloy_log_level }}"
  format = "logfmt"
}
http { listen = "0.0.0.0:{{ alloy_http_port }}" }
""")
    w(c, "roles/alloy/templates/config-node-exporter.alloy.j2", """{% for t in alloy_scrape_targets %}
prometheus.scrape "{{ t.name }}" {
  targets = [{"__address__" = "{{ inventory_hostname }}:{{ t.port }}"}]
}
{% endfor %}
// source: {{ item | basename }}
""")
    w(c, "roles/alloy/templates/config-journal.alloy.j2", """loki.source.journal "default" {
{% if alloy_journal_matches | length > 0 %}
  matches = "{{ alloy_journal_matches | join(' ') }}"
{% endif %}
  labels  = {{ {'host': inventory_hostname} | to_json }}
}
{% raw %}// {{ .Go.Template }} stays literal{% endraw %}
""")
    w(c, "roles/alloy/templates/systemd.override.conf.j2", "[Service]\nEnvironment=LEVEL={{ alloy_log_level }}\n")
    w(c, "roles/alloy/tasks/main.yml", r"""- name: Deploy alloy config files
  ansible.builtin.template:
    src: "{{ item }}"
    dest: "{{ playbook_dir }}/out/{{ inventory_hostname }}/etc/alloy/{{ item | basename | regex_replace('^config-', '') | regex_replace('\\.j2$', '') }}"
  loop: "{{ lookup('fileglob', role_path ~ '/templates/config-*.alloy.j2', wantlist=True) | sort }}"
  loop_control:
    label: "{{ item | basename }}"
- name: Dest per item
  ansible.builtin.debug:
    msg: "/etc/alloy/{{ item | basename | regex_replace('^config-', '') | regex_replace('\\.j2$', '') }}"
  loop: "{{ lookup('fileglob', role_path ~ '/templates/config-*.alloy.j2', wantlist=True) | sort }}"
  loop_control:
    label: "{{ item | basename }}"
- name: Same loop as with_fileglob (relative pattern, role search path)
  ansible.builtin.debug:
    msg: "{{ item | basename }}"
  with_fileglob: ["templates/config-base.*.j2"]
- name: Same dest expression written with a doubled backslash (does not strip .j2 in a task arg)
  ansible.builtin.debug:
    msg: "/etc/alloy/{{ 'config-base.alloy.j2' | regex_replace('^config-', '') | regex_replace('\\\\.j2$', '') }}"
""")


# ---------------------------------------------------------------- 03 trailing newlines and newline_sequence
@case("03_trailing_newlines")
def _03(c):
    fresh(c)
    w(c, "inventory", LOCAL)
    w(c, "vars.yml", "d: {b: 2, a: 1}\nlst: [3, 1]\nnum: 42\nflag: true\nname: web\nnone_v: null\n")
    templates = {
        "plain_one_nl": b"one newline\n",
        "plain_two_nl": b"two newlines\n\n",
        "plain_no_nl": b"no newline",
        "plain_crlf": b"a\r\nb\r\n",
        "jinja_crlf": b"a\r\n{% if true %}\r\nb\r\n{% endif %}\r\nc\r\n",
        "str_nl": b"{{ name }}\n",
        "int_nl": b"{{ num }}\n",
        "int_2nl": b"{{ num }}\n\n",
        "int_space_nl": b"{{ num }} \n",
        "int_string_nl": b"{{ num | string }}\n",
        "bool_nl": b"{{ flag }}\n",
        "list_nl": b"{{ lst }}\n",
        "dict_nl": b"{{ d }}\n",
        "dict_no_nl": b"{{ d }}",
        "none_nl": b"{{ none_v }}\n",
        "emptystr_nl": b"{{ '' }}\n",
        "two_expr_nl": b"{{ num }}{{ num }}\n",
        "block_int_nl": b"{% if flag %}{{ num }}{% endif %}\n",
        "block_false_nl": b"{% if false %}x{% endif %}\n",
        "block_false_3nl": b"{% if false %}x{% endif %}\n\n\n",
        "endif_last": b"x\n{% if true %}\ny\n{% endif %}\n",
        "endif_last_noeol": b"x\n{% if true %}\ny\n{% endif %}",
        "hdr_newline_seq": b"#jinja2: newline_sequence: '\\r\\n'\na\n{{ name }}\n",
        "include_nl": b"A\n{% include 'inc_nl.inc' %}\nB\n",
    }
    for k, v in templates.items():
        w(c, "templates/" + k + ".j2", v)
    w(c, "templates/inc_nl.inc", b"included line\n")
    names = json.dumps(sorted(templates))
    w(c, "site.yml", """- hosts: localhost
  gather_facts: false
  vars_files: [vars.yml]
  tasks:
    - ansible.builtin.file: {path: "{{ playbook_dir }}/out/lf", state: directory}
    - ansible.builtin.file: {path: "{{ playbook_dir }}/out/crlf", state: directory}
    - name: TPL default newline_sequence
      ansible.builtin.template: {src: "{{ case_id }}.j2", dest: "{{ playbook_dir }}/out/lf/{{ case_id }}.txt"}
      loop: %s
      loop_control: {loop_var: case_id}
      ignore_errors: true
    - name: TPL newline_sequence CRLF
      ansible.builtin.template: {src: "{{ case_id }}.j2", dest: "{{ playbook_dir }}/out/crlf/{{ case_id }}.txt", newline_sequence: "\\r\\n"}
      loop: %s
      loop_control: {loop_var: case_id}
      ignore_errors: true
""" % (names, names))


# ---------------------------------------------------------------- 04/05/06 expression oracles (gen_expr.py)
BUILDERS["04_value_rendering"] = lambda c: gen(c, "cases_types.py")
BUILDERS["04n_value_rendering_jinja2_native"] = lambda c: gen(c, "cases_types.py")
BUILDERS["05_filters_tests_operators"] = lambda c: gen(c, "cases_filters.py")
BUILDERS["06_filters_more"] = lambda c: gen(c, "cases_more.py")


# ---------------------------------------------------------------- 09 backslash escaping: task args vs templates
@case("09_backslash_escaping")
def _09(c):
    fresh(c)
    w(c, "inventory", LOCAL)
    w(c, "vars.yml", "path_tpl: /srv/roles/alloy/templates/config-base.alloy.j2\n")
    w(c, "templates/backslash.j2",
      "{{ 'a.b' | regex_replace('\\.', '-') }}|{{ 'a.b' | regex_replace('\\\\.', '-') }}|"
      "{{ '1' is match('\\d') }}|{{ '1' is match('\\\\d') }}|{{ 'x y' | regex_replace(' ', '\\n') }}|"
      "{{ 'ab-12' | regex_replace('(\\w+)-(\\d+)', '\\2_\\1') }}|{{ 'ab-12' | regex_replace('(\\\\w+)-(\\\\d+)', '\\\\2_\\\\1') }}\n")
    w(c, "templates/user_example.j2",
      "{{ path_tpl | basename | regex_replace('^config-', '') | regex_replace('\\.j2$', '') }}|"
      "{{ path_tpl | basename | regex_replace('^config-', '') | regex_replace('\\\\.j2$', '') }}\n")
    w(c, "site.yml", SITE_09)


# The BS:when_curly_* rows pin the YAML-value backslash rule inside a {{ }} of a condition on every core (the review
# measured it on 2.21.4 only, probe review1).
SITE_09 = r"""- hosts: localhost
  gather_facts: false
  vars_files: [vars.yml]
  tasks:
    - name: "BS:dq_curly_single"     # YAML dq -> Jinja text '\.' inside {{ }}
      ansible.builtin.debug: {msg: "{{ 'a.b' | regex_replace('\\.', '-') }}"}
    - name: "BS:dq_curly_double"     # YAML dq -> Jinja text '\\.' inside {{ }}
      ansible.builtin.debug: {msg: "{{ 'a.b' | regex_replace('\\\\.', '-') }}"}
    - name: "BS:sq_curly_single"     # YAML sq -> Jinja text '\.' (YAML sq keeps backslashes)
      ansible.builtin.debug: {msg: '{{ ''a.b'' | regex_replace(''\.'', ''-'') }}'}
    - name: "BS:plain_curly_digit"   # Jinja text '\d'
      ansible.builtin.debug:
        msg: >-
          {{ '1' is match('\d') }}
    - name: "BS:block_digit_single"  # {% %} only, Jinja text '\d'
      ansible.builtin.debug:
        msg: >-
          {% if '1' is match('\d') %}Y{% else %}N{% endif %}
    - name: "BS:block_digit_double"  # {% %} only, Jinja text '\\d'
      ansible.builtin.debug:
        msg: >-
          {% if '1' is match('\\d') %}Y{% else %}N{% endif %}
    - name: "BS:curly_digit_double"  # {{ }}, Jinja text '\\d'
      ansible.builtin.debug:
        msg: >-
          {{ '1' is match('\\d') }}
    - name: "BS:mixed_block_and_curly"  # {{ }} present: does escaping hit {% %} strings too?
      ansible.builtin.debug:
        msg: >-
          {% if '1' is match('\\d') %}Y{% else %}N{% endif %}{{ '' }}
    - name: "BS:when_single"
      ansible.builtin.debug: {msg: ran}
      when: "'1' is match('\\d')"
    - name: "BS:when_double"
      ansible.builtin.debug: {msg: ran}
      when: "'1' is match('\\\\d')"
    - name: "BS:when_curly_single"   # {{ }} inside when:, Jinja text '\d'
      ansible.builtin.debug: {msg: ran}
      when: "{{ '1' is match('\\d') }}"
      ignore_errors: true
    - name: "BS:when_curly_double"   # {{ }} inside when:, Jinja text '\\d'
      ansible.builtin.debug: {msg: ran}
      when: "{{ '1' is match('\\\\d') }}"
      ignore_errors: true
    - name: "BS:outside_expr"         # backslash outside {{ }} is plain text
      ansible.builtin.debug:
        msg: >-
          C:\temp\{{ 'x' }}
    - name: "BS:user_example_task_arg"
      ansible.builtin.debug:
        msg: "/etc/demo/{{ path_tpl | basename | regex_replace('^config-', '') | regex_replace('\\.j2$', '') }}"
    - name: "BS:sq_curly_backref"
      ansible.builtin.debug: {msg: '{{ ''ab-12'' | regex_replace(''(\w+)-(\d+)'', ''\2_\1'') }}'}
    - name: "BS:dq_curly_backref"
      ansible.builtin.debug: {msg: "{{ 'ab-12' | regex_replace('(\\w+)-(\\d+)', '\\2_\\1') }}"}
    - name: "BS:sq_newline_literal"
      ansible.builtin.debug: {msg: '{{ ''a\nb'' }}'}
    - name: TPL
      ansible.builtin.template: {src: "{{ case_id }}.j2", dest: "{{ playbook_dir }}/out/{{ case_id }}.txt"}
      loop: [backslash, user_example]
      loop_control: {loop_var: case_id}
"""


# ---------------------------------------------------------------- 10 magic variables a template sees (deterministic subset)
@case("10_magic_vars")
def _10(c):
    fresh(c)
    w(c, "inventory", """[web]
web1 ansible_connection=local http_port=8080
web2 ansible_connection=local http_port=8081
[db]
db1 ansible_connection=local
[app:children]
web
""")
    w(c, "site.yml", """- name: Magic play
  hosts: web:db
  gather_facts: false
  pre_tasks:
    - ansible.builtin.file: {path: "{{ playbook_dir }}/out/{{ inventory_hostname }}", state: directory}
  roles:
    - demo_agent
""")
    w(c, "roles/demo_agent/defaults/main.yml", "demo_port: 9100\ndemo_name: \"agent-{{ inventory_hostname }}\"\n")
    w(c, "roles/demo_agent/tasks/main.yml", """- name: magic
  ansible.builtin.template: {src: sub/magic.j2, dest: "{{ playbook_dir }}/out/{{ inventory_hostname }}/magic.txt"}
- name: magic via lookup
  ansible.builtin.debug: {msg: "{{ lookup('ansible.builtin.template', 'magic_lookup.j2') }}"}
""")
    w(c, "roles/demo_agent/templates/sub/magic.j2", """managed={{ ansible_managed }}
template_host_type={{ template_host | type_debug }}
template_path={{ template_path }}
template_fullpath={{ template_fullpath }}
template_destpath={{ template_destpath }}
template_run_date_type={{ template_run_date | type_debug }}
template_mtime_type={{ template_mtime | type_debug }}
template_uid_type={{ template_uid | type_debug }}
role_path={{ role_path }}
role_name={{ role_name }}
inventory_hostname={{ inventory_hostname }} short={{ inventory_hostname_short }}
group_names={{ group_names }}
groups={{ groups }}
hostvars_web2_port={{ hostvars['web2'].http_port }}
hostvars_web2_role_default={{ hostvars['web2'].demo_port | default('NOT-IN-HOSTVARS') }}
hostvars_self_role_default={{ hostvars[inventory_hostname].demo_port | default('NOT-IN-HOSTVARS') }}
role_default_direct={{ demo_port }} lazy={{ demo_name }}
playbook_dir={{ playbook_dir }}
inventory_dir={{ inventory_dir }}
ansible_play_hosts={{ ansible_play_hosts }}
ansible_play_batch={{ ansible_play_batch }}
ansible_play_name={{ ansible_play_name }}
ansible_role_names={{ ansible_role_names }}
ansible_check_mode={{ ansible_check_mode }}
ansible_version_full_type={{ ansible_version.full | type_debug }}
item_visible={{ item | default('no item') }}
include_sibling={% include 'inc_sibling.j2' ignore missing %}
include_root={% include 'inc_root.j2' ignore missing %}
""")
    w(c, "roles/demo_agent/templates/sub/inc_sibling.j2", "sibling (templates/sub)")
    w(c, "roles/demo_agent/templates/inc_root.j2", "root (templates/)")
    w(c, "roles/demo_agent/templates/magic_lookup.j2",
      "lk_path={{ template_path }} lk_dest={{ template_destpath | default('UNDEF') }} lk_managed={{ ansible_managed }}\n")


# ---------------------------------------------------------------- 11 ansible_managed: default, user variable, ansible.cfg
@case("11_ansible_managed")
def _11(c):
    fresh(c)
    w(c, "inventory", LOCAL)
    w(c, "templates/managed.j2", "{{ ansible_managed }}\n")
    w(c, "templates/managed_comment.j2", "{{ ansible_managed | comment }}\nkey = value\n")
    w(c, "site.yml", """- hosts: localhost
  gather_facts: false
  tasks:
    - name: TPL default
      ansible.builtin.template: {src: "{{ case_id }}.j2", dest: "{{ playbook_dir }}/out/default_{{ case_id }}.txt"}
      loop: [managed, managed_comment]
      loop_control: {loop_var: case_id}
- hosts: localhost
  gather_facts: false
  vars: {ansible_managed: "user value for {{ inventory_hostname }}"}
  tasks:
    - name: TPL user variable
      ansible.builtin.template: {src: managed.j2, dest: "{{ playbook_dir }}/out/user_var.txt"}
""")


@case("11b_ansible_managed_cfg")
def _11b(c):
    fresh(c)
    _11(c)
    w(c, "ansible.cfg", "[defaults]\nansible_managed = Managed by Ansible: {file}\n")


# ---------------------------------------------------------------- 13 loop item shapes (with_* vs loop)
# W16 and W17 pin how an undefined name prints in a task name on every core (the review measured 2.21.4 only).
LOOPS_13 = r"""- name: W1 with_items flattens one level
  ansible.builtin.debug: {msg: "{{ item }}"}
  with_items: [[1, 2], [3, [4]], 5]
- name: W2 loop does not flatten
  ansible.builtin.debug: {msg: "{{ item }}"}
  loop: [[1, 2], [3, [4]], 5]
- name: W3 with_dict
  ansible.builtin.debug: {msg: "{{ item }}"}
  with_dict: {b: 2, a: 1}
- name: W4 loop dict2items
  ansible.builtin.debug: {msg: "{{ item }}"}
  loop: "{{ {'b': 2, 'a': 1} | dict2items }}"
- name: W5 with_nested
  ansible.builtin.debug: {msg: "{{ item }}"}
  with_nested: [[a, b], [1, 2]]
- name: W6 with_subelements
  ansible.builtin.debug: {msg: "{{ item.0.name }}:{{ item.1 }}"}
  with_subelements:
    - [{name: u1, keys: [k1, k2]}, {name: u2, keys: [k3]}]
    - keys
- name: W7 with_sequence
  ansible.builtin.debug: {msg: "{{ item }}"}
  with_sequence: start=1 end=3 format=n%02d
- name: W8 with_together
  ansible.builtin.debug: {msg: "{{ item }}"}
  with_together: [[1, 2, 3], [a, b]]
- name: W9 with_indexed_items
  ansible.builtin.debug: {msg: "{{ item }}"}
  with_indexed_items: [x, y]
- name: W10 loop_control extended + index_var + label
  ansible.builtin.debug: {msg: "{{ idx }} {{ ansible_loop.index }} {{ ansible_loop.first }} {{ ansible_loop.last }} {{ ansible_loop.length }} {{ ansible_loop.revindex }} {{ ansible_loop.previtem | default('-') }} {{ ansible_loop.nextitem | default('-') }} {{ ansible_loop_var }}"}
  loop: [p, q, r]
  loop_control: {index_var: idx, extended: true, label: "L-{{ item }}"}
- name: W11 with_items of a string
  ansible.builtin.debug: {msg: "{{ item }}"}
  with_items: "single"
- name: W12 loop of a string (error expected)
  ansible.builtin.debug: {msg: "{{ item }}"}
  loop: "single"
  ignore_errors: true
- name: W13 with_list
  ansible.builtin.debug: {msg: "{{ item }}"}
  with_list: [[1, 2], 3]
- name: W14 loop over dict (error expected)
  ansible.builtin.debug: {msg: "{{ item }}"}
  loop: {a: 1}
  ignore_errors: true
- name: W15 loop_var item templated in name {{ item | default('NO-ITEM') }}
  ansible.builtin.debug: {msg: "{{ item }}"}
  loop: [one]
- name: W16 undefined item in a task name {{ item }} / {{ inventory_hostname }} / {{ item | default('NO-ITEM') }}
  ansible.builtin.debug: {msg: "{{ item }}"}
  loop: [one]
- name: W17 undefined variable in a task name {{ no_such_var }} / {{ no_such_var | default('DEFAULT') }}
  ansible.builtin.debug: {msg: "{{ inventory_hostname }}"}
"""


@case("13_loop_items")
def _13(c):
    fresh(c)
    w(c, "inventory", LOCAL)
    w(c, "site.yml", "- hosts: localhost\n  gather_facts: false\n  tasks:\n" + "".join(
        "    " + line + "\n" for line in LOOPS_13.splitlines()))


# ---------------------------------------------------------------- 14 set filters on strings: order depends on the hash seed
@case("14_set_filter_order")
def _14(c):
    fresh(c)
    w(c, "inventory", LOCAL)
    w(c, "site.yml", """- hosts: localhost
  gather_facts: false
  vars:
    words: [alpha, beta, gamma, delta, epsilon, zeta]
    other: [eta, beta, theta]
  tasks:
    - {name: union, ansible.builtin.debug: {msg: "{{ words | union(other) }}"}}
    - {name: difference, ansible.builtin.debug: {msg: "{{ words | difference(other) }}"}}
    - {name: symmetric_difference, ansible.builtin.debug: {msg: "{{ words | symmetric_difference(other) }}"}}
    - {name: intersect, ansible.builtin.debug: {msg: "{{ words | intersect(other) }}"}}
    - {name: "unique (order-preserving)", ansible.builtin.debug: {msg: "{{ (words + other) | unique }}"}}
    - {name: "union of ints", ansible.builtin.debug: {msg: "{{ [30, 1, 20] | union([5]) }}"}}
    - {name: "union of dicts (unhashable, order-preserving)", ansible.builtin.debug: {msg: "{{ [{'a': 1}] | union([{'b': 2}]) }}"}}
""")


def main(argv):
    names = argv or sorted(BUILDERS)
    for name in names:
        if name in CURATED:
            if not (CASES / name / "input").is_dir():
                sys.exit(f"{name}: curated input/ is missing (it is committed, not generated)")
            print(f"{name}: curated, kept")
            continue
        if name not in BUILDERS:
            sys.exit(f"unknown case {name!r}; generated: {', '.join(sorted(BUILDERS))}; curated: {', '.join(CURATED)}")
        BUILDERS[name](name)
        print(f"{name}: input written")


if __name__ == "__main__":
    main(sys.argv[1:])
