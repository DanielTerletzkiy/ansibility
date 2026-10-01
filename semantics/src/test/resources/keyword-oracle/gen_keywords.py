#!/usr/bin/env python3
"""Oracle cases for Ansibility's port of ansible-core's playbook keyword validation (ANS-K001, ANS-K002).

Run it with the Python of the ansible-core version to record; it prints one JSON document on stdout:

    # 2.18.8 (the target repo's Docker pin); the script goes in on stdin, no network, no mounts
    docker run --rm -i --network none --entrypoint sh ansible-infrastructure-ansible-lint:latest \
        -c 'python3 -' < gen_keywords.py > 2.18.8.json
    # 2.21.4 (local Homebrew ansible)
    /opt/homebrew/Cellar/ansible/14.4.0/libexec/bin/python gen_keywords.py > 2.21.4.json

`generate.sh` next to this file does both. Never edit the generated tables by hand: a disagreement with the
Kotlin port (KeywordOracleTest) is a bug in the port.

Each case is a playbook. It is written to a scratch directory (with a role `r` and a playbook `pb.yml` to import)
and taken through what `ansible-playbook` does before and while running a play, in-process and without
connecting anywhere:

* ``Playbook.load`` (parsing: ``ModuleArgsParser``, ``preprocess_data``, ``_validate_attributes``, the ``_load_*``
  loaders and ``validate()``, role and ``import_playbook`` loading);
* per play: ``Play.post_validate`` (the ``always_post_validate`` fields), the serial batches (``pct_to_int`` over
  ``play.serial``, as ``PlaybookExecutor._get_serialized_batches`` does with one host), the host list with
  ``play.order`` (``InventoryManager.get_hosts``), then ``Task.post_validate`` for every compiled task and every
  handler (as if notified), which is where inherited play, block and role keywords are checked (``TaskExecutor``
  does it before running a task).

The outcome is ``ok`` or the exception class and first message line. Templates are rendered with no variables
rendered with [TEMPLATE_VARS]; their values are valid, so a templated case fails only where ansible-core does not
template the value.
"""
from __future__ import annotations

import atexit
import json
import os
import shutil
import sys
import tempfile

_WORK = tempfile.mkdtemp(prefix='ansibility-keywords-')
atexit.register(shutil.rmtree, _WORK, True)
os.environ['ANSIBLE_LOCAL_TEMP'] = os.path.join(_WORK, 'tmp')
os.environ['ANSIBLE_NOCOLOR'] = '1'

from ansible.release import __version__ as ANSIBLE_CORE_VERSION  # noqa: E402
from ansible.plugins.loader import init_plugin_loader  # noqa: E402

init_plugin_loader()

from ansible.parsing.dataloader import DataLoader  # noqa: E402
from ansible.inventory.manager import InventoryManager  # noqa: E402
from ansible.vars.manager import VariableManager  # noqa: E402
from ansible.playbook import Playbook  # noqa: E402
from ansible.utils.helpers import pct_to_int  # noqa: E402

try:  # 2.19+
    from ansible._internal._templating._engine import TemplateEngine as _Engine

    def _templar(loader, variables):
        return _Engine(loader=loader, variables=variables)
except ImportError:  # 2.18
    from ansible.template import Templar as _Engine

    def _templar(loader, variables):
        return _Engine(loader=loader, variables=variables)

DEBUG = 'ansible.builtin.debug: {msg: x}'

# What the templated case values render to: valid values, so a templated case fails only where ansible-core does
# not template (static keywords, loaders that run before templating).
TEMPLATE_VARS = {'x': 1, 'b': True, 't': 'a', 'lc': {}, 'h': 'localhost', 's': 1, 'p': 1, 'v': 'item', 'md': {},
                 'a': {'msg': 'hi'}, 'k': 'kk'}


def task(*lines, module=DEBUG):
    """A play on localhost with one task: the module line, then the given task lines."""
    body = ''.join('      %s\n' % line for line in lines)
    return '- hosts: localhost\n  gather_facts: false\n  tasks:\n    - %s\n%s' % (module, body)


def play(*lines, tasks=True):
    body = ''.join('  %s\n' % line for line in lines)
    tail = '  tasks:\n    - %s\n' % DEBUG if tasks else ''
    return '- hosts: localhost\n  gather_facts: false\n%s%s' % (body, tail)


