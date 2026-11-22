"""The module must never hide or block the game's login.

Login hiding belongs to the standalone offline build only: that build has no INTERNET
permission, so hiding the login surface is consistent with it being offline. The module
runs inside the user's unmodified original game, where the account, cloud sync and login
are all expected to keep working - injecting a login-block there would break the real game.

This guards the boundary in the built module APK rather than in a comment, so a future sync
from the offline repo cannot quietly carry the privacy hooks across.

What it checks:
  * no libphigros_privacy.so is shipped (that library is what installs the hooks)
  * the native library exports no privacy entry points
  * the module sources contain no reference to the privacy hook table
"""
from __future__ import annotations

import re
import sys
import zipfile
from pathlib import Path

MODULE_DIR = Path(r'I:\dsh\work\phigros-phira-lsposed-lab\modules\phigros')
RELEASE_DIR = Path(r'I:\dsh\work\release\modules')

# Names that only exist to suppress the login surface.
FORBIDDEN_SYMBOLS = (
    'privacy_hooks',
    'hide_account',
    'login_hidden',
    'StatsFrameController',
    'privacy_worker',
)
FORBIDDEN_LIBS = ('libphigros_privacy.so',)


def newest_module_apk() -> Path | None:
    if not RELEASE_DIR.is_dir():
        return None
    candidates = sorted(RELEASE_DIR.glob('Phigros-Lab-LSPosed-*.apk'),
                        key=lambda p: p.stat().st_mtime, reverse=True)
    return candidates[0] if candidates else None


def main() -> int:
    try:
        sys.stdout.reconfigure(encoding='utf-8', errors='replace')
    except Exception:
        pass

    problems = []

    # ---- 1. sources -------------------------------------------------------
    offenders = []
    for source in MODULE_DIR.rglob('*'):
        if not source.is_file() or source.suffix not in ('.c', '.h', '.java'):
            continue
        text = source.read_text(encoding='utf-8', errors='replace')
        for symbol in FORBIDDEN_SYMBOLS:
            if re.search(rf'\b{re.escape(symbol)}\b', text):
                offenders.append(f'{source.name}: {symbol}')
    if offenders:
        problems.append('module sources reference login-hiding symbols: '
                        + ', '.join(offenders[:5]))
    else:
        print(f'  PASS module sources carry no login-hiding symbols')

    # ---- 2. built APK -----------------------------------------------------
    apk = newest_module_apk()
    if apk is None:
        print('  SKIP no built module APK to inspect')
    else:
        with zipfile.ZipFile(apk) as archive:
            names = archive.namelist()
            shipped = [n for n in names if n.endswith(FORBIDDEN_LIBS)]
            if shipped:
                problems.append(f'{apk.name} ships {shipped}')
            else:
                print(f'  PASS {apk.name} ships no privacy library')
            # Inspect the native libraries for the forbidden symbols.
            hits = []
            for name in names:
                if not name.endswith('.so'):
                    continue
                payload = archive.read(name)
                for symbol in FORBIDDEN_SYMBOLS:
                    if symbol.encode() in payload:
                        hits.append(f'{name}: {symbol}')
            if hits:
                problems.append(f'{apk.name} exports login-hiding symbols: {hits[:5]}')
            else:
                print(f'  PASS {apk.name} native libraries export no login-hiding symbols')

    print()
    if problems:
        for problem in problems:
            print(f'FAIL {problem}')
        return 1
    print('PASS the module never hides or blocks login')
    return 0


if __name__ == '__main__':
    sys.exit(main())
