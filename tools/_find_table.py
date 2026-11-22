"""Locate the method-code record table by searching for known RVAs.

The metadata lookup resolves (declaringType, methodName) correctly - it found exactly
the four ClickControl/DragControl/FlickControl/HoldControl NoteMove methods - but the
assumed record-table base produced addresses that are not the audited code RVAs. So
the table is somewhere else, or has a different stride.

Rather than guess again, this probe searches the 157 image for the four audited RVAs
as little-endian 64-bit values and reports where they appear. If they appear at a
regular spacing, that spacing is the record stride and the first hit is the record for
that method - which then calibrates the table for 4.1.0.
"""
import struct

SO = r'I:\dsh\work\audit\libil2cpp.so'
META = r'I:\dsh\work\audit\global-metadata.dat'

# Audited 157 RVAs, in ascending order.
KNOWN = {
    'skinClickControl$$NoteMove': 0x1D81E98,
    'skinDragControl$$NoteMove': 0x1D8267C,
    'skinFlickControl$$NoteMove': 0x1D82C7C,
    'skinHoldControl$$NoteMove': 0x1D8334C,
    'click': 0x1D8214C,
    'record': 0x1CB9828,
}


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
        segs.append({'type': t, 'off': off, 'va': va, 'fsz': fsz, 'flags': fl})
    return data, segs


def main():
    data, segs = load(SO)
    print(f'image: {len(data)} bytes, {len(segs)} program headers')

    print('\n=== where does each audited RVA appear as a u64? ===')
    occurrences = {}
    for name, rva in KNOWN.items():
        needle = struct.pack('<Q', rva)
        hits = []
        start = 0
        while True:
            found = data.find(needle, start)
            if found < 0:
                break
            hits.append(found)
            start = found + 1
            if len(hits) > 12:
                break
        occurrences[name] = hits
        print(f'  {name:<30} 0x{rva:<10x} appears at {len(hits)} place(s): '
              f'{[hex(h) for h in hits[:6]]}')

    # The record table should contain all of them at one constant stride. Try each
    # candidate base from the first method's occurrences.
    print('\n=== testing each candidate table base ===')
    first = occurrences['skinClickControl$$NoteMove']
    for base in first:
        # Try strides that could plausibly fit (record entries carry a pointer plus
        # flags, so 8/16/24/32 are the realistic sizes).
        for stride in (8, 16, 24, 32, 40):
            ok = 0
            for name, rva in KNOWN.items():
                offset = base + (rva - KNOWN['skinClickControl$$NoteMove'])
                del offset
            # Simpler: check whether every known RVA sits at base + k*stride for the
            # same set of integer k values. That only holds if the table is ordered by
            # something other than RVA, so instead test "all present within a window".
            window = data[base:base + 0x200000]
            present = sum(1 for rva in KNOWN.values() if struct.pack('<Q', rva) in window)
            if present == len(KNOWN):
                print(f'  base 0x{base:x}: all {present} RVAs within 2 MB after it '
                      f'(stride candidate {stride})')
                break

    # Report the neighbourhood of the first occurrence so the record layout is visible.
    print('\n=== record neighbourhood at the first hit ===')
    base = first[0]
    for delta in range(-32, 96, 8):
        if base + delta < 0 or base + delta + 8 > len(data):
            continue
        value = struct.unpack_from('<Q', data, base + delta)[0]
        print(f'  {base + delta:+6d} (0x{base + delta:08x})  0x{value:016x}  {value}')


if __name__ == '__main__':
    main()
