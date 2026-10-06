#!/usr/bin/env python3
"""Regenerate the layout oracle (expected outputs of the real ansible-core) for every case in
semantics/src/test/resources/layout-oracle/ (written by build_oracle.py next to this script).

A case is a directory with oracle.json and project/ (see the description in oracle.json and the field list below).
For every case and every target the script

  1. copies the case to a fresh work dir (never runs inside the case dir): <work>/project, an empty <work>/cwd and
     an empty <work>/home (HOME, so ~/.ansible.cfg and ~/.ansible never come from the real user);
  2. writes one shell script with all runs of the case; each run gets `env -i` with PATH, HOME, LANG,
     ANSIBLE_NOCOLOR=1, ANSIBLE_CONFIG (the run's cfg, or an empty <work>/empty.cfg) and the run's env, stdin
     /dev/null, and the run's cwd (<work>/cwd unless "cwd": "project");
  3. runs it with the local ansible-core (target "local") and in the docker image ansibility-docgen:2.18.8
     (target "docker": --network none, no mounts, the case is piped in as a tar on stdin and the results come back
     as a tar on stdout); --image runs another image built from tools/docgen/snapshot with
     `docker build --build-arg ANSIBLE_CORE_VERSION=<v> -t ansibility-docgen:<v> tools/docgen/snapshot` (network
     only while building), e.g. for spike S-L1;
  4. writes expected/<core version>/<run>.json:
       {"ansible_core", "tool", "args", "cfg", "cwd", "env", "rc", "stdout", "stderr"}
     where stdout is parsed JSON when it is JSON (text otherwise) and every absolute work path is replaced by
     {project}, {cwd} or {home}. Compare rc and stdout; stderr is informative (wording and wrapping differ
     between versions).

oracle.json run fields: name, tool (ansible-inventory | ansible-config), cfg (relative to the case dir, or null),
cwd ("cwd" | "project"), env ({VAR: value}), args (argv after the tool); "{project}", "{cwd}", "{home}" are
substituted in args and env.

Usage: gen.py [--target local|docker|both] [--image IMAGE] [--work DIR] [case ...]
"""
import argparse
import io
import json
import os
import pathlib
import shlex
import shutil
import subprocess
import sys
import tarfile
import tempfile

REPO = pathlib.Path(__file__).resolve().parents[3]
CASES = REPO / "semantics" / "src" / "test" / "resources" / "layout-oracle"
IMAGE = "ansibility-docgen:2.18.8"
LOCAL_PATH = "/opt/homebrew/bin:/usr/bin:/bin"
DOCKER_PATH = "/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin"


def subst(value, root):
    return (value.replace("{project}", f"{root}/project")
                 .replace("{cwd}", f"{root}/cwd")
                 .replace("{home}", f"{root}/home"))


def script_for(meta, root, path_env, lang):
    """The shell script that performs every run of a case below root (absolute, as seen by the executing host)."""
    lines = ["#!/bin/sh", f"OUT={root}/out", "mkdir -p \"$OUT\"", f": > {root}/empty.cfg"]
    for run in meta["runs"]:
        name = run["name"]
        tool = run.get("tool", "ansible-inventory")
        cfg = f"{root}/{run['cfg']}" if run.get("cfg") else f"{root}/empty.cfg"
        cwd = f"{root}/project" if run.get("cwd") == "project" else f"{root}/cwd"
        env = [f"PATH={path_env}", f"HOME={root}/home", f"LANG={lang}", "ANSIBLE_NOCOLOR=1", "ANSIBLE_FORCE_COLOR=0",
               f"ANSIBLE_CONFIG={cfg}"]
        env += [f"{k}={subst(v, root)}" for k, v in run.get("env", {}).items()]
        argv = [tool] + [subst(a, root) for a in run["args"]]
        lines.append(
            f"(cd {shlex.quote(cwd)} && env -i {' '.join(shlex.quote(e) for e in env)} "
            f"{' '.join(shlex.quote(a) for a in argv)} > \"$OUT/{name}.out\" 2> \"$OUT/{name}.err\" < /dev/null; "
            f"echo $? > \"$OUT/{name}.rc\")")
    lines.append(f"ansible-inventory --version < /dev/null > \"$OUT/_version\" 2>&1")
    return "\n".join(lines) + "\n"


