"""Picks the full-gate fork count from the free memory at the moment it starts.

``scripts/verify-full.ps1`` asks this module only when the caller passed neither ``-Forks``
nor ``-LowMemory``. There are two outcomes: three forks, or one fork with the low-memory Maven
parameters.

Basis (controller measurements on 2026-10-05, 16 cores, 31 GB; four-run measurement at commit
7077325 plus later gates on 8f7911f and d5ac624):

- memory use barely depends on the fork count: Java private bytes peaked at 3.0-3.9 GB and the
  working set at 1.9-2.9 GB for one, two and three forks alike, while three forks took
  247-376 s against 811 s for one fork. A two-fork tier would need at least as much memory as
  three forks, so it is not offered;
- three forks passed with as little as 4623 MB free physical memory at the start; free
  physical memory never fell below 3.1 GB and free commit never below 9.5 GB during a run.

Free commit is the hard limit (a JVM that cannot reserve its heap fails), so it keeps the
"measured peak + 2 GB" margin: 3914 + 2048, rounded up to 6144 MB. Free physical memory only
has to hold the working set; the first rule ("peak + 2 GB" = 4682 MB) sent a 4.6 GB machine
to the 811-second single-fork run although three forks pass there, so the human decision on
2026-10-05 relaxed it to 4096 MB.

    python scripts/lib/gate_resources.py      # {"forks": n, "lowMemory": bool, ...}
"""

from __future__ import annotations

import ctypes
import json
import sys

# See the module docstring for the measurements behind both values.
THREE_FORKS_MIN_FREE_PHYSICAL_MB = 4096
THREE_FORKS_MIN_FREE_COMMIT_MB = 6144
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
