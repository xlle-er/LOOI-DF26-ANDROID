#!/usr/bin/env python3
"""Install official Android SDK command-line tools in ignored .tooling (no global edits)."""
import pathlib, urllib.request, zipfile, io, xml.etree.ElementTree as ET, hashlib, os, subprocess
root = pathlib.Path(__file__).resolve().parents[1]
tool = root / '.tooling'; tool.mkdir(exist_ok=True)
xml = subprocess.check_output(['curl', '-fsSL', '--retry', '3', 'https://dl.google.com/android/repository/repository2-3.xml'])
r = ET.fromstring(xml)
package = next(p for p in r.findall('remotePackage') if p.attrib['path'] == 'cmdline-tools;latest')
archives = package.findall('archives/archive')
a = next(a for a in archives if a.findtext('host-os') == 'macosx' and ('arm64' in a.findtext('complete/url')))
sdk = tool / 'sdk'; dest = sdk / 'cmdline-tools' / 'latest'
if not dest.exists():
    blob = subprocess.check_output(['curl', '-fsSL', '--retry', '3', 'https://dl.google.com/android/repository/' + a.findtext('complete/url')])
    assert hashlib.sha1(blob).hexdigest() == a.findtext('complete/checksum')
    with zipfile.ZipFile(io.BytesIO(blob)) as z:
        z.extractall(tool / 'unpack')
    dest.parent.mkdir(parents=True, exist_ok=True)
    (tool / 'unpack' / 'cmdline-tools').rename(dest)
    for f in (dest / 'bin').iterdir(): f.chmod(0o755)
env = dict(os.environ, ANDROID_USER_HOME=str(tool/'android-user'))
if not env.get('JAVA_HOME'):
    raise SystemExit('Set JAVA_HOME to your JDK 21 installation before running this script.')
manager = str(dest/'bin'/'sdkmanager')
subprocess.run([manager, '--sdk_root='+str(sdk), '--licenses'], env=env, check=True)
subprocess.run([manager, '--sdk_root='+str(sdk), 'platforms;android-36', 'build-tools;36.0.0', 'platform-tools'], env=env, check=True)
(root/'local.properties').write_text('sdk.dir='+str(sdk)+'\n')
