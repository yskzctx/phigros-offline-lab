import hashlib,importlib.util,tempfile
from pathlib import Path
tool=Path(__file__).resolve().parents[2]/'tools/verify_release173_downloads.py'
spec=importlib.util.spec_from_file_location('check_download',tool);m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
with tempfile.TemporaryDirectory()as folder:
 p=Path(folder)/'volume';p.write_bytes(b'valid download')
 expected=hashlib.sha256(p.read_bytes()).hexdigest();assert m.verify(p,14,expected)['status']=='ok'
 p.write_bytes(b'wrong download');assert p.stat().st_size==14
 assert m.verify(p,14,expected)['status']=='hash_mismatch'
 p.write_bytes(b'short');assert m.verify(p,14,expected)['status']=='size_mismatch'
 p.unlink();assert m.verify(p,14,expected)['status']=='missing'
print('PASS: correct download, same-size corruption, truncated and missing volumes distinguished')
