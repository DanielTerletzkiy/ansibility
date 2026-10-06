#!/usr/bin/env python3
"""Synthetic Ansible Vault vectors and oracle tables for ``semantics.vault`` (plan amendment R7/R8, work unit V1).

Run through ``tools/vault/vectors.sh``, never by hand. Modes:

``vectors SYNTHETIC.md OUT ORACLE_INPUT [--fresh | --seed DIR]``
    Local ansible-core 2.21.4. Builds ``OUT/raw`` (the exact ansible-vault output files), ``OUT/vectors/vNN.json``,
    ``OUT/index.json`` and the multi-vault fixture, and writes the oracle input bundle to ``ORACLE_INPUT``.
    Without ``--fresh`` the committed envelopes in ``OUT/raw`` (or ``DIR/raw`` with ``--seed``) are kept, so a
    re-run reproduces the same files.
``oracle [ORACLE_INPUT]``
    Any ansible-core. Runs the oracle against the bundle (a file, or the ``VAULTGEN_INPUT`` global that vectors.sh
    prepends when the script travels on stdin into the docker image) and prints one JSON document.
``split ORACLE_JSON OUT VERSION``
    Checks that the document comes from ansible-core VERSION and writes ``OUT/tol-<major.minor>.json`` and ``OUT/config-<major.minor>.json`` from an oracle document.

Secret rules: every password comes from ``tools/vault/SYNTHETIC.md``. Nothing here reads a real vault, a
``.vault-pass``, an ``.env.local`` or the infra repository; all work happens in fresh temporary directories.
"""
import base64
import binascii
import configparser
import hashlib
import hmac
import inspect
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile

# --------------------------------------------------------------------------------------------------------------------
# Shared helpers
# --------------------------------------------------------------------------------------------------------------------

PASSWORD_ROW = re.compile(
    r"^\|\s*`(?P<id>[\w-]+)`\s*\|\s*`(?P<label>[^`]+)`\s*\|[^|]*\|\s*`(?P<hex>[0-9a-f]+)`\s*\|\s*(?P<source>[\w ]+?)\s*\|"
    r"\s*`(?P<source_hex>[0-9a-f]+)`\s*\|"
)

VAULT_ENV_KEYS = re.compile(r"^ANSIBLE_")


def parse_synthetic(path):
    """The password table of SYNTHETIC.md: id -> {label, hex, source, sourceHex}."""
    passwords = {}
    with open(path, encoding="utf-8") as fh:
        for line in fh:
            m = PASSWORD_ROW.match(line)
            if m:
                passwords[m["id"]] = {
                    "label": m["label"],
                    "hex": m["hex"],
                    "source": m["source"],
                    "sourceHex": m["source_hex"],
                }
    for required in ("pw1", "dev", "prod", "spaced", "nonutf8", "utf8", "ws", "inner", "script", "prompt"):
        if required not in passwords:
            sys.exit(f"SYNTHETIC.md has no password row `{required}`")
    return passwords


def pw_bytes(passwords, pid):
    return bytes.fromhex(passwords[pid]["hex"])


def source_bytes(passwords, pid):
    return bytes.fromhex(passwords[pid]["sourceHex"])


def clean_env(home, config):
    """The calling environment without any ANSIBLE_* variable, plus a private HOME, temp dir and config file."""
    env = {k: v for k, v in os.environ.items() if not VAULT_ENV_KEYS.match(k) and k not in ("EDITOR", "VISUAL")}
    env.update({
        "HOME": home,
        "ANSIBLE_CONFIG": config,
        "ANSIBLE_LOCAL_TEMP": os.path.join(home, ".ansible-tmp"),
        "ANSIBLE_NOCOLOR": "1",
        "PYTHONWARNINGS": "ignore",
    })
    return env


def run_vault(args, cwd, env, stdin=None):
    """Runs the ansible-vault CLI of the current interpreter (same ansible-core as this process)."""
    return subprocess.run(
        [sys.executable, "-m", "ansible.cli.vault"] + list(args),
        cwd=cwd, env=env, input=stdin if stdin is not None else b"", capture_output=True, timeout=120,
    )


def ansible_version():
    from ansible.release import __version__
    return __version__


def write_bytes(path, data, executable=False):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as fh:
        fh.write(data)
    os.chmod(path, 0o755 if executable else 0o644)


def printf_script(data, prefix=""):
    """A POSIX `printf` statement that writes exactly [data] (octal escapes for every byte)."""
    return prefix + "printf '" + "".join("\\%03o" % b for b in data) + "'\n"


# --------------------------------------------------------------------------------------------------------------------
# vectors: the 16 vectors (ansible-vault 2.21.4)
# --------------------------------------------------------------------------------------------------------------------

#: id, password id, YAML key (inline vectors) or None (whole-file vectors), description
SPECS = [
    ("v01", "pw1", "greeting", "encrypt_string, no label -> 1.1"),
    ("v02", "dev", "db_password", "encrypt_string --vault-id dev@file -> 1.2 label dev"),
    ("v03", "prod", "db_password", "encrypt_string --vault-id prod@file -> 1.2 label prod (same key as v02: per-env multi-vault)"),
    ("v04", "pw1", None, "whole-file vault of an EMPTY file (plaintext 0 bytes; the ciphertext is one full PKCS7 block)"),
    ("v05", "pw1", "multiline", "encrypt_string from stdin, multi-line value with a blank line, no trailing newline"),
    ("v06", "pw1", "unicode", "encrypt_string, non-ASCII UTF-8 (umlaut, en dash, CJK, emoji)"),
    ("v07", "pw1", None, "whole-file vault of binary data (all 256 byte values + NUL CR LF 0xFF): 260 B -> 272 B ciphertext"),
    ("v08", "dev", None, "whole-file vault of a YAML vars file, --vault-id dev -> 1.2;AES256;dev"),
    ("v09", "pw1", None, "whole-file vault of the same YAML vars file, default id -> 1.1"),
    ("v10", "pw1", "deterministic", "ANSIBLE_VAULT_ENCRYPT_SALT=fixed-salt-for-tests: the salt is the UTF-8 bytes of that string (20 bytes, not 32); re-encrypting reproduces it byte for byte"),
    ("v11", "pw1", "sixteen", "plaintext of exactly 16 bytes -> PKCS7 adds a full block (32-byte ciphertext)"),
    ("v12", "spaced", "spaced", "the password FILE holds \"  spaced pass 4 \\r\\n\"; Ansible strips the surrounding whitespace -> secret \"spaced pass 4\""),
    ("v13", "prod", "mislabeled", "MISLABELLED: the header label is dev, but the prod password encrypted it (--vault-id dev@pw-prod.txt); decrypts only when every secret is tried (vault_id_match off)"),
    ("v14", "prod", "unlabeled_prod", "1.1 (no label) encrypted with the prod password (--vault-id pw-prod.txt -> label default -> 1.1); found only by trying every secret"),
    ("v15", "nonutf8", "nonutf8_pw", "the password file holds the raw bytes ff 70 61 73 73 2d e9 (not UTF-8): PBKDF2 must run over raw bytes; a char[]-based PBEKeySpec cannot reproduce it"),
    ("v16", "utf8", "utf8_pw", "UTF-8 password with non-ASCII characters, label ops (1.2)"),
]

YAML_VARS_FILE = b"""---
# synthetic vars file
vault_db_user: app
vault_db_password: "s3cr3t-synthetic"
vault_list:
  - one
  - two
"""

BINARY_FILE = bytes(range(256)) + b"\x00\r\n\xff"

ENCRYPT_SALT = "fixed-salt-for-tests"

#: key -> vector of the multi-vault fixture (target-repo style: body at key indent + 2)
FIXTURE_ITEMS = [
    ("vault_greeting", "v01"),
    ("vault_db_password_dev", "v02"),
    ("vault_db_password_prod", "v03"),
    ("vault_mislabeled", "v13"),
    ("vault_unlabeled_prod", "v14"),
    ("vault_unicode", "v06"),
    ("vault_multiline", "v05"),
]
FIXTURE_SECRETS = [["default", "pw1"], ["dev", "dev"], ["prod", "prod"]]


