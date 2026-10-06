#!/usr/bin/env python3
"""Writes the PyYAML ground truth for the YAML adapter tests.

For every ``*.yml`` below ``corpus/`` and ``pyyaml-only/`` this writes ``<name>.yvalue.txt``: the first document
as Ansible's loader (PyYAML, YAML 1.1) sees it, in the dump format that ``YValueDump`` (plugin and semantics tests)
renders from a ``YValue``:

    map                          mapping; entries sorted by key text, duplicates collapsed (the last one wins)
      key <style> "<text>"       one entry; its value follows, indented by two more spaces
    seq                          sequence; items follow in order
    scalar <style> [<tag> ]"<text>" => <resolved>
    empty                        a plain scalar with no text and no tag (``key:``), which loads as None
    vault                        a ``!vault`` scalar

``<resolved>`` is what the constructor makes of a scalar: ``null``, ``bool true``, ``int 420``, ``float 3.2``,
``str``, ``timestamp`` or ``unloadable`` (the constructor raises, so Ansible cannot load the file).
Merge keys are applied with ``SafeConstructor.flatten_mapping``, the code Ansible's constructor runs.

Run with the Python that ships ansible-core (PyYAML 6.0.x), from this directory:

    /opt/homebrew/Cellar/ansible/14.4.0/libexec/bin/python generate.py

The script also checks that libyaml (``CSafeLoader``, which Ansible prefers when present) constructs the same data.
Floats are printed with ``repr``; keep corpus floats between 1e-3 and 1e7, where Kotlin's ``Double.toString`` agrees.

``generate.py --hashes <repo> <out.tsv>`` writes ``<relative path>\t<sha1 of the dump>`` for every ``*.yml``/``*.yaml``
file of an infra checkout (read only) instead; ``PsiYValueAdapterInfraCorpusTest`` compares against it when
``ANSIBILITY_PYYAML_HASHES`` names that file. Only hashes are written, so no value leaves the repository.
"""
import glob
import hashlib
import math
import os
import sys

import yaml
from yaml.constructor import SafeConstructor
from yaml.nodes import MappingNode, ScalarNode, SequenceNode

YAML_ORG = "tag:yaml.org,2002:"
VAULT_TAGS = ("!vault", "!vault-encrypted")
STYLES = {None: "plain", "": "plain", "'": "single", '"': "double", "|": "literal", ">": "folded"}


class RecordingLoader(yaml.SafeLoader):
    """A SafeLoader that remembers each scalar's explicit tag (composed nodes only keep the resolved one)."""

    def compose_scalar_node(self, anchor):
        explicit = self.peek_event().tag
        node = super().compose_scalar_node(anchor)
        node.explicit_tag = explicit
        return node


for _tag in VAULT_TAGS + ("!unsafe",):
    RecordingLoader.add_constructor(_tag, SafeConstructor.construct_yaml_str)


class PassThroughCLoader(yaml.CSafeLoader):
    pass


for _tag in VAULT_TAGS + ("!unsafe",):
    PassThroughCLoader.add_constructor(_tag, SafeConstructor.construct_yaml_str)


def short_tag(tag):
    if tag is None:
        return None
    if tag.startswith(YAML_ORG):
        return "!!" + tag[len(YAML_ORG):]
    return tag


def escape(text):
    out = []
    for ch in text:
        code = ord(ch)
        if ch == "\\":
            out.append("\\\\")
        elif ch == '"':
            out.append('\\"')
        elif ch == "\n":
            out.append("\\n")
        elif ch == "\t":
            out.append("\\t")
        elif ch == "\r":
            out.append("\\r")
        elif code < 0x20 or 0x7F <= code <= 0x9F or code in (0x2028, 0x2029, 0xFEFF):
            out.append("\\u%04x" % code)
        else:
            out.append(ch)
    return '"' + "".join(out) + '"'


