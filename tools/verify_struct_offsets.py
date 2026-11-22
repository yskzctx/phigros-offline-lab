#!/usr/bin/env python3
"""Verify that struct offsets the native code hard-codes still hold on a new build.

offline.c reads and writes game objects at fixed offsets (0x30 level, 0x58/0x5c/0x60/
0x64 counts, 0x90 progress, and the whole LevelResultInfo layout). Those offsets are the
riskiest part of porting to a new game version: a wrong one does not crash, it silently
reads or writes the wrong field.

Two checks, because the offsets come from two different places:

  * the settlement object (LevelResultInfo) - its declared field order is compared
    directly from metadata;
  * the judgment structs - their field offsets are read out of the hook functions'
    machine code as ldr/str displacements. Static-field cache offsets (adrp page +
    offset, large values that differ per build) are ignored, since offline.c never
    references them; only the small instance-field offsets matter.

Usage:
    python tools/verify_struct_offsets.py \
        --reference-so libil2cpp-157.so --reference-meta global-metadata-157.dat \
        --target-so libil2cpp-161.so --target-meta global-metadata-161.dat \
        --reference-profile profiles/157.json --target-profile profiles/161.json
"""
from __future__ import annotations

import argparse
import json
import struct
import sys
from pathlib import Path

import capstone

HEADER_BASE = 0x08
PAIR_STRING, PAIR_FIELDS, PAIR_TYPES = 2, 11, 19
TYPE_STRIDE = 0x58
FIELD_STRIDE = 0x0C
SCAN = 30
INSTANCE_MAX = 512   # anything below this is an instance field offset

RESULT_FIELDS = ['perfect', 'good', 'bad', 'miss', 'early', 'late', 'percent']


def segments(data):
    phoff = struct.unpack_from('<Q', data, 0x20)[0]
    phes = struct.unpack_from('<H', data, 0x36)[0]
    phn = struct.unpack_from('<H', data, 0x38)[0]
    out = []
    for i in range(phn):
        b = phoff + i * phes
        p_type = struct.unpack_from('<I', data, b)[0]
        p_off = struct.unpack_from('<Q', data, b + 8)[0]
        p_va = struct.unpack_from('<Q', data, b + 0x10)[0]
        p_fsz = struct.unpack_from('<Q', data, b + 0x20)[0]
        if p_type == 1:
            out.append((p_va, p_off, p_fsz))
    return out


def metadata_strings_and_types(meta_path):
    meta = open(meta_path, 'rb').read()

    def pair(n):
        return struct.unpack_from('<ii', meta, HEADER_BASE + n * 8)

    strings, _ = pair(PAIR_STRING)
    fields_off, _ = pair(PAIR_FIELDS)
    types_off, types_size = pair(PAIR_TYPES)

    def string(index):
        if index < 0:
            return ''
        start = strings + index
        end = meta.find(b'\x00', start, start + 256)
        return meta[start:end].decode('utf-8', 'replace') if end > 0 else ''

    def type_fields(typename):
        for i in range(types_size // TYPE_STRIDE):
            base = types_off + i * TYPE_STRIDE
            name_i, ns_i = struct.unpack_from('<ii', meta, base)
            full = (string(ns_i) + '.' + string(name_i)) if ns_i >= 0 else string(name_i)
            if full != typename:
                continue
            fstart, = struct.unpack_from('<i', meta, base + 0x20)
            fcount, = struct.unpack_from('<H', meta, base + 0x44)
            names = []
            for k in range(fcount):
                fb = fields_off + (fstart + k) * FIELD_STRIDE
                fname_i, = struct.unpack_from('<i', meta, fb)
                names.append(string(fname_i))
            return names
        return None

    return type_fields


def instance_offsets(data, segs, rva):
    for va, off, size in segs:
        if va <= rva < va + size:
            md = capstone.Cs(capstone.CS_ARCH_ARM64, capstone.CS_MODE_ARM)
            md.detail = True
            ins = list(md.disasm(data[off + (rva - va):off + (rva - va) + SCAN * 4], rva))
            out = set()
            for i in ins:
                if i.mnemonic in ('ldr', 'ldrb', 'str', 'strb', 'ldrsw') and len(i.operands) >= 2:
                    mem = i.operands[-1]
                    if mem.type == capstone.arm64.ARM64_OP_MEM and 0 < mem.mem.disp < INSTANCE_MAX:
                        out.add(mem.mem.disp)
            return out
    return None


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--reference-so', type=Path, required=True)
    parser.add_argument('--reference-meta', type=Path, required=True)
    parser.add_argument('--target-so', type=Path, required=True)
    parser.add_argument('--target-meta', type=Path, required=True)
    parser.add_argument('--reference-profile', type=Path, required=True)
    parser.add_argument('--target-profile', type=Path, required=True)
    args = parser.parse_args()

    ref_so = args.reference_so.read_bytes()
    tgt_so = args.target_so.read_bytes()
    ref_segs = segments(ref_so)
    tgt_segs = segments(tgt_so)
    ref_fields = metadata_strings_and_types(args.reference_meta)
    tgt_fields = metadata_strings_and_types(args.target_meta)
    ref_profile = json.loads(args.reference_profile.read_text(encoding='utf-8'))
    tgt_profile = json.loads(args.target_profile.read_text(encoding='utf-8'))

    problems = []

    # ---- 1. settlement object layout ----
    print('=== LevelResultInfo field order ===')
    a = ref_fields('.LevelResultInfo')
    b = tgt_fields('.LevelResultInfo')
    if a is None or b is None:
        problems.append('LevelResultInfo not found in one of the builds')
    elif a != b:
        problems.append(f'LevelResultInfo field order changed: {a} -> {b}')
        print(f'  157: {a}')
        print(f'  161: {b}')
    else:
        print(f'  identical ({len(a)} fields), so the forced-result offsets hold')
        for pos, name in enumerate(a):
            mark = ' *' if any(k in name.lower() for k in RESULT_FIELDS) else ''
            print(f'    [{pos}] {name}{mark}')

    # ---- 2. judgment struct offsets from the hook bodies ----
    print('\n=== judgment hook instance offsets ===')
    for hook, ref_spec in ref_profile.get('hooks', {}).items():
        if not hook.startswith(('click', 'drag', 'flick', 'hold', 'miss', 'perfect', 'good', 'record')):
            continue
        if hook not in tgt_profile.get('hooks', {}):
            continue
        ref_rva = int(ref_spec['rva'])
        tgt_rva = int(tgt_profile['hooks'][hook]['rva'])
        oa = instance_offsets(ref_so, ref_segs, ref_rva)
        ob = instance_offsets(tgt_so, tgt_segs, tgt_rva)
        if oa is None or ob is None:
            problems.append(f'{hook}: could not read offsets')
            continue
        same = oa == ob
        print(f'  {hook:<10} {"SAME" if same else "DIFF"}  '
              f'{sorted(hex(x) for x in oa)}'
              + ('' if same else f'  vs {sorted(hex(x) for x in ob)}'))
        if not same:
            problems.append(f'{hook}: instance offsets differ '
                            f'(only157={sorted(oa - ob)}, only161={sorted(ob - oa)})')

    print()
    if problems:
        for problem in problems:
            print(f'FAIL {problem}')
        return 1
    print('PASS settlement layout and judgment struct offsets unchanged')
    return 0


if __name__ == '__main__':
    sys.exit(main())
