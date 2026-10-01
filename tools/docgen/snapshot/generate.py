#!/usr/bin/env python3
"""Ansibility documentation snapshot generator.

Produces ONE normalised JSON document with everything the plugin needs to document and check Ansible content
without running Ansible:

* ``modules``   module docs of ansible.builtin and the given collections, options normalised to the plugin's
                OptionSpec shape (``suboptions`` -> ``options``, dict ``choices`` -> an object of value -> paragraphs,
                string descriptions -> lists), returns (with ``contains``), attributes, deprecation, examples,
                file name relative to site-packages and version_added;
* ``keywords``  ``ansible-doc -t keyword -j`` merged with the FieldAttribute metadata of Play, Block, Task, Handler,
                RoleInclude, PlaybookInclude and LoopControl (isa, default, listof, static, applies_to), plus
                ``listen``, ``with_<lookup>`` and ``local_action`` added by hand;
* ``filters``, ``tests``, ``lookups``  short docs and options (Jinja2 builtin filters and tests included);
* ``routing``   ``plugin_routing`` of ansible/config/ansible_builtin_runtime.yml and of each collection's
                meta/runtime.yml (redirects, deprecations, tombstones), plus module aliases (``ansible.builtin.systemd``
                documents ``systemd_service``).

Docs are read through ``python -m ansible.cli.doc`` of the interpreter running this script, one collection at a
time (never ``--metadata-dump`` over a whole Ansible package). The output is deterministic (sorted keys; gzip
without a timestamp).

Usage::

    python3 generate.py [--collections a.b,c.d] [--label TEXT] [--gzip] [--output FILE]

With no ``--output`` the document is written to stdout, which is how the Docker image returns it
(``docker run --rm --network none ansibility-docgen:2.18.8 --gzip > core-2.18.8.json.gz``).
"""

from __future__ import annotations

import argparse
import gzip
import importlib
import inspect
import io
import json
import os
import platform
import re
import subprocess
import sys
import tempfile

FORMAT = 1

DEFAULT_COLLECTIONS = [
    "ansible.mysql",
    "ansible.posix",
    "community.crypto",
    "community.docker",
    "community.general",
    "community.mysql",
]

# Routing sections kept from plugin_routing (the others are connection/become/... plugins the IDE never resolves).
ROUTING_TYPES = ("modules", "action", "filter", "test", "lookup")

# Modules whose extra keys are data rather than options (set_fact: variables; add_host: host variables).
ARBITRARY_KEY_MODULES = {"ansible.builtin.set_fact", "ansible.builtin.add_host"}

# Plugin option type spellings accepted by ansible.config.manager.ensure_type, mapped to the module spellings.
PLUGIN_TYPE_ALIASES = {"integer": "int", "string": "str", "boolean": "bool", "dictionary": "dict"}

# Largest JSON-serialised return ``sample`` kept (bigger samples are dropped; they are display-only).
MAX_SAMPLE_CHARS = 1500

BATCH = 60

# FieldAttribute holders, in the order their names are reported in ``applies_to``.
KEYWORD_CLASSES = [
    ("Play", "ansible.playbook.play", "Play"),
    ("Role", "ansible.playbook.role.include", "RoleInclude"),
    ("Block", "ansible.playbook.block", "Block"),
    ("Task", "ansible.playbook.task", "Task"),
    ("Handler", "ansible.playbook.handler", "Handler"),
    ("PlaybookInclude", "ansible.playbook.playbook_include", "PlaybookInclude"),
    ("LoopControl", "ansible.playbook.loop_control", "LoopControl"),
]

