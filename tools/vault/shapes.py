#!/usr/bin/env python3
"""Wrapped-shape vectors for ANS-V107 "Not a whole-file vault" (plan amendment R21, D159) and their ansible-core oracle.

Run through ``tools/vault/shapes.sh``, never by hand. Modes:

``build VAULT_DIR INPUT``
    Builds ``VAULT_DIR/shapes/<file>`` (vector v01's synthetic envelope saved the ways a vault file goes wrong: a
    ``!vault |`` line first, ``key: !vault |``, a preamble, indentation, a byte order mark, quotes, other text),
    ``VAULT_DIR/shapes/<id>.unwrapped`` (the whole-file vault the Convert fix must write, by the rule of
    ``semantics.vault.VaultFileShape.unwrapped``) and writes the oracle input bundle to ``INPUT``.
``oracle [INPUT]``
    Any ansible-core. Runs every shape and every unwrapped file through ansible-core's own readers (a file, or the
    ``SHAPES_INPUT`` global that shapes.sh prepends when the script travels on stdin into the docker image) and prints
    one JSON document.
``merge VAULT_DIR INPUT ORACLE_JSON...``
    Writes ``VAULT_DIR/shapes/shapes.json``: the table and, per ansible-core version, what its readers did.

Secret rules: the only password is ``pw1`` of ``tools/vault/SYNTHETIC.md`` (read from the committed vector), the only
plaintext is v01's ``hello world``. Nothing here reads a real vault or the infra repository; the oracle works in a
fresh temporary directory.
"""
import base64
import json
import os
import sys
import tempfile

BOM = b"\xef\xbb\xbf"

# id, file name, how it is built, the caller's path facts (yamlInput, readRaw), the kind VaultFileShape must report,
# and whether it unwraps (the unwrapped file is written when the shape is one whole envelope behind a wrapper).
SHAPES = [
    ("tag", "tag.key", "tag", (False, True), "TAG_LINE", True),
    ("tag-indented", "tag-indented.key", "tag_indented", (False, True), "TAG_LINE", True),
    ("tag-crlf", "tag-crlf.key", "tag_crlf", (False, True), "TAG_LINE", True),
    ("tag-folded-strip", "tag-folded-strip.txt", "tag_folded_strip", (False, False), "TAG_LINE", True),
    ("tag-document-start", "tag-document-start.key", "tag_document_start", (False, True), "TAG_LINE", True),
    ("tag-trailing-blanks", "tag-trailing-blanks.key", "tag_trailing_blanks", (False, True), "TAG_LINE", True),
    ("tag-malformed", "tag-malformed.key", "tag_malformed", (False, True), "TAG_LINE", True),
    ("keyed", "greeting.key", "keyed", (False, True), "YAML_VALUE", True),
    ("vars-document", "vars-document.yml", "tag", (True, False), "VARS_DOCUMENT", True),
    ("vars-document-indented", "vars-document-indented.yml", "tag_indented", (True, False), "VARS_DOCUMENT", True),
    ("preamble", "preamble.key", "preamble", (False, True), "PREAMBLE", True),
    ("indented", "indented.key", "indented", (False, True), "INDENTED", True),
    ("bom", "bom.key", "bom", (False, True), "BYTE_ORDER_MARK", True),
    ("quoted", "quoted.key", "quoted", (False, True), "QUOTED", False),
    ("mixed", "mixed.conf", "mixed", (False, True), "MIXED", False),
    ("whole", "whole.key", "whole", (False, True), "VAULT", False),
]


def envelope_lines(vault_dir):
    """v01's envelope: the header and the payload lines (LF, no indentation)."""
    with open(os.path.join(vault_dir, "vectors", "v01.json"), encoding="utf-8") as fh:
        vector = json.load(fh)
    assert vector["passwordId"] == "pw1" and vector["plaintextUtf8"] == "hello world", "v01 changed"
    return vector, vector["envelope"].rstrip("\n").split("\n")


