"""Recover the twelve Unity helper RVAs that skins.c calls directly.

These are not hooks: they are function addresses the skin code calls as ordinary
functions (Sprite.get_texture, Texture2D..ctor, ImageConversion.LoadImage, ...). A
profile's `hooks` table does not describe them, so `profiles/161.json` still carries
an empty `aux` block while the C header hard-codes the 157 values.

Same evidence rules as the hook recovery: instruction skeleton plus a data-reference
sequence, both compared against the audited 157 build. A Unity helper has no useful
BL caller fingerprint (they are called from everywhere), so the skeleton and the
static-data references carry the decision.
"""
import struct

import capstone

SO_157 = r'I:\dsh\work\audit\libil2cpp.so'
SO_410 = r'I:\dsh\work\audit410\libil2cpp.so'

# name -> 157 rva, taken from src/skin-hooks.h
AUX_157 = {
    'SPRITE_TEXTURE_RVA': 0x3C68D60,
    'SPRITE_RECT_RVA': 0x3C68C28,
    'SPRITE_PIVOT_RVA': 0x3C68F14,
    'SPRITE_PPU_RVA': 0x3C68E60,
    'TEXTURE_WIDTH_RVA': 0x3C3E7D0,
    'TEXTURE_HEIGHT_RVA': 0x3C3E844,
    'SPRITE_CREATE_RVA': 0x3C69E2C,
    'RENDERER_GET_RVA': 0x3C67C7C,
    'RENDERER_SET_RVA': 0x3C67CB8,
    'OBJECT_ALIVE_RVA': 0x3C5D6B0,
    'TEXTURE_CTOR_RVA': 0x3C411B8,
    'LOAD_IMAGE_RVA': 0x3C8639C,
}
WINDOW = 40
PREFIX = 8
SEARCH_RADIUS = 0x40000   # these sit in the Unity engine block; scan around the 157 offset


def load(path):
    data = open(path, 'rb').read()
    phoff = struct.unpack_from('<Q', data, 0x20)[0]
    phes = struct.unpack_from('<H', data, 0x36)[0]
    phn = struct.unpack_from('<H', data, 0x38)[0]
    segs = []
    for i in range(phn):
        b = phoff + i * phes
        t, fl = struct.unpack_from('<II', data, b)
        off = struct.unpack_from('<Q', data, b + 8)[0]
        va = struct.unpack_from('<Q', data, b + 0x10)[0]
        fsz = struct.unpack_from('<Q', data, b + 0x20)[0]
        if t == 1:
            segs.append({'off': off, 'va': va, 'fsz': fsz, 'exec': bool(fl & 1)})
    return data, segs


def rva_to_off(segs, rva):
    for s in segs:
        if s['va'] <= rva < s['va'] + s['fsz']:
            return s['off'] + (rva - s['va'])
    return None


md = capstone.Cs(capstone.CS_ARCH_ARM64, capstone.CS_MODE_ARM)
md.detail = True


def shape(ins):
    parts = []
    for op in ins.operands:
        if op.type == capstone.arm64.ARM64_OP_REG:
            parts.append(ins.reg_name(op.reg))
        elif op.type == capstone.arm64.ARM64_OP_IMM:
            parts.append('#A' if abs(op.imm) >= 0x1000 else f'#{op.imm}')
        elif op.type == capstone.arm64.ARM64_OP_MEM:
            base = ins.reg_name(op.mem.base) if op.mem.base else '-'
            idx = ins.reg_name(op.mem.index) if op.mem.index else ''
            disp = '#A' if abs(op.mem.disp) >= 0x1000 else f'#{op.mem.disp}'
            parts.append(f'[{base}{"+" + idx if idx else ""}{disp}]')
        else:
            parts.append(f'op{op.type}')
    return ins.mnemonic + ' ' + ' '.join(parts)


def skeleton(data, segs, rva, count=WINDOW):
    off = rva_to_off(segs, rva)
    if off is None:
        return None
    ins = list(md.disasm(data[off:off + count * 4], rva))
    if len(ins) < count:
        return None
    return [shape(i) for i in ins[:count]]


def datarefs(data, segs, rva, count=WINDOW):
    off = rva_to_off(segs, rva)
    if off is None:
        return None
    ins = list(md.disasm(data[off:off + count * 4], rva))
    pages = {}
    out = []
    for i in ins:
        if i.mnemonic == 'adrp' and len(i.operands) == 2:
            pages[i.reg_name(i.operands[0].reg)] = i.operands[1].imm
        elif len(i.operands) > 1:
            mem = i.operands[1]
            if mem.type == capstone.arm64.ARM64_OP_MEM and mem.mem.base:
                base = i.reg_name(mem.mem.base)
                if base in pages:
                    out.append(i.mnemonic)
    return out


def build_index(data, segs, prefix_len=PREFIX):
    index = {}
    for s in segs:
        if not s['exec']:
            continue
        start, end = s['off'], s['off'] + s['fsz']
        cursor = start
        while cursor + prefix_len * 4 <= end:
            ins = list(md.disasm(data[cursor:cursor + prefix_len * 4], s['va'] + (cursor - start)))
            if len(ins) == prefix_len:
                index.setdefault(tuple(shape(i) for i in ins), []).append(s['va'] + (cursor - start))
            cursor += 4
    return index


def main():
    d157, s157 = load(SO_157)
    d410, s410 = load(SO_410)

    print('indexing 4.1.0 by 8-instruction prefix...')
    index = build_index(d410, s410)
    print(f'  {len(index)} distinct prefixes\n')

    results = {}
    for name, ref_rva in AUX_157.items():
        ref_skel = skeleton(d157, s157, ref_rva)
        ref_refs = datarefs(d157, s157, ref_rva)
        if ref_skel is None:
            print(f'{name}: 157 reference unreadable at 0x{ref_rva:x}')
            continue

        candidates = index.get(tuple(ref_skel[:PREFIX]), [])
        scored = []
        for candidate in candidates:
            # These live in one engine block, so a nearby address is more plausible.
            if abs(candidate - ref_rva) > SEARCH_RADIUS:
                continue
            skel = skeleton(d410, s410, candidate)
            if skel is None:
                continue
            sim = sum(1 for a, b in zip(ref_skel, skel) if a == b) / WINDOW
            refs = datarefs(d410, s410, candidate)
            refs_ok = (bool(ref_refs) and bool(refs) and len(ref_refs) == len(refs)
                       and all(a == b for a, b in zip(ref_refs, refs)))
            scored.append((sim, candidate, refs_ok))
        scored.sort(reverse=True)

        best = scored[0] if scored else None
        if best and best[0] >= 0.85 and best[2]:
            results[name] = best[1]
            print(f'{name:<22} 0x{ref_rva:x} -> 0x{best[1]:x}  sim={best[0]:.1%} refs=match  ACCEPT')
        elif best:
            print(f'{name:<22} 0x{ref_rva:x} -> 0x{best[1]:x}  sim={best[0]:.1%} '
                  f'refs={"match" if best[2] else "diff"}  REJECT')
        else:
            print(f'{name:<22} 0x{ref_rva:x} -> no candidate within {SEARCH_RADIUS // 1024} KB')

    print(f'\nrecovered {len(results)}/{len(AUX_157)}')
    print('\nC defines:')
    for name, rva in results.items():
        print(f'#define {name} 0x{rva:x}')


if __name__ == '__main__':
    main()
