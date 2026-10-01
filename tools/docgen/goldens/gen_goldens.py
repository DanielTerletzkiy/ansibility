#!/usr/bin/env python3
"""Golden tables for Ansibility's Kotlin port of ansible-core's type coercion and argument-spec validation.

Run it with the Python of the ansible-core version to record; it prints one JSON document on stdout:

    # 2.18.8 (the target repo's Docker pin); the script goes in on stdin, no network, no mounts
    docker run --rm -i --network none --entrypoint sh ansibility-docgen:2.18.8 \
        -c 'python3 -' < gen_goldens.py > ../../../semantics/src/test/resources/goldens/2.18.8.json
    # 2.21.4 (local Homebrew ansible)
    /opt/homebrew/Cellar/ansible/14.4.0/libexec/bin/python gen_goldens.py > .../goldens/2.21.4.json

`generate.sh` next to this file does both. Never edit the generated tables by hand: a disagreement with the
Kotlin port (GoldenTablesTest) is a bug in the port.

Every value is written as a YAML spelling and loaded exactly as ansible-core loads a vars file
(`DataLoader().load("v: <spelling>")`), so YAML 1.1 typing is part of each case. Cases:

* ``check_type``: ``DEFAULT_TYPE_VALIDATORS[type](value)``. TypeError/ValueError is a rejection (what
  ``_validate_argument_types`` catches); any other exception is a crash.
* ``validate``: ``ArgumentSpecValidator({'v': spec}).validate({'v': value})`` (or ``{}`` when the value is
  absent). Records the error classes in order, the coerced ``validated_parameters['v']`` (also for failures) and
  crashes (exceptions escaping ``validate``). A ``choices`` mapping in the spec is passed as its keys view,
  which is how the plugin normalises ansible-doc's dict choices.
* ``human_to_bytes`` and ``boolean``: the helpers called directly.

The corpus is synthetic plus value *shapes* seen in the target repo; no repo values are copied. It avoids
inputs outside the plugin's value model (vault payloads, ``~user`` paths, tuples/sets/bytes/complex numbers
produced by ``literal_eval`` inside dicts, integers beyond Python's 4300-digit ``str()`` limit, and NaN
identity: PyYAML returns one shared ``.nan`` object, so ``.nan in [.nan]`` holds on 2.18 but not on tagged 2.19+).
"""
from __future__ import annotations

import atexit
import datetime
import json
import os
import shutil
import sys
import tempfile

# Importing ansible's constants creates its local temp dir; keep that out of the user's home.
_LOCAL_TMP = tempfile.mkdtemp(prefix='ansibility-goldens-')
atexit.register(shutil.rmtree, _LOCAL_TMP, True)
os.environ['ANSIBLE_LOCAL_TEMP'] = _LOCAL_TMP

from ansible.release import __version__ as ANSIBLE_CORE_VERSION  # noqa: E402
from ansible.parsing.dataloader import DataLoader  # noqa: E402  (also switches module_utils to controller mode)
from ansible.module_utils.common.arg_spec import ArgumentSpecValidator  # noqa: E402
from ansible.module_utils.common.parameters import DEFAULT_TYPE_VALIDATORS  # noqa: E402
from ansible.module_utils.common.text.formatters import human_to_bytes  # noqa: E402
from ansible.module_utils.parsing.convert_bool import boolean  # noqa: E402

LOADER = DataLoader()

# check_type_path runs expandvars/expanduser at call time: pin the environment so both runs agree with the
# Kotlin test (which uses the same variables).
os.environ.clear()
os.environ.update({'HOME': '/home/golden', 'GOLDEN_VAR': 'gv', 'GOLDEN_EMPTY': ''})


def load(spelling):
    """The Python value ansible-core's loader produces for `v: <spelling>` in a vars file."""
    return LOADER.load('v: ' + spelling, show_content=False)['v']


def plain(value):
    """Strip ansible's tagged/AnsibleUnicode wrapper types (used for specs, which are plain dicts in real use)."""
    if value is None or isinstance(value, bool):
        return value
    if isinstance(value, int):
        return int(value)
    if isinstance(value, float):
        return float(value)
    if isinstance(value, str):
        return str(value)
    if isinstance(value, datetime.datetime):
        return datetime.datetime(value.year, value.month, value.day, value.hour, value.minute, value.second,
                                 value.microsecond, tzinfo=value.tzinfo)
    if isinstance(value, datetime.date):
        return datetime.date(value.year, value.month, value.day)
    if isinstance(value, list):
        return [plain(v) for v in value]
    if isinstance(value, dict):
        return {plain(k): plain(v) for k, v in value.items()}
    raise TypeError('unexpected type in spec: %s' % type(value).__name__)