def block(*lines):
    body = ''.join('      %s\n' % line for line in lines)
    return '- hosts: localhost\n  gather_facts: false\n  tasks:\n    - block:\n        - %s\n%s' % (DEBUG, body)


def handler(*lines):
    body = ''.join('      %s\n' % line for line in lines)
    return '- hosts: localhost\n  gather_facts: false\n  handlers:\n    - name: h\n      %s\n%s' % (DEBUG, body)


def loop_control(text):
    return task('loop_control: %s' % text)


def role_entry(text):
    return '- hosts: localhost\n  gather_facts: false\n  roles:\n    - %s\n' % text


def imported(*lines):
    body = ''.join('  %s\n' % line for line in lines)
    return '- ansible.builtin.import_playbook: pb.yml\n%s' % body


CASES = {}


def case(name, text):
    assert name not in CASES, name
    CASES[name] = text


INT_VALUES = ['abc', '3', '"3"', '3.0', '3.5', 'true', '"1e3"', '" 4 "', '"0x1F"', '[1]', '{a: 1}', '~', '"inf"',
              '"nan"', '"4_2"', '2024-01-01', '"{{ x }}"', '"{{ x }}y"']
FLOAT_VALUES = ['x', '1.5', '"1.5"', '"inf"', 'true', '[1]', '{a: 1}', '2', '"1e3"', '2024-01-01']
BOOL_VALUES = ['maybe', 'yes', '"yes"', '1', '2', '0.0', '"on"', '[true]', '~', '"{{ b }}"', '"Y"', '1.5', '{a: 1}']
STRING_VALUES = ['[a]', '{a: 1}', '1', 'true', '~', 'abc', '"{{ x }}"']
LIST_TAG_VALUES = ['haproxy', '[a, 1]', '5', '~', '[1.5]', '[[a]]', '[{a: 1}]', '[~]', '"a,b"', 'true', '{a: 1}',
                   '[true]', '"{{ t }}"', '["{{ t }}"]', '2024-01-01', '[2024-01-01]']

for value in INT_VALUES:
    case('task.retries=%s' % value, task('retries: %s' % value))
for keyword in ('async', 'poll', 'throttle', 'timeout', 'port'):
    for value in ('abc', '5', '5.5', '[1]'):
        case('task.%s=%s' % (keyword, value), task('%s: %s' % (keyword, value)))
for value in FLOAT_VALUES:
    case('task.delay=%s' % value, task('delay: %s' % value, 'retries: 1', 'until: true'))
for value in BOOL_VALUES:
    case('task.become=%s' % value, task('become: %s' % value))
for keyword in ('no_log', 'ignore_errors', 'run_once', 'check_mode', 'diff', 'any_errors_fatal', 'delegate_facts',
                'ignore_unreachable'):
    for value in ('maybe', 'yes', '2'):
        case('task.%s=%s' % (keyword, value), task('%s: %s' % (keyword, value)))
for value in STRING_VALUES:
    case('task.become_user=%s' % value, task('become_user: %s' % value))
for keyword in ('name', 'delegate_to', 'connection', 'remote_user', 'become_method', 'become_flags', 'become_exe'):
    for value in ('[a]', '{a: 1}', '1'):
        case('task.%s=%s' % (keyword, value), task('%s: %s' % (keyword, value)))
for value in LIST_TAG_VALUES:
    case('task.tags=%s' % value, task('tags: %s' % value))
for value in ('x', '[x]', '1', '{a: 1}', '~', '[1]', '[[x]]'):
    case('task.notify=%s' % value, task('notify: %s' % value))
for value in ('x', '[x]', 'true', '1', '{a: 1}', '~'):
    case('task.when=%s' % value, task('when: %s' % value))
for keyword, value in (('changed_when', '5'), ('failed_when', '{a: 1}'), ('until', '7'), ('changed_when', '[1]')):
    case('task.%s=%s' % (keyword, value), task('%s: %s' % (keyword, value)))
for value in ('ok_name', '"bad-name"', '"{{ x }}"', '1', '[a]', '~', '"_ok"', '"1st"', '"True"', '"for"'):
    case('task.register=%s' % value, task('register: %s' % value))
for value in ('{ok: 1}', '{bad-key: 1}', '[a]', 'x', '~', '[{a: 1}]', '{"1a": 1}', '{"for": 1}', '{"{{ k }}": 1}'):
    case('task.vars=%s' % value, task('vars: %s' % value))
