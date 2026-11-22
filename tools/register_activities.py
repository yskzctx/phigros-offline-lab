"""Register the offline build's own activities in the APK manifest.

Two activities exist in the Java sources but were never declared in the manifest:
SkinActivity (the skin picker the panel opens) and SaveTransferActivity (save import /
export). Launching an undeclared activity throws ActivityNotFoundException, so "导入皮肤"
crashed and save transfer could not work at all.

The base manifest is binary. aapt2 cannot round-trip it to text, so the activities are
added with apktool: decode the base, insert the <activity> elements, rebuild, and hand the
rebuilt manifest back for the assembler to use.
"""
from __future__ import annotations

import shutil
import subprocess
import sys
import zipfile
from pathlib import Path

JAVA = Path(r'C:\Users\yskzc\Android\jdk17\jdk-17.0.20+8\bin\java.exe')
APKTOOL = Path(r'I:\dsh\work\tools\apktool.jar')

ACTIVITIES = [
    # name, extra attributes, exported
    ('com.phigros.offline.SkinActivity', 'android:label="音符皮肤"', 'false'),
    ('com.phigros.offline.SaveTransferActivity', 'android:label="导入导出存档"', 'false'),
]

INSERT = '''
        <activity android:name="{name}" android:exported="{exported}"
            android:theme="@android:style/Theme.Material.Light.NoActionBar"
            android:configChanges="orientation|screenSize|keyboardHidden" {extra}>
        </activity>
'''


def run(cmd: list[str], cwd: Path | None = None) -> None:
    result = subprocess.run([str(c) for c in cmd], capture_output=True, text=True,
                            errors='replace', cwd=cwd)
    if result.returncode != 0:
        raise SystemExit(f'command failed: {cmd[0]}\n{result.stdout}\n{result.stderr}')


def main() -> int:
    if len(sys.argv) < 3:
        print('usage: register_activities.py <base.apk> <work-dir>')
        return 2
    base = Path(sys.argv[1])
    work = Path(sys.argv[2])
    decoded = work / 'manifest-decoded'
    if decoded.is_dir():
        shutil.rmtree(decoded)

    print(f'decoding {base.name} (resources only)...')
    run([JAVA, '-Xmx4g', '-jar', APKTOOL, 'd', '-f', '-s', '-o', decoded, base])

    manifest = decoded / 'AndroidManifest.xml'
    text = manifest.read_text(encoding='utf-8')

    added = []
    for name, extra, exported in ACTIVITIES:
        if f'android:name="{name}"' in text:
            print(f'  already declared: {name}')
            continue
        block = INSERT.format(name=name, exported=exported, extra=extra)
        # Insert before the closing </application>.
        marker = '    </application>'
        if marker not in text:
            raise SystemExit('unexpected manifest layout: no </application>')
        text = text.replace(marker, block + marker, 1)
        added.append(name)

    if not added:
        print('nothing to add')
        return 0

    manifest.write_text(text, encoding='utf-8', newline='\n')
    print(f'added: {added}')

    print('rebuilding manifest...')
    run([JAVA, '-Xmx4g', '-jar', APKTOOL, 'b', '-o', work / 'rebuilt.apk', decoded])

    # Pull the rebuilt binary manifest out for the assembler.
    rebuilt = work / 'rebuilt.apk'
    with zipfile.ZipFile(rebuilt) as z:
        data = z.read('AndroidManifest.xml')
    out = work / 'AndroidManifest.xml'
    out.write_bytes(data)
    print(f'wrote {out} ({len(data)} bytes)')
    return 0


if __name__ == '__main__':
    sys.exit(main())
