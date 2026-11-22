#!/usr/bin/env python3
"""Split a large APK into standard ZIP volumes and verify the result independently.

The project publishes multi-part ZIP (`.z01`, `.z02`, ... plus `.zip`) because
GitHub release assets cap at 2 GB and the APK is 3.3 GB. Standard ZIP volumes mean
the user extracts the last `.zip` with 7-Zip or WinRAR and gets the APK - no custom
reassembly step and no password.

Two implementation notes, both learned the hard way:

  * Cutting a single-volume archive and patching its offsets afterwards cannot work.
    In a split archive each central-directory entry stores its local-header offset
    relative to the volume that holds it, so the volumes have to be written with the
    split layout from the start - which is what this script does.
  * Python's zipfile cannot be used to verify the output: it raises
    "zipfiles that span multiple disks are not supported" by design. Verification
    therefore reads the volume set with a small parser below, reassembles the
    payload, and hashes it against the input.

Usage:
    python tools/split_release.py --apk out/Phigros-offline-v181.apk \
        --name Phigros-offline-v181 --volume-bytes 1000000000
"""
from __future__ import annotations

import argparse
import hashlib
import json
import struct
import sys
import zlib
from pathlib import Path

CHUNK = 8 * 1024 * 1024

LOCAL = 0x04034B50
CENTRAL = 0x02014B50
EOCD = 0x06054B50
ZIP64_EOCD = 0x06064B50
ZIP64_LOC = 0x07064B50

SIG_LOCAL = b'PK\x03\x04'
SIG_CENTRAL = b'PK\x01\x02'
SIG_EOCD = b'PK\x05\x06'
SIG_ZIP64_EOCD = b'PK\x06\x06'
SIG_ZIP64_LOC = b'PK\x06\x07'


def digest(path: Path) -> str:
    with path.open('rb') as handle:
        return hashlib.file_digest(handle, 'sha256').hexdigest()


class VolumeWriter:
    """Writes one logical stream across fixed-size volumes."""

    def __init__(self, base: Path, volume_bytes: int):
        self.base = base
        self.volume_bytes = volume_bytes
        self.volumes: list[Path] = []
        self.handle = None
        self.in_volume = 0
        self.index = 0
        self._open()

    def _open(self) -> None:
        name = self.base.parent / f'{self.base.name}.z{self.index + 1:02d}'
        self.handle = name.open('wb')
        self.volumes.append(name)
        self.in_volume = 0

    def write(self, data: bytes) -> None:
        view = memoryview(data)
        while view:
            room = self.volume_bytes - self.in_volume
            if room <= 0:
                self.handle.close()
                self.index += 1
                self._open()
                continue
            take = min(room, len(view))
            self.handle.write(view[:take])
            self.in_volume += take
            view = view[take:]

    def close(self) -> None:
        if self.handle:
            self.handle.close()
            self.handle = None

    def finish(self) -> list[Path]:
        """Rename the final volume to .zip so extractors find it by double-click."""
        self.close()
        final = self.base.parent / f'{self.base.name}.zip'
        if self.volumes:
            self.volumes[-1].rename(final)
            self.volumes[-1] = final
        return self.volumes


def build(apk: Path, base: Path, volume_bytes: int) -> list[Path]:
    size = apk.stat().st_size
    crc = 0
    with apk.open('rb') as handle:
        while block := handle.read(CHUNK):
            crc = zlib.crc32(block, crc)
    crc &= 0xFFFFFFFF
    name = apk.name.encode()
    name_len = len(name)

    writer = VolumeWriter(base, volume_bytes)

    local_volume = writer.index
    local_offset = writer.in_volume
    # Sizes exceed the 32-bit fields, so they go in the ZIP64 extra and the 32-bit
    # fields carry the sentinel.
    writer.write(struct.pack('<IHHHHHIIIHH', LOCAL, 45, 0, 0, 0, 0x21,
                             crc, 0xFFFFFFFF, 0xFFFFFFFF, name_len, 20))
    writer.write(name)
    writer.write(struct.pack('<HHQQ', 0x0001, 16, size, size))

    with apk.open('rb') as handle:
        while block := handle.read(CHUNK):
            writer.write(block)

    cd_volume = writer.index
    cd_offset = writer.in_volume
    writer.write(struct.pack('<IHHHHHHIIIHHHHHII', CENTRAL, 45, 45, 0, 0, 0, 0x21,
                             crc, 0xFFFFFFFF, 0xFFFFFFFF, name_len,
                             28, 0, 0, local_volume, 0o100644 << 16, 0xFFFFFFFF))
    writer.write(name)
    # ZIP64 extra in the central record: sizes then the local-header offset, all
    # relative to the volume holding that local header.
    writer.write(struct.pack('<HHQQQ', 0x0001, 24, size, size, local_offset))
    cd_size = writer.in_volume - cd_offset

    zip64_at = writer.in_volume
    writer.write(struct.pack('<IQHHIIQQQQ', ZIP64_EOCD, 44, 45, 45,
                             cd_volume, cd_volume, 1, 1, cd_size, cd_offset))
    writer.write(struct.pack('<IIQII', ZIP64_LOC, cd_volume, zip64_at,
                             len(writer.volumes), len(writer.volumes)))
    writer.write(struct.pack('<IHHHHIIH', EOCD, cd_volume, cd_volume, 1, 1,
                             min(cd_size, 0xFFFFFFFF), min(cd_offset, 0xFFFFFFFF), 0))
    return writer.finish()