def raw_name(vid, key):
    return f"{vid}.yml" if key else f"{vid}.vault"


def fresh_raw(passwords, raw_dir):
    """Encrypts every vector with the local ansible-vault CLI (random salts, except v10)."""
    work = os.path.realpath(tempfile.mkdtemp(prefix="vaultgen-"))
    try:
        home = os.path.join(work, "home")
        os.makedirs(home)
        cfg = os.path.join(work, "empty.cfg")
        write_bytes(cfg, b"[defaults]\n")
        env = clean_env(home, cfg)
        files = {"pw1.txt": "pw1", "pw-dev.txt": "dev", "pw-prod.txt": "prod", "pw-spaced.txt": "spaced",
                 "pw-nonutf8.txt": "nonutf8", "pw-utf8.txt": "utf8"}
        for name, pid in files.items():
            write_bytes(os.path.join(work, name), source_bytes(passwords, pid))
        write_bytes(os.path.join(work, "in", "empty.txt"), b"")
        write_bytes(os.path.join(work, "in", "bin.dat"), BINARY_FILE)
        write_bytes(os.path.join(work, "in", "vars.yml"), YAML_VARS_FILE)
        os.makedirs(os.path.join(work, "out"))

        def es(out, *args, stdin=None, extra=None):
            e = dict(env, **(extra or {}))
            p = run_vault(["encrypt_string"] + list(args), work, e, stdin)
            if p.returncode != 0:
                sys.exit(f"encrypt_string for {out} failed: {p.stderr.decode(errors='replace')}")
            write_bytes(os.path.join(raw_dir, out), p.stdout)

        def ef(out, *args):
            target = os.path.join(work, "out", out)
            p = run_vault(["encrypt"] + list(args) + ["--output", target], work, env)
            if p.returncode != 0:
                sys.exit(f"encrypt for {out} failed: {p.stderr.decode(errors='replace')}")
            shutil.copyfile(target, os.path.join(raw_dir, out))

        es("v01.yml", "--vault-password-file", "pw1.txt", "--name", "greeting", "hello world")
        es("v02.yml", "--vault-id", "dev@pw-dev.txt", "--name", "db_password", "dev only secret")
        es("v03.yml", "--vault-id", "prod@pw-prod.txt", "--name", "db_password", "prod secret")
        ef("v04.vault", "--vault-password-file", "pw1.txt", "in/empty.txt")
        es("v05.yml", "--vault-password-file", "pw1.txt", "--stdin-name", "multiline",
           stdin=b"line one\nline two\n\nline four")
        es("v06.yml", "--vault-password-file", "pw1.txt", "--name", "unicode", "Grüße – 日本語 🔐 ñ")
        ef("v07.vault", "--vault-password-file", "pw1.txt", "in/bin.dat")
        ef("v08.vault", "--vault-id", "dev@pw-dev.txt", "in/vars.yml")
        ef("v09.vault", "--vault-password-file", "pw1.txt", "in/vars.yml")
        for out in ("v10.yml", "v10b.yml"):
            es(out, "--vault-password-file", "pw1.txt", "--name", "deterministic", "same every time",
               extra={"ANSIBLE_VAULT_ENCRYPT_SALT": ENCRYPT_SALT})
        es("v11.yml", "--vault-password-file", "pw1.txt", "--name", "sixteen", "0123456789abcdef")
        es("v12.yml", "--vault-password-file", "pw-spaced.txt", "--name", "spaced", "whitespace-stripped password")
        es("v13.yml", "--vault-id", "dev@pw-prod.txt", "--name", "mislabeled", "label lies")
        es("v14.yml", "--vault-id", "pw-prod.txt", "--name", "unlabeled_prod", "unlabeled prod")
        es("v15.yml", "--vault-password-file", "pw-nonutf8.txt", "--name", "nonutf8_pw",
           "secret behind a non-UTF-8 password")
        es("v16.yml", "--vault-id", "ops@pw-utf8.txt", "--name", "utf8_pw", "secret behind a UTF-8 password")
    finally:
        shutil.rmtree(work, ignore_errors=True)


def vault_scalar_loader():
    import yaml

    class Loader(yaml.SafeLoader):
        pass

    for tag in ("!vault", "!vault-encrypted"):
        Loader.add_constructor(tag, lambda loader, node: loader.construct_scalar(node))
    return Loader


def fixture_text(envelopes):
    out = ["---", "# synthetic multi-vault fixture: secrets default=pw1, dev=dev, prod=prod (tools/vault/SYNTHETIC.md)",
           "plain_value: not secret"]
    for key, vid in FIXTURE_ITEMS:
        out.append(f"{key}: !vault |")
        out.extend("  " + line for line in envelopes[vid].rstrip("\n").split("\n"))
    out.append('derived: "{{ vault_greeting }}-{{ vault_db_password_dev }}"')
    return "\n".join(out) + "\n"


def isolate_ansible(work):
    """Makes the next ansible import see an empty configuration and no ANSIBLE_* variable of the caller."""
    for k in [k for k in os.environ if VAULT_ENV_KEYS.match(k)]:
        del os.environ[k]
    empty_cfg = os.path.join(work, "empty.cfg")
    write_bytes(empty_cfg, b"[defaults]\n")
    os.environ["ANSIBLE_CONFIG"] = empty_cfg
    os.environ["ANSIBLE_LOCAL_TEMP"] = os.path.join(work, ".ansible-tmp")


def vectors(synthetic, out, oracle_input, fresh=False, seed=None):
    work = os.path.realpath(tempfile.mkdtemp(prefix="vaultgen-vectors-"))
    try:
        isolate_ansible(work)
        build_vectors(synthetic, out, oracle_input, fresh, seed)
    finally:
        shutil.rmtree(work, ignore_errors=True)


