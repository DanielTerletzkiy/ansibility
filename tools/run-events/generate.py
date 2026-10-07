#!/usr/bin/env python3
"""Regenerates the run-event goldens of the Ansibility callback (plan amendment R13).

Runs every playbook under cases/ with the callback plugin/src/main/resources/ansible/callback/ansibility_events.py
against the local ansible-core and against the docker image (default ansible-playbook:latest, run with
--network none), normalises the events (paths, ids, times, users) and writes them to
plugin/src/test/testData/run-events/<case>/<ansible version>.jsonl. The callback's line coverage (union of all runs,
measured by cover.py) must reach --min-coverage percent.

    python3 tools/run-events/generate.py [--local-only | --docker-only] [--image IMAGE] [--min-coverage 90]
                                         [--molecule-image IMAGE [--molecule-only]]

With --molecule-image, it also runs `molecule test --all` on the role under molecule/ (two delegated scenarios on
localhost: `default` passes, `volatile` fails its idempotence) in that image and writes the combined output, frames and
Molecule's stage lines in order, to plugin/src/test/testData/run-events/molecule/<molecule version>.log.
"""
import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(os.path.dirname(HERE))
CALLBACK_DIR = os.path.join(REPO, "plugin", "src", "main", "resources", "ansible", "callback")
CALLBACK = os.path.join(CALLBACK_DIR, "ansibility_events.py")
OUT = os.path.join(REPO, "plugin", "src", "test", "testData", "run-events")
TOKEN = "golden"
UUID = re.compile(r"\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\b")
VOLATILE = {"start", "end", "delta", "uid", "gid", "owner", "group", "ansible_job_id", "results_file", "src", "invocation"}


def run(case, workdir, version_runner):
    args = []
    args_file = os.path.join(workdir, "args")
    if os.path.exists(args_file):
        args = [line.strip() for line in open(args_file) if line.strip()]
    return version_runner(workdir, ["-i", "inventory.ini", "site.yml"] + args)


def local_runner(workdir, args):
    playbook = shutil.which("ansible-playbook")
    python = open(playbook).readline()[2:].strip()
    cov = os.path.join(workdir, ".cover.json")
    env = dict(os.environ, ANSIBILITY_EVENTS_TOKEN=TOKEN, ANSIBLE_CALLBACK_PLUGINS=CALLBACK_DIR, ANSIBLE_NOCOLOR="1",
               ANSIBLE_LOCALHOST_WARNING="false", ANSIBLE_PYTHON_INTERPRETER="auto_silent")
    with open(os.path.join(workdir, ".stderr"), "w") as err, open(os.path.join(workdir, ".stdout"), "w") as out:
        subprocess.run([python, os.path.join(HERE, "cover.py"), CALLBACK, cov, "--", playbook] + args,
                       cwd=workdir, env=env, stdout=out, stderr=err, stdin=subprocess.DEVNULL)
    version = subprocess.run([playbook, "--version"], capture_output=True, text=True, stdin=subprocess.DEVNULL).stdout
    return events(workdir), json.load(open(cov)), re.search(r"core ([0-9.]+)", version).group(1)


def docker_runner(image):
    def runner(workdir, args):
        command = [
            "docker", "run", "--rm", "--network", "none",
            "-v", workdir + ":/work", "-v", CALLBACK_DIR + ":/ansibility/callbacks:ro", "-v", HERE + ":/tools:ro", "-w", "/work",
            "-e", "ANSIBILITY_EVENTS_TOKEN=" + TOKEN, "-e", "ANSIBLE_CALLBACK_PLUGINS=/ansibility/callbacks",
            "-e", "ANSIBLE_NOCOLOR=1", "-e", "ANSIBLE_PYTHON_INTERPRETER=auto_silent",
            "--entrypoint", "python3", image, "/tools/cover.py", "/ansibility/callbacks/ansibility_events.py", "/work/.cover.json", "--",
            "/usr/local/bin/ansible-playbook",
        ] + args
        with open(os.path.join(workdir, ".stderr"), "w") as err, open(os.path.join(workdir, ".stdout"), "w") as out:
            subprocess.run(command, stdout=out, stderr=err, stdin=subprocess.DEVNULL)
        version = subprocess.run(["docker", "run", "--rm", "--network", "none", "--entrypoint", "ansible-playbook", image, "--version"],
                                 capture_output=True, text=True, stdin=subprocess.DEVNULL).stdout
        return events(workdir, "/work"), json.load(open(os.path.join(workdir, ".cover.json"))), re.search(r"core ([0-9.]+)", version).group(1)

    return runner


