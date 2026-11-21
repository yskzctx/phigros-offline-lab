#!/usr/bin/env python3
"""Recover hook offsets for a new Phigros build from an audited reference build.

The byte-prologue approach in audit_offsets.py only works within one compiler
generation. Across the Unity 2019.4 -> 2022.3 jump every prologue changes, so this
tool uses evidence that survives recompilation:

  1. instruction-skeleton matching with address-like immediates masked
     (mnemonics and registers kept, struct-field displacements kept because they
     are what separates one control class from another),
  2. a caller-distance fingerprint: each hooked method is called from exactly one
     place, and the distance from that caller is a code-layout property that a
     recompiled build preserves even when addresses move.

A candidate is accepted only when both signals agree, and the assignment across
methods is a bijection - so two methods can never claim the same address.

Usage:
    python recover_profile.py --reference-so libil2cpp-157.so \
        --reference-profile profiles/157.json \
        --target-so libil2cpp-161.so \
        --out profiles/161.json --version-code 161 --version-name 4.1.0
"""
from __future__ import annotations

import argparse
import hashlib
import json
import struct
import sys
from pathlib import Path

try:
    import capstone
except ImportError:
    print('FAIL: capstone is required (pip install capstone)', file=sys.stderr)
    raise SystemExit(3)

WINDOW = 48
PREFIX = 6
MIN_SIMILARITY = 0.75


def load_elf(path: Path):
    data = path.read_bytes()
    if data[:4] != b'\x7fELF' or data[4] != 2:
        raise SystemExit(f'{path} is not a little-endian ELF64 image')
    phoff = struct.unpack_from('<Q', data, 0x20)[0]
    phentsize = struct.unpack_from('<H', data, 0x36)[0]
    phnum = struct.unpack_from('<H', data, 0x38)[0]
    segments = []
    for index in range(phnum):
        base = phoff + index * phentsize
        p_type, p_flags = struct.unpack_from('<II', data, base)
        p_offset = struct.unpack_from('<Q', data, base + 0x08)[0]
        p_vaddr = struct.unpack_from('<Q', data, base + 0x10)[0]
        p_filesz = struct.unpack_from('<Q', data, base + 0x20)[0]
        if p_type == 1:
            segments.append({'off': p_offset, 'va': p_vaddr, 'fsz': p_filesz,
                             'exec': bool(p_flags & 1)})
    return data, segments


def rva_to_off(segments, rva):
    for segment in segments:
        if segment['va'] <= rva < segment['va'] + segment['fsz']:
            return segment['off'] + (rva - segment['va'])
    return None


class Analyzer:
    def __init__(self, path: Path):
        self.path = path
        self.data, self.segments = load_elf(path)
        self.sha256 = hashlib.sha256(self.data).hexdigest()
        self.md = capstone.Cs(capstone.CS_ARCH_ARM64, capstone.CS_MODE_ARM)
        self.md.detail = True
        self._bl_index = None
        self._prefix_index = None

    @staticmethod
    def shape(instruction) -> str:
        parts = []
        for operand in instruction.operands:
            if operand.type == capstone.arm64.ARM64_OP_REG:
                parts.append(instruction.reg_name(operand.reg))
            elif operand.type == capstone.arm64.ARM64_OP_IMM:
                parts.append('#A' if abs(operand.imm) >= 0x1000 else f'#{operand.imm}')
            elif operand.type == capstone.arm64.ARM64_OP_MEM:
                base = instruction.reg_name(operand.mem.base) if operand.mem.base else '-'
                index = instruction.reg_name(operand.mem.index) if operand.mem.index else ''
                disp = '#A' if abs(operand.mem.disp) >= 0x1000 else f'#{operand.mem.disp}'
                parts.append(f'[{base}{"+" + index if index else ""}{disp}]')
            else:
                parts.append(f'op{operand.type}')
        return f'{instruction.mnemonic} {" ".join(parts)}'.strip()

    def window(self, rva: int, count: int = WINDOW):
        offset = rva_to_off(self.segments, rva)
        if offset is None:
            return None
        instructions = list(self.md.disasm(self.data[offset:offset + count * 4], rva))
        if len(instructions) < count:
            return None
        return tuple(self.shape(i) for i in instructions[:count])

    def bl_index(self) -> dict[int, list[int]]:
        if self._bl_index is not None:
            return self._bl_index
        callers: dict[int, list[int]] = {}
        for segment in self.segments:
            if not segment['exec']:
                continue
            chunk = self.data[segment['off']:segment['off'] + segment['fsz']]
            for instruction in self.md.disasm(chunk, segment['va']):
                if instruction.mnemonic != 'bl' or not instruction.operands:
                    continue
                operand = instruction.operands[0]
                if operand.type == capstone.arm64.ARM64_OP_IMM:
                    callers.setdefault(operand.imm, []).append(instruction.address)
        self._bl_index = callers
        return callers

    def caller_distance(self, rva: int):
        callers = self.bl_index().get(rva, [])
        return abs(callers[0] - rva) if len(callers) == 1 else None

    def prefix_index(self, prefix_len: int = PREFIX) -> dict[tuple[str, ...], list[int]]:
        if self._prefix_index is not None:
            return self._prefix_index
        index: dict[tuple[str, ...], list[int]] = {}
        for segment in self.segments:
            if not segment['exec']:
                continue
            start, end = segment['off'], segment['off'] + segment['fsz']
            cursor = start
            while cursor + prefix_len * 4 <= end:
                instructions = list(self.md.disasm(
                    self.data[cursor:cursor + prefix_len * 4],
                    segment['va'] + (cursor - start)))
                if len(instructions) == prefix_len:
                    key = tuple(self.shape(i) for i in instructions)
                    index.setdefault(key, []).append(segment['va'] + (cursor - start))
                cursor += 4
        self._prefix_index = index
        return index