def build_vectors(synthetic, out, oracle_input, fresh, seed):
    import yaml
    from ansible.parsing.vault import VaultLib, VaultSecret, parse_vaulttext, parse_vaulttext_envelope

    passwords = parse_synthetic(synthetic)
    raw_dir = os.path.join(out, "raw")
    os.makedirs(raw_dir, exist_ok=True)
    names = [raw_name(vid, key) for vid, _, key, _ in SPECS] + ["v10b.yml"]
    if fresh:
        fresh_raw(passwords, raw_dir)
    elif seed:
        for name in names + ["multivault-fixture.yml"]:
            shutil.copyfile(os.path.join(seed, "raw", name), os.path.join(raw_dir, name))
    missing = [n for n in names if not os.path.exists(os.path.join(raw_dir, n))]
    if missing:
        sys.exit(f"no committed envelopes ({', '.join(missing)}): run vectors.sh --fresh or --seed DIR")

    version = ansible_version()
    loader = vault_scalar_loader()
    vec_dir = os.path.join(out, "vectors")
    os.makedirs(vec_dir, exist_ok=True)
    index, bundle_vectors, envelopes = [], {}, {}
    for vid, pid, key, desc in SPECS:
        with open(os.path.join(raw_dir, raw_name(vid, key)), "rb") as fh:
            raw = fh.read()
        if key:
            envelope = yaml.load(raw, Loader=loader)[key]
        else:
            envelope = raw.decode("ascii")
        b_env = envelope.encode("ascii")
        envelopes[vid] = envelope
        b_vt, b_ver, cipher, label = parse_vaulttext_envelope(b_env)
        b_ct, b_salt, b_hmac_hex = parse_vaulttext(b_vt)
        pw = pw_bytes(passwords, pid)
        dk = hashlib.pbkdf2_hmac("sha256", pw, b_salt, 10000, 80)
        k1, k2, iv = dk[:32], dk[32:64], dk[64:80]
        if hmac.new(k2, b_ct, hashlib.sha256).hexdigest().encode() != bytes(b_hmac_hex):
            sys.exit(f"{vid}: HMAC does not match the password {pid}")
        plaintext = VaultLib([("x", VaultSecret(pw))]).decrypt(b_env)
        header = b_env.split(b"\n", 1)[0].decode()
        fields = header.split(";")
        try:
            utf8 = plaintext.decode("utf-8")
        except UnicodeDecodeError:
            utf8 = None
        try:
            password_text = pw.decode("utf-8")
        except UnicodeDecodeError:
            password_text = None
        record = {
            "id": vid,
            "description": desc,
            "generatedWith": "ansible-core 2.21.4 (ansible-vault CLI)",
            "passwordId": pid,
            "password": password_text,
            "passwordHex": pw.hex(),
            "passwordSourceHex": passwords[pid]["sourceHex"],
            "kind": "inline" if key else "file",
            "yamlKey": key,
            "rawFile": "raw/" + raw_name(vid, key),
            "header": header,
            "formatVersion": str(b_ver.decode()),
            "cipher": str(cipher),
            "label": fields[3] if len(fields) >= 4 else None,
            "labelAsParsedByAnsible": str(label),
            "envelope": envelope,
            "sourceFileText": raw.decode("utf-8") if key else None,
            "plaintextBase64": base64.b64encode(bytes(plaintext)).decode(),
            "plaintextHex": bytes(plaintext).hex(),
            "plaintextUtf8": utf8,
            "saltHex": bytes(b_salt).hex(),
            "saltLength": len(b_salt),
            "hmacHex": bytes(b_hmac_hex).decode(),
            "ciphertextHex": bytes(b_ct).hex(),
            "ciphertextLength": len(b_ct),
            "derived": {"cipherKeyHex": k1.hex(), "hmacKeyHex": k2.hex(), "ivHex": iv.hex()},
        }
        if vid == "v10":
            record["encryptSalt"] = ENCRYPT_SALT
        with open(os.path.join(vec_dir, vid + ".json"), "w", encoding="utf-8") as fh:
            json.dump(record, fh, indent=2, ensure_ascii=False)
            fh.write("\n")
        index.append({"id": vid, "kind": record["kind"], "header": header, "passwordId": pid, "description": desc})
        bundle_vectors[vid] = {k: record[k] for k in (
            "envelope", "plaintextHex", "passwordId", "kind", "yamlKey", "sourceFileText", "header")}
        if vid == "v10":
            bundle_vectors[vid]["encryptSalt"] = ENCRYPT_SALT
            with open(os.path.join(raw_dir, "v10b.yml"), "rb") as fh:
                if fh.read() != raw:
                    sys.exit("v10 and v10b differ: VAULT_ENCRYPT_SALT is not deterministic")
        print(f"{vid} {header} {len(plaintext)} bytes ok", file=sys.stderr)

    fixture_path = os.path.join(raw_dir, "multivault-fixture.yml")
    if fresh or not os.path.exists(fixture_path):
        with open(fixture_path, "w", encoding="utf-8") as fh:
            fh.write(fixture_text(envelopes))
    with open(fixture_path, encoding="utf-8") as fh:
        fixture = fh.read()
    fixture_values = yaml.load(fixture, Loader=loader)
    expected = {}
    for key, vid in FIXTURE_ITEMS:
        if fixture_values[key] != envelopes[vid]:
            sys.exit(f"multivault-fixture.yml: {key} is not vector {vid}")
        rec = json.load(open(os.path.join(vec_dir, vid + ".json"), encoding="utf-8"))
        expected[key] = {"from": vid, "header": rec["header"], "plaintext": rec["plaintextUtf8"]}
    with open(os.path.join(out, "multivault-fixture.expected.json"), "w", encoding="utf-8") as fh:
        json.dump({"secrets": FIXTURE_SECRETS, "expected": expected}, fh, indent=1, ensure_ascii=False)
        fh.write("\n")

    with open(os.path.join(out, "index.json"), "w", encoding="utf-8") as fh:
        json.dump({
            "note": "Synthetic test vectors. Every password was invented for testing (tools/vault/SYNTHETIC.md); none relates to a real vault.",
            "generator": "tools/vault/vectors.sh",
            "passwords": passwords,
            "vectors": index,
        }, fh, indent=1, ensure_ascii=False)
        fh.write("\n")

    with open(os.path.join(raw_dir, "v10.yml"), encoding="utf-8") as fh:
        v10_raw = fh.read()
    with open(oracle_input, "w", encoding="utf-8") as fh:
        json.dump({
            "passwords": passwords,
            "vectors": bundle_vectors,
            "v10Raw": v10_raw,
            "fixture": {"text": fixture, "secrets": FIXTURE_SECRETS,
                        "expected": {k: v["plaintext"] for k, v in expected.items()}},
        }, fh, ensure_ascii=False)


# --------------------------------------------------------------------------------------------------------------------
# oracle: what this ansible-core version accepts, rejects, resolves and writes
# --------------------------------------------------------------------------------------------------------------------

def walk_exception(exc):
    seen = []
    while exc is not None and exc not in seen:
        seen.append(exc)
        exc = exc.__cause__ or exc.__context__
    return seen


def classify(exc):
    """Result class and detail of a vault failure, independent of the version's message wording."""
    chain = walk_exception(exc)
    names = {type(e).__name__ for e in chain}
    text = " ".join(str(e) for e in chain)
    if "AnsibleVaultFormatError" in names:
        result = "FORMAT"
    elif "not vault encrypted" in text.lower():
        result = "NOT_VAULT"
    elif "could not be found" in text:
        result = "UNKNOWN_CIPHER"
    elif "Decryption failed" in text or "HMAC verification failed" in text:
        result = "NO_SECRET"
    elif "padding" in text.lower():
        result = "PADDING"
    else:
        result = "OTHER"
    detail = None
    if result == "FORMAT":
        if "Odd-length" in text:
            detail = "odd-length"
        elif "Non-hexadecimal" in text:
            detail = "non-hex"
        elif "envelope format" in text:
            detail = "header"
        elif "vaulttext format" in text or "unpack" in text:
            detail = "payload"
    return result, detail


def yaml_cases(header, hexlines):
    hexall = "".join(hexlines)

    def block(ind, lines, style="|"):
        return "x: !vault %s\n" % style + "".join(" " * ind + line + "\n" for line in lines)

    def wrap(s, n):
        return [s[i:i + n] for i in range(0, len(s), n)]

    last = hexlines[-1]
    tampered = last[:-1] + ("0" if last[-1] != "0" else "1")
    return {
        "A_literal_indent10_standard": block(10, [header] + hexlines),
        "B_literal_indent2": block(2, [header] + hexlines),
        "C_strip_chomp_|-": block(2, [header] + hexlines, "|-"),
        "D_keep_chomp_|+_extra_blank_lines": block(4, [header] + hexlines, "|+") + "\n\n",
        "E_CRLF_line_endings": block(2, [header] + hexlines).replace("\n", "\r\n"),
        "F_trailing_spaces_on_hex_lines": "x: !vault |\n" + "".join("  " + line + "   \n" for line in [header] + hexlines),
        "G_rewrapped_64_cols": block(2, [header] + wrap(hexall, 64)),
        "H_hex_on_one_line": block(2, [header, hexall]),
        "I_uppercase_hex": block(2, [header] + [line.upper() for line in hexlines]),
        "J_double_quoted_with_escapes": 'x: !vault "%s\\n%s"\n' % (header, "\\n".join(hexlines)),
        "K_folded_>": block(2, [header] + hexlines, ">"),
        "L_blank_line_between_header_and_hex": block(2, [header, ""] + hexlines),
        "M_spaces_around_semicolons": block(2, ["$ANSIBLE_VAULT ; 1.1 ; AES256"] + hexlines),
        "N_quoted_leading_space_before_header": 'x: !vault " %s\\n%s"\n' % (header, "\\n".join(hexlines)),
        "O_1.1_header_with_label": block(2, ["$ANSIBLE_VAULT;1.1;AES256;dev"] + hexlines),
        "P_1.2_header_without_label": block(2, ["$ANSIBLE_VAULT;1.2;AES256"] + hexlines),
        "Q_version_9.9": block(2, ["$ANSIBLE_VAULT;9.9;AES256"] + hexlines),
        "R_lowercase_cipher_aes256": block(2, ["$ANSIBLE_VAULT;1.1;aes256"] + hexlines),
        "S_vault_encrypted_alias_tag": block(2, [header] + hexlines).replace("!vault", "!vault-encrypted"),
        "T_flow_mapping_in_list": "l:\n  - !vault |\n    " + "\n    ".join([header] + hexlines) + "\n",
        "U_tampered_last_hex_digit": block(2, [header] + hexlines[:-1] + [tampered]),
        "V_odd_length_hex": block(2, [header] + hexlines[:-1] + [last[:-1]]),
        "W_tab_inside_hex_line": block(2, [header] + [hexlines[0][:40] + "\t" + hexlines[0][40:]] + hexlines[1:]),
        "X_comment_after_tag": "x: !vault | # note\n" + "".join("  " + line + "\n" for line in [header] + hexlines),
        "Y_two_header_fields": block(2, ["$ANSIBLE_VAULT;1.1"] + hexlines),
        "Z_empty_label_field": block(2, ["$ANSIBLE_VAULT;1.2;AES256;"] + hexlines),
    }


