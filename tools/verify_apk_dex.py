"""Verify the rebuilt APK keeps the game's code and adds the modification beside it.

The launch crash was caused by writing the freshly compiled classes over classes.dex,
which in this base holds the game itself. These assertions make that specific mistake
impossible to miss: the game dex must be byte-identical to the base, and the
modification must be present in classes2.dex.
"""
import hashlib
import sys
import zipfile

BASE = r'C:\Users\yskzc\Desktop\Phigros-4.0.1-157-offline-arm64-controls-v165.apk'
NEW = r'I:\dsh\work\release\v181-fix\Phigros-offline-v181.apk'

# Strings that must appear in the game dex and the modification dex respectively.
GAME_MARKERS = [b'com/PigeonGames', b'UnityPlayer', b'com/unity3d']
MOD_MARKERS = [b'com/phigros/offline', b'ControlsConfig']


def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def main() -> int:
    problems = []
    with zipfile.ZipFile(BASE) as base, zipfile.ZipFile(NEW) as new:
        base_names = set(base.namelist())
        new_names = set(new.namelist())

        print(f'{"entry":<18}{"base":>12}{"new":>12}  identical?')
        for name in ('classes.dex', 'classes2.dex'):
            if name not in base_names:
                print(f'{name:<18}{"-":>12}{"-":>12}  absent in base')
                continue
            base_data = base.read(name)
            new_data = new.read(name) if name in new_names else b''
            same = digest(base_data) == digest(new_data)
            print(f'{name:<18}{len(base_data):>12}{len(new_data):>12}  {same}')
            if name == 'classes.dex' and not same:
                problems.append('classes.dex changed; the game code was overwritten')

        game_dex = new.read('classes.dex')
        mod_dex = new.read('classes2.dex') if 'classes2.dex' in new_names else b''

        print('\nmarkers:')
        for marker in GAME_MARKERS:
            present = marker in game_dex
            print(f'  game  {marker.decode():<20} {present}')
            if not present:
                problems.append(f'game dex lost {marker!r}')
        for marker in MOD_MARKERS:
            present = marker in mod_dex
            print(f'  mod   {marker.decode():<20} {present}')
            if not present:
                problems.append(f'modification dex missing {marker!r}')

        # The modification must not have leaked into the game dex. Compare against the
        # base rather than asserting absence: the base already ships a minimal
        # com/phigros/offline package (the entry activity), so a bare string check
        # would flag correct builds.
        if 'classes.dex' in base_names:
            base_dex = base.read('classes.dex')
            for marker in MOD_MARKERS:
                if marker in game_dex and marker not in base_dex:
                    problems.append(f'modification code leaked into classes.dex: {marker!r}')

    print()
    if problems:
        for problem in problems:
            print(f'FAIL {problem}')
        return 1
    print('PASS game code intact in classes.dex, modification in classes2.dex')
    return 0


if __name__ == '__main__':
    sys.exit(main())
