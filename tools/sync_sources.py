#!/usr/bin/env python3
"""Synchronise shared sources between the offline build and the LSPosed module.

Three copies of the same Java/native/HTML sources exist across the two repositories:

    src/                                  canonical, used by the offline APK
    chapter-player/android-source/        generated copy (historically drifted)
    modules/phigros/src|native|assets/    module build input

They drifted before: `chapter-player/android-source/controls-ui.html` still had the
old two-mode UI after `ui/controls-ui.html` gained the guard mode, and
`source-manifest.json` still said candidate 177 while the code was 180.

IMPORTANT: the module is not a straight copy of the offline sources. Several files
carry module-only behaviour that must never be overwritten:

    LaunchActivity.java   module session adapter (no Unity launch, no progress dialog)
    OverlayMenu.java      routes through LabContext and ModuleScreens
    RksEditor.java        adds unlockEntries / unlock_all_pending
    OfflineApplication / OfflineAssets / OfflineHttp / UsageNotice
                          module-specific host integration

A first attempt to copy everything broke those files, so the module copy is now an
explicit allowlist: only files whose content is genuinely identical in both products
are synchronised, and everything else is reported for a human to merge.

Usage:
    python sync_sources.py --check     # fail if any copy is stale (CI mode)
    python sync_sources.py             # rewrite the stale copies
"""
from __future__ import annotations

import argparse
import hashlib
import json
import shutil
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
MODULE_REPO = REPO.parent / 'phigros-phira-lsposed-lab'
MODULE = MODULE_REPO / 'modules' / 'phigros'

# Files whose content is shared verbatim with the module.
JAVA_SHARED_WITH_MODULE = [
    'ControlsConfig.java', 'ForcedResult.java', 'NativeControls.java',
    'SkinPack.java', 'SkinController.java', 'SkinActivity.java', 'SkinStyle.java',
    'RksPlanner.java',
    'CatalogXmlMerge.java', 'ChapterFiles.java', 'ChapterLibraryActivity.java',
    'ChapterPack.java', 'ChapterPlayerActivity.java', 'ChapterProcess.java',
    'ChapterSkinExport.java',
]
NATIVE_SHARED_WITH_MODULE = ['controls-plan.h', 'expected-hooks.h', 'offline.c']

# Files that exist in the module with intentional differences; never overwritten.
JAVA_MODULE_SPECIFIC = [
    'LaunchActivity.java', 'OverlayMenu.java', 'RksEditor.java',
    'OfflineApplication.java', 'OfflineAssets.java', 'OfflineHttp.java',
    'UsageNotice.java',
]
NATIVE_MODULE_SPECIFIC = [
    'skins.c',          # module loads via its own native hook batch
    'skin-hooks.h', 'skin-scale.h', 'native-hook-impl.h',
    'privacy.c', 'privacy-hooks.h',
]

JAVA_ALL = JAVA_SHARED_WITH_MODULE + JAVA_MODULE_SPECIFIC
NATIVE_ALL = ['controls-plan.h', 'expected-hooks.h', 'native-hook-impl.h', 'offline.c',
              'privacy-hooks.h', 'privacy.c', 'skin-hooks.h', 'skin-scale.h', 'skins.c']
QUAD = ['QuadNative.java']


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def java_source(name: str) -> Path:
    return REPO / 'src' / 'com' / 'phigros' / 'offline' / name


def native_source(name: str) -> Path:
    return REPO / 'src' / name


def chapter_targets() -> list[tuple[Path, Path]]:
    """chapter-player/android-source is a pure generated mirror of the offline repo."""
    chapter = REPO / 'chapter-player' / 'android-source'
    pairs: list[tuple[Path, Path]] = []
    for name in JAVA_ALL:
        source = java_source(name)
        if source.is_file():
            pairs.append((source, chapter / 'com' / 'phigros' / 'offline' / name))
    for name in NATIVE_ALL:
        source = native_source(name)
        if source.is_file():
            pairs.append((source, chapter / 'native' / name))
    for name in QUAD:
        source = chapter / 'quad_native' / name
        if source.is_file():
            pairs.append((source, chapter / 'quad_native' / name))
    ui = REPO / 'ui' / 'controls-ui.html'
    if ui.is_file():
        pairs.append((ui, chapter / 'controls-ui.html'))
    return pairs