class OutOfModel(Exception):
    """The value has a Python type the plugin's value model does not hold (e.g. a set from literal_eval)."""


def canon(value):
    """Canonical Python repr of a (possibly tagged) value; the Kotlin side prints PyValue the same way."""
    if value is None or isinstance(value, bool):
        return repr(value)
    if isinstance(value, int):
        return repr(int(value))
    if isinstance(value, float):
        return repr(float(value))
    if isinstance(value, str):
        return repr(str(value))
    if isinstance(value, (datetime.date, datetime.datetime)):
        return repr(plain(value))
    if isinstance(value, list):
        return '[' + ', '.join(canon(v) for v in value) + ']'
    if isinstance(value, dict):
        return '{' + ', '.join(canon(k) + ': ' + canon(v) for k, v in value.items()) + '}'
    raise OutOfModel(type(value).__name__)


def coerced(value):
    """The coerced_repr fields; values outside the model are flagged (the port must report them indeterminate)."""
    try:
        return {'coerced_repr': canon(value)}
    except OutOfModel:
        return {'coerced_repr': None, 'out_of_model': True}


def normalise_spec(spec):
    """ansible-doc may give choices as a mapping value -> description; validation uses the keys (a KeysView)."""
    if isinstance(spec.get('choices'), dict):
        spec['choices'] = spec['choices'].keys()
    for sub in (spec.get('options') or {}).values():
        normalise_spec(sub)
    return spec


CASES = []
SEEN = set()


def record(case_id, **fields):
    if case_id in SEEN:
        raise SystemExit('duplicate case id: ' + case_id)
    SEEN.add(case_id)
    fields['case'] = case_id
    CASES.append(fields)


def run_check_type(type_name, spelling):
    fields = {'kind': 'check_type', 'type': type_name, 'yaml': spelling}
    try:
        out = DEFAULT_TYPE_VALIDATORS[type_name](load(spelling))
    except (TypeError, ValueError) as e:
        fields.update(accepted=False, crash=False, coerced_repr=None, error_class=type(e).__name__)
    except Exception as e:  # noqa: BLE001 - an escaping exception is exactly what a crash case records
        fields.update(accepted=False, crash=True, coerced_repr=None, error_class=type(e).__name__)
    else:
        fields.update(accepted=True, crash=False, error_class=None, **coerced(out))
    record('check_type|%s|%s' % (type_name, spelling), **fields)


def run_validate(spec_yaml, spelling):
    """spelling None means the option is absent from the provided parameters."""
    fields = {'kind': 'validate', 'spec': spec_yaml, 'yaml': spelling}
    spec = normalise_spec(plain(load(spec_yaml)))
    params = {} if spelling is None else {'v': load(spelling)}
    try:
        result = ArgumentSpecValidator({'v': spec}).validate(params)
    except Exception as e:  # noqa: BLE001
        fields.update(accepted=False, crash=True, errors=[], error_class=type(e).__name__, coerced_repr=None)
    else:
        errors = [type(e).__name__ for e in result.errors.errors]
        validated = result.validated_parameters
        fields.update(accepted=not errors, crash=False, errors=errors, error_class=errors[0] if errors else None,
                      **(coerced(validated['v']) if 'v' in validated else {'coerced_repr': None}))
    record('validate|%s|%s' % (spec_yaml, '<absent>' if spelling is None else spelling), **fields)


def run_human_to_bytes(spelling, default_unit, isbits):
    fields = {'kind': 'human_to_bytes', 'yaml': spelling, 'default_unit': default_unit, 'isbits': isbits}
    try:
        out = human_to_bytes(load(spelling), default_unit, isbits)
        fields.update(accepted=True, crash=False, error_class=None, **coerced(out))
    except ValueError as e:
        fields.update(accepted=False, crash=False, coerced_repr=None, error_class=type(e).__name__)
    except Exception as e:  # noqa: BLE001
        fields.update(accepted=False, crash=True, coerced_repr=None, error_class=type(e).__name__)
    record('human_to_bytes|%s|%s|%s' % (spelling, default_unit, isbits), **fields)


