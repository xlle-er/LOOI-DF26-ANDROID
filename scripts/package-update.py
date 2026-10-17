#!/usr/bin/env python3
"""Create the public self-update manifest from APK metadata. Never publish or sign."""
import argparse,hashlib,json,re,subprocess
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('apk',type=Path);p.add_argument('--aapt',required=True,type=Path);p.add_argument('--output',type=Path,required=True);args=p.parse_args()
metadata=subprocess.check_output([str(args.aapt),'dump','badging',str(args.apk)],text=True)
match=re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'",metadata)
if not match or match[1]!='app.looi.companion':raise SystemExit('Unexpected APK package metadata')
version=match[3];name=f'LOOI-Android-{version}-debug.apk';args.output.mkdir(parents=True,exist_ok=True)
package=args.output/name;package.write_bytes(args.apk.read_bytes())
manifest={'packageName':match[1],'versionCode':int(match[2]),'versionName':version,'url':f'https://github.com/xlle-er/LOOI-DF26-ANDROID/releases/download/v{version}/{name}','size':package.stat().st_size,'sha256':hashlib.sha256(package.read_bytes()).hexdigest()}
(args.output/'looi-android-update.json').write_text(json.dumps(manifest,indent=2)+'\n')
print(f'Packaged Android {version}, versionCode {match[2]}; nothing published')