def events(workdir, container_dir=None):
    frame = "\x1e" + TOKEN + " "
    with open(os.path.join(workdir, ".stderr"), encoding="utf-8") as handle:
        return [json.loads(line[len(frame):]) for line in handle if line.startswith(frame)]


def normalise(items, workdir, container_dir):
    ids = {}
    home = os.path.expanduser("~")
    user = os.environ.get("USER", "")

    def text(value):
        for prefix in filter(None, [container_dir, os.path.realpath(workdir), workdir]):
            value = value.replace(prefix, "<case>")
        value = value.replace(home, "<home>")
        if user:
            value = value.replace(user, "<user>")
        return UUID.sub(lambda m: ids.setdefault(m.group(0), "id-%d" % (len(ids) + 1)), value)

    def walk(value, key=None):
        if key in VOLATILE:
            return "<%s>" % key
        if isinstance(value, dict):
            return {k: walk(v, k) for k, v in value.items() if k not in ("warnings", "deprecations")}
        if isinstance(value, list):
            return [walk(v) for v in value]
        if isinstance(value, str):
            return text(value)
        return value

    out = []
    for event in items:
        event = walk(event)
        event["t"] = 0
        if "duration" in event:
            event["duration"] = 0.5
        if event.get("e") == "result" and isinstance(event.get("result"), dict):
            facts = event["result"].get("ansible_facts")
            if isinstance(facts, dict) and "discovered_interpreter_python" in facts:
                facts["discovered_interpreter_python"] = "<python>"
        out.append(event)
    return out


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--local-only", action="store_true")
    parser.add_argument("--docker-only", action="store_true")
    parser.add_argument("--image", default="ansible-playbook:latest")
    parser.add_argument("--min-coverage", type=float, default=90.0)
    parser.add_argument("--molecule-image", help="an image with molecule and ansible-core (entrypoint is replaced)")
    parser.add_argument("--molecule-only", action="store_true")
    options = parser.parse_args()
    if options.molecule_image:
        molecule_transcript(options.molecule_image)
        if options.molecule_only:
            return
    runners = []
    if not options.docker_only:
        runners.append(("local", local_runner, None))
    if not options.local_only:
        runners.append(("docker", docker_runner(options.image), "/work"))
    executed, executable = set(), set()
    for case in sorted(os.listdir(os.path.join(HERE, "cases"))):
        for name, runner, container_dir in runners:
            workdir = tempfile.mkdtemp(prefix="ansibility-events-")
            try:
                shutil.copytree(os.path.join(HERE, "cases", case), workdir, dirs_exist_ok=True)
                items, cover, version = run(case, workdir, runner)
                if not items:
                    sys.exit("%s/%s produced no events:\n%s" % (case, name, open(os.path.join(workdir, ".stderr")).read()[-3000:]))
                executed.update(cover["executed"])
                executable.update(cover["executable"])
                target = os.path.join(OUT, case)
                os.makedirs(target, exist_ok=True)
                with open(os.path.join(target, version + ".jsonl"), "w", encoding="utf-8") as handle:
                    for event in normalise(items, workdir, container_dir):
                        handle.write(json.dumps(event, sort_keys=True) + "\n")
                print("%-8s %-7s ansible-core %-7s %3d events" % (case, name, version, len(items)))
            finally:
                shutil.rmtree(workdir, ignore_errors=True)
    runs = 100.0 * len(executable & executed) / max(1, len(executable))
    unit = unit_coverage()
    executed.update(unit["executed"])
    missing = sorted(executable - executed)
    percent = 100.0 * len(executable & executed) / max(1, len(executable))
    report = "ansibility_events.py line coverage: %.1f%% from the playbook runs, %.1f%% (%d/%d lines) with test_callback.py\nnot run: %s\n" % (
        runs, percent, len(executable & executed), len(executable), missing)
    print(report, end="")
    with open(os.path.join(OUT, "COVERAGE.txt"), "w") as handle:
        handle.write(report)
    if percent < options.min_coverage:
        sys.exit("coverage %.1f%% is below %.1f%%" % (percent, options.min_coverage))