# Descriptions for FieldAttributes that keyword_desc.yml does not document (Ansible markup).
HAND_DESCRIPTIONS = {
    "listen": "A list of topics this handler listens to. C(notify) a topic to run every handler that listens to it.",
    "role": "The name of the role, or a path to it, in a play's C(roles:) entry.",
    "import_playbook": "The playbook file to import, relative to the importing playbook.",
    "loop_var": "Name of the loop variable, V(item) by default. Set it for nested loops (for example an included task file that loops itself).",
    "index_var": "Name of a variable that holds the current zero-based loop index.",
    "label": "What to print for each loop item in the task output instead of the whole item.",
    "pause": "Seconds to wait between loop iterations.",
    "extended": "Set C(ansible_loop) with extended loop information (C(index), C(first), C(last), C(length), C(previtem), C(nextitem) ...).",
    "extended_allitems": "Whether C(ansible_loop.allitems) is included when O(extended) is enabled.",
    "break_when": "Conditional that stops the loop after the current iteration when true (an implicit Jinja expression).",
    "validate_argspec": (
        "Validate the play's variables against an argument spec in the playbook's meta file (C(<playbook>.meta.yml))."
        " V(true) uses the spec named like the play; a string names the spec."
    ),
}

HAND_KEYWORDS = {
    "with_<lookup>": {
        "applies_to": ["Task", "Handler"],
        "template": "explicit",
        "hand": True,
        "description": [
            "Loop over the terms returned by the lookup plugin named after the prefix, for example C(with_items),"
            " C(with_dict) or C(with_fileglob). Each element is available as C(item) (see C(loop_control)).",
            "Superseded by C(loop) for most uses.",
        ],
    },
    "local_action": {
        "applies_to": ["Task", "Handler"],
        "template": "explicit",
        "hand": True,
        "description": [
            "Shorthand for C(delegate_to: localhost): the value is the action, written as a C(module args) string"
            " or a mapping with a C(module) key.",
        ],
    },
    "listen": {
        "applies_to": ["Handler"],
        "isa": "list",
        "listof": ["str"],
        "template": "static",
        "hand": True,
        "description": [HAND_DESCRIPTIONS["listen"]],
    },
}


def log(message: str) -> None:
    print(message, file=sys.stderr, flush=True)


# ------------------------------------------------------------------------------------------------ ansible-doc


class DocRunner:
    """Runs ``python -m ansible.cli.doc`` of this interpreter from a scratch directory, stdin from /dev/null."""

    def __init__(self) -> None:
        self.workdir = tempfile.mkdtemp(prefix="ansibility-docgen-")
        self.env = dict(os.environ)
        self.env.update(
            ANSIBLE_NOCOLOR="1",
            ANSIBLE_FORCE_COLOR="0",
            ANSIBLE_DEPRECATION_WARNINGS="0",
            ANSIBLE_LOCAL_TEMP=os.path.join(self.workdir, "tmp"),
        )

    def run(self, args: list[str], module: str = "ansible.cli.doc") -> object:
        proc = subprocess.run(
            [sys.executable, "-m", module, *args],
            cwd=self.workdir,
            env=self.env,
            stdin=subprocess.DEVNULL,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            check=False,
        )
        if proc.returncode != 0:
            raise RuntimeError(f"{module} {' '.join(args[:6])}... failed: {proc.stderr.strip()[-2000:]}")
        return json.loads(proc.stdout) if proc.stdout.strip() else {}

    def list(self, ptype: str, collection: str) -> list[str]:
        listing = self.run(["-t", ptype, "-l", "-j", collection])
        return sorted(name for name in listing if name.startswith(collection + "."))

    def docs(self, ptype: str, names: list[str], skipped: list[str]) -> dict:
        """Docs for ``names``; batches that fail are retried one by one and failures are recorded."""
        result: dict = {}
        for start in range(0, len(names), BATCH):
            batch = names[start : start + BATCH]
            try:
                result.update(self.run(["-t", ptype, "-j", *batch]))
            except RuntimeError:
                for name in batch:
                    try:
                        result.update(self.run(["-t", ptype, "-j", name]))
                    except RuntimeError as ex:
                        skipped.append(f"{ptype} {name}: {str(ex).splitlines()[-1][:300]}")
        return result


