"""Every Activity the code launches must be declared in the APK manifest.

SkinActivity and SaveTransferActivity both existed as classes and were opened from the
panel, but neither was declared, so launching them threw ActivityNotFoundException - the
"导入皮肤" button crashed and save transfer could not open at all. This check compares the
activities the sources start against the manifest, so a new screen cannot ship undeclared.
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

REPO = Path(r'I:\dsh\work\phigros-offline-lab')
SRC = REPO / 'src'


def launched_activities() -> set[str]:
    """Fully-qualified activity class names passed to Intent(context, X.class)."""
    pattern = re.compile(r'new\s+android\.content\.Intent\s*\(\s*[A-Za-z_][\w.]*\s*,\s*([A-Za-z_][\w.]*)\.class\s*\)'
                         r'|new\s+Intent\s*\(\s*[A-Za-z_][\w.]*\s*,\s*([A-Za-z_][\w.]*)\.class\s*\)')
    names = set()
    for source in SRC.rglob('*.java'):
        text = source.read_text(encoding='utf-8', errors='replace')
        for m in pattern.finditer(text):
            simple = m.group(1) or m.group(2)
            names.add(simple)
    return names


def declared_activities(manifest: Path) -> set[str]:
    """Activity names from a decoded (text) manifest."""
    text = manifest.read_text(encoding='utf-8', errors='replace')
    return set(re.findall(r'<activity[^>]*android:name="([^"]+)"', text))


def main() -> int:
    try:
        sys.stdout.reconfigure(encoding='utf-8', errors='replace')
    except Exception:
        pass

    launched = launched_activities()
    print(f'activities launched from sources: {sorted(launched)}')

    decoded = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(r'I:\dsh\work\v183-manifest\manifest-decoded\AndroidManifest.xml')
    if not decoded.is_file():
        print(f'decoded manifest not found: {decoded}')
        print('run tools/register_activities.py first')
        return 2
    declared = declared_activities(decoded)
    print(f'activities declared in manifest: {len(declared)}')

    missing = []
    for simple in launched:
        # Accept either the fully-qualified name or one ending in .<Simple>.
        if not any(d == simple or d.endswith('.' + simple) for d in declared):
            missing.append(simple)

    if missing:
        for name in sorted(missing):
            print(f'FAIL {name} is launched but not declared in the manifest')
        return 1
    print('PASS every launched activity is declared')
    return 0


if __name__ == '__main__':
    sys.exit(main())