def similarity(a, b) -> float:
    return sum(1 for x, y in zip(a, b) if x == y) / len(a)


def recover(reference: Analyzer, target: Analyzer, reference_profile: dict) -> dict:
    hooks = reference_profile['hooks']
    prefix_index = target.prefix_index()
    bl_index = target.bl_index()

    pools: dict[str, list[tuple[float, int, int | None]]] = {}
    reference_distances: dict[str, int | None] = {}
    for name, spec in hooks.items():
        rva = int(spec['rva'])
        shape = reference.window(rva)
        if shape is None:
            continue
        reference_distances[name] = reference.caller_distance(rva)
        scored = []
        for candidate in prefix_index.get(shape[:PREFIX], []):
            window = target.window(candidate)
            if window is None:
                continue
            callers = bl_index.get(candidate, [])
            distance = abs(callers[0] - candidate) if len(callers) == 1 else None
            scored.append((similarity(shape, window), candidate, distance))
        scored.sort(reverse=True)
        pools[name] = scored

    # Assign strongest evidence first: a candidate whose caller distance matches
    # exactly beats one that only looks similar, because the distance is a layout
    # property rather than a statistical score.
    resolved: dict[str, int] = {}
    claimed: set[int] = set()
    order = sorted(pools, key=lambda n: -max((s for s, _c, _d in pools[n]), default=0))
    for name in order:
        wanted = reference_distances.get(name)
        pick = None
        if wanted is not None:
            for score, candidate, distance in pools[name]:
                if distance == wanted and candidate not in claimed and score >= MIN_SIMILARITY:
                    pick = (score, candidate, 'caller-distance')
                    break
        if pick is None:
            for score, candidate, distance in pools[name]:
                if candidate in claimed or score < MIN_SIMILARITY:
                    continue
                pick = (score, candidate, 'skeleton-only')
                break
        if pick:
            claimed.add(pick[1])
            resolved[name] = pick[1]
            pools[name] = [(pick[0], pick[1], pick[2])]
    return {'resolved': resolved, 'pools': pools, 'referenceDistances': reference_distances}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--reference-so', type=Path, required=True)
    parser.add_argument('--reference-profile', type=Path, required=True)
    parser.add_argument('--target-so', type=Path, required=True)
    parser.add_argument('--out', type=Path)
    parser.add_argument('--version-code', type=int)
    parser.add_argument('--version-name')
    parser.add_argument('--report', action='store_true')
    args = parser.parse_args()

    profile = json.loads(args.reference_profile.read_text(encoding='utf-8'))
    reference = Analyzer(args.reference_so)
    target = Analyzer(args.target_so)
    print(f'reference: {args.reference_so.name} {reference.sha256}')
    print(f'target   : {args.target_so.name} {target.sha256} ({len(target.data)} bytes)')

    outcome = recover(reference, target, profile)
    resolved = outcome['resolved']

    print()
    print(f'{"hook":<30}{"rva":<14}{"status"}')
    for name in profile['hooks']:
        value = resolved.get(name)
        if value is None:
            print(f'{name:<30}{"-":<14}unresolved')
        else:
            print(f'{name:<30}0x{value:<12x}recovered')
    print()
    print(f'recovered {len(resolved)}/{len(profile["hooks"])}')

    if args.out:
        if args.version_code is None or args.version_name is None:
            parser.error('--version-code and --version-name are required with --out')
        hooks = {}
        for name, rva in resolved.items():
            source = profile['hooks'][name]
            hooks[name] = {
                'rva': rva,
                'prefix': source['prefix'],
                'relocateMask': source.get('relocateMask', 0),
                'recoveredFrom': int(source['rva']),
            }
        complete = len(hooks) == len(profile['hooks'])
        derived = {
            'profileVersion': profile.get('profileVersion', 1),
            'versionCode': args.version_code,
            'versionName': args.version_name,
            'abi': 'arm64-v8a',
            'il2cppSha256': target.sha256,
            'il2cppBytes': len(target.data),
            'complete': complete,
            'hooks': hooks,
            'aux': {},
            'recoveryMethod': 'instruction-skeleton + caller-distance fingerprint',
            'referenceVersionCode': profile.get('versionCode'),
        }
        if not complete:
            derived['blockedReason'] = (
                f'{len(profile["hooks"]) - len(hooks)} hook(s) could not be recovered; '
                f'do not ship this profile')
        args.out.parent.mkdir(parents=True, exist_ok=True)
        args.out.write_text(json.dumps(derived, indent=2) + '\n', encoding='utf-8')
        print(f'wrote {args.out} (complete={complete})')
    return 0 if len(resolved) == len(profile['hooks']) else 2


if __name__ == '__main__':
    sys.exit(main())
