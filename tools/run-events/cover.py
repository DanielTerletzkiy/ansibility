"""Line coverage of one Python file during a program run, with the standard library only.

    python cover.py <file.py> <out.json> -- <script> [args...]

runs <script> (an `ansible-playbook` entry point) in this process with a tracer that records the lines executed in
<file.py> (matched by file name), and writes {"executed": [...], "executable": [...], "excluded": [...]} to <out.json>.
Lines of a statement marked `# pragma: no cover` (the statement and the block it opens) are excluded.
"""
import ast
import json
import os
import runpy
import sys
import threading


def executable_lines(path):
    """The first line of every statement, except those in `# pragma: no cover` blocks and docstrings."""
    source = open(path, encoding="utf-8").read()
    lines = source.splitlines()
    tree = ast.parse(source)
    excluded = set()
    executable = set()

    def visit(node, skip):
        for child in ast.iter_child_nodes(node):
            if isinstance(child, ast.stmt):
                first = child.lineno
                marked = "pragma: no cover" in lines[first - 1]
                if isinstance(child, ast.Expr) and isinstance(getattr(child, "value", None), ast.Constant) and isinstance(child.value.value, str):
                    continue
                if skip or marked:
                    excluded.update(range(first, (child.end_lineno or first) + 1))
                    visit(child, True)
                    continue
                executable.add(first)
                visit(child, False)
            else:
                marked = isinstance(child, ast.ExceptHandler) and "pragma: no cover" in lines[child.lineno - 1]
                if marked and not skip:
                    excluded.update(range(child.lineno, (getattr(child, "end_lineno", None) or child.lineno) + 1))
                visit(child, skip or marked)

    visit(tree, False)
    return sorted(executable - excluded), sorted(excluded)


def main():
    target, out = sys.argv[1], sys.argv[2]
    script = sys.argv[sys.argv.index("--") + 1]
    name = os.path.basename(target)
    executed = set()

    def local(frame, event, arg):
        if event == "line":
            executed.add(frame.f_lineno)
        return local

    def tracer(frame, event, arg):
        if os.path.basename(frame.f_code.co_filename) == name:
            return local
        return None

    sys.argv = [script] + sys.argv[sys.argv.index("--") + 2:]
    sys.settrace(tracer)
    threading.settrace(tracer)
    code = 0
    try:
        runpy.run_path(script, run_name="__main__")
    except SystemExit as exit:
        code = exit.code if isinstance(exit.code, int) else (0 if exit.code is None else 1)
    finally:
        sys.settrace(None)
        executable, excluded = executable_lines(target)
        with open(out, "w") as handle:
            json.dump({"executed": sorted(executed), "executable": executable, "excluded": excluded}, handle)
    sys.exit(code)


if __name__ == "__main__":
    main()
