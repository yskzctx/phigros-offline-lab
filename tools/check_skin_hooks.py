"""Verify every skin hook prefix actually matches the code at its RVA.

skin_worker() checks all four hook prefixes and sets skin_ready = -1 on the first mismatch,
so a single stale RVA disables custom skins entirely - which is exactly "import works but the
skin never appears after restart". This checks the shipped prefixes against the 157 build the
RVA list was derived from.
"""
import re
from pathlib import Path

SO = Path(r'I:\dsh\work\audit\libil2cpp.so')
HEADER = Path(r'I:\dsh\work\phigros-offline-lab\src\skin-hooks.h')

ENTRY = re.compile(r'\{"([^"]+)",\s*0x([0-9a-fA-F]+),\s*\{([^}]*)\},\s*(\d+)\}')

data = SO.read_bytes()
entries = ENTRY.findall(HEADER.read_text(encoding='utf-8'))
print(f'skin hooks declared: {len(entries)}')

bad = 0
for name, rva_hex, prefix_text, mask in entries:
    rva = int(rva_hex, 16)
    prefix = bytes(int(v.strip(), 16) for v in prefix_text.split(','))
    actual = data[rva:rva + 16]
    m = int(mask)
    shown = 16 if m == 0 else max(1, m // 8)
    same = prefix[:shown] == actual[:shown]
    if not same:
        bad += 1
    print(f'  {"OK  " if same else "BAD "} {name:22} 0x{rva:x}')
    if not same:
        print(f'        header={prefix[:8].hex()}  actual={actual[:8].hex()}')

print()
if bad:
    print(f'FAIL {bad}/{len(entries)} skin hook prefixes do not match -> skins disabled at runtime')
    raise SystemExit(1)
print('PASS every skin hook prefix matches')
