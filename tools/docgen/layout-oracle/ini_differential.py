#!/usr/bin/env python3
"""Regenerate the INI differential cases: small INI inventories at the edges of ansible-core's ini.py grammar and
value typing, each with what the real `ansible-inventory -i <case> --list --export` printed.

Writes semantics/src/test/resources/ini-differential/cases/<name>.ini and expected.json:
    {"ansible_core": "<version>", "cases": {<name>: {"rc", "failed", "stdout"}}}
where "failed" says whether the ini plugin rejected the file and stdout is the parsed JSON. IniDifferentialTest
(:semantics) compares IniInventoryParser with those records.

Every run uses the local ansible-core under `env -i` with only PATH, HOME (an empty temporary home), LANG,
ANSIBLE_NOCOLOR=1 and an empty ANSIBLE_CONFIG, stdin /dev/null and the empty home as the cwd. All content is
synthetic.

Usage: tools/docgen/layout-oracle/ini_differential.py
"""
import json
import pathlib
import subprocess
import tempfile

REPO = pathlib.Path(__file__).resolve().parents[3]
OUT = REPO / "semantics" / "src" / "test" / "resources" / "ini-differential"
LOCAL_PATH = "/opt/homebrew/bin:/usr/bin:/bin"

CASES = {
 "quotes": "[web]\nh1 x=\"a b\" y='c d' z=e\\ f\n",
 "comment-host": "h1 x=1 # comment\n[g]\nh2\n",
 "range-vars": "[g]\nh[1:3] x=[1,2]\n",
 "nested-dict": "[g]\nh1 x=\"{'a': [1, (2, 3)]}\"\n",
 "vars-dup-dict-key": "[g]\nh\n[g:vars]\nx = {'a': 1, 'a': 2}\n",
 "vars-empty-key": "[g]\nh\n[g:vars]\n=5\n",
 "priority": "[g]\nh1\n[g:vars]\nansible_group_priority=5\n[h]\nh1\n[h:vars]\nansible_group_priority=1\n",
 "children-then-vars": "[a:children]\nb\n[b]\nh1\n[a:vars]\nk=1\n",
 "tabs": "[web]\n web1  \n\t[db]\n\tdb1\n",
 "header-twice": "[web:children]\n[web]\nh1\n",
 "port-first-line-wins": "[g]\nh1 ansible_port=22 x=1\n[g]\nh1:2222\n",
 "ipv4-range": "[g]\n192.0.2.[1:3]\n",
 "alpha-step": "[g]\nweb[a:e:2]\n",
 "width-mismatch": "[g]\nweb[01:3]\n",
 "escaped-quote": "[g]\nh1 x=\\\"y\n",
 "dq-escape": "[g]\nh1 x=\"a\\\"b\"\n",
 "sq-unbalanced": "[g]\nh1 x='a\\'b'\n",
 "vars-unterminated": "[g]\nh\n[g:vars]\nx=\"unterminated\n",
 "vars-multiline": "[g]\nh1\n[g:vars]\nx=[1,\n2]\n",
 "empty-section": "[]\nh1\n",
 "colon-only": "[g:]\nh1\n",
 "double-tag": "[g:vars:x]\nh1\n",
 "trailing-junk": "[g]extra\nh1\n",
 "upper-tag": "[g:CHILDREN]\nh1\n",
 "case-groups": "[g]\nh1 a=1\n[G]\nh1 b=2\n",
 "numbers": "[g]\nh1 x=0o17 y=0b11 z=1e-3 w=.5 v=5. u=-0 t=+3\n",
 "names": "[g]\nh1 x=True y=False z=None w=Ellipsis\n",
 "unicode-escape": "[g]\nh1 x=\"\\u00e9\" y=\"'\\u00e9'\"\n",
 "unicode-host": "[g]\nh\u00f6st x=1\n",
 "host-and-group-var": "[g]\nh1 x=1\n[g:vars]\nx=2\n",
 "ungrouped-cleanup": "[ungrouped]\nh1\n[g]\nh1\n",
 "all-vars": "[all:vars]\nx=1\n[g]\nh1\n",
 "ungrouped-child": "h1\n[g:children]\nungrouped\n",
 "self-child": "[g]\nh1\n[g:children]\ng\n",
 "cycle3": "[a:children]\nb\n[b:children]\nc\n[c:children]\na\n",
 "empty-var-name": "[g]\nh1 x=1 =2\n",
 "quoted-token": "[g]\nh1 \"x=1\"\n",
 "space-host": "[g]\n\"h 1\" x=1\n",
 "bad-port": "[g]\nh1:abc\n",
 "port-zero": "[g]\nh1:0\n",
 "ipv6-bracket": "[g]\n[::1]:22\n",
 "ipv6-bare": "[g]\n::1\n",
 "crlf": "[g]\r\nh1 x=1\r\n[h]\r\nh2\r\n",
 "jinja-quoted": "[g]\nh1 ansible_host=\"{{ lookup('env','X') }}\"\n",
 "child-host-name": "[g]\nh1\n[p:children]\nh1\n",
 "vars-before-children-ref": "[x:vars]\nk=1\n[p:children]\nx\n",
 "late-children-chain": "[x:children]\ny\n[y:children]\nz\n[z]\nh1\n",
 "comment-semicolon-vars": "[g]\nh\n[g:vars]\n; comment\n# comment\nk=v\n",
 "big-int": "[g]\nh1 x=123456789012345678901234567890\n",
 "float-exp": "[g]\nh1 x=1e16 y=1e-7 z=123456789.123\n",
 "bool-key-dict": "[g]\nh1 x=\"{True: 1, 1: 2}\"\n",
 "set-dup": "[g]\nh1 x=\"{3, 1, 2, 1}\"\n",
 "tuple-trailing": "[g]\nh1 x=(1,) y=() z=[]\n",
 "str-concat": "[g]\nh1 x=\"'a' 'b'\"\n",
 "raw-str": "[g]\nh\n[g:vars]\nx=r'a\\nb'\ny='a\\nb'\n",
 "hash-in-vars-quoted": "[g]\nh\n[g:vars]\nx=\"a # b\"\n",
 "leading-equals-host": "[g]\n=h1\n",
 "dash-only": "[g]\n-\n",
 "yaml-like-host": "[g]\nh1: x\n",

 "set-a": "[g]\nh1 x=\"{10, 2, 33, 8}\"\n",
 "set-b": "[g]\nh1 x=\"{-1, 5, 100, -2}\"\n",
 "set-c": "[g]\nh1 x=\"{12, 3, 7, 1, 9, 11, 2, 8, 5, 4, 10, 6}\"\n",
 "set-d": "[g]\nh1 x=\"{0, 8, 16, 24, 1, 32, 40}\"\n",
 "set-e": "[g]\nh1 x=\"{2305843009213693952, 2305843009213693951, 4611686018427387904, 1}\"\n",
 "set-f": "[g]\nh1 x=\"{1.0, 2, True, 0, False}\"\n",
 "set-g": "[g]\nh1 x=\"{7, 15, 23, 31, 39, 47, 55, 63, 71, 79, 87, 95, 103, 111}\"\n",
 "set-h": "[g]\nh1 x=\"{1000, 2000, 3000, 4000, 5000, 6000, 7000, 8000, 9000, 10000, 11000, 12000, 13000, 14000, 15000, 16000, 17000, 18000, 19000, 20000, 21000, 22000, 23000, 24000, 25000, 26000, 27000, 28000, 29000, 30000}\"\n",
}


