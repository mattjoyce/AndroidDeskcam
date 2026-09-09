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
run "$BIN/mypy" frontend/console.py frontend/analysis frontend/test_console.py \
    frontend/test_contract.py frontend/test_analysis.py

# bandit reads the shipped module. Test files are excluded because B101 fires on every
# assert, which is what a test is made of.
run "$BIN/bandit" -q -r frontend/console.py frontend/analysis

run "$PY" -m pytest frontend/ -q

# The Go half: the CLI and the console. Skipped rather than failed when there is no
# toolchain, because the Python tools have to stay runnable on a machine without one.
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

echo
echo "all gates pass"
