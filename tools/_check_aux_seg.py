"""Check where the resolved 4.1.0 aux RVAs actually land.

The metadata record table returned RVAs like 0x42ebba0 for Sprite.get_texture. The
157 equivalents (0x3c68d60 etc.) sit inside the executable segment, so if the 4.1.0
values land in a data segment then the record's codeRva is not a code address for
these methods - which would mean the metadata table cannot resolve Unity engine
helpers on this build, and the shape comparison was never going to work.
"""
import struct

SEG_157 = r'I:\dsh\work\audit\libil2cpp.so'
SEG_410 = r'I:\dsh\work\audit410\libil2cpp.so'

RESOLVED_410 = {
    'get_texture': 0x42EBBA0,
    'get_rect': 0x42F47D0,
    'get_pivot': 0x42E8760,
    'get_pixelsPerUnit': 0x42EBBA0,
    'Sprite.Create': 0x430B470,
    'get_sprite': 0x42D9F70,
    'set_sprite': 0x42F47D0,
    'Object.op_Implicit': 0x42DBD80,
    'Texture2D..ctor': 0x42E3270,
    'LoadImage': 0x42FBB00,
    'LoadImage.alt': 0x3F42138,
}

REF_157 = {
    'get_texture': 0x3C68D60,
    'get_rect': 0x3C68C28,
    'get_pivot': 0x3C68F14,
    'get_pixelsPerUnit': 0x3C68E60,
    'Texture2D.get_width': 0x3C3E7D0,
    'Sprite.Create': 0x3C69E2C,
    'get_sprite': 0x3C67C7C,
    'Object.op_Implicit': 0x3C5D6B0,
    'Texture2D..ctor': 0x3C411B8,
    'LoadImage': 0x3C8639C,
}


def segments(path):
    data = open(path, 'rb').read()
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
            out.append((p_va, p_off, p_fsz, bool(p_flags & 1)))
    return out


def locate(segs, rva):
    for va, off, size, is_exec in segs:
        if va <= rva < va + size:
            return ('EXEC' if is_exec else 'DATA'), off + (rva - va)
    return 'OUTSIDE', None


def report(label, path, values):
    segs = segments(path)
    print(f'=== {label} ===')
    for va, off, size, is_exec in segs:
        print(f'  segment va=0x{va:08x} off=0x{off:08x} size=0x{size:08x} '
              f'{"exec" if is_exec else "data"}')
    print()
    for name, rva in values.items():
        kind, offset = locate(segs, rva)
        print(f'  0x{rva:<9x} {name:<22} -> {kind}')
    print()


def main():
    report('157 (reference, known good)', SEG_157, REF_157)
    report('4.1.0 (from the metadata record table)', SEG_410, RESOLVED_410)


if __name__ == '__main__':
    main()