# ------------------------------------------------------------------------------------------------ normalisation


def paragraphs(value) -> list[str]:
    if value is None:
        return []
    if isinstance(value, str):
        return [value.rstrip("\n")]
    if isinstance(value, (list, tuple)):
        return [str(v).rstrip("\n") for v in value if v is not None]
    return [str(value)]


def norm_type(value, plugin: bool):
    if value is None:
        return None
    text = str(value)
    return PLUGIN_TYPE_ALIASES.get(text, text) if plugin else text


def norm_deprecation(value) -> dict | None:
    if not isinstance(value, dict):
        return None
    out = {}
    for key in ("why", "alternative", "removed_in", "removed_at_date", "removed_from_collection"):
        if value.get(key) not in (None, ""):
            out[key] = str(value[key])
    return out or None


def norm_options(options, plugin: bool = False) -> dict:
    if not isinstance(options, dict):
        return {}
    return {str(name): norm_option(spec, plugin) for name, spec in options.items() if isinstance(spec, dict)}


def norm_option(spec: dict, plugin: bool) -> dict:
    out: dict = {}
    if spec.get("type") is not None:
        out["type"] = norm_type(spec["type"], plugin)
    if spec.get("elements") is not None:
        out["elements"] = norm_type(spec["elements"], plugin)
    if spec.get("required"):
        out["required"] = True
    if spec.get("default") is not None:
        out["default"] = spec["default"]
    choices = spec.get("choices")
    if isinstance(choices, dict):
        out["choices"] = {str(k): paragraphs(v) for k, v in choices.items()}
    elif isinstance(choices, (list, tuple)):
        out["choices"] = list(choices)
    if spec.get("aliases"):
        out["aliases"] = [str(a) for a in spec["aliases"]]
    description = paragraphs(spec.get("description"))
    if description:
        out["description"] = description
    nested = spec.get("suboptions", spec.get("options"))
    if isinstance(nested, dict):
        out["options"] = norm_options(nested, plugin)
    if spec.get("version_added") not in (None, ""):
        out["version_added"] = str(spec["version_added"])
    deprecated = norm_deprecation(spec.get("deprecated"))
    if deprecated:
        out["deprecated"] = deprecated
    if spec.get("no_log"):
        out["no_log"] = True
    return out


def norm_returns(returns) -> dict:
    if not isinstance(returns, dict):
        return {}
    out = {}
    for name, spec in returns.items():
        if not isinstance(spec, dict):
            continue
        item: dict = {}
        description = paragraphs(spec.get("description"))
        if description:
            item["description"] = description
        for key in ("returned", "version_added"):
            if spec.get(key) not in (None, ""):
                item[key] = str(spec[key]) if not isinstance(spec[key], list) else ", ".join(map(str, spec[key]))
        if spec.get("type") is not None:
            item["type"] = str(spec["type"])
        if spec.get("elements") is not None:
            item["elements"] = str(spec["elements"])
        if "sample" in spec and spec["sample"] is not None:
            try:
                if len(json.dumps(spec["sample"], sort_keys=True, default=str)) <= MAX_SAMPLE_CHARS:
                    item["sample"] = spec["sample"]
            except (TypeError, ValueError):
                pass
        if isinstance(spec.get("contains"), dict):
            item["contains"] = norm_returns(spec["contains"])
        out[str(name)] = item
    return out


def norm_attributes(attributes) -> dict:
    if not isinstance(attributes, dict):
        return {}
    out = {}
    for name, spec in attributes.items():
        if not isinstance(spec, dict):
            continue
        item = {"support": str(spec.get("support", ""))}
        if spec.get("platforms"):
            platforms = spec["platforms"]
            item["platforms"] = ", ".join(platforms) if isinstance(platforms, list) else str(platforms)
        if spec.get("membership"):
            membership = spec["membership"]
            item["membership"] = [str(m) for m in membership] if isinstance(membership, list) else [str(membership)]
        details = paragraphs(spec.get("details"))
        if details:
            item["details"] = details
        out[str(name)] = item
    return out


