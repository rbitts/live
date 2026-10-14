"""Acquire at most 200 selected GQA images using bounded HTTP ranges, not the full ZIP."""
import argparse
from pathlib import Path

from live.ojt_download import fetch_gqa_images


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('requests', type=Path)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    if not args.output.resolve().is_relative_to(root / '.runtime' / 'ojt'):
        parser.error('--output must be inside .runtime/ojt/')
    try:
        result = fetch_gqa_images(args.requests, args.output,
                                  progress=lambda i, n: print(f'images {i}/{n}', flush=True) if i % 20 == 0 or i == n else None)
    except (ValueError, OSError, KeyError) as exc:
        parser.exit(1, f'{exc}\n')
    print(f'{result["status"]}: {len(result["files"])} images, {result["transferred_bytes"]} HTTP bytes')


if __name__ == '__main__':
    main()