def run_boolean(spelling, strict):
    fields = {'kind': 'boolean', 'yaml': spelling, 'strict': strict}
    try:
        out = boolean(load(spelling), strict=strict)
        fields.update(accepted=True, crash=False, error_class=None, **coerced(out))
    except TypeError as e:
        fields.update(accepted=False, crash=False, coerced_repr=None, error_class=type(e).__name__)
    except Exception as e:  # noqa: BLE001
        fields.update(accepted=False, crash=True, coerced_repr=None, error_class=type(e).__name__)
    record('boolean|%s|%s' % (spelling, strict), **fields)


# ------------------------------------------------------------------------------------------------ value corpus

NULLS = ['', '~', 'null', 'Null']
BOOLS = ['true', 'false', 'yes', 'no', 'on', 'off', 'True', 'FALSE', 'Yes', 'y', 'n', 'Y']
STR_BOOLS = ['"yes"', '"no"', '"true"', '"True"', "' Yes '", '"t"', '"f"', '"1"', '"0"', '"on"', '"OFF"',
             '"enabled"', '"y"', '"n"', '""', '"TRUE\\n"', '"1.0"']
INTS = ['0', '1', '2', '-1', '42', '+12', '0644', '010', '08', '0o644', '0x1F', '0b101', '1_000', '1:20', '-0',
        '123456789012345678901234567890', '65535', '1000', '1024', '9007199254740993',
        str(int(sys.float_info.max) + 1), str(2 ** 1024 - 2 ** 970)]
STR_INTS = ['"42"', '" 42 "', '"4_2"', '"_42"', '"0644"', '"0x1F"', '"1e3"', '1e3', '"-7"', '"+5"', '"42.0"',
            '"42.5"', '"1_0.5"', '"inf"', '"-inf"', '"nan"', '"Infinity"', '"sNaN"', '"NaN12"', '"1e400"',
            '"1.0e3"', '"  3.5  "', '"\u0664\u0662"', '"42abc"', '"0.5e1"', '"420e-1"', '"1 2"', '"."', '"5."',
            '"-0.0"', '"1E+2"', '"\\t7\\n"', '"12\u00a0"', '"\u200b1"']
FLOATS = ['42.0', '3.0', '3.2', '3.10', '42.5', '0.5', '1.0', '0.0', '-0.0', '1.0e+3', '1.0e3', '.5', '.inf',
          '-.inf', '.nan', '.NaN', '12.4', '26.7', '1.0e+16', '1.0e+15', '123456789.123456789', '1.5e+400', '2.5',
          '1.5', '0.1', '1e-05', '1.0e-7', '0.0001', '6.02e+23', '-2.5', '1:30.5', '1_000.5', '0.30000000000000004',
          '1234567890123456.0', '12345678901234567.0', '5e-324', '1.7976931348623157e+308']
STR_FLOATS = ['"3.2"', '"1_0"', '"_1"', '"1__0"', '"1e5"', '".5"', '"1.5E-3"', '"infinity"', '"-nan"', '"+Inf"',
              '"1e1_0"', '"1e_1"', '"0x10"', '"1.5e+400"', '"\u0664.5"', '"1.5 "', '"in"']