def build_shape(how, lines, raw_v01):
    lf = "\n"

    def block(indent, sep=lf, suffix=""):
        return "".join(" " * indent + line + suffix + sep for line in lines)

    if how == "tag":
        return ("!vault |\n" + block(0)).encode()
    if how == "tag_indented":
        return ("!vault |\n" + block(10)).encode()
    if how == "tag_crlf":
        return ("!vault |\r\n" + block(0, "\r\n")).encode()
    if how == "tag_folded_strip":
        return ("!vault >-\n" + block(2)).encode()
    if how == "tag_document_start":
        return ("--- !vault |\n" + block(2)).encode()
    if how == "tag_trailing_blanks":
        return ("!vault |  \n" + block(0, suffix="  ")).encode()
    if how == "tag_malformed":
        return ("!vault |\n" + block(0).replace(";AES256", ";aes256", 1)).encode()
    if how == "keyed":
        return raw_v01
    if how == "preamble":
        return ("# synthetic: pasted from a password manager\n\n" + block(0)).encode()
    if how == "indented":
        return block(2).encode()
    if how == "bom":
        return BOM + block(0).encode()
    if how == "quoted":
        return ('"' + "\\n".join(lines) + '\\n"\n').encode()
    if how == "mixed":
        return ("[db]\nuser = tern\npassword =\n" + block(0) + "port = 5432\n").encode()
    if how == "whole":
        return block(0).encode()
    raise ValueError(how)


def unwrap(data):
    """VaultFileShape.unwrapped: header and payload lines without wrapper, header indentation and trailing blanks."""
    if data.startswith(BOM):
        return data[len(BOM):]
    text = data.decode("latin-1")
    sep = "\r\n" if "\r\n" in text else "\n"
    lines = text.replace("\r\n", "\n").split("\n")
    header = next(i for i, line in enumerate(lines) if line.lstrip(" \t").startswith("$ANSIBLE_VAULT"))
    indent = len(lines[header]) - len(lines[header].lstrip(" \t"))
    out = [lines[header][indent:].rstrip(" \t")]
    for line in lines[header + 1:]:
        stripped = line.rstrip(" \t")
        cut = 0
        while cut < indent and cut < len(stripped) and stripped[cut] in " \t":
            cut += 1
        if stripped[cut:]:
            out.append(stripped[cut:])
    return (sep.join(out) + sep).encode("latin-1")


def build(vault_dir, input_path):
    vector, lines = envelope_lines(vault_dir)
    with open(os.path.join(vault_dir, "raw", "v01.yml"), "rb") as fh:
        raw_v01 = fh.read()
    out = os.path.join(vault_dir, "shapes")
    os.makedirs(out, exist_ok=True)
    for name in os.listdir(out):
        os.remove(os.path.join(out, name))
    bundle = {"passwordHex": vector["passwordHex"], "plaintextHex": vector["plaintextHex"], "shapes": []}
    for shape_id, file_name, how, (yaml_input, read_raw), kind, unwraps in SHAPES:
        data = build_shape(how, lines, raw_v01)
        with open(os.path.join(out, file_name), "wb") as fh:
            fh.write(data)
        unwrapped = unwrap(data) if unwraps else None
        if unwrapped is not None:
            with open(os.path.join(out, shape_id + ".unwrapped"), "wb") as fh:
                fh.write(unwrapped)
        bundle["shapes"].append({
            "id": shape_id,
            "file": file_name,
            "yamlInput": yaml_input,
            "readRaw": read_raw,
            "kind": kind,
            "unwrapped": shape_id + ".unwrapped" if unwrapped is not None else None,
            "dataBase64": base64.b64encode(data).decode(),
            "unwrappedBase64": base64.b64encode(unwrapped).decode() if unwrapped is not None else None,
        })
    with open(input_path, "w", encoding="utf-8") as fh:
        json.dump(bundle, fh)


def error_of(ex):
    message = str(ex).strip().splitlines()[0] if str(ex).strip() else ""
    return {"error": type(ex).__name__, "message": message[:160]}


