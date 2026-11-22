"""Check every panel's script block parses before it is shipped.

A SyntaxError in the script block leaves the panel visible but completely dead - buttons do
nothing, because no handler was ever attached. That is exactly what happened to the Phira panel
(a broken edit nested its tab handler), and it cannot be caught by checking ids alone.

This extracts each panel's <script> and runs `node --check` on it. Panels are skipped when node
is unavailable.
"""
import json
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

PANELS = [
    ('offline', Path(r'I:\dsh\work\phigros-offline-lab\ui\controls-ui.html')),
    ('Phigros module', Path(r'I:\dsh\work\phigros-lsposed\assets\offline\controls-ui.html')),
    ('Phira module', Path(r'I:\dsh\work\phira-lsposed\assets\controls-ui.html')),
]


def main() -> int:
    try:
        sys.stdout.reconfigure(encoding='utf-8', errors='replace')
    except Exception:
        pass

    node = shutil.which('node') or r'C:\Program Files\nodejs\node.exe'
    if not Path(node).is_file():
        print('SKIP node unavailable; cannot check script syntax')
        return 0

    problems = []
    for label, path in PANELS:
        if not path.is_file():
            problems.append(f'{label}: panel missing at {path}')
            continue
        html = path.read_text(encoding='utf-8')
        start = html.find('<script>')
        end = html.find('</script>', start)
        if start < 0 or end < 0:
            problems.append(f'{label}: no script block')
            continue
        js = html[start + len('<script>'):end]

        with tempfile.NamedTemporaryFile('w', suffix='.js', delete=False,
                                         encoding='utf-8', newline='\n') as handle:
            handle.write(js)
            temp = Path(handle.name)
        try:
            result = subprocess.run([node, '--check', str(temp)],
                                    capture_output=True, text=True, errors='replace')
        finally:
            temp.unlink(missing_ok=True)

        if result.returncode != 0:
            first = (result.stderr or '').strip().splitlines()
            detail = first[0] if first else 'syntax error'
            problems.append(f'{label}: {detail}')
            print(f'  FAIL {label}: script does not parse')
            for line in first[:3]:
                print(f'       {line}')
        else:
            print(f'  PASS {label}: script parses ({len(js)} bytes)')

    print()
    if problems:
        for problem in problems:
            print(f'FAIL {problem}')
        return 1
    print('PASS every panel script parses')
    return 0


if __name__ == '__main__':
    sys.exit(main())
