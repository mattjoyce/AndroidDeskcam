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

echo
echo "all gates pass"
