"""Unit tests for the fixture sanitiser, verifier and sync (python3 -m unittest, stdlib only).

    python3 -m unittest discover -s tools/fixtures -p 'test_*.py'

All inputs are synthetic; nothing here reads the infra repo.
"""

from __future__ import annotations

import io
import ipaddress
import os
import sys
import tempfile
import textwrap
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import json  # noqa: E402

import rules  # noqa: E402
import sanitise  # noqa: E402
import sync  # noqa: E402
import vaultfixture  # noqa: E402
import verify  # noqa: E402

# An 80-character payload line shaped like real ansible-vault output (not a real secret).
REAL_HEX = "35333638646337323064366238653132326139363466643731623438376562333936616635633861"
assert len(REAL_HEX) == 80

# A payload shaped exactly like ansible-vault's (32-byte salt, one ciphertext block: 324 digits in lines of
# 80/80/80/80/4), encrypted with a password that is not the fixture's. Synthetic, not a real secret.
OTHER_PAYLOAD = vaultfixture.encrypt_payload(b"not the fixture", b"other-pass", bytes(range(32)))
OTHER_LINES = [OTHER_PAYLOAD[i : i + 80] for i in range(0, len(OTHER_PAYLOAD), 80)]
assert [len(x) for x in OTHER_LINES] == [80, 80, 80, 80, 4]

VECTORS = Path(__file__).resolve().parent.parent.parent / "semantics" / "src" / "test" / "resources" / "vault" / "vectors"


def fixture_payload(text: str, header_line: int) -> list:
    """The payload lines (without indentation) that follow the header on 1-based ``header_line`` of ``text``."""
    lines = text.split("\n")
    indent = lines[header_line - 1][: len(lines[header_line - 1]) - len(lines[header_line - 1].lstrip())]
    out = []
    for line in lines[header_line:]:
        m = rules.HEX_LINE_RX.match(line.rstrip("\r"))
        if m is None or m.group("indent") != indent:
            break
        out.append(m.group("hex"))
    return out


def dedent(text: str) -> str:
    return textwrap.dedent(text).lstrip("\n")


def redact(text: str) -> str:
    out, _ = sanitise.redact_text(text)
    return out


class LinesTest(unittest.TestCase):
    def test_split_and_join_round_trip_all_terminators(self):
        text = "a\nb\r\nc\rd"
        bodies, terms = sanitise.split_lines(text)
        self.assertEqual(bodies, ["a", "b", "c", "d"])
        self.assertEqual(terms, ["\n", "\r\n", "\r", ""])
        self.assertEqual(sanitise.join_lines(bodies, terms), text)

    def test_count_lines_ignores_trailing_terminator(self):
        self.assertEqual(sanitise.count_lines("a\nb\n"), 2)
        self.assertEqual(sanitise.count_lines("a\nb"), 2)
        self.assertEqual(sanitise.count_lines(""), 0)
        self.assertEqual(sanitise.count_lines("\n\n"), 2)


