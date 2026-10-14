"""Generate a bounded Korean annotation audit from the official GQA scene-graph ZIP."""
import argparse
import json
from pathlib import Path

from live.ojt_gqa import build_audit


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('archive', type=Path)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--counts', type=int, nargs=3, default=(140, 30, 30), metavar=('TRAIN', 'DEV', 'TEST'))
    parser.add_argument('--seed', type=int, default=42)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    if not args.output.resolve().is_relative_to(root / '.runtime' / 'ojt'):
        parser.error('--output must be inside .runtime/ojt/')
    try:
        report = build_audit(args.archive, args.output, args.counts, args.seed)
    except (ValueError, OSError, KeyError) as exc:
        parser.exit(1, f'{exc}\n')
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