def craft(header, salt, mac, ct):
    """An envelope with the given payload parts, formatted as ansible-vault formats it."""
    inner = binascii.hexlify(salt) + b"\n" + binascii.hexlify(mac) + b"\n" + binascii.hexlify(ct)
    body = binascii.hexlify(inner).decode()
    return (header + "\n" + "".join(body[i:i + 80] + "\n" for i in range(0, len(body), 80))).encode()


def file_cases(envelope, header, hexlines, password):
    fenv = envelope.encode()
    inner = binascii.unhexlify("".join(hexlines))
    salt_hex, mac_hex, ct_hex = inner.split(b"\n", 2)
    salt, ct = binascii.unhexlify(salt_hex), binascii.unhexlify(ct_hex)
    dk = hashlib.pbkdf2_hmac("sha256", password, salt, 10000, 80)

    def signed(cipher_text):
        return hmac.new(dk[32:64], cipher_text, hashlib.sha256).digest()

    def rehex(payload):
        body = binascii.hexlify(payload).decode()
        return (header + "\n" + "".join(body[i:i + 80] + "\n" for i in range(0, len(body), 80))).encode()

    bad_pad = ct[:-1] + bytes([ct[-1] ^ 0x01])
    return {
        "a_standard": fenv,
        "b_no_trailing_newline": fenv.rstrip(b"\n"),
        "c_CRLF": fenv.replace(b"\n", b"\r\n"),
        "d_leading_newline": b"\n" + fenv,
        "e_utf8_BOM": b"\xef\xbb\xbf" + fenv,
        "f_trailing_blank_lines": fenv + b"\n\n\n",
        "g_leading_spaces_on_hex_lines": (header + "\n" + "".join("  " + line + "\n" for line in hexlines)).encode(),
        "h_trailing_spaces_on_hex_lines": (header + "\n" + "".join(line + " \n" for line in hexlines)).encode(),
        "i_header_leading_space": b" " + fenv,
        "j_lone_CR_line_endings": fenv.replace(b"\n", b"\r"),
        "k_non_ascii_after_body": fenv + b"\xc3\xa4\n",
        "l_magic_only": b"$ANSIBLE_VAULT\n",
        "m_no_body": (header + "\n").encode(),
        "n_payload_without_separators": rehex(inner.replace(b"\n", b"")),
        "o_payload_one_separator": rehex(salt_hex + b"\n" + mac_hex + ct_hex),
        "p_salt_not_hex": rehex(b"zz" + salt_hex[2:] + b"\n" + mac_hex + b"\n" + ct_hex),
        "q_hmac_not_hex": rehex(salt_hex + b"\n" + b"zz" + mac_hex[2:] + b"\n" + ct_hex),
        "r_ciphertext_not_hex": rehex(salt_hex + b"\n" + mac_hex + b"\n" + b"zz" + ct_hex[2:]),
        "s_ciphertext_odd_hex": rehex(salt_hex + b"\n" + mac_hex + b"\n" + ct_hex[:-1]),
        "t_invalid_padding_valid_hmac": craft(header, salt, signed(bad_pad), bad_pad),
        "u_empty_ciphertext_valid_hmac": craft(header, salt, signed(b""), b""),
        "v_partial_block_valid_hmac": craft(header, salt, signed(ct[:-1]), ct[:-1]),
        "w_short_hmac": craft(header, salt, signed(ct)[:16], ct),
        "x_lowercase_magic": fenv.replace(b"$ANSIBLE_VAULT", b"$ansible_vault", 1),
        "y_empty_salt_wrong_hmac": craft(header, b"", signed(ct), ct),
        "z_header_line_with_tab_and_vt": fenv.replace(header.encode(), b"$ANSIBLE_VAULT;\t1.1\x0b;AES256 ", 1),
    }


def oracle(inp):
    # Configuration is read when ansible is imported: start from an empty config and no ANSIBLE_* variables.
    work = os.path.realpath(tempfile.mkdtemp(prefix="vaultgen-oracle-"))
    isolate_ansible(work)
    import warnings
    warnings.simplefilter("ignore")
    try:
        return run_oracle(inp, work)
    finally:
        shutil.rmtree(work, ignore_errors=True)


