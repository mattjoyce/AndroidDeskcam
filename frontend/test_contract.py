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


# ------------------------------------------------------------------ the other copies
#
# The parameter contract was held to one copy above, and then everything else drifted
# instead: one measurement acquired three published values, a seven-value enum was
# documented with five, the CLI reference lost eleven commands, and the explainer's
# "complete" endpoint list was eleven of nineteen. All of it while these tests were green.
# The checks below read the same sources the documents were written from.

USAGE = ROOT / "frontend" / "go" / "usage.go"
THERMAL = BACKEND / "Thermal.java"
EXPLAINER = ROOT / "explainer" / "index.html"
SKILL = ROOT / "skill" / "SKILL.md"
ANALYSIS = ROOT / "frontend" / "analysis"


def fenced_block_after(text: str, heading: str) -> str:
    """The first fenced code block after a Markdown heading."""
    start = text.index(heading)
    open_ = text.index("```\n", start) + 4
    close = text.index("```\n", open_)
    return text[open_:close]


def section(text: str, heading: str) -> str:
    """A Markdown section, from its heading to the next heading at level two or three."""
    start = text.index(heading)
    after = re.search(r"\n#{2,3} ", text[start + len(heading) :])
    return text[start : start + len(heading) + after.start()] if after else text[start:]


def routes() -> set[str]:
    return set(re.findall(r'case "(/api/[a-z]+)"', SERVER.read_text()))


def test_the_readme_cli_reference_is_the_usage_text() -> None:
    """The CLI reference is a copy of what the binary prints, so it is held to be identical."""
    src = USAGE.read_text()
    printed = src[src.index("fmt.Print(`") + len("fmt.Print(`") : src.rindex("`)")]
    assert fenced_block_after(README.read_text(), "### CLI Reference") == printed


def test_the_readme_endpoint_table_is_the_router() -> None:
    text = README.read_text()
    table = text[text.index("### HTTP REST API Endpoints") : text.index("### Camera state")]
    named = set(re.findall(r"^\| `(/api/[a-z]+)`", table, re.MULTILINE))
    assert named == routes(), (
        f"missing {sorted(routes() - named)}, invented {sorted(named - routes())}"
    )


def test_the_explainer_endpoint_table_is_the_router() -> None:
    named = set(
        re.findall(r"<td><code>(?:GET|POST) (/api/[a-z]+)</code></td>", EXPLAINER.read_text())
    )
    assert named == routes(), (
        f"missing {sorted(routes() - named)}, invented {sorted(named - routes())}"
    )


def thermal_words() -> list[str]:
    src = THERMAL.read_text()
    body = src[src.index("static String word(") : src.index("static String means(")]
    return re.findall(r'return "([a-z]+)";', body)


def test_the_readme_lists_every_thermal_word() -> None:
    """A parser built from the README must not fall through when the phone is in trouble."""
    words = thermal_words()
    assert "emergency" in words and "shutdown" in words, "the reader above has lost the enum"
    text = README.read_text()
    line = next(row for row in text.splitlines() if "`X-DeskCam-Thermal`" in row)
    for word in words:
        assert f"`{word}`" in line, f"the X-DeskCam-Thermal line does not list `{word}`"
    ladder = section(text, "### Heat and battery")
    for word in words:
        if word != "unknown":
            assert f"`{word}`" in ladder, f"the shedding ladder does not list `{word}`"


def test_the_readme_lists_every_response_header() -> None:
    emitted = set(re.findall(r"X-DeskCam-[A-Za-z-]+", SERVER.read_text()))
    documented = set(re.findall(r"`(X-DeskCam-[A-Za-z-]+)`", README.read_text()))
    assert emitted <= documented, f"the README does not list {sorted(emitted - documented)}"
    assert documented <= emitted, (
        f"the README lists {sorted(documented - emitted)}, which nothing sends"
    )


