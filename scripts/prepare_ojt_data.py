"""Prepare immutable local splits; does not train or download models."""
import argparse
import json
from pathlib import Path

from live.ojt_data import prepare


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('source', type=Path)
    parser.add_argument('--media-root', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--seed', type=int, default=42)
    parser.add_argument('--split-map', type=Path, help='exact group_id to split mapping')
    parser.add_argument('--provenance', type=Path, help='source and label review JSONL sidecar')
    parser.add_argument('--allow-unreviewed', action='store_true', help='audit pending labels; not training acceptance')
    parser.add_argument('--fixture', action='store_true', help='mark synthetic tooling data, not evaluation evidence')
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    # Labels and local media references also remain outside version control.
    if not args.output.resolve().is_relative_to(root / '.runtime' / 'ojt'):
        parser.error('--output must be inside .runtime/ojt/')
    try:
        report = prepare(args.source, args.media_root, args.output, root / 'configs/models.json',
                         args.seed, args.fixture, args.split_map, args.provenance, args.allow_unreviewed)
    except (ValueError, OSError, KeyError) as exc:
        parser.exit(1, f'{exc}\n')
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