STRINGS = ['abc', '"abc"', "''", '"a,b"', '"a, b"', '"a,,b"', '","', '"k=v"', '"a=1 b=2"', '"a=1, b=2"',
           '"a=\'x y\' b=2"', '"a=\\"q\\\\\\" z\\" b"', '"a=1, b"', '"a\\\\=1 b=2"', '\'{"a": 1}\'',
           '"{\'a\': 1}"', '"{bad"', '"{}"', '"[1, 2]"', '\'{"a": [1, 2.5, true, null]}\'', '\'{"a": NaN}\'',
           '\'{"a": 1, "a": 2}\'', '\'{"a": "\\u00fc\\n\\ud83d\\ude00"}\'', '\'{"a": 1e400, "b": -0.0, "c": 10}\'',
           '\'{"a": 1} x\'', '\'  {"a": 1}\'', '"{\'a\': None, \'b\': True, \'c\': -1.5}"', '"{\'a\': 1,}"',
           '"{\'a\': [1, {\'b\': \'c\'}], \'d\': {}}"', '"{\'a\': 1}  # comment"', '"{\'a\': x}"', '"{1, 2}"',
           '"{\'a\': 1} + 1"', '"{1: \'a\', 1.0: \'b\', True: \'c\'}"', '"{\'a\': \'x\' \'y\', \\"b\\": r\'\\\\n\'}"',
           '"{\'a\': 0x1F, \'b\': 1_000, \'c\': .5e1, \'d\': 0o17, \'e\': 0b11}"', '"{\'a\': 01}"',
           '"{\'a\': \'\\\\x41\\\\u00e9\\\\101\\\\N{BULLET}\'}"', '"{\'a\': [1, 2], [1]: 2}"', '"{\'a\': -(1)}"',
           '"{\'a\': --1}"', '"{\'a\': set()}"', '"{\'a\': \'\'\'x\'\'\'}"', '"{\'a\': f\'x\'}"', '"{**{}}"',
           '"1K"', '"1KB"', '"1.5G"', '"10"', '"1Mb"', '"1 MB"', '"2 kilobytes"', '"1 megabyte"', '"1 Megabyte"',
           '"1kb"', '"1b"', '"1B"', '"1 byte"', '"1 bit"', '"1Kib"', '"1X"', '"1KiB"', '"K"', '".5K"', '"5.K"',
           '"1.5"', '"0.5"', '"2.5"', '"1K\\n"', '"1K \\n\\n"', '"1Y"', '"-1K"', '" padded "', '"\u00dcn\u00efc\u00f6d\u00e9"',
           '"tab\\there"', '"it\'s"', '\'say "hi"\'', '\'both \'\' and "\'', '"back\\\\slash"', '"bell\\a"',
           '"nel\\N"', '"\\u2028sep"', '"emoji \U0001F600"', '"=x"', '"$GOLDEN_VAR/x"', '"${GOLDEN_VAR}/y"',
           '"$UNSET_VAR/x"', '"${GOLDEN_VAR"', '"$$GOLDEN_VAR"', '"$GOLDEN_EMPTY"', '"~/x"', '"~"', '"~/"', '"/~"',
           '"/etc/x"', '"a=b=c"', '"\'q\'=v"', '"x=1,y=2"', '"  a = 1  "', '"none"', '"all"', '"urllib2"',
           '"present"', '"absent"', '"2024-01-01"', '"3.2.1"', '"0644"', '2024-1-1']
DATES = ['2024-01-01', '2002-12-14', '2001-12-14t21:59:43.10-05:00', '2001-12-14 21:59:43.10 -5',
         '2001-12-15T02:59:43.1Z', '2001-12-15 2:59:43.10', '2024-01-01T00:00:00+00:00', '2024-01-01 00:00:00',
         '2001-12-14 21:59:43.1234567', '2001-12-14T21:59:00+05:30', '2001-12-14T21:59:43.+01']
LISTS = ['[]', '[a, b]', '[1, 2]', '["1", "2"]', '[1, "a"]', '[true, false]', '[1.5]', '[null]', '[[1]]',
         '[{a: 1}]', '[a, 3.2]', '[2024-01-01]', '["x", {k: v}]', '[yes, "no", 1, 2]', '["1K", "2M"]', '["a=1"]',
         '[{name: a}, "name=b"]', '[0, [4, 2], 0]', '[1, [4, 2], -1]', '[0, [4, 2], "F"]', '[0, [4, 2], "n"]',
         '[0, [], 0]', '[true, [4, 2], 0]', '[0, [10], 0]', '[0, "12", 0]', '[0, [4, 2]]', '[0, [4, 2], 1.0]',
         '[2, [1], 0]', '[2001-12-14 21:59:43.10 -5]', '["\u00fc", "a\\"b"]', '[.nan, .inf, -0.0]']
DICTS = ['{}', '{a: 1}', '{a: 1, b: [x]}', '{1: a}', '{a: null}', '{a: 2024-01-01}', '{a: 1.5}',
         '{"it\'s": "x"}', '{a: {b: c}}', '{1: a, 1.0: b}', '{true: x, 1: y}', '{null: n, 2024-01-01: d}',
         '{a: 2001-12-14 21:59:43.10 -5}', '{1.5: x, -0.0: z}']

ALL_VALUES = list(dict.fromkeys(NULLS + BOOLS + STR_BOOLS + INTS + STR_INTS + FLOATS + STR_FLOATS + STRINGS
                                 + DATES + LISTS + DICTS))

