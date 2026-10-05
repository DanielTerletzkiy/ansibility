#!/usr/bin/env python3
"""Turn the raw results of run_golden.sh into a render-oracle case's expected outputs.

    finalize.py <case-dir> <raw-dir> [--expected NAME]        (NAME defaults to "expected")

Paths are normalised: the work directory of each run becomes "<pb>", memory addresses become 0x... .

When <raw-dir> holds every primary version of versions.json (2.21 and 2.18), <case>/<NAME>/ is rebuilt:
  common/<file>             rendered output identical on all primary versions
  <label>/<file>            rendered output where the primary versions differ, or that exists on one side only
  tasks.json                per task / host (/ loop item): msg, facts, error or skipped, as "both" when the primary
                            versions agree and as "<label>" otherwise; "task_<label>" when a version names it otherwise
  expressions.json          expression-oracle cases (input/cases.json): per case the template output ("template" or
                            "template_error"), the stored task-argument value ("arg" or "arg_error") and type_debug
                            ("jinja_type"), as "both" or per version
  stderr.<label>.txt        warnings and deprecations of that version (only when non-empty)
  summary.json              rc per version, which outputs are the same, differ, or exist on one side only

Raw results of extra versions (versions.json "extra", spike S-R1) are laid over the primary data, which must exist
already, without changing it: <label>/<file> only where the output differs from common/ (or common/ has none), a
"<label>" key only where it differs from "both" (always where the primary versions differ), "task_<label>" for a
differing task name, and summary.json "extra" with rc, core, the differing files and the common files the version did
not produce. A reader resolves version V as: <label>/<file> or else common/<file> unless listed as missing; the
"<label>" key or else "both". A null "<label>" means the version did not run that task.
"""
import argparse
import json
import os
import re
import shutil
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
VERSIONS = json.load(open(os.path.join(HERE, "versions.json"), encoding="utf-8"))
PRIMARY = VERSIONS["primary"]


def raw_versions(raw):
    return [v for v in VERSIONS["versions"] if os.path.isfile(os.path.join(raw, v, "rc.txt"))]


def check_version(raw, ver):
    core = VERSIONS["versions"][ver]["core"]
    seen = open(os.path.join(raw, ver, "version.txt"), encoding="utf-8").read().strip()
    if "core %s]" % core not in seen:
        sys.exit("%s: the raw run of %s reports %r, expected core %s" % (raw, ver, seen, core))


def workdirs(raw, ver):
    wd = open(os.path.join(raw, ver, "workdir.txt")).read().strip()
    out = {wd}
    if VERSIONS["versions"][ver]["runner"] == "local":
        out.add(os.path.realpath(wd))
        for p in list(out):
            if p.startswith("/private/"):
                out.add(p[len("/private"):])
    return sorted(out, key=len, reverse=True)


class Normaliser:
    def __init__(self, raw):
        self.raw = raw
        self.dirs = {}

    def text(self, s, ver):
        if ver not in self.dirs:
            self.dirs[ver] = workdirs(self.raw, ver)
        for wd in self.dirs[ver]:
            s = s.replace(wd, "<pb>")
        return re.sub(r"0x[0-9a-f]{6,}", "0x...", s)

    def bytes(self, b, ver):
        try:
            return self.text(b.decode("utf-8"), ver).encode("utf-8")
        except UnicodeDecodeError:
            return b

    def short(self, msg, ver):
        return re.sub(r"\s+", " ", self.text(str(msg), ver)).strip()[:400]

    def json(self, v, ver):
        return json.loads(self.text(json.dumps(v, ensure_ascii=False), ver))


def is_failed(r):
    return bool(r.get("failed")) or "exception" in r


def is_skipped(r):
    return bool(r.get("skipped")) or "skip_reason" in r


def item_result(r, ver, n):
    if is_skipped(r):
        return {"skipped": True}
    if is_failed(r):
        return {"error": n.short(r.get("msg"), ver)}
    out = {}
    if "msg" in r:
        out["msg"] = n.json(r["msg"], ver)
    facts = {k: v for k, v in (r.get("ansible_facts") or {}).items() if k != "discovered_interpreter_python"}
    if facts:
        out["facts"] = n.json(facts, ver)
    if not out:
        out["ok"] = True
    return out


def log_doc(raw, ver):
    text = open(os.path.join(raw, ver, "log.txt"), encoding="utf-8").read()
    start = text.find("{")
    return json.loads(text[start:]) if start >= 0 else {"plays": []}


