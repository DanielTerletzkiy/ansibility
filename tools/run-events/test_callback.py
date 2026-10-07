"""Unit tests of the defensive helpers of the Ansibility events callback, with a stub ansible package (no Ansible needed).

    python3 -m unittest discover -s tools/run-events -p 'test_*.py'
"""
import importlib.util
import io
import json
import os
import sys
import types
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
CALLBACK = os.path.join(HERE, "..", "..", "plugin", "src", "main", "resources", "ansible", "callback", "ansibility_events.py")


def load():
    """The callback module, imported against stub `ansible` modules."""
    for name in ("ansible", "ansible.plugins", "ansible.plugins.callback", "ansible.release"):
        sys.modules.setdefault(name, types.ModuleType(name))
    sys.modules["ansible.plugins.callback"].CallbackBase = type("CallbackBase", (), {"__init__": lambda self, *a, **k: None})
    sys.modules["ansible.release"].__version__ = "0.0-test"
    spec = importlib.util.spec_from_file_location("ansibility_events_under_test", CALLBACK)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


events = load()


class Obj:
    def __init__(self, **kwargs):
        self.__dict__.update(kwargs)


class CleanTest(unittest.TestCase):
    def test_values_become_json(self):
        self.assertEqual([1, 2], sorted(events._default({1, 2})))
        self.assertEqual(["a"], events._default(("a",)))
        self.assertEqual("ä", events._default("ä".encode()))
        self.assertEqual("Obj", events._default(type("Obj", (), {"__str__": lambda self: "Obj"})()))

    def test_secret_looking_keys_are_masked(self):
        cleaned = events._clean({"user": "deploy", "password": "hunter2", "api_token": "abc", "passed": True, "_ansible_no_log": False,
                                 "nested": {"private_key": "k", "become_pass": "p", "tokens": ["t"]}})
        self.assertEqual({"user": "deploy", "password": "********", "api_token": "********", "passed": True,
                          "nested": {"private_key": "********", "become_pass": "********", "tokens": ["t"]}}, cleaned)

    def test_sizes_are_capped(self):
        self.assertTrue(events._clean("x" * (events.MAX_STRING + 5)).endswith("[5 more characters]"))
        self.assertEqual(events.MAX_LIST + 1, len(events._clean(list(range(events.MAX_LIST + 3)))))
        deep = {}
        node = deep
        for _ in range(40):
            node["a"] = {}
            node = node["a"]
        text = json.dumps(events._clean(deep), ensure_ascii=False)
        self.assertIn("…", text)
        self.assertEqual("Obj", events._clean(type("Obj", (), {"__str__": lambda self: "Obj"})()))

    def test_positions_come_from_get_path_or_the_source_mapping(self):
        self.assertEqual("site.yml:3", events._position(Obj(get_path=lambda: "site.yml:3")))
        self.assertEqual("site.yml:7", events._position(Obj(get_path=lambda: None, _ds=Obj(ansible_pos=("site.yml", 7, 1)))))
        self.assertEqual("x.yml:2", events._position(Obj(get_path=lambda: (_ for _ in ()).throw(ValueError()), _ds=Obj(ansible_pos=("x.yml", 2, 1)))))
        self.assertIsNone(events._position(Obj()))


class EmitTest(unittest.TestCase):
    def callback(self, token):
        os.environ["ANSIBILITY_EVENTS_TOKEN"] = token
        try:
            callback = events.CallbackModule()
        finally:
            del os.environ["ANSIBILITY_EVENTS_TOKEN"]
        callback._stream = io.StringIO()
        return callback

    def test_nothing_without_a_token(self):
        callback = self.callback("")
        callback._emit("start")
        self.assertEqual("", callback._stream.getvalue())

    def test_frames_carry_the_token_and_too_large_events_lose_their_result(self):
        callback = self.callback("tok")
        callback._emit("result", result={"stdout": "x" * (events.MAX_EVENT + 10)}, diff=[{"before": "a"}])
        line = callback._stream.getvalue()
        self.assertTrue(line.startswith("\x1etok "))
        event = json.loads(line[len("\x1etok "):])
        self.assertEqual("result", event["e"])
        self.assertTrue(event["truncated"])
        self.assertNotIn("result", event)
        self.assertNotIn("diff", event)


if __name__ == "__main__":
    unittest.main()
