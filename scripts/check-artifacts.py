#!/usr/bin/env python3
"""Validate provenance hashes, bundled model and absence of credentials in shareable sources."""
import pathlib, json, hashlib, re, struct, zipfile
root=pathlib.Path(__file__).resolve().parents[1]
manifest=json.loads((root/'docs/resource-hashes.json').read_text())
for name, expected in manifest['hashes'].items():
    assert hashlib.sha256((root/name).read_bytes()).hexdigest() == expected, 'Resource changed: '+name
ignored={'.tooling','.gradle','.kotlin','deliverables','build','node_modules','output','.git','test-results'}
for path in root.rglob('*'):
    if not path.is_file() or ignored.intersection(path.relative_to(root).parts): continue
    assert path.suffix not in {'.jks','.keystore','.pem','.p12','.pfx','.key'}, 'Private key in shareable source: '+str(path)
    if path.suffix in {'.kt','.js','.json','.md','.kts','.xml','.properties','.mjs'}:
        text=path.read_text(errors='replace')
        assert not re.search(r'AIza[\w-]{30,}',text), 'Credential pattern: '+str(path)
        assert not re.search(r'sk-(?:proj-)?[A-Za-z0-9_-]{35,}',text), 'Credential pattern: '+str(path)
apk=root/'app/build/outputs/apk/debug/app-debug.apk'
if apk.exists():
    with zipfile.ZipFile(apk) as z:
        prefix='app/src/main/assets/'
        for name,expected in manifest['hashes'].items():
            if name.startswith(prefix):
                packaged='assets/'+name[len(prefix):]
                assert hashlib.sha256(z.read(packaged)).hexdigest() == expected, 'Stale APK asset: '+packaged
        for name in z.namelist():
            if not name.startswith('lib/arm64-v8a/') or not name.endswith('.so'): continue
            b=z.read(name); off=struct.unpack_from('<Q',b,32)[0]; size,count=struct.unpack_from('<HH',b,54)
            for i in range(count):
                p=off+i*size
                if struct.unpack_from('<I',b,p)[0] == 1:
                    assert struct.unpack_from('<Q',b,p+48)[0] >= 16384, 'Native library is not 16 KB aligned: '+name
print(f"PASS: {len(manifest['hashes'])} resource hashes, source credential scan, ARM64 ELF alignment")