def norm_seealso(entries) -> list:
    out = []
    for entry in entries or []:
        if not isinstance(entry, dict):
            continue
        item = {k: entry[k] for k in ("module", "plugin", "plugin_type", "ref", "name", "link") if entry.get(k)}
        description = paragraphs(entry.get("description"))
        if description:
            item["description"] = description
        if item:
            out.append(item)
    return out


class Paths:
    """Makes plugin file names relative to site-packages or the collections root."""

    def __init__(self) -> None:
        import ansible

        self.site = os.path.dirname(os.path.dirname(os.path.abspath(ansible.__file__)))

    def rel(self, path) -> str | None:
        if not path:
            return None
        path = str(path)
        marker = path.rfind("/ansible_collections/")
        if marker >= 0:
            return path[marker + 1 :]
        if path.startswith(self.site + os.sep):
            return path[len(self.site) + 1 :]
        return os.path.basename(path)


def norm_plugin_doc(fqcn: str, entry: dict, paths: Paths, kind: str) -> dict:
    doc = entry.get("doc") or {}
    plugin = kind != "module"
    out: dict = {}
    if doc.get("short_description"):
        out["short_description"] = str(doc["short_description"]).strip()
    for key in ("description", "notes", "requirements"):
        value = paragraphs(doc.get(key))
        if value:
            out[key] = value
    if doc.get("version_added") not in (None, ""):
        out["version_added"] = str(doc["version_added"])
    deprecated = norm_deprecation(doc.get("deprecated"))
    if deprecated:
        out["deprecated"] = deprecated
    out["options"] = norm_options(doc.get("options"), plugin)
    filename = paths.rel(doc.get("filename"))
    if filename:
        out["filename"] = filename
    if plugin:
        if doc.get("positional"):
            positional = doc["positional"]
            out["positional"] = [p.strip() for p in positional.split(",")] if isinstance(positional, str) else list(positional)
        returns = norm_returns(entry.get("return"))
        if returns:
            out["returns"] = returns
        return out
    attributes = norm_attributes(doc.get("attributes"))
    if attributes:
        out["attributes"] = attributes
    seealso = norm_seealso(doc.get("seealso"))
    if seealso:
        out["seealso"] = seealso
    examples = entry.get("examples")
    if isinstance(examples, str) and examples.strip():
        out["examples"] = examples.strip("\n").rstrip()
    returns = norm_returns(entry.get("return"))
    if returns:
        out["returns"] = returns
    if doc.get("has_action"):
        out["has_action"] = True
    if "free_form" in out["options"]:
        out["free_form"] = "free_form"
    if fqcn in ARBITRARY_KEY_MODULES:
        out["accepts_arbitrary_keys"] = True
    return out


# ------------------------------------------------------------------------------------------------ sections


def collection_versions(runner: DocRunner) -> dict[str, dict]:
    """name -> {version, path} for installed collections (first path wins, as for plugin loading)."""
    listing = runner.run(["collection", "list", "--format", "json"], module="ansible.cli.galaxy")
    found: dict[str, dict] = {}
    for path, collections in (listing or {}).items():
        for name, info in (collections or {}).items():
            found.setdefault(name, {"version": str((info or {}).get("version")), "path": os.path.join(path, *name.split("."))})
    return found


def modules_section(runner, collections, paths, skipped, aliases):
    modules = {}
    for collection in ["ansible.builtin", *collections]:
        names = runner.list("module", collection)
        docs = runner.docs("module", names, skipped)
        log(f"modules {collection}: {len(names)} listed, {len(docs)} documented")
        for fqcn in names:
            entry = docs.get(fqcn)
            if not entry or not entry.get("doc"):
                if fqcn not in docs:
                    skipped.append(f"module {fqcn}: no documentation")
                continue
            doc_name = entry["doc"].get("module")
            short = fqcn.rsplit(".", 1)[1]
            canonical = f"{collection}.{doc_name}" if doc_name and doc_name != short else fqcn
            if canonical != fqcn and canonical in names:
                aliases[fqcn] = canonical
                continue
            modules[fqcn] = norm_plugin_doc(fqcn, entry, paths, "module")
    return modules


