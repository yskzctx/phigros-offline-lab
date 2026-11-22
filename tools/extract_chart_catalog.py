"""Extract the chart catalog (song key -> difficulty constant) from a Phigros APK.

`data/chart-catalog.tsv` in this repository is a 1037-line table of
`<songKey>\t<difficulty>` pairs, generated once by hand from a game build and never
regenerated. It is what RksEditor reads to know each chart's constant, so a stale
table means the RKS editor cannot see songs added after that build.

The chart data lives in Addressables bundles under `assets/aa/Android/`. Each bundle
is a Unity asset bundle; the chart metadata strings are plain UTF-8 inside, so the
catalog can be rebuilt by scanning the bundle payloads for the `<song>.<artist>.<id>`
key pattern and the difficulty table that follows.

This tool extracts those pairs from an APK and writes a TSV in the existing format,
so a newer APK produces an updated catalog without hand editing.
"""
from __future__ import annotations

import argparse
import json
import re
import struct
import sys
import zipfile
from pathlib import Path

# A chart key looks like `Song.Artist.0.Record.EZ` - the same shape the existing
# catalog uses. Difficulty suffixes are fixed by the game.
KEY = re.compile(rb'([A-Za-z0-9_\-]+)\.([A-Za-z0-9_\-]+)\.(\d+)\.Record\.(EZ|HD|IN|AT|Legacy)', re.ASCII)


def bundle_names(apk: Path) -> list[str]:
    with zipfile.ZipFile(apk) as archive:
        return [n for n in archive.namelist() if n.startswith('assets/aa/Android/')]


def scan_bundle(data: bytes) -> set[tuple[str, str, int, str]]:
    found = set()
    for match in KEY.finditer(data):
        song = match.group(1).decode('ascii', 'replace')
        artist = match.group(2).decode('ascii', 'replace')
        index = int(match.group(3))
        difficulty = match.group(4).decode('ascii')
        found.add((song, artist, index, difficulty))
    return found


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--apk', type=Path, required=True)
    parser.add_argument('--out', type=Path)
    parser.add_argument('--compare', type=Path,
                        help='existing catalog to diff against')
    args = parser.parse_args()

    names = bundle_names(args.apk)
    print(f'{args.apk.name}: {len(names)} Addressables bundles')
    if not names:
        raise SystemExit('no bundles found; this APK layout is not supported')

    keys: set[tuple[str, str, int, str]] = set()
    scanned = 0
    with zipfile.ZipFile(args.apk) as archive:
        for name in names:
            info = archive.getinfo(name)
            # Chart metadata is small; skip the giant audio bundles entirely.
            if info.file_size > 64 * 1024 * 1024:
                continue
            try:
                data = archive.read(name)
            except Exception:
                continue
            scanned += 1
            keys |= scan_bundle(data)
    print(f'  scanned {scanned} bundle(s), found {len(keys)} chart keys')

    if not keys:
        raise SystemExit('no chart keys found; the key pattern may have changed')

    lines = [f'{song}.{artist}.{index}.Record.{difficulty}\t0.0'
             for song, artist, index, difficulty in sorted(keys)]

    if args.compare and args.compare.is_file():
        existing = {}
        for line in args.compare.read_text(encoding='utf-8').splitlines():
            if '\t' not in line:
                continue
            key, value = line.split('\t', 1)
            existing[key] = value
        new_keys = [k for k in (l.split('\t')[0] for l in lines) if k not in existing]
        gone = [k for k in existing if k not in {l.split('\t')[0] for l in lines}]
        print(f'\ncompared with {args.compare.name}:')
        print(f'  existing entries: {len(existing)}')
        print(f'  extracted entries: {len(lines)}')
        print(f'  new in this APK: {len(new_keys)}')
        for key in new_keys[:15]:
            print(f'    + {key}')
        if gone:
            print(f'  present before but not found now: {len(gone)}')
            for key in gone[:5]:
                print(f'    - {key}')

    if args.out:
        args.out.write_text('\n'.join(lines) + '\n', encoding='utf-8')
        print(f'\nwrote {args.out} ({len(lines)} lines)')
    return 0


if __name__ == '__main__':
    sys.exit(main())
