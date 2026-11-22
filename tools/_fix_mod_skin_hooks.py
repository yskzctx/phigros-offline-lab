"""Repair the module's skin-hooks.h prefixes (the offline copy was fixed, this one was not).

The four NoteMove prefixes were recorded from a wrong source, so skin_worker() aborts on the
first mismatch and custom skins never install in the module - import succeeds, restart keeps the
original art. The offline build was fixed earlier; this applies the same correction here, taking
each prefix from the actual bytes at the audited RVA.
"""
import json
import re
from pathlib import Path

REPO = Path(r'I:\dsh\work\phigros-offline-lab')
MODULE = Path(r'I:\dsh\work\phigros-lsposed')
DATA = Path(r'I:\dsh\work\audit\libil2cpp.so').read_bytes()
PATH = MODULE / 'native' / 'skin-hooks.h'

profile = json.loads((REPO / 'profiles' / '157.json').read_text(encoding='utf-8'))
order = ['skinClickControl$$NoteMove', 'skinDragControl$$NoteMove',
         'skinFlickControl$$NoteMove', 'skinHoldControl$$NoteMove']

text = PATH.read_text(encoding='utf-8')
start = text.find('static const struct HookSpec skin_hooks[]={')
if start < 0:
    raise SystemExit('skin_hooks table not found')
open_brace = text.find('{', start + len('static const struct HookSpec skin_hooks[]='))
close = text.find('};', open_brace)
if close < 0:
    raise SystemExit('table end not found')

lines = []
for name in order:
    spec = profile['hooks'][name]
    rva = int(spec['rva'])
    prefix = DATA[rva:rva + 16]
    mask = int(spec.get('relocateMask', 0))
    body = ','.join(f'0x{b:02x}' for b in prefix)
    lines.append(f'{{"{name}",0x{rva:x},{{{body}}},{mask}}},')
    print(f'{name:32} 0x{rva:x}  {prefix[:8].hex()}')

new_table = 'static const struct HookSpec skin_hooks[]={\n' + '\n'.join(lines) + '\n'
text = text[:start] + new_table + text[close:]
PATH.write_text(text, encoding='utf-8', newline='\n')
print(f'\nrewrote {PATH.name}')