def oracle(bundle):
    from ansible import release
    from ansible.parsing.dataloader import DataLoader
    from ansible.parsing.vault import VaultLib, VaultSecret, is_encrypted_file

    secret = VaultSecret(bytes.fromhex(bundle["passwordHex"]))
    plaintext = bytes.fromhex(bundle["plaintextHex"])
    vault = VaultLib([("default", secret)])
    results = {}
    with tempfile.TemporaryDirectory() as work:
        for shape in bundle["shapes"]:
            data = base64.b64decode(shape["dataBase64"])
            path = os.path.join(work, shape["file"])
            with open(path, "wb") as fh:
                fh.write(data)
            loader = DataLoader()
            loader.set_vault_secrets([("default", secret)])
            row = {}
            with open(path, "rb") as fh:
                row["isEncryptedFile"] = bool(is_encrypted_file(fh))
            # copy, script, unarchive, assemble: get_real_file decrypts only a whole-file vault.
            real = loader.get_real_file(path, decrypt=True)
            with open(real, "rb") as fh:
                delivered = fh.read()
            row["copyDelivers"] = "asIs" if delivered == data else ("plaintext" if delivered == plaintext else "other")
            if real != path:
                loader.cleanup_tmp_file(real)
            # template, lookup('file'), lookup('unvault'), vars files: _get_file_contents decrypts only vault data.
            contents, _ = loader._get_file_contents(path)
            row["textDelivers"] = "asIs" if bytes(contents) == data else ("plaintext" if bytes(contents) == plaintext else "other")
            if shape["yamlInput"]:
                try:
                    loaded = loader.load_from_file(path)
                    row["varsLoad"] = {"type": type(loaded).__name__}
                except Exception as ex:  # noqa: BLE001 - the oracle records what ansible-core raises
                    row["varsLoad"] = error_of(ex)
            try:
                vault.encrypt(data)
                row["encryptWraps"] = True
            except Exception:  # noqa: BLE001
                row["encryptWraps"] = False
            if shape["unwrappedBase64"] is not None:
                unwrapped = base64.b64decode(shape["unwrappedBase64"])
                upath = path + ".unwrapped"
                with open(upath, "wb") as fh:
                    fh.write(unwrapped)
                with open(upath, "rb") as fh:
                    row["unwrappedIsEncryptedFile"] = bool(is_encrypted_file(fh))
                try:
                    row["unwrappedDecrypts"] = vault.decrypt(unwrapped) == plaintext
                except Exception as ex:  # noqa: BLE001
                    row["unwrappedDecrypts"] = False
                    row["unwrappedError"] = error_of(ex)
                if row["unwrappedDecrypts"]:
                    copy_loader = DataLoader()
                    copy_loader.set_vault_secrets([("default", secret)])
                    real = copy_loader.get_real_file(upath, decrypt=True)
                    with open(real, "rb") as fh:
                        row["unwrappedCopyDelivers"] = "plaintext" if fh.read() == plaintext else "other"
                    if real != upath:
                        copy_loader.cleanup_tmp_file(real)
            results[shape["id"]] = row
    return {"ansibleCore": release.__version__, "results": results}


def merge(vault_dir, input_path, oracle_paths):
    with open(input_path, encoding="utf-8") as fh:
        bundle = json.load(fh)
    oracles = []
    for path in oracle_paths:
        with open(path, encoding="utf-8") as fh:
            oracles.append(json.load(fh))
    rows = []
    for shape in bundle["shapes"]:
        row = {k: shape[k] for k in ("id", "file", "yamlInput", "readRaw", "kind", "unwrapped")}
        row["oracle"] = {o["ansibleCore"]: o["results"][shape["id"]] for o in oracles}
        rows.append(row)
    document = {
        "note": "Generated by tools/vault/shapes.sh from vector v01 (password pw1 of tools/vault/SYNTHETIC.md). Never edit.",
        "rule": "Ansible decrypts a file only when its first 14 bytes are $ANSIBLE_VAULT; every other shape is used as it is.",
        "shapes": rows,
    }
    with open(os.path.join(vault_dir, "shapes", "shapes.json"), "w", encoding="utf-8") as fh:
        json.dump(document, fh, indent=2, sort_keys=False)
        fh.write("\n")


def main(argv):
    mode = argv[1] if len(argv) > 1 else ""
    if mode == "build":
        build(argv[2], argv[3])
    elif mode == "oracle":
        if "SHAPES_INPUT" in globals():
            bundle = json.loads(base64.b64decode(globals()["SHAPES_INPUT"]))
        else:
            with open(argv[2], encoding="utf-8") as fh:
                bundle = json.load(fh)
        json.dump(oracle(bundle), sys.stdout, indent=1)
    elif mode == "merge":
        merge(argv[2], argv[3], argv[4:])
    else:
        sys.stderr.write(__doc__)
        sys.exit(2)


if __name__ == "__main__":
    main(sys.argv)
