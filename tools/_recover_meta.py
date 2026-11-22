"""Resolve method RVAs through il2cpp metadata instead of code shape.

The shape-based recovery works for long functions but not for the Unity helpers the
skin code calls: Sprite.get_texture, Texture2D.get_width and friends are 3-5
instruction getters, so an 8-instruction prefix runs past the end of the function and
matches nothing.

Metadata gives exact semantic identity instead: every method has a name and a
declaring type, and the method-definition record table maps an index to a code RVA.
The earlier attempt at this failed for a specific and fixable reason - it searched for
a bare method name like "NoteMove", but several control classes each declare their
own NoteMove, so the index found belonged to a different class than intended. Matching
on (declaringType, methodName) removes that ambiguity.

The tool self-checks by resolving known-good 157 methods and comparing against the
audited RVAs before trusting anything it reports for 4.1.0.
"""
from __future__ import annotations

import struct
import sys

METHOD_STRIDE = 0x24
RECORD_STRIDE = 0x18
TYPE_STRIDE = 0x58

# Il2CppGlobalMetadataHeader is a sequence of (offset, size) int32 pairs starting at
# 0x08. Reading them as 64-bit values silently produces garbage, so the table is
# spelled out and indexed by pair number.
HEADER_BASE = 0x08
PAIRS = {
    'stringLiteral': 0, 'stringLiteralData': 1, 'string': 2, 'events': 3,
    'properties': 4, 'methods': 5, 'parameterDefaultValues': 6,
    'fieldDefaultValues': 7, 'fieldAndParameterDefaultValueData': 8,
    'fieldMarshaledSizes': 9, 'parameters': 10, 'fields': 11,
    'genericParameters': 12, 'genericParameterConstraints': 13,
    'genericContainers': 14, 'nestedTypes': 15, 'interfaces': 16,
    'vtableMethods': 17, 'interfaceOffsets': 18, 'typeDefinitions': 19,
    'images': 20, 'assemblies': 21, 'fieldRefs': 22,
    'referencedAssemblies': 23, 'attributeData': 24, 'attributeDataRange': 25,
}

# Method-code record table calibration. Each record is 24 bytes laid out as
#   [flags 0x403][codeRva u64][methodInfo u64]
# and records appear in method-index order, so a single known (index, rva) pair fixes
# the table: slot(index) = base + index * 24. The base below was found by locating the
# four audited NoteMove RVAs in the image and reading back to index 0.
CAL = {
    157: {'base': 0x81A350},
    161: {'base': 0x81AEC0},   # calibrated from four recovered anchors, layout verified
}


class Metadata:
    def __init__(self, path: str, so_path: str):
        self.meta = open(path, 'rb').read()
        self.so = open(so_path, 'rb').read()

        def pair(name: str) -> tuple[int, int]:
            offset = HEADER_BASE + PAIRS[name] * 8
            return struct.unpack_from('<ii', self.meta, offset)

        self.strings, _ = pair('string')
        self.type_defs, type_size = pair('typeDefinitions')
        self.methods, method_size = pair('methods')
        self.type_count = type_size // TYPE_STRIDE
        self.method_count = method_size // METHOD_STRIDE

    def string(self, index: int) -> str:
        start = self.strings + index
        end = self.meta.find(b'\x00', start, start + 512)
        if end < 0:
            return ''
        return self.meta[start:end].decode('utf-8', 'replace')

    def method_at(self, index: int):
        base = self.methods + index * METHOD_STRIDE
        name_index, declaring = struct.unpack_from('<Ii', self.meta, base)
        return self.string(name_index), declaring

    def type_name(self, index: int) -> str:
        if index < 0 or index >= self.type_count:
            return ''
        base = self.type_defs + index * TYPE_STRIDE
        name_index = struct.unpack_from('<i', self.meta, base)[0]
        namespace_index = struct.unpack_from('<i', self.meta, base + 4)[0]
        name = self.string(name_index)
        namespace = self.string(namespace_index)
        return f'{namespace}.{name}' if namespace else name

    def find(self, type_hint: str, method: str) -> list[int]:
        """Method indices whose name matches and whose declaring type contains type_hint."""
        hits = []
        for index in range(self.method_count):
            name, declaring = self.method_at(index)
            if name != method:
                continue
            owner = self.type_name(declaring)
            if type_hint in owner:
                hits.append(index)
        return hits


