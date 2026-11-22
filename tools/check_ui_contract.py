"""Verify the redesigned panel still satisfies the JavaScript id contract.

A redesign that drops an id breaks the wiring silently at runtime, so this asserts every id
the script block references exists in the markup, and reports the ones that are gone.
"""
import re
from pathlib import Path

UI = Path(r'I:\dsh\work\phigros-offline-lab\ui\controls-ui.html')
s = UI.read_text(encoding='utf-8')
start = s.find('<script>')
markup, js = s[:start], s[start:]

ids = {m.group(1) for m in re.finditer(r"""\$\(\s*['"]([A-Za-z0-9_\-]+)['"]\s*\)""", js)}
present = set(re.findall(r'\bid="([A-Za-z0-9_\-]+)"', markup))

missing = sorted(ids - present)
print(f'js ids: {len(ids)}   markup ids: {len(present)}')
if missing:
    print(f'FAIL ids referenced but absent from markup: {missing}')
    raise SystemExit(1)
print('PASS every js-referenced id exists in the markup')

extra = sorted(present - ids)
if extra:
    print(f'note: markup-only ids ({len(extra)}): {extra[:10]}')

# Also confirm the tab targets exist.
tabs = re.findall(r'data-tab="([\w-]+)"', markup)
pages = re.findall(r'<section id="([\w-]+)" class="page', markup)
print(f'\ntabs : {tabs}')
print(f'pages: {pages}')
for tab in tabs:
    if tab not in pages:
        print(f'FAIL tab {tab} has no page')
        raise SystemExit(1)
print('PASS every tab has a matching page')
