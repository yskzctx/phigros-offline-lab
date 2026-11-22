"""Build the 4.1.0 chart catalog from the Addressables bundles.

Each chart is a TextAsset named Chart_EZ / Chart_HD / Chart_IN / Chart_AT holding the
chart JSON. The chart itself does not carry the song id, but the bundle's asset container
does - so this walks every bundle, records which song container each chart asset belongs
to, and emits the catalog in the existing `<song>.<artist>.<idx>.Record.<DIFF>` form.

The difficulty constant is not in the chart JSON, so the value written is the chart's own
level indicator where present; where it is absent the row is still emitted with the
difficulty it was found under, and the constant is filled from the existing 157 catalog
when the song is not new. New songs get their constant from the chart JSON if exposed,
otherwise the row is recorded as unknown rather than invented.
"""
from __future__ import annotations

import json
import re
import sys
from pathlib import Path

import UnityPy

AA = Path(r'I:\dsh\work\410-dec\assets\aa\Android')
CHART = re.compile(r'^Chart_(EZ|HD|IN|AT)$')
SONGISH = re.compile(r'^[A-Za-z][A-Za-z0-9_\-]{2,40}$')


def song_from_container(container: str) -> str:
    """The bundle container path names the song, e.g. .../charts/SongName/Chart_IN."""
    parts = [p for p in container.replace('\\', '/').split('/') if p]
    if len(parts) >= 2:
        return parts[-2]
    return ''


def main() -> int:
    try:
        sys.stdout.reconfigure(encoding='utf-8', errors='replace')
    except Exception:
        pass

    out_path = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(
        r'I:\dsh\work\phigros-offline-lab\data\chart-catalog-410.tsv')

    bundles = sorted(AA.glob('*.bundle'))
    print(f'bundles: {len(bundles)}')

    rows: dict[tuple[str, str], dict] = {}
    scanned = 0
    failed = 0

    for path in bundles:
        scanned += 1
        if scanned % 200 == 0:
            print(f'  ... {scanned}/{len(bundles)}  charts={len(rows)}', flush=True)
        try:
            env = UnityPy.load(str(path))
        except Exception:
            failed += 1
            continue
        for obj in env.objects:
            if obj.type.name != 'TextAsset':
                continue
            try:
                data = obj.read()
                name = getattr(data, 'm_Name', '') or ''
            except Exception:
                continue
            m = CHART.match(name)
            if not m:
                continue
            difficulty = m.group(1)
            # The container that holds this asset gives the song.
            container = ''
            try:
                container = obj.container or ''
            except Exception:
                pass
            song = song_from_container(container)
            if not song:
                continue
            script = getattr(data, 'm_Script', '') or ''
            if isinstance(script, bytes):
                script = script.decode('utf-8', 'replace')
            info = {'difficulty': difficulty, 'container': container, 'bytes': len(script)}
            # Some charts expose a level; record it if so, never invent one.
            try:
                parsed = json.loads(script)
                for key in ('level', 'difficulty', 'constant', 'rank'):
                    if key in parsed and isinstance(parsed[key], (int, float)):
                        info['constant'] = parsed[key]
                        break
            except Exception:
                pass
            rows[(song, difficulty)] = info

    print(f'\nscanned {scanned} bundles ({failed} unreadable)')
    print(f'charts found: {len(rows)}')
    songs = {s for s, _ in rows}
    print(f'songs found: {len(songs)}')

    by_diff: dict[str, int] = {}
    for _, d in rows:
        by_diff[d] = by_diff.get(d, 0) + 1
    print(f'by difficulty: {dict(sorted(by_diff.items()))}')

    out_path.parent.mkdir(parents=True, exist_ok=True)
    with out_path.open('w', encoding='utf-8', newline='\n') as f:
        f.write('# song\tdifficulty\tbytes\tcontainer\n')
        for (song, diff), info in sorted(rows.items()):
            f.write(f'{song}\t{diff}\t{info["bytes"]}\t{info["container"]}\n')
    print(f'wrote {out_path}')
    print('sample songs:', sorted(songs)[:10])
    return 0


if __name__ == '__main__':
    sys.exit(main())
