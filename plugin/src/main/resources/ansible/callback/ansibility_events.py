# Ansibility run events: reports a playbook run to the Ansibility IDE plugin as JSON events on stderr, one per line,
# each framed as "\x1e<token> <json>" with the run's token (ANSIBILITY_EVENTS_TOKEN), so that the IDE can show the run
# as plays, tasks and hosts. It is a notification callback: the stdout callback and every other callback keep working,
# and without the token it does nothing. Results are censored (no_log, keys that look like secrets) and capped in size
# before they leave Ansible.
from __future__ import annotations

DOCUMENTATION = """
    name: ansibility_events
    type: notification
    short_description: Reports a playbook run to the Ansibility IDE plugin
    description:
      - Writes one JSON event per line to stderr, framed with the record separator and the run token.
      - Does nothing unless ANSIBILITY_EVENTS_TOKEN is set; Ansibility sets it for the runs it starts.
"""

import json
import os
import re
import sys
import time

from ansible.plugins.callback import CallbackBase

try:
    from ansible.release import __version__ as ANSIBLE_VERSION
except ImportError:  # pragma: no cover - every supported ansible-core has it
    ANSIBLE_VERSION = None

SCHEMA = 1
MAX_STRING = 64 * 1024
MAX_LIST = 1000
MAX_EVENT = 1024 * 1024
MASK = "********"
SENSITIVE = re.compile(r"(^|[_-])(password|passwd|passphrase|pass|secret|token|api[_-]?key|private[_-]?key|credentials?)($|[_-])")
NO_LOG = "the output has been hidden due to the fact that 'no_log: true' was specified for this result"
LINES = ("stdout_lines", "stderr_lines")


def _default(value):
    if isinstance(value, (set, frozenset, tuple)):
        return list(value)
    if isinstance(value, bytes):
        return value.decode("utf-8", "replace")
    return str(value)


def _short(value, limit=MAX_STRING):
    if isinstance(value, str) and len(value) > limit:
        return value[:limit] + "… [%d more characters]" % (len(value) - limit)
    return value


def _clean(value, depth=0):
    """A JSON-friendly copy: internal keys dropped, secret-looking keys masked, long strings and lists capped."""
    if depth > 32:
        return "…"
    if isinstance(value, dict):
        out = {}
        for key, item in value.items():
            key = str(key)
            if key.startswith("_ansible_"):
                continue
            if SENSITIVE.search(key.lower()) and not isinstance(item, (dict, list, bool)) and item not in (None, ""):
                out[key] = MASK
            else:
                out[key] = _clean(item, depth + 1)
        return out
    if isinstance(value, (list, tuple, set, frozenset)):
        items = list(value)
        cleaned = [_clean(item, depth + 1) for item in items[:MAX_LIST]]
        if len(items) > MAX_LIST:
            cleaned.append("… [%d more items]" % (len(items) - MAX_LIST))
        return cleaned
    if isinstance(value, str):
        return _short(value)
    if value is None or isinstance(value, (bool, int, float)):
        return value
    return _short(_default(value))


def _position(obj):
    """"path:line" of a play's or task's source, when Ansible kept it."""
    get_path = getattr(obj, "get_path", None)
    if get_path is not None:
        try:
            path = get_path()
            if path:
                return path
        except Exception:
            pass
    pos = getattr(getattr(obj, "_ds", None), "ansible_pos", None)
    if pos and pos[0]:
        return "%s:%s" % (pos[0], pos[1])
    return None


