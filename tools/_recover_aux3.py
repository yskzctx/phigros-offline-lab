"""Recover the remaining Unity helpers using static-data references.

Six helpers resisted skeleton matching: their 157 prologues are 2-4 instructions and
the 4.1.0 compiler inlined or restructured them, so the instruction shapes diverge
even though the function does the same thing.

Every one of them, however, loads a cached il2cpp class pointer or a cached
MetadataCache slot through an adrp/ldr pair. The *sequence* of those accesses
(load width + which cache slot, in order) is much more stable than the instruction
shapes around them, and it was what settled hold/result/record during the hook
recovery.

This probe indexes the whole 4.1.0 executable segment by the first four data
references of every 4-byte-aligned position and matches against the 157 reference.
"""
import struct

import capstone

SO_157 = r'I:\dsh\work\audit\libil2cpp.so'
SO_410 = r'I:\dsh\work\audit410\libil2cpp.so'

REMAINING = {
    'SPRITE_RECT_RVA': 0x3C68C28,
    'SPRITE_PIVOT_RVA': 0x3C68F14,
    'RENDERER_SET_RVA': 0x3C67CB8,
    'OBJECT_ALIVE_RVA': 0x3C5D6B0,
    'TEXTURE_CTOR_RVA': 0x3C411B8,
    'LOAD_IMAGE_RVA': 0x3C8639C,
}
SCAN = 24          # instructions inspected per candidate
SIGNATURE = 4      # data references that form the key


def segments(data):
    phoff = struct.unpack_from('<Q', data, 0x20)[0]
    phes = struct.unpack_from('<H', data, 0x36)[0]
    phn = struct.unpack_from('<H', data, 0x38)[0]
    out = []
    for i in range(phn):
        b = phoff + i * phes
        p_type, p_flags = struct.unpack_from('<II', data, b)
        p_off = struct.unpack_from('<Q', data, b + 8)[0]
        p_va = struct.unpack_from('<Q', data, b + 0x10)[0]
        p_fsz = struct.unpack_from('<Q', data, b + 0x20)[0]
        if p_type == 1:
            out.append({'va': p_va, 'off': p_off, 'fsz': p_fsz, 'exec': bool(p_flags & 1)})
    return out


def rva_offset(segs, rva):
    for s in segs:
        if s['va'] <= rva < s['va'] + s['fsz']:
            return s['off'] + (rva - s['va'])
    return None


md = capstone.Cs(capstone.CS_ARCH_ARM64, capstone.CS_MODE_ARM)
md.detail = True


def data_ref_signature(data, segs, rva, limit=SCAN):
    """(mnemonic,) of each load through an adrp-derived page, in order."""
    off = rva_offset(segs, rva)
    if off is None:
        return None
    ins = list(md.disasm(data[off:off + limit * 4], rva))
    pages = {}
    refs = []
    for i in ins:
        if i.mnemonic == 'adrp' and len(i.operands) == 2:
            pages[i.reg_name(i.operands[0].reg)] = i.operands[1].imm
        elif len(i.operands) > 1:
            mem = i.operands[1]
            if mem.type == capstone.arm64.ARM64_OP_MEM and mem.mem.base:
                base = i.reg_name(mem.mem.base)
                if base in pages:
                    refs.append((i.mnemonic, mem.mem.disp))
    return refs


def main():
    d157 = open(SO_157, 'rb').read()
    d410 = open(SO_410, 'rb').read()
    s157 = segments(d157)
    s410 = segments(d410)

    exec_seg = next(s for s in s410 if s['exec'])
    print(f'indexing 4.1.0 executable segment '
          f'(0x{exec_seg["va"]:x}..0x{exec_seg["va"] + exec_seg["fsz"]:x}) by data refs...')

    index = {}
    cursor = exec_seg['off']
    end = exec_seg['off'] + exec_seg['fsz']
    va_base = exec_seg['va']
    while cursor + SCAN * 4 <= end:
        rva = va_base + (cursor - exec_seg['off'])
        refs = data_ref_signature(d410, s410, rva)
        if refs and len(refs) >= SIGNATURE:
            key = tuple(m for m, _d in refs[:SIGNATURE])
            index.setdefault(key, []).append(rva)
        cursor += 4
    print(f'  {len(index)} distinct signatures\n')

    resolved = {}
    for name, ref_rva in REMAINING.items():
        ref_refs = data_ref_signature(d157, s157, ref_rva)
        if not ref_refs:
            print(f'{name}: 157 has no data references; cannot use this signal')
            continue
        key = tuple(m for m, _d in ref_refs[:SIGNATURE])
        if len(key) < SIGNATURE:
            print(f'{name}: 157 has only {len(key)} data refs, need {SIGNATURE}')
            continue
        candidates = index.get(key, [])
        print(f'{name:<22} 157 refs={[m for m, _ in ref_refs[:6]]} -> {len(candidates)} candidate(s)')
        for candidate in candidates[:4]:
            cand_refs = data_ref_signature(d410, s410, candidate)
            n = min(len(ref_refs), len(cand_refs))
            sim = sum(1 for a, b in zip(ref_refs[:n], cand_refs[:n]) if a == b) / n
            print(f'     0x{candidate:x} refsim={sim:.0%} refs={[m for m, _ in cand_refs[:6]]}')
        if candidates:
            resolved[name] = candidates[0]

    print(f'\nresolved {len(resolved)}/{len(REMAINING)}')
    if resolved:
        print('\nC defines:')
        for name, rva in resolved.items():
            print(f'#define {name} 0x{rva:x}')


if __name__ == '__main__':
    main()