def load_tasks(raw, ver, n):
    tasks = []
    for p in log_doc(raw, ver)["plays"]:
        play = p["play"]["name"]
        for t in p["tasks"]:
            per = {}
            for h, r in t["hosts"].items():
                if "results" in r:
                    items = []
                    for it in r["results"]:
                        lv = it.get("ansible_loop_var", "item")
                        items.append({"item": n.json(it.get(lv), ver), **item_result(it, ver, n)})
                    per[h] = {"items": items}
                else:
                    per[h] = item_result(r, ver, n)
            tasks.append({"play": play, "task": n.text(t["task"]["name"], ver), "hosts": per})
    return tasks


def merge_tasks(a, b, va, vb):
    out = []
    for i in range(max(len(a), len(b))):
        x = a[i] if i < len(a) else None
        y = b[i] if i < len(b) else None
        if x and y and x["task"] == y["task"] and x["hosts"] == y["hosts"]:
            out.append({"play": x["play"], "task": x["task"], "both": x["hosts"]})
        else:
            e = {"play": (x or y)["play"], "task": (x or y)["task"]}
            if x and y and x["task"] != y["task"]:
                e["task_" + vb] = y["task"]
            e[va] = x["hosts"] if x else None
            e[vb] = y["hosts"] if y else None
            out.append(e)
    return out


def load_expressions(case, raw, ver, n):
    res = {}
    for play in log_doc(raw, ver)["plays"]:
        for t in play["tasks"]:
            name = t["task"]["name"]
            h = t["hosts"].get("localhost", {})
            if name.startswith("A:"):
                e = res.setdefault(name[2:], {})
                if is_failed(h):
                    e["arg_error"] = n.short(h.get("msg"), ver)
                else:
                    e["arg"] = n.json(h.get("ansible_facts", {}).get("r_" + name[2:]), ver)
            elif name.startswith("T:"):
                e = res.setdefault(name[2:], {})
                if not is_failed(h):
                    e["jinja_type"] = h.get("msg")
            elif name == "TEMPLATES":
                for r in h.get("results", []):
                    e = res.setdefault(r.get("case_id"), {})
                    if is_failed(r):
                        e["template_error"] = n.short(r.get("msg"), ver)
                    else:
                        p = os.path.join(raw, ver, "out", r.get("case_id") + ".txt")
                        e["template"] = n.text(open(p, encoding="utf-8", newline="").read(), ver) \
                            if os.path.exists(p) else None
    return res


def out_files(raw, ver, n):
    base = os.path.join(raw, ver, "out")
    files = {}
    for root, _, fs in os.walk(base):
        for f in fs:
            rel = os.path.relpath(os.path.join(root, f), base)
            files[rel] = n.bytes(open(os.path.join(root, f), "rb").read(), ver)
    return files


def write_bytes(path, data):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    open(path, "wb").write(data)


def dump(obj, path, ascii_only=False):
    json.dump(obj, open(path, "w", encoding="utf-8"), indent=1, ensure_ascii=ascii_only)


def write_stderr(exp, raw, ver, n):
    err = n.text(open(os.path.join(raw, ver, "err.txt"), encoding="utf-8").read(), ver).strip()
    path = os.path.join(exp, "stderr." + ver + ".txt")
    if err:
        open(path, "w", encoding="utf-8").write(err + "\n")
    elif os.path.exists(path):
        os.remove(path)


def rc(raw, ver):
    return open(os.path.join(raw, ver, "rc.txt")).read().strip()


def rebuild_primary(case, raw, exp, n, expr_mode):
    va, vb = PRIMARY
    if os.path.isdir(exp):
        shutil.rmtree(exp)
    os.makedirs(exp)
    summary = {"rc": {v: rc(raw, v) for v in PRIMARY}, "same": [], "different": [], "only_" + va: [], "only_" + vb: []}
    if expr_mode:
        cases = json.load(open(os.path.join(case, "input", "cases.json"), encoding="utf-8"))
        per = {v: load_expressions(case, raw, v, n) for v in PRIMARY}
        rows = []
        for cid, expr in cases:
            a, b = per[va].get(cid, {}), per[vb].get(cid, {})
            row = {"id": cid, "expr": expr}
            if a == b:
                row["both"] = a
            else:
                row[va], row[vb] = a, b
            rows.append(row)
        dump(rows, os.path.join(exp, "expressions.json"))
    else:
        dump(merge_tasks(load_tasks(raw, va, n), load_tasks(raw, vb, n), va, vb), os.path.join(exp, "tasks.json"))
        files = {}
        for v in PRIMARY:
            for rel, data in out_files(raw, v, n).items():
                files.setdefault(rel, {})[v] = data
        for rel in sorted(files):
            got = files[rel]
            if len(got) == 2 and got[va] == got[vb]:
                write_bytes(os.path.join(exp, "common", rel), got[va])
                summary["same"].append(rel)
            else:
                for v, data in got.items():
                    write_bytes(os.path.join(exp, v, rel), data)
                summary["different" if len(got) == 2 else "only_" + next(iter(got))].append(rel)
    for v in PRIMARY:
        write_stderr(exp, raw, v, n)
    dump(summary, os.path.join(exp, "summary.json"), ascii_only=True)
    return summary


