"""Fold captured production HTTP/SSE with this checkout's frozen JS client."""
from pathlib import Path
import argparse
import json
import subprocess
import sys


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--inputs', required=True, type=Path,
                        help='JSON array of actual recorder file paths, not fabricated frames')
    args = parser.parse_args()
    inputs = args.inputs.resolve()
    files = json.loads(inputs.read_text(encoding='utf-8-sig'))
    if not isinstance(files, list) or not files or not all(
            isinstance(file, str) and Path(file).is_file() for file in files):
        parser.error('inputs must be a nonempty array of existing recorder paths')
    root = Path(__file__).resolve().parents[3]
    script = Path(__file__).with_name('w05-live-output-frozen-consumer.mjs')
    if not (root / 'frontend/node_modules/@langchain/langgraph-sdk').exists():
        parser.error('install the frozen frontend dependencies in this checkout first')
    try:
        result = subprocess.run(['node', str(script), str(inputs), str(root)], cwd=root,
                                capture_output=True, timeout=30)
    except subprocess.TimeoutExpired:
        print('FAIL: frozen consumer exceeded the 30-second contract budget', file=sys.stderr)
        return 124
    sys.stdout.buffer.write(result.stdout)
    sys.stderr.buffer.write(result.stderr)
    return result.returncode


if __name__ == '__main__':
    raise SystemExit(main())