TYPES = ['str', 'bool', 'int', 'float', 'list', 'dict', 'path', 'raw', 'jsonarg', 'json', 'bytes', 'bits']

# A representative subset for the (type x required/default) validation grid.
VALIDATE_VALUES = ['', '~', 'true', 'no', 'y', '"yes"', '"1"', '"enabled"', '0', '1', '2', '42', '0644', '1:20',
                   '"42"', '"42.0"', '"1e3"', '1e3', '"inf"', '"nan"', '42.0', '42.5', '3.2', '3.10', '.inf', '.nan',
                   '"3.2"', 'abc', "''", '"a,b"', '"a=1 b=2"', '"a=1, b"', '\'{"a": 1}\'', '"{\'a\': 1}"', '"{bad"',
                   '"1K"', '"1Mb"', '"~/x"', '"$GOLDEN_VAR/x"', '2024-01-01', '2001-12-15T02:59:43.1Z', '[]',
                   '[a, b]', '[1, 2]', '[null]', '[{a: 1}]', '[0, [4, 2], 0]', '{}', '{a: 1}', '{1: a}']

DEFAULTS = {'str': 'x', 'bool': 'false', 'int': '1', 'float': '1.5', 'list': '[]', 'dict': '{}', 'path': '/tmp',
            'raw': '1', 'jsonarg': '"{}"', 'json': '"[]"', 'bytes': '"1K"', 'bits': '"1Kb"', 'string': 'x'}