def main():
    cases_dir = OUT / "cases"
    cases_dir.mkdir(parents=True, exist_ok=True)
    for old in cases_dir.glob("*.ini"):
        old.unlink()
    for name, text in CASES.items():
        (cases_dir / f"{name}.ini").write_bytes(text.encode())
    with tempfile.TemporaryDirectory(prefix="ansibility-ini-differential-") as tmp:
        home = pathlib.Path(tmp).resolve()
        cfg = home / "empty.cfg"
        cfg.write_text("")
        env = {"PATH": LOCAL_PATH, "HOME": str(home), "LANG": "en_US.UTF-8", "ANSIBLE_NOCOLOR": "1",
               "ANSIBLE_CONFIG": str(cfg)}
        banner = subprocess.run(["ansible-inventory", "--version"], env=env, stdin=subprocess.DEVNULL,
                                capture_output=True, text=True, cwd=home).stdout
        version = banner.split("[core ", 1)[1].split("]", 1)[0]
        results = {}
        for name in CASES:
            p = subprocess.run(["ansible-inventory", "-i", str(cases_dir / f"{name}.ini"), "--list", "--export"],
                               env=env, stdin=subprocess.DEVNULL, capture_output=True, text=True, cwd=home)
            stdout = json.loads(p.stdout) if p.stdout.strip().startswith("{") else p.stdout
            failed = "Failed to parse inventory with 'ini' plugin" in p.stderr
            results[name] = {"rc": p.returncode, "failed": failed, "stdout": stdout}
    (OUT / "expected.json").write_text(json.dumps({"ansible_core": version, "cases": results}, indent=1) + "\n")
    print(f"{len(results)} cases, ansible-core {version}")


if __name__ == "__main__":
    main()