for value in ('x', '"{{ lc }}"', '{pause: x}', '{pause: 2}', '{pause: "2"}', '{extended: maybe}', '{loop_var: [a]}',
              '{label: [a]}', '{label: "{{ x }}"}', '{index_var: {a: 1}}', '{extended_allitems: 1}',
              '{extended_allitems: 2}', '{break_when: 5}', '{bogus: 1}', '~', '[a]', '{pause: "{{ p }}"}',
              '{loop_var: "{{ v }}"}', '{loop_var: 1}', '{pause: [1]}'):
    case('loop_control=%s' % value, loop_control(value))
for value in ('x', '"{{ a }}"', '{msg: hi}', '[a]'):
    case('task.args=%s' % value, task('args: %s' % value, module='ansible.builtin.debug:'))
for value in ('bogus', 'always', 'true', 'never', '[a]'):
    case('task.debugger=%s' % value, task('debugger: %s' % value))
for value in ('x', '[x]', '{ansible.builtin.debug: {msg: hi}}', '"{{ md }}"', '[{ansible.builtin.debug: {msg: hi}}]'):
    case('task.module_defaults=%s' % value, task('module_defaults: %s' % value))
for value in ('x', '[x]', '{A: b}', '[{A: b}]', '1', '~'):
    case('task.environment=%s' % value, task('environment: %s' % value))
for value in ('x', '[1]', '[ansible.builtin]', '~', '{a: 1}'):
    case('task.collections=%s' % value, task('collections: %s' % value))

# unknown keys and action keys
case('task.unknown_after_module', task('become_usr: root', module='ansible.builtin.file: {path: /tmp/x}'))
case('task.unknown_before_module', task('ansible.builtin.file: {path: /tmp/x}', module='become_usr: root'))
case('task.with_unknown_lookup', task('with_nolookup: [1]'))
case('task.with_items', task('with_items: [1]'))
case('task.async_val', task('async_val: 0'))
case('task.loop_with', task('loop_with: items', 'loop: [1]'))
case('task.listen', task('listen: x'))
case('task.block_keyword_on_task', task('rescue: []'))
case('task.local_action_and_unknown', task('become_usr: root', module='local_action: ansible.builtin.debug msg=x'))
case('task.action_and_module', task('ansible.builtin.ping:', module='action: ansible.builtin.debug msg=x'))
for keyword, value in (('become', 'true'), ('tags', '[a]'), ('delegate_to', 'localhost'), ('notify', 'x'),
                       ('when', 'true'), ('until', 'true'), ('changed_when', 'false'), ('retries', '1'),
                       ('ignore_errors', 'true'), ('register', 'r'), ('no_log', 'true'), ('timeout', '5'),
                       ('check_mode', 'false'), ('environment', '{A: b}')):
    case('include_tasks.%s' % keyword, task('%s: %s' % (keyword, value), module='ansible.builtin.include_tasks: x.yml'))
    case('import_tasks.%s' % keyword, task('%s: %s' % (keyword, value), module='ansible.builtin.import_tasks: x.yml'))
    case('include_role.%s' % keyword, task('%s: %s' % (keyword, value), module='ansible.builtin.include_role: {name: r}'))
    case('import_role.%s' % keyword, task('%s: %s' % (keyword, value), module='ansible.builtin.import_role: {name: r}'))

# handlers
for value in ('x', '[1]', '[a]', '"{{ t }}"', '{a: 1}'):
    case('handler.listen=%s' % value, handler('listen: %s' % value))
case('handler.unknown', handler('bogus: 1'))
case('handler.retries=abc', handler('retries: abc'))

# blocks
for keyword in ('block', 'rescue', 'always'):
    for value in ('x', '~', '[]', '{a: 1}'):
        if keyword == 'block':
            text = '- hosts: localhost\n  gather_facts: false\n  tasks:\n    - name: b\n      block: %s\n' % value
        else:
            text = block('%s: %s' % (keyword, value))
        case('block.%s=%s' % (keyword, value), text)
case('block.unknown', block('bogus: 1'))
case('block.module_key', block('ansible.builtin.ping:'))
case('block.retries', block('retries: 3'))
case('block.become=maybe', block('become: maybe'))
case('block.notify=1', block('notify: 1'))
case('block.tags=5', block('tags: 5'))
case('block.port=abc', block('port: abc'))
case('block.when=x', block('when: x'))
case('block.delegate_to=[a]', block('delegate_to: [a]'))