def code_rva(so: bytes, version: int, method_index: int):
    cal = CAL.get(version)
    if not cal:
        raise SystemExit(f'no record-table calibration for version {version}')
    slot = cal['base'] + method_index * RECORD_STRIDE
    if slot + 8 > len(so):
        return None
    # A record is [flags][codeRva][methodInfo]; the flags word guards against reading
    # an unrelated 24-byte window.
    flags = struct.unpack_from('<Q', so, slot)[0]
    rva = struct.unpack_from('<Q', so, slot + 8)[0]
    if flags & 0xFFFFFFFF != 0x403:
        return None
    return rva


# --- instruction-shape comparison, used to pick between overloads --------------

try:
    import capstone
except ImportError:
    capstone = None

SHAPE_WINDOW = 16


def _segments(so: bytes):
    phoff = struct.unpack_from('<Q', so, 0x20)[0]
    phes = struct.unpack_from('<H', so, 0x36)[0]
    phn = struct.unpack_from('<H', so, 0x38)[0]
    segs = []
    for i in range(phn):
        b = phoff + i * phes
        p_type, p_flags = struct.unpack_from('<II', so, b)
        p_off = struct.unpack_from('<Q', so, b + 8)[0]
        p_va = struct.unpack_from('<Q', so, b + 0x10)[0]
        p_fsz = struct.unpack_from('<Q', so, b + 0x20)[0]
        if p_type == 1:
            segs.append((p_va, p_off, p_fsz, bool(p_flags & 1)))
    return segs


def _rva_offset(segs, rva):
    for va, off, size, _exec in segs:
        if va <= rva < va + size:
            return off + (rva - va)
    return None


def _shape_text(ins):
    parts = []
    for op in ins.operands:
        if op.type == capstone.arm64.ARM64_OP_REG:
            parts.append(ins.reg_name(op.reg))
        elif op.type == capstone.arm64.ARM64_OP_IMM:
            parts.append('#A' if abs(op.imm) >= 0x1000 else f'#{op.imm}')
        elif op.type == capstone.arm64.ARM64_OP_MEM:
            base = ins.reg_name(op.mem.base) if op.mem.base else '-'
            disp = '#A' if abs(op.mem.disp) >= 0x1000 else f'#{op.mem.disp}'
            parts.append(f'[{base}{disp}]')
        else:
            parts.append(f'op{op.type}')
    return ins.mnemonic + ' ' + ' '.join(parts)


def shape_window(so: bytes, segs, rva: int):
    """Instruction shapes at rva, or None if the rva is not inside an exec segment."""
    if capstone is None:
        return None
    offset = _rva_offset(segs, rva)
    if offset is None:
        return None
    md = capstone.Cs(capstone.CS_ARCH_ARM64, capstone.CS_MODE_ARM)
    md.detail = True
    ins = list(md.disasm(so[offset:offset + SHAPE_WINDOW * 4], rva))
    if not ins:
        return None
    # Short getters are only 2-4 instructions; comparing a truncated window is fine as
    # long as both sides are truncated the same way.
    return [_shape_text(i) for i in ins]


def similarity(a, b) -> float:
    if not a or not b:
        return 0.0
    n = min(len(a), len(b))
    return sum(1 for x, y in zip(a[:n], b[:n]) if x == y) / n


