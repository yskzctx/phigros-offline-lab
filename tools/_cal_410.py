"""Calibrate the 4.1.0 method-code record table.

The 157 table was located by searching for audited RVAs and reading back to index 0.
The same works for 4.1.0 in reverse: the 4.1.0 hook RVAs are already recovered and
verified, and the metadata lookup gives their method indices, so the table base is
`file_offset(rva) - index * 24`.

This probe does exactly that for several recovered hooks and reports whether they all
agree on one base - which is the calibration check.
"""
import struct

SO = r'I:\dsh\work\audit410\libil2cpp.so'
META = r'I:\dsh\work\audit410\global-metadata.dat'

RECORD = 0x18
METHOD_STRIDE = 0x24
TYPE_STRIDE = 0x58
HEADER_BASE = 0x08
PAIR_STRING = 2
PAIR_METHODS = 5
PAIR_TYPES = 19

# Recovered 4.1.0 hook RVAs (audited) and the metadata identities they correspond to.
TARGETS = [
    ('ClickControl', 'NoteMove', 0x1D846A0),
    ('DragControl', 'NoteMove', 0x1D84E84),
    ('FlickControl', 'NoteMove', 0x1D85484),
    ('HoldControl', 'NoteMove', 0x1D85B54),
]


def main():
    meta = open(META, 'rb').read()
    so = open(SO, 'rb').read()

    strings, _ = struct.unpack_from('<ii', meta, HEADER_BASE + PAIR_STRING * 8)
    methods, method_size = struct.unpack_from('<ii', meta, HEADER_BASE + PAIR_METHODS * 8)
    types, type_size = struct.unpack_from('<ii', meta, HEADER_BASE + PAIR_TYPES * 8)
    method_count = method_size // METHOD_STRIDE
    type_count = type_size // TYPE_STRIDE
    print(f'4.1.0 metadata: {method_count} methods, {type_count} types')

    def string(index):
        start = strings + index
        end = meta.find(b'\x00', start, start + 512)
        return meta[start:end].decode('utf-8', 'replace') if end > 0 else ''

    def type_name(index):
        if not 0 <= index < type_count:
            return ''
        base = types + index * TYPE_STRIDE
        name_i, ns_i = struct.unpack_from('<ii', meta, base)
        name, namespace = string(name_i), string(ns_i)
        return f'{namespace}.{name}' if namespace else name

    def find(type_hint, method):
        hits = []
        for index in range(method_count):
            base = methods + index * METHOD_STRIDE
            name_i, declaring = struct.unpack_from('<Ii', meta, base)
            if string(name_i) != method:
                continue
            if type_hint in type_name(declaring):
                hits.append(index)
        return hits

    print('\n=== indices and file offsets ===')
    bases = []
    for type_hint, method, rva in TARGETS:
        indices = find(type_hint, method)
        if not indices:
            print(f'  {type_hint}$${method}: not found')
            continue
        needle = struct.pack('<Q', rva)
        offset = so.find(needle)
        if offset < 0:
            print(f'  {type_hint}$${method}: rva 0x{rva:x} not found in image')
            continue
        # The RVA sits 8 bytes into its record (after the flags word).
        record_start = offset - 8
        for index in indices:
            base = record_start - index * RECORD
            bases.append(base)
            print(f'  {type_hint:<14}$${method:<10} idx={index:<6} rva=0x{rva:<9x} '
                  f'record=0x{record_start:x} -> base 0x{base:x}')

    print()
    if not bases:
        raise SystemExit('no calibration produced')
    unique = sorted(set(bases))
    print(f'distinct candidate bases: {[hex(b) for b in unique]}')
    if len(unique) == 1:
        base = unique[0]
        print(f'\nCALIBRATED: 161 base = 0x{base:x}')
        # Verify: read the flags word of a few records to confirm the layout.
        print('\nlayout spot-check (flags should be 0x403):')
        for type_hint, method, rva in TARGETS:
            for index in find(type_hint, method):
                slot = base + index * RECORD
                flags, code = struct.unpack_from('<QQ', so, slot)
                mark = 'OK' if code == rva else 'MISMATCH'
                print(f'  idx={index:<6} flags=0x{flags:x} code=0x{code:x} {mark}')
                break
    else:
        print('bases disagree; the record layout or the index lookup is wrong')


if __name__ == '__main__':
    main()