def run_oracle(inp, work):
    from ansible import constants as C
    from ansible.parsing.dataloader import DataLoader
    from ansible.parsing.vault import VaultLib, VaultSecret, is_encrypted

    version = ansible_version()
    passwords = inp["passwords"]
    vectors = inp["vectors"]
    secret = {pid: VaultSecret(pw_bytes(passwords, pid)) for pid in passwords}
    result = {"version": version}

    # 1. Every vector decrypts with its password.
    cross = {}
    for vid, vec in vectors.items():
        try:
            plain = VaultLib([("x", secret[vec["passwordId"]])]).decrypt(vec["envelope"].encode())
            cross[vid] = "OK" if bytes(plain).hex() == vec["plaintextHex"] else "WRONG_PLAINTEXT"
        except Exception as exc:
            cross[vid] = "ERR " + classify(exc)[0]
    result["vectors"] = cross

    # 2. Tolerance matrices on v01 (password pw1), through Ansible's own YAML loader.
    loader_secrets = [("default", secret["pw1"]), ("dev", secret["dev"]), ("prod", secret["prod"])]
    try:
        from ansible.parsing.vault import VaultSecretsContext
        VaultSecretsContext.initialize(VaultSecretsContext(loader_secrets))
    except ImportError:
        pass
    loader = DataLoader()
    loader.set_vault_secrets(loader_secrets)
    v01 = vectors["v01"]["envelope"]
    expect = bytes.fromhex(vectors["v01"]["plaintextHex"]).decode()
    header, *hexlines = v01.rstrip("\n").split("\n")
    pyyaml = vault_scalar_loader()
    import yaml

    def value_of(data):
        v = data["x"] if "x" in data else data["l"][0]
        s = v.data if hasattr(v, "data") and not isinstance(v, str) else str(v)
        if hasattr(s, "decode"):
            s = s.decode()
        return str(s)

    yaml_rows = []
    for case_id, text in yaml_cases(header, hexlines).items():
        try:
            parsed = yaml.load(text, Loader=pyyaml)
            scalar = parsed["x"] if "x" in parsed else parsed["l"][0]
        except Exception:
            scalar = None
        try:
            got = value_of(loader.load(text))
            outcome, detail = ("OK", None) if got == expect else ("WRONG_PLAINTEXT", None)
        except Exception as exc:
            outcome, detail = classify(exc)
        yaml_rows.append({"id": case_id, "yaml": text, "scalar": scalar, "result": outcome, "detail": detail})
    result["yaml"] = yaml_rows

    file_rows = []
    vl = VaultLib(loader_secrets)
    for case_id, data in file_cases(v01, header, hexlines, pw_bytes(passwords, "pw1")).items():
        detected = bool(is_encrypted(data[:14])) if len(data) >= 14 else False
        if not is_encrypted(data):
            outcome, detail = "NOT_VAULT", None
        else:
            try:
                outcome = "OK" if bytes(vl.decrypt(data)) == expect.encode() else "WRONG_PLAINTEXT"
                detail = None
            except Exception as exc:
                outcome, detail = classify(exc)
        file_rows.append({"id": case_id, "hex": data.hex(), "fileHeaderDetected": detected,
                          "result": outcome, "detail": detail})
    result["file"] = file_rows

    # 3. Multi-vault matching: which secret decrypts, and the exact order of tries.
    class Recording(VaultSecret):
        def __init__(self, label, data, log):
            super().__init__(data)
            self.label = label
            self.log = log

        @property
        def bytes(self):
            self.log.append(self.label)
            return self._bytes

    v02 = vectors["v02"]["envelope"]
    envelopes = {k: vectors[k]["envelope"] for k in ("v01", "v02", "v03", "v13", "v14")}
    envelopes["v02-empty-label"] = v02.replace("$ANSIBLE_VAULT;1.2;AES256;dev", "$ANSIBLE_VAULT;1.2;AES256;", 1)
    envelopes["v02-label-on-1.1"] = v02.replace("$ANSIBLE_VAULT;1.2;AES256;dev", "$ANSIBLE_VAULT;1.1;AES256;dev", 1)
    secret_lists = {
        "[dev,prod]": [("dev", "dev"), ("prod", "prod")],
        "[prod,dev]": [("prod", "prod"), ("dev", "dev")],
        "[default,prod]": [("default", "pw1"), ("prod", "prod")],
        "[prod,default,dev]": [("prod", "prod"), ("default", "pw1"), ("dev", "dev")],
        "[dev]": [("dev", "dev")],
        "[x=prod]": [("x", "prod")],
        "[team=pw1,prod]": [("team", "pw1"), ("prod", "prod")],
    }
    original_match, original_identity = C.DEFAULT_VAULT_ID_MATCH, C.DEFAULT_VAULT_IDENTITY
    mv_rows = []
    for identity in ("default", "team"):
        for match in (False, True):
            C.DEFAULT_VAULT_ID_MATCH = match
            C.DEFAULT_VAULT_IDENTITY = identity
            for list_id, entries in secret_lists.items():
                if (identity == "team") != list_id.startswith("[team"):
                    continue
                for env_id, env_text in envelopes.items():
                    log = []
                    secs = [(label, Recording(label, pw_bytes(passwords, pid), log)) for label, pid in entries]
                    try:
                        _, used, _ = VaultLib(secs).decrypt_and_get_vault_id(env_text.encode())
                        outcome, via = "OK", used
                    except Exception as exc:
                        outcome, via = classify(exc)[0], None
                    mv_rows.append({"defaultIdentity": identity, "match": match, "secrets": [list(e) for e in entries],
                                    "secretsName": list_id, "envelope": env_id, "envelopeText": env_text,
                                    "result": outcome, "via": via, "tried": log})
    C.DEFAULT_VAULT_ID_MATCH, C.DEFAULT_VAULT_IDENTITY = original_match, original_identity
    result["multivault"] = mv_rows

    # 4. The multi-vault fixture through Ansible's loader.
    fx = inp["fixture"]
    data = loader.load(fx["text"])
    result["fixture"] = {key: ("OK" if value_of({"x": data[key]}) == plain else "MISMATCH")
                         for key, plain in fx["expected"].items()}

    # 5. CLI and configuration probes (fresh processes, each with its own configuration).
    tree_root = os.path.join(work, "t")
    tree = build_tree(tree_root, passwords, VaultLib, VaultSecret)
    result["tree"] = tree
    result["deterministicV10"] = deterministic_v10(tree_root, inp)
    result["config"] = [relativize(run_probe(tree_root, row), tree_root) for row in CONFIG_ROWS]
    result["tries"] = [relativize(run_tries(tree_root, row, vectors), tree_root) for row in TRIES_ROWS]
    result["encrypt"] = [relativize(run_encrypt(tree_root, row, passwords, VaultLib, VaultSecret), tree_root)
                         for row in ENCRYPT_ROWS]
    result["edit"] = [relativize(run_edit(tree_root, row, passwords, vectors, VaultLib, VaultSecret), tree_root)
                      for row in EDIT_ROWS]
    return result


# ---- the probe tree -------------------------------------------------------------------------------------------------

#: The salt of every envelope the oracle writes itself, so that re-runs give identical tables.
ORACLE_SALT = "vaultgen-oracle-salt"


def multi_client_script(passwords):
    lines = [
        "#!/bin/sh",
        "# synthetic vault client script: one test password per --vault-id label",
        "printf '%s\\n' \"[$*]\" >> \"$(dirname \"$0\")/script-args.log\"",
        'case "$2" in',
    ]
    for label, pid in (("dev", "dev"), ("prod", "prod"), ("default", "pw1")):
        lines.append(f"  {label}) " + printf_script(source_bytes(passwords, pid)).strip() + " ;;")
    lines += ['  *) echo "unknown vault id $2" >&2; exit 2 ;;', "esac", ""]
    return "\n".join(lines).encode()


def plain_script(passwords):
    return ("#!/bin/sh\n# synthetic plain vault password script (no arguments)\n"
            "printf '%s\\n' \"[$*]\" >> \"$(dirname \"$0\")/script-args.log\"\n"
            + printf_script(source_bytes(passwords, "script"))).encode()


def build_tree(root, passwords, VaultLib, VaultSecret):
    """The files every probe row sees; returns {relative path: {hex, executable}}."""
    inner_envelope = VaultLib().encrypt(source_bytes(passwords, "inner"), VaultSecret(pw_bytes(passwords, "pw1")),
                                        salt=ORACLE_SALT)
    files = {
        "cfgdir/pw.txt": (source_bytes(passwords, "pw1"), False),
        "elsewhere/pw.txt": (source_bytes(passwords, "dev"), False),
        "home/pw.txt": (source_bytes(passwords, "prod"), False),
        "vars/pw.txt": (source_bytes(passwords, "utf8"), False),
        "elsewhere/pw1.txt": (source_bytes(passwords, "pw1"), False),
        "elsewhere/pw-dev.txt": (source_bytes(passwords, "dev"), False),
        "elsewhere/pw-prod.txt": (source_bytes(passwords, "prod"), False),
        "elsewhere/pw-spaced.txt": (source_bytes(passwords, "spaced"), False),
        "elsewhere/pw-nonutf8.txt": (source_bytes(passwords, "nonutf8"), False),
        "elsewhere/pw-ws.txt": (source_bytes(passwords, "ws"), False),
        "elsewhere/pw-empty.txt": (b"  \r\n", False),
        "elsewhere/pw-vaulted.txt": (bytes(inner_envelope), False),
        "elsewhere/notexec-client.sh": (source_bytes(passwords, "dev"), False),
        "elsewhere/multi-client.sh": (multi_client_script(passwords), True),
        "elsewhere/plain-script.sh": (plain_script(passwords), True),
        "elsewhere/ed-append.sh": (b"#!/bin/sh\nprintf 'edited\\n' >> \"$1\"\n", True),
        "elsewhere/ed-noop.sh": (b"#!/bin/sh\nexit 0\n", True),
    }
    for rel, (data, executable) in files.items():
        write_bytes(os.path.join(root, rel), data, executable)
    os.makedirs(os.path.join(root, "home"), exist_ok=True)
    return {rel: {"hex": data.hex(), "executable": executable} for rel, (data, executable) in sorted(files.items())}


def write_cfg(root, ini):
    lines = ["[defaults]"]
    for key, value in (ini or {}).items():
        value = value.replace("{T}", root)
        lines.append(f"{key} =" + (f" {value}" if value != "" else ""))
    text = "\n".join(lines) + "\n"
    path = os.path.join(root, "cfgdir", "ansible.cfg")
    write_bytes(path, text.encode())
    parser = configparser.ConfigParser(inline_comment_prefixes=(";",))
    parser.read_string(text)
    defaults = {k: parser.get("defaults", k, raw=True) for k in parser.options("defaults")}
    return path, defaults