def main():
    for type_name in TYPES:
        for spelling in ALL_VALUES:
            run_check_type(type_name, spelling)

    # type x {optional, required, spec default} x values, plus absent values
    for type_name in TYPES + ['string']:
        variants = ['{type: %s}' % type_name, '{type: %s, required: true}' % type_name,
                    '{type: %s, default: %s}' % (type_name, DEFAULTS[type_name])]
        for spec in variants:
            run_validate(spec, None)
            for spelling in VALIDATE_VALUES:
                run_validate(spec, spelling)
    for spelling in ['abc', '1', '~', '[1]']:
        run_validate('{}', spelling)
    for spec in ['{type: int, default: abc}', '{type: bool, default: maybe}', '{type: list, default: 5}',
                 '{type: str, default: ~}', '{type: int, default: ~}', '{type: str, required: true, default: x}',
                 '{type: int, required: false}', '{type: str, required: true, default: ~}',
                 '{type: str, default: ""}']:
        for spelling in [None, '~', '5', 'abc']:
            run_validate(spec, spelling)

    # choices: coercion first, per element for lists, the 'True'/'False' rescue, raw not coerced, dict choices
    choice_cases = {
        '{type: str, choices: [a, b]}': ['a', 'c', '1', 'true', '~', '[a]', '"a,b"'],
        '{type: str, choices: ["1", "2"]}': ['2', '"2"', '3', '2.0'],
        '{type: int, choices: [1, 2]}': ['"2"', '3', 'abc', '2.0', 'true', '"1e0"', '~'],
        '{type: raw, choices: ["1", "2"]}': ['2', '"2"', '~'],
        '{type: raw, choices: [1, 2]}': ['2', '"2"', '2.0', 'true'],
        '{type: str, choices: ["yes", "no"]}': ['yes', 'no', 'on', '"yes"', 'True'],
        '{type: str, choices: ["on", "off"]}': ['on', 'off', 'true'],
        '{type: str, choices: ["yes", "true"]}': ['yes', 'true', '"true"'],
        '{type: str, choices: ["no", "off"]}': ['no', 'false'],
        '{type: str, choices: ["yes", 1]}': ['true', '1'],
        '{type: str, choices: [all, "no", none, safe, urllib2, "yes"]}': ['false', 'no', 'none', 'true', 'maybe'],
        '{type: str, choices: {none: "Do not follow", all: "Follow all", safe: "Safe only"}}': ['none', 'false',
                                                                                              'all', 'bogus'],
        '{type: bool, choices: [true]}': ['"yes"', 'false', 'true'],
        '{type: bool, choices: ["True"]}': ['true', '"True"'],
        '{type: list, elements: str, choices: [a, b, c]}': ['"a,c"', '"a,d"', '[a, x]', '[]', 'a', '[a, [b]]'],
        '{type: list, choices: [1, 2]}': ['[1, "2"]', '[1, 2]', '"1,2"', '[1.0, true]'],
        '{type: raw, choices: [null, a]}': ['~', 'a', 'b'],
        '{type: str, choices: [[1], a]}': ['a', 'true', 'b'],
        '{type: str, choices: []}': ['a'],
        '{type: str, choices: [a], default: b}': [None, 'a'],
        '{type: int, choices: [1.0, 2]}': ['1', '"1"'],
        '{type: float, choices: [1, 2.5]}': ['1', '"2.5"', 'true'],
        '{type: str, choices: [a, b], required: true}': [None, '~'],
        '{type: raw, choices: [2024-01-01, x]}': ['2024-01-01', '"2024-01-01"'],
        '{type: dict, choices: [{a: 1}]}': ['{a: 1}', '{a: 1.0}', '"a=1"'],
    }
    for spec, spellings in choice_cases.items():
        for spelling in spellings:
            run_validate(spec, spelling)

    # elements
    element_cases = {
        '{type: list, elements: int}': ['["1", "2"]', '"1,2,3"', '[1.5]', '[a]', '[null]', '[true]', '[42.0]',
                                         '["inf"]', '[]', '5', '"1, 2"'],
        '{type: list, elements: str}': ['[1, 2]', '[{a: 1}]', '[null]', '[[1, 2]]', '[2024-01-01]', '[3.10]', 'a',
                                         '[yes]'],
        '{type: list, elements: bool}': ['[yes, "no", 1, 2]', '["y", "maybe"]', '[]'],
        '{type: list, elements: dict}': ['[{a: 1}]', '["a=1"]', '[a]', '[[1]]', '[{a: 1}, "b=2", ~]'],
        '{type: list, elements: float}': ['[1, "2.5", true]', '["x"]'],
        '{type: list, elements: path}': ['["~/a", "$GOLDEN_VAR"]', '[1]', '[null]'],
        '{type: list, elements: raw}': ['[1, a]', '[]'],
        '{type: list, elements: bytes}': ['["1K", "2M"]', '["1Mb"]', '[1024]'],
        '{type: list, elements: bits}': ['["1Mb"]', '["1MB"]'],
        '{type: list, elements: jsonarg}': ['[{a: 1}, [1], "x "]', '[1]'],
        '{type: list, elements: list}': ['[[1], "a,b", 3]', '[{a: 1}]'],
        '{type: list, elements: string}': ['[a]', '[]'],
        '{type: str, elements: str}': ['ab', '[a]'],
        '{type: raw, elements: str}': ['[1]', 'ab', '5'],
        '{type: dict, elements: str}': ['{a: 1}'],
        '{type: raw, elements: int}': ['5', '["1"]'],
        '{type: list, elements: int, required: true}': ['~', '[]'],
        '{type: list, elements: int, default: [1]}': [None, '~'],
        '{type: list, elements: str, default: ~}': ['~', '[~]'],
    }
    for spec, spellings in element_cases.items():
        for spelling in spellings:
            run_validate(spec, spelling)

    # sub-specs (dict and list of dicts with options), nested required/unsupported, aliases, no_log
    dict_spec = '{type: dict, options: {host: {type: str, required: true}, port: {type: int, default: 80}}}'
    list_spec = ('{type: list, elements: dict, options: {name: {type: str, required: true}, '
                 'secret_file_src: {type: path}}}')
    nested_spec = '{type: dict, options: {sub: {type: dict, options: {x: {type: int}}}, flag: {type: bool}}}'
    alias_spec = '{type: dict, options: {name: {type: str, aliases: [n, nm]}, count: {type: int, aliases: [c]}}}'
    sub_cases = {
        dict_spec: ['{host: a}', '{host: a, port: "444"}', '{port: 1}', '{host: a, extra: 1}',
                    '{host: a, port: abc}', '"host=a port=2"', '~', '[]', '5', 'abc', '{host: ~}', '{}',
                    '{host: a, extra: 1, other: 2}', '[{host: a}]', '"{\'host\': \'b\'}"', None],
        list_spec: ['[{name: a}]', '[{name: a, extra: 1}, {name: b, other: 2}]', '[{}]', '[a]',
                    '[{name: a}, "name=b"]', '"name=a"', '[]', '~', '{name: a}', '[{name: a, secret_file_src: 1}]',
                    '[{name: a, secret_file_src: "~/s"}]', '[[1]]', '[{name: 1.5}, {name: yes}]', None],
        nested_spec: ['{sub: {x: "1", y: 2}}', '{sub: {x: abc}}', '{sub: 5}', '{sub: ~}', '{flag: maybe}',
                      '{sub: "x=3"}', '{sub: {x: 1}, flag: yes, z: 1}'],
        alias_spec: ['{n: a}', '{name: a, n: b}', '{nm: a, c: "3"}', '{c: x}', '{name: a, count: 1, cc: 2}'],
        '{type: dict, options: {a: {type: str, choices: [x, y]}, b: {type: list, elements: int}}}': [
            '{a: z}', '{a: x, b: "1,2"}', '{b: [a]}', '{a: true}'],
        '{type: dict, options: {a: {type: str, required: true, default: x}}}': ['{a: 1}', '{a: 1, z: 2}', '{}'],
        '{type: dict, options: {a: {type: int, default: 5}, b: {type: str}}}': ['{}', '{b: ~}', '{a: ~}'],
        '{type: dict, options: ~}': ['{a: 1}', '"a=1"'],
        '{type: list, elements: dict, options: ~}': ['[{a: 1}]', '["a=1"]'],
        '{type: dict, options: {}}': ['{a: 1}', '{}'],
        '{type: raw, options: {a: {type: int}}}': ['{a: x}'],
        '{type: list, options: {a: {type: int}}}': ['[{a: x}]'],
        '{type: dict, required: true, options: {a: {type: int}}}': ['{a: "7"}', '~', None],
        '{type: dict, default: {a: "2"}, options: {a: {type: int}}}': [None, '{}'],
        '{type: dict, no_log: true, options: {a: {type: str, no_log: true}}}': ['{a: s3cr3t}', '{a: 2024-01-01}',
                                                                                  '5'],
        '{type: raw, no_log: true}': ['2024-01-01', '[1, [2]]', 'secret', '5'],
        ('{type: list, elements: dict, options: {client_id: {type: str, required: true}, '
         'default_client_scopes: {type: list, elements: str}}}'): [
            '[{client_id: a, default_client_scopes: [x], optional_client_scopes: [y]}]',
            '[{client_id: a}, {client_id: b, optional_client_scopes: [y]}]'],
    }
    for spec, spellings in sub_cases.items():
        for spelling in spellings:
            run_validate(spec, spelling)

    # human_to_bytes directly
    for spelling in ['"1K"', '"1KB"', '"1Kb"', '"1.5G"', '"10"', '1024', '"1 MB"', '"1MB "', '"1Mb"', '"1 megabyte"',
                     '"1 Megabyte"', '"1 megabit"', '"abc"', '""', '"1.5"', '"0.5"', '"2.5"', '"3.5"', '"1e3"',
                     '"-1K"', '"1Y"', '"1X"', '"1KiB"', '"1 kilobit"', '"K"', '".5K"', '"5.K"', '1.5', '2.5', 'true',
                     '~', '"1K\\n"', '"1 bytes"', '"1 byte"', '"1b"', '"1B"', '"12 bits"', '"1.1b"',
                     '"' + '9' * 400 + '"', '"' + '9' * 300 + 'Y"', '"1e+16"', '1.0e+16', '[1]']:
        for default_unit in [None, 'M', 'Mb', 'kilobyte', 'X']:
            for isbits in [False, True]:
                run_human_to_bytes(spelling, default_unit, isbits)

    # convert_bool.boolean directly (strict and lenient)
    for spelling in BOOLS + STR_BOOLS + ['0', '1', '2', '1.0', '0.0', '-0.0', '0.5', '.nan', '~', '[]', '[1]', '{}',
                                         '2024-01-01', 'abc', '"  on  "', '"\u0130"']:
        for strict in [True, False]:
            run_boolean(spelling, strict)

    out = sys.stdout
    out.write('{"ansible_core": %s, "python": %s, "generator": "tools/docgen/goldens/gen_goldens.py",\n'
              % (json.dumps(ANSIBLE_CORE_VERSION), json.dumps(sys.version.split()[0])))
    out.write(' "cases": [\n')
    out.write(',\n'.join('  ' + json.dumps(case, sort_keys=True) for case in CASES))
    out.write('\n]}\n')


if __name__ == '__main__':
    main()
