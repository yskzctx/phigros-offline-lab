#!/usr/bin/env python3
"""Audit a profile's offsets against a reference build's disassembly.

A profile records RVAs recovered for a new game build. A wrong RVA does not crash
the build - it hooks the wrong function and produces subtly wrong behaviour, which is
far worse. This tool checks every hook against a reference build using three signals
that survive recompilation:

  A. the address starts a real function (frame setup, not mid-instruction),
  B. the instruction skeleton matches the reference function, with address-like
     immediates masked (mnemonics, registers and small struct displacements kept),
  C. the static data-reference sequence (adrp/ldr pairs) matches the reference in
     order.

A hook passes when A holds and either B is strong or C matches exactly. The tool
exits non-zero when any hook fails, so it can gate a release.

Usage:
    python tools/audit_profile.py --profile profiles/161.json \
        --reference-so libil2cpp-157.so --target-so libil2cpp-161.so
"""
from __future__ import annotations

import argparse
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
SKELETON_THRESHOLD = 0.75


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


def rva_to_off(segments, rva: int):
    for segment in segments:
        if segment['va'] <= rva < segment['va'] + segment['fsz']:
            return segment['off'] + (rva - segment['va'])
    return None


class Analyzer:
    def __init__(self, path: Path):
        self.data, self.segments = load_elf(path)
        self.md = capstone.Cs(capstone.CS_ARCH_ARM64, capstone.CS_MODE_ARM)
        self.md.detail = True

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

    def skeleton(self, rva: int, count: int = WINDOW):
        offset = rva_to_off(self.segments, rva)
        if offset is None:
            return None
        instructions = list(self.md.disasm(self.data[offset:offset + count * 4], rva))
        if len(instructions) < count:
            return None
        return [self.shape(i) for i in instructions[:count]]

    def data_refs(self, rva: int, count: int = WINDOW):
        """Mnemonics of every load/store through an adrp-derived page."""
        offset = rva_to_off(self.segments, rva)
        if offset is None:
            return None
        instructions = list(self.md.disasm(self.data[offset:offset + count * 4], rva))
        pages = {}
        out = []
        for instruction in instructions:
            if instruction.mnemonic == 'adrp' and len(instruction.operands) == 2:
                pages[instruction.reg_name(instruction.operands[0].reg)] = instruction.operands[1].imm
            elif len(instruction.operands) > 1:
                memory = instruction.operands[1]
                if memory.type == capstone.arm64.ARM64_OP_MEM and memory.mem.base:
                    base = instruction.reg_name(memory.mem.base)
                    if base in pages:
                        out.append(instruction.mnemonic)
        return out

    def starts_function(self, rva: int) -> bool:
        offset = rva_to_off(self.segments, rva)
        if offset is None:
            return False
        instructions = list(self.md.disasm(self.data[offset:offset + 8], rva))
        if not instructions:
            return False
        head = f'{instructions[0].mnemonic} {instructions[0].op_str}'
        return head.startswith(('stp ', 'str ', 'sub sp')) and 'sp' in head


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--profile', type=Path, required=True)
    parser.add_argument('--reference-so', type=Path, required=True)
    parser.add_argument('--target-so', type=Path, required=True)
    args = parser.parse_args()

    profile = json.loads(args.profile.read_text(encoding='utf-8'))
    reference = Analyzer(args.reference_so)
    target = Analyzer(args.target_so)

    print(f'profile  : {args.profile}')
    print(f'reference: {args.reference_so.name}')
    print(f'target   : {args.target_so.name}')
    print()
    print(f'{"hook":<34}{"rva":<13}{"entry":<7}{"skel":<8}{"refs":<8}verdict')

    failures = []
    for name, spec in profile['hooks'].items():
        rva = int(spec['rva'])
        source = spec.get('recoveredFrom')
        if source is None:
            failures.append(f'{name}: no recoveredFrom, cannot audit')
            print(f'{name:<34}0x{rva:<11x}{"-":<7}{"-":<8}{"-":<8}SKIP')
            continue
        source = int(source)

        entry_ok = target.starts_function(rva)
        reference_skeleton = reference.skeleton(source)
        target_skeleton = target.skeleton(rva)
        if reference_skeleton and target_skeleton:
            similarity = sum(1 for a, b in zip(reference_skeleton, target_skeleton)
                             if a == b) / WINDOW
        else:
            similarity = 0.0

        reference_refs = reference.data_refs(source)
        target_refs = target.data_refs(rva)
        refs_ok = (bool(reference_refs) and bool(target_refs)
                   and len(reference_refs) == len(target_refs)
                   and all(a == b for a, b in zip(reference_refs, target_refs)))

        if not entry_ok:
            verdict = 'FAIL entry'
            failures.append(f'{name}: 0x{rva:x} does not start a function')
        elif similarity < SKELETON_THRESHOLD and not refs_ok:
            verdict = 'FAIL evidence'
            failures.append(f'{name}: skeleton {similarity:.1%} and data refs differ')
        else:
            verdict = 'OK'
        print(f'{name:<34}0x{rva:<11x}{"yes" if entry_ok else "no":<7}'
              f'{similarity:<8.1%}{"match" if refs_ok else "diff":<8}{verdict}')

    print()
    if failures:
        print(f'{len(failures)} hook(s) failed:')
        for failure in failures:
            print(f'  {failure}')
        return 1
    print(f'PASS all {len(profile["hooks"])} hooks audited against the reference build')
    return 0


if __name__ == '__main__':
    sys.exit(main())
