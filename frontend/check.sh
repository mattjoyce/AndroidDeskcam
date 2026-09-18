#!/usr/bin/env bash
# The quality gates for the workstation half, as one command.
#
# The phone half has its own gates: backend/build.sh compiles with -Xlint:all -Werror and
# runs backend/test/dev/deskcam/Tests.java before it packages anything.
#
# Settings live in pyproject.toml and follow "Agent Coding Standards - Python".
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

PY="${PY:-$ROOT/.venv/bin/python}"
[ -x "$PY" ] || PY=python3
BIN="$(dirname "$PY")"

run() { echo ">> $*"; "$@"; }

run "$BIN/ruff" check frontend/
run "$BIN/ruff" format --check frontend/
run "$BIN/mypy" frontend/analysis frontend/test_contract.py frontend/test_analysis.py \
    frontend/surface.py frontend/streamlag.py

# surface.py is the live half of the contract check and needs a phone, so it is not run
# here. It is type-checked above, because a tool nobody can run when they need it is worse
# than no tool: see its own docstring for how it is used.

# bandit reads the shipped module. Test files are excluded because B101 fires on every
# assert, which is what a test is made of.
run "$BIN/bandit" -q -r frontend/analysis

run "$PY" -m pytest frontend/ -q

# The Go half: the CLI and the console, which is now the whole workstation tool. Skipped
# rather than failed when there is no toolchain, because the measurement tools have to
# stay runnable on a machine without one.
if command -v go >/dev/null 2>&1; then
    echo ">> go (frontend/go)"
    (
        cd frontend/go
        unformatted="$(gofmt -l .)"
        if [ -n "$unformatted" ]; then
            echo "gofmt would change:" >&2
            echo "$unformatted" >&2
            exit 1
        fi
        go vet ./...
        go build -o deskcam .
        go test ./...
    )
else
    echo ">> go (skipped, no toolchain)"
fi

# The console's page is a file the Go build embeds without reading, so a script that does
# not parse builds, passes every Go test, and serves a page that does nothing. It happened:
# a second declaration of `key` stopped the whole script and no gate noticed. Skipped when
# there is no node, for the same reason the Go half is.
if command -v node >/dev/null 2>&1; then
    echo ">> node --check (the console page's script)"
    script="$(mktemp --suffix=.js)"
    "$PY" - "$script" <<'PYEOF'
import re
import sys
from pathlib import Path

page = Path("frontend/go/page.html").read_text()
Path(sys.argv[1]).write_text("\n".join(re.findall(r"<script>(.*?)</script>", page, re.S)))
PYEOF
    node --check "$script"
    rm -f "$script"
else
    echo ">> node --check (skipped, no node)"
fi

echo
echo "all gates pass"