class VaultFixtureCryptoTest(unittest.TestCase):
    def test_aes256_known_answer(self):
        # FIPS-197 appendix C.3.
        key = bytes(range(32))
        block = bytes.fromhex("00112233445566778899aabbccddeeff")
        self.assertEqual(vaultfixture.aes256_encrypt_block(key, block).hex(), "8ea2b7ca516745bfeafc49904b496089")

    def test_reproduces_the_committed_ansible_vault_vectors_byte_for_byte(self):
        checked = 0
        for path in sorted(VECTORS.glob("v*.json")):
            v = json.loads(path.read_text(encoding="utf-8"))
            if v.get("plaintextHex") is None or v.get("saltHex") is None or not v.get("envelope"):
                continue
            payload = "".join(v["envelope"].split("\n")[1:])
            password = bytes.fromhex(v["passwordHex"])
            self.assertEqual(
                vaultfixture.encrypt_payload(bytes.fromhex(v["plaintextHex"]), password, bytes.fromhex(v["saltHex"])),
                payload,
                v["id"],
            )
            self.assertEqual(vaultfixture.decrypt_payload(payload, password), bytes.fromhex(v["plaintextHex"]), v["id"])
            checked += 1
        self.assertGreaterEqual(checked, 10)

    def test_wrong_password_and_damaged_payload_do_not_decrypt(self):
        self.assertIsNone(vaultfixture.decrypt_payload(OTHER_PAYLOAD, vaultfixture.FIXTURE_PASSWORD))
        self.assertEqual(vaultfixture.decrypt_payload(OTHER_PAYLOAD, b"other-pass"), b"not the fixture")
        self.assertIsNone(vaultfixture.decrypt_payload(OTHER_PAYLOAD[:-2], b"other-pass"))
        self.assertIsNone(vaultfixture.decrypt_payload("zz" + OTHER_PAYLOAD[2:], b"other-pass"))

    def test_payload_keeps_line_lengths_and_decrypts_to_the_dummy(self):
        for blocks in (1, 2, 3, 13, 107):
            digits = 260 + 64 * blocks
            lengths = [80] * (digits // 80) + ([digits % 80] if digits % 80 else [])
            lines = vaultfixture.payload_lines("a/vault.yml:3", lengths)
            self.assertEqual([len(x) for x in lines], lengths)
            plaintext = vaultfixture.decrypt_payload("".join(lines), vaultfixture.FIXTURE_PASSWORD)
            self.assertEqual(plaintext, b"dummy" + b"-" * (16 * (blocks - 1)))
            self.assertIsNone(vaultfixture.check_envelope(vaultfixture.FIXTURE_HEADER, lines))

    def test_payload_is_deterministic_and_distinct_per_value(self):
        a = vaultfixture.payload_lines("a/vault.yml:3", [80, 80, 80, 80, 4])
        self.assertEqual(a, vaultfixture.payload_lines("a/vault.yml:3", [80, 80, 80, 80, 4]))
        self.assertNotEqual(a, vaultfixture.payload_lines("a/vault.yml:9", [80, 80, 80, 80, 4]))
        # Addresses are blanked in the salt key, so a path never leaks through the salt.
        self.assertEqual(
            vaultfixture.fixture_salt("host_vars/10.1.2.3/vault.yml:2"), vaultfixture.fixture_salt("host_vars/10.9.9.9/vault.yml:2")
        )

    def test_odd_shapes_keep_the_line_count(self):
        for lengths in ([80], [80, 36], [80, 80, 7], [40] * 9, [1]):
            lines = vaultfixture.payload_lines("k:1", lengths)
            self.assertEqual(len(lines), len(lengths))
            self.assertTrue(all(lines))
            self.assertIsNone(vaultfixture.check_envelope(vaultfixture.FIXTURE_HEADER, lines))

    def test_check_envelope_reasons(self):
        good = vaultfixture.payload_lines("k:1", [80, 80, 80, 80, 4])
        self.assertEqual(
            vaultfixture.check_envelope("$ANSIBLE_VAULT;1.2;AES256;prod", good), "vault header is not the synthetic 1.1 header"
        )
        self.assertEqual(
            vaultfixture.check_envelope(vaultfixture.FIXTURE_HEADER, OTHER_LINES),
            "vault payload is not the synthetic fixture envelope",
        )
        self.assertEqual(
            vaultfixture.check_envelope(vaultfixture.FIXTURE_HEADER, good[:-1] + [good[-1] + " "]),
            "vault payload is empty or has trailing whitespace",
        )
        other = vaultfixture.encrypt_payload(b"real-looking", vaultfixture.FIXTURE_PASSWORD, bytes(32))
        self.assertEqual(
            vaultfixture.check_envelope(vaultfixture.FIXTURE_HEADER, [other]), "vault payload does not decrypt to the fixture dummy"
        )


class VaultTest(unittest.TestCase):
    def test_inline_vault_block_keeps_tag_indent_line_count_and_line_lengths(self):
        src = dedent(f"""
            ---
            vault_db_password: !vault |
              $ANSIBLE_VAULT;1.1;AES256
              {OTHER_LINES[0]}
              {OTHER_LINES[1]}
              {OTHER_LINES[2]}
              {OTHER_LINES[3]}
              {OTHER_LINES[4]}
            other: 1
        """)
        out, stats = sanitise.redact_text(src, salt_key="group_vars/all/vault.yml")
        lines = out.split("\n")
        self.assertEqual(sanitise.count_lines(out), sanitise.count_lines(src))
        self.assertEqual([len(x) for x in lines], [len(x) for x in src.split("\n")])
        self.assertEqual(lines[1], "vault_db_password: !vault |")
        self.assertEqual(lines[2], "  " + vaultfixture.FIXTURE_HEADER)
        self.assertEqual(lines[8], "other: 1")
        self.assertEqual((stats.vault_blocks, stats.vault_payload_lines), (1, 5))
        payload = fixture_payload(out, 3)
        self.assertEqual(len(payload), 5)
        self.assertIsNone(vaultfixture.check_envelope(vaultfixture.FIXTURE_HEADER, payload))
        self.assertNotIn(OTHER_LINES[0][:20], out)

    def test_vault_in_sequence_item_and_nested_mapping_gets_the_1_1_header(self):
        src = dedent(f"""
            users:
              - name: a
                pw: !vault |-
                  $ANSIBLE_VAULT;1.2;AES256;prod
                  {REAL_HEX}
                shell: /bin/bash
        """)
        out = redact(src)
        lines = out.split("\n")
        self.assertEqual(lines[2], "    pw: !vault |-")
        self.assertEqual(lines[3], "      " + vaultfixture.FIXTURE_HEADER)
        self.assertEqual(lines[5], "    shell: /bin/bash")
        self.assertIsNone(vaultfixture.check_envelope(vaultfixture.FIXTURE_HEADER, fixture_payload(out, 4)))

    def test_whole_file_vault(self):
        src = vaultfixture.FIXTURE_HEADER.replace("1.1", "1.2") + ";new\n" + "\n".join(OTHER_LINES) + "\n"
        out = redact(src)
        lines = out.split("\n")
        self.assertEqual(lines[0], vaultfixture.FIXTURE_HEADER)
        self.assertEqual(len(lines), len(src.split("\n")))
        self.assertIsNone(vaultfixture.check_envelope(lines[0], lines[1:-1]))

    def test_crlf_is_kept_and_trailing_blanks_are_dropped(self):
        src = "k: !vault |\r\n  $ANSIBLE_VAULT;1.1;AES256\r\n" + "".join(f"  {x}  \r\n" for x in OTHER_LINES)
        out = redact(src)
        bodies, terms = sanitise.split_lines(out)
        self.assertEqual(terms[:-1], ["\r\n"] * 7)
        self.assertEqual(bodies[1], "  " + vaultfixture.FIXTURE_HEADER)
        self.assertTrue(all(not b.endswith(" ") for b in bodies))
        self.assertIsNone(vaultfixture.check_envelope(vaultfixture.FIXTURE_HEADER, [b.strip() for b in bodies[2:7]]))

    def test_marker_outside_header_line_is_refused(self):
        with self.assertRaises(sanitise.SanitiseError):
            redact('k: !vault "$ANSIBLE_VAULT;1.1;AES256\\n6162"\n')

    def test_vault_tag_without_header_is_refused(self):
        with self.assertRaises(sanitise.SanitiseError):
            redact(f"k: !vault |\n  {REAL_HEX}\n")

    def test_header_without_payload_is_refused(self):
        with self.assertRaises(sanitise.SanitiseError):
            redact("k: !vault |\n  $ANSIBLE_VAULT;1.1;AES256\nother: 1\n")

    def test_payload_continuing_after_a_blank_line_is_refused(self):
        with self.assertRaises(sanitise.SanitiseError):
            redact(f"k: !vault |\n  $ANSIBLE_VAULT;1.1;AES256\n  {REAL_HEX}\n\n  {REAL_HEX}\n")

    def test_idempotent(self):
        src = "k: !vault |\n  $ANSIBLE_VAULT;1.1;AES256\n" + "".join(f"  {x}\n" for x in OTHER_LINES)
        once = sanitise.redact_text(src, salt_key="f.yml")[0]
        self.assertEqual(sanitise.redact_text(once, salt_key="f.yml")[0], once)


class VaultKeyTest(unittest.TestCase):
    def test_plain_and_quoted_values(self):
        src = dedent("""
            vault_a: hunter2
            vault_b: "s3cr3t" # old one
            vault_c: 'x''y'
            "vault_d": 1234
            - vault_e: plain
        """)
        self.assertEqual(redact(src), dedent("""
            vault_a: REDACTED
            vault_b: "REDACTED"
            vault_c: 'REDACTED'
            "vault_d": REDACTED
            - vault_e: REDACTED
        """))

    def test_block_scalar_lines_become_redacted_at_their_indentation(self):
        src = dedent("""
            vault_key: |
              -----BEGIN KEY-----
                abc
              -----END KEY-----
            next: 1
        """)
        self.assertEqual(redact(src), dedent("""
            vault_key: |
              REDACTED
                REDACTED
              REDACTED
            next: 1
        """))

    def test_multiline_plain_and_quoted_continuations_become_empty(self):
        src = dedent("""
            vault_a: first
              second
            vault_b: "one
              two"
            keep: 1
        """)
        out = redact(src)
        self.assertEqual(out, 'vault_a: REDACTED\n\nvault_b: "REDACTED"\n\nkeep: 1\n')
        self.assertEqual(sanitise.count_lines(out), sanitise.count_lines(src))

    def test_null_empty_template_alias_and_nested_are_kept(self):
        src = dedent("""
            vault_a:
            vault_b: ~
            vault_c: ""
            vault_d: "{{ vault_other }}"
            vault_e: *anchor
            vault_f:
              type: str
              description: documented option
        """)
        self.assertEqual(redact(src), src)

    def test_flow_collection_is_redacted(self):
        self.assertEqual(redact("vault_list: [a, b]\n"), "vault_list: REDACTED\n")

    def test_json_trailer_is_kept(self):
        self.assertEqual(redact('  "vault_x": "abc",\n'), '  "vault_x": "REDACTED",\n')

    def test_stats_count_vault_keys(self):
        _, stats = sanitise.redact_text("vault_a: x1\nvault_b: y2\n")
        self.assertEqual(stats.vault_keys_redacted, 2)
        self.assertEqual(stats.secret_keys_redacted, 0)


class SecretKeyTest(unittest.TestCase):
    def test_random_literals_are_redacted(self):
        src = dedent("""
            app_jwt_secret: '0380178ad5f1c0b2'
            broadcast_password: '$$2y$$13$$abcdefghijk'
            mailer_api_key: Molecule-Key-1
            aws_access_key_id: "AKIAABCDEFGHIJKLMNOP"
            privateKey: "-----BEGIN"
        """)
        out, stats = sanitise.redact_text(src)
        self.assertEqual(out, dedent("""
            app_jwt_secret: 'REDACTED'
            broadcast_password: 'REDACTED'
            mailer_api_key: REDACTED
            aws_access_key_id: "REDACTED"
            privateKey: "REDACTED"
        """))
        self.assertEqual(stats.secret_keys_redacted, 5)

    def test_non_secret_values_are_kept(self):
        src = dedent("""
            x_password: "{{ vault_x_password }}"
            POSTGRES_PASSWORD: "${SERVICE_PASSWORD_POSTGRES}"
            privateKey: "${readFile:/var/lib/key}"
            update_password: on_create
            password_policy: "length(12) and digits(1)"
            token_ttl_seconds: 2592000
            KEYCLOAK_TOKEN_TIMEOUT: '5.0'
            ssh_private_key_file: /tmp/id_ed25519
            secret_file_src: files/app.env
            api_token_url: https://api.example.test/token
            sshd_password_authentication: false
            secret:
            x_secret: ""
            client_secret: REDACTED
        """)
        self.assertEqual(redact(src), src)

    def test_url_with_credentials_is_redacted(self):
        self.assertEqual(redact("db_password: https://u:p@host/x\n"), "db_password: REDACTED\n")

    def test_block_scalar_under_secret_key(self):
        src = "tls_private_key: |\n  -----BEGIN PRIVATE KEY-----\n  MIIE\n"
        self.assertEqual(redact(src), "tls_private_key: |\n  REDACTED\n  REDACTED\n")
        templated = "tls_private_key: |\n  {{ lookup('file', 'k') }}\n"
        self.assertEqual(redact(templated), templated)

    def test_unrelated_keys_are_untouched(self):
        src = "secretary: x9Y8z7\nkeycloak_client_id: abcDEF123\n"
        self.assertEqual(redact(src), src)


class IpTest(unittest.TestCase):
    def mapper(self, *texts):
        found = set()
        for t in texts:
            found |= sanitise.collect_ipv4(t)
        return sanitise.IpMapper(found)

    def rewrite(self, mapper, text):
        return sanitise.map_ips(text, mapper, sanitise.Stats())

    def test_stable_mapping_keeps_cidr_and_shares(self):
        a = "- 10.1.2.3/29 # office\nhost: 10.1.2.3\n"
        b = "ansible_host: 10.9.9.9\nfloating: 10.1.2.3\n"
        m = self.mapper(a, b)
        ra, rb = self.rewrite(m, a), self.rewrite(m, b)
        self.assertEqual(ra, "- 192.0.2.1/29 # office\nhost: 192.0.2.1\n")
        self.assertEqual(rb, "ansible_host: 192.0.2.2\nfloating: 192.0.2.1\n")

    def test_numeric_order_and_distinct_targets(self):
        text = "\n".join(f"- 172.16.{i}.{j}" for i in range(3) for j in range(1, 200))
        m = self.mapper(text)
        out = self.rewrite(m, text)
        targets = [line[2:] for line in out.split("\n")]
        self.assertEqual(len(set(targets)), len(targets))
        self.assertTrue(all(rules.is_test_net(ipaddress.IPv4Address(t)) for t in targets))
        self.assertEqual(targets[0], "192.0.2.1")
        self.assertEqual(targets[254], "198.51.100.1")

    def test_special_addresses_are_kept(self):
        text = "a: 127.0.0.1:514\nb: 0.0.0.0/0\nc: 255.255.255.0\nd: 255.255.255.255\ne: 127.0.0.11\n"
        m = self.mapper(text)
        self.assertEqual(len(m), 0)
        self.assertEqual(self.rewrite(m, text), text)

    def test_non_addresses_are_untouched(self):
        text = "v: 1.2.3.4.5\nw: 300.1.1.1\nx: 10.0.0\noid: 1.3.6.1.4.1.2021\n"
        m = self.mapper(text)
        self.assertEqual(self.rewrite(m, text), text)

    def test_existing_test_net_addresses_keep_their_value_and_leave_the_pool(self):
        text = "a: 192.0.2.1\nb: 10.0.0.1\n"
        m = self.mapper(text)
        self.assertEqual(self.rewrite(m, text), "a: 192.0.2.1\nb: 192.0.2.2\n")

    def test_later_addresses_never_shift_earlier_replacements(self):
        base = "a: 10.0.0.5\nb: 10.0.0.9\n"
        later = "c: 10.0.0.1\nd: 10.0.0.9\ne: 192.0.2.200\n"
        alone = sanitise.IpMapper(sanitise.collect_ipv4(base))
        both = sanitise.IpMapper(sanitise.collect_ipv4(base), sanitise.collect_ipv4(later))
        self.assertEqual(self.rewrite(alone, base), self.rewrite(both, base))
        self.assertEqual(self.rewrite(both, base), "a: 192.0.2.1\nb: 192.0.2.2\n")
        # 10.0.0.1 sorts first but is mapped after the base set; 10.0.0.9 keeps its base replacement.
        self.assertEqual(self.rewrite(both, later), "c: 192.0.2.3\nd: 192.0.2.2\ne: 192.0.2.200\n")
        self.assertEqual(len(both), 3)

    def test_later_test_net_address_equal_to_an_earlier_replacement_is_refused(self):
        with self.assertRaises(sanitise.SanitiseError):
            sanitise.IpMapper(sanitise.collect_ipv4("a: 10.0.0.5\n"), sanitise.collect_ipv4("b: 192.0.2.1\n"))

    def test_exhausted_pool_raises(self):
        many = {ipaddress.IPv4Address(0x0A000000 + i) for i in range(1, 800)}
        with self.assertRaises(sanitise.SanitiseError):
            sanitise.IpMapper(many)

    def test_allowed_predicate(self):
        allowed = ["192.0.2.7", "198.51.100.1", "203.0.113.254", "127.0.0.1", "0.0.0.0", "255.255.0.0"]
        denied = ["10.0.0.1", "8.8.8.8", "192.168.1.1", "255.255.0.255", "192.0.3.1"]
        for s in allowed:
            self.assertTrue(rules.is_allowed_ipv4(ipaddress.IPv4Address(s)), s)
        for s in denied:
            self.assertFalse(rules.is_allowed_ipv4(ipaddress.IPv4Address(s)), s)


class ForbiddenTest(unittest.TestCase):
    def test_forbidden_paths(self):
        for rel in (
            "repos/falcon/ansible/.vault-pass",
            "repos/falcon/ansible/.initial-root-pass",
            "repos/falcon/ansible/.env.local",
            "repos/falcon/ansible/.env",
            "golden/roles/keycloak/files/ssl/ca.pem",
            "golden/roles/x/molecule/default/files/ssh/users/a.pub",
            "repos/platform/ansible/files/ssh/hosts/bitbucket.org",
            "a/b/server.key",
            "a/b/server.CRT",
            "a/b/root.password",
            "repos/falcon/.git",
            "repos/falcon/.git/config",
        ):
            self.assertIsNotNone(rules.forbidden_reason(rel), rel)

    def test_allowed_paths(self):
        for rel in (
            "golden/roles/docker/files/apt.asc",
            "golden/roles/system/files/bash/bashrc_default",
            "golden/roles/jenkins-controller/files/ssh_config",
            "golden/roles/x/templates/ssl.conf.j2",
            "golden/roles/x/.gitignore",
            "golden/roles/x/files/ssl",
            "golden/roles/x/tasks/keys.yml",
        ):
            self.assertIsNone(rules.forbidden_reason(rel), rel)

    def test_junk(self):
        self.assertIsNotNone(rules.junk_reason("a/__pycache__/x.cpython-314.pyc"))
        self.assertIsNotNone(rules.junk_reason("a/.DS_Store"))
        self.assertIsNone(rules.junk_reason("a/tasks/main.yml"))


class VerifyTest(unittest.TestCase):
    def test_clean_text(self):
        payload = "".join(f"  {x}\n" for x in vaultfixture.payload_lines("f.yml:2", [80, 80, 80, 80, 4]))
        text = f"k: !vault |\n  $ANSIBLE_VAULT;1.1;AES256\n{payload}vault_x: REDACTED\nip: 192.0.2.1\n"
        self.assertEqual(verify.verify_text("f.yml", text), [])

    def test_each_violation_is_reported_with_its_line_and_without_the_value(self):
        text = dedent(f"""
            k: !vault |
              $ANSIBLE_VAULT;1.1;AES256
              {REAL_HEX}
            vault_x: hunter2
            app_secret: 'a1b2c3d4e5'
            ip: 10.0.0.1
            note: "$ANSIBLE_VAULT;1.1;AES256"
        """)
        problems = verify.verify_text("f.yml", text)
        self.assertEqual(
            [(p.line, p.reason) for p in problems],
            [
                (2, "vault payload is not the synthetic fixture envelope"),
                (7, "'$ANSIBLE_VAULT' outside a vault header line"),
                (4, "plaintext vault_* value"),
                (5, "plaintext secret value"),
                (6, "IPv4 address outside TEST-NET"),
            ],
        )
        self.assertFalse(any("hunter2" in str(p) or REAL_HEX[:8] in str(p) for p in problems))

    def test_tree_checks_files_and_git_links(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "repos/falcon/ansible").mkdir(parents=True)
            (root / "repos/falcon/.git").write_text(rules.submodule_git_link("falcon"))
            (root / "repos/nolink").mkdir(parents=True)
            (root / "repos/heron").mkdir(parents=True)
            (root / "repos/heron/.git").write_text(rules.submodule_git_link("falcon"))
            wt = root / rules.WORKTREE_DIR
            wt.mkdir(parents=True)
            (wt / ".git").write_text(rules.WORKTREE_GIT_LINK)
            (root / "repos/falcon/ansible/.vault-pass").write_text("x")
            (root / "sub/.git").mkdir(parents=True)
            (root / "bin.dat").write_bytes(b"\xff\xfe\x00")
            (root / "10.1.2.3").mkdir()
            (root / "10.1.2.3/vars.yml").write_text("a: 1\n")
            (root / ".DS_Store").write_bytes(b"\x00\xff")
            reasons = {(p.path, p.reason) for p in verify.verify_tree(root)}
            self.assertEqual(
                reasons,
                {
                    ("repos/heron/.git", ".git link does not match its location"),
                    ("repos/falcon/ansible/.vault-pass", "forbidden file (vault or root password file)"),
                    ("sub/.git/", ".git directory"),
                    ("bin.dat", "binary file (cannot be verified)"),
                    ("10.1.2.3/vars.yml", "IPv4 address outside TEST-NET in the path"),
                    ("repos/nolink/.git", "synthetic .git link missing (run sync.py --links-only)"),
                },
            )


class SubsetTest(unittest.TestCase):
    def test_brace_expansion_nested_and_deduplicated(self):
        self.assertEqual(
            sync.expand_braces("r/{a,e/{p,q}/**,a}"),
            ["r/a", "r/e/p/**", "r/e/q/**"],
        )

    def test_match_pattern(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            for rel in ("x/playbook-a.yml", "x/playbook-b.yml", "x/other.yml", "x/d/y/Dockerfile"):
                (root / rel).parent.mkdir(parents=True, exist_ok=True)
                (root / rel).write_text("")
            names = lambda ps: [p.relative_to(root).as_posix() for p in ps]  # noqa: E731
            self.assertEqual(names(sync.match_pattern(root, "x/playbook-*.yml")), ["x/playbook-a.yml", "x/playbook-b.yml"])
            self.assertEqual(names(sync.match_pattern(root, "x/d/**")), ["x/d"])
            self.assertEqual(names(sync.match_pattern(root, "x/*/y/Dockerfile")), ["x/d/y/Dockerfile"])
            self.assertEqual(sync.match_pattern(root, "x/missing.yml"), [])
            with self.assertRaises(ValueError):
                sync.match_pattern(root, "x/**/Dockerfile")

    def test_subset_entries_are_valid_patterns(self):
        with tempfile.TemporaryDirectory() as tmp:
            for entry in sync.SUBSET + sync.SUBSET_ADDITIONS:
                for pattern in sync.expand_braces(entry):
                    self.assertNotIn("{", pattern)
                    sync.match_pattern(Path(tmp), pattern)


class SyncEndToEndTest(unittest.TestCase):
    """Runs ``sync.run`` on a small fake infra repo."""

    FILES = {
        "golden/roles/haproxy/defaults/main.yml": "haproxy_bind_ip: 10.20.30.40\n",
        "golden/roles/haproxy/files/ssl/cert.pem": "PEM\n",
        "golden/roles/haproxy/__pycache__/x.pyc": "junk\n",
        "golden/playbooks/playbook-setup.yml": "- hosts: all\n  roles: [haproxy]\n",
        "repos/falcon/.git": "gitdir: ../../.git/modules/repos/falcon\n",
        "repos/falcon/ansible/ansible.cfg": "[defaults]\n",
        "repos/falcon/ansible/.vault-pass": "secret\n",
        "repos/falcon/ansible/environments/prod/.vault-pass": "secret\n",
        "repos/falcon/ansible/environments/prod/.env.local": "X=1\n",
        "repos/falcon/ansible/playbook-a.yml": "- hosts: all\n",
        "repos/falcon/ansible/group_vars/all/vault.yml": (
            "vault_db: !vault |\n  $ANSIBLE_VAULT;1.1;AES256\n" + "".join(f"  {x}\n" for x in OTHER_LINES) + "vault_plain: hunter2\n"
        ),
        "repos/falcon/ansible/environments/prod/hosts.yml": "all:\n  hosts:\n    prod1:\n      ansible_host: 10.20.30.40\n",
        "repos/falcon/ansible/environments/prod/host_vars/10.9.8.7/vars.yml": "x: 1\n",
        "repos/falcon/ansible/.git/config": "[core]\n",
    }

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        base = Path(self._tmp.name)
        self.source = base / "infra-src"
        self.dest = base / "plugin" / "testData" / "infra"
        self.manifest = base / "manifest.tsv"
        for rel, text in self.FILES.items():
            p = self.source / rel
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_text(text)

    def tearDown(self):
        self._tmp.cleanup()

    def run_sync(self, *extra):
        out = io.StringIO()
        code = sync.run(["--source", str(self.source), "--dest", str(self.dest), "--manifest", str(self.manifest), *extra], out)
        return code, out.getvalue()

    def test_sync_copies_sanitises_and_reports(self):
        code, out = self.run_sync()
        self.assertEqual(code, 0, out)
        d = self.dest
        self.assertIn("haproxy_bind_ip: 192.0.2.2", (d / "golden/roles/haproxy/defaults/main.yml").read_text())
        self.assertIn("ansible_host: 192.0.2.2", (d / "repos/falcon/ansible/environments/prod/hosts.yml").read_text())
        self.assertTrue((d / "repos/falcon/ansible/environments/prod/host_vars/192.0.2.1/vars.yml").is_file())
        vault = (d / "repos/falcon/ansible/group_vars/all/vault.yml").read_text()
        lines = vault.split("\n")
        self.assertEqual(lines[:2], ["vault_db: !vault |", "  $ANSIBLE_VAULT;1.1;AES256"])
        self.assertEqual(lines[7:], ["vault_plain: REDACTED", ""])
        payload = fixture_payload(vault, 2)
        self.assertEqual([len(x) for x in payload], [80, 80, 80, 80, 4])
        self.assertIsNone(vaultfixture.check_envelope(vaultfixture.FIXTURE_HEADER, payload))
        self.assertEqual(payload, vaultfixture.payload_lines("repos/falcon/ansible/group_vars/all/vault.yml:2", [80, 80, 80, 80, 4]))
        for gone in (".vault-pass", "environments/prod/.vault-pass", "environments/prod/.env.local", ".git/config"):
            self.assertFalse((d / "repos/falcon/ansible" / gone).exists(), gone)
        self.assertFalse((d / "golden/roles/haproxy/files/ssl").exists())
        self.assertFalse((d / "golden/roles/haproxy/__pycache__").exists())
        self.assertEqual((d / "repos/falcon/.git").read_text(), "gitdir: ../../.git/modules/repos/falcon\n")
        self.assertEqual((d / rules.WORKTREE_DIR / ".git").read_text(), rules.WORKTREE_GIT_LINK)
        self.assertTrue((d / rules.WORKTREE_DIR / "golden/roles/haproxy/tasks/main.yml").is_file())
        self.assertEqual(verify.verify_tree(d), [])
        self.assertIn("missing subset paths:", out)
        self.assertIn("verify:               OK", out)
        self.assertIn("all equal to source", out)
        self.assertNotIn("hunter2", out)
        self.assertNotIn("10.20.30.40", out)
        rows = [r.split("\t") for r in self.manifest.read_text().splitlines() if not r.startswith("#")]
        by_path = {r[0]: r for r in rows}
        self.assertEqual(by_path["repos/falcon/ansible/group_vars/all/vault.yml"][1:3], ["8", "8"])
        self.assertEqual(by_path["repos/falcon/.git"][4], "synthetic")
        self.assertNotIn("10.20.30.40", self.manifest.read_text())

    def test_additions_keep_the_output_of_the_base_subset(self):
        self.assertEqual(self.run_sync()[0], 0)
        before = {p.relative_to(self.dest).as_posix(): p.read_bytes() for p in self.dest.rglob("*") if p.is_file()}
        added = self.source / "repos/wren/ansible/environments/prod/hosts.yml"
        added.parent.mkdir(parents=True)
        added.write_text("all:\n  hosts:\n    w1:\n      ansible_host: 10.0.0.1\n    w2:\n      ansible_host: 10.20.30.40\n")
        self.assertEqual(self.run_sync()[0], 0)
        after = {p.relative_to(self.dest).as_posix(): p.read_bytes() for p in self.dest.rglob("*") if p.is_file()}
        for rel, data in before.items():
            self.assertEqual(after.get(rel), data, rel)
        wren = (self.dest / "repos/wren/ansible/environments/prod/hosts.yml").read_text()
        self.assertIn("ansible_host: 192.0.2.3", wren, "the lower new address is mapped after the base set")
        self.assertIn("ansible_host: 192.0.2.2", wren, "a shared address keeps its base replacement")
        self.assertEqual(sorted(set(after) - set(before)), ["repos/wren/.git", "repos/wren/ansible/environments/prod/hosts.yml"])

    def test_missing_subset_paths_are_reported(self):
        code, out = self.run_sync()
        self.assertEqual(code, 0, out)
        self.assertIn("- repos/heron/ansible/ansible.cfg", out)

    def test_resync_replaces_previous_output_and_never_writes_to_source(self):
        before = sorted(str(p) for p in self.source.rglob("*"))
        self.assertEqual(self.run_sync()[0], 0)
        (self.dest / "stale.yml").write_text("a: 1\n")
        self.assertEqual(self.run_sync()[0], 0)
        self.assertFalse((self.dest / "stale.yml").exists())
        self.assertEqual(sorted(str(p) for p in self.source.rglob("*")), before)
        leftovers = [p.name for p in self.dest.parent.iterdir() if p.name != "infra"]
        self.assertEqual(leftovers, [])

    def test_refuses_foreign_non_empty_destination(self):
        self.dest.mkdir(parents=True)
        (self.dest / "keep.txt").write_text("mine\n")
        code, out = self.run_sync()
        self.assertEqual(code, 2)
        self.assertIn("not a previous sync", out)
        self.assertTrue((self.dest / "keep.txt").exists())

    def test_refuses_destination_inside_source(self):
        out = io.StringIO()
        code = sync.run(["--source", str(self.source), "--dest", str(self.source / "x")], out)
        self.assertEqual(code, 2)
        self.assertFalse((self.source / "x").exists())

    def test_unsanitisable_file_leaves_destination_unchanged(self):
        self.assertEqual(self.run_sync()[0], 0)
        marker = self.dest / "golden/roles/haproxy/defaults/main.yml"
        before = marker.read_text()
        bad = self.source / "golden/roles/haproxy/tasks/main.yml"
        bad.parent.mkdir(parents=True)
        bad.write_text('- debug: msg="$ANSIBLE_VAULT;1.1;AES256"\n')
        code, out = self.run_sync()
        self.assertEqual(code, 2, out)
        self.assertEqual(marker.read_text(), before)
        self.assertFalse((self.dest / "golden/roles/haproxy/tasks").exists())

    def test_links_only_restores_git_files(self):
        self.assertEqual(self.run_sync()[0], 0)
        (self.dest / "repos/falcon/.git").unlink()
        (self.dest / rules.WORKTREE_DIR / ".git").unlink()
        out = io.StringIO()
        code = sync.run(["--dest", str(self.dest), "--links-only"], out)
        self.assertEqual(code, 0)
        self.assertEqual((self.dest / "repos/falcon/.git").read_text(), rules.submodule_git_link("falcon"))
        self.assertEqual((self.dest / rules.WORKTREE_DIR / ".git").read_text(), rules.WORKTREE_GIT_LINK)

    def test_envelopes_only_equals_a_full_sync_and_reads_no_source(self):
        self.assertEqual(self.run_sync()[0], 0)
        vault = self.dest / "repos/falcon/ansible/group_vars/all/vault.yml"
        synced = vault.read_text()
        manifest = self.manifest.read_text()
        # An older fixture: the same structure, the payload digits of the previous dummy rule.
        lines = synced.split("\n")
        lines[2:7] = ["  " + ("64756d6d79" * 8)[: len(x) - 2] for x in lines[2:7]]
        vault.write_text("\n".join(lines))
        out = io.StringIO()
        code = sync.run(["--source", str(self.source / "missing"), "--dest", str(self.dest), "--envelopes-only"], out)
        self.assertEqual(code, 0, out.getvalue())
        self.assertIn("1 files to rewrite", out.getvalue())
        self.assertEqual(vault.read_text(), synced)
        self.assertEqual(self.manifest.read_text(), manifest)
        self.assertEqual(verify.verify_tree(self.dest), [])
        again = io.StringIO()
        self.assertEqual(sync.run(["--dest", str(self.dest), "--envelopes-only"], again), 0)
        self.assertIn("0 files to rewrite", again.getvalue())

    def test_envelopes_only_refuses_a_directory_that_is_no_previous_sync(self):
        out = io.StringIO()
        self.assertEqual(sync.run(["--dest", str(self.source), "--envelopes-only"], out), 2)

    def test_dry_run_writes_nothing(self):
        code, out = self.run_sync("--dry-run", "--list")
        self.assertEqual(code, 0)
        self.assertFalse(self.dest.exists())
        self.assertIn("  repos/falcon/ansible/ansible.cfg", out)
        self.assertIn("skip repos/falcon/ansible/environments/prod/.vault-pass (vault or root password file)", out)
        self.assertIn("skip repos/falcon/ansible/environments/prod/.env.local (.env file)", out)
        self.assertIn("skip golden/roles/haproxy/files/ssl/cert.pem (files/ssl subtree)", out)


if __name__ == "__main__":
    unittest.main()