def row_env(root, row, extra=None):
    env = clean_env(os.path.join(root, "home"), os.path.join(root, "cfgdir", "ansible.cfg"))
    for key, value in (row.get("env") or {}).items():
        env[key] = value.replace("{T}", root)
    env.update(extra or {})
    return env


def relativize(value, root):
    if isinstance(value, str):
        return value.replace(root, "{T}")
    if isinstance(value, list):
        return [relativize(v, root) for v in value]
    if isinstance(value, dict):
        return {k: relativize(v, root) for k, v in value.items()}
    return value


def pop_script_args(root):
    log = os.path.join(root, "elsewhere", "script-args.log")
    if not os.path.exists(log):
        return []
    with open(log, encoding="utf-8") as fh:
        args = [line.rstrip("\n") for line in fh]
    os.remove(log)
    return args


PROBE = r'''
import inspect, json, os, sys, warnings
warnings.simplefilter("ignore")
from ansible import constants as C
from ansible.cli import CLI
from ansible.parsing.dataloader import DataLoader
import ansible.parsing.vault as V
prompt = os.environ.get("VAULTGEN_PROMPT_HEX")
if prompt is not None:
    V.display.prompt = lambda *a, **k: bytes.fromhex(prompt).decode("utf-8")
kwargs = dict(vault_ids=list(C.DEFAULT_VAULT_IDENTITY_LIST), vault_password_files=[], ask_vault_pass=False,
              auto_prompt=False)
if "initialize_context" in inspect.signature(CLI.setup_vault_secrets).parameters:
    kwargs["initialize_context"] = False
error = None
try:
    secrets = CLI.setup_vault_secrets(DataLoader(), **kwargs)
except Exception as exc:
    secrets, error = [], type(exc).__name__
def plain(v):
    if isinstance(v, (list, tuple)):
        return [plain(x) for x in v]
    if isinstance(v, bool) or v is None:
        return v
    return str(v)
print(json.dumps({
    "constants": {
        "DEFAULT_VAULT_IDENTITY_LIST": plain(C.DEFAULT_VAULT_IDENTITY_LIST),
        "DEFAULT_VAULT_PASSWORD_FILE": plain(C.DEFAULT_VAULT_PASSWORD_FILE),
        "DEFAULT_VAULT_IDENTITY": plain(C.DEFAULT_VAULT_IDENTITY),
        "DEFAULT_VAULT_ID_MATCH": plain(C.DEFAULT_VAULT_ID_MATCH),
        "DEFAULT_VAULT_ENCRYPT_IDENTITY": plain(C.DEFAULT_VAULT_ENCRYPT_IDENTITY),
        "VAULT_ENCRYPT_SALT": plain(C.config.get_config_value("VAULT_ENCRYPT_SALT")),
    },
    "secrets": [{
        "label": str(label),
        "type": type(s).__name__,
        "filename": plain(getattr(s, "filename", None)),
        "clientVaultId": plain(getattr(s, "_vault_id", None)),
        "bytesHex": (s.bytes or b"").hex(),
    } for label, s in secrets],
    "error": error,
}))
'''

#: Configuration rows: cfg keys of [defaults] (values as written; {T} is the probe tree), environment, cwd.
CONFIG_ROWS = [
    dict(id="defaults-none", ini={}),
    dict(id="path-cfg-relative", ini={"vault_password_file": "pw.txt"}),
    dict(id="path-cfg-dotdot", ini={"vault_password_file": "../cfgdir/./pw.txt"}),
    dict(id="path-cfg-cwd-magic", ini={"vault_password_file": "{{CWD}}/pw.txt"}),
    dict(id="path-cfg-home", ini={"vault_password_file": "~/pw.txt"}),
    dict(id="path-cfg-envvar", ini={"vault_password_file": "$PWDIR/pw.txt"}, env={"PWDIR": "{T}/vars"}),
    dict(id="path-cfg-envvar-braces", ini={"vault_password_file": "${PWDIR}/pw.txt"}, env={"PWDIR": "{T}/vars"}),
    dict(id="path-cfg-envvar-unset", ini={"vault_password_file": "$NOPE/pw.txt"}),
    dict(id="path-cfg-absolute", ini={"vault_password_file": "{T}/vars/pw.txt"}),
    dict(id="path-cfg-quoted", ini={"vault_password_file": '"pw.txt"'}),
    dict(id="path-env-relative", env={"ANSIBLE_VAULT_PASSWORD_FILE": "pw.txt"}),
    dict(id="path-env-beats-ini", ini={"vault_password_file": "pw.txt"}, env={"ANSIBLE_VAULT_PASSWORD_FILE": "pw.txt"}),
    dict(id="path-list-relative-to-cwd", ini={"vault_identity_list": "dev@pw.txt"}),
    dict(id="path-list-cwd-is-cfgdir", ini={"vault_identity_list": "dev@pw.txt"}, cwd="cfgdir"),
    dict(id="path-list-home", ini={"vault_identity_list": "dev@~/pw.txt"}),
    dict(id="path-list-envvar", ini={"vault_identity_list": "dev@$PWDIR/pw.txt"}, env={"PWDIR": "{T}/vars"}),
    dict(id="path-list-absolute", ini={"vault_identity_list": "dev@{T}/vars/pw.txt"}),
    dict(id="order-list-then-password-file",
         ini={"vault_identity_list": "prod@pw-prod.txt , dev@pw-dev.txt", "vault_password_file": "pw.txt"}),
    dict(id="order-env-list-beats-ini", ini={"vault_identity_list": "prod@pw-prod.txt"},
         env={"ANSIBLE_VAULT_IDENTITY_LIST": "dev@pw-dev.txt,prod@pw-prod.txt"}),
    dict(id="order-duplicates-kept", ini={"vault_identity_list": "dev@pw-dev.txt, dev@pw-dev.txt"}),
    dict(id="order-missing-skipped", ini={"vault_identity_list": "dev@missing.txt, prod@pw-prod.txt"}),
    dict(id="label-none", ini={"vault_identity_list": "pw-dev.txt"}),
    dict(id="label-empty", ini={"vault_identity_list": "@pw-dev.txt"}),
    dict(id="label-at-in-path", ini={"vault_identity_list": "dev@pw@x.txt"}),
    dict(id="label-identity-renamed",
         ini={"vault_identity": "team", "vault_identity_list": "pw-dev.txt", "vault_password_file": "pw.txt"}),
    dict(id="list-quoted-items", ini={"vault_identity_list": "\"dev@pw-dev.txt\", 'prod@pw-prod.txt'"}),
    dict(id="list-quoted-whole", ini={"vault_identity_list": "\"dev@pw-dev.txt, prod@pw-prod.txt\""}),
    dict(id="list-trailing-comma", ini={"vault_identity_list": "dev@pw-dev.txt,"}),
    dict(id="strip-spaced", ini={"vault_identity_list": "default@pw-spaced.txt"}),
    dict(id="strip-ws", ini={"vault_identity_list": "default@pw-ws.txt"}),
    dict(id="strip-nonutf8", ini={"vault_identity_list": "default@pw-nonutf8.txt"}),
    dict(id="strip-empty-refused", ini={"vault_identity_list": "default@pw-empty.txt"}),
    dict(id="vaulted-file-after-its-secret", ini={"vault_identity_list": "default@pw1.txt, prod@pw-vaulted.txt"}),
    dict(id="vaulted-file-before-its-secret", ini={"vault_identity_list": "prod@pw-vaulted.txt, default@pw1.txt"}),
    dict(id="vaulted-file-match-off", ini={"vault_identity_list": "x@pw1.txt, prod@pw-vaulted.txt"}),
    dict(id="vaulted-file-match-on", ini={"vault_identity_list": "x@pw1.txt, prod@pw-vaulted.txt",
                                          "vault_id_match": "yes"}),
    dict(id="client-labelled", ini={"vault_identity_list": "prod@multi-client.sh"}),
    dict(id="client-unlabelled", ini={"vault_identity_list": "multi-client.sh"}),
    dict(id="client-empty-label", ini={"vault_identity_list": "@multi-client.sh"}),
    dict(id="client-password-file", ini={"vault_password_file": "../elsewhere/multi-client.sh"}),
    dict(id="client-unknown-id", ini={"vault_identity_list": "staging@multi-client.sh"}),
    dict(id="client-renamed-default", ini={"vault_identity": "team", "vault_password_file": "../elsewhere/multi-client.sh"}),
    dict(id="client-not-executable", ini={"vault_identity_list": "dev@notexec-client.sh"}),
    dict(id="script-plain", ini={"vault_identity_list": "dev@plain-script.sh"}),
    dict(id="prompt-labelled", ini={"vault_identity_list": "dev@prompt"}, prompt="prompt"),
    dict(id="prompt-ask-vault-pass", ini={"vault_identity_list": "prompt_ask_vault_pass"}, prompt="prompt"),
    dict(id="prompt-empty-label", ini={"vault_identity_list": "@prompt"}, prompt="prompt"),
    dict(id="prompt-whitespace-only", ini={"vault_identity_list": "dev@prompt"}, promptHex="202009"),
    dict(id="match-ini-false", ini={"vault_id_match": "false"}),
    dict(id="match-ini-zero", ini={"vault_id_match": "0"}),
    dict(id="match-ini-quoted-empty", ini={"vault_id_match": '""'}),
    dict(id="match-ini-empty", ini={"vault_id_match": ""}),
    dict(id="match-ini-single-quoted-no", ini={"vault_id_match": "'no'"}),
    dict(id="match-env-false", env={"ANSIBLE_VAULT_ID_MATCH": "false"}),
    dict(id="match-env-empty-beats-ini", ini={"vault_id_match": "True"}, env={"ANSIBLE_VAULT_ID_MATCH": ""}),
    dict(id="match-env-quoted-empty", env={"ANSIBLE_VAULT_ID_MATCH": '""'}),
    dict(id="identity-ini-quoted", ini={"vault_identity": "'team'"}),
    dict(id="identity-env", env={"ANSIBLE_VAULT_IDENTITY": "team"}),
    dict(id="encrypt-identity-ini-quoted", ini={"vault_encrypt_identity": '"dev"'}),
    dict(id="encrypt-identity-env", ini={"vault_encrypt_identity": "dev"}, env={"ANSIBLE_VAULT_ENCRYPT_IDENTITY": "prod"}),
    dict(id="encrypt-salt-ini", ini={"vault_encrypt_salt": "fixed-salt-for-tests"}),
    dict(id="encrypt-salt-env", env={"ANSIBLE_VAULT_ENCRYPT_SALT": "env salt"}),
]