# plays
for value in ('~', '""', '[]', '[a, ~]', '[1]', '1', '{a: 1}', 'all', '[all]', '"{{ h }}"', '[""]', '" "'):
    case('play.hosts=%s' % value, '- hosts: %s\n  gather_facts: false\n  tasks:\n    - %s\n' % (value, DEBUG))
for value in ('maybe', 'yes', '1'):
    case('play.gather_facts=%s' % value, '- hosts: localhost\n  gather_facts: %s\n  tasks: []\n' % value)
for value in ('all', '[1]', '[all, "!min"]', '1', '{a: 1}'):
    case('play.gather_subset=%s' % value, play('gather_subset: %s' % value, tasks=False))
for value in ('x', '5', '[1]'):
    case('play.gather_timeout=%s' % value, play('gather_timeout: %s' % value, tasks=False))
for value in ('1', '[1, "50%"]', '[a]', '"50%"', '"x%"', '[~]', '[[1]]', '2.5', '"2.5%"', '"3"', '" 3 "', '"1_0"',
              '[{a: 1}]', '"{{ s }}"', '["{{ s }}"]', 'true', '"%"', '"50%%"', '-1', '"+2"', '[1, 2.5]', '0'):
    case('play.serial=%s' % value, play('serial: %s' % value))
for value in ('50', '"50%"', 'x', '[1]', '"5.5"', '~', '{a: 1}', '"%"'):
    case('play.max_fail_percentage=%s' % value, play('max_fail_percentage: %s' % value))
for value in ('[a]', 'linear'):
    case('play.strategy=%s' % value, play('strategy: %s' % value))
for value in ('sorted', 'bogus', 'inventory', 'reverse_sorted', 'reverse_inventory', 'shuffle', '[a]', '1'):
    case('play.order=%s' % value, play('order: %s' % value))
for value in ('x', 'true'):
    case('play.force_handlers=%s' % value, play('force_handlers: %s' % value))
case('play.fact_path=[a]', play('fact_path: [a]'))
case('play.unknown', play('bogus: 1'))
case('play.user', play('user: root'))
case('play.module_key', play('ansible.builtin.ping:'))
case('play.validate_argspec', play('validate_argspec: main'))
for keyword in ('tasks', 'pre_tasks', 'post_tasks', 'handlers'):
    for value in ('x', '~', '{a: 1}', '[]'):
        case('play.%s=%s' % (keyword, value), '- hosts: localhost\n  gather_facts: false\n  %s: %s\n' % (keyword, value))
for value in ('x', '~', '{a: 1}', '[r]'):
    case('play.roles=%s' % value, '- hosts: localhost\n  gather_facts: false\n  roles: %s\n' % value)
for keyword, value in (('port', 'abc'), ('become', 'maybe'), ('timeout', 'abc'), ('throttle', 'x'), ('tags', '5'),
                       ('tags', '[1.5]'), ('no_log', 'maybe'), ('debugger', 'bogus'), ('vars', '[a]'),
                       ('vars', '{bad-key: 1}'), ('module_defaults', 'x'), ('collections', '[1]'),
                       ('become_user', '[a]'), ('name', '[a]'), ('remote_user', '{a: 1}'), ('run_once', '2'),
                       ('environment', 'x'), ('vars_files', 'x'), ('vars_files', '[x.yml]')):
    case('play.%s=%s' % (keyword, value), play('%s: %s' % (keyword, value)))
case('play.port=abc,no_tasks', play('port: abc', tasks=False))
for value in ('0', 'false', '{}'):
    case('play.hosts=%s' % value, '- hosts: %s\n  gather_facts: false\n  tasks:\n    - %s\n' % (value, DEBUG))
for value in ('"1a"', 'true', '"true"', 'my_item', '"not"', '"for"'):
    case('loop_control.loop_var=%s' % value, loop_control('{loop_var: %s}' % value))
    case('loop_control.index_var=%s' % value, loop_control('{index_var: %s}' % value))
case('task.args=""', task('args: ""', module='ansible.builtin.debug:'))
case('task.args=~', task('args: ~', module='ansible.builtin.debug: {msg: x}'))
for value in ('[~, a]', '[a, ~, 1]', '[.inf]'):
    case('task.tags=%s' % value, task('tags: %s' % value))
