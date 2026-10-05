"""Picks the full-gate fork count from the free memory at the moment it starts.

``scripts/verify-full.ps1`` asks this module only when the caller passed neither ``-Forks``
nor ``-LowMemory``. The thresholds are the measured memory peak of a run plus 2 GB, kept as
named constants:

- 3 forks: the highest stable count seen in the measurement;
- 2 forks: the next step down;
- otherwise 1 fork with the low-memory Maven parameters.

Provisional values (待实测校准): the controller's four-run measurement
(``local-tmp/<slot>/evidence/fork-measure/measure-summary.json``) was still incomplete (only the
1-fork run, 2026-10-05, private peak 3343 MB, 811 s) when this module was written, so the
conservative defaults from the dispatch are used for the 2- and 3-fork thresholds. Replace each
constant with ``measured peak at that fork count + 2048`` once the remaining runs land and note
the measurement date.

    python scripts/lib/gate_resources.py      # {"forks": n, "lowMemory": bool, ...}
"""

from __future__ import annotations

import ctypes
import json
import sys

# Measured 2026-10-05 (provisional, 待实测校准); each threshold is the observed peak + 2048 MB.
THREE_FORKS_MIN_FREE_PHYSICAL_MB = 6144
THREE_FORKS_MIN_FREE_COMMIT_MB = 12288
TWO_FORKS_MIN_FREE_PHYSICAL_MB = 4096
TWO_FORKS_MIN_FREE_COMMIT_MB = 8192
DEFAULT_FORKS = 3


class MemoryStatusEx(ctypes.Structure):
    _fields_ = [
        ("dwLength", ctypes.c_ulong),
        ("dwMemoryLoad", ctypes.c_ulong),
        ("ullTotalPhys", ctypes.c_ulonglong),
        ("ullAvailPhys", ctypes.c_ulonglong),
        ("ullTotalPageFile", ctypes.c_ulonglong),
        ("ullAvailPageFile", ctypes.c_ulonglong),
        ("ullTotalVirtual", ctypes.c_ulonglong),
        ("ullAvailVirtual", ctypes.c_ulonglong),
        ("ullAvailExtendedVirtual", ctypes.c_ulonglong),
    ]


def select_forks(free_physical_mb: int | None, free_commit_mb: int | None) -> tuple[int, bool]:
    """Pure decision: (fork count, use low-memory Maven parameters)."""
    if free_physical_mb is None or free_commit_mb is None:
        return DEFAULT_FORKS, False
    if free_physical_mb >= THREE_FORKS_MIN_FREE_PHYSICAL_MB and free_commit_mb >= THREE_FORKS_MIN_FREE_COMMIT_MB:
        return 3, False
    if free_physical_mb >= TWO_FORKS_MIN_FREE_PHYSICAL_MB and free_commit_mb >= TWO_FORKS_MIN_FREE_COMMIT_MB:
        return 2, False
    return 1, True


def probe_memory() -> dict | None:
    """Free physical and commit memory in MB, or None when the probe is unavailable."""
    try:
        status = MemoryStatusEx()
        status.dwLength = ctypes.sizeof(MemoryStatusEx)
        if not ctypes.windll.kernel32.GlobalMemoryStatusEx(ctypes.byref(status)):
            return None
        return {
            "freePhysicalMb": int(status.ullAvailPhys // (1024 * 1024)),
            "freeCommitMb": int(status.ullAvailPageFile // (1024 * 1024)),
        }
    except (AttributeError, OSError):
        return None


def selection() -> dict:
    probe = probe_memory()
    if probe is None:
        return {"forks": DEFAULT_FORKS, "lowMemory": False, "freePhysicalMb": None,
                "freeCommitMb": None, "probeAvailable": False}
    forks, low_memory = select_forks(probe["freePhysicalMb"], probe["freeCommitMb"])
    return {"forks": forks, "lowMemory": low_memory, "freePhysicalMb": probe["freePhysicalMb"],
            "freeCommitMb": probe["freeCommitMb"], "probeAvailable": True}


def main() -> int:
    print(json.dumps(selection()))
    return 0


if __name__ == "__main__":
    sys.exit(main())
