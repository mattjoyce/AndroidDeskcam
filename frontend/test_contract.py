"""
The contract exists once, and these tests keep it that way.

`Params.java` declares every parameter name, its group and its help text. The parser reads
that list and `/api/help` is printed from it, so those two cannot drift. The documents are
the third copy, and they are prose, so nothing but a test can hold them to it. Before this,
`/api/help` was missing five endpoints and `measure`, and the README, the specification and
the help each listed a different set of parameters.

These read the Java source as text. That is on purpose: they must fail on a workstation
with no phone and no Android SDK, in the same second as the rest of the suite.

`surface.py` beside this file is the other half, for the claim these cannot make: that a
change meant to alter nothing altered nothing. It asks a running phone and compares two
recordings, so it is a tool you run across a refactor rather than a test that runs here.
"""

from __future__ import annotations

import re
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parent.parent
BACKEND = ROOT / "backend" / "app" / "src" / "dev" / "deskcam"
SERVER = BACKEND / "HttpServer.java"
HELP = BACKEND / "WebUi.java"
PARAMS = BACKEND / "Params.java"
README = ROOT / "README.md"
DECISIONS = ROOT / "docs" / "DECISIONS.md"

# The names of one declaration: the run of string literals just before its closing `);`.
TRAILING_NAMES = re.compile(r'((?:"[a-z_]{1,16}"\s*,\s*)*"[a-z_]{1,16}")\s*\);')
GROUP_CALL = re.compile(r"^\s{8}(camera|presentation|router)\(", re.MULTILINE)


def declarations() -> dict[str, list[str]]:
    """Every parameter Params.java declares, by group."""
    src = PARAMS.read_text()
    body = src[src.index("static {") : src.index("\n    public static P get(")]
    out: dict[str, list[str]] = {"camera": [], "presentation": [], "router": []}
    starts = [(m.start(), m.group(1)) for m in GROUP_CALL.finditer(body)]
    assert starts, "no parameter declarations found; has Params.java been reshaped?"
    for i, (start, group) in enumerate(starts):
        end = starts[i + 1][0] if i + 1 < len(starts) else len(body)
        chunk = body[start:end]
        match = TRAILING_NAMES.search(chunk)
        assert match, f"cannot read the names out of: {chunk[:80]!r}"
        out[group].extend(re.findall(r'"([a-z_]+)"', match.group(1)))
    return out


@pytest.fixture(scope="module")
def declared() -> dict[str, list[str]]:
    return declarations()


def test_the_source_declares_the_parameters_we_expect(declared: dict[str, list[str]]) -> None:
    """A guard on the reader above, so a silent parse failure cannot pass everything."""
    assert "zoom" in declared["camera"]
    assert "w" in declared["presentation"]
    assert "settle" in declared["router"]
    assert len(declared["camera"]) > 20


def test_every_name_is_declared_once(declared: dict[str, list[str]]) -> None:
    everything = declared["camera"] + declared["presentation"] + declared["router"]
    assert len(everything) == len(set(everything)), "a name is declared twice"


def test_every_parameter_has_help_text() -> None:
    """/api/help is printed from these, so a name with no help is a hole in R6."""
    src = PARAMS.read_text()
    body = src[src.index("static {") : src.index("\n    public static P get(")]
    for match in GROUP_CALL.finditer(body):
        after = body[match.end() : match.end() + 200].lstrip()
        assert after.startswith('"'), f"a declaration with no help text: {after[:60]!r}"


@pytest.mark.parametrize("doc", [README], ids=["README"])
def test_the_documents_name_every_parameter(doc: Path, declared: dict[str, list[str]]) -> None:
    """The README is the only prose that has to name every parameter.

    docs/SPEC.md used to be checked here too. It was deleted: a specification for a thing
    that already exists is a second copy of it, and this assertion was part of what kept
    that copy alive. What replaced it, docs/DECISIONS.md, records why things are the way
    they are and deliberately lists no parameters, so there is nothing here to check.
    """
    text = doc.read_text()
    missing = [
        name
        for names in declared.values()
        for name in names
        if name not in ("t", "_") and f"`{name}`" not in text
    ]
    assert not missing, f"{doc.name} does not mention {missing}"


def test_the_readme_invents_no_parameter(declared: dict[str, list[str]]) -> None:
    """A row in the README for something the parser will reject is worse than no row."""
    known = set(declared["camera"] + declared["presentation"] + declared["router"])
    text = README.read_text()
    tables = text[text.index("### Camera state") : text.index("### Errors")]
    named = {
        n
        for row in tables.splitlines()
        if row.startswith("| `")
        for n in re.findall(r"`([a-z_]+)`", row.split("|")[1])
    }
    assert named, "the parameter tables have gone"
    assert named <= known, f"the README names parameters that do not exist: {sorted(named - known)}"


def test_the_presentation_group_is_exactly_what_the_decision_says(
    declared: dict[str, list[str]],
) -> None:
    """Decision D9. Widening this set quietly is how w and h came to persist."""
    assert sorted(declared["presentation"]) == ["h", "jpegq", "quality", "w"]


def test_rotate_is_camera_state(declared: dict[str, list[str]]) -> None:
    """The other half of decision D9, which the specification had to settle explicitly."""
    assert "rotate" in declared["camera"]


def test_api_help_names_every_endpoint() -> None:
    """
    Rule R6: an agent learns the whole surface from /api/help.

    The endpoint list is written by hand while the routes are a switch, so the two drift
    apart in silence. They did: /api/focussweep and /api/bracket were built, tested and
    documented for people, and an agent reading /api/help could not find either. The
    parameters cannot drift this way because they are generated from one table; this is
    the same check for the other half of the surface.
    """
    routes = set(re.findall(r'case "(/api/[a-z]+)"', SERVER.read_text()))
    # The method is part of the advertisement, and /api/script is the one POST.
    advertised = set(re.findall(r'ep\.put\("(?:GET|POST) (/api/[a-z]+)"', HELP.read_text()))
    missing = sorted(routes - advertised)
    assert not missing, f"/api/help does not mention {missing}"
    invented = sorted(advertised - routes)
    assert not invented, f"/api/help offers {invented}, which the server does not answer"
