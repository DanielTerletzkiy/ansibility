#!/usr/bin/env python3
"""Run a scenario: python3 shoot.py <script.js> [--bg]. lib.js is prepended."""
import os, sys
from robot import js
here = os.path.dirname(os.path.abspath(__file__))
lib = open(os.path.join(here, "lib.js")).read()
src = open(sys.argv[1]).read() if sys.argv[1] != "-" else sys.stdin.read()
if "--raw" not in sys.argv:
    src = "edt(function () {\n" + src + "\n});"
js(lib + "\n" + src, edt=False)