def run_probe(root, row):
    _, defaults = write_cfg(root, row.get("ini"))
    extra = {}
    if "prompt" in row:
        extra["VAULTGEN_PROMPT_HEX"] = row_prompt_hex(row)
    elif "promptHex" in row:
        extra["VAULTGEN_PROMPT_HEX"] = row["promptHex"]
    env = row_env(root, row, extra)
    pop_script_args(root)
    p = subprocess.run([sys.executable, "-c", PROBE], cwd=os.path.join(root, row.get("cwd", "elsewhere")), env=env,
                       stdin=subprocess.DEVNULL, capture_output=True, timeout=120)
    if p.returncode != 0:
        sys.exit(f"probe {row['id']} crashed: {p.stderr.decode(errors='replace')}")
    out = json.loads(p.stdout.decode())
    return {
        "id": row["id"],
        "cfg": defaults,
        "env": row.get("env") or {},
        "cwd": row.get("cwd", "elsewhere"),
        "promptHex": extra.get("VAULTGEN_PROMPT_HEX"),
        **out,
        "scriptArgs": pop_script_args(root),
    }


_PROMPT_SOURCE = {}


def row_prompt_hex(row):
    return _PROMPT_SOURCE[row["prompt"]]


#: Decrypt runs with -vvvvv: the labels Ansible tries, in order.
TRIES_ROWS = [
    dict(id="tries-all-match-off", envelope="v15",
         ini={"vault_identity_list": "prod@pw-prod.txt, dev@pw-dev.txt", "vault_password_file": "pw.txt"}),
    dict(id="tries-label-not-first", envelope="v02",
         ini={"vault_identity_list": "prod@pw-prod.txt, dev@pw-dev.txt", "vault_password_file": "pw.txt"}),
    dict(id="tries-match-on-label-only", envelope="v02",
         ini={"vault_identity_list": "prod@pw-prod.txt, dev@pw-dev.txt", "vault_password_file": "pw.txt",
              "vault_id_match": "false"}),
    dict(id="tries-match-on-mislabelled", envelope="v13",
         ini={"vault_identity_list": "prod@pw-prod.txt, dev@pw-dev.txt", "vault_id_match": "false"}),
    dict(id="tries-match-on-unlabelled", envelope="v14",
         ini={"vault_identity_list": "prod@pw-prod.txt, dev@pw-dev.txt", "vault_password_file": "pw.txt",
              "vault_id_match": "false"}),
    dict(id="tries-match-off-unlabelled", envelope="v14",
         ini={"vault_identity_list": "dev@pw-dev.txt, prod@pw-prod.txt", "vault_password_file": "pw.txt"}),
    dict(id="tries-renamed-default-match-on", envelope="v01",
         ini={"vault_identity": "team", "vault_password_file": "pw.txt", "vault_id_match": "on"}),
    dict(id="tries-env-match-false", envelope="v13",
         ini={"vault_identity_list": "dev@pw-dev.txt, prod@pw-prod.txt"}, env={"ANSIBLE_VAULT_ID_MATCH": "false"}),
]

TRY_LINE = re.compile(r"Trying to use vault secret=\(.*?\) id=(\S+) to decrypt")
SUCCESS_LINE = re.compile(r"successful with secret=.*? and vault_id=(\S+)")


def run_tries(root, row, vectors):
    _, defaults = write_cfg(root, row.get("ini"))
    target = os.path.join(root, "elsewhere", "probe.vault")
    write_bytes(target, vectors[row["envelope"]]["envelope"].encode())
    p = run_vault(["decrypt", "--output", "-", "-vvvvv", target], os.path.join(root, "elsewhere"), row_env(root, row))
    text = (p.stdout + b"\n" + p.stderr).decode(errors="replace")
    success = SUCCESS_LINE.search(text)
    return {
        "id": row["id"], "cfg": defaults, "env": row.get("env") or {}, "cwd": "elsewhere",
        "envelope": row["envelope"], "tried": TRY_LINE.findall(text),
        "result": "OK" if p.returncode == 0 else "FAILED", "via": success.group(1) if success else None,
    }


#: encrypt_string runs: which id Ansible encrypts with, or why it refuses.
ENCRYPT_ROWS = [
    dict(id="encrypt-two-ids-refused", ini={"vault_identity_list": "dev@pw-dev.txt, prod@pw-prod.txt"}),
    dict(id="encrypt-default-writes-1.1", ini={"vault_password_file": "pw.txt"}),
    dict(id="encrypt-renamed-default", ini={"vault_identity": "team", "vault_password_file": "pw.txt"}),
    dict(id="encrypt-identity-chooses", ini={"vault_identity_list": "dev@pw-dev.txt, prod@pw-prod.txt",
                                             "vault_encrypt_identity": "prod"}),
    dict(id="encrypt-identity-not-found", ini={"vault_identity_list": "dev@pw-dev.txt", "vault_encrypt_identity": "qa"}),
    dict(id="encrypt-default-label-dev-password", ini={"vault_identity_list": "default@pw-dev.txt"}),
    dict(id="encrypt-duplicate-labels-refused", ini={"vault_identity_list": "pw1.txt, pw1.txt"}),
    dict(id="encrypt-identity-env", ini={"vault_identity_list": "dev@pw-dev.txt, prod@pw-prod.txt"},
         env={"ANSIBLE_VAULT_ENCRYPT_IDENTITY": "dev"}),
    dict(id="encrypt-identity-default-name", ini={"vault_identity_list": "default@pw1.txt, dev@pw-dev.txt",
                                                  "vault_encrypt_identity": "default"}),
    dict(id="encrypt-identity-first-of-duplicates", ini={"vault_identity_list": "dev@pw-dev.txt, dev@pw1.txt",
                                                         "vault_encrypt_identity": "dev"}),
]


