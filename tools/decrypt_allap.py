"""Decrypt the game's local PlayerPrefs entries to inspect what the offline build ships.

RksEditor.encrypt/decrypt is AES-256-CBC with a key and IV derived by a small custom
transform. Reproducing it here lets us read assets/offline/all-ap.xml and see exactly
which songs, collections and avatars a build carries - the thing that has to be
regenerated for 4.1.0 rather than assumed.
"""
import base64
import re
import sys
import urllib.parse
import zipfile

from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes

KEY_SRC = 'Phigros.enc.j57vnvr8wlZssXM7eWpa'
IV_SRC = 'Q4zHm5vUEMJJ3iS9'


def derive(text: str) -> bytes:
    """Mirror of RksEditor.derive: out[i] = raw[(i+1) % n] ^ (reverse_bits(raw[i]) >> 24)."""
    raw = text.encode('utf-8')
    n = len(raw)
    out = bytearray(n)
    for i in range(n):
        v = raw[i]
        # Integer.reverse(v) >>> 24 == reverse the low 8 bits
        rev = int(f'{v:08b}'[::-1], 2)
        out[i] = (raw[(i + 1) % n] ^ rev) & 0xFF
    return bytes(out)


KEY = derive(KEY_SRC)
IV = derive(IV_SRC)


def decrypt(value: str) -> str:
    raw = base64.b64decode(urllib.parse.unquote(value))
    cipher = Cipher(algorithms.AES(KEY), modes.CBC(IV))
    d = cipher.decryptor()
    data = d.update(raw) + d.finalize()
    pad = data[-1]
    return data[:-pad].decode('utf-8')


def main() -> int:
    try:
        sys.stdout.reconfigure(encoding='utf-8', errors='replace')
    except Exception:
        pass

    apk = sys.argv[1] if len(sys.argv) > 1 else r'I:\dsh\work\release\v183\Phigros-offline-v183.apk'
    with zipfile.ZipFile(apk) as z:
        xml = z.read('assets/offline/all-ap.xml').decode('utf-8', 'replace')

    entries = re.findall(r'<string\b[^>]*\bname="([^"]+)"[^>]*>(.*?)</string>', xml, re.DOTALL)
    print(f'{apk}')
    print(f'entries: {len(entries)}')

    kinds = {}
    songs = set()
    records = 0
    for enc_name, _enc_val in entries:
        try:
            name = decrypt(enc_name)
        except Exception:
            kinds['<undecryptable>'] = kinds.get('<undecryptable>', 0) + 1
            continue
        if '.Record.' in name:
            records += 1
            parts = name.split('.')
            if parts:
                songs.add(parts[0])
        else:
            # Bucket by the leading token so the shape is visible.
            head = name.split('.')[0][:28]
            kinds[head] = kinds.get(head, 0) + 1

    print(f'record entries: {records}   distinct song ids: {len(songs)}')
    print('non-record entry buckets:')
    for k, v in sorted(kinds.items(), key=lambda kv: -kv[1])[:24]:
        print(f'   {v:6d}  {k}')
    print()
    print('sample song ids:', sorted(songs)[:8])
    return 0


if __name__ == '__main__':
    sys.exit(main())