def prepare(case_dir, root):
    if root.exists():
        shutil.rmtree(root)
    (root / "cwd").mkdir(parents=True)
    (root / "home").mkdir()
    shutil.copytree(case_dir / "project", root / "project")
    for p in [root, root / "cwd", root / "home"]:
        p.chmod(0o755)


def run_local(case_dir, meta, work):
    root = work / "local" / case_dir.name
    prepare(case_dir, root)
    (root / "run.sh").write_text(script_for(meta, str(root), LOCAL_PATH, "en_US.UTF-8"))
    subprocess.run(["sh", str(root / "run.sh")], stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                   stderr=subprocess.DEVNULL, check=True)
    return str(root), root / "out"


def run_docker(case_dir, meta, work, image):
    """Pipes the case into the container as a tar; results come back as a tar into <work>/docker/<case>/out."""
    root_in = "/w/case"
    local = work / "docker" / case_dir.name
    if local.exists():
        shutil.rmtree(local)
    (local / "out").mkdir(parents=True)
    buf = io.BytesIO()
    with tarfile.open(fileobj=buf, mode="w") as tar:
        tar.add(case_dir / "project", arcname="project")
        script = script_for(meta, root_in, DOCKER_PATH, "C.UTF-8").encode()
        info = tarfile.TarInfo("run.sh")
        info.size = len(script)
        tar.addfile(info, io.BytesIO(script))
    cmd = ["docker", "run", "--rm", "-i", "--network", "none", "--entrypoint", "sh", image, "-c",
           f"mkdir -p {root_in}/cwd {root_in}/home && chmod 755 /w {root_in} {root_in}/cwd {root_in}/home && "
           f"cd {root_in} && tar xf - && sh run.sh >/dev/null 2>&1; tar -C {root_in}/out -cf - ."]
    result = subprocess.run(cmd, input=buf.getvalue(), capture_output=True, check=True)
    with tarfile.open(fileobj=io.BytesIO(result.stdout)) as tar:
        tar.extractall(local / "out", filter="data")
    return root_in, local / "out"


def collect(case_dir, meta, root, out):
    first = (out / "_version").read_text().splitlines()[0]          # "ansible-inventory [core 2.21.4]"
    version = first.split("core", 1)[1].strip(" ]")
    dest = case_dir / "expected" / version
    if dest.exists():
        shutil.rmtree(dest)
    dest.mkdir(parents=True)

    def norm(text):
        return (text.replace(f"{root}/project", "{project}").replace(f"{root}/cwd", "{cwd}")
                    .replace(f"{root}/home", "{home}").replace(root, "{work}"))

    for run in meta["runs"]:
        name = run["name"]
        raw = norm((out / f"{name}.out").read_text())
        try:
            stdout = json.loads(raw)
        except ValueError:
            stdout = raw
        record = {
            "ansible_core": version,
            "tool": run.get("tool", "ansible-inventory"),
            "args": run["args"],
            "cfg": run.get("cfg"),
            "cwd": run.get("cwd", "cwd"),
            "env": run.get("env", {}),
            "rc": int((out / f"{name}.rc").read_text().strip()),
            "stdout": stdout,
            "stderr": norm((out / f"{name}.err").read_text()),
        }
        (dest / f"{name}.json").write_text(json.dumps(record, indent=2, sort_keys=False) + "\n")
    return version


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--target", choices=["local", "docker", "both"], default="both")
    parser.add_argument("--image", default=IMAGE, help="docker image for the docker target (default %(default)s)")
    parser.add_argument("--work", type=pathlib.Path, help="work directory (default: a fresh temporary directory)")
    parser.add_argument("cases", nargs="*")
    args = parser.parse_args()
    names = args.cases or sorted(p.name for p in CASES.iterdir() if (p / "oracle.json").is_file())
    targets = ["local", "docker"] if args.target == "both" else [args.target]
    with tempfile.TemporaryDirectory(prefix="ansibility-layout-oracle-") as tmp:
        work = (args.work or pathlib.Path(tmp)).resolve()
        for name in names:
            case_dir = CASES / name
            meta = json.loads((case_dir / "oracle.json").read_text())
            for target in targets:
                if target == "local":
                    root, out = run_local(case_dir, meta, work)
                else:
                    root, out = run_docker(case_dir, meta, work, args.image)
                version = collect(case_dir, meta, root, out)
                print(f"{name}: {target} {version}: {len(meta['runs'])} runs")


if __name__ == "__main__":
    sys.exit(main())
