#!/usr/bin/env sh
# Quick start without installing a service (no root required).
# Starts the Gateway + frozen frontend in the background and writes logs under logs/.
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

exec "$PY" "$HERE/scripts/install-service.py" start --prefix "$HERE" "$@"
