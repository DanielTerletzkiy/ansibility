#!/usr/bin/env python3
"""Write case.json of every render-oracle case (or of the named ones): static metadata read by the :semantics reader.

    write_case_json.py [case ...]

case.json keys:
  case, title, pins        what the case shows (pins: the facts it pins, as measured)
  kind                     "files" (expected outputs + tasks.json), "expressions" (expressions.json),
                           "hash_seeds" (one expected_hashseed_<n>/ per PYTHONHASHSEED) or "regex" (Python re vs Java)
  versions                 label -> {core, python, jinja2, where}: every version measured for this case
  primary, extra           the labels whose outputs define expected/common and expected/<label>, and the labels
                           laid over them (spike S-R1); see finalize.py for the resolution rule
  order_varies             expressions whose result order depends on the hash seed (a renderer must mark the order)
  regenerate, layout, synthetic
"""
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
CASES_DIR = os.path.normpath(os.path.join(HERE, "..", "..", "..", "semantics", "src", "test", "resources", "render-oracle"))
VERSIONS = json.load(open(os.path.join(HERE, "versions.json"), encoding="utf-8"))
TOOLS = "tools/docgen/render-oracle"
REGEN = TOOLS + "/run_some.sh {case}   (inputs: " + TOOLS + "/build_inputs.py; all cases: " + TOOLS + "/run_all.sh)"
LAYOUT = ("input/ = what ran; expected/common = identical output on all primary versions; expected/<label> = output "
          "that differs or exists on one side only (for an extra version: only where it differs from common); "
          "tasks.json / expressions.json: 'both' or per-version keys; summary.json; stderr.<label>.txt")
SEED_DEPENDENT = "hash seed: list(set(a) op set(b)) on strings; the members are fixed, their order is not"
CASES = {
 "01_user_example_fileglob": ("The user's example: fileglob loop over role templates and the dest expression",
   ["loop: lookup('fileglob', role_path ~ '/templates/config-*.alloy.j2', wantlist=True) | sort -> 3 absolute paths, sorted",
    "dest expression per item -> /etc/alloy/base.alloy, journal.alloy, node-exporter.alloy (both versions)",
    "rendered config files per host: host-b's inventory variable beats the role default; identical on 2.21 and 2.18",
    "with_fileglob: ['templates/config-base.*.j2'] resolves against the role (files/templates first, then templates/)",
    "four backslashes in YAML (Jinja text '\\\\.j2$') do not strip .j2 in a task arg: /etc/alloy/base.alloy.j2"]),
 "02_template_env_defaults": ("Template module environment: whitespace control, markers, #jinja2: header, newlines",
   ["variants out/default, out/lstrip (lstrip_blocks: true), out/notrim (trim_blocks: false), out/crlf (newline_sequence CRLF), out/markers",
    "hdr_bad.j2: 2.18 ignores an unknown header key, 2.21 fails (see tasks.json)",
    "only_block.j2 / trail_one / trail_two / hdr_newline_seq differ by version (trailing newline re-append rules)"]),
 "03_trailing_newlines": ("Trailing newlines and newline_sequence (out/lf and out/crlf)",
   ["2.21: a single non-string output node or no output node drops the re-appended trailing newline",
    "2.21: a file without template syntax is copied verbatim (CRLF kept, newline_sequence not applied)",
    "2.18 re-appends with the #jinja2: header's newline_sequence, 2.21 with the task's"]),
 "04_value_rendering": ("How values print in a template and what a task arg stores (expressions.json)",
   ["template = rendered file text; arg = set_fact value as the json callback prints it (dict keys sorted by the callback); jinja_type = type_debug",
    "YAML 1.1 typed values from vars.yml (octal, sexagesimal, 3.10, 1e3 string, dates, !unsafe)",
    "tuples: 2.18 prints (1, 'a'), 2.21 [1, 'a']"]),
 "04n_value_rendering_jinja2_native": ("Same cases with ANSIBLE_JINJA2_NATIVE=True",
   ["2.18: None prints 'None' in templates; task-arg strings are literal_eval'd ('0o644' -> 420, '1e3' -> 1000.0)",
    "2.21: the option is deprecated and ignored (stderr.2.21.txt)"]),
 "05_filters_tests_operators": ("Filters, tests and operators: 355 expressions (expressions.json)",
   ["each case is rendered three ways: set_fact (arg, YAML task-arg backslash rules apply), debug type_debug, template file",
    "arg and template differ for backslash-bearing cases: task args double backslashes inside {{ }} string literals"]),
 "06_filters_more": ("More filters/lookups: dumps, regex flags, numbers, set order, pure lookups (expressions.json)",
   ["union/difference/symmetric_difference/intersect on strings: the order depends on the hash seed (order_varies; see 14_set_filter_order)"]),
 "07_undefined": ("Undefined handling in templates, task args and when:",
   ["StrictUndefined-like printing/iteration/comparison; chained attribute access stays undefined until used; default() and is defined at any depth",
    "2.21: when: needs a boolean result; 2.18: truthiness",
    "{{ omit }} in a template: 2.21 error, 2.18 placeholder string"]),
 "08_control_structures": ("Statements: for/loop.*, set scoping, namespace, macros, call, include/import context, extends",
   ["include passes the context (locals and task vars); import/from-import default to without context (no task vars either)",
    "an included template loses its trailing newline"]),
 "09_backslash_escaping": ("Backslashes: task args ({{ }} literals escaped) versus {% %}, when: and template files",
   ["tasks.json BS:* tasks; out/backslash.txt and out/user_example.txt are the template-file side",
    "BS:when_curly_*: a {{ }} inside when: follows the task-arg rule on both cores: '\\d' runs, '\\\\d' skips"]),
 "10_magic_vars": ("Magic variables a role template sees (deterministic subset), include search path",
   ["hostvars[h] has inventory vars but no role defaults (also for the current host)",
    "template_host/uid/run_date/mtime are printed only as types; lookup('template') sets template_path to the term and template_destpath empty"]),
 "11_ansible_managed": ("ansible_managed: default, user variable, | comment",
   ["2.18: the config string wins over a user variable; 2.21: the user variable wins"]),
 "11b_ansible_managed_cfg": ("ansible_managed from ansible.cfg ('Managed by Ansible: {file}')",
   ["2.21 prints a deprecation for DEFAULT_MANAGED_STR (stderr.2.21.txt)"]),
 "12_lookup_search_paths": ("fileglob/first_found/file/template lookups and the template src search path in a role",
   ["fileglob: a pattern with a directory globs only the first existing of files/<dir>, <dir>, tasks/files/<dir>, tasks/<dir>, playbook files/<dir>, playbook <dir>",
    "fileglob without a directory: first search-path dir with a match wins (role files/ before role root before playbook)",
    "lookup('fileglob') without wantlist is a comma-joined string; loop over it fails",
    "template src search: role templates/, role root, role tasks/templates/, role tasks/, playbook templates/, playbook dir (never files/)"]),
 "13_loop_items": ("Loop item shapes of with_* lookups versus loop; undefined names in a task name",
   ["W16/W17: an undefined name in a task name never fails; 2.21 prints << error 1 - 'item' is undefined >> and renders the other parts, 2.18 keeps the raw name text"]),
 "14_set_filter_order": ("Set filters on strings under PYTHONHASHSEED=1 and =2 (expected_hashseed_1/, expected_hashseed_2/)",
   ["union/difference/symmetric_difference/intersect use list(set(a) op set(b)): order of strings changes with the hash seed",
    "unique and the unhashable (dict) fallback keep order; small ints come out in set order"]),
 "15_regex_python_vs_java": ("Python re (3.13.15 / 3.14.7) versus java.util.regex (JDK 25) on 35 patterns",
   ["expected/comparison.json: naive Java agrees with Python 3.14 on 14 of 35, with UNICODE_CHARACTER_CLASS|UNICODE_CASE|UNIX_LINES on 22; the other 13 need syntax translation or abstention",
    "Python 3.13 rejects \\z, 3.14 accepts it: the controller's Python decides, so no core proves the result"]),
}
ORDER_VARIES = {
    "06_filters_more": {cid: SEED_DEPENDENT for cid in ("union_str", "difference_str", "intersect_str", "symdiff_str")},
}