def test_the_explainer_refusal_limits_are_the_tools_constants() -> None:
    """The page about refusal discipline once invented a gate; its numbers come from here."""
    limits = {}
    for tool, const in [
        ("linearity", "FIT_LIMIT"),
        ("scale", "PEAK_LIMIT"),
        ("burstnoise", "TIGHTNESS_LIMIT"),
        ("hdr", "COVERAGE_LIMIT"),
    ]:
        src = (ANALYSIS / f"{tool}.py").read_text()
        found = re.search(rf"^{const} = ([\d.]+)", src, re.MULTILINE)
        assert found, f"{tool}.py no longer declares {const}"
        limits[tool] = float(found.group(1))
    html = EXPLAINER.read_text()
    start = html.index("Philosophy of Refusal")
    table = html[start : html.index('id="architectural-decisions"', start)]
    for name, tool in [
        ("linearity", "linearity"),
        ("scale", "scale"),
        ("burst-noise", "burstnoise"),
        ("hdr", "hdr"),
    ]:
        row = table[table.index(f"<code>{name}</code>") :]
        row = row[: row.index("</tr>")]
        shown = re.search(r"&lt; ([\d.]+)</td>", row)
        assert shown, f"no limit shown for {name}"
        assert float(shown.group(1)) == pytest.approx(limits[tool]), (
            f"{name}: page says {shown.group(1)}, tool says {limits[tool]}"
        )


def test_the_explainer_lists_every_decision() -> None:
    decided = set(re.findall(r"^\*\*D(\d+)\.", DECISIONS.read_text(), re.MULTILINE))
    shown = set(re.findall(r"Decision D(\d+)</span>", EXPLAINER.read_text()))
    assert decided, "DECISIONS.md has no numbered decisions"
    assert shown == decided, (
        f"missing {sorted(decided - shown, key=int)}, invented {sorted(shown - decided, key=int)}"
    )


def test_the_documents_quote_one_linearity_figure() -> None:
    """One measurement was published as 2.062x, 2.004x and 2.02x at once. Never again."""
    figure = re.compile(r"\b(\d\.\d{3})x\b")
    readme_text = README.read_text()
    readme_section = readme_text[readme_text.index("### Sensor linearity") :]
    readme_section = readme_section[: readme_section.index("\n### ", 10)]
    skill_text = SKILL.read_text()
    skill_section = skill_text[skill_text.index("## Measuring, not photographing") :]
    skill_section = skill_section[: skill_section.index("\n## ", 10)]
    readme = set(figure.findall(readme_section))
    skill = set(figure.findall(skill_section))
    assert readme, "the README no longer states the linearity result"
    assert readme == skill, f"README says {sorted(readme)}, skill says {sorted(skill)}"


def test_the_documents_name_only_real_endpoints() -> None:
    """A made-up endpoint in a header list is how /api/focuswalk came to be published."""
    known = routes()
    for doc in (README, SKILL, EXPLAINER, DECISIONS):
        named = set(re.findall(r"/api/[a-z]+", doc.read_text()))
        invented = sorted(named - known)
        assert not invented, f"{doc.name} names {invented}, which the server does not answer"


# ----------------------------------------------------------------- versions

VERSION_FILE = ROOT / "VERSION"
CHANGELOG = ROOT / "CHANGELOG.md"
MANIFEST = ROOT / "backend" / "app" / "AndroidManifest.xml"


def test_the_version_is_semver_and_has_a_changelog_entry() -> None:
    """build.sh derives Android's version code as major*10000 + minor*100 + patch."""
    version = VERSION_FILE.read_text().strip()
    assert re.fullmatch(r"\d+\.\d{1,2}\.\d{1,2}", version), f"VERSION is not x.y.z: {version!r}"
    assert f"## [{version}]" in CHANGELOG.read_text(), f"CHANGELOG.md has no section for {version}"


def test_the_manifest_carries_no_version_of_its_own() -> None:
    """One version, in VERSION. A second copy in the manifest is how they drift apart."""
    text = MANIFEST.read_text()
    assert "versionCode" not in text
    assert "versionName" not in text


def test_no_placeholder_survives_in_the_readme() -> None:
    assert "{{" not in README.read_text(), "a {{placeholder}} was left in the README"


# ------------------------------------------------------------------- the panels

CONSOLE_PAGE = ROOT / "frontend" / "go" / "page.html"


PHONE_PAGE = ROOT / "backend" / "app" / "assets" / "panel.html"


@pytest.mark.parametrize("page", [PHONE_PAGE, CONSOLE_PAGE], ids=["phone", "console"])
def test_a_panel_hides_what_it_marks_hidden(page: Path) -> None:
    """A rule that sets display beats the browser's own [hidden] rule.

    Both panels toggle the paused overlay with `hidden` and style it with `display:flex`.
    Without this guard the overlay covered the live view from the first paint, dimmed it,
    and took every click, drag and wheel meant for the picture.
    """
    css = re.sub(r"\s+", "", page.read_text())
    assert "[hidden]{display:none!important" in css
