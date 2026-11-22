#!/usr/bin/env python3
"""Assemble the modified offline Phigros APK from a verified base APK.

The repository shipped built APKs and the Java/native sources, but no script that
turns those sources back into an installable package. Every release was therefore
built by an undocumented manual procedure, which is why `source-manifest.json`
could claim one candidate while the code was another.

This script performs the whole assembly deterministically:

  1. verify the base APK against a known SHA-256 (the base carries the game's own
     resources; using an unverified one would silently ship altered assets),
  2. compile the Java sources with javac against the platform android.jar,
  3. dex them with d8,
  4. cross-compile the native libraries with the NDK,
  5. repack the base APK with the new dex, natives and assets,
  6. zipalign to 16 KB page alignment and sign,
  7. verify the signature, the alignment, the absence of INTERNET permission and
     that the game's own engine/resources were not replaced,
  8. emit SHA256SUMS plus a verification JSON describing what was checked.

Copy-first is inherent: the base APK is opened read-only and the output is a new
file, so a failed run can never damage the input.

Usage:
    python tools/assemble_offline_apk.py \
        --base path/to/base.apk \
        --out out/ \
        --keystore path/to/key.jks --alias NAME --password-file path/to/pw.txt \
        --version-code 181 --version-name 4.0.1

Use --check-only to run every validation without writing a package.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import struct
import subprocess
import sys
import tempfile
import zipfile
import zlib
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
PACKAGE = 'com.PigeonGames.Phigros.offline'

# Base APKs that are known to carry unmodified game resources. A base outside this
# list is refused unless --allow-unknown-base is given, because replacing assets
# from an unverified source is exactly the mistake this check exists to prevent.
KNOWN_BASES = {
    # v165 offline base, the last one present on this machine.
    '79aa992cd7157e94d441a9bfa7c95d2e42090afb4e42f8c094dbbec5cb208d0d': 'offline v165 base (4.0.1/157)',
}

# Entries that must come from the base untouched. Replacing any of these would
# change the game itself rather than the wrapper around it.
#
# classes.dex belongs here: in this base the game's own code lives in classes.dex and
# the modification lives in classes2.dex. An earlier version of this script wrote the
# freshly compiled classes over classes.dex, which deleted the entire game - the APK
# installed and then crashed on launch, and nothing in the report noticed because the
# protected list did not cover dex files.
PROTECTED_PREFIXES = ('assets/bin/Data/', 'assets/aa/')
PROTECTED_SUFFIXES = ('libil2cpp.so', 'libunity.so', 'classes.dex')

NATIVE_TARGETS = [
    # (output name inside lib/arm64-v8a, source file, extra defines)
    # Every library the Java layer loads must be built here or the app dies at
    # startup with UnsatisfiedLinkError: OfflineApplication loads phigros_privacy,
    # SkinController loads phigros_skins, LaunchActivity loads phigros_offline.
    ('libphigros_offline.so', 'src/offline.c', []),
    ('libphigros_privacy.so', 'src/privacy.c', []),
    ('libphigros_skins.so', 'src/skins.c', []),
]
def asset_sources(catalog: Path | None = None) -> list[tuple[str, str]]:
    """Repo-relative sources copied into assets/offline/.

    The chart catalog decides which songs the unlock/RKS features can see, so it is
    selectable: the default is the 157 table, and --catalog picks another (the 4.1.0 table
    is data/chart-catalog-410.tsv, regenerated from that build's Addressables keys).
    """
    catalog_path = catalog if catalog is not None else REPO / 'data' / 'chart-catalog.tsv'
    try:
        catalog_rel = str(catalog_path.resolve().relative_to(REPO.resolve()))
    except ValueError:
        catalog_rel = str(catalog_path)
    return [
        ('ui/controls-ui.html', 'assets/offline/controls-ui.html'),
        (catalog_rel, 'assets/offline/chart-catalog.tsv'),
    ]


def digest(path: Path) -> str:
    with path.open('rb') as handle:
        return hashlib.file_digest(handle, 'sha256').hexdigest()


def run(cmd, cwd=None, env=None):
    printable = ' '.join(str(part) for part in cmd)
    result = subprocess.run([str(part) for part in cmd], cwd=cwd, env=env,
                            capture_output=True, text=True, errors='replace')
    if result.returncode != 0:
        raise SystemExit(f'command failed ({result.returncode}): {printable}\n'
                         f'{result.stdout}\n{result.stderr}')
    return result.stdout + result.stderr


def find_sdk_tool(sdk: Path, build_tools: str, name: str) -> Path:
    candidate = sdk / 'build-tools' / build_tools / name
    if candidate.is_file():
        return candidate
    for directory in sorted((sdk / 'build-tools').iterdir(), reverse=True):
        candidate = directory / name
        if candidate.is_file():
            return candidate
    raise SystemExit(f'{name} not found under {sdk / "build-tools"}')


def find_ndk_compiler(sdk: Path, ndk_version: str) -> Path:
    host = 'windows-x86_64' if os.name == 'nt' else 'linux-x86_64'
    suffix = '.cmd' if os.name == 'nt' else ''
    ndk_root = sdk / 'ndk' / ndk_version
    candidate = ndk_root / 'toolchains' / 'llvm' / 'prebuilt' / host / 'bin' / f'aarch64-linux-android26-clang{suffix}'
    if candidate.is_file():
        return candidate
    for directory in sorted((sdk / 'ndk').iterdir(), reverse=True):
        candidate = directory / 'toolchains' / 'llvm' / 'prebuilt' / host / 'bin' / f'aarch64-linux-android26-clang{suffix}'
        if candidate.is_file():
            return candidate
    raise SystemExit(f'NDK clang not found under {sdk / "ndk"}')


def base_inventory(apk: Path) -> dict:
    with zipfile.ZipFile(apk) as archive:
        names = archive.namelist()
        return {
            'entries': len(names),
            'protected': sorted(n for n in names
                                if n.startswith(PROTECTED_PREFIXES) or n.endswith(PROTECTED_SUFFIXES)),
            'has_internet': any('INTERNET' in n for n in names if n == 'AndroidManifest.xml'),
        }


def compile_java(sdk: Path, platform: str, work: Path) -> Path:
    javac = shutil.which('javac')
    if not javac:
        raise SystemExit('javac not found on PATH')
    android_jar = sdk / 'platforms' / platform / 'android.jar'
    if not android_jar.is_file():
        raise SystemExit(f'{android_jar} is missing')
    classes = work / 'classes'
    classes.mkdir(parents=True, exist_ok=True)
    sources = sorted(str(p) for p in (REPO / 'src' / 'com' / 'phigros' / 'offline').glob('*.java'))
    if not sources:
        raise SystemExit('no Java sources found')
    run([javac, '--release', '8', '-encoding', 'UTF-8', '-cp', android_jar, '-d', classes, *sources])
    return classes


def dex_classes(sdk: Path, build_tools: str, platform: str, classes: Path, work: Path) -> Path:
    d8_jar = find_sdk_tool(sdk, build_tools, 'lib/d8.jar')
    android_jar = sdk / 'platforms' / platform / 'android.jar'
    dex = work / 'dex'
    dex.mkdir(parents=True, exist_ok=True)
    class_files = sorted(str(p) for p in classes.rglob('*.class'))
    run(['java', f'-Djava.io.tmpdir={work}', '-cp', d8_jar, 'com.android.tools.r8.D8',
         '--min-api', '26', '--lib', android_jar, '--output', dex, *class_files])
    return dex / 'classes.dex'


def build_native(sdk: Path, ndk_version: str, work: Path) -> dict[str, Path]:
    compiler = find_ndk_compiler(sdk, ndk_version)
    natives = {}
    for name, source, defines in NATIVE_TARGETS:
        output = work / name
        command = [compiler, '-shared', '-fPIC', '-O2', '-std=c11', '-Wall', '-Wextra', '-Werror',
                   '-Wno-unused-function', '-Wno-unused-variable', '-fstack-protector-strong',
                   '-Wl,-z,max-page-size=16384', '-Wl,-z,relro,-z,now',
                   *defines, '-o', output, REPO / source, '-llog', '-ldl', '-lm']
        run(command)
        natives[name] = output
    return natives


def patch_manifest_version(manifest: bytes, version_code: int | None, version_name: str | None) -> bytes:
    """Rewrite android:versionCode / versionName in a binary AndroidManifest.

    aapt2 cannot dump a manifest to text, and rebuilding one from a rendered tree
    risks losing attributes it prints ambiguously. The attribute values are plain
    32-bit words in the manifest element, so patching them in place is a one-word
    change that leaves every other byte identical.

    Raises SystemExit if the expected attribute layout is not found, so a manifest
    from an unexpected base can never be silently half-patched.
    """
    if version_code is None and version_name is None:
        return manifest

    data = bytearray(manifest)
    RES_STRING_POOL_TYPE = 0x0001
    RES_XML_START_ELEMENT_TYPE = 0x0102

    offset = 8
    pool_start = None
    while offset + 8 <= len(data):
        chunk_type, _header_size, chunk_size = struct.unpack_from('<HHI', data, offset)
        if chunk_size < 8:
            break
        if chunk_type == RES_STRING_POOL_TYPE:
            pool_start = offset
            break
        offset += chunk_size
    if pool_start is None:
        raise SystemExit('manifest has no string pool')

    count = struct.unpack_from('<I', data, pool_start + 8)[0]
    flags = struct.unpack_from('<I', data, pool_start + 16)[0]
    strings_start = struct.unpack_from('<I', data, pool_start + 20)[0]
    offsets = [struct.unpack_from('<I', data, pool_start + 28 + i * 4)[0] for i in range(count)]
    utf8 = bool(flags & 0x100)

    def string_at(index: int) -> str:
        if index >= count:
            return ''
        position = pool_start + strings_start + offsets[index]
        if utf8:
            position += 2 if data[position] & 0x80 else 1
            length = data[position]
            position += 1
            return data[position:position + length].decode('utf-8', 'replace')
        length = struct.unpack_from('<H', data, position)[0]
        position += 2
        return data[position:position + length * 2].decode('utf-16-le', 'replace')

    offset = 8
    while offset + 8 <= len(data):
        chunk_type, _header_size, chunk_size = struct.unpack_from('<HHI', data, offset)
        if chunk_size < 8:
            break
        if chunk_type == RES_XML_START_ELEMENT_TYPE:
            header_size = struct.unpack_from('<H', data, offset + 2)[0]
            ext = offset + header_size
            name_index = struct.unpack_from('<I', data, ext + 4)[0]
            if string_at(name_index) == 'manifest':
                attr_start = struct.unpack_from('<H', data, ext + 8)[0]
                attr_size = struct.unpack_from('<H', data, ext + 10)[0]
                attr_count = struct.unpack_from('<H', data, ext + 12)[0]
                base = ext + attr_start
                for position in range(attr_count):
                    record = base + position * attr_size
                    name_idx = struct.unpack_from('<I', data, record + 4)[0]
                    attribute = string_at(name_idx)
                    if attribute == 'versionCode' and version_code is not None:
                        struct.pack_into('<I', data, record + 16, version_code)
                    elif attribute == 'versionName' and version_name is not None:
                        # versionName is a string reference; replacing the text in
                        # place is only safe at equal length, so refuse otherwise.
                        raw_index = struct.unpack_from('<I', data, record + 8)[0]
                        if raw_index >= count:
                            raise SystemExit('versionName has no string reference')
                        if string_at(raw_index) != version_name:
                            raise SystemExit(
                                f'cannot change versionName to {version_name!r} without '
                                f'rebuilding the manifest string pool; pass the base value '
                                f'or leave --version-name unset')
                return bytes(data)
        offset += chunk_size
    raise SystemExit('manifest element not found; refusing to patch a partial manifest')


def repack(base: Path, dex: Path, natives: dict[str, Path], work: Path,
           version_code: int | None = None, version_name: str | None = None,
           manifest_override: Path | None = None,
           catalog: Path | None = None) -> Path:
    """Rebuild the APK by copying the base's raw compressed bytes verbatim.

    Copying entries through ZipFile.open() would recompress them and let Python add
    a zip64 extra field whenever it only learns an entry's size while writing. The
    base APK has no extra fields at all, and zipalign rejects an archive whose
    central directory gained them. So the stored (already compressed) payload and
    its original central-directory record are copied byte for byte, and only the
    replaced entries are written fresh.

    This also means a 3 GB base never has to be decompressed or held in memory.
    """
    unsigned = work / 'unsigned.apk'
    # The base APK keeps the game in classes.dex (6 MB) and the previous modification
    # in classes2.dex (50 KB). The newly compiled classes must therefore REPLACE
    # classes2.dex, not classes.dex: overwriting classes.dex drops the entire game and
    # the result installs but crashes on launch.
    replaced = {'classes2.dex', 'AndroidManifest.xml'}
    replaced.update(f'lib/arm64-v8a/{leaf}' for leaf in natives)
    sources = asset_sources(catalog)
    replaced.update(target_name for _source, target_name in sources)

    patched_manifest = None
    if manifest_override is not None:
        # A pre-rebuilt manifest, e.g. one produced by register_activities.py after
        # adding the offline activities. It still gets its version stamped so the
        # package identity matches the build.
        patched_manifest = patch_manifest_version(
            Path(manifest_override).read_bytes(), version_code, version_name)
    elif version_code is not None or version_name is not None:
        with zipfile.ZipFile(base) as archive:
            patched_manifest = patch_manifest_version(
                archive.read('AndroidManifest.xml'), version_code, version_name)

    central: list[bytes] = []
    with open(base, 'rb') as source, open(unsigned, 'wb') as target:
        # Read the base's end-of-central-directory to find the directory.
        source.seek(0, os.SEEK_END)
        total_size = source.tell()
        source.seek(max(0, total_size - 65557))
        tail = source.read()
        eocd_at = tail.rfind(b'PK\x05\x06')
        if eocd_at < 0:
            raise SystemExit('base APK has no end-of-central-directory record')
        eocd = tail[eocd_at:eocd_at + 22]
        (_, _disk, _cd_disk, _n_disk, entry_count,
         cd_size, cd_offset, _comment) = struct.unpack('<IHHHHIIH', eocd)

        source.seek(cd_offset)
        directory = source.read(cd_size)
        if len(directory) != cd_size:
            raise SystemExit('base APK central directory is truncated')

        position = 0
        records = []
        while position < len(directory):
            if directory[position:position + 4] != b'PK\x01\x02':
                raise SystemExit('base APK central directory is malformed')
            (name_len,) = struct.unpack('<H', directory[position + 28:position + 30])
            (extra_len,) = struct.unpack('<H', directory[position + 30:position + 32])
            (comment_len,) = struct.unpack('<H', directory[position + 32:position + 34])
            record_len = 46 + name_len + extra_len + comment_len
            record = directory[position:position + record_len]
            name = record[46:46 + name_len].decode('utf-8')
            records.append((name, record, record_len))
            position += record_len

        if len(records) != entry_count:
            raise SystemExit(f'base APK claims {entry_count} entries but has {len(records)}')

        # Stream every entry we keep, rewriting only its local header offset.
        new_offsets: dict[str, int] = {}
        for name, record, record_len in records:
            if name in replaced:
                continue
            if name.startswith('META-INF/') and name.upper().endswith(('.RSA', '.DSA', '.EC', '.SF', '.MF')):
                continue
            (method,) = struct.unpack('<H', record[10:12])
            (compressed_size,) = struct.unpack('<I', record[20:24])
            (local_offset,) = struct.unpack('<I', record[42:46])
            (name_len,) = struct.unpack('<H', record[28:30])
            (extra_len,) = struct.unpack('<H', record[30:32])
            source.seek(local_offset)
            local_header = source.read(30)
            if local_header[:4] != b'PK\x03\x04':
                raise SystemExit(f'entry {name} has a malformed local header')
            local_name_len = struct.unpack('<H', local_header[26:28])[0]
            local_extra_len = struct.unpack('<H', local_header[28:30])[0]
            source.seek(local_offset)
            payload = source.read(30 + local_name_len + local_extra_len + compressed_size)
            new_offset = target.tell()
            target.write(payload)
            new_offsets[name] = new_offset
            patched = bytearray(record)
            struct.pack_into('<I', patched, 42, new_offset)
            central.append(bytes(patched))

        # Fresh entries: dex, natives, assets.
        def write_new(local_name: str, data: bytes, compress: bool, is_dir: bool = False) -> None:
            name_bytes = local_name.encode('utf-8')
            method = zipfile.ZIP_DEFLATED if compress else zipfile.ZIP_STORED
            payload = data
            crc = zlib.crc32(data) & 0xFFFFFFFF
            if compress:
                compressor = zlib.compressobj(9, zlib.DEFLATED, -15)
                payload = compressor.compress(data) + compressor.flush()
            offset = target.tell()
            local = struct.pack('<IHHHHHIIIHH', 0x04034B50, 20, 0, method, 0, 0x21,
                                crc, len(payload), len(data), len(name_bytes), 0)
            target.write(local)
            target.write(name_bytes)
            target.write(payload)
            record = struct.pack('<IHHHHHHIIIHHHHHII', 0x02014B50, 20, 20, 0, method, 0, 0x21,
                                 crc, len(payload), len(data), len(name_bytes),
                                 0, 0, 0, 0, (0o40755 << 16) | 0x10 if is_dir else (0o100644 << 16),
                                 offset)
            central.append(record + name_bytes)

        write_new('classes2.dex', dex.read_bytes(), True)
        if patched_manifest is not None:
            write_new('AndroidManifest.xml', patched_manifest, True)
        for leaf, path in natives.items():
            write_new(f'lib/arm64-v8a/{leaf}', path.read_bytes(), False)
        for source_path, target_name in sources:
            local = REPO / source_path
            if local.is_file():
                write_new(target_name, local.read_bytes(), True)

        cd_start = target.tell()
        for record in central:
            target.write(record)
        cd_bytes = target.tell() - cd_start
        target.write(struct.pack('<IHHHHIIH', 0x06054B50, 0, 0, len(central), len(central),
                                 cd_bytes, cd_start, 0))
    return unsigned


def align_and_sign(sdk: Path, build_tools: str, unsigned: Path, work: Path,
                   keystore: Path, alias: str, password_file: Path) -> Path:
    zipalign = find_sdk_tool(sdk, build_tools, 'zipalign.exe' if os.name == 'nt' else 'zipalign')
    apksigner = find_sdk_tool(sdk, build_tools, 'lib/apksigner.jar')
    aligned = work / 'aligned.apk'
    signed = work / 'signed.apk'
    run([zipalign, '-P', '16', '-f', '4', unsigned, aligned])
    run(['java', f'-Djava.io.tmpdir={work}', '-jar', apksigner, 'sign',
         '--ks', keystore, '--ks-key-alias', alias,
         '--ks-pass', f'file:{password_file}',
         '--min-sdk-version', '26', '--v1-signing-enabled', 'false',
         '--v2-signing-enabled', 'true', '--v3-signing-enabled', 'true',
         '--v4-signing-enabled', 'false', '--out', signed, aligned])
    return signed


def entry_hashes(apk: Path) -> dict[str, str]:
    """Hash only the protected entries, streaming each one."""
    hashes = {}
    with zipfile.ZipFile(apk) as archive:
        for name in archive.namelist():
            if not (name.startswith(PROTECTED_PREFIXES) or name.endswith(PROTECTED_SUFFIXES)):
                continue
            digest_obj = hashlib.sha256()
            with archive.open(name) as handle:
                while block := handle.read(1024 * 1024):
                    digest_obj.update(block)
            hashes[name] = digest_obj.hexdigest()
    return hashes


def required_native_libraries() -> set[str]:
    """Native library file names the Java sources load via System.loadLibrary."""
    pattern = re.compile(r'System\.loadLibrary\(\s*"([^"]+)"\s*\)')
    names = set()
    for source in (REPO / 'src').rglob('*.java'):
        for match in pattern.findall(source.read_text(encoding='utf-8', errors='replace')):
            names.add(f'lib{match}.so')
    return names


def verify(sdk: Path, build_tools: str, apk: Path, base: Path) -> dict:
    zipalign = find_sdk_tool(sdk, build_tools, 'zipalign.exe' if os.name == 'nt' else 'zipalign')
    apksigner = find_sdk_tool(sdk, build_tools, 'lib/apksigner.jar')
    aapt2 = find_sdk_tool(sdk, build_tools, 'aapt2.exe' if os.name == 'nt' else 'aapt2')

    signature = run(['java', '-jar', apksigner, 'verify', '--verbose', apk])
    run([zipalign, '-c', '-P', '16', '4', apk])
    badging = run([aapt2, 'dump', 'badging', apk])

    with zipfile.ZipFile(apk) as archive:
        assert archive.testzip() is None, 'output APK has a corrupt entry'
        names = set(archive.namelist())

    protected_now = entry_hashes(apk)
    protected_base = entry_hashes(base)
    changed = sorted(n for n, h in protected_base.items() if protected_now.get(n) != h)

    return {
        'signature_verified': 'Verified' in signature or 'verifies' in signature.lower(),
        'alignment_16k_verified': True,
        'package': re.search(r"package: name='([^']+)'", badging).group(1)
                   if re.search(r"package: name='([^']+)'", badging) else None,
        'version_code': int(m.group(1)) if (m := re.search(r"versionCode='(\d+)'", badging)) else None,
        'version_name': m.group(1) if (m := re.search(r"versionName='([^']+)'", badging)) else None,
        'no_internet_permission': 'android.permission.INTERNET' not in badging,
        'protected_entries': len(protected_base),
        'protected_entries_changed': changed,
        'native_libraries': sorted(n for n in names if n.startswith('lib/arm64-v8a/')),
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--base', type=Path, required=True, help='verified base APK')
    parser.add_argument('--out', type=Path, help='output directory')
    parser.add_argument('--sdk', type=Path, default=Path(os.environ.get('ANDROID_SDK_ROOT', r'C:\Users\yskzc\Android\Sdk')))
    parser.add_argument('--build-tools', default='36.0.0')
    parser.add_argument('--platform', default='android-36')
    parser.add_argument('--ndk', default='26.3.11579264')
    parser.add_argument('--keystore', type=Path)
    parser.add_argument('--alias')
    parser.add_argument('--password-file', type=Path)
    parser.add_argument('--version-code', type=int)
    parser.add_argument('--version-name')
    parser.add_argument('--allow-unknown-base', action='store_true')
    parser.add_argument('--check-only', action='store_true', help='validate the base and sources, build nothing')
    parser.add_argument('--catalog', type=Path, help='chart catalog TSV to ship (default: data/chart-catalog.tsv; use data/chart-catalog-410.tsv for a 4.1.0 build)')
    parser.add_argument('--manifest', type=Path, help='prebuilt binary AndroidManifest.xml to use instead of the base manifest (needed to declare the offline activities)')
    args = parser.parse_args()

    base = args.base.resolve()
    if not base.is_file():
        raise SystemExit(f'base APK not found: {base}')
    base_hash = digest(base)
    print(f'base      : {base}')
    print(f'base sha  : {base_hash}')
    print(f'base size : {base.stat().st_size} bytes')

    known = KNOWN_BASES.get(base_hash)
    if known:
        print(f'base known: {known}')
    elif not args.allow_unknown_base:
        print('base is NOT in the verified list. Re-run with --allow-unknown-base only if you')
        print('have independently confirmed it carries unmodified game resources.')
        return 2
    else:
        print('base unknown: proceeding because --allow-unknown-base was given')

    inventory = base_inventory(base)
    print(f'entries   : {inventory["entries"]} ({len(inventory["protected"])} protected)')

    if args.check_only:
        print('check-only: base and sources validated, nothing built')
        return 0

    if not (args.keystore and args.alias and args.password_file and args.out):
        parser.error('--out, --keystore, --alias and --password-file are required to build')

    work = Path(tempfile.mkdtemp(prefix='phigros-assemble-'))
    keep = bool(os.environ.get('PHIGROS_KEEP_WORK'))
    try:
        print('== compiling Java ==')
        classes = compile_java(args.sdk, args.platform, work)
        print(f'   {len(list(classes.rglob("*.class")))} class files')
        print('== dexing ==')
        dex = dex_classes(args.sdk, args.build_tools, args.platform, classes, work)
        print(f'   classes.dex {dex.stat().st_size} bytes')
        print('== building native ==')
        natives = build_native(args.sdk, args.ndk, work)
        for leaf, path in natives.items():
            print(f'   {leaf} {path.stat().st_size} bytes')
        print('== repacking ==')
        unsigned = repack(base, dex, natives, work, args.version_code, args.version_name,
                    args.manifest, args.catalog)
        print('== aligning and signing ==')
        signed = align_and_sign(args.sdk, args.build_tools, unsigned, work,
                                args.keystore.resolve(), args.alias, args.password_file.resolve())
        print('== verifying ==')
        report = verify(args.sdk, args.build_tools, signed, base)
        for key, value in report.items():
            print(f'   {key}: {value}')

        problems = []
        if not report['signature_verified']:
            problems.append('signature did not verify')
        if not report['no_internet_permission']:
            problems.append('output declares INTERNET permission')
        if report['protected_entries_changed']:
            problems.append(f'game resources changed: {report["protected_entries_changed"][:3]}')
        if report['package'] != PACKAGE:
            problems.append(f'package is {report["package"]}, expected {PACKAGE}')
        # Every library the Java layer loads must be present, or the app dies at
        # startup with UnsatisfiedLinkError. This is checked from the sources rather
        # than a fixed list so adding a loadLibrary call without a matching native
        # target fails the build instead of the phone.
        loaded = required_native_libraries()
        present = {name.rsplit('/', 1)[-1] for name in report['native_libraries']}
        missing = sorted(lib for lib in loaded if lib not in present)
        if missing:
            problems.append(f'Java loads libraries the APK lacks: {missing}')
        if problems:
            print()
            for problem in problems:
                print(f'FAIL {problem}')
            return 3

        args.out.mkdir(parents=True, exist_ok=True)
        final = args.out / f'Phigros-offline-v{args.version_code or report["version_code"]}.apk'
        shutil.copy2(signed, final)
        final_hash = digest(final)
        (args.out / 'SHA256SUMS.txt').write_text(f'{final_hash}  {final.name}\n', encoding='utf-8')
        (args.out / 'VERIFICATION.json').write_text(json.dumps({
            'artifact': final.name,
            'sha256': final_hash,
            'bytes': final.stat().st_size,
            'base': {'path': str(base), 'sha256': base_hash},
            'checks': report,
            'device_tested': False,
        }, indent=2) + '\n', encoding='utf-8')
        print()
        print(f'built {final} ({final.stat().st_size} bytes)')
        print(f'sha256 {final_hash}')
        return 0
    finally:
        if keep:
            print(f'work directory kept: {work}')
        else:
            shutil.rmtree(work, ignore_errors=True)


if __name__ == '__main__':
    sys.exit(main())
