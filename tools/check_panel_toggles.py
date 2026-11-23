"""Static checks that the panel toggles stay wired.

Regression guard for the exact defects reported:
  * force-enabled must have an onchange (otherwise toggling it does nothing and the next state
    push reverts it);
  * the auto checkbox must not set its own disabled=true (that left it unclickable when no state
    push arrived, e.g. while the module's autoplay toggle failed);
  * saveForce must actually send force-result.
"""
import sys
from pathlib import Path

PANELS = [
    ('offline/Phigros', Path(r'I:\dsh\work\phigros-offline-lab\ui\controls-ui.html')),
    ('Phira', Path(r'I:\dsh\work\phira-lsposed\assets\controls-ui.html')),
]


def main() -> int:
    try:
        sys.stdout.reconfigure(encoding='utf-8', errors='replace')
    except Exception:
        pass
    problems = []
    for label, path in PANELS:
        if not path.is_file():
            problems.append(f'{label}: missing {path}')
            continue
        s = path.read_text(encoding='utf-8')
        checks = [
            ("force toggle has onchange", "$('force-enabled').onchange" in s),
            ("saveForce sends force-result", "send('force-result',v)" in s),
            ("auto toggle does not self-disable", "$('auto').disabled=true" not in s),
            ("auto toggle has onchange", "$('auto').onchange" in s),
        ]
        for name, ok in checks:
            if not ok:
                problems.append(f'{label}: {name} FAILED')
        print(f'  {"PASS" if all(ok for _, ok in checks) else "FAIL"} {label}')
    print()
    if problems:
        for p in problems:
            print('FAIL', p)
        return 1
    print('PASS panel toggles are wired')
    return 0


if __name__ == '__main__':
    sys.exit(main())