def version_entry(label):
    v = VERSIONS["versions"][label]
    return {"core": v["core"], "python": v["python"], "jinja2": v["jinja2"],
            "where": v.get("where") or "docker %s, --network none" % v["image"]}


def kind_of(d):
    if os.path.isfile(os.path.join(d, "expected", "comparison.json")):
        return "regex"
    if os.path.isdir(os.path.join(d, "expected_hashseed_1")):
        return "hash_seeds"
    if os.path.isfile(os.path.join(d, "expected", "expressions.json")):
        return "expressions"
    return "files"


def extras_of(d, kind):
    if kind == "regex":
        return []
    summary = os.path.join(d, "expected_hashseed_1" if kind == "hash_seeds" else "expected", "summary.json")
    return sorted(json.load(open(summary, encoding="utf-8")).get("extra", {}))


def main(names):
    for case in names or sorted(CASES):
        if case not in CASES:
            sys.exit("unknown case %r" % case)
        d = os.path.join(CASES_DIR, case)
        if not os.path.isdir(d):
            sys.exit("%s is missing" % d)
        title, pins = CASES[case]
        kind = kind_of(d)
        extra = extras_of(d, kind)
        meta = {"case": case, "title": title, "kind": kind, "pins": pins,
                "versions": {label: version_entry(label) for label in VERSIONS["primary"] + extra},
                "primary": VERSIONS["primary"], "extra": extra}
        if case in ORDER_VARIES:
            meta["order_varies"] = ORDER_VARIES[case]
        if kind == "regex":
            java = [f for f in os.listdir(os.path.join(d, "expected")) if f.startswith("java-") and f.endswith(".json")]
            meta["java"] = json.load(open(os.path.join(d, "expected", java[0]), encoding="utf-8"))["java"]
            meta["regenerate"] = TOOLS + "/regex/regen.sh <path to a JDK 25 java>"
        else:
            meta["regenerate"] = REGEN.format(case=case)
        meta["layout"] = LAYOUT
        meta["synthetic"] = True
        with open(os.path.join(d, "case.json"), "w", encoding="utf-8") as fh:
            json.dump(meta, fh, indent=1, ensure_ascii=False)
            fh.write("\n")
    print("ok")


if __name__ == "__main__":
    main(sys.argv[1:])