def jinja_builtins(ptype: str) -> dict:
    """Jinja2's own filters/tests as ansible.builtin plugins (ansible-core >= 2.19 lists these stubs itself)."""
    from jinja2 import defaults

    source = defaults.DEFAULT_FILTERS if ptype == "filter" else defaults.DEFAULT_TESTS
    out = {}
    for name, func in sorted(source.items()):
        if not name.isidentifier():
            continue
        doc = inspect.getdoc(func) or ""
        short = re.split(r"(\.|!|\s\(|:\s)", doc, maxsplit=1)[0].replace("\n", " ").strip()
        if short:
            short += "."
        entry = {
            "short_description": short,
            "description": [
                short,
                f"This is the Jinja builtin {ptype} plugin '{name}'.",
                f"See: U(https://jinja.palletsprojects.com/en/stable/templates/#jinja-{ptype}s.{name})",
            ],
            "jinja_builtin": True,
            "options": {},
        }
        options, positional = signature_options(func)
        if options:
            entry["options"] = options
            if positional:
                entry["positional"] = positional
        out[f"ansible.builtin.{name}"] = entry
    return out


def signature_options(func) -> tuple[dict, list]:
    try:
        signature = inspect.signature(func)
    except (TypeError, ValueError):
        return {}, []
    params = [
        p
        for p in signature.parameters.values()
        if p.name not in ("environment", "eval_ctx", "context", "env", "ctx") and p.kind is not p.VAR_KEYWORD
    ]
    options: dict = {}
    positional = []
    for index, p in enumerate(params):
        if index == 0:
            options["_input"] = {"type": "raw", "required": True, "description": ["The value the filter or test is applied to."]}
            continue
        if p.kind is p.VAR_POSITIONAL:
            options[p.name] = {"type": "list", "description": ["Additional positional arguments."]}
            positional.append(p.name)
            continue
        spec: dict = {}
        default = p.default
        if default is inspect.Parameter.empty:
            spec["required"] = True
        elif isinstance(default, bool):
            spec.update(type="bool", default=default)
        elif isinstance(default, int):
            spec.update(type="int", default=default)
        elif isinstance(default, float):
            spec.update(type="float", default=default)
        elif isinstance(default, str):
            spec.update(type="str", default=default)
        else:
            spec["type"] = "raw"
        options[p.name] = spec
        if p.kind in (p.POSITIONAL_ONLY, p.POSITIONAL_OR_KEYWORD):
            positional.append(p.name)
    return options, positional


def plugins_section(runner, ptype, collections, paths, skipped):
    out = {}
    for collection in ["ansible.builtin", *collections]:
        try:
            names = runner.list(ptype, collection)
        except RuntimeError as ex:
            skipped.append(f"{ptype} listing {collection}: {str(ex).splitlines()[-1][:300]}")
            continue
        docs = runner.docs(ptype, names, skipped)
        for fqcn in names:
            entry = docs.get(fqcn)
            if entry and entry.get("doc") and entry["doc"].get("filename", "") != "":
                out[fqcn] = norm_plugin_doc(fqcn, entry, paths, ptype)
        log(f"{ptype} {collection}: {len(names)} listed")
    if ptype in ("filter", "test"):
        for fqcn, entry in jinja_builtins(ptype).items():
            out.setdefault(fqcn, entry)
    return out


