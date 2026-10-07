# Run events: goldens and coverage of the Ansibility callback

`plugin/src/main/resources/ansible/callback/ansibility_events.py` reports a run as token-framed JSON events on stderr
(plan amendment R13). This directory checks it against real Ansible:

- `generate.py` runs every case under `cases/` against the local `ansible-playbook` and the Compose image
  (`--image`, default `ansible-playbook:latest`, run with `--network none`), normalises the events and writes
  `plugin/src/test/testData/run-events/<case>/<ansible-core version>.jsonl` plus `COVERAGE.txt`. It fails when the
  callback's line coverage (union of all runs and `test_callback.py`) is below `--min-coverage` (90).
- `cover.py` is a standard-library line tracer for one file (no `coverage` package needed, in or outside the image).
- `test_callback.py` unit-tests the defensive helpers with stub `ansible` modules; CI runs it:
  `python3 -m unittest discover -s tools/run-events -p 'test_*.py'`.

Regenerate after changing the callback or a case: `python3 tools/run-events/generate.py` (`--local-only` without docker).

## Molecule (plan amendment R14)

`molecule/roles/demo` is a role with two scenarios on Molecule's `default` (delegated) driver against `localhost`, so a
run needs no containers and no network: `default` passes converge, idempotence and verify; `volatile` adds a task that
always changes, so its idempotence fails. `generate.py --molecule-image IMAGE` runs `molecule test --all` on it in an
image that has Molecule (`--network none`, the callback mounted) and writes the merged stdout and stderr, normalised,
to `plugin/src/test/testData/run-events/molecule/<molecule version>.log`; `--molecule-only` skips the playbook cases.
`MoleculeTest` replays it in order and with the stage lines arriving early; `RunViewTest` shows it in the Plays tab.

Molecule (25 and later) logs its stage lines (`[default > converge] Executing`) on stderr and passes Ansible's output,
the event frames included, on through stdout. The recording merges the two; the IDE reads them on two threads. The
opt-in test `RunViewTest.testARealMoleculeRunWhenAnImageIsGiven` runs the role through the IDE's process handler, two
streams and all:

    ANSIBILITY_MOLECULE_IMAGE=<image with molecule> ./gradlew :plugin:test --tests '*RunViewTest.testARealMolecule*' --rerun

(`--rerun`: the variable is no input of the test task, so Gradle would otherwise report the last result.)
