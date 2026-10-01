#!/usr/bin/env python3
"""Regenerate the inventory/precedence oracle used by the :semantics tests.

Every case lives in semantics/src/test/resources/inventory-oracle/<case>/:

    oracle.json            {"description": ..., "hash_behaviour": "merge"?, "precedence": "a,b,..."?}
    inventory/hosts.yml    the YAML inventory (-i)
    inventory/group_vars/  inventory-adjacent vars (levels 4, 6)
    inventory/host_vars/   inventory-adjacent vars (level 9)
    group_vars/, host_vars/  playbook-adjacent vars (levels 5, 7, 10); the case dir is the --playbook-dir

For each case the script copies the case to a fresh temporary directory (never running inside a source tree),
runs the LOCAL ansible-inventory

    ansible-inventory -i <tmp>/inventory/hosts.yml --playbook-dir <tmp> --list </dev/null

with an empty ANSIBLE_CONFIG, only the yaml inventory plugin enabled, and ANSIBLE_HASH_BEHAVIOUR /
ANSIBLE_PRECEDENCE taken from oracle.json, and writes <case>/expected.json:

    {"ansible_core": "<version>", "inventory": <the --list output>}

Usage: tools/docgen/inventory-oracle/gen.py [--ansible-inventory PATH] [case ...]
"""
import argparse
import json
import os
import pathlib
import shutil
import subprocess
import sys
import tempfile

REPO = pathlib.Path(__file__).resolve().parents[3]
CASES = REPO / "semantics" / "src" / "test" / "resources" / "inventory-oracle"


def core_version(binary):
    out = subprocess.run([binary, "--version"], stdin=subprocess.DEVNULL, capture_output=True, text=True, check=True)
    first = out.stdout.splitlines()[0]  # "ansible-inventory [core 2.21.4]"
    return first.split("core", 1)[1].strip(" ]")


def run_case(binary, case_dir, version):
    config = json.loads((case_dir / "oracle.json").read_text())
    with tempfile.TemporaryDirectory(prefix="ansibility-inventory-oracle-") as tmp:
        tmp = pathlib.Path(tmp)
        work = tmp / case_dir.name
        shutil.copytree(case_dir, work, ignore=shutil.ignore_patterns("expected.json", "oracle.json"))
        empty_cfg = tmp / "empty.cfg"
        empty_cfg.write_text("")
        env = {k: v for k, v in os.environ.items() if not k.startswith("ANSIBLE_")}
        env.update(
            ANSIBLE_CONFIG=str(empty_cfg),
            ANSIBLE_HOME=str(tmp / "ansible-home"),
            ANSIBLE_INVENTORY_ENABLED="yaml",
            ANSIBLE_INVENTORY_UNPARSED_FAILED="True",
            ANSIBLE_NOCOLOR="1",
        )
        if config.get("hash_behaviour"):
            env["ANSIBLE_HASH_BEHAVIOUR"] = config["hash_behaviour"]
        if config.get("precedence"):
            env["ANSIBLE_PRECEDENCE"] = config["precedence"]
        cmd = [binary, "-i", str(work / "inventory" / "hosts.yml"), "--playbook-dir", str(work), "--list"]
        result = subprocess.run(cmd, cwd=tmp, env=env, stdin=subprocess.DEVNULL, capture_output=True, text=True)
        if result.returncode != 0:
            sys.exit(f"{case_dir.name}: ansible-inventory failed:\n{result.stderr}")
        inventory = json.loads(result.stdout)
    expected = {"ansible_core": version, "inventory": inventory}
    (case_dir / "expected.json").write_text(json.dumps(expected, indent=2, sort_keys=True) + "\n")
    print(f"{case_dir.name}: {len(inventory.get('_meta', {}).get('hostvars', {}))} hosts")


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--ansible-inventory", default=shutil.which("ansible-inventory") or "ansible-inventory")
    parser.add_argument("cases", nargs="*")
    args = parser.parse_args()
    version = core_version(args.ansible_inventory)
    names = args.cases or sorted(p.name for p in CASES.iterdir() if (p / "oracle.json").is_file())
    for name in names:
        run_case(args.ansible_inventory, CASES / name, version)


if __name__ == "__main__":
    main()
