"""Recover the 12 Unity helper RVAs for 4.1.0 by skeleton match, unbounded.

Two earlier attempts failed and both failures are informative:

  * the 8-instruction prefix index found nothing, because these are 2-4 instruction
    getters and the prefix ran into the next function;
  * the metadata record table resolved them to data-segment addresses on 4.1.0, and
    checking the 157 build shows why: those RVAs sit at record indices 152803-154484
    while the metadata declares only 88103 methods, so the table continues past the
    method array into something else. It is not a reliable index for engine helpers.

What does work is the method that recovered the 13 hooks: compare instruction shapes
with address immediates masked, over a short window sized to the function. The only
change needed is to drop the "must be near the 157 offset" assumption - the engine
block moved by more than the earlier 256 KB search radius.
"""
import struct

import capstone

SO_157 = r'I:\dsh\work\audit\libil2cpp.so'
SO_410 = r'I:\dsh\work\audit410\libil2cpp.so'

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
WINDOW = 12


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


def window(data, segs, rva, count=WINDOW):
    off = rva_offset(segs, rva)
    if off is None:
        return None
    ins = list(md.disasm(data[off:off + count * 4], rva))
    if not ins:
        return None
    return [shape(i) for i in ins]


def build_prefix_index(data, segs, prefix_len):
    index = {}
    for s in segs:
        if not s['exec']:
            continue
        cursor = s['off']
        end = s['off'] + s['fsz']
        while cursor + prefix_len * 4 <= end:
            ins = list(md.disasm(data[cursor:cursor + prefix_len * 4], s['va'] + (cursor - s['off'])))
            if len(ins) == prefix_len:
                index.setdefault(tuple(shape(i) for i in ins), []).append(
                    s['va'] + (cursor - s['off']))
            cursor += 4
    return index


def main():
    d157 = open(SO_157, 'rb').read()
    d410 = open(SO_410, 'rb').read()
    s157 = segments(d157)
    s410 = segments(d410)

    print('building a 5-instruction prefix index over 4.1.0 executable code...')
    index = build_prefix_index(d410, s410, 5)
    print(f'  {len(index)} distinct prefixes\n')

    resolved = {}
    for name, ref_rva in AUX_157.items():
        ref = window(d157, s157, ref_rva)
        if not ref:
            print(f'{name}: 157 reference unreadable')
            continue
        # Getter bodies are short; the first 5 shapes carry most of the signal.
        candidates = index.get(tuple(ref[:5]), [])
        scored = []
        for candidate in candidates:
            cand = window(d410, s410, candidate)
            if not cand:
                continue
            n = min(len(ref), len(cand))
            sim = sum(1 for a, b in zip(ref[:n], cand[:n]) if a == b) / n
            scored.append((sim, candidate))
        scored.sort(reverse=True)
        if scored and scored[0][0] >= 0.9:
            resolved[name] = scored[0][1]
            print(f'{name:<22} 0x{ref_rva:x} -> 0x{scored[0][1]:x}  '
                  f'sim={scored[0][0]:.1%}  ({len(candidates)} candidates)  ACCEPT')
        elif scored:
            print(f'{name:<22} 0x{ref_rva:x} -> best 0x{scored[0][1]:x} '
                  f'sim={scored[0][0]:.1%}  ({len(candidates)} candidates)  REJECT')
        else:
            print(f'{name:<22} 0x{ref_rva:x} -> no prefix candidate')

    print(f'\nrecovered {len(resolved)}/{len(AUX_157)}')
    if resolved:
        print('\nC defines:')
        for name, rva in resolved.items():
            print(f'#define {name} 0x{rva:x}')


if __name__ == '__main__':
    main()