def main() -> int:
    # --- self-check on 157 -------------------------------------------------
    m157 = Metadata(r'I:\dsh\work\audit\global-metadata.dat', r'I:\dsh\work\audit\libil2cpp.so')
    known = [
        ('ClickControl', 'NoteMove', 0x1D81E98),
        ('DragControl', 'NoteMove', 0x1D8267C),
        ('FlickControl', 'NoteMove', 0x1D82C7C),
        ('HoldControl', 'NoteMove', 0x1D8334C),
    ]
    print('=== self-check on the audited 157 build ===')
    checks_ok = True
    for type_hint, method, expected in known:
        indices = m157.find(type_hint, method)
        if not indices:
            print(f'  {type_hint}$${method}: not found in metadata')
            checks_ok = False
            continue
        for index in indices:
            got = code_rva(m157.so, 157, index)
            ok = got == expected
            checks_ok &= ok
            print(f'  {type_hint}$${method} idx={index} -> 0x{got:x} '
                  f'expected 0x{expected:x} {"OK" if ok else "MISMATCH"}')
    if not checks_ok:
        print('\nself-check failed: the record table or the lookup is wrong, aborting')
        return 1
    print('  record table and (type, method) lookup both verified\n')

    # --- resolve the 4.1.0 targets -----------------------------------------
    m410 = Metadata(r'I:\dsh\work\audit410\global-metadata.dat', r'I:\dsh\work\audit410\libil2cpp.so')
    # Each target also names the 157 rva it replaces. When metadata offers several
    # overloads, the one whose code shape matches the audited 157 function is chosen -
    # method identity alone cannot pick between overloads, but shape can.
    targets = [
        ('Sprite', 'get_texture', 'SPRITE_TEXTURE_RVA', 0x3C68D60),
        ('Sprite', 'get_rect', 'SPRITE_RECT_RVA', 0x3C68C28),
        ('Sprite', 'get_pivot', 'SPRITE_PIVOT_RVA', 0x3C68F14),
        ('Sprite', 'get_pixelsPerUnit', 'SPRITE_PPU_RVA', 0x3C68E60),
        ('Texture2D', 'get_width', 'TEXTURE_WIDTH_RVA', 0x3C3E7D0),
        ('Texture2D', 'get_height', 'TEXTURE_HEIGHT_RVA', 0x3C3E844),
        ('Sprite', 'Create', 'SPRITE_CREATE_RVA', 0x3C69E2C),
        ('SpriteRenderer', 'get_sprite', 'RENDERER_GET_RVA', 0x3C67C7C),
        ('SpriteRenderer', 'set_sprite', 'RENDERER_SET_RVA', 0x3C67CB8),
        ('Object', 'op_Implicit', 'OBJECT_ALIVE_RVA', 0x3C5D6B0),
        ('Texture2D', '.ctor', 'TEXTURE_CTOR_RVA', 0x3C411B8),
        ('ImageConversion', 'LoadImage', 'LOAD_IMAGE_RVA', 0x3C8639C),
    ]
    print('=== 4.1.0 resolution ===')
    segs157 = _segments(m157.so)
    segs410 = _segments(m410.so)
    resolved = {}
    for type_hint, method, label, ref_157 in targets:
        indices = m410.find(type_hint, method)
        if not indices:
            print(f'{label:<22} {type_hint}$${method}: no match')
            continue
        ref_shape = shape_window(m157.so, segs157, ref_157)
        scored = []
        for index in indices:
            rva = code_rva(m410.so, 161, index)
            if not rva:
                continue
            candidate = shape_window(m410.so, segs410, rva)
            sim = similarity(ref_shape, candidate)
            owner = m410.type_name(m410.method_at(index)[1])
            scored.append((sim, rva, index, owner))
        scored.sort(reverse=True)
        for sim, rva, index, owner in scored[:3]:
            mark = 'ACCEPT' if sim >= 0.75 else ''
            print(f'{label:<22} {owner}$${method} idx={index} -> 0x{rva:x} sim={sim:.1%} {mark}')
        if scored and scored[0][0] >= 0.75:
            resolved[label] = scored[0][1]
    print(f'\nresolved {len(resolved)}/{len(targets)}')
    print('\nC defines for 4.1.0:')
    for label, rva in resolved.items():
        print(f'#define {label} 0x{rva:x}')
    return 0 if len(resolved) == len(targets) else 2


if __name__ == '__main__':
    sys.exit(main())
