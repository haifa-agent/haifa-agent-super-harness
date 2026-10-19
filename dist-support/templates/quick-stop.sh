#!/usr/bin/env sh
# Stop the processes started by quick-start.sh (uses config/run.json PIDs only).
set -eu

HERE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)

if command -v python3 >/dev/null 2>&1; then
    PY=python3
elif command -v python >/dev/null 2>&1; then
    PY=python
else
    echo "python (>= 3.8) is required." >&2
    exit 1
fi

exec "$PY" "$HERE/scripts/install-service.py" stop --prefix "$HERE" "$@"