def module_targets() -> list[tuple[Path, Path]]:
    """Only the allowlisted, genuinely shared files are copied into the module."""
    if not MODULE.is_dir():
        return []
    pairs: list[tuple[Path, Path]] = []
    for name in JAVA_SHARED_WITH_MODULE:
        source = java_source(name)
        if source.is_file():
            pairs.append((source, MODULE / 'src' / 'com' / 'phigros' / 'offline' / name))
    for name in NATIVE_SHARED_WITH_MODULE:
        source = native_source(name)
        if source.is_file():
            pairs.append((source, MODULE / 'native' / 'core' / name))
    ui = REPO / 'ui' / 'controls-ui.html'
    if ui.is_file():
        pairs.append((ui, MODULE / 'assets' / 'offline' / 'controls-ui.html'))
    return pairs


def report_divergence() -> list[str]:
    """List module-specific files so a reviewer knows what still needs merging."""
    notes = []
    for name in JAVA_MODULE_SPECIFIC:
        target = MODULE / 'src' / 'com' / 'phigros' / 'offline' / name
        source = java_source(name)
        if target.is_file() and source.is_file() and digest(source) != digest(target):
            notes.append(f'  module-only differences: {name}')
    for name in NATIVE_MODULE_SPECIFIC:
        target = MODULE / 'native' / 'core' / name
        source = native_source(name)
        if target.is_file() and source.is_file() and digest(source) != digest(target):
            notes.append(f'  module-only differences: native/{name}')
    return notes


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--check', action='store_true', help='report drift and exit non-zero instead of writing')
    args = parser.parse_args()

    stale, missing, written = [], [], []
    for source, target in chapter_targets() + module_targets():
        if not source.is_file():
            missing.append(str(source.relative_to(REPO)) if REPO in source.parents else str(source))
            continue
        if not target.is_file():
            missing.append(str(target))
            if not args.check:
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(source, target)
                written.append(str(target))
            continue
        if digest(source) != digest(target):
            stale.append((source, target))
            if not args.check:
                shutil.copy2(source, target)
                written.append(str(target))

    if missing:
        print('missing files:')
        for item in missing:
            print(f'  {item}')

    if stale:
        print(f'{len(stale)} stale cop{"y" if len(stale) == 1 else "ies"}:')
        for source, target in stale:
            print(f'  {target}')
    else:
        print('all synchronised copies match their canonical sources')

    notes = report_divergence()
    if notes:
        print()
        print('intentionally NOT synchronised (merge by hand when behaviour changes):')
        for note in notes:
            print(note)

    if args.check:
        return 1 if (stale or missing) else 0

    if written:
        print()
        print(f'updated {len(written)} file(s)')
        update_manifest()
    return 0


def update_manifest() -> None:
    """Refresh the hash manifest so it can never claim a stale candidate again."""
    manifest_path = REPO / 'chapter-player' / 'android-source' / 'source-manifest.json'
    if not manifest_path.is_file():
        return
    manifest = json.loads(manifest_path.read_text(encoding='utf-8'))
    manifest['java_sources'] = {
        f'com/phigros/offline/{name}': digest(java_source(name))
        for name in JAVA_ALL if java_source(name).is_file()
    }
    manifest['native_sources'] = {
        f'native/{name}': digest(native_source(name))
        for name in NATIVE_ALL if native_source(name).is_file()
    }
    ui = REPO / 'ui' / 'controls-ui.html'
    if ui.is_file():
        manifest['controls_ui_sha256'] = digest(ui)
    manifest_path.write_text(json.dumps(manifest, indent=2) + '\n', encoding='utf-8')
    print(f'refreshed {manifest_path.relative_to(REPO)}')


if __name__ == '__main__':
    sys.exit(main())
