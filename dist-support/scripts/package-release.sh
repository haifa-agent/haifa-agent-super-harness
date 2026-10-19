#!/usr/bin/env sh
# Thin wrapper for dist-support/scripts/package-release.py.
# All logic lives in Python; this only locates an interpreter and forwards args/exit code.
set -eu

HERE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)

if command -v python3 >/dev/null 2>&1; then
    PY=python3
elif command -v python >/dev/null 2>&1; then
    PY=python
else
    echo "python (>= 3.8) is required to run package-release.py" >&2
    exit 1
fi

exec "$PY" "$HERE/package-release.py" "$@"