for value in ('.inf', '[.nan]', '"1e3"', '[2024-01-01]'):
    case('play.serial=%s' % value, play('serial: %s' % value))
for value in ('.inf', '1e400', '"-inf"'):
    case('task.delay=%s' % value, task('delay: %s' % value, 'retries: 1', 'until: true'))
case('task.retries=[0, [4, 2], 0]', task('retries: [0, [4, 2], 0]'))
case('task.vars={"true": 1}', task('vars: {"true": 1}'))
case('task.vars={1: a}', task('vars: {1: a}'))
case('task.register="true"', task('register: "true"'))
case('task.register={a: b}', task('register: {a: b}'))

# import_playbook
for keyword, value in (('become', 'maybe'), ('tags', '5'), ('tags', '[1.5]'), ('when', 'x'), ('bogus', '1'),
                       ('vars', '[a]'), ('vars', 'x'), ('name', '[a]'), ('retries', '3'), ('port', 'abc'),
                       ('tags', 'a'), ('vars', '{a: 1}'), ('no_log', 'maybe'), ('debugger', 'bogus')):
    case('import_playbook.%s=%s' % (keyword, value), imported('%s: %s' % (keyword, value)))
case('import_playbook.vars=~', imported('vars: ~'))
case('import_playbook.int', '- ansible.builtin.import_playbook: 5\n')
case('import_playbook.list', '- ansible.builtin.import_playbook: [pb.yml]\n')

# role entries
for text in ('{role: r, become: maybe}', '{role: r, bogus: 1}', '{role: r, tags: 5}', '{role: r, when: x}',
             '{role: r, port: abc}', '{role: r, tags: [a]}', '{role: r, name: [a]}', '{role: r, delegate_to: [a]}'):
    case('role_entry.%s' % text, role_entry(text))


def run(text):
    case_dir = tempfile.mkdtemp(dir=_WORK)
    os.makedirs(os.path.join(case_dir, 'roles', 'r', 'tasks'))
    with open(os.path.join(case_dir, 'roles', 'r', 'tasks', 'main.yml'), 'w') as f:
        f.write('- %s\n' % DEBUG)
    with open(os.path.join(case_dir, 'x.yml'), 'w') as f:
        f.write('- %s\n' % DEBUG)
    with open(os.path.join(case_dir, 'pb.yml'), 'w') as f:
        f.write('- hosts: localhost\n  gather_facts: false\n  tasks:\n    - %s\n' % DEBUG)
    path = os.path.join(case_dir, 'site.yml')
    with open(path, 'w') as f:
        f.write(text)
    loader = DataLoader()
    inventory = InventoryManager(loader=loader, sources='localhost,')
    variables = VariableManager(loader=loader, inventory=inventory)
    host = inventory.get_host('localhost')
    try:
        playbook = Playbook.load(path, variable_manager=variables, loader=loader)
        for entry in playbook.get_plays():
            all_vars = variables.get_vars(play=entry, host=host)
            all_vars.update(TEMPLATE_VARS)
            templar = _templar(loader, all_vars)
            entry.post_validate(templar)
            for item in entry.serial:
                pct_to_int(item, 1)
            inventory.get_hosts(entry.hosts, order=entry.order)
            for compiled in entry.compile():
                for t in compiled.block:
                    t.post_validate(templar)
            # handlers are post-validated when notified; validate them as if they were
            for compiled in entry.handlers:
                for t in compiled.block:
                    t.post_validate(templar)
        return {'outcome': 'ok'}
    except Exception as e:  # every failure is a result; the class tells validation errors from crashes
        message = str(e).strip().splitlines()[0] if str(e).strip() else ''
        return {'outcome': 'error', 'error_class': type(e).__name__, 'message': message[:300]}


def main():
    cases = []
    for name, text in CASES.items():
        result = {'case': name, 'yaml': text}
        result.update(run(text))
        cases.append(result)
    json.dump({'ansible_core': ANSIBLE_CORE_VERSION, 'python': sys.version.split()[0],
               'generator': 'semantics/src/test/resources/keyword-oracle/gen_keywords.py', 'cases': cases},
              sys.stdout, indent=1, sort_keys=True)
    sys.stdout.write('\n')


if __name__ == '__main__':
    main()