def decrypting_password(envelope_bytes, passwords, VaultLib, VaultSecret):
    for pid in passwords:
        try:
            VaultLib([("x", VaultSecret(pw_bytes(passwords, pid)))]).decrypt(envelope_bytes)
            return pid
        except Exception:
            continue
    return None


def run_encrypt(root, row, passwords, VaultLib, VaultSecret):
    _, defaults = write_cfg(root, row.get("ini"))
    p = run_vault(["encrypt_string", "--name", "probe", "synthetic value"], os.path.join(root, "elsewhere"),
                  row_env(root, row))
    out = {"id": row["id"], "cfg": defaults, "env": row.get("env") or {}, "cwd": "elsewhere"}
    if p.returncode != 0:
        err = p.stderr.decode(errors="replace")
        refusal = ("AMBIGUOUS" if "available to encrypt" in err
                   else "NOT_FOUND" if "Did not find a match" in err else "OTHER")
        return dict(out, result="REFUSED", refusal=refusal, header=None, decryptsWith=None)
    lines = p.stdout.decode().split("\n")
    envelope = "\n".join(line.strip() for line in lines[1:] if line.strip()) + "\n"
    return dict(out, result="OK", refusal=None, header=envelope.split("\n", 1)[0],
                decryptsWith=decrypting_password(envelope.encode(), passwords, VaultLib, VaultSecret))


#: ansible-vault edit runs: the header and the secret the edited file is written with.
EDIT_ROWS = [
    dict(id="edit-1.1-renamed-default", password="pw1", header="$ANSIBLE_VAULT;1.1;AES256",
         ini={"vault_identity": "team", "vault_password_file": "pw.txt"}),
    dict(id="edit-1.1-with-label-field", password="dev", header="$ANSIBLE_VAULT;1.1;AES256;dev",
         ini={"vault_identity_list": "dev@pw-dev.txt"}),
    dict(id="edit-1.2-without-label", password="pw1", header="$ANSIBLE_VAULT;1.2;AES256",
         ini={"vault_password_file": "pw.txt"}),
    dict(id="edit-mislabelled-keeps-label", password="prod", header="$ANSIBLE_VAULT;1.2;AES256;dev",
         ini={"vault_identity_list": "dev@pw-dev.txt, prod@pw-prod.txt"}),
    dict(id="edit-labelled-keeps-label", password="dev", header="$ANSIBLE_VAULT;1.2;AES256;dev",
         ini={"vault_identity_list": "prod@pw-prod.txt, dev@pw-dev.txt"}),
    dict(id="edit-no-change-writes-nothing", password="pw1", header="$ANSIBLE_VAULT;1.1;AES256",
         ini={"vault_password_file": "pw.txt"}, editor="ed-noop.sh"),
]

EDIT_PLAINTEXT = b"synthetic edit plaintext\n"


def run_edit(root, row, passwords, vectors, VaultLib, VaultSecret):
    _, defaults = write_cfg(root, row.get("ini"))
    sealed = VaultLib().encrypt(EDIT_PLAINTEXT, VaultSecret(pw_bytes(passwords, row["password"])), salt=ORACLE_SALT)
    text = sealed.decode()
    body = text.split("\n", 1)[1]
    original = (row["header"] + "\n" + body).encode()
    target = os.path.join(root, "elsewhere", "edit.vault")
    write_bytes(target, original)
    editor = os.path.join(root, "elsewhere", row.get("editor", "ed-append.sh"))
    p = run_vault(["edit", target], os.path.join(root, "elsewhere"), row_env(root, row, {"EDITOR": editor}))
    with open(target, "rb") as fh:
        after = fh.read()
    pid = decrypting_password(after, passwords, VaultLib, VaultSecret)
    plain = None
    if pid:
        plain = bytes(VaultLib([("x", VaultSecret(pw_bytes(passwords, pid)))]).decrypt(after)).hex()
    return {
        "id": row["id"], "cfg": defaults, "env": row.get("env") or {}, "cwd": "elsewhere",
        "originalHeader": row["header"], "originalPassword": row["password"], "originalHex": original.hex(),
        "result": "OK" if p.returncode == 0 else "FAILED", "unchanged": after == original,
        "header": after.decode(errors="replace").split("\n", 1)[0], "decryptsWith": pid, "plaintextHex": plain,
    }


def deterministic_v10(root, inp):
    """VAULT_ENCRYPT_SALT makes encrypt_string reproduce v10 byte for byte, in this version too."""
    write_cfg(root, {"vault_password_file": "../elsewhere/pw1.txt"})
    p = run_vault(["encrypt_string", "--name", "deterministic", "same every time"], os.path.join(root, "elsewhere"),
                  row_env(root, {"env": {"ANSIBLE_VAULT_ENCRYPT_SALT": inp["vectors"]["v10"]["encryptSalt"]}}))
    return p.returncode == 0 and p.stdout.decode() == inp["v10Raw"]


# --------------------------------------------------------------------------------------------------------------------
# split
# --------------------------------------------------------------------------------------------------------------------

def split(oracle_json, out, expected_version):
    with open(oracle_json, encoding="utf-8") as fh:
        doc = json.load(fh)
    version = doc["version"]
    if version != expected_version:
        sys.exit(f"{oracle_json}: ansible-core {version}, expected {expected_version}")
    short = ".".join(version.split(".")[:2])
    bad = [k for k, v in doc["vectors"].items() if v != "OK"] + [k for k, v in doc["fixture"].items() if v != "OK"]
    if bad or not doc["deterministicV10"]:
        sys.exit(f"ansible-core {version} disagrees with the vectors: {bad or 'v10 not deterministic'}")
    tol = {k: doc[k] for k in ("version", "vectors", "fixture", "deterministicV10", "yaml", "file", "multivault")}
    config = {k: doc[k] for k in ("version", "tree", "config", "tries", "encrypt", "edit")}
    for name, data in ((f"tol-{short}.json", tol), (f"config-{short}.json", config)):
        with open(os.path.join(out, name), "w", encoding="utf-8") as fh:
            json.dump(data, fh, indent=1, ensure_ascii=False)
            fh.write("\n")
    print(f"{version}: {len(doc['yaml'])} yaml, {len(doc['file'])} file, {len(doc['multivault'])} multi-vault, "
          f"{len(doc['config'])} config, {len(doc['tries'])} tries, {len(doc['encrypt'])} encrypt, "
          f"{len(doc['edit'])} edit rows", file=sys.stderr)


def main(argv):
    mode = argv[1] if len(argv) > 1 else ""
    if mode == "vectors":
        args = argv[2:]
        fresh = "--fresh" in args
        seed = args[args.index("--seed") + 1] if "--seed" in args else None
        positional = [a for i, a in enumerate(args) if not a.startswith("--") and (i == 0 or args[i - 1] != "--seed")]
        vectors(positional[0], positional[1], positional[2], fresh=fresh, seed=seed)
    elif mode == "oracle":
        if len(argv) > 2:
            with open(argv[2], encoding="utf-8") as fh:
                inp = json.load(fh)
        else:
            inp = json.loads(base64.b64decode(globals()["VAULTGEN_INPUT"]).decode("utf-8"))
        _PROMPT_SOURCE["prompt"] = inp["passwords"]["prompt"]["sourceHex"]
        json.dump(oracle(inp), sys.stdout, ensure_ascii=False)
    elif mode == "split":
        split(argv[2], argv[3], argv[4])
    else:
        sys.exit(__doc__)


if __name__ == "__main__":
    main(sys.argv)