def molecule_transcript(image):
    """`molecule test --all` of the golden role, stdout and stderr in one ordered stream, normalised."""
    workdir = tempfile.mkdtemp(prefix="ansibility-molecule-")
    try:
        shutil.copytree(os.path.join(HERE, "molecule", "roles"), os.path.join(workdir, "roles"))
        command = [
            "docker", "run", "--rm", "--network", "none",
            "-v", os.path.join(workdir, "roles") + ":/ansible/roles", "-v", CALLBACK_DIR + ":/ansibility/callbacks:ro", "-w", "/ansible/roles/demo",
            "-e", "ANSIBILITY_EVENTS_TOKEN=" + TOKEN, "-e", "ANSIBLE_CALLBACK_PLUGINS=/ansibility/callbacks",
            "-e", "PYTHONUNBUFFERED=1", "-e", "ANSIBLE_NOCOLOR=1", "-e", "NO_COLOR=1", "-e", "ANSIBLE_PYTHON_INTERPRETER=auto_silent",
            "-e", "ANSIBLE_ROLES_PATH=/ansible/roles",
            "--entrypoint", "molecule", image, "test", "--all",
        ]
        output = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL).stdout.decode("utf-8", "replace")
        version = subprocess.run(["docker", "run", "--rm", "--network", "none", "--entrypoint", "molecule", image, "--version"],
                                 capture_output=True, text=True, stdin=subprocess.DEVNULL).stdout
        version = re.search(r"molecule ([0-9.]+)", version).group(1)
        frame = "\x1e" + TOKEN + " "
        # Not splitlines(): it breaks lines at the record separator that starts every frame.
        lines_out = output.split("\n")
        events = [json.loads(line[len(frame):]) for line in lines_out if line.startswith(frame)]
        normalised = iter(normalise(events, workdir, "/ansible/roles/demo"))
        ephemeral = re.compile(r"/root/\.ansible/tmp/molecule\.[A-Za-z0-9]+\.")
        lines = []
        for line in lines_out:
            if line.startswith(frame):
                lines.append(frame + json.dumps(next(normalised), sort_keys=True))
            else:
                lines.append(ephemeral.sub("<ephemeral>.", line.replace("/ansible/roles/demo", "<case>")))
        target = os.path.join(OUT, "molecule")
        os.makedirs(target, exist_ok=True)
        with open(os.path.join(target, version + ".log"), "w", encoding="utf-8") as handle:
            handle.write("\n".join(lines) + "\n")
        print("molecule test --all  molecule %-7s %3d events, %d lines" % (version, len(events), len(lines)))
    finally:
        shutil.rmtree(workdir, ignore_errors=True)


def unit_coverage():
    """The lines test_callback.py runs (stub ansible modules, the defensive helpers)."""
    out = os.path.join(tempfile.mkdtemp(prefix="ansibility-events-"), "cover.json")
    subprocess.run([sys.executable, os.path.join(HERE, "cover.py"), CALLBACK, out, "--", os.path.join(HERE, "test_callback.py")],
                   check=True, stdin=subprocess.DEVNULL, capture_output=True)
    return json.load(open(out))


if __name__ == "__main__":
    main()
