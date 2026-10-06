"""Real-name layer of the fixture tools.

The committed subset rules, the fixture under ``plugin/src/test/testData/infra`` and the tests use neutral aliases for
the names of the source infrastructure repository (product repositories, domains). The mapping from real names to
aliases is local only: ``tools/fixtures/local/names.json`` (git-ignored), passed with ``sync.py --names``. Without a
mapping every name maps to itself.

``names.json``::

    {"literal": [["real-substring", "alias"], ...], "tokens": {"real": "alias", ...},
     "regex": [["python-regex", "replacement"], ...]}

Literal pairs replace substrings; tokens replace whole words (letters and digits around them end a word, ``_``, ``-``
and a preceding string escape such as ``\n`` do not), in lower case, Capitalised (camel case) and UPPER case alike.
"""
import json
import re
from pathlib import Path
from typing import Dict, List, Optional, Sequence, Tuple

HERE = Path(__file__).resolve().parent
LOCAL_MAPPING = HERE / "local" / "names.json"


def _word_patterns(pairs: Dict[str, str]) -> List[Tuple["re.Pattern[str]", str]]:
    out = []
    for real, alias in sorted(pairs.items(), key=lambda kv: -len(kv[0])):
        out.append((re.compile(r"(?:(?<![A-Za-z0-9])|(?<=\\[ntr]))" + re.escape(real) + r"(?![a-z])"), alias))
        out.append((re.compile(r"(?<![A-Z0-9])" + re.escape(real.capitalize()) + r"(?![a-z])"), alias.capitalize()))
        out.append((re.compile(r"(?:(?<![A-Za-z0-9])|(?<=\\[ntr]))" + re.escape(real.upper()) + r"(?![A-Za-z])"), alias.upper()))
    return out


class Names:
    """Maps texts and paths between the real names and their aliases."""

    def __init__(
        self,
        literal: Sequence[Sequence[str]] = (),
        tokens: Optional[Dict[str, str]] = None,
        regex: Sequence[Sequence[str]] = (),
    ) -> None:
        self.literal = [(str(a), str(b)) for a, b in literal]
        self.tokens = dict(tokens or {})
        self.regex = [(re.compile(str(p)), str(r)) for p, r in regex]
        self._to_alias = _word_patterns(self.tokens)
        # Several real names may share an alias; paths only ever hold the longest one, which wins here.
        reverse: Dict[str, str] = {}
        for real, alias in sorted(self.tokens.items(), key=lambda kv: -len(kv[0])):
            reverse.setdefault(alias, real)
        self._to_real = _word_patterns(reverse)

    @property
    def identity(self) -> bool:
        return not self.literal and not self.tokens and not self.regex

    def to_alias(self, text: str) -> str:
        """``text`` with every real name replaced by its alias (contents and paths of the fixture)."""
        for real, alias in self.literal:
            text = text.replace(real, alias)
        for pattern, alias in self._to_alias:
            text = pattern.sub(alias, text)
        for pattern, alias in self.regex:
            text = pattern.sub(alias, text)
        return text

    def to_real(self, path: str) -> str:
        """A subset rule or fixture path with its aliases replaced by the real names (to read the source repo)."""
        for pattern, real in self._to_real:
            path = pattern.sub(real, path)
        return path


IDENTITY = Names()


def load(path: Optional[Path]) -> Names:
    """The mapping in ``path``, or :data:`IDENTITY` for ``None``."""
    if path is None:
        return IDENTITY
    data = json.loads(Path(path).read_text(encoding="utf-8"))
    return Names(data.get("literal", ()), data.get("tokens", {}), data.get("regex", ()))
