#!/usr/bin/env python3
"""Write comparison.json of case 15 from the harness outputs (called by regen.sh).

    compare.py <cases.json> <python-A.json> <python-B.json> <java-X.json> > comparison.json

One row per pattern: the pattern, flags, input and replacement, each Python's result ("ok", "err", and "warn" when
Python printed a warning), the Java results without ("java_naive") and with ("java_compat") the flags
UNICODE_CHARACTER_CLASS | UNICODE_CASE | UNIX_LINES, and whether each Java result agrees with the newest Python: the
same "ok" text, or an error on both sides.
"""
import json
import sys


def key(doc):
    major, minor = doc["python"].split(".")[:2]
    return "python_%s_%s" % (major, minor)


def agrees(p, j):
    return ("ok" in p and "ok" in j and p["ok"] == j["ok"]) or ("err" in p and "err" in j)


def main(cases_path, *paths):
    cases = json.load(open(cases_path, encoding="utf-8"))
    pythons = sorted((json.load(open(p, encoding="utf-8")) for p in paths[:-1]),
                     key=lambda d: tuple(int(x) for x in d["python"].split(".")))
    java = json.load(open(paths[-1], encoding="utf-8"))
    newest = key(pythons[-1])
    rows = []
    for cid, pattern, flags, text, replacement in cases:
        row = {"id": cid, "pattern": pattern, "flags": flags, "input": text, "replacement": replacement}
        for doc in pythons:
            row[key(doc)] = doc[cid]
        row["java_naive"] = java[cid + ".naive"]
        row["java_compat"] = java[cid + ".compat"]
        for mode in ("compat", "naive"):
            row["java_%s_agrees_with_%s" % (mode, newest)] = agrees(row[newest], row["java_" + mode])
        rows.append(row)
    json.dump(rows, sys.stdout, indent=1, ensure_ascii=False)


if __name__ == "__main__":
    if len(sys.argv) < 5:
        sys.exit(__doc__)
    main(*sys.argv[1:])
