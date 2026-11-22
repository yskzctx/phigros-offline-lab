"""Compare the 4.1.0 chart payloads against the 157 catalog to size the difference.

Chart JSON is extractable (Chart_EZ/HD/IN/AT TextAssets), so the number of charts a build
carries is directly countable even though their song ids are not in the payload. That
gives a defensible answer to "how many charts does 4.1.0 have" and therefore whether the
157 catalog (1037 charts / 326 songs) is short.
"""
from __future__ import annotations

import sys
from pathlib import Path

import UnityPy

AA = Path(r'I:\dsh\work\410-dec\assets\aa\Android')


def main() -> int:
    try:
        sys.stdout.reconfigure(encoding='utf-8', errors='replace')
    except Exception:
        pass

    charts = 0
    by_diff: dict[str, int] = {}
    bundles_with_chart = 0
    for path in sorted(AA.glob('*.bundle')):
        try:
            env = UnityPy.load(str(path))
        except Exception:
            continue
        found = 0
        for obj in env.objects:
            if obj.type.name != 'TextAsset':
                continue
            try:
                name = obj.read().m_Name or ''
            except Exception:
                continue
            if name.startswith('Chart_'):
                diff = name.split('_', 1)[1]
                by_diff[diff] = by_diff.get(diff, 0) + 1
                charts += 1
                found += 1
        if found:
            bundles_with_chart += 1

    print(f'4.1.0 charts found: {charts}')
    print(f'  by difficulty: {dict(sorted(by_diff.items()))}')
    print(f'  bundles carrying a chart: {bundles_with_chart}')
    print()
    print(f'157 catalog: 1037 charts / 326 songs')
    print(f'difference:  {charts - 1037:+d} charts')
    return 0


if __name__ == '__main__':
    sys.exit(main())