def json_default(value):
    """FieldAttribute defaults as JSON: containers for list/dict factories, null for config/CLI-dependent ones."""
    if callable(value):
        if value in (list, tuple, set):
            return []
        if value is dict:
            return {}
        return None
    if isinstance(value, (str, int, float, bool)) or value is None:
        return value
    if isinstance(value, (list, tuple, set)):
        return [json_default(v) for v in value]
    if isinstance(value, dict):
        return {str(k): json_default(v) for k, v in value.items()}
    return str(value)


def listof_names(listof) -> list[str]:
    names = []
    for item in listof if isinstance(listof, tuple) else (listof,):
        if isinstance(item, tuple):
            names.extend(listof_names(item))
        elif isinstance(item, type):
            names.append({"str": "str", "int": "int", "dict": "dict", "bool": "bool", "float": "float"}.get(item.__name__, item.__name__))
    return names


def keywords_section(runner) -> dict:
    listed = list(runner.run(["-t", "keyword", "-l", "-j"]).keys())
    documented = runner.run(["-t", "keyword", "-j", *listed]) if listed else {}
    keywords: dict = {}
    for label, module_name, class_name in KEYWORD_CLASSES:
        cls = getattr(importlib.import_module(module_name), class_name)
        for attr_name, fa in sorted(cls.fattributes.items()):
            if getattr(fa, "private", False):
                continue
            name = getattr(fa, "alias", None) or attr_name
            entry = keywords.get(name)
            if entry is None:
                entry = keywords[name] = {"applies_to": [], "isa": getattr(fa, "isa", None) or "string"}
                if getattr(fa, "listof", None):
                    entry["listof"] = listof_names(fa.listof)
                entry["default"] = json_default(getattr(fa, "default", None))
                if getattr(fa, "static", False):
                    entry["static"] = True
                if getattr(fa, "priority", 0):
                    entry["priority"] = fa.priority
                if name.endswith("when") or name == "until":
                    entry["template"] = "implicit"
                elif getattr(fa, "static", False):
                    entry["template"] = "static"
                else:
                    entry["template"] = "explicit"
            elif entry["isa"] != (getattr(fa, "isa", None) or "string"):
                entry.setdefault("isa_by_class", {})[label] = fa.isa
            if label not in entry["applies_to"]:
                entry["applies_to"].append(label)
    for name, doc in documented.items():
        entry = keywords.get(name)
        if entry is None:
            continue  # documented but accepted nowhere (e.g. the removed accelerate* keywords of 2.18)
        entry["description"] = paragraphs(doc.get("description"))
        if doc.get("template"):
            entry["template"] = doc["template"]
        for level in doc.get("applies_to") or []:
            if level not in entry["applies_to"]:
                entry["applies_to"].append(level)
    for name, entry in keywords.items():
        if "description" not in entry and name in HAND_DESCRIPTIONS:
            entry["description"] = [HAND_DESCRIPTIONS[name]]
        order = [label for label, _, _ in KEYWORD_CLASSES]
        entry["applies_to"].sort(key=lambda level: order.index(level) if level in order else len(order))
    for name, entry in HAND_KEYWORDS.items():
        keywords.setdefault(name, entry)
    return keywords


def norm_route(entry) -> dict | None:
    if not isinstance(entry, dict):
        return None
    out = {}
    if entry.get("redirect"):
        out["redirect"] = str(entry["redirect"])
    for key in ("deprecation", "tombstone"):
        value = entry.get(key)
        if isinstance(value, dict):
            notice = {k: str(value[k]) for k in ("removal_version", "removal_date", "warning_text") if value.get(k) is not None}
            out[key] = notice
    return out or None