def resolved(loader, node):
    tag = node.tag
    construct = {
        YAML_ORG + "bool": loader.construct_yaml_bool,
        YAML_ORG + "int": loader.construct_yaml_int,
        YAML_ORG + "float": loader.construct_yaml_float,
        YAML_ORG + "timestamp": loader.construct_yaml_timestamp,
    }
    if tag == YAML_ORG + "null":
        return "null"
    if tag in (YAML_ORG + "str", "!unsafe"):
        return "str"
    if tag not in construct:
        return "unloadable"
    try:
        value = construct[tag](node)
    except Exception:
        return "unloadable"
    if tag.endswith(":bool"):
        return "bool " + ("true" if value else "false")
    if tag.endswith(":int"):
        return "int %d" % value
    if tag.endswith(":float"):
        if math.isnan(value):
            return "float nan"
        if math.isinf(value):
            return "float " + ("inf" if value > 0 else "-inf")
        return "float " + repr(value)
    return "timestamp"


def dump(loader, node, indent, out):
    pad = " " * indent
    if isinstance(node, ScalarNode):
        explicit = node.explicit_tag
        if explicit in VAULT_TAGS:
            out.append(pad + "vault")
        elif node.value == "" and STYLES[node.style] == "plain" and explicit is None:
            out.append(pad + "empty")
        else:
            tag = short_tag(explicit)
            tag_part = (tag + " ") if tag else ""
            out.append("%sscalar %s %s%s => %s" % (pad, STYLES[node.style], tag_part, escape(node.value),
                                                   resolved(loader, node)))
    elif isinstance(node, SequenceNode):
        out.append(pad + "seq")
        for item in node.value:
            dump(loader, item, indent + 2, out)
    elif isinstance(node, MappingNode):
        loader.flatten_mapping(node)
        entries = {}
        for key, value in node.value:
            entries.pop(key.value, None)
            entries[key.value] = (key, value)
        out.append(pad + "map")
        for text in sorted(entries):
            key, value = entries[text]
            out.append("%s  key %s %s" % (pad, STYLES[key.style], escape(key.value)))
            dump(loader, value, indent + 4, out)
    else:
        raise TypeError(node)


def render(text):
    loader = RecordingLoader(text)
    try:
        node = loader.get_node() if loader.check_node() else None
    finally:
        loader.dispose()
    out = []
    if node is not None:
        dump(loader, node, 0, out)
    return "".join(line + "\n" for line in out)


def generate(path):
    with open(path, encoding="utf-8") as fh:
        text = fh.read()
    with open(path[: -len(".yml")] + ".yvalue.txt", "w", encoding="utf-8") as fh:
        fh.write(render(text))
    check_libyaml(text, path)


SKIPPED_DIRS = {".git", ".claude", ".ansible", "node_modules", "patches"}


def hash_corpus(repo, target):
    """Hashes of the dumps of every YAML file in an infra checkout; the same walk as the Kotlin corpus test."""
    rows = []
    for dirpath, dirnames, filenames in os.walk(repo):
        dirnames[:] = sorted(d for d in dirnames if d not in SKIPPED_DIRS)
        for name in sorted(filenames):
            if not name.endswith((".yml", ".yaml")):
                continue
            path = os.path.join(dirpath, name)
            with open(path, encoding="utf-8") as fh:
                text = fh.read()
            try:
                digest = hashlib.sha1(render(text).encode("utf-8")).hexdigest()
            except yaml.YAMLError:
                digest = "unparsable"
            rows.append("%s\t%s\n" % (os.path.relpath(path, repo), digest))
    with open(target, "w", encoding="utf-8") as fh:
        fh.writelines(rows)
    print("hashed", len(rows), "files into", target)


def check_libyaml(text, path):
    def first(loader_class):
        loader = loader_class(text)
        try:
            return loader.get_data() if loader.check_data() else None
        except (yaml.YAMLError, ValueError, KeyError, AttributeError):
            return "unloadable"
        finally:
            loader.dispose()

    if first(RecordingLoader) != first(PassThroughCLoader):
        sys.exit("libyaml and PyYAML disagree on " + path)


def main():
    if len(sys.argv) == 4 and sys.argv[1] == "--hashes":
        hash_corpus(sys.argv[2], sys.argv[3])
        return
    here = os.path.dirname(os.path.abspath(__file__))
    files = sorted(glob.glob(os.path.join(here, "corpus", "*.yml")) + glob.glob(os.path.join(here, "pyyaml-only", "*.yml")))
    for path in files:
        generate(path)
        print("wrote", os.path.relpath(path[: -len(".yml")] + ".yvalue.txt", here))


if __name__ == "__main__":
    main()