def overlay_extra(case, raw, exp, n, expr_mode, ver):
    """Lays the raw results of the extra version [ver] over the primary data in [exp]."""
    summary_path = os.path.join(exp, "summary.json")
    if not os.path.isfile(summary_path):
        sys.exit("%s: no primary %s/summary.json to lay %s over; run the primary versions first" % (case, exp, ver))
    summary = json.load(open(summary_path, encoding="utf-8"))
    shutil.rmtree(os.path.join(exp, ver), ignore_errors=True)
    info = {"rc": rc(raw, ver), "core": VERSIONS["versions"][ver]["core"], "differs": [], "missing": []}
    if expr_mode:
        path = os.path.join(exp, "expressions.json")
        rows = json.load(open(path, encoding="utf-8"))
        got = load_expressions(case, raw, ver, n)
        for row in rows:
            row.pop(ver, None)
            r = got.get(row["id"], {})
            if "both" not in row or r != row["both"]:
                row[ver] = r
                info["differs"].append(row["id"])
        dump(rows, path)
    else:
        path = os.path.join(exp, "tasks.json")
        entries = json.load(open(path, encoding="utf-8"))
        for e in entries:
            e.pop(ver, None)
            e.pop("task_" + ver, None)
        # An entry that only an earlier overlay of this version appended is dropped with it.
        entries = [e for e in entries if "both" in e or any(e.get(k) is not None for k in VERSIONS["versions"])]
        tasks = load_tasks(raw, ver, n)
        for i, e in enumerate(entries):
            y = tasks[i] if i < len(tasks) else None
            if y is None:
                e[ver] = None
                continue
            if y["task"] != e["task"]:
                e["task_" + ver] = y["task"]
            if "both" not in e or y["hosts"] != e["both"]:
                e[ver] = y["hosts"]
        for y in tasks[len(entries):]:
            e = {"play": y["play"], "task": y["task"], **{v: None for v in PRIMARY}, ver: y["hosts"]}
            entries.append(e)
        dump(entries, path)
        common = os.path.join(exp, "common")
        common_files = set()
        for root, _, fs in os.walk(common):
            for f in fs:
                common_files.add(os.path.relpath(os.path.join(root, f), common))
        files = out_files(raw, ver, n)
        for rel in sorted(files):
            c = os.path.join(common, rel)
            if rel in common_files and open(c, "rb").read() == files[rel]:
                continue
            write_bytes(os.path.join(exp, ver, rel), files[rel])
            info["differs"].append(rel)
        info["missing"] = sorted(common_files - set(files))
    write_stderr(exp, raw, ver, n)
    summary.setdefault("extra", {})[ver] = info
    summary["extra"] = {k: summary["extra"][k] for k in sorted(summary["extra"])}
    dump(summary, summary_path, ascii_only=True)
    return info


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("case")
    ap.add_argument("raw")
    ap.add_argument("--expected", default="expected")
    args = ap.parse_args()
    case, raw = os.path.abspath(args.case), os.path.abspath(args.raw)
    exp = os.path.join(case, args.expected)
    present = raw_versions(raw)
    for v in present:
        check_version(raw, v)
    primary = [v for v in present if v in PRIMARY]
    if primary and primary != [v for v in PRIMARY]:
        sys.exit("%s: raw results of %s only; the primary versions %s are rebuilt together" % (case, primary, PRIMARY))
    n = Normaliser(raw)
    expr_mode = os.path.exists(os.path.join(case, "input", "cases.json"))
    line = os.path.basename(case)
    if primary:
        s = rebuild_primary(case, raw, exp, n, expr_mode)
        line += " %s same=%d different=%d %s" % (s["rc"], len(s["same"]), len(s["different"]),
                                                 " ".join("only%s=%d" % (v, len(s["only_" + v])) for v in PRIMARY))
    for v in present:
        if v not in PRIMARY:
            info = overlay_extra(case, raw, exp, n, expr_mode, v)
            line += " | %s %s differs=%d missing=%d" % (v, info["rc"], len(info["differs"]), len(info["missing"]))
    print(line)


main()