class CallbackModule(CallbackBase):
    CALLBACK_VERSION = 2.0
    CALLBACK_TYPE = "notification"
    CALLBACK_NAME = "ansibility_events"
    CALLBACK_NEEDS_ENABLED = False

    def __init__(self, *args, **kwargs):
        super(CallbackModule, self).__init__(*args, **kwargs)
        self._token = os.environ.get("ANSIBILITY_EVENTS_TOKEN", "")
        self._stream = sys.stderr
        self._play = None
        self._started = {}

    # ------------------------------------------------------------------ output

    def _emit(self, event, **data):
        if not self._token:
            return
        data["v"] = SCHEMA
        data["e"] = event
        data["t"] = round(time.time(), 3)
        try:
            text = json.dumps(data, default=_default, separators=(",", ":"))
            if len(text) > MAX_EVENT:
                data.pop("result", None)
                data.pop("diff", None)
                data["truncated"] = True
                text = json.dumps(data, default=_default, separators=(",", ":"))
        except Exception as exc:  # pragma: no cover - _default makes every value serialisable
            text = json.dumps({"v": SCHEMA, "e": "error", "t": data["t"], "event": event, "message": str(exc)})
        try:
            self._stream.write("\x1e" + self._token + " " + text + "\n")
            self._stream.flush()
        except Exception:  # pragma: no cover - a closed stderr ends the reporting, not the run
            self._token = ""

    # ------------------------------------------------------------------ playbook, plays, tasks

    def v2_playbook_on_start(self, playbook):
        args = {}
        try:
            from ansible import context

            cli = context.CLIARGS
            args = {
                "check": bool(cli.get("check")),
                "diff": bool(cli.get("diff")),
                "limit": cli.get("subset"),
                "tags": list(cli.get("tags") or []),
                "skip_tags": list(cli.get("skip_tags") or []),
                "forks": cli.get("forks"),
                "verbosity": cli.get("verbosity"),
            }
        except Exception:  # pragma: no cover - CLIARGS exists in every supported ansible-core
            pass
        self._emit("start", playbook=getattr(playbook, "_file_name", None), ansible=ANSIBLE_VERSION, **args)

    def v2_playbook_on_play_start(self, play):
        self._play = getattr(play, "_uuid", None)
        hosts = play.hosts if isinstance(play.hosts, list) else [play.hosts]
        self._emit(
            "play",
            id=self._play,
            name=play.get_name(),
            hosts=[str(h) for h in hosts if h is not None],
            path=_position(play),
            serial=_clean(getattr(play, "serial", None)),
            strategy=getattr(play, "strategy", None),
        )

    def _task(self, task, handler=False, cleanup=False):
        role = getattr(task, "_role", None)
        self._emit(
            "task",
            id=task._uuid,
            play=self._play,
            name=task.get_name(),
            role=role.get_name() if role is not None else None,
            action=task.action,
            path=_position(task),
            handler=handler,
            cleanup=cleanup,
            loop=bool(getattr(task, "loop", None) or getattr(task, "loop_with", None)),
            tags=sorted(str(tag) for tag in (task.tags or [])),
        )

    def v2_playbook_on_task_start(self, task, is_conditional):
        self._task(task)

    def v2_playbook_on_handler_task_start(self, task):
        self._task(task, handler=True)

    def v2_playbook_on_cleanup_task_start(self, task):  # pragma: no cover - only a few strategies run cleanup tasks
        self._task(task, cleanup=True)

    def v2_playbook_on_include(self, included_file):
        self._emit(
            "include",
            file=getattr(included_file, "_filename", None),
            hosts=[h.get_name() for h in getattr(included_file, "_hosts", [])],
            task=getattr(getattr(included_file, "_task", None), "_uuid", None),
        )

    def v2_playbook_on_notify(self, handler, host):
        self._emit("notify", handler=handler.get_name(), host=host.get_name() if hasattr(host, "get_name") else str(host))

    def v2_playbook_on_no_hosts_matched(self):
        self._emit("no_hosts", play=self._play)

    def v2_playbook_on_no_hosts_remaining(self):
        self._emit("no_hosts_remaining", play=self._play)

    # ------------------------------------------------------------------ results

    def v2_runner_on_start(self, host, task):
        self._started[(task._uuid, host.get_name())] = time.time()
        self._emit("host_start", task=task._uuid, host=host.get_name())

    def _result(self, event, status, result):
        raw = result._result
        task = result._task
        host = result._host.get_name()
        data = {"task": task._uuid, "host": host, "status": status, "changed": bool(raw.get("changed"))}
        if event == "result":
            started = self._started.pop((task._uuid, host), None)
            if started is not None:
                data["duration"] = round(time.time() - started, 3)
        else:
            loop_var = raw.get("ansible_loop_var", "item")
            data["item"] = _clean(raw.get("_ansible_item_label", raw.get(loop_var)))
        delegated_vars = raw.get("_ansible_delegated_vars") or {}
        delegated = delegated_vars.get("ansible_delegated_host") or delegated_vars.get("ansible_host")
        if delegated:
            data["delegated_to"] = delegated
        if raw.get("_ansible_no_log"):
            data["result"] = {"censored": raw.get("censored", NO_LOG), "changed": data["changed"]}
            self._emit(event, **data)
            return
        clean = _clean(raw)
        if event == "result" and isinstance(raw.get("results"), list):
            data["items"] = len(raw["results"])
            clean.pop("results", None)
        for key in LINES:
            if isinstance(clean.get(key[: -len("_lines")]), str):
                clean.pop(key, None)
        diff = clean.pop("diff", None)
        if diff:
            data["diff"] = [d for d in (diff if isinstance(diff, list) else [diff]) if isinstance(d, dict)]
        if "msg" in clean:
            data["msg"] = clean["msg"] if isinstance(clean["msg"], str) else json.dumps(clean["msg"], default=_default)
        data["result"] = clean
        self._emit(event, **data)

    def v2_runner_on_ok(self, result):
        self._result("result", "changed" if result._result.get("changed") else "ok", result)

    def v2_runner_on_failed(self, result, ignore_errors=False):
        self._result("result", "ignored" if ignore_errors else "failed", result)

    def v2_runner_on_skipped(self, result):
        self._result("result", "skipped", result)

    def v2_runner_on_unreachable(self, result):
        self._result("result", "unreachable", result)

    def v2_runner_item_on_ok(self, result):
        self._result("item", "changed" if result._result.get("changed") else "ok", result)

    def v2_runner_item_on_failed(self, result):
        self._result("item", "failed", result)

    def v2_runner_item_on_skipped(self, result):
        self._result("item", "skipped", result)

    def v2_runner_retry(self, result):
        raw = result._result
        self._emit(
            "retry",
            task=result._task._uuid,
            host=result._host.get_name(),
            attempt=raw.get("attempts"),
            retries=raw.get("retries"),
            msg=_short(raw.get("msg")) if isinstance(raw.get("msg"), str) else None,
        )

    def v2_runner_on_async_poll(self, result):
        self._emit("async_poll", task=result._task._uuid, host=result._host.get_name(), job=result._result.get("ansible_job_id"))

    # ------------------------------------------------------------------ recap

    def v2_playbook_on_stats(self, stats):
        hosts = {}
        for host in sorted(stats.processed.keys()):
            hosts[host] = stats.summarize(host)
        self._emit("stats", hosts=hosts)
