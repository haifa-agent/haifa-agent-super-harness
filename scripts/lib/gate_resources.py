"""Picks the full-gate fork count from the free memory at the moment it starts.

``scripts/verify-full.ps1`` asks this module only when the caller passed neither ``-Forks``
nor ``-LowMemory``. The thresholds are the measured memory peak of a run plus 2 GB. Free
physical memory is compared against the Java working-set peak and free commit memory against
the private-bytes peak, both taken from the controller's four-run measurement on 2026-10-05
(16 cores, 31 GB, commit 7077325; ``local-tmp/<slot>/evidence/fork-measure/measure-summary.json``):

- 3 forks: working-set peak 2634 MB (max of 2634/2326) and private peak 3259 MB (max of
  3259/3044) -> 4682 / 5307 MB thresholds;
- 2 forks: working-set peak 2918 MB and private peak 3914 MB -> 4966 / 5962 MB thresholds;
- otherwise 1 fork with the low-memory Maven parameters.

The measured 2-fork peaks are not below the 3-fork peaks, so the 2-fork thresholds are higher
than the 3-fork thresholds and the 2-fork band can never satisfy its own guard before the
3-fork guard: the 2-fork tier is not reachable in practice and the constants are kept as
measured rather than tuned to make all three tiers appear.

    python scripts/lib/gate_resources.py      # {"forks": n, "lowMemory": bool, ...}
"""

from __future__ import annotations

import ctypes
import json
import sys

# Measured 2026-10-05 (commit 7077325): each threshold is the observed peak + 2048 MB.
THREE_FORKS_MIN_FREE_PHYSICAL_MB = 4682
THREE_FORKS_MIN_FREE_COMMIT_MB = 5307
TWO_FORKS_MIN_FREE_PHYSICAL_MB = 4966
TWO_FORKS_MIN_FREE_COMMIT_MB = 5962
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