def routing_section(installed: dict, collections: list[str], aliases: dict) -> dict:
    import yaml
    import ansible

    routing: dict = {t: {} for t in ROUTING_TYPES}
    sources = [("ansible.builtin", os.path.join(os.path.dirname(ansible.__file__), "config", "ansible_builtin_runtime.yml"))]
    for collection in collections:
        info = installed.get(collection)
        if info:
            sources.append((collection, os.path.join(info["path"], "meta", "runtime.yml")))
    for collection, path in sources:
        if not os.path.exists(path):
            continue
        with open(path, encoding="utf-8") as handle:
            data = yaml.safe_load(handle) or {}
        for ptype in ROUTING_TYPES:
            for name, entry in ((data.get("plugin_routing") or {}).get(ptype) or {}).items():
                route = norm_route(entry)
                if route:
                    routing[ptype][f"{collection}.{name}"] = route
    routing["aliases"] = {"modules": dict(sorted(aliases.items()))}
    return routing


# ------------------------------------------------------------------------------------------------ main


def count_options(options: dict) -> int:
    return sum(1 + count_options(spec.get("options") or {}) for spec in options.values())


def generate(collections: list[str], label: str | None) -> dict:
    import jinja2
    from ansible.release import __version__ as core_version

    runner = DocRunner()
    paths = Paths()
    installed = collection_versions(runner)
    missing = [c for c in collections if c not in installed]
    if missing:
        raise SystemExit(f"collections not installed: {', '.join(missing)}")
    skipped: list[str] = []
    aliases: dict = {}
    modules = modules_section(runner, collections, paths, skipped, aliases)
    keywords = keywords_section(runner)
    filters = plugins_section(runner, "filter", collections, paths, skipped)
    tests = plugins_section(runner, "test", collections, paths, skipped)
    lookups = plugins_section(runner, "lookup", collections, paths, skipped)
    routing = routing_section(installed, collections, aliases)
    stats = {
        "modules": len(modules),
        "module_options": sum(count_options(m["options"]) for m in modules.values()),
        "keywords": len(keywords),
        "filters": len(filters),
        "tests": len(tests),
        "lookups": len(lookups),
        "redirects": sum(1 for t in ROUTING_TYPES for r in routing[t].values() if "redirect" in r),
        "deprecations": sum(1 for t in ROUTING_TYPES for r in routing[t].values() if "deprecation" in r),
        "tombstones": sum(1 for t in ROUTING_TYPES for r in routing[t].values() if "tombstone" in r),
        "aliases": len(aliases),
        "skipped": len(skipped),
    }
    log("stats: " + json.dumps(stats, sort_keys=True))
    for line in skipped:
        log("skipped: " + line)
    return {
        "format": FORMAT,
        "core": core_version,
        "label": label or f"ansible-core {core_version}",
        "python": platform.python_version(),
        "jinja": jinja2.__version__,
        "collections": {"ansible.builtin": core_version, **{c: installed[c]["version"] for c in collections}},
        "stats": stats,
        "skipped": skipped,
        "modules": modules,
        "keywords": keywords,
        "filters": filters,
        "tests": tests,
        "lookups": lookups,
        "routing": routing,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--collections", default=",".join(DEFAULT_COLLECTIONS), help="comma-separated collection names")
    parser.add_argument("--label", help="human-readable source label stored in the snapshot")
    parser.add_argument("--gzip", action="store_true", help="gzip the output (deterministic, no timestamp)")
    parser.add_argument("--output", help="output file (default: stdout)")
    args = parser.parse_args()
    collections = [c.strip() for c in args.collections.split(",") if c.strip()]
    snapshot = generate(collections, args.label)
    data = json.dumps(snapshot, sort_keys=True, ensure_ascii=False, separators=(",", ":"), default=str).encode("utf-8")
    if args.gzip:
        buffer = io.BytesIO()
        with gzip.GzipFile(filename="", mode="wb", fileobj=buffer, compresslevel=9, mtime=0) as handle:
            handle.write(data)
        data = buffer.getvalue()
    if args.output:
        with open(args.output, "wb") as handle:
            handle.write(data)
    else:
        sys.stdout.buffer.write(data)
        sys.stdout.buffer.flush()
    return 0


if __name__ == "__main__":
    sys.exit(main())
