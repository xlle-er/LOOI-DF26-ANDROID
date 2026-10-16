#!/usr/bin/env python3
"""Package shareable sources without caches, local settings, signing keys or emulator state."""
from pathlib import Path
from zipfile import ZipFile, ZIP_DEFLATED
root=Path(__file__).resolve().parents[1]
output=root/'deliverables';output.mkdir(exist_ok=True)
ignored={'.tooling','.gradle','.kotlin','.git','.idea','node_modules','build','output','test-results','deliverables','__pycache__'}
archive=output/'LOOI-DF26-Android-source.zip'
with ZipFile(archive,'w',ZIP_DEFLATED) as z:
    for path in sorted(root.rglob('*')):
        if not path.is_file() or ignored.intersection(path.relative_to(root).parts): continue
        if path.name in {'local.properties','.DS_Store'} or path.name.startswith('.env'): continue
        if path.suffix in {'.jks','.keystore','.pem','.p12','.key','.pfx','.apk','.aab','.log','.hprof'}: continue
        z.write(path,Path(root.name)/path.relative_to(root))
print(archive.name,archive.stat().st_size,'bytes')
