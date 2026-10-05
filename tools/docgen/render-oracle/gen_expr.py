#!/usr/bin/env python3
"""Write the input/ directory of an expression-oracle case from a Python case module (called by build_inputs.py).

    gen_expr.py <expr/cases_*.py> <case>/input

The module defines VARS (a synthetic dict, written as vars.json), optionally VARS_YAML (YAML 1.1 typed values,
written as vars.yml) and INVENTORY, and CASES: a list of (id, Jinja expression text). The text is the exact Jinja
source between {{ and }} as Jinja sees it (after YAML decoding). The playbook is written as JSON, which is valid YAML,
so no YAML escaping changes the expression.

Per case three things run, and finalize.py collects them into expected/expressions.json:
  * task "A:<id>": set_fact r_<id>: "{{ <expr> }}"         -> "arg": the stored task-argument value
  * task "T:<id>": debug msg: "{{ (<expr>) | type_debug }}" -> "jinja_type": the Python type inside Jinja
  * one loop item of the task TEMPLATES: templates/e/<id>.j2 = "{{ <expr> }}" (no trailing newline)
                                                             -> "template": the rendered file text (out/<id>.txt)
"""
import importlib.util
import json
import os
import sys


def main(module_path, out):
    spec = importlib.util.spec_from_file_location("cases", module_path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    os.makedirs(os.path.join(out, "templates", "e"), exist_ok=True)

    ids = []
    tasks = []
    for cid, expr in mod.CASES:
        assert cid.isidentifier(), cid
        ids.append(cid)
        tasks.append({"name": "A:" + cid, "ansible.builtin.set_fact": {"r_" + cid: "{{ " + expr + " }}"},
                      "ignore_errors": True})
        tasks.append({"name": "T:" + cid, "ansible.builtin.debug": {"msg": "{{ (" + expr + ") | type_debug }}"},
                      "ignore_errors": True})
        with open(os.path.join(out, "templates", "e", cid + ".j2"), "w", encoding="utf-8") as fh:
            fh.write("{{ " + expr + " }}")
    tasks.append({"name": "TEMPLATES", "ansible.builtin.template": {
        "src": "e/{{ case_id }}.j2", "dest": "{{ playbook_dir }}/out/{{ case_id }}.txt"},
        "loop": ids, "loop_control": {"loop_var": "case_id"}, "ignore_errors": True})

    vfiles = ["vars.json"]
    if hasattr(mod, "VARS_YAML"):
        with open(os.path.join(out, "vars.yml"), "w", encoding="utf-8") as fh:
            fh.write(mod.VARS_YAML)
        vfiles.append("vars.yml")
    play = [{"hosts": "localhost", "gather_facts": False, "vars_files": vfiles, "tasks": tasks}]
    with open(os.path.join(out, "site.yml"), "w", encoding="utf-8") as fh:
        json.dump(play, fh, indent=1, ensure_ascii=False)
    with open(os.path.join(out, "vars.json"), "w", encoding="utf-8") as fh:
        json.dump(mod.VARS, fh, indent=1, ensure_ascii=False)
    with open(os.path.join(out, "inventory"), "w") as fh:
        fh.write(getattr(mod, "INVENTORY", "localhost ansible_connection=local\n"))
    with open(os.path.join(out, "cases.json"), "w", encoding="utf-8") as fh:
        json.dump(mod.CASES, fh, indent=1, ensure_ascii=False)
    print(len(ids), "cases")


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    main(sys.argv[1], sys.argv[2])