def verify(apk: Path, volumes: list[Path], volume_bytes: int) -> str:
    """Reassemble the payload from the volume set and hash it.

    Parses the split archive directly because zipfile rejects multi-disk archives.
    """
    final = volumes[-1].read_bytes()
    locator = final.rfind(SIG_ZIP64_LOC)
    if locator < 0:
        raise SystemExit('no ZIP64 locator in the final volume')
    _, cd_disk, zip64_offset, disks_this, total_disks = struct.unpack_from('<IIQII', final, locator)
    if total_disks != len(volumes):
        raise SystemExit(f'locator says {total_disks} disks, got {len(volumes)}')

    zip64 = struct.unpack_from('<IQHHIIQQQQ', final, zip64_offset)
    _, _, _, _, _, cd_disk_2, _, _, cd_size, cd_offset = zip64
    if cd_disk != cd_disk_2:
        raise SystemExit('locator and ZIP64 EOCD disagree on the central-directory disk')
    if final[cd_offset:cd_offset + 4] != SIG_CENTRAL:
        raise SystemExit('central directory signature not where ZIP64 EOCD says')

    # Walk the central directory to find the payload entry.
    position = cd_offset
    entry = None
    while final[position:position + 4] == SIG_CENTRAL:
        (name_len,) = struct.unpack_from('<H', final, position + 28)
        (extra_len,) = struct.unpack_from('<H', final, position + 30)
        (comment_len,) = struct.unpack_from('<H', final, position + 32)
        (local_disk,) = struct.unpack_from('<H', final, position + 34)
        entry_name = final[position + 46:position + 46 + name_len].decode('utf-8')
        extra_at = position + 46 + name_len
        cursor = extra_at
        end = extra_at + extra_len
        sizes = None
        while cursor + 4 <= end:
            header_id, header_size = struct.unpack_from('<HH', final, cursor)
            if header_id == 0x0001:
                values = struct.unpack_from(f'<{header_size // 8}Q', final, cursor + 4)
                sizes = values
                break
            cursor += 4 + header_size
        if entry_name == apk.name:
            entry = (local_disk, sizes)
        position = extra_at + extra_len + comment_len

    if entry is None:
        raise SystemExit(f'{apk.name} is not in the central directory')
    local_disk, sizes = entry
    if not sizes or len(sizes) < 3:
        raise SystemExit('payload entry has no ZIP64 size/offset extra')
    size, compressed, local_offset = sizes[0], sizes[1], sizes[2]

    # Read the local header, then the payload that follows it.
    with volumes[local_disk].open('rb') as handle:
        handle.seek(local_offset)
        header = handle.read(30)
        if header[:4] != SIG_LOCAL:
            raise SystemExit('local header signature mismatch')
        local_name_len = struct.unpack_from('<H', header, 26)[0]
        local_extra_len = struct.unpack_from('<H', header, 28)[0]
        handle.seek(local_offset + 30 + local_name_len + local_extra_len)
        digest_obj = hashlib.sha256()
        remaining = size
        current = local_disk
        while remaining > 0:
            block = handle.read(min(CHUNK, remaining))
            if not block:
                # Payload continues in the next volume.
                current += 1
                if current >= len(volumes):
                    raise SystemExit('payload runs past the last volume')
                handle.close()
                handle = volumes[current].open('rb')
                continue
            digest_obj.update(block)
            remaining -= len(block)
        handle.close()
    rebuilt = digest_obj.hexdigest()
    expected = digest(apk)
    if rebuilt != expected:
        raise SystemExit(f'reassembled payload {rebuilt} != {expected}')
    return expected


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--apk', type=Path, required=True)
    parser.add_argument('--name', required=True)
    parser.add_argument('--volume-bytes', type=int, default=1_000_000_000)
    parser.add_argument('--out', type=Path)
    args = parser.parse_args()

    apk = args.apk.resolve()
    if not apk.is_file():
        raise SystemExit(f'APK not found: {apk}')
    out = (args.out or apk.parent).resolve()
    out.mkdir(parents=True, exist_ok=True)

    print(f'apk    : {apk.name} ({apk.stat().st_size} bytes)')
    print(f'volume : {args.volume_bytes} bytes')

    for stale in list(out.glob(f'{args.name}.z[0-9][0-9]')) + [out / f'{args.name}.zip']:
        if stale.is_file() and stale.resolve() != apk.resolve():
            stale.unlink()

    volumes = build(apk, out / args.name, args.volume_bytes)
    print(f'volumes: {len(volumes)}')
    apk_hash = verify(apk, volumes, args.volume_bytes)
    print(f'payload reassembled and verified: {apk_hash}')

    entries = [{'name': v.name, 'bytes': v.stat().st_size, 'sha256': digest(v)} for v in volumes]
    manifest = {
        'apk': apk.name,
        'apk_bytes': apk.stat().st_size,
        'apk_sha256': apk_hash,
        'volumes': entries,
        'extractWith': f'open {args.name}.zip with 7-Zip or WinRAR; keep all volumes in one folder',
        'reconstructedPayloadVerified': True,
    }
    (out / f'{args.name}-VOLUMES.json').write_text(json.dumps(manifest, indent=2) + '\n', encoding='utf-8')
    sums = ''.join(f'{entry["sha256"]}  {entry["name"]}\n' for entry in entries)
    sums += f'{apk_hash}  {apk.name}\n'
    (out / 'SHA256SUMS.txt').write_text(sums, encoding='utf-8')

    print()
    for entry in entries:
        print(f'  {entry["name"]:<34}{entry["bytes"]:>14} bytes')
    print()
    print(f'wrote {out / "SHA256SUMS.txt"}')
    return 0


if __name__ == '__main__':
    sys.exit(main())
