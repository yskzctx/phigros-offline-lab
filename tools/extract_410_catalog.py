"""Rebuild the 4.1.0 chart catalog from the Addressables key table.

Two findings make this work:

  * each key carries a type tag, and tag 1 means the payload is UTF-16LE (the non-ASCII
    song titles: 華灯爱, 青丘, ニライカナイ...). Reading everything as UTF-8 both produced
    mojibake and broke the parse at the first such key.
  * the addresses are `Assets/Tracks/<Song>.<Artist>.<index>/Chart_<DIFF>.json`, which maps
    one-to-one onto the `<song>.<artist>.<index>.Record.<DIFF>` preference keys that
    chart-catalog.tsv holds.

So the catalog is regenerated from the build's own catalog.json. Difficulty constants are
carried over from the existing table where a chart is unchanged, and left blank for charts
the old table never had - never invented.
"""
from __future__ import annotations

import base64
import json
import sys
from pathlib import Path

CATALOG = Path(r'I:\dsh\work\410-dec\assets\aa\catalog.json')
REPO = Path(r'I:\dsh\work\phigros-offline-lab')
OLD = REPO / 'data' / 'chart-catalog.tsv'
OUT = REPO / 'data' / 'chart-catalog-410.tsv'
NEW_LIST = REPO / 'data' / 'chart-catalog-410-new.txt'


def decode_keys(raw: bytes) -> tuple[list[str], int]:
    """Decode the key table; returns (keys, keys_consumed_before_stop)."""
    count = int.from_bytes(raw[:4], 'little')
    pos = 4
    keys: list[str] = []
    for _ in range(count):
        if pos + 5 > len(raw):
            break
        tag = raw[pos]
        pos += 1
        length = int.from_bytes(raw[pos:pos + 4], 'little')
        pos += 4
        if length > 8192 or pos + length > len(raw):
            break
        payload = raw[pos:pos + length]
        pos += length
        if tag == 1 or b'\x00' in payload:
            text = payload.decode('utf-16-le', 'replace')
        else:
            text = payload.decode('utf-8', 'replace')
        keys.append(text)
    return keys, len(keys)


def main() -> int:
    try:
        sys.stdout.reconfigure(encoding='utf-8', errors='replace')
    except Exception:
        pass

    data = json.loads(CATALOG.read_text(encoding='utf-8', errors='replace'))
    keys, decoded = decode_keys(base64.b64decode(data['m_KeyDataString']))
    print(f'keys decoded: {decoded}')

    charts: dict[str, str] = {}
    for key in keys:
        if not key.startswith('Assets/Tracks/') or 'Chart_' not in key:
            continue
        parts = key.split('/')
        if len(parts) < 4:
            continue
        track = parts[2]
        diff = parts[3].replace('Chart_', '').replace('.json', '')
        if track and diff:
            charts[f'{track}.Record.{diff}'] = diff

    songs = {k.split('.')[0] for k in charts}
    print(f'charts: {len(charts)}   songs: {len(songs)}')

    old: dict[str, float] = {}
    if OLD.is_file():
        for line in OLD.read_text(encoding='utf-8').splitlines():
            p = line.split('\t')
            if len(p) == 2:
                try:
                    old[p[0]] = float(p[1])
                except ValueError:
                    pass
    print(f'157 catalog entries: {len(old)}')

    new_keys = sorted(k for k in charts if k not in old)
    carried = len(charts) - len(new_keys)
    print(f'  carried over: {carried}')
    print(f'  new in 4.1.0: {len(new_keys)}')
    for k in new_keys[:25]:
        print(f'     + {k}')

    OUT.parent.mkdir(parents=True, exist_ok=True)
    with OUT.open('w', encoding='utf-8', newline='\n') as f:
        for key in sorted(charts):
            constant = old.get(key)
            f.write(f'{key}\t{constant if constant is not None else ""}\n')
    print(f'\nwrote {OUT}')

    with NEW_LIST.open('w', encoding='utf-8', newline='\n') as f:
        for key in new_keys:
            f.write(key + '\n')
    print(f'wrote {NEW_LIST}')

    missing = [k for k in old if k not in charts]
    print(f'\nstill missing from the extract: {len(missing)}')
    return 0


if __name__ == '__main__':
    sys.exit(main())
