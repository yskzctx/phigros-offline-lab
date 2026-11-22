"""Cross-check profiles/161.json against the audited hex offsets.

The profile stores decimal RVAs (JSON has no hex literals), so a transcription error
is easy to make and invisible on inspection. This compares every value against the
hex table the audit produced.
"""
import json
from pathlib import Path

EXPECTED = {
    'click': 0x1D84954,
    'drag': 0x1D85100,
    'flick': 0x1D85700,
    'hold': 0x1D86390,
    'record': 0x1CBB6BC,
    'perfect': 0x1D8A0A4,
    'result': 0x1D88BC8,
    'miss': 0x1D89CEC,
    'good': 0x1D89EC0,
    'skinClickControl$$NoteMove': 0x1D846A0,
    'skinDragControl$$NoteMove': 0x1D84E84,
    'skinFlickControl$$NoteMove': 0x1D85484,
    'skinHoldControl$$NoteMove': 0x1D85B54,
}

profile = json.loads(Path('profiles/161.json').read_text(encoding='utf-8'))
hooks = profile['hooks']

print(f'complete: {profile["complete"]}')
print(f'hooks: {len(hooks)}')

problems = []
for name, expected in EXPECTED.items():
    if name not in hooks:
        problems.append(f'{name}: missing from profile')
        continue
    got = hooks[name]['rva']
    if got != expected:
        problems.append(f'{name}: profile 0x{got:x} != audited 0x{expected:x}')

extra = set(hooks) - set(EXPECTED)
if extra:
    problems.append(f'unexpected hooks: {sorted(extra)}')

values = [hook['rva'] for hook in hooks.values()]
if len(values) != len(set(values)):
    problems.append('duplicate rva values')

for name, hook in hooks.items():
    if hook.get('confidence') != 'confirmed':
        problems.append(f'{name}: confidence is {hook.get("confidence")!r}, not confirmed')

print()
if problems:
    for problem in problems:
        print(f'FAIL {problem}')
    raise SystemExit(1)
print(f'PASS all {len(EXPECTED)} hooks match the audited offsets, all unique, all confirmed')
