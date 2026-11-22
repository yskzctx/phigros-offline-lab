"""Derive the chart catalog from a game's own PlayerPrefs, not from bundle scanning.

The bundle scan finds nothing because the Addressables payloads do not contain plaintext
chart keys. But the game itself writes one encrypted PlayerPrefs entry per chart, of the
form `<SongKey>.Record.<DIFFICULTY>` - and the offline build already ships a decrypted
dump of exactly that in assets/offline/all-ap.xml.

So the authoritative catalog for a build is: decrypt its all-ap.xml, take every
`*.Record.*` key, and pair it with the difficulty constant from chart-catalog.tsv where
known. This reports what a build actually carries, which is the question that matters
for "does this include the new songs".
"""
import base64
import re
import sys
import urllib.parse
import zipfile

from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes

KEY_SRC = 'Phigros.enc.j57vnvr8wlZssXM7eWpa'
IV_SRC = 'Q4zHm5vUEMJJ3iS9'
ENTRY = re.compile(r'<string\b[^>]*\bname="([^"]+)"[^>]*>(.*?)</string>', re.DOTALL)


def derive(text: str) -> bytes:
    raw = text.encode('utf-8')
    n = len(raw)
    out = bytearray(n)
    for i in range(n):
        v = raw[i]
        rev = int(f'{v:08b}'[::-1], 2)
        out[i] = (raw[(i + 1) % n] ^ rev) & 0xFF
    return bytes(out)


KEY = derive(KEY_SRC)
IV = derive(IV_SRC)


def decrypt(value: str) -> str:
    raw = base64.b64decode(urllib.parse.unquote(value))
    d = Cipher(algorithms.AES(KEY), modes.CBC(IV)).decryptor()
    data = d.update(raw) + d.finalize()
    return data[:-data[-1]].decode('utf-8')


def catalog_from_allap(xml: str) -> set[str]:
    """Every `<Song>.<Artist>.<idx>.Record.<DIFF>` key present in the dump."""
    keys = set()
    for enc_name, _ in ENTRY.findall(xml):
        try:
            name = decrypt(enc_name)
        except Exception:
            continue
        if '.Record.' in name:
            keys.add(name)
    return keys


def main() -> int:
    try:
        sys.stdout.reconfigure(encoding='utf-8', errors='replace')
    except Exception:
        pass

    apks = sys.argv[1:] or [r'I:\dsh\work\release\v183\Phigros-offline-v183.apk']
    for apk in apks:
        with zipfile.ZipFile(apk) as z:
            if 'assets/offline/all-ap.xml' not in z.namelist():
                print(f'{apk}: no all-ap.xml')
                continue
            xml = z.read('assets/offline/all-ap.xml').decode('utf-8', 'replace')
        keys = catalog_from_allap(xml)
        songs = {k.split('.')[0] for k in keys}
        diffs = {}
        for k in keys:
            d = k.rsplit('.', 1)[-1]
            diffs[d] = diffs.get(d, 0) + 1
        print(f'{apk}')
        print(f'  chart keys: {len(keys)}   distinct songs: {len(songs)}')
        print(f'  by difficulty: {dict(sorted(diffs.items()))}')
        print(f'  sample: {sorted(keys)[:3]}')
    return 0


if __name__ == '__main__':
    sys.exit(main())
